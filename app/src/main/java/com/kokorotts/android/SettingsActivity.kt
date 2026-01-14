package com.kokorotts.android

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.util.*

/**
 * Settings activity for Kokoro TTS configuration and testing.
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SettingsActivity"
    }

    private lateinit var voiceSpinner: Spinner
    private lateinit var speedSeekBar: SeekBar
    private lateinit var speedText: TextView
    private lateinit var testButton: Button
    private lateinit var stopButton: Button
    private lateinit var testInput: EditText
    private lateinit var statusText: TextView
    private lateinit var progressBar: ProgressBar

    private var kokoroEngine: KokoroEngine? = null
    private var phonemeConverter: PhonemeConverter? = null
    private var voiceManager: VoiceManager? = null
    
    private var audioTrack: AudioTrack? = null
    private var synthesisJob: Job? = null
    private var currentSpeed = 1.0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        initializeViews()
        initializeEngine()
        setupListeners()
    }

    private fun initializeViews() {
        voiceSpinner = findViewById(R.id.voice_spinner)
        speedSeekBar = findViewById(R.id.speed_seekbar)
        speedText = findViewById(R.id.speed_text)
        testButton = findViewById(R.id.test_button)
        stopButton = findViewById(R.id.stop_button)
        testInput = findViewById(R.id.test_input)
        statusText = findViewById(R.id.status_text)
        progressBar = findViewById(R.id.progress_bar)

        // Set default test text
        testInput.setText(getString(R.string.default_test_text))
        
        // Initially hide progress
        progressBar.visibility = View.GONE
        stopButton.visibility = View.GONE
    }

    private fun initializeEngine() {
        updateStatus(getString(R.string.model_loading))
        progressBar.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                voiceManager = VoiceManager(this@SettingsActivity)
                phonemeConverter = PhonemeConverter(this@SettingsActivity)
                kokoroEngine = KokoroEngine(this@SettingsActivity)

                withContext(Dispatchers.Main) {
                    setupVoiceSelector()
                    updateStatus(getString(R.string.model_ready))
                    progressBar.visibility = View.GONE
                    testButton.isEnabled = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize engine", e)
                withContext(Dispatchers.Main) {
                    updateStatus("${getString(R.string.model_error)}: ${e.message}")
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun setupVoiceSelector() {
        val vm = voiceManager ?: return
        val voices = vm.getVoiceDefinitions()

        val displayNames = voices.map { voice ->
            "${voice.displayName} (${voice.gender})"
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            displayNames
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        voiceSpinner.adapter = adapter

        // Select default voice
        val defaultIndex = voices.indexOfFirst { it.name == "af_sky" }
        if (defaultIndex >= 0) {
            voiceSpinner.setSelection(defaultIndex)
        }
    }

    private fun setupListeners() {
        // Speed slider setup (range: 0.5x to 2.0x, mapped to 0-150)
        speedSeekBar.max = 150
        speedSeekBar.progress = 50 // 1.0x default
        updateSpeedText(1.0f)

        speedSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                currentSpeed = 0.5f + (progress / 100f)
                updateSpeedText(currentSpeed)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        testButton.setOnClickListener {
            testSynthesis()
        }

        stopButton.setOnClickListener {
            stopSynthesis()
        }
    }

    private fun updateSpeedText(speed: Float) {
        speedText.text = String.format(Locale.US, "Speed: %.2fx", speed)
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    private fun testSynthesis() {
        val text = testInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Please enter text to synthesize", Toast.LENGTH_SHORT).show()
            return
        }

        val engine = kokoroEngine
        val converter = phonemeConverter
        val vm = voiceManager

        if (engine == null || converter == null || vm == null) {
            Toast.makeText(this, "Engine not ready", Toast.LENGTH_SHORT).show()
            return
        }

        // Get selected voice
        val voiceIndex = voiceSpinner.selectedItemPosition
        val voices = vm.getVoiceDefinitions()
        if (voiceIndex < 0 || voiceIndex >= voices.size) {
            Toast.makeText(this, "Please select a voice", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedVoice = voices[voiceIndex]
        val voiceEmbedding = vm.getVoiceEmbedding(selectedVoice.name)

        // Update UI
        testButton.visibility = View.GONE
        stopButton.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        updateStatus("Processing...")

        synthesisJob = lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Convert to phonemes
                val phonemes = converter.phonemize(text)
                Log.d(TAG, "Phonemes: $phonemes")

                withContext(Dispatchers.Main) {
                    updateStatus("Generating audio...")
                }

                // Collect all audio chunks
                val audioChunks = mutableListOf<ByteArray>()

                val success = engine.synthesize(
                    phonemes = phonemes,
                    voiceEmbedding = voiceEmbedding,
                    speed = currentSpeed
                ) { chunk ->
                    audioChunks.add(chunk)
                    true
                }

                if (!success || audioChunks.isEmpty()) {
                    throw Exception("Synthesis failed")
                }

                // Combine chunks
                val totalSize = audioChunks.sumOf { it.size }
                val fullAudio = ByteArray(totalSize)
                var offset = 0
                for (chunk in audioChunks) {
                    System.arraycopy(chunk, 0, fullAudio, offset, chunk.size)
                    offset += chunk.size
                }

                Log.d(TAG, "Generated ${fullAudio.size} bytes of audio")

                withContext(Dispatchers.Main) {
                    updateStatus("Playing...")
                    playAudio(fullAudio)
                }

            } catch (e: CancellationException) {
                Log.d(TAG, "Synthesis cancelled")
                withContext(Dispatchers.Main) {
                    updateStatus("Stopped")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Synthesis error", e)
                withContext(Dispatchers.Main) {
                    updateStatus("Error: ${e.message}")
                    Toast.makeText(this@SettingsActivity, "Synthesis failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                withContext(Dispatchers.Main) {
                    testButton.visibility = View.VISIBLE
                    stopButton.visibility = View.GONE
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun playAudio(audioData: ByteArray) {
        try {
            stopAudio()

            val bufferSize = AudioTrack.getMinBufferSize(
                AudioGenerator.SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(AudioGenerator.SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(bufferSize, audioData.size))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.apply {
                write(audioData, 0, audioData.size)
                setNotificationMarkerPosition(audioData.size / 2) // 2 bytes per sample
                setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                    override fun onMarkerReached(track: AudioTrack?) {
                        updateStatus(getString(R.string.model_ready))
                    }
                    override fun onPeriodicNotification(track: AudioTrack?) {}
                })
                play()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Audio playback error", e)
            updateStatus("Playback error: ${e.message}")
        }
    }

    private fun stopAudio() {
        try {
            audioTrack?.apply {
                if (state == AudioTrack.STATE_INITIALIZED) {
                    stop()
                }
                release()
            }
            audioTrack = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping audio", e)
        }
    }

    private fun stopSynthesis() {
        synthesisJob?.cancel()
        kokoroEngine?.stop()
        stopAudio()
        
        testButton.visibility = View.VISIBLE
        stopButton.visibility = View.GONE
        progressBar.visibility = View.GONE
        updateStatus("Stopped")
    }

    override fun onPause() {
        super.onPause()
        stopSynthesis()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSynthesis()
        kokoroEngine?.release()
        kokoroEngine = null
    }
}
