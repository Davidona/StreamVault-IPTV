package com.streamvault.feature.settings.presentation

import com.streamvault.core.ui.localization.localeForLanguageTag
import com.streamvault.core.ui.localization.supportedAppLanguageTags
import java.util.Locale

public data class AppLanguageOption(
    val tag: String,
    val label: String
)

public fun supportedAppLanguages(systemDefaultLabel: String): List<AppLanguageOption> {
    val localeTags = listOf("system") + supportedAppLanguageTags()

    return localeTags.map { tag ->
        AppLanguageOption(
            tag = tag,
            label = if (tag == "system") {
                systemDefaultLabel
            } else {
                val locale = localeForLanguageTag(tag)
                val display = if (tag.contains('-')) {
                    locale.getDisplayName(locale)
                } else {
                    locale.getDisplayLanguage(locale)
                }
                display
                    .replaceFirstChar { character ->
                        if (character.isLowerCase()) {
                            character.titlecase(locale)
                        } else {
                            character.toString()
                        }
                    }
            }
        )
    }
}

public fun supportedAudioLanguages(autoLabel: String): List<AppLanguageOption> {
    return buildList {
        add(AppLanguageOption(tag = "auto", label = autoLabel))
        addAll(supportedAppLanguages(autoLabel).filterNot { it.tag == "system" })
    }
}

private val subtitleLanguageTags = listOf(
    "en", "ar", "bg", "bn", "ca", "cs", "da", "de", "el", "es", "fa", "fi", "fr", "he", "hi",
    "hr", "hu", "id", "it", "ja", "ko", "ms", "nb", "nl", "pl", "pt", "pt-br", "ro", "ru",
    "sk", "sl", "sr", "sv", "ta", "te", "th", "tr", "uk", "ur", "vi", "zh-cn", "zh-tw"
)

public fun subtitleLanguageOptions(): List<AppLanguageOption> = subtitleLanguageTags
    .map { tag -> AppLanguageOption(tag = tag, label = subtitleLanguageLabel(tag)) }
    .sortedBy { it.label.lowercase(Locale.getDefault()) }

public fun subtitleLanguageLabel(languageTag: String): String {
    if (languageTag.isBlank()) return languageTag
    val locale = Locale.forLanguageTag(languageTag)
    if (locale.language.isBlank()) return languageTag
    return locale.getDisplayName(Locale.getDefault())
        .replaceFirstChar { character ->
            if (character.isLowerCase()) {
                character.titlecase(Locale.getDefault())
            } else {
                character.toString()
            }
        }
        .ifBlank { languageTag }
}

public fun displayLanguageLabel(languageTag: String, defaultLabel: String): String {
    if (languageTag.isBlank() || languageTag == "system" || languageTag == "auto") return defaultLabel
    val locale = localeForLanguageTag(languageTag)
    if (locale.language.isBlank()) return defaultLabel
    return locale.getDisplayLanguage(Locale.getDefault())
        .replaceFirstChar { character ->
            if (character.isLowerCase()) {
                character.titlecase(Locale.getDefault())
            } else {
                character.toString()
            }
        }
}
