package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.settings.VodTrackPreferenceScope
import org.junit.Test

class PlayerVodTrackPreferenceScopeTest {

    @Test
    fun `movie preferences use the movie identity`() {
        assertThat(
            buildVodTrackPreferenceScope(
                contentType = ContentType.MOVIE,
                providerId = 7L,
                contentId = 42L,
                seriesId = null
            )
        ).isEqualTo(VodTrackPreferenceScope.Movie(providerId = 7L, contentId = 42L))
    }

    @Test
    fun `series episode preferences use the series identity`() {
        assertThat(
            buildVodTrackPreferenceScope(
                contentType = ContentType.SERIES_EPISODE,
                providerId = 7L,
                contentId = 101L,
                seriesId = 9L
            )
        ).isEqualTo(VodTrackPreferenceScope.Series(providerId = 7L, contentId = 9L))
    }

    @Test
    fun `live playback has no VOD preference scope`() {
        assertThat(
            buildVodTrackPreferenceScope(
                contentType = ContentType.LIVE,
                providerId = 7L,
                contentId = 101L,
                seriesId = null
            )
        ).isNull()
    }
}
