package com.streamvault.domain.settings

import java.util.Locale

object SubtitleLanguageOrdering {

    fun normalizePinned(tags: List<String>): List<String> {
        val seen = HashSet<String>()
        val normalized = ArrayList<String>(tags.size)
        tags.forEach { raw ->
            val tag = raw.trim().lowercase().takeIf { it.isNotBlank() } ?: return@forEach
            if (seen.add(tag)) normalized += tag
        }
        return normalized
    }

    fun primaryCode(languageTag: String?): String? {
        val tag = languageTag?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        if (tag == "und" || tag == "auto" || tag == "undetermined") return null
        val localeLanguage = Locale.forLanguageTag(tag).language.lowercase()
            .takeIf { it.isNotBlank() && it != "und" }
            ?: tag.substringBefore('-')
        val mapped = ISO_639_2_TO_1[localeLanguage] ?: localeLanguage
        return mapped.substringBefore('-').takeIf { it.isNotBlank() && it != "und" }
    }

    fun <T> sortByPinned(
        pinnedTags: List<String>,
        items: List<T>,
        languageOf: (T) -> String?
    ): List<T> {
        val pinned = normalizePinned(pinnedTags)
        if (pinned.isEmpty() || items.size < 2) return items

        val exactRanks = HashMap<String, Int>()
        val primaryRanks = HashMap<String, Int>()
        pinned.forEachIndexed { index, tag ->
            exactRanks.putIfAbsent(tag, index)
            primaryCode(tag)?.let { primaryRanks.putIfAbsent(it, index) }
        }

        return items.sortedBy { item ->
            val normalizedTag = languageOf(item)?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
                ?: return@sortedBy Int.MAX_VALUE
            exactRanks[normalizedTag]?.let { return@sortedBy it * 2 }
            primaryRanks[primaryCode(normalizedTag)]?.let { return@sortedBy it * 2 + 1 }
            Int.MAX_VALUE
        }
    }

    private val ISO_639_2_TO_1 = mapOf(
        "eng" to "en", "fre" to "fr", "fra" to "fr", "ger" to "de", "deu" to "de",
        "spa" to "es", "ita" to "it", "por" to "pt", "rus" to "ru", "ara" to "ar",
        "hin" to "hi", "jpn" to "ja", "kor" to "ko", "chi" to "zh", "zho" to "zh",
        "nld" to "nl", "dut" to "nl", "pol" to "pl", "tur" to "tr", "swe" to "sv",
        "nor" to "no", "nob" to "nb", "nno" to "nn", "dan" to "da", "fin" to "fi",
        "ell" to "el", "gre" to "el", "heb" to "he", "ukr" to "uk", "vie" to "vi",
        "tha" to "th", "ces" to "cs", "cze" to "cs", "hun" to "hu", "ron" to "ro",
        "rum" to "ro", "bul" to "bg", "hrv" to "hr", "srp" to "sr", "slk" to "sk",
        "slo" to "sk", "slv" to "sl", "lit" to "lt", "lav" to "lv", "est" to "et",
        "ind" to "id", "msa" to "ms", "may" to "ms", "fil" to "tl", "tgl" to "tl",
        "ben" to "bn", "tam" to "ta", "tel" to "te", "mar" to "mr", "guj" to "gu",
        "kan" to "kn", "mal" to "ml", "pan" to "pa", "urd" to "ur", "fas" to "fa",
        "per" to "fa", "sqi" to "sq", "alb" to "sq", "hye" to "hy", "arm" to "hy",
        "kat" to "ka", "geo" to "ka", "aze" to "az", "kaz" to "kk", "uzb" to "uz",
        "mon" to "mn", "nep" to "ne", "sin" to "si", "khm" to "km", "lao" to "lo",
        "mya" to "my", "bur" to "my", "amh" to "am", "swa" to "sw", "zul" to "zu",
        "afr" to "af", "isl" to "is", "ice" to "is", "gle" to "ga", "cym" to "cy",
        "wel" to "cy", "eus" to "eu", "baq" to "eu", "cat" to "ca", "glg" to "gl",
        "mkd" to "mk", "mac" to "mk", "bos" to "bs", "yue" to "zh"
    )
}
