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
 * Kokoro TTS Service - Implements Android's TextToSpeechService
 * to provide system-level TTS integration using the Kokoro-82M model.
 */
class KokoroTTSService : TextToSpeechService() {

    companion object {
        private const val TAG = "KokoroTTSService"
        private const val SAMPLE_RATE = 24000
    }

    private var kokoroEngine: KokoroEngine? = null
    private var phonemeConverter: PhonemeConverter? = null
    private var voiceManager: VoiceManager? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    @Volatile
    private var isInitialized = false
    
    @Volatile
    private var currentSynthesisJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        serviceScope.launch {
            try {
                initializeComponents()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize service", e)
            }
        }
    }
    
    private suspend fun initializeComponents() = withContext(Dispatchers.IO) {
        Log.d(TAG, "Initializing components...")
        
        try {
            // Initialize voice manager first (lightweight)
            voiceManager = VoiceManager(this@KokoroTTSService)
            Log.d(TAG, "VoiceManager initialized")
            
            // Initialize phoneme converter
            phonemeConverter = PhonemeConverter(this@KokoroTTSService)
            Log.d(TAG, "PhonemeConverter initialized")
            
            // Initialize ONNX engine (heavyweight)
            kokoroEngine = KokoroEngine(this@KokoroTTSService)
            Log.d(TAG, "KokoroEngine initialized")
            
            isInitialized = true
            Log.d(TAG, "All components initialized successfully")
            
        } catch (e: Exception) {
            Log.e(TAG, "Component initialization failed", e)
            throw e
        }
    }

    override fun onGetLanguage(): Array<String> {
        // Return [language, country, variant]
        return arrayOf("eng", "USA", "")
    }

    override fun onIsLanguageAvailable(lang: String?, country: String?, variant: String?): Int {
        return when {
            lang == null -> TextToSpeech.LANG_NOT_SUPPORTED
            lang.equals("eng", ignoreCase = true) || lang.equals("en", ignoreCase = true) -> {
                when {
                    country.equals("USA", ignoreCase = true) || 
                    country.equals("US", ignoreCase = true) -> TextToSpeech.LANG_COUNTRY_AVAILABLE
                    country.equals("GBR", ignoreCase = true) || 
                    country.equals("GB", ignoreCase = true) -> TextToSpeech.LANG_COUNTRY_AVAILABLE
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
        return setOf(
            TextToSpeech.Engine.KEY_FEATURE_EMBEDDED_SYNTHESIS
        )
    }

    override fun onGetVoices(): List<Voice> {
        val voices = mutableListOf<Voice>()
        val vm = voiceManager ?: return voices
        
        for (voiceName in vm.getAvailableVoices()) {
            val locale = if (voiceName.startsWith("b")) {
                Locale.UK
            } else {
                Locale.US
            }
            
            val gender = if (voiceName.contains("f")) {
                Voice.QUALITY_VERY_HIGH
            } else {
                Voice.QUALITY_VERY_HIGH
            }
            
            voices.add(
                Voice(
                    voiceName,
                    locale,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL,
                    false, // requires network
                    emptySet()
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

        if (text.isEmpty()) {
            callback.done()
            return
        }

        Log.d(TAG, "Synthesizing: ${text.take(50)}...")

        // Wait for initialization if needed
        if (!isInitialized) {
            Log.w(TAG, "Engine not yet initialized, waiting...")
            runBlocking {
                withTimeoutOrNull(10000) {
                    while (!isInitialized) {
                        delay(100)
                    }
                }
            }
            
            if (!isInitialized) {
                Log.e(TAG, "Engine initialization timeout")
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

        try {
            // Get voice from request
            val voiceName = request.voiceName ?: "af_sky"
            val voiceEmbedding = vm.getVoiceEmbedding(voiceName)

            // Get speech parameters
            val speedParam = request.speechRate
            val speed = when {
                speedParam in 50..400 -> speedParam / 100f
                else -> 1.0f
            }

            // Start audio synthesis
            callback.start(SAMPLE_RATE, android.media.AudioFormat.ENCODING_PCM_16BIT, 1)

            currentSynthesisJob = serviceScope.launch {
                try {
                    // Split text into sentences to avoid token limits and improve pacing
                    val iterator = java.text.BreakIterator.getSentenceInstance(java.util.Locale.US)
                    iterator.setText(text)
                    
                    var start = iterator.first()
                    var end = iterator.next()
                    
                    var allSuccess = true

                    while (end != java.text.BreakIterator.DONE && isActive) {
                        val sentence = text.substring(start, end).trim()
                        
                        if (sentence.isNotEmpty()) {
                            Log.d(TAG, "Processing sentence: ${sentence.take(50)}...")
                            
                            // Convert text to phonemes
                            val phonemes = converter.phonemize(sentence)
                            
                            // Synthesize audio for sentence
                            val sentenceSuccess = engine.synthesize(
                                phonemes = phonemes,
                                voiceEmbedding = voiceEmbedding,
                                speed = speed
                            ) { audioChunk ->
                                writeAudioToCallback(callback, audioChunk)
                            }
                            
                            if (!sentenceSuccess) {
                                allSuccess = false
                                Log.w(TAG, "Failed to synthesize sentence: $sentence")
                            }
                        }
                        
                        start = end
                        end = iterator.next()
                    }

                    if (allSuccess) {
                        callback.done()
                        Log.d(TAG, "Full synthesis completed successfully")
                    } else {
                        callback.error()
                        Log.e(TAG, "Synthesis completed with errors")
                    }

                } catch (e: CancellationException) {
                    Log.d(TAG, "Synthesis cancelled")
                    // Don't call done() if cancelled, let system handle it
                } catch (e: Exception) {
                    Log.e(TAG, "Synthesis error", e)
                    callback.error()
                }
            }

            // Wait for completion
            runBlocking {
                currentSynthesisJob?.join()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Synthesis failed", e)
            callback.error()
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
        
        currentSynthesisJob?.cancel()
        serviceScope.cancel()
        
        kokoroEngine?.release()
        kokoroEngine = null
        phonemeConverter = null
        voiceManager = null
        isInitialized = false
    }
}
