package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.PlaybackStartMode
import org.junit.Test

class PlaybackResumeActionTest {
    @Test fun `external resume seeks directly while normal playback prompts`() {
        assertThat(playbackResumeAction(PlaybackStartMode.RESUME, 30_000, 120_000))
            .isEqualTo(PlaybackResumeAction.Seek(30_000))
        assertThat(playbackResumeAction(PlaybackStartMode.PROMPT, 30_000, 120_000))
            .isEqualTo(PlaybackResumeAction.Prompt(30_000))
    }

    @Test fun `resume honors short saved positions without introducing in-app prompts`() {
        assertThat(playbackResumeAction(PlaybackStartMode.RESUME, 2_000, 120_000))
            .isEqualTo(PlaybackResumeAction.Seek(2_000))
        assertThat(playbackResumeAction(PlaybackStartMode.PROMPT, 2_000, 120_000))
            .isEqualTo(PlaybackResumeAction.None)
    }

    @Test fun `start over ignores saved progress`() {
        assertThat(playbackResumeAction(PlaybackStartMode.START_OVER, 30_000, 120_000))
            .isEqualTo(PlaybackResumeAction.None)
    }

    @Test fun `missing zero and completed history never seeks or prompts`() {
        for (mode in PlaybackStartMode.entries) {
            for ((position, duration) in listOf(null to null, 0L to 120_000L,
                120_000L to 120_000L, 119_000L to 120_000L)) {
                assertThat(playbackResumeAction(mode, position, duration)).isEqualTo(PlaybackResumeAction.None)
            }
        }
    }
}
