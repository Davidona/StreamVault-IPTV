package com.streamvault.player.tracks

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import com.streamvault.domain.settings.VodTrackPreferences

internal data class PreferredTextTrackPolicy(
    val preferredLanguageTag: String?,
    val selectTextByDefault: Boolean,
    val textDisabled: Boolean
)

internal fun resolvePreferredTextTrackPolicy(
    preferences: VodTrackPreferences?
): PreferredTextTrackPolicy {
    val subtitlePreference = preferences?.subtitle
        ?.takeUnless { it.disabled }
    val languageTag = subtitlePreference?.language
        ?.trim()
        ?.replace('_', '-')
        ?.takeIf(String::isNotBlank)

    return PreferredTextTrackPolicy(
        preferredLanguageTag = languageTag,
        selectTextByDefault = languageTag != null,
        textDisabled = languageTag == null
    )
}

internal fun TrackSelectionParameters.Builder.applyPreferredTextTrackPolicy(
    policy: PreferredTextTrackPolicy
): TrackSelectionParameters.Builder = apply {
    setPreferredTextLanguage(policy.preferredLanguageTag)
    setSelectTextByDefault(policy.selectTextByDefault)
    setTrackTypeDisabled(C.TRACK_TYPE_TEXT, policy.textDisabled)
}
