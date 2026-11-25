package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.playback.player.overlay.formatPlaybackSpeedLabel
import org.junit.Test

class VodSettingsRowsTest {
    private fun state(
        quality: Boolean = true,
        external: Boolean = true,
        avSync: Boolean = true,
    ) = VodOverlayState(
        isVod = true,
        chapters = emptyList(),
        currentChapter = null,
        showChapterAction = false,
        showChapterSkipActions = false,
        showPreviousEpisodeAction = false,
        showNextEpisodeAction = false,
        showEpisodesAction = true,
        showSubtitleAction = true,
        showAudioAction = true,
        showVideoQualityAction = quality,
        showAudioVideoSyncAction = avSync,
        showExternalPlayerAction = external,
        showSettingsAction = true,
    )

    @Test
    fun allRowsAreListedInDrawerOrder() {
        assertThat(buildVodSettingsRows(state()).map { it.key }).containsExactly(
            "quality", "speed", "stop_timer", "idle_timer", "aspect", "mute",
            "pip", "external", "split_screen", "cast", "av_sync",
        ).inOrder()
    }

    @Test
    fun rowsTheTitleCannotUseAreLeftOut() {
        val rows = buildVodSettingsRows(
            state(quality = false, external = false, avSync = false),
        )

        assertThat(rows.map { it.key }).containsExactly(
            "speed", "stop_timer", "idle_timer", "aspect", "mute", "pip", "split_screen", "cast",
        ).inOrder()
    }

    @Test
    fun audioSubtitlesAndEpisodesStayOnTheOverlayOnly() {
        val keys = buildVodSettingsRows(state()).map { it.key }

        assertThat(keys).containsNoneOf("subtitles", "audio", "episodes")
    }

    @Test
    fun onlySpeedTimersAndAspectOpenInFramePages() {
        val pageRows = VodSettingsRow.entries.filter { vodSettingsPageFor(it) != null }

        assertThat(pageRows).containsExactly(
            VodSettingsRow.SPEED, VodSettingsRow.STOP_TIMER, VodSettingsRow.IDLE_TIMER, VodSettingsRow.ASPECT,
        )
        VodSettingsPage.entries.forEach { assertThat(vodSettingsPageFor(it.row)).isEqualTo(it) }
    }

    @Test
    fun focusReturnsToTheRowThatOpenedThePage() {
        val rows = buildVodSettingsRows(state())

        assertThat(vodSettingsFocusRow(rows, VodSettingsRow.ASPECT)).isEqualTo(VodSettingsRow.ASPECT)
    }

    @Test
    fun focusStartsOnTheFirstRowWithoutAReturnRow() {
        val rows = buildVodSettingsRows(state())

        assertThat(vodSettingsFocusRow(rows, null)).isEqualTo(VodSettingsRow.QUALITY)
    }

    @Test
    fun focusFallsBackToTheFirstRowWhenTheReturnRowDisappeared() {
        val rows = buildVodSettingsRows(state(quality = false))

        assertThat(vodSettingsFocusRow(rows, VodSettingsRow.QUALITY)).isEqualTo(VodSettingsRow.SPEED)
    }

    @Test
    fun timerOptionsStartWithOffAndAscend() {
        assertThat(vodSettingsTimerOptions()).containsExactly(0, 15, 30, 45, 60, 90, 120).inOrder()
    }

    @Test
    fun speedOptionsMatchThePlayerRange() {
        assertThat(vodSettingsSpeedOptions()).containsExactly(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f).inOrder()
    }

    @Test
    fun speedLabelsDropTrailingZeros() {
        assertThat(vodSettingsSpeedOptions().map(::formatPlaybackSpeedLabel))
            .containsExactly("0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x").inOrder()
    }

    @Test
    fun everyAspectRatioHasItsOwnLabel() {
        assertThat(AspectRatio.entries.map { it.labelRes() }.toSet()).hasSize(AspectRatio.entries.size)
    }

    @Test
    fun shortPagesOpenAtTheirTop() {
        assertThat(vodSettingsFirstVisibleIndex(-1)).isEqualTo(0)
        assertThat(vodSettingsFirstVisibleIndex(0)).isEqualTo(0)
        assertThat(vodSettingsFirstVisibleIndex(3)).isEqualTo(0)
    }

    @Test
    fun deepTargetsKeepRowsAboveThemVisible() {
        assertThat(vodSettingsFirstVisibleIndex(6)).isEqualTo(3)
    }
}
