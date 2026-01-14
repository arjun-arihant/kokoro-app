package com.kokorotts.android

import android.media.AudioFormat
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio utilities for handling TTS audio output.
 */
object AudioGenerator {

    private const val TAG = "AudioGenerator"
    const val SAMPLE_RATE = 24000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    /**
     * Get Android audio format constant.
     */
    fun getAudioFormat(): Int = AudioFormat.ENCODING_PCM_16BIT

    /**
     * Get channel configuration.
     */
    fun getChannelConfig(): Int = AudioFormat.CHANNEL_OUT_MONO

    /**
     * Convert float audio samples to 16-bit PCM bytes.
     * 
     * @param floatData Float audio samples in range [-1.0, 1.0]
     * @return PCM 16-bit byte array (little-endian)
     */
    fun floatToPcm16(floatData: FloatArray): ByteArray {
        val pcmData = ByteArray(floatData.size * 2)
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)

        for (sample in floatData) {
            val clampedSample = sample.coerceIn(-1.0f, 1.0f)
            val pcmSample = (clampedSample * 32767).toInt().toShort()
            buffer.putShort(pcmSample)
        }

        return pcmData
    }

    /**
     * Convert 16-bit PCM bytes to float samples.
     */
    fun pcm16ToFloat(pcmData: ByteArray): FloatArray {
        val floatData = FloatArray(pcmData.size / 2)
        val buffer = ByteBuffer.wrap(pcmData).order(ByteOrder.LITTLE_ENDIAN)

        for (i in floatData.indices) {
            val pcmSample = buffer.short
            floatData[i] = pcmSample / 32768f
        }

        return floatData
    }

    /**
     * Create a WAV file header for the given PCM data.
     */
    fun createWavHeader(
        dataSize: Int,
        sampleRate: Int = SAMPLE_RATE,
        channels: Int = CHANNELS,
        bitsPerSample: Int = BITS_PER_SAMPLE
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val fileSize = 36 + dataSize

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        header.put("RIFF".toByteArray())
        header.putInt(fileSize)
        header.put("WAVE".toByteArray())

        // fmt chunk
        header.put("fmt ".toByteArray())
        header.putInt(16) // Chunk size
        header.putShort(1) // Audio format (PCM)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(byteRate)
        header.putShort(blockAlign.toShort())
        header.putShort(bitsPerSample.toShort())

        // data chunk
        header.put("data".toByteArray())
        header.putInt(dataSize)

        return header.array()
    }

    /**
     * Create a complete WAV file from PCM data.
     */
    fun createWavFile(pcmData: ByteArray): ByteArray {
        val header = createWavHeader(pcmData.size)
        
        val output = ByteArrayOutputStream()
        output.write(header)
        output.write(pcmData)
        
        return output.toByteArray()
    }

    /**
     * Apply simple speed change by resampling (affects pitch).
     * For pitch-preserving speed change, use a proper time-stretching algorithm.
     */
    fun applySpeedChange(audioData: FloatArray, speed: Float): FloatArray {
        if (speed == 1.0f) return audioData

        val newLength = (audioData.size / speed).toInt()
        val result = FloatArray(newLength)

        for (i in result.indices) {
            val srcIndex = (i * speed).toInt()
            if (srcIndex < audioData.size) {
                result[i] = audioData[srcIndex]
            }
        }

        return result
    }

    /**
     * Normalize audio to prevent clipping.
     */
    fun normalize(audioData: FloatArray, targetPeak: Float = 0.95f): FloatArray {
        val maxAbs = audioData.maxOfOrNull { kotlin.math.abs(it) } ?: 0f
        
        if (maxAbs <= 0f || maxAbs <= targetPeak) {
            return audioData
        }

        val scale = targetPeak / maxAbs
        return FloatArray(audioData.size) { audioData[it] * scale }
    }

    /**
     * Apply fade in/out to avoid clicks.
     */
    fun applyFade(
        audioData: FloatArray, 
        fadeInSamples: Int = 100, 
        fadeOutSamples: Int = 100
    ): FloatArray {
        val result = audioData.copyOf()

        // Fade in
        for (i in 0 until minOf(fadeInSamples, result.size)) {
            result[i] *= i.toFloat() / fadeInSamples
        }

        // Fade out
        for (i in 0 until minOf(fadeOutSamples, result.size)) {
            val idx = result.size - 1 - i
            result[idx] *= i.toFloat() / fadeOutSamples
        }

        return result
    }
}
