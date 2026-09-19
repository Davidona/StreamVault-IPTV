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
                locale.getDisplayLanguage(locale)
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
