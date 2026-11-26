package com.streamvault.feature.playback.player

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.res.stringResource
import com.streamvault.core.ui.design.AppSpacing
import com.streamvault.core.ui.design.FocusSafeLazyColumn
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.feature.playback.R
import com.streamvault.feature.playback.player.overlay.formatPlaybackSpeedLabel
import com.streamvault.feature.playback.player.overlay.formatTimerPresetLabel

private class SettingsOption(
    val key: String,
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

/**
 * Settings frame: a main list with the current value on each row, and in-frame pages for speed, aspect ratio and the
 * two timers. Back, or the arrow beside a page title, returns to the list with focus on the row that opened the page,
 * and closes the frame from the list. Choosing an option returns to the list, so the new value is visible. The
 * start-side key (Left, mirrored in RTL) never leaves the frame or changes page. Rows that need a dialog shared with
 * live (quality, A/V sync) hand over to it and close this frame.
 */
@Composable
internal fun VodPlaybackSettingsSheet(
    state: VodOverlayState,
    playbackSpeed: Float,
    aspectRatio: AspectRatio,
    stopTimerMinutes: Int,
    idleTimerMinutes: Int,
    isMuted: Boolean,
    isCastConnected: Boolean,
    onOpenVideoTracks: () -> Unit,
    onOpenAudioVideoSync: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectStopTimer: (Int) -> Unit,
    onSelectIdleTimer: (Int) -> Unit,
    onSelectAspectRatio: (AspectRatio) -> Unit,
    onToggleMute: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onOpenExternalPlayer: () -> Unit,
    onOpenSplitScreen: () -> Unit,
    onCast: () -> Unit,
    onStopCasting: () -> Unit,
    onDismiss: () -> Unit,
) {
    var page by remember { mutableStateOf<VodSettingsPage?>(null) }
    // After a page closes, focus goes back to the row that opened it instead of the first row.
    var returnRow by remember { mutableStateOf<VodSettingsRow?>(null) }
    val rows = remember(state) { buildVodSettingsRows(state) }
    val pageFocus = remember { FocusRequester() }
    val startKey = startSideKey()
    val closePage = { returnRow = page?.row; page = null }

    val options = page?.let {
        settingsOptions(
            page = it,
            playbackSpeed = playbackSpeed,
            aspectRatio = aspectRatio,
            stopTimerMinutes = stopTimerMinutes,
            idleTimerMinutes = idleTimerMinutes,
            onChosen = closePage,
            onSelectSpeed = onSelectSpeed,
            onSelectAspectRatio = onSelectAspectRatio,
            onSelectStopTimer = onSelectStopTimer,
            onSelectIdleTimer = onSelectIdleTimer,
        )
    }
    val focusKey = if (options == null) {
        vodSettingsFocusRow(rows, returnRow)?.key
    } else {
        (options.firstOrNull { it.selected } ?: options.firstOrNull())?.key
    }

    VodPanelFrame(
        title = stringResource(page?.row?.let(::settingsLabelRes) ?: R.string.player_playback_settings),
        icon = page?.row?.let { vodSettingsIcon(it, isMuted) } ?: Icons.Default.Settings,
        onBack = if (page != null) closePage else null,
        onDismiss = { if (page != null) closePage() else onDismiss() },
    ) {
        // Each page gets its own list state, started at its target row: a shared state would carry the previous
        // page's scroll offset over and show the new page scrolled for a few frames before the focus effect runs.
        key(page) {
            val keys = options?.map { it.key } ?: rows.map { it.key }
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = vodSettingsFirstVisibleIndex(keys.indexOf(focusKey)),
            )
            LaunchedEffect(Unit) {
                // Fallback for when the window had not taken its own initial focus yet at the first placement.
                withFrameNanos { }
                pageFocus.requestFocusSafely("VodPlaybackSettingsSheet", "Active page target row")
            }
            FocusSafeLazyColumn(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    // Every row is full width, so the start key has nowhere to go: swallow it instead of letting the
                    // focus search leave the frame.
                    .onKeyEvent { event -> event.key == startKey },
                state = listState,
                verticalArrangement = Arrangement.spacedBy(AppSpacing().xs),
            ) {
                if (options == null) {
                    items(rows, key = { it.key }) { row ->
                        SettingsMainRow(
                            row = row,
                            modifier = if (row.key == focusKey) rememberPlacementFocus(pageFocus, "VodPlaybackSettingsSheet") else Modifier,
                            value = settingsValue(row, playbackSpeed, aspectRatio, stopTimerMinutes, idleTimerMinutes, isMuted),
                            isMuted = isMuted,
                            isCastConnected = isCastConnected,
                            onClick = {
                                val target = vodSettingsPageFor(row)
                                if (target != null) {
                                    returnRow = row
                                    page = target
                                } else {
                                    when (row) {
                                        VodSettingsRow.QUALITY -> onOpenVideoTracks()
                                        VodSettingsRow.MUTE -> onToggleMute()
                                        VodSettingsRow.PICTURE_IN_PICTURE -> onEnterPictureInPicture()
                                        VodSettingsRow.EXTERNAL_PLAYER -> onOpenExternalPlayer()
                                        VodSettingsRow.SPLIT_SCREEN -> onOpenSplitScreen()
                                        VodSettingsRow.CAST -> if (isCastConnected) onStopCasting() else onCast()
                                        VodSettingsRow.AV_SYNC -> onOpenAudioVideoSync()
                                        else -> Unit
                                    }
                                }
                            },
                        )
                    }
                } else {
                    items(options, key = { it.key }) { option ->
                        VodPanelRow(
                            title = option.label,
                            modifier = if (option.key == focusKey) rememberPlacementFocus(pageFocus, "VodPlaybackSettingsSheet") else Modifier,
                            selected = option.selected,
                            onClick = option.onSelect,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsMainRow(
    row: VodSettingsRow,
    modifier: Modifier,
    value: String?,
    isMuted: Boolean,
    isCastConnected: Boolean,
    onClick: () -> Unit,
) {
    val title = when (row) {
        VodSettingsRow.MUTE -> stringResource(if (isMuted) R.string.player_unmute else R.string.player_mute)
        VodSettingsRow.CAST -> stringResource(if (isCastConnected) R.string.player_stop_casting else R.string.player_cast)
        else -> stringResource(settingsLabelRes(row))
    }
    VodPanelRow(
        title = title,
        modifier = modifier,
        value = value,
        icon = vodSettingsIcon(row, isMuted),
        opensPage = vodSettingsPageFor(row) != null,
        onClick = onClick,
    )
}

/** Current value shown at the end of a row, or null when the row is an action. */
@Composable
private fun settingsValue(
    row: VodSettingsRow,
    playbackSpeed: Float,
    aspectRatio: AspectRatio,
    stopTimerMinutes: Int,
    idleTimerMinutes: Int,
    isMuted: Boolean,
): String? = when (row) {
    VodSettingsRow.SPEED -> formatPlaybackSpeedLabel(playbackSpeed)
    VodSettingsRow.STOP_TIMER -> formatTimerPresetLabel(stopTimerMinutes)
    VodSettingsRow.IDLE_TIMER -> formatTimerPresetLabel(idleTimerMinutes)
    VodSettingsRow.ASPECT -> stringResource(aspectRatio.labelRes())
    VodSettingsRow.MUTE -> if (isMuted) stringResource(R.string.player_muted_badge) else null
    else -> null
}

@Composable
private fun settingsOptions(
    page: VodSettingsPage,
    playbackSpeed: Float,
    aspectRatio: AspectRatio,
    stopTimerMinutes: Int,
    idleTimerMinutes: Int,
    onChosen: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    onSelectAspectRatio: (AspectRatio) -> Unit,
    onSelectStopTimer: (Int) -> Unit,
    onSelectIdleTimer: (Int) -> Unit,
): List<SettingsOption> = when (page) {
    VodSettingsPage.SPEED -> vodSettingsSpeedOptions().map { speed ->
        SettingsOption("speed:$speed", formatPlaybackSpeedLabel(speed), speed == playbackSpeed) {
            onSelectSpeed(speed)
            onChosen()
        }
    }
    VodSettingsPage.ASPECT -> AspectRatio.entries.map { ratio ->
        SettingsOption("aspect:${ratio.name}", stringResource(ratio.labelRes()), ratio == aspectRatio) {
            onSelectAspectRatio(ratio)
            onChosen()
        }
    }
    VodSettingsPage.STOP_TIMER -> vodSettingsTimerOptions().map { minutes ->
        SettingsOption("stop:$minutes", formatTimerPresetLabel(minutes), minutes == stopTimerMinutes) {
            onSelectStopTimer(minutes)
            onChosen()
        }
    }
    VodSettingsPage.IDLE_TIMER -> vodSettingsTimerOptions().map { minutes ->
        SettingsOption("idle:$minutes", formatTimerPresetLabel(minutes), minutes == idleTimerMinutes) {
            onSelectIdleTimer(minutes)
            onChosen()
        }
    }
}

@StringRes
private fun settingsLabelRes(row: VodSettingsRow): Int = when (row) {
    VodSettingsRow.QUALITY -> R.string.player_video_quality
    VodSettingsRow.SPEED -> R.string.player_playback_speed_title
    VodSettingsRow.STOP_TIMER -> R.string.player_stop_playback_after
    VodSettingsRow.IDLE_TIMER -> R.string.player_idle_standby_after
    VodSettingsRow.ASPECT -> R.string.player_aspect_ratio
    VodSettingsRow.MUTE -> R.string.player_mute
    VodSettingsRow.PICTURE_IN_PICTURE -> R.string.player_picture_in_picture
    VodSettingsRow.EXTERNAL_PLAYER -> R.string.player_open_in_external_player
    VodSettingsRow.SPLIT_SCREEN -> R.string.multiview_nav
    VodSettingsRow.CAST -> R.string.player_cast
    VodSettingsRow.AV_SYNC -> R.string.player_av_sync_short
}

private fun vodSettingsIcon(row: VodSettingsRow, isMuted: Boolean): ImageVector = when (row) {
    VodSettingsRow.QUALITY -> Icons.Default.HighQuality
    VodSettingsRow.SPEED -> Icons.Default.Speed
    VodSettingsRow.STOP_TIMER -> Icons.Default.Timer
    VodSettingsRow.IDLE_TIMER -> Icons.Default.Bedtime
    VodSettingsRow.ASPECT -> Icons.Default.AspectRatio
    VodSettingsRow.MUTE -> if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp
    VodSettingsRow.PICTURE_IN_PICTURE -> Icons.Default.PictureInPictureAlt
    VodSettingsRow.EXTERNAL_PLAYER -> Icons.Default.OpenInNew
    VodSettingsRow.SPLIT_SCREEN -> Icons.Default.ViewWeek
    VodSettingsRow.CAST -> Icons.Default.Cast
    VodSettingsRow.AV_SYNC -> Icons.Default.Sync
}
