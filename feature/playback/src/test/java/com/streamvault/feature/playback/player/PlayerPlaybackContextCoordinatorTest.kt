package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StreamInfo
import org.junit.Test

class PlayerPlaybackContextCoordinatorTest {

    @Test
    fun `resolved stream can be cleared without losing logical playback identity`() {
        val coordinator = PlayerPlaybackContextCoordinator()
        coordinator.currentContentId = 42L
        coordinator.currentContentType = ContentType.LIVE
        coordinator.currentStreamUrl = "https://example.test/logical"
        coordinator.currentResolvedPlaybackUrl = "https://example.test/resolved"
        coordinator.currentResolvedStreamInfo = StreamInfo(
            url = coordinator.currentResolvedPlaybackUrl,
            title = "Example"
        )

        coordinator.clearResolvedStream()

        assertThat(coordinator.currentContentId).isEqualTo(42L)
        assertThat(coordinator.currentContentType).isEqualTo(ContentType.LIVE)
        assertThat(coordinator.currentStreamUrl).isEqualTo("https://example.test/logical")
        assertThat(coordinator.currentResolvedPlaybackUrl).isEmpty()
        assertThat(coordinator.currentResolvedStreamInfo).isNull()
    }
}
