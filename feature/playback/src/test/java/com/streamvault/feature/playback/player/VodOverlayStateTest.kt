package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlayerChapter
import org.junit.Test

class VodOverlayStateTest {

    private val chapter = PlayerChapter(
        index = 1,
        title = "Opening",
        startTimeMs = 0L,
        endTimeMs = 60_000L
    )

    @Test
    fun `movie exposes chapter and track actions`() {
        val state = buildVodOverlayState(
            contentType = "MOVIE",
            isCatchUpPlayback = false,
            chapters = listOf(chapter),
            currentPositionMs = 30_000L,
            showEpisodesAction = false,
            subtitleTrackCount = 2,
            audioTrackCount = 1,
            videoQualityCount = 3,
            showExternalPlayerAction = true,
            isCastConnected = false
        )

        assertThat(state.isVod).isTrue()
        assertThat(state.currentChapter).isEqualTo(chapter)
        assertThat(state.showChapterAction).isTrue()
        assertThat(state.showEpisodesAction).isFalse()
        assertThat(state.showSubtitleAction).isTrue()
        assertThat(state.showAudioAction).isTrue()
        assertThat(state.showVideoQualityAction).isTrue()
        assertThat(state.showExternalPlayerAction).isTrue()
    }

    @Test
    fun `series episode exposes episode action only when host says it is available`() {
        val state = buildVodOverlayState(
            contentType = "SERIES_EPISODE",
            isCatchUpPlayback = false,
            chapters = emptyList(),
            currentPositionMs = 0L,
            showEpisodesAction = true,
            subtitleTrackCount = 0,
            audioTrackCount = 0,
            videoQualityCount = 0,
            showExternalPlayerAction = false,
            isCastConnected = false
        )

        assertThat(state.isVod).isTrue()
        assertThat(state.showEpisodesAction).isTrue()
        assertThat(state.showChapterAction).isFalse()
    }

    @Test
    fun `cast hides chapter action while preserving navigation state`() {
        val state = buildVodOverlayState(
            contentType = "MOVIE",
            isCatchUpPlayback = false,
            chapters = listOf(chapter),
            currentPositionMs = 30_000L,
            showEpisodesAction = false,
            subtitleTrackCount = 0,
            audioTrackCount = 0,
            videoQualityCount = 0,
            showExternalPlayerAction = false,
            isCastConnected = true
        )

        assertThat(state.chapters).containsExactly(chapter)
        assertThat(state.currentChapter).isEqualTo(chapter)
        assertThat(state.showChapterAction).isFalse()
    }

    @Test
    fun `catch up playback does not expose vod actions`() {
        val state = buildVodOverlayState(
            contentType = "MOVIE",
            isCatchUpPlayback = true,
            chapters = listOf(chapter),
            currentPositionMs = 30_000L,
            showEpisodesAction = true,
            subtitleTrackCount = 1,
            audioTrackCount = 1,
            videoQualityCount = 1,
            showExternalPlayerAction = true,
            isCastConnected = false
        )

        assertThat(state.isVod).isFalse()
        assertThat(state.showChapterAction).isFalse()
        assertThat(state.showEpisodesAction).isFalse()
        assertThat(state.showSubtitleAction).isFalse()
        assertThat(state.showAudioAction).isFalse()
        assertThat(state.showVideoQualityAction).isFalse()
        assertThat(state.showExternalPlayerAction).isFalse()
    }

    @Test
    fun `live content does not expose vod actions`() {
        val state = buildVodOverlayState(
            contentType = "LIVE",
            isCatchUpPlayback = false,
            chapters = listOf(chapter),
            currentPositionMs = 30_000L,
            showEpisodesAction = true,
            subtitleTrackCount = 1,
            audioTrackCount = 1,
            videoQualityCount = 1,
            showExternalPlayerAction = true,
            isCastConnected = false
        )

        assertThat(state.isVod).isFalse()
        assertThat(state.showChapterAction).isFalse()
        assertThat(state.showEpisodesAction).isFalse()
    }

    @Test
    fun `negative capability counts are treated as unavailable`() {
        val state = buildVodOverlayState(
            contentType = "MOVIE",
            isCatchUpPlayback = false,
            chapters = emptyList(),
            currentPositionMs = 0L,
            showEpisodesAction = false,
            subtitleTrackCount = -1,
            audioTrackCount = -1,
            videoQualityCount = -1,
            showExternalPlayerAction = true,
            isCastConnected = false
        )

        assertThat(state.showSubtitleAction).isFalse()
        assertThat(state.showAudioAction).isFalse()
        assertThat(state.showVideoQualityAction).isFalse()
        assertThat(state.showExternalPlayerAction).isTrue()
    }
}
