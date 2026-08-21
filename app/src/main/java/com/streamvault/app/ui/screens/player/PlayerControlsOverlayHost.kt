package com.streamvault.app.ui.screens.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.cast.CastConnectionState
import com.streamvault.app.ui.screens.player.overlay.PlayerControlsOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerResolutionBadge
import com.streamvault.app.ui.screens.player.overlay.PlayerSleepTimerWarningOverlay
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.VideoFormat
import com.streamvault.player.PLAYER_TRACK_AUTO_ID
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerTrack

/**
 * Owns the player controls' high-frequency transport state at the overlay boundary.
 *
 * Keeping position and duration collection here prevents the screen coordinator from
 * also owning the controls' realtime state while we continue decomposing PlayerScreen.
 */
@Composable
internal fun PlayerControlsOverlayHost(
    playerEngine: PlayerEngine,
    viewModel: PlayerViewModel,
    visible: Boolean,
    title: String,
    contentType: String,
    modifier: Modifier = Modifier,
    isCatchUpPlayback: Boolean = false,
    isPlaying: Boolean,
    currentProgram: Program?,
    currentChannel: Channel?,
    currentChannelName: String?,
    displayChannelNumber: Int,
    aspectRatioLabel: String,
    playButtonFocusRequester: FocusRequester,
    quickActionsFocusRequester: FocusRequester,
    onOpenArchive: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenVideoTracks: () -> Unit,
    onOpenPlaybackSpeed: () -> Unit,
    onOpenStopPlaybackTimer: () -> Unit,
    onOpenIdleStandbyTimer: () -> Unit,
    onOpenAudioVideoSync: () -> Unit,
    showEpisodesAction: Boolean,
    onOpenEpisodes: () -> Unit,
    onOpenSplitScreen: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onRunRecordingAction: (() -> Unit) -> Unit,
    onOpenCastRouteChooser: () -> Unit
) {
    val currentPosition by playerEngine.currentPosition.collectAsStateWithLifecycle()
    val duration by playerEngine.duration.collectAsStateWithLifecycle()
    val availableAudioTracks by viewModel.availableAudioTracks.collectAsStateWithLifecycle()
    val availableSubtitleTracks by viewModel.availableSubtitleTracks.collectAsStateWithLifecycle()
    val availableVideoQualities by viewModel.availableVideoQualities.collectAsStateWithLifecycle()
    val liveTranslationAvailable by viewModel.liveTranslationAvailable.collectAsStateWithLifecycle()
    val currentChannelRecording by viewModel.currentChannelRecording.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val mediaTitle by viewModel.mediaTitle.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val audioVideoSyncEnabled by viewModel.audioVideoSyncEnabled.collectAsStateWithLifecycle()
    val castConnectionState by viewModel.castConnectionState.collectAsStateWithLifecycle()
    val seekPreview by viewModel.seekPreview.collectAsStateWithLifecycle()
    val timeshiftUiState by viewModel.timeshiftUiState.collectAsStateWithLifecycle()
    val sleepTimerUiState by viewModel.sleepTimerUiState.collectAsStateWithLifecycle()

    PlayerControlsOverlay(
        visible = visible,
        title = title,
        contentType = contentType,
        isCatchUpPlayback = isCatchUpPlayback,
        isPlaying = isPlaying,
        currentProgram = currentProgram,
        currentChannel = currentChannel,
        currentChannelName = currentChannelName,
        displayChannelNumber = displayChannelNumber,
        currentPosition = currentPosition,
        duration = duration,
        aspectRatioLabel = aspectRatioLabel,
        subtitleTrackCount = availableSubtitleTracks.size,
        liveTranslationAvailable = liveTranslationAvailable,
        audioTrackCount = availableAudioTracks.size,
        videoQualityCount = availableVideoQualities.size,
        currentRecordingStatus = currentChannelRecording?.status,
        isMuted = isMuted,
        playbackSpeed = playbackSpeed,
        mediaTitle = mediaTitle,
        sleepTimerUiState = sleepTimerUiState,
        timeshiftUiState = timeshiftUiState,
        playButtonFocusRequester = playButtonFocusRequester,
        quickActionsFocusRequester = quickActionsFocusRequester,
        modifier = modifier,
        onClose = viewModel::toggleControls,
        onTogglePlayPause = { if (isPlaying) viewModel.pause() else viewModel.play() },
        onSeekBackward = viewModel::seekBackward,
        onSeekForward = viewModel::seekForward,
        onRestartProgram = viewModel::restartCurrentProgram,
        onOpenArchive = onOpenArchive,
        onStartRecording = { onRunRecordingAction(viewModel::startManualRecording) },
        onStopRecording = viewModel::stopCurrentRecording,
        onScheduleRecording = { onRunRecordingAction(viewModel::scheduleRecording) },
        onScheduleDailyRecording = { onRunRecordingAction(viewModel::scheduleDailyRecording) },
        onScheduleWeeklyRecording = { onRunRecordingAction(viewModel::scheduleWeeklyRecording) },
        onToggleAspectRatio = viewModel::toggleAspectRatio,
        onOpenSubtitleTracks = onOpenSubtitleTracks,
        onOpenAudioTracks = onOpenAudioTracks,
        onOpenVideoTracks = onOpenVideoTracks,
        onOpenPlaybackSpeed = onOpenPlaybackSpeed,
        onOpenStopPlaybackTimer = onOpenStopPlaybackTimer,
        onOpenIdleStandbyTimer = onOpenIdleStandbyTimer,
        onOpenAudioVideoSync = onOpenAudioVideoSync,
        audioVideoSyncEnabled = audioVideoSyncEnabled,
        showEpisodesAction = showEpisodesAction,
        onOpenEpisodes = onOpenEpisodes,
        onOpenSplitScreen = onOpenSplitScreen,
        onEnterPictureInPicture = onEnterPictureInPicture,
        onToggleMute = viewModel::toggleMute,
        isCastConnected = castConnectionState == CastConnectionState.CONNECTED,
        onCast = { viewModel.castCurrentMedia(onOpenCastRouteChooser) },
        onStopCasting = viewModel::stopCasting,
        onSeekToLiveEdge = viewModel::seekToLiveEdge,
        onSeekToPosition = viewModel::seekTo,
        onSetScrubbingMode = viewModel::setScrubbingMode,
        seekPreview = seekPreview,
        onSeekPreviewPositionChanged = viewModel::updateSeekPreview,
        onUserInteraction = {
            viewModel.notifyUserActivity()
            viewModel.refreshControlsAutoHide()
        }
    )
}

@Composable
internal fun PlayerRecordingIndicatorHost(
    viewModel: PlayerViewModel,
    modifier: Modifier = Modifier
) {
    val currentChannelRecording by viewModel.currentChannelRecording.collectAsStateWithLifecycle()
    if (currentChannelRecording?.status != RecordingStatus.RECORDING) return

    val recordingPulse = rememberInfiniteTransition(label = "recordingPulse")
    val recordingAlpha by recordingPulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 750),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recordingAlpha"
    )
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(Color(0xFFFF4D4F).copy(alpha = recordingAlpha), RoundedCornerShape(999.dp))
        )
        Text(
            text = stringResource(R.string.settings_recording_status_recording),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White
        )
    }
}

@Composable
internal fun PlayerSleepTimerWarningHost(
    viewModel: PlayerViewModel,
    isInPictureInPictureMode: Boolean,
    modifier: Modifier = Modifier
) {
    if (isInPictureInPictureMode) return
    val sleepTimerUiState by viewModel.sleepTimerUiState.collectAsStateWithLifecycle()
    PlayerSleepTimerWarningOverlay(
        state = sleepTimerUiState,
        modifier = modifier,
        onExtendStopTimer = viewModel::extendStopPlaybackTimer,
        onDisableStopTimer = viewModel::disableStopPlaybackTimer,
        onExtendIdleTimer = viewModel::extendIdleStandbyTimer,
        onDisableIdleTimer = viewModel::disableIdleStandbyTimer
    )
}

@Composable
internal fun PlayerResolutionBadgeHost(
    viewModel: PlayerViewModel,
    streamUrl: String,
    videoFormat: VideoFormat,
    controlsVisible: Boolean,
    modifier: Modifier = Modifier
) {
    val videoTracks by viewModel.availableVideoQualities.collectAsStateWithLifecycle()
    val resolutionBadgeLabel = buildResolutionBadgeLabel(
        videoFormat = videoFormat,
        videoTracks = videoTracks,
        autoResolutionLabel = stringResource(R.string.player_resolution_auto_label, videoFormat.resolutionLabel)
    )
    var showResolution by remember(streamUrl) { mutableStateOf(false) }
    var lastResolutionBadgeLabel by remember(streamUrl) { mutableStateOf<String?>(null) }

    LaunchedEffect(resolutionBadgeLabel) {
        val nextLabel = resolutionBadgeLabel ?: run {
            showResolution = false
            lastResolutionBadgeLabel = null
            return@LaunchedEffect
        }
        if (nextLabel == lastResolutionBadgeLabel) return@LaunchedEffect
        lastResolutionBadgeLabel = nextLabel
        showResolution = true
        kotlinx.coroutines.delay(3000)
        if (lastResolutionBadgeLabel == nextLabel) showResolution = false
    }

    PlayerResolutionBadge(
        visible = showResolution && !controlsVisible && resolutionBadgeLabel != null,
        resolutionLabel = resolutionBadgeLabel.orEmpty(),
        modifier = modifier
    )
}

private fun buildResolutionBadgeLabel(
    videoFormat: VideoFormat,
    videoTracks: List<PlayerTrack>,
    autoResolutionLabel: String
): String? {
    if (videoFormat.isEmpty) return null
    val selectedTrack = videoTracks.firstOrNull(PlayerTrack::isSelected)
    return if (selectedTrack == null || selectedTrack.id == PLAYER_TRACK_AUTO_ID) {
        autoResolutionLabel
    } else {
        selectedTrack.name
    }
}
