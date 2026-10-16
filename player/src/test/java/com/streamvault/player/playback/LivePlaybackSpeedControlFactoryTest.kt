package com.streamvault.player.playback

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LivePlaybackSpeedControlFactoryTest {

    @Test
    fun `live control catches up when current offset exceeds target and media sets no speed bounds`() {
        val control = createLivePlaybackSpeedControl()
        control.setLiveConfiguration(
            MediaItem.LiveConfiguration.Builder()
                .setTargetOffsetMs(3_000L)
                .build()
        )

        val speed = control.getAdjustedPlaybackSpeed(
            /* liveOffsetUs= */ 5_000_000L,
            /* bufferedDurationUs= */ 1_000_000L
        )

        assertThat(speed > 1.0f).isTrue()
        assertThat(speed <= DefaultLivePlaybackSpeedControl.DEFAULT_FALLBACK_MAX_PLAYBACK_SPEED).isTrue()
    }
}
