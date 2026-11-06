package com.streamvault.feature.playback.player.overlay

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.feature.playback.player.PlayerTimeshiftUiState
import com.streamvault.player.PlayerTrack
import com.streamvault.player.SUBTITLE_OFFSET_MAX_MS
import com.streamvault.player.SUBTITLE_OFFSET_MIN_MS
import com.streamvault.player.TrackType
import com.streamvault.player.timeshift.LiveTimeshiftState
import com.streamvault.player.timeshift.LiveTimeshiftStatus
import org.junit.Test

class ChannelInfoOverlayActionsTest {

    private val minimal = ChannelInfoActionInputs(
        showTimeshiftControls = false,
        subtitleTrackCount = 0,
        liveTranslationAvailable = false,
        localSubtitleLoadingAvailable = false,
        audioTrackCount = 0,
        videoQualityCount = 0,
        channelVariantCount = 0,
        qualityOptionCount = 0,
        audioVideoSyncEnabled = false,
        isCastConnected = false,
        lastVisitedCategoryName = null,
        recordingStatus = null,
        canRestartProgram = false,
        canBrowseArchive = false
    )

    // Row

    @Test
    fun `row always has play, guide, record and more in that order`() {
        assertThat(channelInfoRowActions(minimal)).containsExactly(
            ChannelInfoRowAction.PLAY_PAUSE,
            ChannelInfoRowAction.GUIDE,
            ChannelInfoRowAction.RECORD,
            ChannelInfoRowAction.MORE
        ).inOrder()
    }

    @Test
    fun `row with every option keeps the approved order`() {
        val inputs = minimal.copy(
            localSubtitleLoadingAvailable = true,
            audioTrackCount = 2,
            canBrowseArchive = true
        )
        assertThat(channelInfoRowActions(inputs)).containsExactly(
            ChannelInfoRowAction.PLAY_PAUSE,
            ChannelInfoRowAction.SUBTITLES,
            ChannelInfoRowAction.AUDIO,
            ChannelInfoRowAction.GUIDE,
            ChannelInfoRowAction.RECORD,
            ChannelInfoRowAction.CATCH_UP,
            ChannelInfoRowAction.MORE
        ).inOrder()
    }

    @Test
    fun `subs shows for subtitle tracks, live translation or local subtitle loading`() {
        listOf(
            minimal.copy(subtitleTrackCount = 1),
            minimal.copy(liveTranslationAvailable = true),
            minimal.copy(localSubtitleLoadingAvailable = true)
        ).forEach { inputs ->
            assertThat(channelInfoRowActions(inputs)).contains(ChannelInfoRowAction.SUBTITLES)
        }
        assertThat(channelInfoRowActions(minimal)).doesNotContain(ChannelInfoRowAction.SUBTITLES)
    }

    @Test
    fun `audio shows only with audio tracks`() {
        assertThat(channelInfoRowActions(minimal)).doesNotContain(ChannelInfoRowAction.AUDIO)
        assertThat(channelInfoRowActions(minimal.copy(audioTrackCount = 1))).contains(ChannelInfoRowAction.AUDIO)
    }

    @Test
    fun `catch-up shows when the programme can restart or the archive is available`() {
        assertThat(channelInfoRowActions(minimal)).doesNotContain(ChannelInfoRowAction.CATCH_UP)
        assertThat(channelInfoRowActions(minimal.copy(canRestartProgram = true))).contains(ChannelInfoRowAction.CATCH_UP)
        assertThat(channelInfoRowActions(minimal.copy(canBrowseArchive = true))).contains(ChannelInfoRowAction.CATCH_UP)
    }

    // More

    @Test
    fun `more always has aspect ratio, mute, multiview, pip, cast and stats in order`() {
        assertThat(channelInfoMoreItems(minimal)).containsExactly(
            ChannelInfoMoreItem.ASPECT_RATIO,
            ChannelInfoMoreItem.MUTE,
            ChannelInfoMoreItem.MULTIVIEW,
            ChannelInfoMoreItem.PICTURE_IN_PICTURE,
            ChannelInfoMoreItem.CAST,
            ChannelInfoMoreItem.STATS
        ).inOrder()
    }

    @Test
    fun `more with every option keeps the approved order`() {
        val inputs = minimal.copy(
            videoQualityCount = 3,
            channelVariantCount = 2,
            qualityOptionCount = 2,
            audioVideoSyncEnabled = true,
            lastVisitedCategoryName = "News"
        )
        assertThat(channelInfoMoreItems(inputs)).containsExactly(
            ChannelInfoMoreItem.QUALITY,
            ChannelInfoMoreItem.VARIANTS,
            ChannelInfoMoreItem.FORMAT,
            ChannelInfoMoreItem.ASPECT_RATIO,
            ChannelInfoMoreItem.MUTE,
            ChannelInfoMoreItem.AV_SYNC,
            ChannelInfoMoreItem.MULTIVIEW,
            ChannelInfoMoreItem.PICTURE_IN_PICTURE,
            ChannelInfoMoreItem.CAST,
            ChannelInfoMoreItem.LAST_GROUP,
            ChannelInfoMoreItem.STATS
        ).inOrder()
    }

    @Test
    fun `quality shows with at least one quality option`() {
        assertThat(channelInfoMoreItems(minimal)).doesNotContain(ChannelInfoMoreItem.QUALITY)
        assertThat(channelInfoMoreItems(minimal.copy(videoQualityCount = 1))).contains(ChannelInfoMoreItem.QUALITY)
    }

    @Test
    fun `variants shows only with more than one source`() {
        assertThat(channelInfoMoreItems(minimal.copy(channelVariantCount = 1))).doesNotContain(ChannelInfoMoreItem.VARIANTS)
        assertThat(channelInfoMoreItems(minimal.copy(channelVariantCount = 2))).contains(ChannelInfoMoreItem.VARIANTS)
    }

    @Test
    fun `format shows only with more than one stream format`() {
        assertThat(channelInfoMoreItems(minimal.copy(qualityOptionCount = 1))).doesNotContain(ChannelInfoMoreItem.FORMAT)
        assertThat(channelInfoMoreItems(minimal.copy(qualityOptionCount = 2))).contains(ChannelInfoMoreItem.FORMAT)
    }

    @Test
    fun `av sync shows only when the setting is on and nothing is casting`() {
        assertThat(channelInfoMoreItems(minimal.copy(audioVideoSyncEnabled = true))).contains(ChannelInfoMoreItem.AV_SYNC)
        assertThat(channelInfoMoreItems(minimal.copy(audioVideoSyncEnabled = true, isCastConnected = true)))
            .doesNotContain(ChannelInfoMoreItem.AV_SYNC)
        assertThat(channelInfoMoreItems(minimal)).doesNotContain(ChannelInfoMoreItem.AV_SYNC)
    }

    @Test
    fun `last group shows only when a named group exists`() {
        assertThat(channelInfoMoreItems(minimal.copy(lastVisitedCategoryName = " "))).doesNotContain(ChannelInfoMoreItem.LAST_GROUP)
        assertThat(channelInfoMoreItems(minimal.copy(lastVisitedCategoryName = "Sports"))).contains(ChannelInfoMoreItem.LAST_GROUP)
    }

    // Trays

    @Test
    fun `record tray leads with record now, stop recording or cancel scheduled`() {
        assertThat(channelInfoRecordItems(null).first()).isEqualTo(ChannelInfoRecordItem.RECORD_NOW)
        assertThat(channelInfoRecordItems(RecordingStatus.RECORDING).first()).isEqualTo(ChannelInfoRecordItem.STOP_RECORDING)
        assertThat(channelInfoRecordItems(RecordingStatus.SCHEDULED).first()).isEqualTo(ChannelInfoRecordItem.CANCEL_SCHEDULED)
    }

    @Test
    fun `record tray always offers every schedule option after the first item`() {
        assertThat(channelInfoRecordItems(null).drop(1)).containsExactly(
            ChannelInfoRecordItem.SCHEDULE,
            ChannelInfoRecordItem.SCHEDULE_DAILY,
            ChannelInfoRecordItem.SCHEDULE_WEEKLY,
            ChannelInfoRecordItem.SCHEDULE_BY_TIME
        ).inOrder()
    }

    @Test
    fun `catch-up tray offers restart and archive only when available, guide always`() {
        assertThat(channelInfoCatchUpItems(minimal.copy(canBrowseArchive = true))).containsExactly(
            ChannelInfoCatchUpItem.BROWSE_ARCHIVE,
            ChannelInfoCatchUpItem.BROWSE_GUIDE
        ).inOrder()
        assertThat(channelInfoCatchUpItems(minimal.copy(canRestartProgram = true, canBrowseArchive = true))).containsExactly(
            ChannelInfoCatchUpItem.RESTART,
            ChannelInfoCatchUpItem.BROWSE_ARCHIVE,
            ChannelInfoCatchUpItem.BROWSE_GUIDE
        ).inOrder()
    }

    // DVR

    @Test
    fun `dvr transport waits for more than a second of buffer and a prepared engine`() {
        val ready = PlayerTimeshiftUiState(
            bufferDepthMs = 5_000L,
            engineState = LiveTimeshiftState(status = LiveTimeshiftStatus.LIVE)
        )
        assertThat(liveDvrTransportState(ready).transportReady).isTrue()
        assertThat(liveDvrTransportState(ready.copy(bufferDepthMs = 1_000L)).transportReady).isFalse()
        assertThat(
            liveDvrTransportState(ready.copy(engineState = LiveTimeshiftState(status = LiveTimeshiftStatus.PREPARING)))
                .transportReady
        ).isFalse()
    }

    @Test
    fun `forward and live need a ready buffer and room to seek toward live`() {
        val ready = PlayerTimeshiftUiState(
            bufferDepthMs = 5_000L,
            canSeekToLive = true,
            engineState = LiveTimeshiftState(status = LiveTimeshiftStatus.LIVE)
        )
        assertThat(liveDvrTransportState(ready).canSeekTowardLive).isTrue()
        assertThat(liveDvrTransportState(ready.copy(canSeekToLive = false)).canSeekTowardLive).isFalse()
        assertThat(liveDvrTransportState(ready.copy(bufferDepthMs = 0L)).canSeekTowardLive).isFalse()
    }

    @Test
    fun `behind live only beyond the one second tolerance`() {
        assertThat(liveDvrTransportState(PlayerTimeshiftUiState(bufferedBehindLiveMs = 1_000L)).isBehindLive).isFalse()
        assertThat(liveDvrTransportState(PlayerTimeshiftUiState(bufferedBehindLiveMs = 1_001L)).isBehindLive).isTrue()
        assertThat(liveDvrTransportState(PlayerTimeshiftUiState(bufferedBehindLiveMs = -50L)).behindLiveMs).isEqualTo(0L)
    }

    @Test
    fun `transport presses inside 240ms or while disabled are ignored`() {
        assertThat(shouldRunDvrTransportAction(enabled = true, nowMs = 1_000L, lastActionAtMs = 800L)).isFalse()
        assertThat(shouldRunDvrTransportAction(enabled = true, nowMs = 1_040L, lastActionAtMs = 800L)).isTrue()
        assertThat(shouldRunDvrTransportAction(enabled = false, nowMs = 5_000L, lastActionAtMs = 0L)).isFalse()
    }

    // LIVE tag placement

    @Test
    fun `live tag centres under the playhead when there is room`() {
        val offset = liveTagStartOffset(
            rowWidth = 1_000, tagWidth = 100, startTextWidth = 200,
            playheadFraction = 0.5f, gap = 10, minEndWidth = 100
        )
        assertThat(offset).isEqualTo(450)
    }

    @Test
    fun `live tag never overlaps the time range`() {
        val offset = liveTagStartOffset(
            rowWidth = 1_000, tagWidth = 100, startTextWidth = 300,
            playheadFraction = 0.1f, gap = 10, minEndWidth = 100
        )
        assertThat(offset).isEqualTo(310)
    }

    @Test
    fun `live tag leaves the minimum width for the end text`() {
        val offset = liveTagStartOffset(
            rowWidth = 1_000, tagWidth = 100, startTextWidth = 200,
            playheadFraction = 1f, gap = 10, minEndWidth = 100
        )
        assertThat(offset).isEqualTo(790)
    }

    @Test
    fun `live tag favours the time range when the row is too narrow for both limits`() {
        val offset = liveTagStartOffset(
            rowWidth = 400, tagWidth = 100, startTextWidth = 300,
            playheadFraction = 0.9f, gap = 10, minEndWidth = 100
        )
        assertThat(offset).isEqualTo(310)
    }

    // Track side sheet

    private fun track(id: String, type: TrackType, selected: Boolean = false) =
        PlayerTrack(id = id, name = "Track $id", language = null, type = type, isSelected = selected)

    @Test
    fun `subtitle sheet lists off, tracks, then the subtitle actions`() {
        val rows = tvTrackSheetRows(
            ChannelInfoPanel.SUBTITLES,
            listOf(track("a", TrackType.TEXT), track("b", TrackType.TEXT)),
            liveTranslationAvailable = false,
            liveTranslationActive = false
        )
        assertThat(rows.map { it.key }).containsExactly("off", "track:a", "track:b", "divider", "delay", "device").inOrder()
    }

    @Test
    fun `subtitle sheet shows off as current only when no track and no translation is active`() {
        val none = tvTrackSheetRows(ChannelInfoPanel.SUBTITLES, listOf(track("a", TrackType.TEXT)), false, false)
        assertThat(none.first { it.kind == TvTrackSheetRowKind.OFF }.isSelected).isTrue()

        val withTrack = tvTrackSheetRows(
            ChannelInfoPanel.SUBTITLES, listOf(track("a", TrackType.TEXT, selected = true)), false, false
        )
        assertThat(withTrack.first { it.kind == TvTrackSheetRowKind.OFF }.isSelected).isFalse()

        val translating = tvTrackSheetRows(ChannelInfoPanel.SUBTITLES, listOf(track("a", TrackType.TEXT)), true, true)
        assertThat(translating.first { it.kind == TvTrackSheetRowKind.OFF }.isSelected).isFalse()
    }

    @Test
    fun `live translation row sits after the tracks and replaces the selected track as current`() {
        val rows = tvTrackSheetRows(
            ChannelInfoPanel.SUBTITLES, listOf(track("a", TrackType.TEXT, selected = true)),
            liveTranslationAvailable = true, liveTranslationActive = true
        )
        assertThat(rows.map { it.key }).containsExactly("off", "track:a", "translation", "divider", "delay", "device").inOrder()
        assertThat(rows.first { it.kind == TvTrackSheetRowKind.TRACK }.isSelected).isFalse()
        assertThat(rows.first { it.kind == TvTrackSheetRowKind.LIVE_TRANSLATION }.isSelected).isTrue()
    }

    @Test
    fun `audio sheet is the track list alone and keeps the selection`() {
        val rows = tvTrackSheetRows(
            ChannelInfoPanel.AUDIO,
            listOf(track("1", TrackType.AUDIO, selected = true), track("2", TrackType.AUDIO)),
            liveTranslationAvailable = true,
            liveTranslationActive = true
        )
        assertThat(rows.map { it.key }).containsExactly("track:1", "track:2").inOrder()
        assertThat(rows.map { it.isSelected }).containsExactly(true, false).inOrder()
    }

    @Test
    fun `audio sheet with no tracks has no rows`() {
        assertThat(tvTrackSheetRows(ChannelInfoPanel.AUDIO, emptyList(), false, false)).isEmpty()
    }

    @Test
    fun `only more, subtitles, audio and subtitle delay are side sheets`() {
        assertThat(ChannelInfoPanel.entries.filter { it.isSideSheet }).containsExactly(
            ChannelInfoPanel.MORE,
            ChannelInfoPanel.SUBTITLES,
            ChannelInfoPanel.AUDIO,
            ChannelInfoPanel.SUBTITLE_DELAY
        )
    }

    @Test
    fun `delay rows enable both steps inside the limits and mark reset current at zero`() {
        val rows = tvSubtitleDelayRows(0)
        assertThat(rows.map { it.key }).containsExactly("earlier", "later", "reset").inOrder()
        assertThat(rows.map { it.isEnabled }).containsExactly(true, true, true).inOrder()
        assertThat(rows.first { it.key == "reset" }.isSelected).isTrue()
        assertThat(tvSubtitleDelayRows(300).first { it.key == "reset" }.isSelected).isFalse()
    }

    @Test
    fun `delay steps disable at the minimum and maximum offset`() {
        assertThat(tvSubtitleDelayRows(SUBTITLE_OFFSET_MIN_MS).first { it.key == "earlier" }.isEnabled).isFalse()
        assertThat(tvSubtitleDelayRows(SUBTITLE_OFFSET_MIN_MS).first { it.key == "later" }.isEnabled).isTrue()
        assertThat(tvSubtitleDelayRows(SUBTITLE_OFFSET_MAX_MS).first { it.key == "later" }.isEnabled).isFalse()
    }
}
