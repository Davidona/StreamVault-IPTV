package com.streamvault.domain.util

import java.text.Normalizer
import java.util.Locale

/** Shared name-based adult-content classification for providers and external catalogs. */
object AdultContentNameClassifier {
    private val diacriticsRegex = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val separatorsRegex = Regex("[^a-z0-9+]+")
    private val strongContainsKeywords = listOf(
        "xxx",
        "18+",
        "+18",
        "+ 18",
        "18 +",
        "18 plus",
        "plus 18",
        "18plus"
    )
    private val boundaryKeywords = listOf(
        "adult", "adults", "porn", "porno", "milf", "gay", "lesbian", "sex", "erotic",
        "hustler", "playboy", "hanime", "live cam", "live cams", "redlight", "red light",
        "adulto", "adultos", "adulte", "adultes", "erotique", "erwachsene", "erotik",
        "vzroslye", "dlya vzroslykh", "lilkbar", "yetiskin", "hentai", "for adults",
        "pour adultes", "para adultos"
    )

    private val normalizedStrongKeywords = strongContainsKeywords.map(::normalize).distinct()
    private val normalizedBoundaryKeywords = boundaryKeywords.map(::normalize).distinct()

    fun isAdultCategoryName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val normalized = normalize(name)
        val normalizedPadded = " $normalized "
        return normalizedStrongKeywords.any(normalized::contains) ||
            normalizedBoundaryKeywords.any { keyword -> normalizedPadded.contains(" $keyword ") }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(diacriticsRegex, "")
            .lowercase(Locale.ROOT)
            .replace(separatorsRegex, " ")
            .trim()
}
