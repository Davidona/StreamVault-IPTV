package com.streamvault.domain.util

import java.text.Normalizer
import java.util.Locale

/**
 * Folds text for accent-insensitive searches while preserving marks used by non-Latin scripts.
 * Accents attached to Latin letters are removed; the original value remains unchanged for display.
 */
object SearchTextNormalizer {
    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
        val folded = StringBuilder(decomposed.length)
        var previousBaseWasLatin = false
        var index = 0

        while (index < decomposed.length) {
            val codePoint = decomposed.codePointAt(index)
            val isCombiningMark = when (Character.getType(codePoint)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt() -> true
                else -> false
            }

            if (!isCombiningMark || !previousBaseWasLatin) {
                folded.appendCodePoint(codePoint)
            }
            if (!isCombiningMark) {
                previousBaseWasLatin = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
            }

            index += Character.charCount(codePoint)
        }

        return folded.toString().lowercase(Locale.ROOT)
    }
}
