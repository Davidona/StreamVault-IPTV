package com.streamvault.feature.playback.player.overlay

import com.streamvault.domain.model.RecordingStatus
import com.streamvault.feature.playback.player.PlayerTimeshiftUiState
import com.streamvault.player.PlayerTrack
import com.streamvault.player.SUBTITLE_OFFSET_MAX_MS
import com.streamvault.player.SUBTITLE_OFFSET_MIN_MS
import com.streamvault.player.timeshift.LiveTimeshiftStatus

internal enum class ChannelInfoPanel {
    LIVE_DVR,
    RECORD,
    CATCH_UP,
    MORE,
    SUBTITLES,
    AUDIO,
    SUBTITLE_DELAY;

    /** Panels shown in the end-edge side sheet rather than in a tray above the action row. */
    val isSideSheet: Boolean get() = this == MORE || this == SUBTITLES || this == AUDIO || this == SUBTITLE_DELAY
}

/** Everything that decides which live overlay actions exist; kept free of Compose so it can be unit tested. */
internal data class ChannelInfoActionInputs(
    val showTimeshiftControls: Boolean,
    val subtitleTrackCount: Int,
    val liveTranslationAvailable: Boolean,
    val localSubtitleLoadingAvailable: Boolean,
    val audioTrackCount: Int,
    val videoQualityCount: Int,
    val channelVariantCount: Int,
    val qualityOptionCount: Int,
    val audioVideoSyncEnabled: Boolean,
    val isCastConnected: Boolean,
    val lastVisitedCategoryName: String?,
    val recordingStatus: RecordingStatus?,
    val canRestartProgram: Boolean,
    val canBrowseArchive: Boolean
) {
    val hasCatchUpOptions: Boolean get() = canRestartProgram || canBrowseArchive
}

internal enum class ChannelInfoRowAction {
    PLAY_PAUSE,
    SUBTITLES,
    AUDIO,
    GUIDE,
    RECORD,
    CATCH_UP,
    MORE
}

internal enum class ChannelInfoMoreItem {
    QUALITY,
    VARIANTS,
    FORMAT,
    ASPECT_RATIO,
    MUTE,
    AV_SYNC,
    MULTIVIEW,
    PICTURE_IN_PICTURE,
    CAST,
    LAST_GROUP,
    STATS
}

internal enum class ChannelInfoRecordItem {
    RECORD_NOW,
    STOP_RECORDING,
    CANCEL_SCHEDULED,
    SCHEDULE,
    SCHEDULE_DAILY,
    SCHEDULE_WEEKLY,
    SCHEDULE_BY_TIME
}

internal enum class ChannelInfoCatchUpItem {
    RESTART,
    BROWSE_ARCHIVE,
    BROWSE_GUIDE
}

internal fun channelInfoRowActions(inputs: ChannelInfoActionInputs): List<ChannelInfoRowAction> = buildList {
    add(ChannelInfoRowAction.PLAY_PAUSE)
    if (inputs.subtitleTrackCount > 0 || inputs.liveTranslationAvailable || inputs.localSubtitleLoadingAvailable) {
        add(ChannelInfoRowAction.SUBTITLES)
    }
    if (inputs.audioTrackCount > 0) add(ChannelInfoRowAction.AUDIO)
    add(ChannelInfoRowAction.GUIDE)
    add(ChannelInfoRowAction.RECORD)
    if (inputs.hasCatchUpOptions) add(ChannelInfoRowAction.CATCH_UP)
    add(ChannelInfoRowAction.MORE)
}

internal fun channelInfoMoreItems(inputs: ChannelInfoActionInputs): List<ChannelInfoMoreItem> = buildList {
    if (inputs.videoQualityCount > 0) add(ChannelInfoMoreItem.QUALITY)
    if (inputs.channelVariantCount > 1) add(ChannelInfoMoreItem.VARIANTS)
    if (inputs.qualityOptionCount > 1) add(ChannelInfoMoreItem.FORMAT)
    add(ChannelInfoMoreItem.ASPECT_RATIO)
    add(ChannelInfoMoreItem.MUTE)
    if (inputs.audioVideoSyncEnabled && !inputs.isCastConnected) add(ChannelInfoMoreItem.AV_SYNC)
    add(ChannelInfoMoreItem.MULTIVIEW)
    add(ChannelInfoMoreItem.PICTURE_IN_PICTURE)
    add(ChannelInfoMoreItem.CAST)
    if (!inputs.lastVisitedCategoryName.isNullOrBlank()) add(ChannelInfoMoreItem.LAST_GROUP)
    add(ChannelInfoMoreItem.STATS)
}

internal enum class TvTrackSheetRowKind { OFF, TRACK, LIVE_TRANSLATION, DIVIDER, SUBTITLE_DELAY, LOAD_FROM_DEVICE, DELAY_EARLIER, DELAY_LATER, DELAY_RESET }

/** One row of the subtitles or audio side sheet. [key] is stable across track refreshes so focus survives them. */
internal data class TvTrackSheetRow(
    val key: String,
    val kind: TvTrackSheetRowKind,
    val trackId: String? = null,
    val name: String? = null,
    val isSelected: Boolean = false,
    val isEnabled: Boolean = true
)

/** Rows for the subtitle delay panel; a step is disabled once the offset reaches its limit. */
internal fun tvSubtitleDelayRows(offsetMs: Int): List<TvTrackSheetRow> = listOf(
    TvTrackSheetRow(
        key = "earlier",
        kind = TvTrackSheetRowKind.DELAY_EARLIER,
        isEnabled = offsetMs > SUBTITLE_OFFSET_MIN_MS
    ),
    TvTrackSheetRow(
        key = "later",
        kind = TvTrackSheetRowKind.DELAY_LATER,
        isEnabled = offsetMs < SUBTITLE_OFFSET_MAX_MS
    ),
    TvTrackSheetRow(key = "reset", kind = TvTrackSheetRowKind.DELAY_RESET, isSelected = offsetMs == 0)
)

/**
 * Rows for the live TV subtitles or audio side sheet, in the order the former dialog listed them.
 * Only [ChannelInfoPanel.SUBTITLES] gets the Off row and the subtitle actions; audio is the track list alone.
 */
internal fun tvTrackSheetRows(
    panel: ChannelInfoPanel,
    tracks: List<PlayerTrack>,
    liveTranslationAvailable: Boolean,
    liveTranslationActive: Boolean
): List<TvTrackSheetRow> = buildList {
    val isSubtitles = panel == ChannelInfoPanel.SUBTITLES
    if (isSubtitles) {
        add(
            TvTrackSheetRow(
                key = "off",
                kind = TvTrackSheetRowKind.OFF,
                isSelected = tracks.none { it.isSelected } && !liveTranslationActive
            )
        )
    }
    tracks.forEach { track ->
        add(
            TvTrackSheetRow(
                key = "track:${track.id}",
                kind = TvTrackSheetRowKind.TRACK,
                trackId = track.id,
                name = track.name,
                isSelected = track.isSelected && !(isSubtitles && liveTranslationActive)
            )
        )
    }
    if (isSubtitles) {
        if (liveTranslationAvailable) {
            add(
                TvTrackSheetRow(
                    key = "translation",
                    kind = TvTrackSheetRowKind.LIVE_TRANSLATION,
                    isSelected = liveTranslationActive
                )
            )
        }
        // Separates the choices above from the actions below so the actions don't read as tracks.
        add(TvTrackSheetRow(key = "divider", kind = TvTrackSheetRowKind.DIVIDER))
        add(TvTrackSheetRow(key = "delay", kind = TvTrackSheetRowKind.SUBTITLE_DELAY))
        add(TvTrackSheetRow(key = "device", kind = TvTrackSheetRowKind.LOAD_FROM_DEVICE))
    }
}

internal fun channelInfoRecordItems(recordingStatus: RecordingStatus?): List<ChannelInfoRecordItem> = buildList {
    add(
        when (recordingStatus) {
            RecordingStatus.RECORDING -> ChannelInfoRecordItem.STOP_RECORDING
            RecordingStatus.SCHEDULED -> ChannelInfoRecordItem.CANCEL_SCHEDULED
            else -> ChannelInfoRecordItem.RECORD_NOW
        }
    )
    add(ChannelInfoRecordItem.SCHEDULE)
    add(ChannelInfoRecordItem.SCHEDULE_DAILY)
    add(ChannelInfoRecordItem.SCHEDULE_WEEKLY)
    add(ChannelInfoRecordItem.SCHEDULE_BY_TIME)
}

internal fun channelInfoCatchUpItems(inputs: ChannelInfoActionInputs): List<ChannelInfoCatchUpItem> = buildList {
    if (inputs.canRestartProgram) add(ChannelInfoCatchUpItem.RESTART)
    if (inputs.canBrowseArchive) add(ChannelInfoCatchUpItem.BROWSE_ARCHIVE)
    add(ChannelInfoCatchUpItem.BROWSE_GUIDE)
}

/** DVR transport readiness, shared by the phone and TV trays so both apply the same buffer rules. */
internal data class LiveDvrTransportState(
    val transportReady: Boolean,
    val behindLiveMs: Long,
    val bufferDepthMs: Long,
    val canSeekTowardLive: Boolean
) {
    val isBehindLive: Boolean get() = behindLiveMs > LIVE_EDGE_TOLERANCE_MS

    companion object {
        const val LIVE_EDGE_TOLERANCE_MS = 1_000L
    }
}

internal fun liveDvrTransportState(timeshiftUiState: PlayerTimeshiftUiState): LiveDvrTransportState {
    val transportReady = timeshiftUiState.bufferDepthMs > LiveDvrTransportState.LIVE_EDGE_TOLERANCE_MS &&
        timeshiftUiState.engineState.status != LiveTimeshiftStatus.PREPARING
    return LiveDvrTransportState(
        transportReady = transportReady,
        behindLiveMs = timeshiftUiState.bufferedBehindLiveMs.coerceAtLeast(0L),
        bufferDepthMs = timeshiftUiState.bufferDepthMs.coerceAtLeast(0L),
        canSeekTowardLive = transportReady && timeshiftUiState.canSeekToLive
    )
}

/** Ignores transport presses closer together than this, so a held D-pad key can't queue a burst of seeks. */
internal const val DVR_TRANSPORT_REPEAT_GUARD_MS = 240L

internal fun shouldRunDvrTransportAction(
    enabled: Boolean,
    nowMs: Long,
    lastActionAtMs: Long
): Boolean = enabled && nowMs - lastActionAtMs >= DVR_TRANSPORT_REPEAT_GUARD_MS

/**
 * Start offset for the LIVE tag: centred under the playhead, but never overlapping the time range
 * on the start side or squeezing the end text below [minEndWidth].
 */
internal fun liveTagStartOffset(
    rowWidth: Int,
    tagWidth: Int,
    startTextWidth: Int,
    playheadFraction: Float,
    gap: Int,
    minEndWidth: Int
): Int {
    val lowest = startTextWidth + gap
    val highest = maxOf(lowest, rowWidth - tagWidth - gap - minEndWidth)
    val centred = (playheadFraction.coerceIn(0f, 1f) * rowWidth).toInt() - tagWidth / 2
    return centred.coerceIn(lowest, highest)
}
