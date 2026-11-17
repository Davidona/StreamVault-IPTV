package com.streamvault.feature.live.home

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.live.home.InlineFullscreenAction.Consume
import com.streamvault.feature.live.home.InlineFullscreenAction.MoveFocus
import com.streamvault.feature.live.home.InlineFullscreenAction.Pass
import com.streamvault.feature.live.home.InlineFullscreenAction.Zap
import com.streamvault.feature.live.home.InlineFullscreenMode.CONTROLS
import com.streamvault.feature.live.home.InlineFullscreenMode.SUBTITLES
import com.streamvault.feature.live.home.InlineFullscreenMode.WATCHING
import org.junit.Test

class InlineFullscreenInputTest {

    private fun down(mode: InlineFullscreenMode, key: Int, repeat: Int = 0) =
        decideInlineFullscreenKey(mode, key, isDown = true, repeatCount = repeat)

    private fun up(mode: InlineFullscreenMode, key: Int) =
        decideInlineFullscreenKey(mode, key, isDown = false, repeatCount = 0)

    @Test
    fun `remote rewind and fast forward seek 10s in every mode, like the full player`() {
        for (mode in InlineFullscreenMode.entries) {
            assertThat(down(mode, KeyEvent.KEYCODE_MEDIA_REWIND)).isEqualTo(InlineFullscreenAction.SeekBackward)
            assertThat(down(mode, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD)).isEqualTo(InlineFullscreenAction.SeekForward)
            // The release must not leak to a focused button.
            assertThat(up(mode, KeyEvent.KEYCODE_MEDIA_REWIND)).isEqualTo(Consume)
        }
    }

    @Test
    fun `holding rewind does not fire a burst of seeks`() {
        assertThat(down(WATCHING, KeyEvent.KEYCODE_MEDIA_REWIND, repeat = 3)).isEqualTo(Consume)
    }

    @Test
    fun `play pause keys control playback`() {
        assertThat(down(WATCHING, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)).isEqualTo(InlineFullscreenAction.TogglePlayPause)
        assertThat(down(CONTROLS, KeyEvent.KEYCODE_MEDIA_PLAY)).isEqualTo(InlineFullscreenAction.Play)
        assertThat(down(SUBTITLES, KeyEvent.KEYCODE_MEDIA_PAUSE)).isEqualTo(InlineFullscreenAction.Pause)
    }

    @Test
    fun `while watching, up and down change channel`() {
        assertThat(down(WATCHING, KeyEvent.KEYCODE_DPAD_UP)).isEqualTo(Zap(-1))
        assertThat(down(WATCHING, KeyEvent.KEYCODE_DPAD_DOWN)).isEqualTo(Zap(1))
        assertThat(down(WATCHING, KeyEvent.KEYCODE_CHANNEL_UP)).isEqualTo(Zap(-1))
        assertThat(down(WATCHING, KeyEvent.KEYCODE_DPAD_DOWN, repeat = 1)).isEqualTo(Consume)
    }

    @Test
    fun `OK opens the controls on release so the same press cannot activate a button`() {
        assertThat(down(WATCHING, KeyEvent.KEYCODE_DPAD_CENTER)).isEqualTo(Consume)
        assertThat(up(WATCHING, KeyEvent.KEYCODE_DPAD_CENTER)).isEqualTo(InlineFullscreenAction.ShowControls)
        assertThat(up(WATCHING, KeyEvent.KEYCODE_DPAD_LEFT)).isEqualTo(InlineFullscreenAction.ShowControls)
    }

    @Test
    fun `in the controls, left and right move focus explicitly and OK goes to the button`() {
        assertThat(down(CONTROLS, KeyEvent.KEYCODE_DPAD_LEFT)).isEqualTo(MoveFocus(-1))
        assertThat(down(CONTROLS, KeyEvent.KEYCODE_DPAD_RIGHT)).isEqualTo(MoveFocus(1))
        assertThat(down(CONTROLS, KeyEvent.KEYCODE_DPAD_CENTER)).isEqualTo(Pass)
        // Down has nowhere to go: swallowed so focus can't escape to the hidden channel list.
        assertThat(down(CONTROLS, KeyEvent.KEYCODE_DPAD_DOWN)).isEqualTo(Consume)
        assertThat(down(CONTROLS, KeyEvent.KEYCODE_DPAD_UP)).isEqualTo(InlineFullscreenAction.HideControls)
    }

    @Test
    fun `in the subtitle list, up and down move and sideways is blocked`() {
        assertThat(down(SUBTITLES, KeyEvent.KEYCODE_DPAD_UP)).isEqualTo(MoveFocus(-1))
        assertThat(down(SUBTITLES, KeyEvent.KEYCODE_DPAD_DOWN)).isEqualTo(MoveFocus(1))
        assertThat(down(SUBTITLES, KeyEvent.KEYCODE_DPAD_LEFT)).isEqualTo(Consume)
        assertThat(down(SUBTITLES, KeyEvent.KEYCODE_DPAD_CENTER)).isEqualTo(Pass)
    }

    @Test
    fun `back steps out one level at a time`() {
        assertThat(up(SUBTITLES, KeyEvent.KEYCODE_BACK)).isEqualTo(InlineFullscreenAction.CloseSubtitles)
        assertThat(up(CONTROLS, KeyEvent.KEYCODE_BACK)).isEqualTo(InlineFullscreenAction.HideControls)
        assertThat(up(WATCHING, KeyEvent.KEYCODE_BACK)).isEqualTo(InlineFullscreenAction.ExitFullscreen)
        assertThat(down(WATCHING, KeyEvent.KEYCODE_BACK)).isEqualTo(Consume)
    }

    @Test
    fun `menu still opens the full player`() {
        assertThat(up(WATCHING, KeyEvent.KEYCODE_MENU)).isEqualTo(InlineFullscreenAction.OpenFullPlayer)
    }

    @Test
    fun `unrelated keys such as volume pass through`() {
        assertThat(down(WATCHING, KeyEvent.KEYCODE_VOLUME_UP)).isEqualTo(Pass)
    }
}
