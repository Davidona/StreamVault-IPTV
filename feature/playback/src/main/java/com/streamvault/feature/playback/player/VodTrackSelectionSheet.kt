package com.streamvault.feature.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.streamvault.core.ui.components.dialogs.AppDialog as Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.AppSpacing
import com.streamvault.core.ui.design.FocusSafeLazyColumn
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.core.ui.design.scrollListToTopOnFocus
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.feature.playback.R
import com.streamvault.player.PlayerTrack
import com.streamvault.player.SUBTITLE_OFFSET_STEP_MS
import com.streamvault.player.TrackType
import com.streamvault.player.SubtitleTrackSource
import kotlin.math.roundToInt

/** Shared chrome for the VOD settings, subtitle sources, and audio language choices. */
@Composable
internal fun VodSelectionSheet(
    title: String,
    icon: ImageVector,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.32f))) {
            Surface(
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(400.dp),
                colors = SurfaceDefaults.colors(containerColor = Color(0xFF10151D)),
            ) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
                        Text(title, Modifier.weight(1f), color = Color.White,
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    }
                    content()
                }
            }
        }
    }
}

@Composable
internal fun VodSelectionRow(
    title: String,
    detail: String? = null,
    icon: ImageVector = Icons.Default.ChevronRight,
    selected: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TvClickableSurface(
        onClick = onClick, enabled = enabled, modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = if (selected) 0.10f else 0.035f),
            contentColor = Color(0xFFDCE3ED),
            focusedContainerColor = Color(0xFFDFEBFA), focusedContentColor = Color(0xFF10151D),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = LocalContentColor.current.copy(alpha = 0.8f))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = LocalContentColor.current, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (detail != null) Text(detail, color = LocalContentColor.current.copy(alpha = 0.65f),
                    style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (selected) Icons.Default.Check else Icons.Default.ChevronRight, null,
                Modifier.size(18.dp), tint = LocalContentColor.current.copy(alpha = if (selected) 1f else 0.45f))
        }
    }
}

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
    var settings by remember { mutableStateOf(false) }
    var provider by remember { mutableStateOf(false) }
    val subtitles = trackType == TrackType.TEXT
    val providerTracks = if (subtitles) tracks.filter { it.subtitleSource == SubtitleTrackSource.PROVIDER } else tracks
    val firstFocus = remember { FocusRequester() }
    val listState = rememberLazyListState()
    // The track list pages open on the selected track. The subtitle provider page has a back row above the tracks.
    val focusedTrack = providerTracks.firstOrNull { it.isSelected } ?: providerTracks.firstOrNull()
    val trackPage = !subtitles || provider
    val focusedTrackIndex = if (trackPage && focusedTrack != null) {
        providerTracks.indexOf(focusedTrack) + if (subtitles) 1 else 0
    } else {
        0
    }
    VodPanelFrame(
        title = stringResource(if (subtitles) R.string.player_track_subs else R.string.player_track_audio),
        icon = if (subtitles) Icons.Default.ClosedCaption else Icons.Default.Audiotrack,
        onDismiss = onDismiss,
    ) {
        LaunchedEffect(settings, provider) {
            // Wait until the dialog window has established its initial focus.
            withFrameNanos { }
            // A lazy item that is not composed cannot take focus, so bring the target into the window first.
            listState.scrollToItem(focusedTrackIndex)
            if (subtitles || tracks.isNotEmpty()) {
                firstFocus.requestFocusSafely("VodTrackSelectionSheet", "Active page first action")
            }
        }
        if (subtitles) {
            Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing().xs)) {
                VodPanelRow(stringResource(R.string.vod_subtitle_sources), selected = !settings,
                    icon = Icons.Default.ClosedCaption, modifier = Modifier.weight(1f)) { settings = false; provider = false }
                VodPanelRow(stringResource(R.string.vod_subtitle_settings), selected = settings,
                    icon = Icons.Default.Tune, modifier = Modifier.weight(1f)) { settings = true; provider = false }
            }
        }
        FocusSafeLazyColumn(
            modifier = Modifier.weight(1f, fill = false).fillMaxWidth(),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(AppSpacing().xs),
        ) {
            when {
                subtitles && settings -> {
                    item {
                        Box(Modifier.fillMaxWidth().background(Color.Black, PanelPreviewShape).padding(AppSpacing().lg),
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
                            // The preview above the first stepper is not focusable, so entering the page's first
                            // control scrolls back up to it.
                            firstButtonModifier = Modifier.focusRequester(firstFocus).scrollListToTopOnFocus(listState),
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
                            { onSetOpacity((opacity + 10).coerceAtMost(100)) })
                        Text(stringResource(R.string.vod_subtitle_background_hint), color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                subtitles && !provider -> {
                    item {
                        VodPanelRow(stringResource(R.string.vod_subtitle_provider),
                            detail = providerTracks.firstOrNull { it.isSelected }?.name ?: stringResource(R.string.vod_subtitle_provider_hint),
                            icon = Icons.Default.ClosedCaption, opensPage = true,
                            modifier = Modifier.focusRequester(firstFocus)) { provider = true }
                    }
                    item {
                        VodPanelRow(stringResource(R.string.vod_subtitle_online),
                            detail = stringResource(if (onlineAvailable) R.string.vod_subtitle_online_hint else R.string.vod_subtitle_online_unavailable),
                            icon = Icons.Default.CloudDownload, enabled = onlineAvailable, onClick = onSearchOnline)
                    }
                    item {
                        VodPanelRow(stringResource(R.string.player_subtitle_load_from_device),
                            detail = stringResource(R.string.vod_subtitle_device_hint), icon = Icons.Default.FolderOpen, onClick = onLoadLocal)
                    }
                    if (stremioSubtitlePicker.enabled) {
                        item {
                            Text(
                                stringResource(R.string.player_stremio_subtitles_heading),
                                color = AppColors.TextPrimary,
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = AppSpacing().xs, bottom = 2.dp)
                            )
                        }
                        if (stremioSubtitlePicker.loading) {
                            item {
                                Text(
                                    stringResource(R.string.player_stremio_subtitles_loading),
                                    color = AppColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                        if (stremioSubtitlePicker.failed) {
                            item {
                                VodPanelRow(
                                    stringResource(R.string.player_stremio_subtitles_retry),
                                    icon = Icons.Default.Refresh,
                                    onClick = onRetryStremioSubtitles
                                )
                            }
                        }
                        if (!stremioSubtitlePicker.loading && !stremioSubtitlePicker.failed &&
                            stremioSubtitlePicker.options.isEmpty()
                        ) {
                            item {
                                Text(
                                    stringResource(R.string.player_stremio_subtitles_empty),
                                    color = AppColors.TextSecondary,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(vertical = 6.dp)
                                )
                            }
                        }
                        items(stremioSubtitlePicker.options, key = { "stremio:${it.key}" }) { option ->
                            VodPanelRow(
                                option.label,
                                icon = Icons.Default.CloudDownload,
                                onClick = { onSelectStremioSubtitle(option); onDismiss() }
                            )
                        }
                    }
                    item {
                        VodPanelRow(stringResource(R.string.player_track_off), selected = tracks.none { it.isSelected },
                            icon = Icons.Default.SubtitlesOff) { onSelectTrack(null); onDismiss() }
                    }
                    items(tracks.filter { it.subtitleSource != SubtitleTrackSource.PROVIDER }, key = { it.id }) { track ->
                        VodPanelRow(track.name, selected = track.isSelected,
                            icon = when (track.subtitleSource) {
                                SubtitleTrackSource.DEVICE -> Icons.Default.FolderOpen
                                SubtitleTrackSource.STREMIO -> Icons.Default.Subtitles
                                SubtitleTrackSource.ONLINE -> Icons.Default.CloudDownload
                                SubtitleTrackSource.PROVIDER -> Icons.Default.ClosedCaption
                            }) {
                            onSelectTrack(track.id); onDismiss()
                        }
                    }
                }
                else -> {
                    if (subtitles) item {
                        VodPanelRow(stringResource(R.string.vod_subtitle_back), icon = Icons.AutoMirrored.Filled.ArrowBack,
                            modifier = if (focusedTrack == null) Modifier.focusRequester(firstFocus) else Modifier) { provider = false }
                    }
                    if (providerTracks.isEmpty()) item {
                        Text(stringResource(R.string.vod_tracks_empty), color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(AppSpacing().sm))
                    }
                    items(providerTracks, key = { it.id }) { track ->
                        VodPanelRow(track.name, selected = track.isSelected,
                            icon = if (subtitles) Icons.Default.ClosedCaption else Icons.Default.Audiotrack,
                            modifier = if (track == focusedTrack) Modifier.focusRequester(firstFocus) else Modifier) {
                            onSelectTrack(track.id); onDismiss()
                        }
                    }
                }
            }
        }
    }
}

private val PanelPreviewShape = RoundedCornerShape(12.dp)

@Composable
private fun SubtitleSettingStepper(
    label: String, value: String, canDecrease: Boolean, canIncrease: Boolean,
    onDecrease: () -> Unit, onIncrease: () -> Unit,
    firstButtonModifier: Modifier = Modifier,
    onReset: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = AppSpacing().sm, vertical = AppSpacing().xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(AppSpacing().xs),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, color = AppColors.TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(value, color = AppColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }
        VodControlButton(onDecrease, modifier = firstButtonModifier, enabled = canDecrease) {
            Icon(Icons.Default.Remove, stringResource(R.string.vod_subtitle_decrease_setting, label))
        }
        VodControlButton(onIncrease, enabled = canIncrease) {
            Icon(Icons.Default.Add, stringResource(R.string.vod_subtitle_increase_setting, label))
        }
        if (onReset != null) VodControlButton(onReset) {
            Icon(Icons.Default.Restore, stringResource(R.string.player_subtitle_delay_reset))
        }
    }
}
