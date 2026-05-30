package com.streamvault.app.ui.screens.player.overlay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.ChannelLogoBadge
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.screens.player.PlayerTimeshiftUiState
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.createTimeFormat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.player.timeshift.LiveTimeshiftStatus
import java.util.Date
import com.streamvault.app.ui.design.AppColors.Brand as Primary
import com.streamvault.app.ui.design.AppColors.TextTertiary as OnSurfaceDim

@Composable
fun ChannelInfoOverlay(
    currentChannel: Channel?,
    displayChannelNumber: Int,
    currentProgram: Program?,
    nextProgram: Program?,
    focusRequester: FocusRequester,
    lastVisitedCategoryName: String?,
    onDismiss: () -> Unit,
    onOverlayInteracted: () -> Unit,
    onOpenFullEpg: () -> Unit,
    onOpenLastGroup: () -> Unit,
    currentRecordingStatus: RecordingStatus?,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onScheduleRecording: () -> Unit,
    onScheduleDailyRecording: () -> Unit,
    onScheduleWeeklyRecording: () -> Unit,
    onRestartProgram: () -> Unit,
    onOpenArchive: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onToggleDiagnostics: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekToLiveEdge: () -> Unit,
    isPlaying: Boolean,
    currentAspectRatio: String,
    isDiagnosticsEnabled: Boolean,
    onOpenSplitScreen: () -> Unit = {},
    subtitleTrackCount: Int = 0,
    audioTrackCount: Int = 0,
    videoQualityCount: Int = 0,
    channelVariantCount: Int = 0,
    qualityOptionCount: Int = 0,
    isMuted: Boolean = false,
    onToggleMute: () -> Unit = {},
    onOpenSubtitleTracks: () -> Unit = {},
    onOpenAudioTracks: () -> Unit = {},
    onOpenVideoTracks: () -> Unit = {},
    onOpenVariants: () -> Unit = {},
    onOpenStreamFormats: () -> Unit = {},
    onOpenAudioVideoSync: () -> Unit = {},
    audioVideoSyncEnabled: Boolean = false,
    onEnterPictureInPicture: () -> Unit = {},
    isCastConnected: Boolean = false,
    onCast: () -> Unit = {},
    onStopCasting: () -> Unit = {},
    timeshiftUiState: PlayerTimeshiftUiState = PlayerTimeshiftUiState(),
    onTransientPanelVisibilityChanged: (Boolean) -> Unit = {},
    resolutionLabel: String? = null
) {
    val appTimeFormat = LocalAppTimeFormat.current
    val timeFormat = remember(appTimeFormat) { appTimeFormat.createTimeFormat() }
    val showTimeshiftControls = timeshiftUiState.available && !isCastConnected
    val hasCatchUpOptions = currentChannel?.catchUpSupported == true || currentProgram?.hasArchive == true
    var expandedPanel by remember { mutableStateOf<ChannelInfoPanel?>(null) }
    val recordButtonFocusRequester = remember { FocusRequester() }
    val catchUpButtonFocusRequester = remember { FocusRequester() }
    val liveDvrPanelFocusRequester = remember { FocusRequester() }
    val recordPanelFocusRequester = remember { FocusRequester() }
    val catchUpPanelFocusRequester = remember { FocusRequester() }

    fun handleMainActionFocus(ownerPanel: ChannelInfoPanel?) {
        onOverlayInteracted()
        if (expandedPanel != null && expandedPanel != ownerPanel) {
            expandedPanel = null
        }
    }

    fun togglePanel(panel: ChannelInfoPanel) {
        expandedPanel = if (expandedPanel == panel) null else panel
    }

    LaunchedEffect(showTimeshiftControls, hasCatchUpOptions) {
        expandedPanel = when (expandedPanel) {
            ChannelInfoPanel.LIVE_DVR -> expandedPanel.takeIf { showTimeshiftControls }
            ChannelInfoPanel.CATCH_UP -> expandedPanel.takeIf { hasCatchUpOptions }
            else -> expandedPanel
        }
    }

    LaunchedEffect(expandedPanel) {
        onTransientPanelVisibilityChanged(expandedPanel != null)
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { onTransientPanelVisibilityChanged(false) }
    }

    BackHandler {
        if (expandedPanel != null) {
            expandedPanel = null
        } else {
            onDismiss()
        }
    }

    PlayerOverlayPanel(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentChannel != null) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.dp, AppColors.Focus.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
                        ) {
                            ChannelLogoBadge(
                                channelName = currentChannel.name,
                                logoUrl = currentChannel.logoUrl,
                                backgroundColor = AppColors.SurfaceEmphasis.copy(alpha = 0.46f),
                                contentPadding = PaddingValues(6.dp),
                                textStyle = MaterialTheme.typography.labelLarge,
                                textColor = AppColors.TextSecondary,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (displayChannelNumber > 0) {
                                StatusPill(
                                    label = stringResource(R.string.player_live_channel, displayChannelNumber),
                                    containerColor = AppColors.BrandMuted
                                )
                            }
                            if (showTimeshiftControls) {
                                StatusPill(
                                    label = stringResource(R.string.player_live_rewind_badge),
                                    containerColor = AppColors.SurfaceEmphasis
                                )
                            }
                            if (currentRecordingStatus == RecordingStatus.RECORDING) {
                                StatusPill(
                                    label = stringResource(R.string.player_recording_badge),
                                    containerColor = AppColors.Live
                                )
                            } else if (currentRecordingStatus == RecordingStatus.SCHEDULED) {
                                StatusPill(
                                    label = stringResource(R.string.player_recording_scheduled_badge),
                                    containerColor = AppColors.BrandMuted
                                )
                            }
                            if (currentChannel != null) {
                                Text(
                                    text = currentChannel.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = AppColors.TextPrimary,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
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
                            resolutionLabel?.takeIf { it.isNotBlank() }?.let { label ->
                                StatusPill(
                                    label = label,
                                    containerColor = AppColors.SurfaceEmphasis
                                )
                            }
                            currentChannel?.currentVariant
                                ?.takeIf { channelVariantCount > 1 }
                                ?.attributes
                                ?.toOverlayBadgeLabel()
                                ?.takeIf { it.isNotBlank() }
                                ?.let { variantLabel ->
                                    StatusPill(
                                        label = variantLabel,
                                        containerColor = AppColors.SurfaceEmphasis
                                    )
                                }
                            if (currentChannel?.catchUpSupported == true) {
                                StatusPill(
                                    label = stringResource(R.string.player_catchup_badge),
                                    containerColor = AppColors.Live
                                )
                            }
                        }

                        if (currentProgram != null) {
                            Text(
                                text = currentProgram.title,
                                style = MaterialTheme.typography.titleSmall,
                                color = AppColors.TextPrimary,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.player_time_range_minutes,
                                        timeFormat.format(Date(currentProgram.startTime)),
                                        timeFormat.format(Date(currentProgram.endTime)),
                                        currentProgram.durationMinutes
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AppColors.TextSecondary,
                                    maxLines = 1
                                )
                                val now = System.currentTimeMillis()
                                val start = currentProgram.startTime
                                val end = currentProgram.endTime
                                if (start in 1..<end) {
                                    val progress = (now - start).toFloat() / (end - start)
                                    val remainingMin = ((end - now) / 60000).toInt().coerceAtLeast(0)
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { progress.coerceIn(0f, 1f) },
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(999.dp)),
                                        color = Primary,
                                        trackColor = AppColors.SurfaceEmphasis.copy(alpha = 0.45f)
                                    )
                                    Text(
                                        text = stringResource(R.string.player_minutes_remaining, remainingMin),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = OnSurfaceDim,
                                        maxLines = 1
                                    )
                                }
                            }
                            if (nextProgram != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.player_next_label),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Primary
                                    )
                                    Text(
                                        text = nextProgram.title,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = timeFormat.format(Date(nextProgram.startTime)),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AppColors.TextTertiary
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.player_no_guide_data),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSecondary,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            if (showTimeshiftControls && expandedPanel == ChannelInfoPanel.LIVE_DVR) {
                CompactTimeshiftTransport(
                    timeshiftUiState = timeshiftUiState,
                    isPlaying = isPlaying,
                    onOverlayInteracted = onOverlayInteracted,
                    onTogglePlayPause = onTogglePlayPause,
                    onSeekBackward = onSeekBackward,
                    onSeekForward = onSeekForward,
                    onSeekToLiveEdge = onSeekToLiveEdge,
                    firstFocusRequester = liveDvrPanelFocusRequester,
                    ownerFocusRequester = focusRequester
                )
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                contentPadding = PaddingValues(end = 8.dp)
            ) {
                item {
                    QuickActionButton(
                        icon = if (showTimeshiftControls) "DVR" else stringResource(R.string.player_action_playback),
                        label = if (showTimeshiftControls) {
                            stringResource(R.string.player_live_dvr_controls)
                        } else if (isPlaying) {
                            stringResource(R.string.player_pause)
                        } else {
                            stringResource(R.string.player_play)
                        },
                        onClick = {
                            if (showTimeshiftControls) {
                                togglePanel(ChannelInfoPanel.LIVE_DVR)
                            } else {
                                onTogglePlayPause()
                            }
                        },
                        onInteraction = { handleMainActionFocus(ChannelInfoPanel.LIVE_DVR.takeIf { showTimeshiftControls }) },
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (expandedPanel == ChannelInfoPanel.LIVE_DVR) {
                                Primary.copy(alpha = 0.30f)
                            } else {
                                Primary.copy(alpha = 0.20f)
                            },
                            focusedContainerColor = Primary,
                            pressedContainerColor = Primary.copy(alpha = 0.8f)
                        ),
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .focusProperties {
                                if (expandedPanel == ChannelInfoPanel.LIVE_DVR) {
                                    up = liveDvrPanelFocusRequester
                                }
                            }
                    )
                }
                item {
                    QuickActionButton(
                        icon = stringResource(R.string.player_action_mute),
                        label = if (isMuted) stringResource(R.string.player_unmute) else stringResource(R.string.player_mute),
                        onClick = onToggleMute,
                        onInteraction = { handleMainActionFocus(null) }
                    )
                }
                if (subtitleTrackCount > 0) {
                    item {
                        QuickActionButton(
                            icon = stringResource(R.string.player_subs),
                            label = stringResource(R.string.player_subs),
                            onClick = onOpenSubtitleTracks,
                            onInteraction = { handleMainActionFocus(null) }
                        )
                    }
                }
                if (videoQualityCount > 0) {
                    item {
                        QuickActionButton(
                            icon = stringResource(R.string.player_action_quality),
                            label = stringResource(R.string.player_quality_short),
                            onClick = onOpenVideoTracks,
                            onInteraction = { handleMainActionFocus(null) }
                        )
                    }
                }
                if (channelVariantCount > 1) {
                    item {
                        QuickActionButton(
                            icon = stringResource(R.string.player_action_variants),
                            label = stringResource(R.string.player_variants_short),
                            onClick = onOpenVariants,
                            onInteraction = { handleMainActionFocus(null) }
                        )
                    }
                }
                if (qualityOptionCount > 1) {
                    item {
                        QuickActionButton(
                            icon = stringResource(R.string.player_action_format),
                            label = stringResource(R.string.player_format_short),
                            onClick = onOpenStreamFormats,
                            onInteraction = { handleMainActionFocus(null) }
                        )
                    }
                }
                if (audioTrackCount > 0) {
                    item {
                        QuickActionButton(
                            icon = stringResource(R.string.player_audio),
                            label = stringResource(R.string.player_audio),
                            onClick = onOpenAudioTracks,
                            onInteraction = { handleMainActionFocus(null) }
                        )
                    }
                }
                if (audioVideoSyncEnabled && !isCastConnected) {
                    item {
                        QuickActionButton(
                            icon = "A/V",
                            label = stringResource(R.string.player_av_sync_short),
                            onClick = {
                                expandedPanel = null
                                onOpenAudioVideoSync()
                            },
                            onInteraction = { handleMainActionFocus(null) }
                        )
                    }
                }
                item {
                    QuickActionButton(
                        icon = stringResource(R.string.player_action_guide),
                        label = stringResource(R.string.player_epg_short),
                        onClick = {
                            expandedPanel = null
                            onDismiss()
                            onOpenFullEpg()
                        },
                        onInteraction = { handleMainActionFocus(null) }
                    )
                }
                item {
                    QuickActionButton(
                        icon = stringResource(R.string.player_action_split),
                        label = stringResource(R.string.player_multiview_short),
                        onClick = {
                            expandedPanel = null
                            onDismiss()
                            onOpenSplitScreen()
                        },
                        onInteraction = { handleMainActionFocus(null) }
                    )
                }
                item {
                    QuickActionButton(
                        icon = stringResource(R.string.player_action_diagnostics),
                        label = stringResource(R.string.player_stats),
                        onClick = {
                            expandedPanel = null
                            onToggleDiagnostics()
                        },
                        onInteraction = { handleMainActionFocus(null) }
                    )
                }
                if (!lastVisitedCategoryName.isNullOrBlank()) {
                    item {
                        QuickActionButton(
                            icon = stringResource(R.string.player_action_group),
                            label = lastVisitedCategoryName,
                            onClick = {
                                expandedPanel = null
                                onOpenLastGroup()
                            },
                            onInteraction = { handleMainActionFocus(null) }
                        )
                    }
                }
                item {
                    QuickActionButton(
                        icon = "REC",
                        label = stringResource(R.string.player_record),
                        onClick = { togglePanel(ChannelInfoPanel.RECORD) },
                        onInteraction = { handleMainActionFocus(ChannelInfoPanel.RECORD) },
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (expandedPanel == ChannelInfoPanel.RECORD) Primary.copy(alpha = 0.22f) else AppColors.SurfaceEmphasis,
                            focusedContainerColor = Primary.copy(alpha = 0.85f)
                        ),
                        modifier = Modifier
                            .focusRequester(recordButtonFocusRequester)
                            .focusProperties {
                                if (expandedPanel == ChannelInfoPanel.RECORD) {
                                    up = recordPanelFocusRequester
                                }
                            }
                    )
                }
                if (hasCatchUpOptions) {
                    item {
                        QuickActionButton(
                            icon = "C-UP",
                            label = stringResource(R.string.player_catchup_badge),
                            onClick = { togglePanel(ChannelInfoPanel.CATCH_UP) },
                            onInteraction = { handleMainActionFocus(ChannelInfoPanel.CATCH_UP) },
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = if (expandedPanel == ChannelInfoPanel.CATCH_UP) Primary.copy(alpha = 0.22f) else AppColors.SurfaceEmphasis,
                                focusedContainerColor = Primary.copy(alpha = 0.85f)
                            ),
                            modifier = Modifier
                                .focusRequester(catchUpButtonFocusRequester)
                                .focusProperties {
                                    if (expandedPanel == ChannelInfoPanel.CATCH_UP) {
                                        up = catchUpPanelFocusRequester
                                    }
                                }
                        )
                    }
                }
                item {
                    QuickActionButton(
                        icon = stringResource(R.string.player_action_cast),
                        label = if (isCastConnected) stringResource(R.string.player_stop_casting) else stringResource(R.string.player_cast),
                        onClick = {
                            expandedPanel = null
                            if (isCastConnected) onStopCasting() else onCast()
                        },
                        onInteraction = { handleMainActionFocus(null) }
                    )
                }
                item {
                    QuickActionButton(
                        icon = stringResource(R.string.player_action_pip),
                        label = stringResource(R.string.player_pip_short),
                        onClick = {
                            expandedPanel = null
                            onEnterPictureInPicture()
                        },
                        onInteraction = { handleMainActionFocus(null) }
                    )
                }
                item {
                    QuickActionButton(
                        icon = stringResource(R.string.player_action_view),
                        label = currentAspectRatio,
                        onClick = {
                            expandedPanel = null
                            onToggleAspectRatio()
                        },
                        onInteraction = { handleMainActionFocus(null) }
                    )
                }
            }

            when (expandedPanel) {
                ChannelInfoPanel.RECORD -> {
                    ChannelInfoActionMenuTray(
                        title = stringResource(R.string.player_record_options),
                        actions = buildList {
                            if (currentRecordingStatus == RecordingStatus.RECORDING || currentRecordingStatus == RecordingStatus.SCHEDULED) {
                                add(
                                    ChannelInfoMenuEntry(
                                        label = if (currentRecordingStatus == RecordingStatus.SCHEDULED) {
                                            stringResource(R.string.player_cancel_scheduled_recording)
                                        } else {
                                            stringResource(R.string.player_stop_recording)
                                        }
                                    ) {
                                        expandedPanel = null
                                        onStopRecording()
                                    }
                                )
                            } else {
                                add(
                                    ChannelInfoMenuEntry(stringResource(R.string.player_record_now)) {
                                        expandedPanel = null
                                        onStartRecording()
                                    }
                                )
                            }
                            add(ChannelInfoMenuEntry(stringResource(R.string.player_schedule_recording)) {
                                expandedPanel = null
                                onScheduleRecording()
                            })
                            add(ChannelInfoMenuEntry(stringResource(R.string.player_schedule_daily_recording)) {
                                expandedPanel = null
                                onScheduleDailyRecording()
                            })
                            add(ChannelInfoMenuEntry(stringResource(R.string.player_schedule_weekly_recording)) {
                                expandedPanel = null
                                onScheduleWeeklyRecording()
                            })
                        },
                        onInteraction = onOverlayInteracted,
                        firstActionFocusRequester = recordPanelFocusRequester,
                        ownerFocusRequester = recordButtonFocusRequester
                    )
                }

                ChannelInfoPanel.CATCH_UP -> {
                    ChannelInfoActionMenuTray(
                        title = stringResource(R.string.player_catchup_options),
                        actions = buildList {
                            if (currentProgram?.hasArchive == true) {
                                add(ChannelInfoMenuEntry(stringResource(R.string.player_restart)) {
                                    expandedPanel = null
                                    onRestartProgram()
                                    onDismiss()
                                })
                            }
                            if (currentChannel?.catchUpSupported == true) {
                                add(ChannelInfoMenuEntry(stringResource(R.string.player_browse_archive)) {
                                    expandedPanel = null
                                    onDismiss()
                                    onOpenArchive()
                                })
                            }
                            add(ChannelInfoMenuEntry(stringResource(R.string.player_browse_guide_catchup)) {
                                expandedPanel = null
                                onDismiss()
                                onOpenFullEpg()
                            })
                        },
                        onInteraction = onOverlayInteracted,
                        firstActionFocusRequester = catchUpPanelFocusRequester,
                        ownerFocusRequester = catchUpButtonFocusRequester
                    )
                }

                ChannelInfoPanel.LIVE_DVR,
                null -> Unit
            }
        }
    }
}

private enum class ChannelInfoPanel {
    LIVE_DVR,
    RECORD,
    CATCH_UP
}

private fun com.streamvault.domain.model.LiveChannelVariantAttributes.toOverlayBadgeLabel(): String {
    val parts = buildList {
        resolutionLabel?.let(::add)
        codecLabel?.takeIf { it == "HEVC" || it == "AV1" }?.let(::add)
        frameRate?.takeIf { it >= 50 }?.let { add("${it}fps") }
        if (isHdr) {
            add("HDR")
        }
        sourceHint?.takeIf { it == "Backup" || it == "Alternate" || it == "Lite" || it == "Mobile" }?.let(::add)
    }
    return parts.joinToString(" ")
}

private data class ChannelInfoMenuEntry(
    val label: String,
    val onClick: () -> Unit
)

@Composable
private fun CompactTimeshiftTransport(
    timeshiftUiState: PlayerTimeshiftUiState,
    isPlaying: Boolean,
    onOverlayInteracted: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onSeekToLiveEdge: () -> Unit,
    firstFocusRequester: FocusRequester,
    ownerFocusRequester: FocusRequester
) {
    val transportReady = timeshiftUiState.bufferDepthMs > 1_000L &&
        timeshiftUiState.engineState.status != LiveTimeshiftStatus.PREPARING
    val behindLive = timeshiftUiState.bufferedBehindLiveMs.coerceAtLeast(0L)
    val bufferDepth = timeshiftUiState.bufferDepthMs.coerceAtLeast(0L)
    val liveProgress = if (bufferDepth > 0L) {
        ((bufferDepth - behindLive).toFloat() / bufferDepth.toFloat()).coerceIn(0f, 1f)
    } else {
        1f
    }
    val statusLine = when {
        !transportReady -> timeshiftUiState.statusMessage.ifBlank {
            stringResource(R.string.player_live_timeshift_buffering)
        }
        behindLive > 1_000L -> stringResource(
            R.string.player_live_offset,
            formatTimeLabel(behindLive)
        )
        else -> stringResource(R.string.player_live_ready)
    }
    var lastTransportActionAtMs by remember { mutableStateOf(0L) }

    fun runTransportAction(enabled: Boolean, action: () -> Unit) {
        if (!enabled) return
        val now = System.currentTimeMillis()
        if (now - lastTransportActionAtMs < 240L) return
        lastTransportActionAtMs = now
        onOverlayInteracted()
        action()
    }

    Surface(
        shape = RoundedCornerShape(18.dp),
        colors = SurfaceDefaults.colors(containerColor = Color.White.copy(alpha = 0.06f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    colors = SurfaceDefaults.colors(containerColor = Color.Black.copy(alpha = 0.22f))
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.player_live_rewind_badge),
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            color = Primary
                        )
                        Text(
                            text = statusLine,
                            style = MaterialTheme.typography.titleSmall.copy(fontSize = 20.sp),
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(
                                R.string.player_live_buffer_depth,
                                formatTimeLabel(bufferDepth)
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.7f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CompactTransportButton(
                        topLabel = stringResource(R.string.player_rewind),
                        mainLabel = stringResource(R.string.player_seek_back_10),
                        enabled = transportReady,
                        onInteraction = onOverlayInteracted,
                        onClick = { runTransportAction(transportReady, onSeekBackward) },
                        modifier = Modifier
                            .focusRequester(firstFocusRequester)
                            .focusProperties { down = ownerFocusRequester }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TvClickableSurface(
                        onClick = { runTransportAction(transportReady, onTogglePlayPause) },
                        enabled = transportReady,
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Primary.copy(alpha = 0.88f),
                            focusedContainerColor = Primary,
                            disabledContainerColor = Primary.copy(alpha = 0.28f)
                        ),
                        modifier = Modifier
                            .size(52.dp)
                            .focusProperties { down = ownerFocusRequester }
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isPlaying) {
                                Text(
                                    text = "II",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Color.White
                                )
                            } else {
                                androidx.tv.material3.Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = stringResource(R.string.player_play),
                                    tint = Color.White,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    CompactTransportButton(
                        topLabel = stringResource(R.string.player_forward),
                        mainLabel = stringResource(R.string.player_seek_forward_10),
                        enabled = transportReady && timeshiftUiState.canSeekToLive,
                        onInteraction = onOverlayInteracted,
                        onClick = { runTransportAction(transportReady && timeshiftUiState.canSeekToLive, onSeekForward) },
                        modifier = Modifier.focusProperties { down = ownerFocusRequester }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    CompactTransportButton(
                        topLabel = stringResource(R.string.player_live_ready),
                        mainLabel = stringResource(R.string.player_jump_to_live_short),
                        enabled = transportReady && timeshiftUiState.canSeekToLive,
                        highlighted = !timeshiftUiState.canSeekToLive,
                        onInteraction = onOverlayInteracted,
                        onClick = { runTransportAction(transportReady && timeshiftUiState.canSeekToLive, onSeekToLiveEdge) },
                        modifier = Modifier.focusProperties { down = ownerFocusRequester }
                    )
                }
            }

            androidx.compose.material3.LinearProgressIndicator(
                progress = { liveProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = Primary,
                trackColor = Color.White.copy(alpha = 0.16f)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.player_live_buffer_start),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.52f)
                )
                Text(
                    text = if (behindLive > 1_000L) {
                        stringResource(R.string.player_live_offset, formatTimeLabel(behindLive))
                    } else {
                        stringResource(R.string.player_live_ready)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.76f)
                )
                Text(
                    text = stringResource(R.string.player_live_buffer_end),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.52f)
                )
            }
        }
    }
}

@Composable
private fun CompactTransportButton(
    topLabel: String,
    mainLabel: String,
    enabled: Boolean,
    highlighted: Boolean = false,
    onInteraction: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvClickableSurface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (highlighted) {
                Primary.copy(alpha = 0.18f)
            } else {
                Color.White.copy(alpha = 0.08f)
            },
            focusedContainerColor = Primary.copy(alpha = 0.86f),
            disabledContainerColor = Color.White.copy(alpha = 0.04f)
        ),
        modifier = modifier
            .widthIn(min = 68.dp)
            .onFocusChanged {
                if (it.isFocused) onInteraction()
            }
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = topLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = Color.White.copy(alpha = if (enabled) 0.72f else 0.32f)
            )
            Text(
                text = mainLabel,
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp),
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.32f),
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun ChannelInfoActionMenuTray(
    title: String,
    actions: List<ChannelInfoMenuEntry>,
    onInteraction: () -> Unit,
    firstActionFocusRequester: FocusRequester,
    ownerFocusRequester: FocusRequester
) {
    if (actions.isEmpty()) return

    Surface(
        shape = RoundedCornerShape(18.dp),
        colors = SurfaceDefaults.colors(containerColor = Color.Black.copy(alpha = 0.22f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = Primary,
                fontWeight = FontWeight.Bold
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(actions) { index, action ->
                    CompactMenuActionButton(
                        text = action.label,
                        onClick = action.onClick,
                        onInteraction = onInteraction,
                        modifier = (if (index == 0) Modifier.focusRequester(firstActionFocusRequester) else Modifier)
                            .focusProperties { down = ownerFocusRequester }
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactMenuActionButton(
    text: String,
    onClick: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier
) {
    TvClickableSurface(
        onClick = {
            onInteraction()
            onClick()
        },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f),
            focusedContainerColor = Color.White.copy(alpha = 0.16f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.0f),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color.White.copy(alpha = 0.75f))
            )
        ),
        modifier = modifier
            .onFocusChanged {
                if (it.isFocused) onInteraction()
            }
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}
