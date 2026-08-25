package com.streamvault.feature.playback.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.PlayerNavigationRequest
import org.junit.Test

class PlaybackNavigationPolicyTest {

    @Test
    fun `rejects missing blank and unsupported playback requests`() {
        assertThat(safePlayerNavigationRequest(null)).isNull()
        assertThat(
            safePlayerNavigationRequest(
                PlayerNavigationRequest(streamUrl = "", title = "Blank")
            )
        ).isNull()
        assertThat(
            safePlayerNavigationRequest(
                PlayerNavigationRequest(streamUrl = "javascript:alert(1)", title = "Unsafe")
            )
        ).isNull()
    }

    @Test
    fun `accepts every supported playback scheme without changing request fields`() {
        val schemes = listOf(
            "http",
            "https",
            "rtsp",
            "rtmp",
            "rtsps",
            "mms",
            "xtream",
            "stalker",
            "content",
            "file"
        )

        schemes.forEach { scheme ->
            val request = PlayerNavigationRequest(
                streamUrl = "$scheme://example.test/live",
                title = "Channel",
                channelId = "channel-7",
                internalId = 7L,
                categoryId = 8L,
                providerId = 9L,
                isVirtual = true,
                combinedProfileId = 10L,
                combinedSourceFilterProviderId = 11L,
                contentType = "SERIES_EPISODE",
                artworkUrl = "https://example.test/poster.jpg",
                archiveStartMs = 12L,
                archiveEndMs = 13L,
                archiveTitle = "Archive",
                seriesId = 14L,
                seasonNumber = 2,
                episodeNumber = 3,
                episodeId = 15L
            )

            assertThat(safePlayerNavigationRequest(request)).isSameInstanceAs(request)
        }
    }
}
