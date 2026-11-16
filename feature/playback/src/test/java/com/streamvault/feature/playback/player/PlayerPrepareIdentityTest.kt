package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.StremioPlaybackReference
import org.junit.Test

class PlayerPrepareIdentityTest {
    @Test
    fun `negative category identities survive preparation and distinguish custom groups`() {
        val categoryIds = listOf(-1L, -2L, -998L, -999L, -1_000_000L)
        val identities = categoryIds.map { category ->
            buildPlayerPrepareIdentity(
                streamUrl = "https://example.test/channel.m3u8", epgChannelId = null,
                internalChannelId = 10, categoryId = category, providerId = 1, isVirtual = true,
                combinedProfileId = null, combinedSourceFilterProviderId = null,
                contentType = "LIVE", archiveStartMs = null, archiveEndMs = null
            )
        }
        assertThat(identities.map { it.categoryId }).containsExactlyElementsIn(categoryIds).inOrder()
        assertThat(identities.toSet()).hasSize(categoryIds.size)
    }

    @Test
    fun `buildPlayerPrepareIdentity ignores display metadata changes`() {
        val baseline = buildPlayerPrepareIdentity(
            streamUrl = "https://example.com/stream",
            epgChannelId = "epg-1",
            internalChannelId = 100L,
            categoryId = 10L,
            providerId = 20L,
            isVirtual = false,
            combinedProfileId = null,
            combinedSourceFilterProviderId = null,
            contentType = "SERIES_EPISODE",
            archiveStartMs = null,
            archiveEndMs = null
        )

        val sameIdentity = buildPlayerPrepareIdentity(
            streamUrl = "https://example.com/stream",
            epgChannelId = "epg-1",
            internalChannelId = 100L,
            categoryId = 10L,
            providerId = 20L,
            isVirtual = false,
            combinedProfileId = null,
            combinedSourceFilterProviderId = null,
            contentType = "SERIES_EPISODE",
            archiveStartMs = null,
            archiveEndMs = null
        )

        assertThat(sameIdentity).isEqualTo(baseline)
    }

    @Test
    fun `buildPlayerPrepareIdentity changes when archive window changes`() {
        val baseline = buildPlayerPrepareIdentity(
            streamUrl = "https://example.com/stream",
            epgChannelId = "epg-1",
            internalChannelId = 100L,
            categoryId = 10L,
            providerId = 20L,
            isVirtual = false,
            combinedProfileId = null,
            combinedSourceFilterProviderId = null,
            contentType = "LIVE",
            archiveStartMs = 1_000L,
            archiveEndMs = 2_000L
        )

        val changedWindow = buildPlayerPrepareIdentity(
            streamUrl = "https://example.com/stream",
            epgChannelId = "epg-1",
            internalChannelId = 100L,
            categoryId = 10L,
            providerId = 20L,
            isVirtual = false,
            combinedProfileId = null,
            combinedSourceFilterProviderId = null,
            contentType = "LIVE",
            archiveStartMs = 2_000L,
            archiveEndMs = 3_000L
        )

        assertThat(changedWindow).isNotEqualTo(baseline)
    }

    @Test
    fun `buildPlayerPrepareIdentity changes when Stremio content identity changes`() {
        fun identity(exactVideoId: String) = buildPlayerPrepareIdentity(
            streamUrl = "https://example.com/stream.mp4",
            epgChannelId = null,
            internalChannelId = -1L,
            categoryId = null,
            providerId = null,
            isVirtual = false,
            combinedProfileId = null,
            combinedSourceFilterProviderId = null,
            contentType = "SERIES_EPISODE",
            archiveStartMs = null,
            archiveEndMs = null,
            stremioPlaybackReference = StremioPlaybackReference(
                mediaType = "series",
                exactVideoId = exactVideoId,
                parentSeriesId = "series-id",
                catalogOriginKey = "catalog-key",
                streamOriginKey = "stream-key"
            )
        )

        val baseline = identity("episode-one")
        val nextEpisode = identity("episode-two")

        assertThat(nextEpisode).isNotEqualTo(baseline)
    }

    @Test
    fun `resolveRouteDisplayTitle prefers archive title for live archive playback`() {
        assertThat(
            resolveRouteDisplayTitle(
                title = "Channel",
                contentType = "LIVE",
                archiveStartMs = 1_000L,
                archiveEndMs = 2_000L,
                archiveTitle = "Morning News"
            )
        ).isEqualTo("Morning News")
    }

    @Test
    fun `shouldResolveChannelPlaybackContext ignores movie content ids`() {
        assertThat(shouldResolveChannelPlaybackContext("MOVIE", 42L)).isFalse()
    }

    @Test
    fun `shouldResolveChannelPlaybackContext uses live channel ids`() {
        assertThat(shouldResolveChannelPlaybackContext("LIVE", 42L)).isTrue()
    }
}
