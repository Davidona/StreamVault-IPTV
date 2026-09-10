package com.streamvault.feature.playback.player

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.settings.VodTrackPreferenceScope

internal fun buildVodTrackPreferenceScope(
    contentType: ContentType,
    providerId: Long,
    contentId: Long,
    seriesId: Long?
): VodTrackPreferenceScope? {
    if (providerId <= 0L || contentId <= 0L) return null

    return when (contentType) {
        ContentType.MOVIE,
        ContentType.VOD -> VodTrackPreferenceScope.Movie(
            providerId = providerId,
            contentId = contentId
        )

        ContentType.SERIES_EPISODE -> seriesId
            ?.takeIf { it > 0L }
            ?.let { VodTrackPreferenceScope.Series(providerId = providerId, contentId = it) }

        ContentType.LIVE,
        ContentType.SERIES -> null
    }
}
