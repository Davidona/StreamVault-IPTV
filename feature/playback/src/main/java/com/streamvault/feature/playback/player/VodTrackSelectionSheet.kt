package com.streamvault.feature.playback.player

import androidx.annotation.StringRes
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.AppSpacing
import com.streamvault.core.ui.design.FocusSafeLazyColumn
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.core.ui.design.scrollListToTopOnFocus
import com.streamvault.feature.playback.R
import com.streamvault.player.PlayerTrack
import com.streamvault.player.SUBTITLE_OFFSET_STEP_MS
import com.streamvault.player.TrackType
import com.streamvault.player.SubtitleTrackSource
import kotlin.math.roundToInt

@Composable
internal fun VodTrackSelectionSheet(
    trackType: TrackType,
    tracks: List<PlayerTrack>,
    offsetMs: Int,
    textScale: Float,
    backgroundColor: Int,
    onlineAvailable: Boolean,
    stremioSubtitlePicker: StremioSubtitlePickerState = StremioSubtitlePickerState(),
    onDismiss: () -> Unit,
    onSelectTrack: (String?) -> Unit,
    onSearchOnline: () -> Unit,
    onLoadLocal: () -> Unit,
    onAdjustOffset: (Int) -> Unit,
    onResetOffset: () -> Unit,
    onSetTextScale: (Float) -> Unit,
    onSetOpacity: (Int) -> Unit,
    onSelectStremioSubtitle: (StremioPlayerSubtitleOption) -> Unit = {},
    onRetryStremioSubtitles: () -> Unit = {},
) {
    if (trackType == TrackType.TEXT) {
        VodSubtitlePanel(
            tracks = tracks,
            offsetMs = offsetMs,
            textScale = textScale,
            backgroundColor = backgroundColor,
            onlineAvailable = onlineAvailable,
            stremioSubtitlePicker = stremioSubtitlePicker,
            onDismiss = onDismiss,
            onSelectTrack = onSelectTrack,
            onSearchOnline = onSearchOnline,
            onLoadLocal = onLoadLocal,
            onAdjustOffset = onAdjustOffset,
            onResetOffset = onResetOffset,
            onSetTextScale = onSetTextScale,
            onSetOpacity = onSetOpacity,
            onSelectStremioSubtitle = onSelectStremioSubtitle,
            onRetryStremioSubtitles = onRetryStremioSubtitles,
        )
    } else {
        VodAudioPanel(tracks = tracks, onDismiss = onDismiss, onSelectTrack = onSelectTrack)
    }
}

@Composable
private fun VodAudioPanel(
    tracks: List<PlayerTrack>,
    onDismiss: () -> Unit,
    onSelectTrack: (String?) -> Unit,
) {
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val focusedTrack = tracks.firstOrNull { it.isSelected } ?: tracks.firstOrNull()
    VodPanelFrame(
        title = stringResource(R.string.player_track_audio),
        icon = Icons.Default.Audiotrack,
        onDismiss = onDismiss,
    ) {
        LaunchedEffect(Unit) {
            // Wait until the dialog window has established its initial focus.
            withFrameNanos { }
            // A lazy item that is not composed cannot take focus, so bring the target into the window first.
            listState.scrollToItem(tracks.indexOf(focusedTrack).coerceAtLeast(0))
            if (tracks.isNotEmpty()) firstFocus.requestFocusSafely("VodTrackSelectionSheet", "Audio selected track")
        }
        FocusSafeLazyColumn(
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(AppSpacing().xs),
        ) {
            if (tracks.isEmpty()) item {
                Text(stringResource(R.string.vod_tracks_empty), color = AppColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(AppSpacing().sm))
            }
            items(tracks, key = { it.id }) { track ->
                VodPanelRow(track.name, selected = track.isSelected, icon = Icons.Default.Audiotrack,
                    modifier = if (track == focusedTrack) Modifier.focusRequester(firstFocus) else Modifier) {
                    onSelectTrack(track.id); onDismiss()
                }
            }
        }
    }
}

/**
 * Subtitle frame: one main page (tracks, add-on subtitles, search, device file) and an in-frame appearance page.
 * Back, or the arrow beside the appearance title, returns from the appearance page to the main page, and closes the
 * frame from the main page. The start-side key (Left, mirrored in RTL) never leaves the frame or changes page.
 */
@Composable
private fun VodSubtitlePanel(
    tracks: List<PlayerTrack>,
    offsetMs: Int,
    textScale: Float,
    backgroundColor: Int,
    onlineAvailable: Boolean,
    stremioSubtitlePicker: StremioSubtitlePickerState,
    onDismiss: () -> Unit,
    onSelectTrack: (String?) -> Unit,
    onSearchOnline: () -> Unit,
    onLoadLocal: () -> Unit,
    onAdjustOffset: (Int) -> Unit,
    onResetOffset: () -> Unit,
    onSetTextScale: (Float) -> Unit,
    onSetOpacity: (Int) -> Unit,
    onSelectStremioSubtitle: (StremioPlayerSubtitleOption) -> Unit,
    onRetryStremioSubtitles: () -> Unit,
) {
    var appearance by remember { mutableStateOf(false) }
    // After the appearance page closes, focus goes back to the row that opened it instead of the selected track.
    var cameFromAppearance by remember { mutableStateOf(false) }
    val rows = remember(tracks, stremioSubtitlePicker) { buildVodSubtitleRows(tracks, stremioSubtitlePicker) }
    val pageFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val startKey = startSideKey()
    val closeAppearance = { cameFromAppearance = true; appearance = false }
    val firstControlModifier = Modifier.focusRequester(pageFocus)
    // The preview above the first stepper and the hint below the last one are not focusable, so entering either row
    // scrolls the list to its end, and the fade never rests over them.
    val firstRowModifier = Modifier.scrollListToTopOnFocus(listState)
    val lastRowModifier = Modifier.scrollListToBottomOnFocus(listState)
    val focusKey = if (cameFromAppearance) VodSubtitleRow.Appearance.key else vodSubtitleSelectedKey(tracks)
    VodPanelFrame(
        title = stringResource(if (appearance) R.string.vod_subtitle_appearance else R.string.player_track_subs),
        icon = Icons.Default.ClosedCaption,
        onBack = if (appearance) closeAppearance else null,
        onDismiss = { if (appearance) closeAppearance() else onDismiss() },
    ) {
        LaunchedEffect(appearance) {
            // Wait until the dialog window has established its initial focus.
            withFrameNanos { }
            // A lazy item that is not composed cannot take focus, so bring the target into the window first.
            listState.scrollToItem(if (appearance) 0 else rows.indexOfFirst { it.key == focusKey }.coerceAtLeast(0))
            pageFocus.requestFocusSafely("VodSubtitlePanel", "Active page first action")
        }
        FocusSafeLazyColumn(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                // Rows on the main page are full width, so the start key has nowhere to go: swallow it instead of
                // letting the focus search leave the frame. The appearance page handles it per button.
                .onKeyEvent { event -> !appearance && event.key == startKey },
            state = listState,
            verticalArrangement = Arrangement.spacedBy(AppSpacing().xs),
        ) {
            if (appearance) {
                subtitleAppearanceItems(
                    offsetMs = offsetMs, textScale = textScale, backgroundColor = backgroundColor,
                    firstControlModifier = firstControlModifier, firstRowModifier = firstRowModifier,
                    lastRowModifier = lastRowModifier,
                    onAdjustOffset = onAdjustOffset, onResetOffset = onResetOffset,
                    onSetTextScale = onSetTextScale, onSetOpacity = onSetOpacity,
                )
            } else {
                items(rows, key = { it.key }) { row ->
                    val rowModifier = if (row.key == focusKey) Modifier.focusRequester(pageFocus) else Modifier
                    SubtitleMainRow(
                        row = row, modifier = rowModifier, noneSelected = tracks.none { it.isSelected },
                        onlineAvailable = onlineAvailable,
                        onSelectTrack = { onSelectTrack(it); onDismiss() },
                        onSelectStremioSubtitle = { onSelectStremioSubtitle(it); onDismiss() },
                        onRetryStremioSubtitles = onRetryStremioSubtitles,
                        onSearchOnline = onSearchOnline, onLoadLocal = onLoadLocal,
                        onOpenAppearance = { cameFromAppearance = false; appearance = true },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleMainRow(
    row: VodSubtitleRow,
    modifier: Modifier,
    noneSelected: Boolean,
    onlineAvailable: Boolean,
    onSelectTrack: (String?) -> Unit,
    onSelectStremioSubtitle: (StremioPlayerSubtitleOption) -> Unit,
    onRetryStremioSubtitles: () -> Unit,
    onSearchOnline: () -> Unit,
    onLoadLocal: () -> Unit,
    onOpenAppearance: () -> Unit,
) {
    when (row) {
        VodSubtitleRow.Off -> VodPanelRow(stringResource(R.string.player_track_off), modifier = modifier,
            selected = noneSelected, icon = Icons.Default.SubtitlesOff) { onSelectTrack(null) }
        is VodSubtitleRow.Track -> VodPanelRow(row.track.name, modifier = modifier, selected = row.track.isSelected,
            icon = when (row.track.subtitleSource) {
                SubtitleTrackSource.DEVICE -> Icons.Default.FolderOpen
                SubtitleTrackSource.STREMIO -> Icons.Default.Subtitles
                SubtitleTrackSource.ONLINE -> Icons.Default.CloudDownload
                SubtitleTrackSource.PROVIDER -> Icons.Default.ClosedCaption
            }) { onSelectTrack(row.track.id) }
        VodSubtitleRow.StremioHeading -> Text(stringResource(R.string.player_stremio_subtitles_heading),
            color = AppColors.TextPrimary, style = MaterialTheme.typography.titleSmall,
            modifier = modifier.padding(top = AppSpacing().xs, bottom = 2.dp))
        VodSubtitleRow.StremioLoading -> SubtitleHintText(R.string.player_stremio_subtitles_loading)
        VodSubtitleRow.StremioEmpty -> SubtitleHintText(R.string.player_stremio_subtitles_empty)
        VodSubtitleRow.StremioRetry -> VodPanelRow(stringResource(R.string.player_stremio_subtitles_retry),
            modifier = modifier, icon = Icons.Default.Refresh, onClick = onRetryStremioSubtitles)
        is VodSubtitleRow.StremioOption -> VodPanelRow(row.option.label, modifier = modifier,
            icon = Icons.Default.CloudDownload) { onSelectStremioSubtitle(row.option) }
        VodSubtitleRow.SearchOnline -> VodPanelRow(stringResource(R.string.vod_subtitle_online), modifier = modifier,
            detail = stringResource(if (onlineAvailable) R.string.vod_subtitle_online_hint else R.string.vod_subtitle_online_unavailable),
            icon = Icons.Default.CloudDownload, opensPage = true, enabled = onlineAvailable, onClick = onSearchOnline)
        VodSubtitleRow.LoadFromDevice -> VodPanelRow(stringResource(R.string.player_subtitle_load_from_device),
            modifier = modifier, detail = stringResource(R.string.vod_subtitle_device_hint),
            icon = Icons.Default.FolderOpen, onClick = onLoadLocal)
        VodSubtitleRow.Appearance -> VodPanelRow(stringResource(R.string.vod_subtitle_appearance), modifier = modifier,
            icon = Icons.Default.Tune, opensPage = true, onClick = onOpenAppearance)
    }
}

@Composable
private fun SubtitleHintText(@StringRes textRes: Int) {
    Text(stringResource(textRes), color = AppColors.TextSecondary, style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 6.dp))
}

private fun LazyListScope.subtitleAppearanceItems(
    offsetMs: Int,
    textScale: Float,
    backgroundColor: Int,
    firstControlModifier: Modifier,
    firstRowModifier: Modifier,
    lastRowModifier: Modifier,
    onAdjustOffset: (Int) -> Unit,
    onResetOffset: () -> Unit,
    onSetTextScale: (Float) -> Unit,
    onSetOpacity: (Int) -> Unit,
) {
    item {
        // A solid mid-gray stands in for the picture, so the subtitle's background opacity and size are visible
        // against something that is neither the dark panel nor pure black.
        Box(Modifier.fillMaxWidth().background(PanelPreviewBackdrop, PanelPreviewShape).padding(AppSpacing().lg),
            contentAlignment = Alignment.Center) {
            // Subtitles are drawn over video, so the preview uses the video-content colors.
            Text(stringResource(R.string.vod_subtitle_preview), color = Color.White,
                fontSize = MaterialTheme.typography.bodyLarge.fontSize * textScale,
                modifier = Modifier.background(Color(backgroundColor), RoundedCornerShape(3.dp)).padding(6.dp))
        }
    }
    item {
        SubtitleSettingStepper(
            stringResource(R.string.player_subtitle_delay_title), "$offsetMs ms", true, true,
            { onAdjustOffset(-SUBTITLE_OFFSET_STEP_MS) },
            { onAdjustOffset(SUBTITLE_OFFSET_STEP_MS) },
            firstButtonModifier = firstControlModifier,
            modifier = firstRowModifier,
            onReset = onResetOffset,
        )
        Text(stringResource(R.string.player_subtitle_delay_hint), color = AppColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
    item {
        SubtitleSettingStepper(stringResource(R.string.vod_subtitle_font_size),
            "${(textScale * 100).roundToInt()}%", textScale > 0.75f, textScale < 1.75f,
            { onSetTextScale((textScale - 0.25f).coerceAtLeast(0.75f)) },
            { onSetTextScale((textScale + 0.25f).coerceAtMost(1.75f)) })
    }
    item {
        val opacity = ((backgroundColor ushr 24) * 100f / 255).roundToInt()
        SubtitleSettingStepper(stringResource(R.string.vod_subtitle_background), "$opacity%",
            opacity > 0, opacity < 100,
            { onSetOpacity((opacity - 10).coerceAtLeast(0)) },
            { onSetOpacity((opacity + 10).coerceAtMost(100)) },
            modifier = lastRowModifier)
        Text(stringResource(R.string.vod_subtitle_background_hint), color = AppColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
    }
}

private val PanelPreviewShape = RoundedCornerShape(12.dp)
private val PanelPreviewBackdrop = Color.Gray

@Composable
private fun SubtitleSettingStepper(
    label: String, value: String, canDecrease: Boolean, canIncrease: Boolean,
    onDecrease: () -> Unit, onIncrease: () -> Unit,
    modifier: Modifier = Modifier,
    firstButtonModifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
) {
    // The start key moves to the previous enabled button of this row and stops at the first one. A focus search
    // would pick a button of another row or leave the frame.
    val buttons = remember { List(3) { FocusRequester() } }
    val enabled = listOf(canDecrease, canIncrease, onReset != null)
    val startKey = startSideKey()
    fun startKeyHandler(index: Int) = Modifier.onKeyEvent { event ->
        if (event.key != startKey) return@onKeyEvent false
        if (event.type == KeyEventType.KeyDown) {
            val previous = (index - 1 downTo 0).firstOrNull { enabled[it] }
            previous?.let { buttons[it].requestFocus() }
        }
        true
    }
    Row(
        modifier.fillMaxWidth().padding(horizontal = AppSpacing().sm, vertical = AppSpacing().xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing().xs),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = AppColors.TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(value, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        VodControlButton(onDecrease, modifier = firstButtonModifier.focusRequester(buttons[0]).then(startKeyHandler(0)), enabled = canDecrease) {
            Icon(Icons.Default.Remove, stringResource(R.string.vod_subtitle_decrease_setting, label))
        }
        VodControlButton(onIncrease, modifier = Modifier.focusRequester(buttons[1]).then(startKeyHandler(1)), enabled = canIncrease) {
            Icon(Icons.Default.Add, stringResource(R.string.vod_subtitle_increase_setting, label))
        }
        if (onReset != null) VodControlButton(onReset, modifier = Modifier.focusRequester(buttons[2]).then(startKeyHandler(2))) {
            Icon(Icons.Default.Restore, stringResource(R.string.player_subtitle_delay_reset))
        }
    }
}

private const val SCROLL_TO_BOTTOM_MILLIS = 350

/** Mirror of [scrollListToTopOnFocus]: scrolls [state] to its end when focus enters this node. */
@Composable
private fun Modifier.scrollListToBottomOnFocus(state: LazyListState): Modifier {
    val scope = rememberCoroutineScope()
    return onFocusChanged { focus ->
        if (focus.hasFocus && state.canScrollForward) scope.launch { state.animateToBottom() }
    }
}

// UserInput priority, for the same reason as the scroll to top: the bring-into-view that follows the focus change
// would cancel a default-priority animation after its first frame.
private suspend fun LazyListState.animateToBottom() {
    scroll(MutatePriority.UserInput) {
        val info = layoutInfo
        val last = info.visibleItemsInfo.lastOrNull()?.takeIf { it.index == info.totalItemsCount - 1 } ?: return@scroll
        val remaining = last.offset + last.size + info.afterContentPadding - info.viewportEndOffset
        if (remaining <= 0) return@scroll
        var previous = 0f
        animate(0f, remaining.toFloat(), animationSpec = tween(SCROLL_TO_BOTTOM_MILLIS)) { value, _ ->
            scrollBy(value - previous)
            previous = value
        }
    }
}

/** The key that moves toward the start edge: Left, or Right when the layout is mirrored. */
@Composable
internal fun startSideKey(): Key =
    if (LocalLayoutDirection.current == LayoutDirection.Rtl) Key.DirectionRight else Key.DirectionLeft
