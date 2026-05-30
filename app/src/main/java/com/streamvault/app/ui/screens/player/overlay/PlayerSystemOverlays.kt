package com.streamvault.app.ui.screens.player.overlay

import android.view.KeyEvent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.streamvault.app.R
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.requestFocusSafely
import com.streamvault.app.ui.screens.player.PlayerNoticeAction
import com.streamvault.app.ui.screens.player.PlayerNoticeState
import com.streamvault.app.ui.screens.player.PlayerAudioVideoOffsetUiState
import com.streamvault.app.ui.screens.player.PlayerRecoveryType
import com.streamvault.app.ui.screens.player.SleepTimerUiState
import com.streamvault.app.ui.theme.AccentAmber
import com.streamvault.app.ui.theme.ErrorColor
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.app.ui.theme.PrimaryLight
import com.streamvault.app.ui.theme.TextSecondary
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ChannelQualityOption
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.LiveChannelVariant
import com.streamvault.domain.model.Season
import com.streamvault.player.PlayerError
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType
import com.streamvault.player.AUDIO_VIDEO_OFFSET_MAX_MS
import com.streamvault.player.AUDIO_VIDEO_OFFSET_MIN_MS
import java.util.Locale
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvIconButton

@Composable
fun PlayerNoticeBanner(
    notice: PlayerNoticeState?,
    onDismiss: () -> Unit,
    onAction: (PlayerNoticeAction) -> Unit,
    modifier: Modifier = Modifier
) {
    if (notice == null) return

    val (containerColor, focusedContainerColor) = when {
        notice.isRetryNotice || notice.recoveryType == PlayerRecoveryType.NETWORK || notice.recoveryType == PlayerRecoveryType.CATCH_UP ->
            Color(0xCC17314E) to Color(0xFF214C78)
        notice.recoveryType == PlayerRecoveryType.SOURCE ||
            notice.recoveryType == PlayerRecoveryType.BUFFER_TIMEOUT ||
            notice.recoveryType == PlayerRecoveryType.DECODER ->
            Color(0xCC4A3314) to Color(0xFF6D4A19)
        notice.recoveryType == PlayerRecoveryType.DRM ->
            Color(0xCC5B1F1F) to Color(0xFFE45757)
        else -> SurfaceElevated.copy(alpha = 0.96f) to SurfaceHighlight
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 760.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(containerColor)
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Text(
                text = notice.message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White
            )
            if (notice.actions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    notice.actions.forEach { action ->
                        TvClickableSurface(
                            onClick = { onAction(action) },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.12f),
                                focusedContainerColor = focusedContainerColor
                            )
                        ) {
                            Text(
                                text = playerNoticeActionLabel(action),
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerErrorOverlay(
    playerError: PlayerError?,
    contentType: String,
    hasAlternateStream: Boolean,
    hasLastChannel: Boolean,
    onAction: (PlayerNoticeAction) -> Unit,
    modifier: Modifier = Modifier
) {
    val fallbackMessage = when (playerError) {
        is PlayerError.NetworkError -> stringResource(R.string.player_error_network)
        is PlayerError.SourceError -> stringResource(R.string.player_error_source)
        is PlayerError.DecoderError -> stringResource(R.string.player_error_decoder)
        is PlayerError.DrmError -> stringResource(R.string.player_error_drm)
        is PlayerError.UnknownError -> stringResource(R.string.player_error_unknown)
        null -> stringResource(R.string.player_error_unknown)
    }
    // Prefer the detailed engine message (e.g. "No decoder available for codec H.265/HEVC")
    // and fall back to the generic localised string when the message is absent or unhelpful.
    val errorMessage = playerError?.message?.takeIf { it.isNotBlank() } ?: fallbackMessage
    val recoveryActions = buildList {
        add(PlayerNoticeAction.RETRY)
        if (contentType == "LIVE" && hasAlternateStream) add(PlayerNoticeAction.ALTERNATE_STREAM)
        if (contentType == "LIVE" && hasLastChannel) add(PlayerNoticeAction.LAST_CHANNEL)
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = stringResource(R.string.player_error_title),
                style = MaterialTheme.typography.titleMedium,
                color = ErrorColor
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                recoveryActions.forEach { action ->
                    TvClickableSurface(
                        onClick = { onAction(action) },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (action == PlayerNoticeAction.RETRY) Primary else Color.White.copy(alpha = 0.08f),
                            focusedContainerColor = if (action == PlayerNoticeAction.RETRY) PrimaryLight else Color.White.copy(alpha = 0.18f)
                        )
                    ) {
                        Text(
                            text = playerNoticeActionLabel(action),
                            color = if (action == PlayerNoticeAction.RETRY) OnBackground else Color.White,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (contentType == "LIVE" && hasLastChannel) {
                    stringResource(R.string.player_error_live_hint)
                } else {
                    stringResource(R.string.player_error_back)
                },
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim
            )
        }
    }
}

@Composable
fun PlayerTrackSelectionDialog(
    trackType: TrackType?,
    audioTracks: List<PlayerTrack>,
    subtitleTracks: List<PlayerTrack>,
    videoTracks: List<PlayerTrack>,
    onDismiss: () -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectVideo: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit
) {
    if (trackType == null) return

    val firstItemFocusRequester = remember(trackType) { FocusRequester() }

    val tracks = when (trackType) {
        TrackType.AUDIO -> audioTracks
        TrackType.VIDEO -> videoTracks
        TrackType.TEXT -> subtitleTracks
    }
    val title = when (trackType) {
        TrackType.AUDIO -> stringResource(R.string.player_track_audio)
        TrackType.VIDEO -> stringResource(R.string.player_video_quality)
        TrackType.TEXT -> stringResource(R.string.player_track_subs)
    }

    LaunchedEffect(trackType, tracks.firstOrNull()?.id) {
        firstItemFocusRequester.requestFocusSafely(
            tag = "PlayerTrackSelectionDialog",
            target = "First track option"
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 300.dp, max = 400.dp)
                    .background(SurfaceElevated, RoundedCornerShape(12.dp))
                    .padding(24.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_BACK -> false
                            else -> true
                        }
                    }
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (trackType == TrackType.TEXT) {
                        item {
                            TrackSelectionItem(
                                name = stringResource(R.string.player_track_off),
                                isSelected = tracks.none { it.isSelected },
                                onClick = {
                                    onSelectSubtitle(null)
                                    onDismiss()
                                },
                                modifier = Modifier.focusRequester(firstItemFocusRequester)
                            )
                        }
                    }

                    items(tracks, key = { it.id }) { track ->
                        TrackSelectionItem(
                            name = track.name,
                            isSelected = track.isSelected,
                            onClick = {
                                when (trackType) {
                                    TrackType.AUDIO -> onSelectAudio(track.id)
                                    TrackType.VIDEO -> onSelectVideo(track.id)
                                    TrackType.TEXT -> onSelectSubtitle(track.id)
                                }
                                onDismiss()
                            },
                            modifier = if (trackType != TrackType.TEXT && track == tracks.firstOrNull()) {
                                Modifier.focusRequester(firstItemFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelVariantSelectionDialog(
    visible: Boolean,
    channel: Channel?,
    onDismiss: () -> Unit,
    onSelectVariant: (Long) -> Unit
) {
    val variants = channel?.variants.orEmpty()
    if (!visible || channel == null || variants.size <= 1) return

    val firstItemFocusRequester = remember(channel.logicalGroupId, channel.selectedVariantId) { FocusRequester() }

    LaunchedEffect(visible, channel.logicalGroupId, channel.selectedVariantId) {
        firstItemFocusRequester.requestFocusSafely(
            tag = "ChannelVariantSelectionDialog",
            target = "Selected channel variant"
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 360.dp, max = 520.dp)
                    .background(SurfaceElevated, RoundedCornerShape(12.dp))
                    .padding(24.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_BACK -> false
                            else -> true
                        }
                    }
            ) {
                Text(
                    text = stringResource(R.string.player_channel_variants),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Text(
                    text = channel.canonicalName.ifBlank { channel.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
                )

                val initiallyFocusedVariantId = channel.variants
                    .firstOrNull { it.rawChannelId == channel.selectedVariantId }
                    ?.rawChannelId
                    ?: channel.variants.firstOrNull()?.rawChannelId

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(variants, key = { _, variant -> variant.rawChannelId }) { index, variant ->
                        TrackSelectionItem(
                            name = buildVariantSelectionLabel(variant),
                            isSelected = variant.rawChannelId == channel.selectedVariantId,
                            onClick = {
                                onSelectVariant(variant.rawChannelId)
                                onDismiss()
                            },
                            modifier = if (variant.rawChannelId == initiallyFocusedVariantId) {
                                Modifier.focusRequester(firstItemFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog for selecting the stream format (e.g. HLS vs MPEG-TS) for a live channel.
 * Shown when the channel has multiple [ChannelQualityOption]s with distinct stream URLs.
 */
@Composable
fun StreamFormatSelectionDialog(
    visible: Boolean,
    channel: Channel?,
    onDismiss: () -> Unit,
    onSelectFormat: (String) -> Unit
) {
    val qualityOptions = channel?.qualityOptions.orEmpty()
    // Only show options that have a distinct stream URL (meaning they are different formats)
    val formatOptions = qualityOptions.filter { !it.url.isNullOrBlank() }
    if (!visible || channel == null || formatOptions.size <= 1) return

    val firstItemFocusRequester = remember(channel.logicalGroupId) { FocusRequester() }

    LaunchedEffect(visible, channel.logicalGroupId) {
        firstItemFocusRequester.requestFocusSafely(
            tag = "StreamFormatSelectionDialog",
            target = "First format option"
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 360.dp, max = 520.dp)
                    .background(SurfaceElevated, RoundedCornerShape(12.dp))
                    .padding(24.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_BACK -> false
                            else -> true
                        }
                    }
            ) {
                Text(
                    text = stringResource(R.string.player_stream_format),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Text(
                    text = channel.canonicalName.ifBlank { channel.name },
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(formatOptions, key = { _, option -> option.url!! }) { index, option ->
                        TrackSelectionItem(
                            name = option.label,
                            isSelected = false,
                            onClick = {
                                onSelectFormat(option.url!!)
                                onDismiss()
                            },
                            modifier = if (index == 0) {
                                Modifier.focusRequester(firstItemFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerSpeedSelectionDialog(
    visible: Boolean,
    selectedSpeed: Float,
    onDismiss: () -> Unit,
    onSelectSpeed: (Float) -> Unit
) {
    if (!visible) return

    val firstItemFocusRequester = remember { FocusRequester() }
    val speedOptions = remember { listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f) }

    LaunchedEffect(visible) {
        firstItemFocusRequester.requestFocusSafely(
            tag = "PlayerSpeedSelectionDialog",
            target = "First speed option"
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 300.dp, max = 400.dp)
                    .background(SurfaceElevated, RoundedCornerShape(12.dp))
                    .padding(24.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_BACK -> false
                            else -> true
                        }
                    }
            ) {
                Text(
                    text = stringResource(R.string.player_playback_speed_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(speedOptions, key = { it }) { speed ->
                        TrackSelectionItem(
                            name = formatPlaybackSpeedLabel(speed),
                            isSelected = speed == selectedSpeed,
                            onClick = {
                                onSelectSpeed(speed)
                                onDismiss()
                            },
                            modifier = if (speed == speedOptions.first()) {
                                Modifier.focusRequester(firstItemFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerSleepTimerDialog(
    visible: Boolean,
    title: String,
    selectedMinutes: Int,
    onDismiss: () -> Unit,
    onSelectMinutes: (Int) -> Unit
) {
    if (!visible) return

    val firstItemFocusRequester = remember { FocusRequester() }
    val options = remember { listOf(0, 15, 30, 45, 60, 90, 120) }

    LaunchedEffect(visible) {
        firstItemFocusRequester.requestFocusSafely(
            tag = "PlayerSleepTimerDialog",
            target = "First timer option"
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 300.dp, max = 420.dp)
                    .background(SurfaceElevated, RoundedCornerShape(12.dp))
                    .padding(24.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_BACK -> false
                            else -> true
                        }
                    }
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(options, key = { it }) { minutes ->
                        TrackSelectionItem(
                            name = formatTimerPresetLabel(minutes),
                            isSelected = minutes == selectedMinutes,
                            onClick = { onSelectMinutes(minutes) },
                            modifier = if (minutes == options.first()) {
                                Modifier.focusRequester(firstItemFocusRequester)
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerSleepTimerWarningOverlay(
    state: SleepTimerUiState,
    modifier: Modifier = Modifier,
    onExtendStopTimer: () -> Unit,
    onDisableStopTimer: () -> Unit,
    onExtendIdleTimer: () -> Unit,
    onDisableIdleTimer: () -> Unit
) {
    val showStopWarning = state.stopTimerWarningVisible
    val showIdleWarning = !showStopWarning && state.idleTimerWarningVisible
    if (!showStopWarning && !showIdleWarning) return

    val message = if (showStopWarning) {
        stringResource(R.string.player_timer_warning_stop, formatTimerCountdown(state.stopRemainingMs))
    } else {
        stringResource(R.string.player_timer_warning_idle, formatTimerCountdown(state.idleRemainingMs))
    }
    val onExtend = if (showStopWarning) onExtendStopTimer else onExtendIdleTimer
    val onDisable = if (showStopWarning) onDisableStopTimer else onDisableIdleTimer

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated.copy(alpha = 0.94f)),
        border = Border(androidx.compose.foundation.BorderStroke(1.dp, Primary.copy(alpha = 0.35f)))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
            TvButton(onClick = onExtend) {
                Text(text = stringResource(R.string.player_timer_extend_30))
            }
            TvButton(onClick = onDisable) {
                Text(text = stringResource(R.string.player_timer_disable))
            }
        }
    }
}

@Composable
fun PlayerAudioVideoOffsetDialog(
    visible: Boolean,
    state: PlayerAudioVideoOffsetUiState,
    canSaveChannel: Boolean,
    onDismiss: () -> Unit,
    onAdjust: (Int) -> Unit,
    onReset: () -> Unit,
    onSaveForChannel: () -> Unit,
    onSaveAsGlobal: () -> Unit,
    onUseGlobal: () -> Unit
) {
    if (!visible) return

    val firstItemFocusRequester = remember { FocusRequester() }
    val effectiveLabel = remember(state.effectiveOffsetMs) {
        formatAudioVideoOffsetLabel(state.effectiveOffsetMs)
    }
    val sourceLabel = when {
        state.previewOffsetMs != null -> stringResource(R.string.player_av_sync_preview)
        state.hasChannelOverride -> stringResource(R.string.player_av_sync_channel)
        else -> stringResource(R.string.player_av_sync_global)
    }

    LaunchedEffect(visible) {
        firstItemFocusRequester.requestFocusSafely(
            tag = "PlayerAudioVideoOffsetDialog",
            target = "First A/V sync option"
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.8f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 340.dp, max = 460.dp)
                    .background(SurfaceElevated, RoundedCornerShape(12.dp))
                    .padding(24.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_BACK -> false
                            else -> true
                        }
                    }
            ) {
                Text(
                    text = stringResource(R.string.player_av_sync_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White
                )
                Text(
                    text = stringResource(R.string.player_av_sync_value, effectiveLabel, sourceLabel),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim,
                    modifier = Modifier.padding(top = 6.dp, bottom = 16.dp)
                )

                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        TrackSelectionItem(
                            name = stringResource(R.string.player_av_sync_minus_50),
                            isSelected = false,
                            onClick = { onAdjust(-50) },
                            enabled = state.effectiveOffsetMs > AUDIO_VIDEO_OFFSET_MIN_MS,
                            modifier = Modifier.focusRequester(firstItemFocusRequester)
                        )
                    }
                    item {
                        TrackSelectionItem(
                            name = stringResource(R.string.player_av_sync_plus_50),
                            isSelected = false,
                            onClick = { onAdjust(50) },
                            enabled = state.effectiveOffsetMs < AUDIO_VIDEO_OFFSET_MAX_MS
                        )
                    }
                    item {
                        TrackSelectionItem(
                            name = stringResource(R.string.player_av_sync_reset),
                            isSelected = state.effectiveOffsetMs == 0,
                            onClick = onReset
                        )
                    }
                    if (canSaveChannel) {
                        item {
                            TrackSelectionItem(
                                name = stringResource(R.string.player_av_sync_save_channel),
                                isSelected = state.hasChannelOverride && state.previewOffsetMs == null,
                                onClick = {
                                    onSaveForChannel()
                                    onDismiss()
                                }
                            )
                        }
                    }
                    item {
                        TrackSelectionItem(
                            name = stringResource(R.string.player_av_sync_save_global),
                            isSelected = false,
                            onClick = {
                                onSaveAsGlobal()
                                onDismiss()
                            }
                        )
                    }
                    if (canSaveChannel && state.hasChannelOverride) {
                        item {
                            TrackSelectionItem(
                                name = stringResource(R.string.player_av_sync_use_global),
                                isSelected = false,
                                onClick = {
                                    onUseGlobal()
                                    onDismiss()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PlayerEpisodeSelectionDialog(
    visible: Boolean,
    seriesTitle: String,
    seasons: List<Season>,
    currentEpisodeId: Long,
    currentSeasonNumber: Int?,
    onDismiss: () -> Unit,
    onSelectEpisode: (Episode) -> Unit
) {
    if (!visible) return

    val availableSeasons = remember(seasons) { seasons.filter { it.episodes.isNotEmpty() } }
    if (availableSeasons.isEmpty()) return

    val totalEpisodeCount = remember(availableSeasons) { availableSeasons.sumOf { it.episodes.size } }

    var selectedSeasonNumber by remember(visible, currentSeasonNumber, availableSeasons) {
        mutableStateOf(currentSeasonNumber ?: availableSeasons.first().seasonNumber)
    }
    val selectedSeason = availableSeasons.firstOrNull { it.seasonNumber == selectedSeasonNumber }
        ?: availableSeasons.first()
    val selectedEpisode = remember(selectedSeason, currentEpisodeId) {
        selectedSeason.episodes.firstOrNull { it.id == currentEpisodeId }
    }
    val selectedSeasonFocusRequester = remember(visible, selectedSeason.seasonNumber) { FocusRequester() }
    val episodeEntryFocusRequester = remember(visible, selectedSeason.seasonNumber, currentEpisodeId) { FocusRequester() }

    LaunchedEffect(availableSeasons, selectedSeasonNumber) {
        if (availableSeasons.none { it.seasonNumber == selectedSeasonNumber }) {
            selectedSeasonNumber = availableSeasons.first().seasonNumber
        }
    }

    LaunchedEffect(visible, selectedSeason.seasonNumber) {
        episodeEntryFocusRequester.requestFocusSafely(
            tag = "PlayerEpisodeSelectionDialog",
            target = "Selected episode option"
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.68f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .widthIn(min = 680.dp, max = 940.dp)
                    .fillMaxWidth(0.88f)
                    .heightIn(max = 640.dp)
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                        when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER,
                            KeyEvent.KEYCODE_BACK -> false
                            else -> true
                        }
                    },
                shape = RoundedCornerShape(20.dp),
                colors = SurfaceDefaults.colors(containerColor = AppColors.CanvasElevated.copy(alpha = 0.98f)),
                border = Border(
                    border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.Outline)
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(
                                    AppColors.BrandMuted.copy(alpha = 0.10f),
                                    AppColors.CanvasElevated,
                                    AppColors.Canvas
                                )
                            )
                        )
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = stringResource(R.string.player_episodes),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = seriesTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PlayerEpisodeStatPill(text = "${availableSeasons.size} seasons")
                            PlayerEpisodeStatPill(text = "$totalEpisodeCount episodes")
                            PlayerEpisodeStatPill(
                                text = selectedEpisode?.let { "Now on S${it.seasonNumber}E${it.episodeNumber}" }
                                    ?: selectedSeason.name,
                                accent = true
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            modifier = Modifier
                                .width(190.dp)
                                .fillMaxHeight(),
                            shape = RoundedCornerShape(16.dp),
                            colors = SurfaceDefaults.colors(containerColor = AppColors.Surface.copy(alpha = 0.78f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.series_seasons),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Choose a season, then move right for episodes.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextTertiary
                                )
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(availableSeasons, key = { it.seasonNumber }) { season ->
                                        val isSelectedSeason = season.seasonNumber == selectedSeason.seasonNumber
                                        TvClickableSurface(
                                            onClick = { selectedSeasonNumber = season.seasonNumber },
                                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                                            colors = ClickableSurfaceDefaults.colors(
                                                containerColor = if (isSelectedSeason) AppColors.BrandMuted.copy(alpha = 0.72f) else AppColors.SurfaceElevated.copy(alpha = 0.62f),
                                                focusedContainerColor = AppColors.Surface.copy(alpha = 0.76f),
                                                contentColor = Color.White,
                                                focusedContentColor = Color.White
                                            ),
                                            border = ClickableSurfaceDefaults.border(
                                                border = Border(
                                                    border = androidx.compose.foundation.BorderStroke(
                                                        1.dp,
                                                        if (isSelectedSeason) AppColors.Brand else AppColors.Outline
                                                    )
                                                ),
                                                focusedBorder = Border(
                                                    border = androidx.compose.foundation.BorderStroke(2.dp, AppColors.Focus)
                                                )
                                            ),
                                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                            modifier = Modifier
                                                .then(
                                                    if (isSelectedSeason) Modifier.focusRequester(selectedSeasonFocusRequester) else Modifier
                                                )
                                                .focusProperties { right = episodeEntryFocusRequester }
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = season.name,
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = "${season.episodes.size} episodes",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isSelectedSeason) AppColors.TextPrimary else AppColors.TextSecondary
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(16.dp),
                            colors = SurfaceDefaults.colors(containerColor = AppColors.Surface.copy(alpha = 0.70f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(
                                            text = selectedSeason.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "${selectedSeason.episodes.size} episodes ready to play",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                    }
                                    selectedEpisode?.let { episode ->
                                        PlayerEpisodeStatPill(
                                            text = buildEpisodeCodeLabel(episode),
                                            accent = true
                                        )
                                    }
                                }

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 440.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    itemsIndexed(selectedSeason.episodes, key = { _, episode -> episode.id }) { index, episode ->
                                        val isSelectedEpisode = episode.id == currentEpisodeId
                                        TvClickableSurface(
                                            onClick = { onSelectEpisode(episode) },
                                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                                            colors = ClickableSurfaceDefaults.colors(
                                                containerColor = if (isSelectedEpisode) AppColors.BrandMuted.copy(alpha = 0.62f) else AppColors.SurfaceElevated.copy(alpha = 0.44f),
                                                focusedContainerColor = AppColors.Surface.copy(alpha = 0.74f)
                                            ),
                                            border = ClickableSurfaceDefaults.border(
                                                border = Border(
                                                    border = androidx.compose.foundation.BorderStroke(
                                                        1.dp,
                                                        if (isSelectedEpisode) AppColors.Brand else AppColors.Outline
                                                    )
                                                ),
                                                focusedBorder = Border(
                                                    border = androidx.compose.foundation.BorderStroke(2.dp, AppColors.Focus)
                                                )
                                            ),
                                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .then(
                                                    if ((selectedEpisode == null && index == 0) || episode.id == selectedEpisode?.id) {
                                                        Modifier.focusRequester(episodeEntryFocusRequester)
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                                .focusProperties {
                                                    if (index == 0) {
                                                        left = selectedSeasonFocusRequester
                                                    }
                                                }
                                        ) {
                                            Column(modifier = Modifier.fillMaxWidth()) {
                                                PlayerEpisodeListItem(
                                                    episode = episode,
                                                    selected = isSelectedEpisode,
                                                    modifier = Modifier.fillMaxWidth()
                                                )
                                                if (isSelectedEpisode) {
                                                    Text(
                                                        text = stringResource(R.string.player_selected),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = AppColors.BrandStrong,
                                                        modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 8.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerEpisodeStatPill(
    text: String,
    accent: Boolean = false
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        colors = SurfaceDefaults.colors(
            containerColor = if (accent) AppColors.BrandMuted.copy(alpha = 0.78f) else AppColors.SurfaceElevated.copy(alpha = 0.70f)
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (accent) AppColors.BrandStrong else AppColors.TextSecondary,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
        )
    }
}

@Composable
private fun PlayerEpisodeListItem(
    episode: Episode,
    selected: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .width(120.dp)
                .height(68.dp)
                .background(AppColors.SurfaceEmphasis.copy(alpha = 0.72f), RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (!episode.coverUrl.isNullOrBlank()) {
                AsyncImage(
                    model = episode.coverUrl,
                    contentDescription = episode.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    text = buildEpisodeCodeLabel(episode),
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextSecondary
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = episode.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildEpisodeMetaLabel(episode),
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            episode.plot?.takeIf { it.isNotBlank() }?.let { plot ->
                Text(
                    text = plot,
                    style = MaterialTheme.typography.labelSmall,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun formatPlaybackSpeedLabel(speed: Float): String {
    return if (speed % 1f == 0f) {
        "${speed.toInt()}x"
    } else {
        "${("%.2f".format(Locale.US, speed)).trimEnd('0').trimEnd('.')}x"
    }
}

@Composable
private fun formatTimerPresetLabel(minutes: Int): String =
    if (minutes <= 0) {
        stringResource(R.string.player_timer_off)
    } else {
        androidx.compose.ui.platform.LocalContext.current.resources.getQuantityString(
            R.plurals.settings_timer_minutes,
            minutes,
            minutes
        )
    }

private fun formatTimerCountdown(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
    if (totalSeconds < 60L) return "${totalSeconds}s"
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return if (seconds == 0L) "${minutes}m" else "${minutes}m ${seconds}s"
}

private fun formatAudioVideoOffsetLabel(offsetMs: Int): String = when {
    offsetMs > 0 -> "+$offsetMs ms"
    offsetMs < 0 -> "$offsetMs ms"
    else -> "0 ms"
}

private fun buildVariantSelectionLabel(variant: LiveChannelVariant): String {
    val metaParts = buildList {
        variant.attributes.resolutionLabel?.let(::add)
        variant.attributes.codecLabel?.let(::add)
        variant.attributes.frameRate?.takeIf { it > 0 }?.let { add("${it}fps") }
        if (variant.attributes.isHdr) {
            add("HDR")
        }
        variant.attributes.transportLabel?.let(::add)
        variant.attributes.sourceHint?.let(::add)
        variant.attributes.regionHint?.let(::add)
        variant.attributes.languageHint?.let(::add)
        addAll(variant.attributes.rawTags.take(2))
    }.distinct()

    return buildString {
        append(variant.originalName)
        if (metaParts.isNotEmpty()) {
            append(" • ")
            append(metaParts.joinToString(" • "))
        }
    }
}

@Composable
private fun TrackSelectionItem(
    name: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    TvClickableSurface(
        onClick = { if (enabled) onClick() },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.2f) else Color.Transparent,
            focusedContainerColor = SurfaceHighlight
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> Color.White.copy(alpha = 0.38f)
                    isSelected -> Primary
                    else -> Color.White
                },
                modifier = Modifier.weight(1f)
            )
            if (isSelected) {
                Text(
                    text = stringResource(R.string.player_selected),
                    color = Primary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

private fun buildEpisodeCodeLabel(episode: Episode): String = buildString {
    append("S")
    append(episode.seasonNumber)
    append("E")
    append(episode.episodeNumber)
}

private fun buildEpisodeMetaLabel(episode: Episode): String = buildString {
    append(stringResourceFallbackEpisode(episode.episodeNumber))
    episode.duration?.takeIf { it.isNotBlank() }?.let {
        append(" • ")
        append(it)
    }
}

private fun stringResourceFallbackEpisode(episodeNumber: Int): String = "Episode $episodeNumber"

@Composable
fun PlayerResumePrompt(
    title: String,
    onStartOver: () -> Unit,
    onResume: () -> Unit
) {
    val startOverFocusRequester = remember(title) { FocusRequester() }
    val resumeFocusRequester = remember(title) { FocusRequester() }

    LaunchedEffect(title) {
        resumeFocusRequester.requestFocusSafely(
            tag = "PlayerResumePrompt",
            target = "Resume button"
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .background(SurfaceElevated, RoundedCornerShape(12.dp))
                .focusGroup()
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP,
                        KeyEvent.KEYCODE_DPAD_DOWN -> true
                        else -> false
                    }
                }
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.player_resume_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.player_resume_desc, title),
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TvClickableSurface(
                    onClick = onStartOver,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(startOverFocusRequester)
                        .focusProperties {
                            right = resumeFocusRequester
                        }
                ) {
                    Text(
                        stringResource(R.string.player_resume_start_over),
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = Color.White
                    )
                }

                TvClickableSurface(
                    onClick = onResume,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary,
                        focusedContainerColor = PrimaryLight
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(resumeFocusRequester)
                        .focusProperties {
                            left = startOverFocusRequester
                        }
                ) {
                    Text(
                        stringResource(R.string.player_resume),
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun playerNoticeActionLabel(action: PlayerNoticeAction): String =
    when (action) {
        PlayerNoticeAction.RETRY -> stringResource(R.string.player_retry)
        PlayerNoticeAction.LAST_CHANNEL -> stringResource(R.string.player_last_channel_action)
        PlayerNoticeAction.ALTERNATE_STREAM -> stringResource(R.string.player_try_alternate_stream)
        PlayerNoticeAction.OPEN_GUIDE -> stringResource(R.string.player_open_guide_action)
    }

@Composable
fun NextEpisodeCountdownOverlay(
    nextEpisode: Episode,
    secondsRemaining: Int,
    totalSeconds: Int = 10,
    onPlayNow: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val playNowFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        playNowFocusRequester.requestFocusSafely(
            tag = "NextEpisodeCountdown",
            target = "Play Now button"
        )
    }

    val progress = (secondsRemaining.toFloat() / totalSeconds.toFloat()).coerceIn(0f, 1f)
    val animatedSweep by animateFloatAsState(
        targetValue = 360f * progress,
        animationSpec = tween(durationMillis = 900, easing = LinearEasing),
        label = "countdown_arc"
    )

    Box(
        modifier = modifier
            .widthIn(max = 360.dp)
            .background(Color.Black.copy(alpha = 0.90f), RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            // "UP NEXT" label
            Text(
                text = stringResource(R.string.player_up_next),
                style = MaterialTheme.typography.labelMedium,
                color = Primary,
                fontWeight = FontWeight.Bold
            )

            // Episode info: arc timer + title + thumbnail
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Circular countdown arc
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(60.dp)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawArc(
                            color = Color.White.copy(alpha = 0.12f),
                            startAngle = -90f,
                            sweepAngle = 360f,
                            useCenter = false,
                            style = Stroke(width = 4.dp.toPx())
                        )
                        drawArc(
                            color = Primary,
                            startAngle = -90f,
                            sweepAngle = animatedSweep,
                            useCenter = false,
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        text = secondsRemaining.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Title and subtitle
                Column(modifier = Modifier.weight(1f)) {
                    val episodeLabel = buildString {
                        append("S${nextEpisode.seasonNumber}E${nextEpisode.episodeNumber}")
                        if (nextEpisode.title.isNotBlank()) append(" · ${nextEpisode.title}")
                    }
                    Text(
                        text = episodeLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = stringResource(R.string.player_auto_play_countdown, secondsRemaining),
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                // Episode thumbnail
                if (!nextEpisode.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = nextEpisode.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .width(80.dp)
                            .height(55.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
            }

            // Action buttons
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TvClickableSurface(
                    onClick = onPlayNow,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary,
                        focusedContainerColor = PrimaryLight
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(playNowFocusRequester)
                ) {
                    Text(
                        text = stringResource(R.string.player_auto_play_play_now),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
                TvClickableSurface(
                    onClick = onCancel,
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stringResource(R.string.player_auto_play_cancel),
                        color = Color.White,
                        modifier = Modifier
                            .padding(vertical = 10.dp)
                            .fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
