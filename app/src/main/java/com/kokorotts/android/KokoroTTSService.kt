package com.kokorotts.android

import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import android.util.Log
import kotlinx.coroutines.*
import java.util.*

/**
 * Enhanced Kokoro TTS Service with improved error handling and memory management.
 */
class KokoroTTSService : TextToSpeechService() {

    companion object {
        private const val TAG = "KokoroTTSService"
        private const val SAMPLE_RATE = 24000
        private const val INITIALIZATION_TIMEOUT_MS = 30000L
        private const val SYNTHESIS_TIMEOUT_MS = 60000L
        private const val MAX_TEXT_LENGTH = 10000
    }

    // Core components
    private var kokoroEngine: KokoroEngine? = null
    private var phonemeConverter: PhonemeConverter? = null
    private var voiceManager: VoiceManager? = null
    
    // Use CoroutineManager for structured concurrency
    private lateinit var coroutineManager: CoroutineManager
    
    // State flags
    @Volatile
    private var isInitialized = false
    
    @Volatile
    private var initializationError: String? = null
    
    private var currentSynthesisJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        
        coroutineManager = CoroutineManager()
        
        // Start initialization
        coroutineManager.launch("initialization") {
            val result = initializeComponents()
            
            result.onSuccess {
                isInitialized = true
                Log.i(TAG, "Service initialized successfully")
            }.onError { error, code ->
                initializationError = error.message
                Log.e(TAG, "Service initialization failed: $code - ${error.message}")
            }
        }
    }
    
    /**
     * Initialize all components with proper error handling.
     */
    private suspend fun initializeComponents(): TTSResult<Unit> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Initializing components...")
        
        try {
            // Check memory before loading heavy components
            val memStatus = MemoryManager.getMemoryStatus()
            if (memStatus.isLow) {
                Log.w(TAG, "Low memory detected: ${memStatus.freeMB}MB free")
                MemoryManager.requestGarbageCollection()
            }
            
            // Initialize voice manager first (lightweight)
            voiceManager = VoiceManager(this@KokoroTTSService)
            Log.d(TAG, "VoiceManager initialized")
            
            // Initialize phoneme converter
            phonemeConverter = PhonemeConverter(this@KokoroTTSService)
            Log.d(TAG, "PhonemeConverter initialized")
            
            // Initialize ONNX engine (heavyweight) with memory check
            if (!MemoryManager.hasEnoughMemory(150)) { // 150MB for model
                return@withContext TTSResult.Error(
                    OutOfMemoryError("Insufficient memory to load ONNX model"),
                    ErrorCode.OUT_OF_MEMORY
                )
            }
            
            kokoroEngine = KokoroEngine(this@KokoroTTSService)
            
            if (!kokoroEngine!!.isReady()) {
                return@withContext TTSResult.Error(
                    EngineNotInitializedException("Engine failed to initialize"),
                    ErrorCode.ENGINE_NOT_INITIALIZED
                )
            }
            
            Log.d(TAG, "KokoroEngine initialized")
            
            // Log memory after initialization
            MemoryManager.logMemoryStatus()
            
            TTSResult.Success(Unit)
            
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory during initialization", e)
            TTSResult.Error(e, ErrorCode.OUT_OF_MEMORY)
        } catch (e: Exception) {
            Log.e(TAG, "Component initialization failed", e)
            TTSResult.Error(e, ErrorCode.ENGINE_NOT_INITIALIZED)
        }
    }

    override fun onGetLanguage(): Array<String> {
        return arrayOf("eng", "USA", "")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return when {
            lang == null -> TextToSpeech.LANG_NOT_SUPPORTED
            lang.equals("eng", ignoreCase = true) || 
            lang.equals("en", ignoreCase = true) -> {
                when {
                    country.equals("USA", ignoreCase = true) || 
                    country.equals("US", ignoreCase = true) -> 
                        TextToSpeech.LANG_COUNTRY_AVAILABLE
                    country.equals("GBR", ignoreCase = true) || 
                    country.equals("GB", ignoreCase = true) -> 
                        TextToSpeech.LANG_COUNTRY_AVAILABLE
                    country.isNullOrEmpty() -> TextToSpeech.LANG_AVAILABLE
                    else -> TextToSpeech.LANG_AVAILABLE
                }
            }
            else -> TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(lang: String?, country: String?, variant: String?): Int {
        return onIsLanguageAvailable(lang, country, variant)
    }

    override fun onGetFeaturesForLanguage(
        lang: String?,
        country: String?,
        variant: String?
    ): Set<String> {
        return setOf(TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS)
    }

    override fun onGetVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()
        val vm = voiceManager
        
        if (vm == null) {
            Log.w(TAG, "VoiceManager not initialized, returning empty voice list")
            return voices
        }
        
        for (voiceName in vm.getAvailableVoices()) {
            val locale = if (voiceName.startsWith("b")) Locale.UK else Locale.US
            
            voices.add(
                Voice(
                    voiceName,
                    locale,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL,
                    false,
                    setOf(Voice.LATENCY_VERY_LOW, Voice.QUALITY_VERY_HIGH)
                )
            )
        }
        
        return voices
    }

    override fun onIsValidVoiceName(voiceName: String?): Int {
        if (voiceName == null) return TextToSpeech.ERROR
        val vm = voiceManager ?: return TextToSpeech.ERROR
        
        return if (vm.hasVoice(voiceName)) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }
    }

    override fun onLoadVoice(voiceName: String?): Int {
        return onIsValidVoiceName(voiceName)
    }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?
    ): String {
        return when {
            country.equals("GB", ignoreCase = true) || 
            country.equals("GBR", ignoreCase = true) -> "bf_emma"
            else -> "af_sky"
        }
    }

    override fun onSynthesizeText(request: SynthesisRequest, callback: SynthesisCallback) {
        val text = request.charSequenceText?.toString() ?: ""

        // Validate input
        if (text.isEmpty()) {
            Log.w(TAG, "Empty text received")
            callback.done()
            return
        }
        
        if (text.length > MAX_TEXT_LENGTH) {
            Log.w(TAG, "Text too long (${text.length} chars), truncating to $MAX_TEXT_LENGTH")
        }
        
        val truncatedText = text.take(MAX_TEXT_LENGTH)

        // Wait for initialization
        if (!isInitialized) {
            val waitResult = runBlocking {
                withTimeoutOrNull(INITIALIZATION_TIMEOUT_MS) {
                    while (!isInitialized && initializationError == null) {
                        delay(100)
                    }
                }
            }
            
            if (waitResult == null) {
                Log.e(TAG, "Initialization timeout")
                callback.error()
                return
            }
            
            if (initializationError != null) {
                Log.e(TAG, "Cannot synthesize: initialization failed - $initializationError")
                callback.error()
                return
            }
        }

        val engine = kokoroEngine
        val converter = phonemeConverter
        val vm = voiceManager

        if (engine == null || converter == null || vm == null) {
            Log.e(TAG, "Components not initialized")
            callback.error()
            return
        }

        // Start synthesis
        val result = runBlocking {
            synthesizeWithTimeout(
                text = truncatedText,
                voiceName = request.voiceName ?: "af_sky",
                speechRate = request.speechRate,
                engine = engine,
                converter = converter,
                voiceManager = vm,
                callback = callback
            )
        }
        
        result.onError { error, code ->
            Log.e(TAG, "Synthesis failed: $code - ${error.message}")
        }
    }

    /**
     * Synthesize text with timeout and proper cleanup.
     */
    private suspend fun synthesizeWithTimeout(
        text: String,
        voiceName: String,
        speechRate: Int,
        engine: KokoroEngine,
        converter: PhonemeConverter,
        voiceManager: VoiceManager,
        callback: SynthesisCallback
    ): TTSResult<Unit> = withTimeoutOrNull(SYNTHESIS_TIMEOUT_MS) {
        synthesizeInternal(
            text = text,
            voiceName = voiceName,
            speechRate = speechRate,
            engine = engine,
            converter = converter,
            voiceManager = voiceManager,
            callback = callback
        )
    } ?: TTSResult.Error(
        TimeoutException("Synthesis timeout"),
        ErrorCode.TIMEOUT
    )

    /**
     * Internal synthesis implementation.
     */
    private suspend fun synthesizeInternal(
        text: String,
        voiceName: String,
        speechRate: Int,
        engine: KokoroEngine,
        converter: PhonemeConverter,
        voiceManager: VoiceManager,
        callback: SynthesisCallback
    ): TTSResult<Unit> {
        return try {
            // Get voice embedding
            val voiceEmbedding = voiceManager.getVoiceEmbedding(voiceName)
                ?: return TTSResult.Error(
                    VoiceNotFoundException(voiceName),
                    ErrorCode.VOICE_NOT_FOUND
                )

            // Calculate speed
            val speed = when {
                speechRate in 50..400 -> speechRate / 100f
                else -> 1.0f
            }

            // Start audio
            callback.start(SAMPLE_RATE, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

            // Process by sentences
            val iterator = java.text.BreakIterator.getSentenceInstance(java.util.Locale.US)
            iterator.setText(text)
            
            var start = iterator.first()
            var end = iterator.next()
            var allSuccess = true

            while (end != java.text.BreakIterator.DONE) {
                val sentence = text.substring(start, end).trim()
                
                if (sentence.isNotEmpty()) {
                    // Check cancellation
                    if (currentSynthesisJob?.isCancelled == true) {
                        Log.d(TAG, "Synthesis cancelled")
                        return TTSResult.Error(
                            CancellationException(),
                            ErrorCode.CANCELLED
                        )
                    }
                    
                    // Synthesize sentence
                    val success = synthesizeSentence(
                        sentence = sentence,
                        voiceEmbedding = voiceEmbedding,
                        speed = speed,
                        engine = engine,
                        converter = converter,
                        callback = callback
                    )
                    
                    if (!success) {
                        allSuccess = false
                        Log.w(TAG, "Failed to synthesize sentence: $sentence")
                    }
                }
                
                start = end
                end = iterator.next()
            }

            if (allSuccess) {
                callback.done()
                TTSResult.Success(Unit)
            } else {
                callback.error()
                TTSResult.Error(
                    AudioGenerationException("Some sentences failed"),
                    ErrorCode.AUDIO_GENERATION_FAILED
                )
            }

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Synthesis error", e)
            callback.error()
            TTSResult.Error(e, ErrorCode.UNKNOWN)
        }
    }

    /**
     * Synthesize a single sentence.
     */
    private suspend fun synthesizeSentence(
        sentence: String,
        voiceEmbedding: Array<FloatArray>,
        speed: Float,
        engine: KokoroEngine,
        converter: PhonemeConverter,
        callback: SynthesisCallback
    ): Boolean {
        return try {
            // Convert to phonemes
            val phonemes = converter.phonemize(sentence)
            
            // Synthesize audio
            engine.synthesize(
                phonemes = phonemes,
                voiceEmbedding = voiceEmbedding,
                speed = speed
            ) { audioChunk ->
                writeAudioToCallback(callback, audioChunk)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sentence synthesis failed", e)
            false
        }
    }

    private fun writeAudioToCallback(callback: SynthesisCallback, audioData: ByteArray): Boolean {
        var bytesWritten = 0

        while (bytesWritten < audioData.size) {
            val bytesToWrite = minOf(
                callback.maxBufferSize,
                audioData.size - bytesWritten
            )

            val written = callback.audioAvailable(
                audioData,
                bytesWritten,
                bytesToWrite
            )

            if (written < 0) {
                Log.e(TAG, "Error writing audio data: $written")
                return false
            }

            bytesWritten += bytesToWrite
        }

        return true
    }

    override fun onStop() {
        Log.d(TAG, "onStop called")
        currentSynthesisJob?.cancel()
        kokoroEngine?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        
        // Cancel current synthesis
        currentSynthesisJob?.cancel()
        
        // Close coroutine manager
        coroutineManager.close()
        
        // Release resources
        kokoroEngine?.release()
        kokoroEngine = null
        phonemeConverter = null
        voiceManager = null
        isInitialized = false
        
        // Request garbage collection to free native memory
        MemoryManager.requestGarbageCollection()
    }
}
