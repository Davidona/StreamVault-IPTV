package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.settings.PlaybackQualityPreferenceScope
import com.streamvault.domain.settings.PlaybackQualityPreferenceScopeType

internal fun buildPlaybackQualityPreferenceScope(
    providerId: Long,
    contentType: ContentType,
    remoteContentId: String?,
    remoteSeriesId: String?,
    liveLogicalGroupId: String? = null,
    liveRemoteStreamId: Long? = null
): PlaybackQualityPreferenceScope? {
    if (providerId <= 0L) return null
    val (type, key) = when (contentType) {
        ContentType.LIVE -> {
            val group = liveLogicalGroupId?.trim()?.takeIf(String::isNotBlank)?.let { rawGroup ->
                // ChannelNormalizer prefixes this key with the local provider row id. Provider
                // scope already supplies that namespace, and removing the prefix keeps portable
                // preferences valid when a restore assigns the provider a different local id.
                rawGroup.removePrefix("${providerId}_").takeIf(String::isNotBlank) ?: rawGroup
            }
            when {
                group != null -> PlaybackQualityPreferenceScopeType.LIVE_CHANNEL_GROUP to group
                liveRemoteStreamId != null && liveRemoteStreamId > 0L ->
                    PlaybackQualityPreferenceScopeType.LIVE_REMOTE_CHANNEL to liveRemoteStreamId.toString()
                else -> return null
            }
        }
        ContentType.MOVIE -> PlaybackQualityPreferenceScopeType.MOVIE to
            remoteContentId?.trim()?.takeIf(String::isNotBlank).orEmpty()
        ContentType.SERIES_EPISODE -> PlaybackQualityPreferenceScopeType.SERIES to
            remoteSeriesId?.trim()?.takeIf(String::isNotBlank).orEmpty()
        else -> return null
    }
    return PlaybackQualityPreferenceScope(providerId, type, key).takeIf { it.isValid() }
}
