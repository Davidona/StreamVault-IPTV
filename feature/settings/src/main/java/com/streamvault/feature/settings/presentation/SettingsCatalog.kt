package com.streamvault.feature.settings.presentation

import java.text.Normalizer
import java.util.Locale
import com.streamvault.domain.model.RemoteColorButton
import com.streamvault.domain.model.RemoteShortcutProfile

data class SettingsSearchTarget(
    val category: SettingsCategory,
    val page: SettingsPage?,
    val itemId: String,
    val requestId: Long,
)

internal fun remoteShortcutSettingId(
    profile: RemoteShortcutProfile,
    button: RemoteColorButton,
): String = "remote.shortcuts.${profile.storageValue}.${button.storageValue}"

internal fun remoteShortcutTargetFromId(id: String?): Pair<RemoteShortcutProfile, RemoteColorButton>? {
    val parts = id?.split('.') ?: return null
    if (parts.size != 4 || parts[0] != "remote" || parts[1] != "shortcuts") return null
    val profile = RemoteShortcutProfile.fromStorage(parts[2]) ?: return null
    val button = RemoteColorButton.fromStorage(parts[3]) ?: return null
    return profile to button
}

/** A resolved catalog item contains display text only, never the setting's current value. */
data class ResolvedSettingsCatalogEntry(
    val id: String,
    val category: SettingsCategory,
    val page: SettingsPage?,
    val label: String,
    val aliases: Set<String> = emptySet(),
    val available: Boolean = true,
    val enabled: Boolean = true,
    val disabledExplanation: String? = null,
) {
    fun searchableText(): String = buildString {
        append(label)
        append(' ')
        append(category.name.replace('_', ' '))
        page?.let {
            append(' ')
            append(it.name.replace('_', ' '))
        }
        aliases.forEach {
            append(' ')
            append(it)
        }
    }
}

object SettingsCatalogSearch {
    fun search(
        entries: List<ResolvedSettingsCatalogEntry>,
        query: String,
    ): List<ResolvedSettingsCatalogEntry> {
        val needle = query.normalized()
        val available = entries.filter { it.available }
        if (needle.isBlank()) return available

        return available
            .mapNotNull { entry ->
                val label = entry.label.normalized()
                val haystack = entry.searchableText().normalized()
                if (needle !in haystack) null else entry to when {
                    label == needle -> 0
                    label.startsWith(needle) -> 1
                    label.split(' ').any { it.startsWith(needle) } -> 2
                    else -> 3
                }
            }
            .sortedWith(compareBy<Pair<ResolvedSettingsCatalogEntry, Int>> { it.second }
                .thenBy { it.first.label })
            .map { it.first }
    }
}

private fun String.normalized(): String = Normalizer
    .normalize(this, Normalizer.Form.NFKD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .trim()
