package com.streamvault.player.tracks

import com.streamvault.domain.settings.VodTrackPreference
import com.streamvault.player.PlayerTrack

internal fun resolvePreferredPlayerTrack(
    preference: VodTrackPreference,
    availableTracks: List<PlayerTrack>
): PlayerTrack? {
    if (preference.disabled) return null

    preference.trackId
        ?.takeIf(String::isNotBlank)
        ?.let { trackId ->
            availableTracks.firstOrNull { it.id == trackId }
        }
        ?.let { return it }

    val preferredLanguage = normalizeTrackLanguage(preference.language)
    val preferredLabel = normalizeTrackValue(preference.label)

    if (!preferredLanguage.isNullOrBlank()) {
        availableTracks.firstOrNull { track ->
            trackLanguageMatches(preferredLanguage, track.language) &&
                !preferredLabel.isNullOrBlank() &&
                normalizeTrackValue(track.name) == preferredLabel
        }?.let { return it }

        availableTracks.firstOrNull { track ->
            trackLanguageMatches(preferredLanguage, track.language)
        }?.let { return it }
    }

    return availableTracks.firstOrNull { track ->
        !preferredLabel.isNullOrBlank() && normalizeTrackValue(track.name) == preferredLabel
    }
}

private fun normalizeTrackValue(value: String?): String? = value
    ?.trim()
    ?.takeIf(String::isNotBlank)
    ?.lowercase()

private fun normalizeTrackLanguage(value: String?): String? = value
    ?.trim()
    ?.replace('_', '-')
    ?.takeIf(String::isNotBlank)
    ?.lowercase()

private fun trackLanguageMatches(preferredLanguage: String?, availableLanguage: String?): Boolean {
    val normalizedAvailable = normalizeTrackLanguage(availableLanguage) ?: return false
    if (preferredLanguage.isNullOrBlank()) return false
    return preferredLanguage == normalizedAvailable ||
        preferredLanguage.substringBefore('-') == normalizedAvailable.substringBefore('-')
}
