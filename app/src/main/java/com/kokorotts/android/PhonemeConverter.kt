package com.kokorotts.android

import android.content.Context
import android.content.res.Resources
import android.util.Log
import com.github.medavox.ipa_transcribers.Language
import java.io.IOException

/**
 * Converts text to IPA phonemes using CMU dictionary with fallback to rule-based transcription.
 */
class PhonemeConverter(context: Context) {

    companion object {
        private const val TAG = "PhonemeConverter"
    }

    private val phonemeMap = mutableMapOf<String, String>()
    private val englishTranscriber = Language.ENGLISH.transcriber

    init {
        loadDictionary(context)
    }

    private fun loadDictionary(context: Context) {
        try {
            // Try to load CMU dictionary from raw resources
            // First try cmudict_ipa (our filename without extension)
            var resourceId = context.resources.getIdentifier(
                "cmudict_ipa", "raw", context.packageName
            )
            
            // Fallback to cmudict_ipa_dict if needed
            if (resourceId == 0) {
                resourceId = context.resources.getIdentifier(
                    "cmudict_ipa_dict", "raw", context.packageName
                )
            }
            
            if (resourceId != 0) {
                context.resources.openRawResource(resourceId).bufferedReader()
                    .useLines { lines ->
                        lines.filter { !it.startsWith(";;;") }.forEach { line ->
                            val parts = line.split("\t", limit = 2)
                            if (parts.size == 2) {
                                phonemeMap[parts[0].uppercase()] = parts[1]
                            }
                        }
                    }
                Log.d(TAG, "CMU dictionary loaded: ${phonemeMap.size} entries")
            } else {
                Log.w(TAG, "CMU dictionary not found, using fallback transcription only")
            }
        } catch (e: IOException) {
            Log.w(TAG, "Error loading dictionary: ${e.message}")
        } catch (e: Resources.NotFoundException) {
            Log.w(TAG, "Dictionary resource not found: ${e.message}")
        }
    }

    /**
     * Convert text to IPA phonemes.
     * 
     * @param text Input text
     * @param lang Language code (default: "en-us")
     * @param normalize Whether to normalize text before conversion
     * @return IPA phoneme string
     */
    fun phonemize(text: String, lang: String = "en-us", normalize: Boolean = true): String {
        val normalizedText = if (normalize) normalizeText(text) else text
        Log.d(TAG, "Normalized text: ${normalizedText.take(100)}...")

        // Split into words and punctuation, keeping delimiters
        val tokens = normalizedText.split(Regex("(?<=[;:,.!?—…\"()])|(?=[;:,.!?—…\"()])|\\s+")).filter { it.isNotBlank() }

        val phonemes = StringBuilder()
        var previousWasWord = false

        for (token in tokens) {
            // Check if it's strictly punctuation/symbols
            if (token.matches(Regex("[^a-zA-Z0-9']+"))) {
                // Keep punctuation as-is
                phonemes.append(token)
                previousWasWord = false
            } else {
                // Add space before word if previous was also a word
                if (previousWasWord) {
                    phonemes.append(" ")
                }
                
                // Convert word to phonemes
                val ipaPhonemes = convertWordToPhonemes(token)
                phonemes.append(adjustStressMarkers(ipaPhonemes))
                previousWasWord = true
            }
        }

        // Post-process phonemes
        return postProcessPhonemes(phonemes.toString(), lang)
    }

    private fun convertWordToPhonemes(word: String): String {
        val cleanWord = word.replace(Regex("[^a-zA-Z']"), "").uppercase()
        
        // Try dictionary lookup
        val dictPhonemes = phonemeMap[cleanWord]
        if (dictPhonemes != null) {
            // Return first pronunciation variant
            return dictPhonemes.split(",").first().trim()
        }

        // Fallback to rule-based transcription
        return fallbackTranscribe(word)
    }

    private fun fallbackTranscribe(word: String): String {
        return try {
            englishTranscriber.transcribe(word)
        } catch (e: Exception) {
            Log.w(TAG, "Fallback transcription failed for '$word': ${e.message}")
            word.lowercase() // Return original word if transcription fails
        }
    }

    /**
     * Move stress markers to before the vowel they stress.
     */
    private fun adjustStressMarkers(input: String): String {
        val vowels = setOf(
            'a', 'e', 'i', 'o', 'u',
            'ɑ', 'ɐ', 'ɒ', 'æ', 'ɔ', 'ə', 'ɘ', 'ɚ', 'ɛ', 'ɜ', 'ɝ', 'ɞ',
            'ɪ', 'ɨ', 'ø', 'ɵ', 'œ', 'ɶ', 'ʉ', 'ʊ', 'ʌ',
            'A', 'E', 'I', 'O', 'U', 'ː', 'ˑ'
        )

        val builder = StringBuilder(input)
        var i = 0

        while (i < builder.length) {
            if (builder[i] == 'ˈ' || builder[i] == 'ˌ') {
                val stressIndex = i
                val stressChar = builder[i]

                for (j in stressIndex + 1 until builder.length) {
                    if (builder[j] in vowels) {
                        builder.deleteCharAt(stressIndex)
                        builder.insert(j - 1, stressChar)
                        i = j
                        break
                    }
                }
            }
            i++
        }

        return builder.toString()
    }

    private fun normalizeText(text: String): String {
        var normalized = text
            .lines()
            .joinToString("\n") { it.trim() }
            // Normalize quotes
            .replace(Regex("[''ʼ]"), "'")
            .replace(Regex("[\"«»„“”]"), "\"")
            // Normalize Asian punctuation
            .replace("、", ", ")
            .replace("。", ". ")
            .replace("！", "! ")
            .replace("，", ", ")
            .replace("：", ": ")
            .replace("；", "; ")
            .replace("？", "? ")

        // Expand common abbreviations
        normalized = normalized
            .replace(Regex("\\bDr\\.(?= [A-Z])"), "Doctor")
            .replace(Regex("\\bMr\\.(?= [A-Z])"), "Mister")
            .replace(Regex("\\bMrs\\.(?= [A-Z])"), "Missus")
            .replace(Regex("\\bMs\\.(?= [A-Z])"), "Miss")
            .replace(Regex("\\betc\\.(?! [A-Z])"), "etcetera")
            .replace(Regex("\\be\\.g\\."), "for example")
            .replace(Regex("\\bi\\.e\\."), "that is")
            .replace(Regex("\\bvs\\."), "versus")

        // Handle numbers in text
        normalized = normalized
            .replace(Regex("(?<=\\d),(?=\\d)"), "") // Remove commas in numbers
            .replace(Regex("(?<=\\d)-(?=\\d)"), " to ") // Convert ranges

        return normalized.trim()
    }

    private fun postProcessPhonemes(phonemes: String, lang: String): String {
        var result = phonemes
            // Common IPA substitutions
            .replace("r", "ɹ")
            .replace("x", "k")
            .replace("ʲ", "j")
            .replace("ɬ", "l")

        // Fix common mispronunciations
        result = result
            .replace("kəkˈoːɹoʊ", "kˈoʊkəɹoʊ")
            .replace("kəkˈɔːɹəʊ", "kˈəʊkəɹəʊ")

        // American English specific adjustments
        if (lang == "en-us") {
            // Flap t -> d between vowels
        }

        // Filter to only valid vocabulary characters
        result = result.filter { char ->
            Tokenizer.isValidChar(char)
        }

        return result.trim()
    }
}
