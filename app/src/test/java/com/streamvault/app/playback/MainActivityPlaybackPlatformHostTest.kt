package com.streamvault.app.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.playback.api.PlaybackPictureInPictureState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class MainActivityPlaybackPlatformHostTest {

    @Test
    fun `forwards PiP state and operations without changing them`() {
        val pictureInPictureMode = MutableStateFlow(false)
        val operations = FakeMainActivityPlaybackPlatformOperations(
            pictureInPictureMode = pictureInPictureMode,
            enterPictureInPictureResult = true
        )
        val host = MainActivityPlaybackPlatformHost(operations)
        val state = PlaybackPictureInPictureState(
            enabled = true,
            isPlaying = true,
            videoWidth = 1_920,
            videoHeight = 1_080,
            pixelWidthHeightRatio = 1.25f
        )

        host.updatePictureInPictureState(state)
        host.clearPictureInPictureState()
        host.openCastRouteChooser()

        assertThat(host.pictureInPictureMode).isSameInstanceAs(pictureInPictureMode)
        assertThat(operations.pictureInPictureStates).containsExactly(state)
        assertThat(operations.clearPictureInPictureStateCalls).isEqualTo(1)
        assertThat(host.enterPictureInPicture()).isTrue()
        assertThat(operations.enterPictureInPictureCalls).isEqualTo(1)
        assertThat(operations.openCastRouteChooserCalls).isEqualTo(1)
    }

    @Test
    fun `forwards keep screen on true and false`() {
        val operations = FakeMainActivityPlaybackPlatformOperations(
            pictureInPictureMode = MutableStateFlow(false),
            enterPictureInPictureResult = false
        )
        val host = MainActivityPlaybackPlatformHost(operations)

        host.setKeepScreenOn(true)
        host.setKeepScreenOn(false)

        assertThat(operations.keepScreenOnValues).containsExactly(true, false).inOrder()
    }

    private class FakeMainActivityPlaybackPlatformOperations(
        override val pictureInPictureMode: MutableStateFlow<Boolean>,
        private val enterPictureInPictureResult: Boolean
    ) : MainActivityPlaybackPlatformOperations {
        val pictureInPictureStates = mutableListOf<PlaybackPictureInPictureState>()
        var clearPictureInPictureStateCalls = 0
        var enterPictureInPictureCalls = 0
        var openCastRouteChooserCalls = 0
        val keepScreenOnValues = mutableListOf<Boolean>()

        override fun updatePictureInPictureState(state: PlaybackPictureInPictureState) {
            pictureInPictureStates += state
        }

        override fun clearPictureInPictureState() {
            clearPictureInPictureStateCalls++
        }

        override fun enterPictureInPicture(): Boolean {
            enterPictureInPictureCalls++
            return enterPictureInPictureResult
        }

        override fun openCastRouteChooser() {
            openCastRouteChooserCalls++
        }

        override fun setKeepScreenOn(enabled: Boolean) {
            keepScreenOnValues += enabled
        }
    }
}
