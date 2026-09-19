package com.streamvault.core.ui.localization

import android.os.Build
import android.content.res.Configuration
import java.util.Locale

private val supportedLanguageTags = listOf(
    "en",
    "ar",
    "cs",
    "da",
    "de",
    "el",
    "es",
    "fi",
    "fr",
    "he",
    "hu",
    "id",
    "it",
    "ja",
    "ko",
    "nb",
    "nl",
    "pl",
    "pt",
    "ro",
    "ru",
    "sv",
    "tr",
    "uk",
    "vi",
    "zh"
)

fun supportedAppLanguageTags(): List<String> = supportedLanguageTags

fun localeForLanguageTag(languageTag: String): Locale = Locale.forLanguageTag(canonicalLanguageTag(languageTag))

fun resolveAppLocale(preferredLanguageTag: String, baseConfiguration: Configuration): Locale {
    val requestedLocales = if (preferredLanguageTag == "system") {
        systemLocaleCandidates(baseConfiguration)
    } else {
        listOf(localeForLanguageTag(preferredLanguageTag))
    }

    return requestedLocales
        .firstNotNullOfOrNull(::matchSupportedLocale)
        ?: Locale.ENGLISH
}

private fun systemLocaleCandidates(configuration: Configuration): List<Locale> {
    val locales = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        buildList {
            for (index in 0 until configuration.locales.size()) {
                add(configuration.locales[index])
            }
        }
    } else {
        @Suppress("DEPRECATION")
        listOf(configuration.locale)
    }
    return if (locales.isNotEmpty()) locales else listOf(Locale.getDefault())
}

private fun matchSupportedLocale(locale: Locale): Locale? {
    val candidates = buildList {
        locale.toLanguageTag().takeIf { it.isNotBlank() }?.let(::add)
        locale.language.takeIf { it.isNotBlank() }?.let(::add)
        when (locale.language.lowercase(Locale.ROOT)) {
            "iw" -> add("he")
            "he" -> add("iw")
            "in" -> add("id")
            "id" -> add("in")
        }
    }.map(::canonicalLanguageTag)

    val matchedTag = candidates.firstOrNull { candidate ->
        supportedLanguageTags.any { canonicalLanguageTag(it) == candidate }
    } ?: return null

    return Locale.forLanguageTag(matchedTag)
}

private fun canonicalLanguageTag(languageTag: String): String {
    val normalized = languageTag.trim().lowercase(Locale.ROOT)
    return when {
        normalized.startsWith("iw") || normalized.startsWith("he") -> "he"
        normalized.startsWith("in") || normalized.startsWith("id") -> "id"
        else -> normalized.substringBefore('-')
    }
}
