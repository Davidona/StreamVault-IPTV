package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerPrepareIdentityTest {

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
