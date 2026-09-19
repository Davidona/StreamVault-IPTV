package com.streamvault.domain.settings

/**
 * Stable hints for restoring a user's selected VOD track across playback
 * sessions. Media track ids are provider-specific, so language and label are
 * retained as fallbacks for series episodes whose ids change between streams.
 */
data class VodTrackPreference(
    val trackId: String? = null,
    val language: String? = null,
    val label: String? = null,
    val disabled: Boolean = false
)

data class VodTrackPreferences(
    val audio: VodTrackPreference? = null,
    val subtitle: VodTrackPreference? = null
)

sealed interface VodTrackPreferenceScope {
    val providerId: Long
    val contentId: Long

    data class Movie(
        override val providerId: Long,
        override val contentId: Long
    ) : VodTrackPreferenceScope

    data class Series(
        override val providerId: Long,
        override val contentId: Long
    ) : VodTrackPreferenceScope
}
