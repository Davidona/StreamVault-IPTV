package com.streamvault.feature.playback.player.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CastConnected
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MoreTime
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Today
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.annotation.StringRes
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.core.ui.image.ChannelLogoBadge
import com.streamvault.core.ui.time.LocalUiTimeFormat
import com.streamvault.core.ui.time.createTimeFormat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.feature.playback.R
import com.streamvault.feature.playback.player.PlayerTimeshiftUiState
import com.streamvault.player.PlayerTrack
import com.streamvault.player.SUBTITLE_OFFSET_STEP_MS
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SafeHorizontal = 48.dp
private val SafeVertical = 27.dp
private val MoreSheetWidth = 360.dp
private val TrayGap = 12.dp
private val PlayButtonSize = 56.dp
private val ActionRowGap = 10.dp
private val PillPadding = 5.dp
private val PillBorder = 1.dp
private val PillButtonSize = 46.dp
private val PillButtonGap = 2.dp
private val PillLabelGap = 8.dp
private val PillLabelMaxWidth = 120.dp

private const val FocusScaleMillis = 60
private const val SheetScrimAlpha = 0.2f
private const val PanelFadeMillis = 200
private val InfoToActionsGap = 28.dp

/**
 * The focus label floats above the row, so the programme info only reserves the collapsed row.
 *
 * The programme info, progress block and pill container each sit in their own graphics layer. A focus change repaints only the pills; without the layers it re-recorded every text and
 * gradient in the overlay, which made channels with a guide (more text) visibly slower.
 */
private fun programInfoEndInset(actionCount: Int): Dp {
    val pillButtons = (actionCount - 1).coerceAtLeast(0)
    val collapsedRow = PlayButtonSize + ActionRowGap + (PillPadding + PillBorder) * 2 +
        PillButtonSize * pillButtons + PillButtonGap * (pillButtons - 1).coerceAtLeast(0)
    return collapsedRow + InfoToActionsGap
}

internal data class TvChannelInfoCallbacks(
    val onDismiss: () -> Unit,
    val onOverlayInteracted: () -> Unit,
    val onBackToMenu: () -> Unit,
    val onOpenFullEpg: () -> Unit,
    val onOpenLastGroup: () -> Unit,
    val onStartRecording: () -> Unit,
    val onStopRecording: () -> Unit,
    val onScheduleRecording: () -> Unit,
    val onScheduleDailyRecording: () -> Unit,
    val onScheduleWeeklyRecording: () -> Unit,
    val onScheduleByTime: () -> Unit,
    val onRestartProgram: () -> Unit,
    val onOpenArchive: () -> Unit,
    val onToggleAspectRatio: () -> Unit,
    val onToggleDiagnostics: () -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onSeekBackward: () -> Unit,
    val onSeekForward: () -> Unit,
    val onSeekToLiveEdge: () -> Unit,
    val onOpenSplitScreen: () -> Unit,
    val onToggleMute: () -> Unit,
    val onOpenVideoTracks: () -> Unit,
    val onOpenVariants: () -> Unit,
    val onOpenStreamFormats: () -> Unit,
    val onOpenAudioVideoSync: () -> Unit,
    val onEnterPictureInPicture: () -> Unit,
    val onCast: () -> Unit,
    val onStopCasting: () -> Unit
)

/** Data and actions for the subtitles and audio side sheet; collected by the host, read only while the sheet is open. */
data class TvTrackSheetInputs(
    val subtitleTracks: List<PlayerTrack> = emptyList(),
    val audioTracks: List<PlayerTrack> = emptyList(),
    val liveTranslationAvailable: Boolean = false,
    val liveTranslationActive: Boolean = false,
    val subtitleOffsetMs: Int = 0,
    val onSelectSubtitle: (String?) -> Unit = {},
    val onSelectAudio: (String) -> Unit = {},
    val onSelectLiveTranslation: () -> Unit = {},
    val onAdjustSubtitleOffset: (Int) -> Unit = {},
    val onResetSubtitleOffset: () -> Unit = {},
    val onLoadLocalSubtitle: () -> Unit = {}
)

/**
 * Full-bleed live overlay for TV. Everything focusable sits in one bottom row because, with the
 * overlay open and no panel showing, D-pad Up and Down change channel (see playerPreviewInputDecision).
 */
@Composable
internal fun TvChannelInfoOverlay(
    currentChannel: Channel?,
    displayChannelNumber: Int,
    currentProgram: Program?,
    nextProgram: Program?,
    actionInputs: ChannelInfoActionInputs,
    expandedPanel: ChannelInfoPanel?,
    onExpandedPanelChange: (ChannelInfoPanel?) -> Unit,
    playPauseFocusRequester: FocusRequester,
    isPlaying: Boolean,
    isMuted: Boolean,
    currentAspectRatio: String,
    isDiagnosticsEnabled: Boolean,
    resolutionLabel: String?,
    variantLabel: String?,
    timeshiftUiState: PlayerTimeshiftUiState,
    showBackButton: Boolean,
    trackSheetInputs: TvTrackSheetInputs,
    callbacks: TvChannelInfoCallbacks
) {
    val moreButtonFocusRequester = remember { FocusRequester() }
    val subtitlesButtonFocusRequester = remember { FocusRequester() }
    val audioButtonFocusRequester = remember { FocusRequester() }
    var previousPanel by remember { mutableStateOf<ChannelInfoPanel?>(null) }
    var overlayDismissing by remember { mutableStateOf(false) }

    // Closing a side sheet (Back, or an item that keeps the overlay open) hands focus back to the pill that opened it.
    LaunchedEffect(expandedPanel) {
        if (expandedPanel == null && !overlayDismissing) {
            val owner = when (previousPanel) {
                ChannelInfoPanel.MORE -> moreButtonFocusRequester
                ChannelInfoPanel.SUBTITLES, ChannelInfoPanel.SUBTITLE_DELAY -> subtitlesButtonFocusRequester
                ChannelInfoPanel.AUDIO -> audioButtonFocusRequester
                else -> null
            }
            owner?.requestFocusSafely(tag = "TvChannelInfoOverlay", target = "Side sheet owner pill")
        }
        previousPanel = expandedPanel
    }

    fun dismissOverlay() {
        overlayDismissing = true
        onExpandedPanelChange(null)
        callbacks.onDismiss()
    }

    // The bottom panel steps aside while a side sheet is open. It stays composed (so the pill that opened
    // the sheet can take focus back) and fades in the layer block: no composition work, and a fully
    // transparent layer is not drawn, so the open sheet also costs less GPU fill.
    val sheetOpen = expandedPanel?.isSideSheet == true
    val panelAlpha by animateFloatAsState(
        targetValue = if (sheetOpen) 0f else 1f,
        animationSpec = tween(PanelFadeMillis),
        label = "bottomPanelAlpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(start = SafeHorizontal, end = SafeHorizontal, top = SafeVertical),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showBackButton) {
                // Touch only in practice: Up changes channel before focus can reach it.
                PlayerBackButton(
                    onClick = callbacks.onBackToMenu,
                    modifier = Modifier.onFocusChanged { if (it.isFocused) callbacks.onOverlayInteracted() }
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            TvStatusPills(
                isCasting = actionInputs.isCastConnected,
                isMuted = isMuted,
                isRecording = actionInputs.recordingStatus == RecordingStatus.RECORDING
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .graphicsLayer { alpha = panelAlpha }
                .padding(start = SafeHorizontal, end = SafeHorizontal, bottom = SafeVertical)
                .background(PanelColor, PanelShape)
                .padding(horizontal = PanelPaddingHorizontal, vertical = PanelPaddingVertical),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                TvProgramInfo(
                    currentChannel = currentChannel,
                    displayChannelNumber = displayChannelNumber,
                    currentProgram = currentProgram,
                    chips = tvInfoChips(actionInputs, resolutionLabel, variantLabel),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(end = programInfoEndInset(channelInfoRowActions(actionInputs).size))
                )
                TvActionRow(
                    modifier = Modifier.align(Alignment.BottomEnd),
                    actionInputs = actionInputs,
                    expandedPanel = expandedPanel,
                    onExpandedPanelChange = onExpandedPanelChange,
                    playPauseFocusRequester = playPauseFocusRequester,
                    moreButtonFocusRequester = moreButtonFocusRequester,
                    subtitlesButtonFocusRequester = subtitlesButtonFocusRequester,
                    audioButtonFocusRequester = audioButtonFocusRequester,
                    isPlaying = isPlaying,
                    timeshiftUiState = timeshiftUiState,
                    callbacks = callbacks,
                    onDismissOverlay = ::dismissOverlay
                )
            }
            TvLiveProgress(
                currentProgram = currentProgram,
                nextProgram = nextProgram,
                behindLiveMs = if (actionInputs.showTimeshiftControls) {
                    liveDvrTransportState(timeshiftUiState).behindLiveMs
                } else {
                    0L
                }
            )
        }

        // One container for every side sheet; the latched panel keeps title and rows alive through the exit animation.
        val sheetPanel = rememberLatchedSheetPanel(expandedPanel)
        TvSideSheet(
            visible = expandedPanel?.isSideSheet == true,
            contentKey = sheetPanel,
            titleRes = when (sheetPanel) {
                ChannelInfoPanel.SUBTITLES -> R.string.player_track_subs
                ChannelInfoPanel.AUDIO -> R.string.player_track_audio
                ChannelInfoPanel.SUBTITLE_DELAY -> R.string.player_subtitle_delay_title
                else -> R.string.player_more
            },
            subtitle = {
                if (sheetPanel == ChannelInfoPanel.SUBTITLE_DELAY) {
                    stringResource(R.string.player_subtitle_delay_current, trackSheetInputs.subtitleOffsetMs)
                } else {
                    channelLine(displayChannelNumber, currentChannel?.name)
                }
            }
        ) {
            when (sheetPanel) {
                ChannelInfoPanel.SUBTITLES, ChannelInfoPanel.AUDIO, ChannelInfoPanel.SUBTITLE_DELAY -> rememberTvTrackRows(
                    panel = sheetPanel,
                    inputs = trackSheetInputs,
                    onInteraction = callbacks.onOverlayInteracted,
                    onClosePanel = { onExpandedPanelChange(null) },
                    onOpenDelayPanel = { onExpandedPanelChange(ChannelInfoPanel.SUBTITLE_DELAY) }
                )
                else -> rememberTvMoreRows(
                    actionInputs = actionInputs,
                    isMuted = isMuted,
                    isDiagnosticsEnabled = isDiagnosticsEnabled,
                    currentAspectRatio = currentAspectRatio,
                    resolutionLabel = resolutionLabel,
                    variantLabel = variantLabel,
                    onInteraction = callbacks.onOverlayInteracted,
                    onItemClick = { item ->
                        // Mute and aspect ratio apply in place; pickers and toggles close More first.
                        fun closeMoreThen(action: () -> Unit) {
                            onExpandedPanelChange(null)
                            action()
                        }
                        when (item) {
                            ChannelInfoMoreItem.MUTE -> callbacks.onToggleMute()
                            ChannelInfoMoreItem.ASPECT_RATIO -> callbacks.onToggleAspectRatio()
                            ChannelInfoMoreItem.QUALITY -> closeMoreThen(callbacks.onOpenVideoTracks)
                            ChannelInfoMoreItem.VARIANTS -> closeMoreThen(callbacks.onOpenVariants)
                            ChannelInfoMoreItem.FORMAT -> closeMoreThen(callbacks.onOpenStreamFormats)
                            ChannelInfoMoreItem.AV_SYNC -> closeMoreThen(callbacks.onOpenAudioVideoSync)
                            ChannelInfoMoreItem.PICTURE_IN_PICTURE -> closeMoreThen(callbacks.onEnterPictureInPicture)
                            ChannelInfoMoreItem.CAST -> closeMoreThen(
                                if (actionInputs.isCastConnected) callbacks.onStopCasting else callbacks.onCast
                            )
                            ChannelInfoMoreItem.STATS -> closeMoreThen(callbacks.onToggleDiagnostics)
                            ChannelInfoMoreItem.MULTIVIEW -> {
                                dismissOverlay()
                                callbacks.onOpenSplitScreen()
                            }
                            ChannelInfoMoreItem.LAST_GROUP -> {
                                // The host closes the overlay as part of opening the group.
                                overlayDismissing = true
                                onExpandedPanelChange(null)
                                callbacks.onOpenLastGroup()
                            }
                        }
                    }
                )
            }
        }
    }
}

/** Keeps the last side-sheet panel so the sheet's title and rows survive its own exit animation. */
@Composable
private fun rememberLatchedSheetPanel(expandedPanel: ChannelInfoPanel?): ChannelInfoPanel {
    val latched = remember { arrayOf(ChannelInfoPanel.MORE) }
    expandedPanel?.takeIf { it.isSideSheet }?.let { latched[0] = it }
    return latched[0]
}

@Composable
private fun rememberTickingNowMs(): Long {
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    return nowMs
}

// Compose invalidates the whole window while anything animates, so every translucent fill is
// repainted on every animation frame and its GPU cost scales with its area. One flat panel behind
// the whole bottom block replaces the gradient scrims; it is solid, not a gradient, and stops at the
// safe margins. At 0.90 the video bleeding through (broadcaster tickers, white sets) stays faint
// enough that 13-14 sp secondary text keeps about 8.6:1 contrast even over a white frame.
private val PanelShape = RoundedCornerShape(16.dp)
private val PanelPaddingHorizontal = 16.dp
private val PanelPaddingVertical = 12.dp
private val PanelColor: Color @Composable get() = AppColors.Canvas.copy(alpha = 0.90f)

private val GlassColor: Color @Composable get() = AppColors.Canvas.copy(alpha = 0.62f)
private val GlassLine: Color @Composable get() = AppColors.Focus.copy(alpha = 0.12f)

@Composable
private fun TvStatusPills(isCasting: Boolean, isMuted: Boolean, isRecording: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (isCasting) {
            TvStatusPill(icon = Icons.Default.CastConnected, description = stringResource(R.string.player_stop_casting))
        }
        if (isMuted) {
            TvStatusPill(icon = Icons.AutoMirrored.Filled.VolumeOff, description = stringResource(R.string.player_unmute))
        }
        if (isRecording) {
            TvStatusPill(
                icon = Icons.Default.FiberManualRecord,
                description = null,
                label = stringResource(R.string.player_recording_badge),
                tint = AppColors.Live
            )
        }
    }
}

@Composable
private fun TvStatusPill(
    icon: ImageVector,
    description: String?,
    label: String? = null,
    tint: Color? = null
) {
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier = Modifier
            .height(26.dp)
            .clip(shape)
            .background(tint?.copy(alpha = 0.18f) ?: GlassColor)
            .border(1.dp, tint?.copy(alpha = 0.45f) ?: GlassLine, shape)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = tint ?: AppColors.TextPrimary,
            modifier = Modifier.size(16.dp)
        )
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextPrimary,
                maxLines = 1
            )
        }
    }
}

private enum class TvChipTone { NEUTRAL, ACCENT, WARN }

private data class TvInfoChip(val label: String, val tone: TvChipTone)

@Composable
private fun tvInfoChips(
    inputs: ChannelInfoActionInputs,
    resolutionLabel: String?,
    variantLabel: String?
): List<TvInfoChip> {
    val rewindBadge = stringResource(R.string.player_live_rewind_badge)
    val recordingBadge = stringResource(R.string.player_recording_badge)
    val scheduledBadge = stringResource(R.string.player_recording_scheduled_badge)
    val catchUpBadge = stringResource(R.string.player_catchup_badge)
    // A fresh list every recomposition would stop TvProgramInfo from skipping.
    return remember(
        inputs.showTimeshiftControls, inputs.recordingStatus, inputs.canBrowseArchive,
        resolutionLabel, variantLabel, rewindBadge, recordingBadge, scheduledBadge, catchUpBadge
    ) {
        buildList {
            resolutionLabel?.takeIf { it.isNotBlank() }?.let { add(TvInfoChip(it, TvChipTone.NEUTRAL)) }
            variantLabel?.let { add(TvInfoChip(it, TvChipTone.NEUTRAL)) }
            if (inputs.showTimeshiftControls) add(TvInfoChip(rewindBadge, TvChipTone.NEUTRAL))
            when (inputs.recordingStatus) {
                RecordingStatus.RECORDING -> add(TvInfoChip(recordingBadge, TvChipTone.WARN))
                RecordingStatus.SCHEDULED -> add(TvInfoChip(scheduledBadge, TvChipTone.ACCENT))
                else -> Unit
            }
            if (inputs.canBrowseArchive) add(TvInfoChip(catchUpBadge, TvChipTone.ACCENT))
        }
    }
}

@Composable
private fun TvProgramInfo(
    currentChannel: Channel?,
    displayChannelNumber: Int,
    currentProgram: Program?,
    chips: List<TvInfoChip>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.graphicsLayer(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (currentChannel != null) {
                val logoShape = RoundedCornerShape(10.dp)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(logoShape)
                        .border(1.dp, GlassLine, logoShape)
                ) {
                    ChannelLogoBadge(
                        channelName = currentChannel.name,
                        logoUrl = currentChannel.logoUrl,
                        backgroundColor = AppColors.SurfaceEmphasis.copy(alpha = 0.75f),
                        contentPadding = PaddingValues(6.dp),
                        textStyle = MaterialTheme.typography.labelLarge,
                        textColor = AppColors.TextPrimary,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            TvChannelLine(displayChannelNumber, currentChannel?.name, modifier = Modifier.weight(1f))
        }
        if (currentProgram != null) {
            Text(
                text = currentProgram.title,
                style = MaterialTheme.typography.displaySmall.copy(fontSize = 28.sp, lineHeight = 34.sp),
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            currentProgram.description.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 520.dp)
                )
            }
        } else {
            Text(
                text = stringResource(R.string.player_no_guide_data),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp),
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
                maxLines = 1
            )
        }
        if (chips.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(top = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                chips.forEach { TvChip(it) }
            }
        }
    }
}

/** Number, separator and name as separate nodes so a right-to-left name can't reorder the number. */
@Composable
private fun TvChannelLine(displayChannelNumber: Int, channelName: String?, modifier: Modifier = Modifier) {
    val style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, letterSpacing = 0.26.sp)
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (displayChannelNumber > 0) {
            Text(
                text = String.format(Locale.getDefault(), "%d", displayChannelNumber),
                style = style,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
                maxLines = 1
            )
            if (channelName != null) {
                Text(
                    text = CHANNEL_LINE_SEPARATOR,
                    style = style,
                    color = AppColors.TextTertiary,
                    maxLines = 1
                )
            }
        }
        if (channelName != null) {
            Text(
                text = channelName,
                style = style,
                fontWeight = FontWeight.SemiBold,
                color = AppColors.TextSecondary,
                maxLines = 1,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 900,
                        repeatDelayMillis = 1200,
                        velocity = 24.dp
                    )
            )
        }
    }
}

// Typographic separator, not text: it reads the same in every locale.
private const val CHANNEL_LINE_SEPARATOR = " · "

private fun channelLine(displayChannelNumber: Int, channelName: String?): String = listOfNotNull(
    displayChannelNumber.takeIf { it > 0 }?.let { String.format(Locale.getDefault(), "%d", it) },
    channelName
).joinToString(CHANNEL_LINE_SEPARATOR)

@Composable
private fun TvChip(chip: TvInfoChip) {
    val shape = RoundedCornerShape(6.dp)
    val (container, outline, content) = when (chip.tone) {
        TvChipTone.NEUTRAL -> Triple(AppColors.Focus.copy(alpha = 0.08f), AppColors.Focus.copy(alpha = 0.10f), AppColors.TextSecondary)
        TvChipTone.ACCENT -> Triple(AppColors.Brand.copy(alpha = 0.16f), AppColors.Brand.copy(alpha = 0.32f), AppColors.BrandStrong)
        TvChipTone.WARN -> Triple(AppColors.Live.copy(alpha = 0.16f), AppColors.Live.copy(alpha = 0.35f), AppColors.TextPrimary)
    }
    Box(
        modifier = Modifier
            .height(22.dp)
            .clip(shape)
            .background(container)
            .border(1.dp, outline, shape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = chip.label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, letterSpacing = 0.35.sp),
            fontWeight = FontWeight.SemiBold,
            color = content,
            maxLines = 1
        )
    }
}

@Composable
private fun TvActionRow(
    modifier: Modifier,
    actionInputs: ChannelInfoActionInputs,
    expandedPanel: ChannelInfoPanel?,
    onExpandedPanelChange: (ChannelInfoPanel?) -> Unit,
    playPauseFocusRequester: FocusRequester,
    moreButtonFocusRequester: FocusRequester,
    subtitlesButtonFocusRequester: FocusRequester,
    audioButtonFocusRequester: FocusRequester,
    isPlaying: Boolean,
    timeshiftUiState: PlayerTimeshiftUiState,
    callbacks: TvChannelInfoCallbacks,
    onDismissOverlay: () -> Unit
) {
    val recordButtonFocusRequester = remember { FocusRequester() }
    val catchUpButtonFocusRequester = remember { FocusRequester() }
    val trayFirstItemFocusRequester = remember { FocusRequester() }
    val playOpensDvr = actionInputs.showTimeshiftControls

    fun handleMainActionFocus(ownerPanel: ChannelInfoPanel?) {
        callbacks.onOverlayInteracted()
        if (expandedPanel != null && expandedPanel != ownerPanel) onExpandedPanelChange(null)
    }

    fun togglePanel(panel: ChannelInfoPanel) {
        onExpandedPanelChange(if (expandedPanel == panel) null else panel)
    }

    fun Modifier.trayOwner(panel: ChannelInfoPanel): Modifier = focusProperties {
        if (expandedPanel == panel) up = trayFirstItemFocusRequester
    }

    val trayOwnerFocusRequester = when (expandedPanel) {
        ChannelInfoPanel.LIVE_DVR -> playPauseFocusRequester
        ChannelInfoPanel.RECORD -> recordButtonFocusRequester
        ChannelInfoPanel.CATCH_UP -> catchUpButtonFocusRequester
        ChannelInfoPanel.MORE, ChannelInfoPanel.SUBTITLES, ChannelInfoPanel.AUDIO,
        ChannelInfoPanel.SUBTITLE_DELAY, null -> null
    }

    TrayAnchoredLayout(
        modifier = modifier,
        tray = {
            if (trayOwnerFocusRequester != null) {
                TvActionTray(
                    panel = expandedPanel,
                    actionInputs = actionInputs,
                    isPlaying = isPlaying,
                    timeshiftUiState = timeshiftUiState,
                    firstItemFocusRequester = trayFirstItemFocusRequester,
                    ownerFocusRequester = trayOwnerFocusRequester,
                    callbacks = callbacks,
                    onClosePanel = { onExpandedPanelChange(null) },
                    onDismissOverlay = onDismissOverlay
                )
            }
        }
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ActionRowGap), verticalAlignment = Alignment.CenterVertically) {
            TvPlayPauseButton(
                isPlaying = isPlaying,
                label = when {
                    playOpensDvr -> stringResource(R.string.player_live_dvr_controls)
                    isPlaying -> stringResource(R.string.player_pause)
                    else -> stringResource(R.string.player_play)
                },
                isOwner = expandedPanel == ChannelInfoPanel.LIVE_DVR,
                onClick = { if (playOpensDvr) togglePanel(ChannelInfoPanel.LIVE_DVR) else callbacks.onTogglePlayPause() },
                onInteraction = { handleMainActionFocus(ChannelInfoPanel.LIVE_DVR.takeIf { playOpensDvr }) },
                modifier = Modifier
                    .focusRequester(playPauseFocusRequester)
                    .trayOwner(ChannelInfoPanel.LIVE_DVR)
            )
            Row(
                modifier = Modifier
                    .graphicsLayer()
                    .border(1.dp, GlassLine, CircleShape)
                    .padding(5.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                channelInfoRowActions(actionInputs).forEach { action ->
                    when (action) {
                        ChannelInfoRowAction.PLAY_PAUSE -> Unit
                        ChannelInfoRowAction.SUBTITLES -> TvPillButton(
                            icon = Icons.Default.ClosedCaption,
                            label = stringResource(R.string.player_subs),
                            isOwner = expandedPanel == ChannelInfoPanel.SUBTITLES ||
                                expandedPanel == ChannelInfoPanel.SUBTITLE_DELAY,
                            onClick = { togglePanel(ChannelInfoPanel.SUBTITLES) },
                            onInteraction = { handleMainActionFocus(ChannelInfoPanel.SUBTITLES) },
                            modifier = Modifier.focusRequester(subtitlesButtonFocusRequester)
                        )
                        ChannelInfoRowAction.AUDIO -> TvPillButton(
                            icon = Icons.Default.Audiotrack,
                            label = stringResource(R.string.player_audio),
                            isOwner = expandedPanel == ChannelInfoPanel.AUDIO,
                            onClick = { togglePanel(ChannelInfoPanel.AUDIO) },
                            onInteraction = { handleMainActionFocus(ChannelInfoPanel.AUDIO) },
                            modifier = Modifier.focusRequester(audioButtonFocusRequester)
                        )
                        ChannelInfoRowAction.GUIDE -> TvPillButton(
                            icon = Icons.AutoMirrored.Filled.EventNote,
                            label = stringResource(R.string.player_action_guide),
                            onClick = {
                                onDismissOverlay()
                                callbacks.onOpenFullEpg()
                            },
                            onInteraction = { handleMainActionFocus(null) }
                        )
                        ChannelInfoRowAction.RECORD -> TvPillButton(
                            icon = Icons.Default.FiberManualRecord,
                            iconTint = AppColors.Live,
                            label = stringResource(R.string.player_record),
                            isOwner = expandedPanel == ChannelInfoPanel.RECORD,
                            onClick = { togglePanel(ChannelInfoPanel.RECORD) },
                            onInteraction = { handleMainActionFocus(ChannelInfoPanel.RECORD) },
                            modifier = Modifier
                                .focusRequester(recordButtonFocusRequester)
                                .trayOwner(ChannelInfoPanel.RECORD)
                        )
                        ChannelInfoRowAction.CATCH_UP -> TvPillButton(
                            icon = Icons.Default.History,
                            label = stringResource(R.string.player_catchup_badge),
                            isOwner = expandedPanel == ChannelInfoPanel.CATCH_UP,
                            onClick = { togglePanel(ChannelInfoPanel.CATCH_UP) },
                            onInteraction = { handleMainActionFocus(ChannelInfoPanel.CATCH_UP) },
                            modifier = Modifier
                                .focusRequester(catchUpButtonFocusRequester)
                                .trayOwner(ChannelInfoPanel.CATCH_UP)
                        )
                        ChannelInfoRowAction.MORE -> TvPillButton(
                            icon = Icons.Default.MoreHoriz,
                            label = stringResource(R.string.player_more),
                            isOwner = expandedPanel == ChannelInfoPanel.MORE,
                            onClick = { togglePanel(ChannelInfoPanel.MORE) },
                            onInteraction = { handleMainActionFocus(ChannelInfoPanel.MORE) },
                            modifier = Modifier.focusRequester(moreButtonFocusRequester)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Lays out [content] at its own size and floats [tray] above it, aligned to the end edge, so an
 * open tray never pushes the programme info aside.
 */
@Composable
private fun TrayAnchoredLayout(
    modifier: Modifier,
    tray: @Composable () -> Unit,
    content: @Composable () -> Unit
) {
    Layout(contents = listOf(content, tray), modifier = modifier) { (contentMeasurables, trayMeasurables), constraints ->
        val looseConstraints = constraints.copy(minWidth = 0, minHeight = 0)
        val contentPlaceable = contentMeasurables.first().measure(looseConstraints)
        val trayPlaceable = trayMeasurables.firstOrNull()?.measure(
            Constraints(maxWidth = constraints.maxWidth)
        )
        layout(contentPlaceable.width, contentPlaceable.height) {
            contentPlaceable.placeRelative(0, 0)
            trayPlaceable?.placeRelative(
                x = contentPlaceable.width - trayPlaceable.width,
                y = -trayPlaceable.height - TrayGap.roundToPx()
            )
        }
    }
}

/**
 * Focus surface for the action row. The TV Material surface animates colour, scale and border
 * through state read during composition, which recomposed the whole button every frame of a focus
 * move. Here focus is a state read only in the draw phase (background, border) and in the
 * [content] colour producer, and the scale is an [Animatable] driven from the focus callback and
 * read in a graphicsLayer block. A focus move therefore recomposes nothing in this surface; the
 * animation frames cost draw time, not composition time.
 */
@Composable
private fun TvFocusSurface(
    onClick: () -> Unit,
    shape: Shape,
    containerColor: Color,
    focusedContainerColor: Color,
    contentColor: Color,
    focusedContentColor: Color,
    focusedScale: Float,
    modifier: Modifier = Modifier,
    focusedBorder: BorderStroke? = null,
    content: @Composable BoxScope.(contentColor: () -> Color) -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .drawBehind {
                drawFocusSurface(
                    shape = shape,
                    fill = if (isFocused) focusedContainerColor else containerColor,
                    border = if (isFocused) focusedBorder else null
                )
            }
            .onFocusChanged {
                isFocused = it.isFocused
                val target = if (it.isFocused) focusedScale else 1f
                scope.launch { scale.animateTo(target, tween(FocusScaleMillis)) }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
    ) {
        content { if (isFocused) focusedContentColor else contentColor }
    }
}

/** Matches Modifier.background plus Modifier.border: the stroke sits inside the bounds. */
private fun DrawScope.drawFocusSurface(shape: Shape, fill: Color, border: BorderStroke?) {
    if (fill.alpha > 0f) {
        drawOutline(shape.createOutline(size, layoutDirection, this), fill)
    }
    if (border != null) {
        val strokeWidth = border.width.toPx()
        val inset = strokeWidth / 2f
        translate(inset, inset) {
            val outline = shape.createOutline(
                Size(size.width - strokeWidth, size.height - strokeWidth),
                layoutDirection,
                this
            )
            drawOutline(outline, border.brush, style = Stroke(strokeWidth))
        }
    }
}

/**
 * Icon tinted by a colour producer read in the draw phase, so a focus colour change redraws the icon
 * without recomposing it (androidx.tv Icon takes a plain Color, which needs a composition read).
 */
@Composable
private fun TvTintedIcon(imageVector: ImageVector, tint: () -> Color, modifier: Modifier = Modifier) {
    val painter = rememberVectorPainter(imageVector)
    Spacer(
        modifier.drawBehind {
            with(painter) { draw(size, colorFilter = ColorFilter.tint(tint())) }
        }
    )
}

@Composable
private fun TvPlayPauseButton(
    isPlaying: Boolean,
    label: String,
    isOwner: Boolean,
    onClick: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvFocusSurface(
        onClick = {
            onInteraction()
            onClick()
        },
        shape = CircleShape,
        containerColor = if (isOwner) AppColors.BrandStrong else AppColors.Brand,
        focusedContainerColor = AppColors.Focus,
        contentColor = AppColors.Canvas,
        focusedContentColor = AppColors.Canvas,
        focusedScale = 1.08f,
        focusedBorder = BorderStroke(4.dp, AppColors.Brand.copy(alpha = 0.55f)),
        modifier = modifier
            .size(56.dp)
            .semantics { contentDescription = label }
            .onFocusChanged { if (it.isFocused) onInteraction() }
    ) { contentColor ->
        TvTintedIcon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            tint = contentColor,
            modifier = Modifier
                .align(Alignment.Center)
                .size(30.dp)
        )
    }
}

/**
 * Icon button in the glass pill; its label shows only while focused. [onInteraction] runs on
 * focus and on click so the overlay's auto-hide timer restarts either way.
 */
@Composable
private fun TvPillButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
    iconTint: Color? = null,
    isOwner: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    Box {
        TvFocusSurface(
            onClick = {
                onInteraction()
                onClick()
            },
            shape = CircleShape,
            containerColor = if (isOwner) AppColors.Brand.copy(alpha = 0.24f) else Color.Transparent,
            focusedContainerColor = AppColors.Focus,
            contentColor = AppColors.TextPrimary,
            focusedContentColor = AppColors.Canvas,
            focusedScale = 1.04f,
            modifier = modifier
                .size(PillButtonSize)
                .semantics { contentDescription = label }
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onInteraction()
                }
        ) { contentColor ->
            TvTintedIcon(
                imageVector = icon,
                tint = { iconTint ?: contentColor() },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(22.dp)
            )
        }
        // Always composed and shown through alpha in the draw phase: adding and removing the label on
        // every focus move cost a text layout, a node insert and accessibility structure events.
        // The owner's tray opens in the same spot above the pill, so the label steps aside for it.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (isFocused && !isOwner) 1f else 0f }
        ) { TvPillLabel(label) }
    }
}

/**
 * Drawn above the icon without taking layout space, so showing or hiding it never moves the play
 * button or neighbouring icons. Centred on the icon via an unbounded wrap, then lifted by its own
 * height, so it needs no measurement of the row.
 */
@Composable
private fun TvPillLabel(label: String) {
    val liftPx = with(LocalDensity.current) { PillLabelGap.toPx() }
    Box(Modifier.wrapContentSize(align = Alignment.TopCenter, unbounded = true)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
            fontWeight = FontWeight.SemiBold,
            color = AppColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .graphicsLayer { translationY = -(size.height + liftPx) }
                .widthIn(max = PillLabelMaxWidth)
                .background(AppColors.Canvas.copy(alpha = 0.92f), RoundedCornerShape(10.dp))
                .border(1.dp, GlassLine, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
                .clearAndSetSemantics { }
        )
    }
}

private data class TvTrayEntry(
    val key: String,
    val icon: ImageVector,
    val label: String,
    val enabled: Boolean = true,
    val iconTint: Color? = null,
    val onClick: () -> Unit
)

@Composable
private fun TvActionTray(
    panel: ChannelInfoPanel?,
    actionInputs: ChannelInfoActionInputs,
    isPlaying: Boolean,
    timeshiftUiState: PlayerTimeshiftUiState,
    firstItemFocusRequester: FocusRequester,
    ownerFocusRequester: FocusRequester,
    callbacks: TvChannelInfoCallbacks,
    onClosePanel: () -> Unit,
    onDismissOverlay: () -> Unit
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .background(AppColors.Canvas.copy(alpha = 0.88f))
            .border(1.dp, GlassLine, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        when (panel) {
            ChannelInfoPanel.RECORD -> {
                TvTrayHeader(stringResource(R.string.player_record_options))
                TvTrayButtons(
                    entries = recordTrayEntries(actionInputs.recordingStatus, callbacks, onClosePanel),
                    firstItemFocusRequester = firstItemFocusRequester,
                    ownerFocusRequester = ownerFocusRequester,
                    onInteraction = callbacks.onOverlayInteracted
                )
            }
            ChannelInfoPanel.CATCH_UP -> {
                TvTrayHeader(stringResource(R.string.player_catchup_options))
                TvTrayButtons(
                    entries = catchUpTrayEntries(actionInputs, callbacks, onDismissOverlay),
                    firstItemFocusRequester = firstItemFocusRequester,
                    ownerFocusRequester = ownerFocusRequester,
                    onInteraction = callbacks.onOverlayInteracted
                )
            }
            ChannelInfoPanel.LIVE_DVR -> TvDvrTray(
                timeshiftUiState = timeshiftUiState,
                isPlaying = isPlaying,
                firstItemFocusRequester = firstItemFocusRequester,
                ownerFocusRequester = ownerFocusRequester,
                callbacks = callbacks
            )
            ChannelInfoPanel.MORE, ChannelInfoPanel.SUBTITLES, ChannelInfoPanel.AUDIO,
            ChannelInfoPanel.SUBTITLE_DELAY, null -> Unit
        }
    }
}

@Composable
private fun TvTrayHeader(text: String) {
    Text(
        text = text.uppercase(Locale.getDefault()),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 1.1.sp),
        fontWeight = FontWeight.Bold,
        color = AppColors.TextTertiary,
        modifier = Modifier.padding(horizontal = 6.dp)
    )
}

@Composable
private fun recordTrayEntries(
    recordingStatus: RecordingStatus?,
    callbacks: TvChannelInfoCallbacks,
    onClosePanel: () -> Unit
): List<TvTrayEntry> = channelInfoRecordItems(recordingStatus).map { item ->
    fun closeThen(action: () -> Unit): () -> Unit = {
        onClosePanel()
        action()
    }
    when (item) {
        ChannelInfoRecordItem.RECORD_NOW -> TvTrayEntry(
            item.name, Icons.Default.FiberManualRecord, stringResource(R.string.player_record_now),
            iconTint = AppColors.Live, onClick = closeThen(callbacks.onStartRecording)
        )
        ChannelInfoRecordItem.STOP_RECORDING -> TvTrayEntry(
            item.name, Icons.Default.Stop, stringResource(R.string.player_stop_recording),
            onClick = closeThen(callbacks.onStopRecording)
        )
        ChannelInfoRecordItem.CANCEL_SCHEDULED -> TvTrayEntry(
            item.name, Icons.Default.EventBusy, stringResource(R.string.player_cancel_scheduled_recording),
            onClick = closeThen(callbacks.onStopRecording)
        )
        ChannelInfoRecordItem.SCHEDULE -> TvTrayEntry(
            item.name, Icons.Default.Schedule, stringResource(R.string.player_schedule_recording),
            onClick = closeThen(callbacks.onScheduleRecording)
        )
        ChannelInfoRecordItem.SCHEDULE_DAILY -> TvTrayEntry(
            item.name, Icons.Default.Today, stringResource(R.string.player_schedule_daily_recording),
            onClick = closeThen(callbacks.onScheduleDailyRecording)
        )
        ChannelInfoRecordItem.SCHEDULE_WEEKLY -> TvTrayEntry(
            item.name, Icons.Default.DateRange, stringResource(R.string.player_schedule_weekly_recording),
            onClick = closeThen(callbacks.onScheduleWeeklyRecording)
        )
        ChannelInfoRecordItem.SCHEDULE_BY_TIME -> TvTrayEntry(
            item.name, Icons.Default.MoreTime, stringResource(R.string.player_schedule_by_time),
            onClick = closeThen(callbacks.onScheduleByTime)
        )
    }
}

@Composable
private fun catchUpTrayEntries(
    inputs: ChannelInfoActionInputs,
    callbacks: TvChannelInfoCallbacks,
    onDismissOverlay: () -> Unit
): List<TvTrayEntry> = channelInfoCatchUpItems(inputs).map { item ->
    when (item) {
        ChannelInfoCatchUpItem.RESTART -> TvTrayEntry(
            item.name, Icons.Default.RestartAlt, stringResource(R.string.player_restart)
        ) {
            callbacks.onRestartProgram()
            onDismissOverlay()
        }
        ChannelInfoCatchUpItem.BROWSE_ARCHIVE -> TvTrayEntry(
            item.name, Icons.Default.VideoLibrary, stringResource(R.string.player_browse_archive)
        ) {
            onDismissOverlay()
            callbacks.onOpenArchive()
        }
        ChannelInfoCatchUpItem.BROWSE_GUIDE -> TvTrayEntry(
            item.name, Icons.AutoMirrored.Filled.EventNote, stringResource(R.string.player_browse_guide_catchup)
        ) {
            onDismissOverlay()
            callbacks.onOpenFullEpg()
        }
    }
}

@Composable
private fun TvTrayButtons(
    entries: List<TvTrayEntry>,
    firstItemFocusRequester: FocusRequester,
    ownerFocusRequester: FocusRequester,
    onInteraction: () -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        entries.forEachIndexed { index, entry ->
            TvTrayButton(
                entry = entry,
                onInteraction = onInteraction,
                modifier = (if (index == 0) Modifier.focusRequester(firstItemFocusRequester) else Modifier)
                    .focusProperties { down = ownerFocusRequester }
            )
        }
    }
}

@Composable
private fun TvTrayButton(
    entry: TvTrayEntry,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    // A disabled tv Surface can't take focus, so Up from the owner would find nothing while the DVR
    // buffer is preparing and fall through to a channel change. Unavailable entries stay focusable but inert.
    TvFocusSurface(
        onClick = {
            onInteraction()
            if (entry.enabled) entry.onClick()
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = AppColors.Focus.copy(alpha = 0.07f),
        focusedContainerColor = AppColors.Focus,
        contentColor = AppColors.TextPrimary,
        focusedContentColor = AppColors.Canvas,
        focusedScale = 1.04f,
        modifier = modifier
            .height(40.dp)
            .alpha(
                when {
                    entry.enabled -> 1f
                    isFocused -> 0.62f
                    else -> 0.38f
                }
            )
            .semantics { if (!entry.enabled) disabled() }
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onInteraction()
            }
    ) { contentColor ->
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TvTintedIcon(
                imageVector = entry.icon,
                tint = { entry.iconTint ?: contentColor() },
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = entry.label,
                color = contentColor(),
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun TvDvrTray(
    timeshiftUiState: PlayerTimeshiftUiState,
    isPlaying: Boolean,
    firstItemFocusRequester: FocusRequester,
    ownerFocusRequester: FocusRequester,
    callbacks: TvChannelInfoCallbacks
) {
    val dvrState = liveDvrTransportState(timeshiftUiState)
    var lastTransportActionAtMs by remember { mutableLongStateOf(0L) }

    fun runTransportAction(enabled: Boolean, action: () -> Unit): () -> Unit = {
        val now = System.currentTimeMillis()
        if (shouldRunDvrTransportAction(enabled, now, lastTransportActionAtMs)) {
            lastTransportActionAtMs = now
            action()
        }
    }

    val statusLine = when {
        !dvrState.transportReady -> timeshiftUiState.statusMessage.ifBlank {
            stringResource(R.string.player_live_timeshift_buffering)
        }
        dvrState.isBehindLive -> stringResource(R.string.player_live_offset, formatTimeLabel(dvrState.behindLiveMs))
        else -> stringResource(R.string.player_live_ready)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier
                .widthIn(max = 220.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black.copy(alpha = 0.25f))
                .padding(horizontal = 10.dp, vertical = 7.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = stringResource(R.string.player_live_rewind_badge),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 0.8.sp),
                fontWeight = FontWeight.Bold,
                color = AppColors.Brand
            )
            Text(
                text = statusLine,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp, lineHeight = 19.sp),
                fontWeight = FontWeight.Bold,
                color = AppColors.TextPrimary
            )
            Text(
                text = stringResource(R.string.player_live_buffer_depth, formatTimeLabel(dvrState.bufferDepthMs)),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                color = AppColors.TextSecondary
            )
        }
        TvTrayButtons(
            entries = listOf(
                TvTrayEntry(
                    "back", Icons.Default.Replay10, stringResource(R.string.player_seek_back_10),
                    enabled = dvrState.transportReady,
                    onClick = runTransportAction(dvrState.transportReady, callbacks.onSeekBackward)
                ),
                TvTrayEntry(
                    "play_pause",
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                    enabled = dvrState.transportReady,
                    onClick = runTransportAction(dvrState.transportReady, callbacks.onTogglePlayPause)
                ),
                TvTrayEntry(
                    "forward", Icons.Default.Forward10, stringResource(R.string.player_seek_forward_10),
                    enabled = dvrState.canSeekTowardLive,
                    onClick = runTransportAction(dvrState.canSeekTowardLive, callbacks.onSeekForward)
                ),
                TvTrayEntry(
                    "live", Icons.Default.RadioButtonChecked, stringResource(R.string.player_jump_to_live_short),
                    enabled = dvrState.canSeekTowardLive,
                    iconTint = AppColors.Live,
                    onClick = runTransportAction(dvrState.canSeekTowardLive, callbacks.onSeekToLiveEdge)
                )
            ),
            firstItemFocusRequester = firstItemFocusRequester,
            ownerFocusRequester = ownerFocusRequester,
            onInteraction = callbacks.onOverlayInteracted
        )
    }
}

@Composable
private fun TvLiveProgress(
    currentProgram: Program?,
    nextProgram: Program?,
    behindLiveMs: Long
) {
    // Read here, not at the overlay root, so the per-second tick recomposes only this row.
    val nowMs = rememberTickingNowMs()
    val appTimeFormat = LocalUiTimeFormat.current
    val timeFormat = remember(appTimeFormat) { appTimeFormat.createTimeFormat() }
    val isBehindLive = behindLiveMs > LiveDvrTransportState.LIVE_EDGE_TOLERANCE_MS
    val liveTag: @Composable () -> Unit = {
        TvLiveTag(
            isBehindLive = isBehindLive,
            text = if (isBehindLive) {
                stringResource(R.string.player_live_offset, formatTimeLabel(behindLiveMs))
            } else {
                stringResource(R.string.player_live_ready)
            },
            clock = timeFormat.format(Date(nowMs)).takeUnless { isBehindLive }
        )
    }

    val start = currentProgram?.startTime ?: 0L
    val end = currentProgram?.endTime ?: 0L
    if (currentProgram == null || start !in 1..<end) {
        Box(modifier = Modifier.heightIn(min = 22.dp).graphicsLayer(), contentAlignment = Alignment.CenterStart) { liveTag() }
        return
    }

    val durationMs = (end - start).toFloat()
    val edgeFraction = ((nowMs - start) / durationMs).coerceIn(0f, 1f)
    val playheadFraction = (edgeFraction - behindLiveMs / durationMs).coerceIn(0f, 1f)

    Column(
        modifier = Modifier.graphicsLayer(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TvProgressBar(edgeFraction = edgeFraction, playheadFraction = playheadFraction, showLiveEdge = isBehindLive)
        TvProgressMetaRow(
            playheadFraction = playheadFraction,
            startText = {
                Text(
                    text = stringResource(
                        R.string.player_time_range_minutes,
                        timeFormat.format(Date(start)),
                        timeFormat.format(Date(end)),
                        currentProgram.durationMinutes
                    ),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    fontWeight = FontWeight.SemiBold,
                    color = AppColors.TextPrimary,
                    maxLines = 1
                )
            },
            liveTag = liveTag,
            endText = {
                val remainingMinutes = ((end - nowMs) / 60_000L).toInt().coerceAtLeast(0)
                val remaining = stringResource(R.string.player_minutes_remaining, remainingMinutes)
                val nextLabel = stringResource(R.string.player_next_label)
                Text(
                    text = buildAnnotatedString {
                        withStyle(SpanStyle(color = AppColors.TextSecondary)) { append(remaining) }
                        if (nextProgram != null) {
                            append(CHANNEL_LINE_SEPARATOR)
                            withStyle(SpanStyle(color = AppColors.BrandStrong, fontWeight = FontWeight.SemiBold)) {
                                append(nextLabel)
                            }
                            append(" ")
                            append(nextProgram.title)
                        }
                    },
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        )
    }
}

/** Track, buffer, fill and dots drawn in one pass; the bar changes every second and needs no layout. */
@Composable
private fun TvProgressBar(edgeFraction: Float, playheadFraction: Float, showLiveEdge: Boolean) {
    val trackColor = AppColors.Focus.copy(alpha = 0.18f)
    val bufferColor = AppColors.Brand.copy(alpha = 0.38f)
    val fillColor = AppColors.Brand
    val liveColor = AppColors.Live
    val headColor = AppColors.Focus
    val headRingColor = AppColors.Brand.copy(alpha = 0.35f)
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .drawBehind {
                val isRtl = layoutDirection == LayoutDirection.Rtl
                fun x(fraction: Float) = size.width * if (isRtl) 1f - fraction else fraction
                val centreY = size.height / 2
                val barHeight = 4.dp.toPx()
                val corner = CornerRadius(2.dp.toPx())
                fun bar(toFraction: Float, color: Color) {
                    val from = x(0f)
                    val to = x(toFraction)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(minOf(from, to), centreY - barHeight / 2),
                        size = Size(abs(to - from), barHeight),
                        cornerRadius = corner
                    )
                }
                bar(1f, trackColor)
                bar(edgeFraction, bufferColor)
                bar(playheadFraction, fillColor)
                if (showLiveEdge) drawCircle(liveColor, 4.dp.toPx(), Offset(x(edgeFraction), centreY))
                drawCircle(headRingColor, 9.dp.toPx(), Offset(x(playheadFraction), centreY))
                drawCircle(headColor, 6.dp.toPx(), Offset(x(playheadFraction), centreY))
            }
    )
}

/** Time range at the start, LIVE tag under the playhead (clamped), remaining/next text at the end. */
@Composable
private fun TvProgressMetaRow(
    playheadFraction: Float,
    startText: @Composable () -> Unit,
    liveTag: @Composable () -> Unit,
    endText: @Composable () -> Unit
) {
    Layout(
        contents = listOf(startText, liveTag, endText),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 22.dp)
    ) { (startMeasurables, tagMeasurables, endMeasurables), constraints ->
        val gap = 14.dp.roundToPx()
        val minEndWidth = 110.dp.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val startPlaceable = startMeasurables.first().measure(loose)
        val tagPlaceable = tagMeasurables.first().measure(loose)
        val tagX = liveTagStartOffset(
            rowWidth = constraints.maxWidth,
            tagWidth = tagPlaceable.width,
            startTextWidth = startPlaceable.width,
            playheadFraction = playheadFraction,
            gap = gap,
            minEndWidth = minEndWidth
        )
        val endMaxWidth = (constraints.maxWidth - tagX - tagPlaceable.width - gap).coerceAtLeast(0)
        val endPlaceable = endMeasurables.first().measure(loose.copy(maxWidth = endMaxWidth))
        val height = maxOf(startPlaceable.height, tagPlaceable.height, endPlaceable.height, constraints.minHeight)
        layout(constraints.maxWidth, height) {
            startPlaceable.placeRelative(0, (height - startPlaceable.height) / 2)
            tagPlaceable.placeRelative(tagX, (height - tagPlaceable.height) / 2)
            endPlaceable.placeRelative(constraints.maxWidth - endPlaceable.width, (height - endPlaceable.height) / 2)
        }
    }
}

@Composable
private fun TvLiveTag(isBehindLive: Boolean, text: String, clock: String?) {
    val shape = RoundedCornerShape(11.dp)
    Row(
        modifier = Modifier
            .height(22.dp)
            .clip(shape)
            .background(if (isBehindLive) AppColors.Focus.copy(alpha = 0.08f) else AppColors.Live.copy(alpha = 0.14f))
            .border(1.dp, if (isBehindLive) GlassLine else AppColors.Live.copy(alpha = 0.38f), shape)
            .padding(horizontal = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (isBehindLive) AppColors.TextTertiary else AppColors.Live)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 13.sp,
                letterSpacing = if (isBehindLive) 0.sp else 0.65.sp
            ),
            fontWeight = if (isBehindLive) FontWeight.SemiBold else FontWeight.Bold,
            color = AppColors.TextPrimary,
            maxLines = 1
        )
        if (clock != null) {
            Text(
                text = clock,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                fontWeight = FontWeight.Medium,
                color = AppColors.TextSecondary,
                maxLines = 1
            )
        }
    }
}

/**
 * End-edge side sheet shared by More, Subtitles and Audio. Nothing inside [AnimatedVisibility] is
 * composed while [visible] is false. [contentKey] restarts the sheet's focus when the panel changes.
 */
@Composable
private fun TvSideSheet(
    visible: Boolean,
    contentKey: Any,
    @StringRes titleRes: Int,
    subtitle: @Composable () -> String,
    rows: @Composable () -> (LazyListScope.(firstItemFocus: FocusRequester) -> Unit)
) {
    // slideInHorizontally works in absolute pixels, so the sign flips to keep the sheet entering from the end edge.
    val fromEnd = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(AppColors.Canvas.copy(alpha = SheetScrimAlpha))
            )
        }
        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally { fromEnd * it } + fadeIn(),
            exit = slideOutHorizontally { fromEnd * it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
        ) {
            key(contentKey) {
                TvSideSheetContent(
                    trapFocus = visible,
                    titleRes = titleRes,
                    subtitle = subtitle,
                    rows = rows
                )
            }
        }
    }
}

@Composable
private fun TvSideSheetContent(
    trapFocus: Boolean,
    @StringRes titleRes: Int,
    subtitle: @Composable () -> String,
    rows: @Composable () -> (LazyListScope.(firstItemFocus: FocusRequester) -> Unit)
) {
    val buildRows = rows()
    val firstItemFocusRequester = remember { FocusRequester() }
    // Reopening while the exit animation still runs keeps this composition alive, so the request keys on trapFocus.
    // The rows are laid out after composition; a track list can also be empty, so the request must not throw.
    LaunchedEffect(trapFocus) {
        if (!trapFocus) return@LaunchedEffect
        withFrameNanos { }
        firstItemFocusRequester.requestFocusSafely(tag = "TvSideSheet", target = "First sheet row")
    }

    Row(
        modifier = Modifier
            .fillMaxHeight()
            .width(MoreSheetWidth)
            .background(AppColors.Canvas.copy(alpha = 0.95f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp)
                .background(GlassLine)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 20.dp, end = 20.dp, top = SafeVertical, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(titleRes),
                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = 20.sp),
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextPrimary
                )
                Text(
                    text = subtitle(),
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp),
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusGroup()
                    // Keep D-pad focus inside the open sheet; Back is the only way out. Once it is
                    // closing, focus must be free to return to the pill that opened it.
                    .focusProperties { onExit = { if (trapFocus) cancelFocusChange() } },
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                buildRows(firstItemFocusRequester)
            }
        }
    }
}

private fun Modifier.firstRowFocus(index: Int, requester: FocusRequester): Modifier =
    if (index == 0) focusRequester(requester) else this

@Composable
private fun rememberTvMoreRows(
    actionInputs: ChannelInfoActionInputs,
    isMuted: Boolean,
    isDiagnosticsEnabled: Boolean,
    currentAspectRatio: String,
    resolutionLabel: String?,
    variantLabel: String?,
    onInteraction: () -> Unit,
    onItemClick: (ChannelInfoMoreItem) -> Unit
): LazyListScope.(FocusRequester) -> Unit {
    val items = remember(actionInputs) { channelInfoMoreItems(actionInputs) }
    return { firstItemFocus ->
        itemsIndexed(items, key = { _, item -> item.name }) { index, item ->
            TvMoreRow(
                item = item,
                isMuted = isMuted,
                isCastConnected = actionInputs.isCastConnected,
                isDiagnosticsEnabled = isDiagnosticsEnabled,
                currentAspectRatio = currentAspectRatio,
                resolutionLabel = resolutionLabel,
                variantLabel = variantLabel,
                lastGroupName = actionInputs.lastVisitedCategoryName,
                onInteraction = onInteraction,
                onClick = { onItemClick(item) },
                modifier = Modifier.firstRowFocus(index, firstItemFocus)
            )
        }
    }
}

/**
 * Subtitle and audio rows, built once per open from the track data. Picking a track applies it and
 * closes the sheet; the subtitle actions close it before they open their own UI.
 */
@Composable
private fun rememberTvTrackRows(
    panel: ChannelInfoPanel,
    inputs: TvTrackSheetInputs,
    onInteraction: () -> Unit,
    onClosePanel: () -> Unit,
    onOpenDelayPanel: () -> Unit
): LazyListScope.(FocusRequester) -> Unit {
    val tracks = if (panel == ChannelInfoPanel.AUDIO) inputs.audioTracks else inputs.subtitleTracks
    val rows = remember(panel, tracks, inputs.liveTranslationAvailable, inputs.liveTranslationActive, inputs.subtitleOffsetMs) {
        if (panel == ChannelInfoPanel.SUBTITLE_DELAY) {
            tvSubtitleDelayRows(inputs.subtitleOffsetMs)
        } else {
            tvTrackSheetRows(panel, tracks, inputs.liveTranslationAvailable, inputs.liveTranslationActive)
        }
    }
    return { firstItemFocus ->
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            if (row.kind == TvTrackSheetRowKind.DIVIDER) {
                TvSheetDivider()
                return@itemsIndexed
            }
            TvTrackRow(
                row = row,
                subtitleOffsetMs = inputs.subtitleOffsetMs,
                onInteraction = onInteraction,
                onClick = onClick@{
                    if (!row.isEnabled) return@onClick
                    when (row.kind) {
                        // The delay controls stay open so the offset can be stepped repeatedly.
                        TvTrackSheetRowKind.DELAY_EARLIER -> inputs.onAdjustSubtitleOffset(-SUBTITLE_OFFSET_STEP_MS)
                        TvTrackSheetRowKind.DELAY_LATER -> inputs.onAdjustSubtitleOffset(SUBTITLE_OFFSET_STEP_MS)
                        TvTrackSheetRowKind.DELAY_RESET -> inputs.onResetSubtitleOffset()
                        TvTrackSheetRowKind.SUBTITLE_DELAY -> onOpenDelayPanel()
                        else -> {
                            onClosePanel()
                            runTrackRowAction(row, panel, inputs)
                        }
                    }
                },
                modifier = Modifier.firstRowFocus(index, firstItemFocus)
            )
        }
    }
}

private fun runTrackRowAction(row: TvTrackSheetRow, panel: ChannelInfoPanel, inputs: TvTrackSheetInputs) {
    when (row.kind) {
        TvTrackSheetRowKind.OFF -> inputs.onSelectSubtitle(null)
        TvTrackSheetRowKind.TRACK -> row.trackId?.let { id ->
            if (panel == ChannelInfoPanel.SUBTITLES) inputs.onSelectSubtitle(id) else inputs.onSelectAudio(id)
        }
        TvTrackSheetRowKind.LIVE_TRANSLATION -> inputs.onSelectLiveTranslation()
        TvTrackSheetRowKind.LOAD_FROM_DEVICE -> inputs.onLoadLocalSubtitle()
        // Handled by the caller: dividers do nothing and the delay rows and entry keep the sheet open.
        TvTrackSheetRowKind.DIVIDER,
        TvTrackSheetRowKind.SUBTITLE_DELAY,
        TvTrackSheetRowKind.DELAY_EARLIER,
        TvTrackSheetRowKind.DELAY_LATER,
        TvTrackSheetRowKind.DELAY_RESET -> Unit
    }
}

@Composable
private fun TvTrackRow(
    row: TvTrackSheetRow,
    subtitleOffsetMs: Int,
    onInteraction: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = when (row.kind) {
        TvTrackSheetRowKind.OFF -> stringResource(R.string.player_track_off)
        TvTrackSheetRowKind.TRACK -> row.name.orEmpty()
        TvTrackSheetRowKind.LIVE_TRANSLATION -> stringResource(R.string.player_track_live_translation)
        TvTrackSheetRowKind.SUBTITLE_DELAY -> stringResource(R.string.player_subtitle_delay_action, subtitleOffsetMs)
        TvTrackSheetRowKind.DIVIDER -> return
        TvTrackSheetRowKind.LOAD_FROM_DEVICE -> stringResource(R.string.player_subtitle_load_from_device)
        TvTrackSheetRowKind.DELAY_EARLIER ->
            stringResource(R.string.player_subtitle_delay_earlier, SUBTITLE_OFFSET_STEP_MS)
        TvTrackSheetRowKind.DELAY_LATER ->
            stringResource(R.string.player_subtitle_delay_later, SUBTITLE_OFFSET_STEP_MS)
        TvTrackSheetRowKind.DELAY_RESET -> stringResource(R.string.player_subtitle_delay_reset)
    }
    TvSheetRow(
        label = label,
        onInteraction = onInteraction,
        onClick = onClick,
        showCheck = row.isSelected,
        // Stays focusable when unavailable, like the DVR tray: focus must not fall through to a channel change.
        modifier = if (row.isEnabled) modifier else modifier.alpha(0.38f)
    )
}

/** Not focusable, so D-pad moves skip it. */
@Composable
private fun TvSheetDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(1.dp)
            .background(GlassLine)
    )
}

@Composable
private fun TvMoreRow(
    item: ChannelInfoMoreItem,
    isMuted: Boolean,
    isCastConnected: Boolean,
    isDiagnosticsEnabled: Boolean,
    currentAspectRatio: String,
    resolutionLabel: String?,
    variantLabel: String?,
    lastGroupName: String?,
    onInteraction: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, label, value) = when (item) {
        ChannelInfoMoreItem.QUALITY -> Triple(Icons.Default.Hd, stringResource(R.string.player_quality_short), resolutionLabel)
        ChannelInfoMoreItem.VARIANTS -> Triple(Icons.Default.SwapHoriz, stringResource(R.string.player_variants_short), variantLabel)
        ChannelInfoMoreItem.FORMAT -> Triple(Icons.Default.Movie, stringResource(R.string.player_format_short), null)
        ChannelInfoMoreItem.ASPECT_RATIO -> Triple(Icons.Default.AspectRatio, stringResource(R.string.player_aspect_ratio), currentAspectRatio)
        ChannelInfoMoreItem.MUTE -> if (isMuted) {
            Triple(Icons.AutoMirrored.Filled.VolumeOff, stringResource(R.string.player_unmute), null)
        } else {
            Triple(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.player_mute), null)
        }
        ChannelInfoMoreItem.AV_SYNC -> Triple(Icons.Default.Sync, stringResource(R.string.player_av_sync_short), null)
        ChannelInfoMoreItem.MULTIVIEW -> Triple(Icons.Default.GridView, stringResource(R.string.player_multiview_short), null)
        ChannelInfoMoreItem.PICTURE_IN_PICTURE -> Triple(Icons.Default.PictureInPictureAlt, stringResource(R.string.player_pip_short), null)
        ChannelInfoMoreItem.CAST -> if (isCastConnected) {
            Triple(Icons.Default.CastConnected, stringResource(R.string.player_stop_casting), null)
        } else {
            Triple(Icons.Default.Cast, stringResource(R.string.player_cast), null)
        }
        ChannelInfoMoreItem.LAST_GROUP -> Triple(Icons.AutoMirrored.Filled.PlaylistPlay, lastGroupName.orEmpty(), null)
        ChannelInfoMoreItem.STATS -> Triple(Icons.Default.Insights, stringResource(R.string.player_stats), null)
    }
    TvSheetRow(
        label = label,
        onInteraction = onInteraction,
        onClick = onClick,
        icon = icon,
        value = value,
        showCheck = item == ChannelInfoMoreItem.STATS && isDiagnosticsEnabled,
        modifier = modifier
    )
}

/**
 * Row shared by every side sheet. Like [TvFocusSurface], focus is read only in the draw phase (fill,
 * icon tint and text colour producers), so a focus move recomposes no row.
 */
@Composable
private fun TvSheetRow(
    label: String,
    onInteraction: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = null,
    showCheck: Boolean = false
) {
    var isFocused by remember { mutableStateOf(false) }
    val focusedSecondary = AppColors.SurfaceAccent
    val secondary = AppColors.TextSecondary
    val tertiary = AppColors.TextTertiary
    val brand = AppColors.Brand
    val labelStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
    val valueStyle = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium)
    TvFocusSurface(
        onClick = {
            onInteraction()
            onClick()
        },
        shape = RoundedCornerShape(12.dp),
        containerColor = Color.Transparent,
        focusedContainerColor = AppColors.Focus,
        contentColor = AppColors.TextPrimary,
        focusedContentColor = AppColors.Canvas,
        focusedScale = 1f,
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onInteraction()
            }
    ) { contentColor ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                TvTintedIcon(
                    imageVector = icon,
                    tint = { if (isFocused) focusedSecondary else secondary },
                    modifier = Modifier.size(21.dp)
                )
            }
            BasicText(
                text = label,
                style = labelStyle,
                color = ColorProducer { contentColor() },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            when {
                showCheck -> TvTintedIcon(
                    imageVector = Icons.Default.Check,
                    tint = { if (isFocused) focusedSecondary else brand },
                    modifier = Modifier.size(20.dp)
                )
                value != null -> BasicText(
                    text = value,
                    style = valueStyle,
                    color = ColorProducer { if (isFocused) focusedSecondary else tertiary },
                    maxLines = 1
                )
            }
        }
    }
}
