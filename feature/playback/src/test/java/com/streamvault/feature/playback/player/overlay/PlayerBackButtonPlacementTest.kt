package com.streamvault.feature.playback.player.overlay

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.PlayerBackButtonVisibility
import org.junit.Test

class PlayerBackButtonPlacementTest {
    @Test
    fun `always mode uses standalone placement without controls`() {
        assertThat(
            playerBackButtonPlacement(
                mode = PlayerBackButtonVisibility.ALWAYS,
                controlsVisible = false,
                hasBlockingOverlay = false,
                isInPictureInPictureMode = false
            )
        ).isEqualTo(PlayerBackButtonPlacement.STANDALONE)
    }

    @Test
    fun `always and with-controls modes use controls placement when controls are visible`() {
        listOf(
            PlayerBackButtonVisibility.ALWAYS,
            PlayerBackButtonVisibility.WITH_CONTROLS
        ).forEach { mode ->
            assertThat(playerBackButtonPlacement(mode, true, false, false))
                .isEqualTo(PlayerBackButtonPlacement.CONTROLS_TOP_BAR)
        }
    }

    @Test
    fun `channel info overlay uses its own placement for visible modes`() {
        listOf(
            PlayerBackButtonVisibility.ALWAYS,
            PlayerBackButtonVisibility.WITH_CONTROLS
        ).forEach { mode ->
            assertThat(
                playerBackButtonPlacement(
                    mode = mode,
                    controlsVisible = false,
                    channelInfoOverlayVisible = true,
                    hasBlockingOverlay = false,
                    isInPictureInPictureMode = false
                )
            ).isEqualTo(PlayerBackButtonPlacement.CHANNEL_INFO_OVERLAY)
        }
    }

    @Test
    fun `with-controls mode is hidden while controls are hidden`() {
        assertThat(
            playerBackButtonPlacement(
                mode = PlayerBackButtonVisibility.WITH_CONTROLS,
                controlsVisible = false,
                hasBlockingOverlay = false,
                isInPictureInPictureMode = false
            )
        ).isEqualTo(PlayerBackButtonPlacement.HIDDEN)
    }

    @Test
    fun `blockers and picture in picture suppress every placement`() {
        assertThat(playerBackButtonPlacement(PlayerBackButtonVisibility.HIDDEN, true, false, false))
            .isEqualTo(PlayerBackButtonPlacement.HIDDEN)
        assertThat(playerBackButtonPlacement(PlayerBackButtonVisibility.ALWAYS, false, true, false))
            .isEqualTo(PlayerBackButtonPlacement.HIDDEN)
        assertThat(playerBackButtonPlacement(PlayerBackButtonVisibility.ALWAYS, false, false, true))
            .isEqualTo(PlayerBackButtonPlacement.HIDDEN)
    }
}
