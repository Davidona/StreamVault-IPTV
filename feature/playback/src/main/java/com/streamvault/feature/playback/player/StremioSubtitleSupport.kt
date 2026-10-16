package com.streamvault.feature.playback.player

import com.streamvault.core.navigation.StremioSubtitlePlaybackSource
import com.streamvault.domain.model.stremio.StremioAttributedResource
import com.streamvault.domain.model.stremio.StremioSubtitle
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class StremioPlayerSubtitleOption(
    val key: String,
    val url: String,
    val languageTag: String,
    val label: String
)

data class StremioSubtitlePickerState(
    val enabled: Boolean = false,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val options: List<StremioPlayerSubtitleOption> = emptyList()
)

internal fun buildStremioPlayerSubtitleOptions(
    inlineSubtitles: List<StremioSubtitlePlaybackSource>,
    addonSubtitles: List<StremioAttributedResource<StremioSubtitle>>
): List<StremioPlayerSubtitleOption> {
    val candidates = buildList {
        inlineSubtitles.forEachIndexed { index, subtitle ->
            add(
                subtitleOption(
                    url = subtitle.url,
                    language = subtitle.language,
                    id = subtitle.id,
                    sourceKey = "inline",
                    sourceLabel = "Stream",
                    index = index
                )
            )
        }
        addonSubtitles.forEach { attributed ->
            add(
                subtitleOption(
                    url = attributed.resource.url,
                    language = attributed.resource.lang,
                    id = attributed.resource.id,
                    sourceKey = attributed.source.installFingerprint,
                    sourceLabel = attributed.source.addonName,
                    index = 0
                )
            )
        }
    }.filterNotNull()

    return candidates.distinctBy { "${it.url}\u0000${it.languageTag}" }
}

internal fun stremioSubtitleLanguageTag(language: String): String {
    val raw = language.trim().replace('_', '-')
    if (raw.isEmpty()) return "und"
    val parts = raw.split('-')
    val primary = parts.first().lowercase(Locale.ROOT)
    val languageCode = when {
        primary.length == 2 && primary.all(Char::isLetter) -> primary
    primary.length == 3 && primary.all(Char::isLetter) ->
            ISO_639_3_TO_639_1[primary] ?: ISO_639_3_ALIASES[primary] ?: "und"
        else -> "und"
    }
    if (languageCode == "und" || parts.size == 1) return languageCode
    val regionAndVariants = parts.drop(1).filter { it.isNotBlank() }
    return (listOf(languageCode) + regionAndVariants
        .map { part ->
            when {
                part.length == 2 && part.all(Char::isLetter) -> part.uppercase(Locale.ROOT)
                part.length == 4 && part.all(Char::isLetter) ->
                    part.lowercase(Locale.ROOT).replaceFirstChar(Char::uppercaseChar)
                else -> part.lowercase(Locale.ROOT)
            }
        }
        )
        .joinToString("-")
}

private fun subtitleOption(
    url: String,
    language: String,
    id: String?,
    sourceKey: String,
    sourceLabel: String,
    index: Int
): StremioPlayerSubtitleOption? {
    val canonicalUrl = url.trim().toHttpUrlOrNull()?.toString() ?: return null
    val languageTag = stremioSubtitleLanguageTag(language)
    val languageName = Locale.forLanguageTag(languageTag)
        .getDisplayLanguage(Locale.getDefault())
        .takeIf { it.isNotBlank() && languageTag != "und" }
        ?: languageTag
    return StremioPlayerSubtitleOption(
        key = "$sourceKey:${id ?: index}:$languageTag",
        url = canonicalUrl,
        languageTag = languageTag,
        label = "$languageName · $sourceLabel"
    )
}

private val ISO_639_3_TO_639_1: Map<String, String> by lazy {
    Locale.getISOLanguages().mapNotNull { language ->
        runCatching {
            val locale = Locale.Builder()
                .setLanguage(language)
                .build()
            locale.getISO3Language().lowercase(Locale.ROOT) to language.lowercase(Locale.ROOT)
        }.getOrNull()
    }.toMap()
}

private val ISO_639_3_ALIASES = mapOf(
    "alb" to "sq",
    "arm" to "hy",
    "baq" to "eu",
    "bur" to "my",
    "chi" to "zh",
    "cze" to "cs",
    "dut" to "nl",
    "fre" to "fr",
    "geo" to "ka",
    "ger" to "de",
    "gre" to "el",
    "ice" to "is",
    "mac" to "mk",
    "may" to "ms",
    "per" to "fa",
    "rum" to "ro",
    "slo" to "sk",
    "tib" to "bo",
    "wel" to "cy"
)
