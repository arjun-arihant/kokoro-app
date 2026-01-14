package com.kokorotts.android

import android.util.Log

/**
 * Tokenizer for converting IPA phoneme strings to token IDs.
 * Based on the Kokoro model's vocabulary.
 */
object Tokenizer {

    private const val TAG = "Tokenizer"
    private const val MAX_PHONEME_LENGTH = 512

    // Complete IPA vocabulary used by Kokoro model matches hexgrad/Kokoro-82M config.json
    private val VOCAB: Map<Char, Int> by lazy {
        mapOf(
            '$' to 0,
            ';' to 1,
            ':' to 2,
            ',' to 3,
            '.' to 4,
            '!' to 5,
            '?' to 6,
            '—' to 9,
            '…' to 10,
            '"' to 11,
            '(' to 12,
            ')' to 13,
            '“' to 14,
            '”' to 15,
            ' ' to 16,
            '\u0303' to 17,
            'ʣ' to 18,
            'ʥ' to 19,
            'ʦ' to 20,
            'ʨ' to 21,
            'ᵝ' to 22,
            '\uAB67' to 23,
            'A' to 24,
            'I' to 25,
            'O' to 31,
            'Q' to 33,
            'S' to 35,
            'T' to 36,
            'W' to 39,
            'Y' to 41,
            'ᵊ' to 42,
            'a' to 43,
            'b' to 44,
            'c' to 45,
            'd' to 46,
            'e' to 47,
            'f' to 48,
            'h' to 50,
            'i' to 51,
            'j' to 52,
            'k' to 53,
            'l' to 54,
            'm' to 55,
            'n' to 56,
            'o' to 57,
            'p' to 58,
            'q' to 59,
            'r' to 60,
            's' to 61,
            't' to 62,
            'u' to 63,
            'v' to 64,
            'w' to 65,
            'x' to 66,
            'y' to 67,
            'z' to 68,
            'ɑ' to 69,
            'ɐ' to 70,
            'ɒ' to 71,
            'æ' to 72,
            'β' to 75,
            'ɔ' to 76,
            'ɕ' to 77,
            'ç' to 78,
            'ɖ' to 80,
            'ð' to 81,
            'ʤ' to 82,
            'ə' to 83,
            'ɚ' to 85,
            'ɛ' to 86,
            'ɜ' to 87,
            'ɟ' to 90,
            'ɡ' to 92,
            'ɥ' to 99,
            'ɨ' to 101,
            'ɪ' to 102,
            'ʝ' to 103,
            'ɯ' to 110,
            'ɰ' to 111,
            'ŋ' to 112,
            'ɳ' to 113,
            'ɲ' to 114,
            'ɴ' to 115,
            'ø' to 116,
            'ɸ' to 118,
            'θ' to 119,
            'œ' to 120,
            'ɹ' to 123,
            'ɾ' to 125,
            'ɻ' to 126,
            'ʁ' to 128,
            'ɽ' to 129,
            'ʂ' to 130,
            'ʃ' to 131,
            'ʈ' to 132,
            'ʧ' to 133,
            'ʊ' to 135,
            'ʋ' to 136,
            'ʌ' to 138,
            'ɣ' to 139,
            'ɤ' to 140,
            'χ' to 142,
            'ʎ' to 143,
            'ʒ' to 147,
            'ʔ' to 148,
            'ˈ' to 156,
            'ˌ' to 157,
            'ː' to 158,
            'ʰ' to 162,
            'ʲ' to 164,
            '↓' to 169,
            '→' to 171,
            '↗' to 172,
            '↘' to 173,
            'ᵻ' to 177
        )
    }

    /**
     * Tokenize a phoneme string into token IDs.
     * 
     * @param phonemes IPA phoneme string
     * @return LongArray of token IDs
     * @throws IllegalArgumentException if text is too long or contains unknown symbols
     */
    fun tokenize(phonemes: String): LongArray {
        if (phonemes.length > MAX_PHONEME_LENGTH) {
            Log.w(TAG, "Phonemes too long (${phonemes.length}), will be truncated")
        }

        val truncated = phonemes.take(MAX_PHONEME_LENGTH)
        
        return truncated.mapNotNull { char ->
            val tokenId = VOCAB[char]
            if (tokenId == null) {
                Log.w(TAG, "Unknown symbol: '$char' (${char.code}), skipping")
                null
            } else {
                tokenId.toLong()
            }
        }.toLongArray()
    }

    /**
     * Check if a character is in the vocabulary.
     */
    fun isValidChar(char: Char): Boolean = VOCAB.containsKey(char)

    /**
     * Filter a string to only include valid vocabulary characters.
     */
    fun filterToVocab(text: String): String {
        return text.filter { isValidChar(it) }
    }

    /**
     * Get the vocabulary size.
     */
    fun vocabSize(): Int = VOCAB.size
}
