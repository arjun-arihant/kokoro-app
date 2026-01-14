package com.kokorotts.android

import android.content.Context
import android.util.Log
import org.jetbrains.bio.npy.NpyArray
import org.jetbrains.bio.npy.NpyFile
import java.io.File
import java.io.FileOutputStream

/**
 * Voice style data class containing name and embedding.
 */
data class VoiceStyle(
    val name: String,
    val displayName: String,
    val locale: String,
    val gender: String
)

/**
 * Manages voice styles and embeddings for the Kokoro TTS engine.
 */
class VoiceManager(private val context: Context) {

    companion object {
        private const val TAG = "VoiceManager"
        private const val EMBEDDING_DIM = 256
        private const val STYLE_FRAMES = 511
    }

    // Voice definitions
    private val voiceDefinitions = listOf(
        VoiceStyle("af", "Default Female (US)", "en_US", "female"),
        VoiceStyle("af_bella", "Bella (US)", "en_US", "female"),
        VoiceStyle("af_nicole", "Nicole (US)", "en_US", "female"),
        VoiceStyle("af_sarah", "Sarah (US)", "en_US", "female"),
        VoiceStyle("af_sky", "Sky (US)", "en_US", "female"),
        VoiceStyle("am_adam", "Adam (US)", "en_US", "male"),
        VoiceStyle("am_michael", "Michael (US)", "en_US", "male"),
        VoiceStyle("bf_emma", "Emma (UK)", "en_GB", "female"),
        VoiceStyle("bf_isabella", "Isabella (UK)", "en_GB", "female"),
        VoiceStyle("bm_george", "George (UK)", "en_GB", "male"),
        VoiceStyle("bm_lewis", "Lewis (UK)", "en_GB", "male")
    )

    private val voiceMap = voiceDefinitions.associateBy { it.name }
    private val embeddingCache = mutableMapOf<String, Array<FloatArray>>()

    init {
        Log.d(TAG, "VoiceManager initialized with ${voiceDefinitions.size} voices")
    }

    /**
     * Get list of available voice names.
     */
    fun getAvailableVoices(): List<String> = voiceDefinitions.map { it.name }

    /**
     * Get voice definitions.
     */
    fun getVoiceDefinitions(): List<VoiceStyle> = voiceDefinitions

    /**
     * Check if a voice exists.
     */
    fun hasVoice(name: String): Boolean = voiceMap.containsKey(name)

    /**
     * Get voice style info.
     */
    fun getVoiceInfo(name: String): VoiceStyle? = voiceMap[name]

    /**
     * Get voice embedding for synthesis.
     * 
     * @param name Voice name
     * @param frameIndex Style frame index (0-510), default 0
     * @return Voice embedding array [1, 256]
     */
    fun getVoiceEmbedding(name: String, frameIndex: Int = 0): Array<FloatArray> {
        // Check cache first
        val cacheKey = "${name}_$frameIndex"
        embeddingCache[cacheKey]?.let { return it }

        // Load embedding from NPY file
        val embedding = loadEmbeddingFromNpy(name, frameIndex)
        embeddingCache[cacheKey] = embedding
        
        return embedding
    }

    private fun loadEmbeddingFromNpy(name: String, frameIndex: Int): Array<FloatArray> {
        try {
            // Try to find NPY file in raw resources
            val resourceId = context.resources.getIdentifier(
                name, "raw", context.packageName
            )

            if (resourceId != 0) {
                // Copy resource to temp file for NPY library
                val tempFile = File.createTempFile("voice_$name", ".npy", context.cacheDir)
                tempFile.deleteOnExit()

                context.resources.openRawResource(resourceId).use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Load NPY file
                val npyArray: NpyArray = NpyFile.read(tempFile.toPath())
                
                // Validate shape [511, 1, 256]
                val shape = npyArray.shape
                if (shape.size == 3 && shape[0] == 511 && shape[1] == 1 && shape[2] == 256) {
                    val floatArray = npyArray.asFloatArray()
                    
                    // Extract embedding at frame index
                    val validIndex = frameIndex.coerceIn(0, 510)
                    val embedding = Array(1) { FloatArray(EMBEDDING_DIM) }
                    
                    for (i in 0 until EMBEDDING_DIM) {
                        embedding[0][i] = floatArray[validIndex * EMBEDDING_DIM + i]
                    }
                    
                    Log.d(TAG, "Loaded voice embedding: $name (frame $validIndex)")
                    return embedding
                } else {
                    Log.e(TAG, "Invalid NPY shape for $name: ${shape.contentToString()}")
                }
            } else {
                Log.w(TAG, "Voice NPY not found: $name")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load voice embedding: $name", e)
        }

        // Return default embedding if loading fails
        Log.w(TAG, "Using default embedding for $name")
        return getDefaultEmbedding()
    }

    /**
     * Create a default neutral embedding.
     */
    private fun getDefaultEmbedding(): Array<FloatArray> {
        return Array(1) { FloatArray(EMBEDDING_DIM) { 0.0f } }
    }

    /**
     * Mix two voice embeddings.
     * 
     * @param voice1 First voice name
     * @param voice2 Second voice name
     * @param weight Weight for voice1 (0.0 to 1.0)
     * @return Mixed embedding
     */
    fun mixVoices(voice1: String, voice2: String, weight: Float): Array<FloatArray> {
        val emb1 = getVoiceEmbedding(voice1)
        val emb2 = getVoiceEmbedding(voice2)
        
        val mixed = Array(1) { FloatArray(EMBEDDING_DIM) }
        val w = weight.coerceIn(0f, 1f)
        
        for (i in 0 until EMBEDDING_DIM) {
            mixed[0][i] = emb1[0][i] * w + emb2[0][i] * (1 - w)
        }
        
        return mixed
    }
}
