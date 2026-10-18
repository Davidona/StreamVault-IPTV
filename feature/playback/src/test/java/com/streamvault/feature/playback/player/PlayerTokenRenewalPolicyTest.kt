package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StreamInfo
import org.junit.Test

class PlayerTokenRenewalPolicyTest {

    @Test
    fun `expiry and title changes do not require replacing the playback source`() {
        val current = StreamInfo(
            url = "https://cdn.example/movie.mp4?token=stable",
            title = "Movie",
            expirationTime = 1_000_000L
        )
        val refreshed = current.copy(
            title = "Updated title",
            expirationTime = 2_000_000L
        )

        assertThat(isSamePlaybackRequestForRenewal(current, refreshed)).isTrue()
    }

    @Test
    fun `changed authorization headers require replacing the playback source`() {
        val current = StreamInfo(
            url = "https://cdn.example/movie.mp4",
            headers = mapOf("Authorization" to "Bearer old"),
            expirationTime = 1_000_000L
        )
        val refreshed = current.copy(
            headers = mapOf("Authorization" to "Bearer new"),
            expirationTime = 2_000_000L
        )

        assertThat(isSamePlaybackRequestForRenewal(current, refreshed)).isFalse()
    }

    @Test
    fun `stale refreshed expiry is rejected even when the URL changes`() {
        val previousExpiry = 1_000_000L
        val now = 1_000_000L

        assertThat(
            isUsableTokenRenewalExpiry(
                previousExpiryTime = previousExpiry,
                refreshedExpiryTime = previousExpiry,
                nowMs = now
            )
        ).isFalse()
        assertThat(
            isUsableTokenRenewalExpiry(
                previousExpiryTime = previousExpiry,
                refreshedExpiryTime = 999_000L,
                nowMs = now
            )
        ).isFalse()
        assertThat(
            isUsableTokenRenewalExpiry(
                previousExpiryTime = previousExpiry,
                refreshedExpiryTime = 1_001_000L,
                nowMs = now
            )
        ).isTrue()
        assertThat(
            isUsableTokenRenewalExpiry(
                previousExpiryTime = previousExpiry,
                refreshedExpiryTime = null,
                nowMs = now
            )
        ).isTrue()
    }

    @Test
    fun `next renewal requires a later expiry outside the lead window`() {
        val previousExpiry = 1_000_000L
        val now = 1_000_000L

        assertThat(
            shouldArmNextTokenRenewal(
                previousExpiryTime = previousExpiry,
                refreshedExpiryTime = 1_121_000L,
                nowMs = now
            )
        ).isTrue()
        assertThat(
            shouldArmNextTokenRenewal(
                previousExpiryTime = previousExpiry,
                refreshedExpiryTime = previousExpiry,
                nowMs = now
            )
        ).isFalse()
        assertThat(
            shouldArmNextTokenRenewal(
                previousExpiryTime = previousExpiry,
                refreshedExpiryTime = 1_059_000L,
                nowMs = now
            )
        ).isFalse()
        assertThat(
            shouldArmNextTokenRenewal(
                previousExpiryTime = previousExpiry,
                refreshedExpiryTime = Long.MAX_VALUE,
                nowMs = now
            )
        ).isFalse()
    }
}
