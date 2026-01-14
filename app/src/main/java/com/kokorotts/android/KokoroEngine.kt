package com.kokorotts.android

import android.content.Context
import android.util.Log
import ai.onnxruntime.*
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Kokoro inference engine - handles ONNX model loading and audio synthesis.
 */
class KokoroEngine(private val context: Context) {

    companion object {
        private const val TAG = "KokoroEngine"
        private const val MODEL_NAME = "kokoro.onnx"
        private const val SAMPLE_RATE = 24000
        private const val MAX_PHONEME_LENGTH = 500
        private const val AUDIO_CHUNK_SIZE = 4096 // Samples per chunk
    }

    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    
    @Volatile
    private var isInitialized = false
    
    @Volatile
    private var isStopped = false

    init {
        initializeModel()
    }

    private fun initializeModel() {
        try {
            Log.d(TAG, "Initializing ONNX Runtime")

            // Create ORT environment
            ortEnvironment = OrtEnvironment.getEnvironment()

            // Get model file
            val modelFile = getModelFile()
            
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: ${modelFile.absolutePath}")
                Log.e(TAG, "Please place kokoro.onnx in app's assets or files directory")
                return
            }

            // Create session options optimized for mobile
            val sessionOptions = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(4)
                setInterOpNumThreads(2)
                
                // Try to use NNAPI for acceleration
                try {
                    addConfigEntry("session.use_nnapi", "1")
                } catch (e: Exception) {
                    Log.w(TAG, "NNAPI not available")
                }
            }

            // Create session
            ortSession = ortEnvironment!!.createSession(modelFile.absolutePath, sessionOptions)

            isInitialized = true
            Log.d(TAG, "Model initialized successfully")

            // Log model info
            logModelInfo()

        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize model", e)
        }
    }

    private fun getModelFile(): File {
        // First check cache directory
        val cacheModelFile = File(context.cacheDir, MODEL_NAME)
        if (cacheModelFile.exists()) {
            Log.d(TAG, "Using model from cache: ${cacheModelFile.absolutePath}")
            return cacheModelFile
        }

        // Try to copy from assets
        try {
            Log.d(TAG, "Copying model from assets")
            context.assets.open(MODEL_NAME).use { input ->
                FileOutputStream(cacheModelFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Model copied from assets to cache successfully")
            return cacheModelFile
        } catch (e: Exception) {
            Log.w(TAG, "Model not in assets: ${e.message}")
        }

        // Try to load from raw resources (like reference implementation)
        try {
            val resourceId = context.resources.getIdentifier(
                "kokoro", "raw", context.packageName
            )
            if (resourceId != 0) {
                Log.d(TAG, "Copying model from raw resources")
                context.resources.openRawResource(resourceId).use { input ->
                    FileOutputStream(cacheModelFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.d(TAG, "Model copied from raw resources to cache successfully")
                return cacheModelFile
            }
        } catch (e: Exception) {
            Log.w(TAG, "Model not in raw resources: ${e.message}")
        }

        // Check external files directory
        val externalModelFile = File(context.getExternalFilesDir(null), MODEL_NAME)
        if (externalModelFile.exists()) {
            Log.d(TAG, "Using model from external files: ${externalModelFile.absolutePath}")
            return externalModelFile
        }

        // Return cache path even if doesn't exist (will fail later with clear error)
        return cacheModelFile
    }

    private fun logModelInfo() {
        try {
            val session = ortSession ?: return
            
            Log.d(TAG, "=== Model Information ===")
            Log.d(TAG, "Input names: ${session.inputNames}")
            Log.d(TAG, "Output names: ${session.outputNames}")

            session.inputInfo.forEach { (name, info) ->
                Log.d(TAG, "Input '$name': ${info.info}")
            }

            session.outputInfo.forEach { (name, info) ->
                Log.d(TAG, "Output '$name': ${info.info}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not log model info", e)
        }
    }

    /**
     * Synthesize audio from phonemes.
     * 
     * @param phonemes IPA phoneme string
     * @param voiceEmbedding Voice style embedding array [1, 256]
     * @param speed Speech rate (0.5 - 2.0)
     * @param onAudioChunk Callback for each audio chunk (PCM 16-bit bytes)
     * @return true if synthesis completed successfully
     */
    suspend fun synthesize(
        phonemes: String,
        voiceEmbedding: Array<FloatArray>,
        speed: Float = 1.0f,
        onAudioChunk: (ByteArray) -> Boolean
    ): Boolean = withContext(Dispatchers.Default) {

        if (!isInitialized) {
            Log.e(TAG, "Engine not initialized")
            return@withContext false
        }

        val session = ortSession ?: return@withContext false
        val env = ortEnvironment ?: return@withContext false

        isStopped = false

        try {
            // Truncate phonemes if too long
            val truncatedPhonemes = if (phonemes.length > MAX_PHONEME_LENGTH) {
                Log.w(TAG, "Phonemes too long, truncating to $MAX_PHONEME_LENGTH")
                phonemes.take(MAX_PHONEME_LENGTH)
            } else {
                phonemes
            }

            // Tokenize phonemes
            val tokens = Tokenizer.tokenize(truncatedPhonemes)
            Log.d(TAG, "Tokenized ${tokens.size} tokens")

            // Prepare input tensors
            val inputs = prepareInputs(env, tokens, voiceEmbedding, speed)

            // Run inference
            Log.d(TAG, "Running inference...")
            val startTime = System.currentTimeMillis()
            
            val outputs = session.run(inputs)
            
            val inferenceTime = System.currentTimeMillis() - startTime
            Log.d(TAG, "Inference completed in ${inferenceTime}ms")

            // Process audio output
            val audioOutput = outputs[0]
            val audioData = when (val value = audioOutput.value) {
                is FloatArray -> value
                is Array<*> -> {
                    // Handle nested array if needed
                    @Suppress("UNCHECKED_CAST")
                    (value as? Array<FloatArray>)?.flatMap { it.toList() }?.toFloatArray()
                        ?: floatArrayOf()
                }
                else -> {
                    Log.e(TAG, "Unexpected output type: ${value?.javaClass}")
                    floatArrayOf()
                }
            }

            if (audioData.isEmpty()) {
                Log.e(TAG, "No audio data generated")
                outputs.close()
                inputs.values.forEach { it.close() }
                return@withContext false
            }

            Log.d(TAG, "Generated ${audioData.size} audio samples")

            // Convert to PCM and send in chunks
            val pcmData = floatToPcm16(audioData)
            var offset = 0
            val chunkBytes = AUDIO_CHUNK_SIZE * 2 // 2 bytes per sample

            while (offset < pcmData.size && !isStopped) {
                val chunkSize = minOf(chunkBytes, pcmData.size - offset)
                val chunk = pcmData.copyOfRange(offset, offset + chunkSize)

                if (!onAudioChunk(chunk)) {
                    Log.d(TAG, "Audio chunk callback returned false, stopping")
                    break
                }

                offset += chunkSize
                
                // Yield to allow cancellation
                yield()
            }

            // Cleanup
            outputs.close()
            inputs.values.forEach { it.close() }

            return@withContext !isStopped

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis error", e)
            return@withContext false
        }
    }

    private fun prepareInputs(
        env: OrtEnvironment,
        tokens: LongArray,
        voiceEmbedding: Array<FloatArray>,
        speed: Float
    ): Map<String, OnnxTensor> {

        // Pad tokens with 0 at start and end
        // Add extra padding at end to prevent audio cutoff
        val paddedTokens = longArrayOf(0L) + tokens.toList() + LongArray(5) { 0L }
        
        // Token tensor: [1, seq_len]
        val tokenTensor = OnnxTensor.createTensor(
            env,
            arrayOf(paddedTokens)
        )

        // Style tensor: [1, 256]
        val styleTensor = OnnxTensor.createTensor(
            env,
            voiceEmbedding
        )

        // Speed tensor: [1]
        val speedTensor = OnnxTensor.createTensor(
            env,
            floatArrayOf(speed)
        )

        return mapOf(
            "tokens" to tokenTensor,
            "style" to styleTensor,
            "speed" to speedTensor
        )
    }

    /**
     * Convert float audio samples [-1, 1] to 16-bit PCM bytes.
     */
    private fun floatToPcm16(floatData: FloatArray): ByteArray {
        val pcmData = ByteArray(floatData.size * 2)
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)

        for (sample in floatData) {
            // Clamp to [-1, 1]
            val clampedSample = sample.coerceIn(-1.0f, 1.0f)
            // Convert to 16-bit PCM
            val pcmSample = (clampedSample * 32767).toInt().toShort()
            buffer.putShort(pcmSample)
        }

        return pcmData
    }

    fun stop() {
        isStopped = true
    }

    fun release() {
        try {
            isStopped = true
            
            ortSession?.close()
            ortSession = null
            
            // Don't close the environment as it's shared
            ortEnvironment = null
            
            isInitialized = false
            Log.d(TAG, "Engine released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing engine", e)
        }
    }
    
    fun isReady(): Boolean = isInitialized
}
