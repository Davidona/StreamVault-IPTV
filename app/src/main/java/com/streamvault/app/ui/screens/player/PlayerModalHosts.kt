package com.streamvault.app.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import com.streamvault.feature.playback.ui.components.dialogs.ProgramHistoryDialog
import com.streamvault.app.ui.screens.multiview.MultiViewPlannerDialog
import com.streamvault.app.ui.screens.multiview.MultiViewViewModel
import com.streamvault.app.ui.screens.player.overlay.ChannelVariantSelectionDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerAudioVideoOffsetDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerEpisodeSelectionDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerSleepTimerDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerSpeedSelectionDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerTrackSelectionDialog
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Program
import com.streamvault.player.TrackType

@Composable
internal fun PlayerTopLevelModalHost(
    viewModel: PlayerViewModel,
    isInPictureInPictureMode: Boolean,
    showProgramHistory: Boolean,
    onDismissProgramHistory: () -> Unit,
    onSelectProgramHistory: (Program) -> Unit,
    showSplitDialog: Boolean,
    currentChannel: Channel?,
    onDismissSplitDialog: () -> Unit,
    onLaunchMultiView: () -> Unit,
) {
    if (!isInPictureInPictureMode && showProgramHistory) {
        val programHistory by viewModel.programHistory.collectAsStateWithLifecycle()
        ProgramHistoryDialog(
            programs = programHistory,
            onDismiss = onDismissProgramHistory,
            onProgramSelect = onSelectProgramHistory
        )
    }

    if (showSplitDialog && currentChannel != null) {
        val multiViewViewModel: MultiViewViewModel = hiltViewModel()
        MultiViewPlannerDialog(
            pendingChannel = currentChannel,
            onDismiss = onDismissSplitDialog,
            onLaunch = onLaunchMultiView,
            viewModel = multiViewViewModel
        )
    }
}

@Composable
internal fun PlayerControlsModalHost(
    viewModel: PlayerViewModel,
    isInPictureInPictureMode: Boolean,
    showTrackSelection: TrackType?,
    showVariantSelection: Boolean,
    currentChannel: Channel?,
    showSpeedSelection: Boolean,
    showStopPlaybackTimerDialog: Boolean,
    showIdleStandbyTimerDialog: Boolean,
    canSaveChannel: Boolean,
    showAudioVideoOffsetDialog: Boolean,
    showEpisodePicker: Boolean,
    fallbackTitle: String,
    fallbackEpisodeId: Long,
    fallbackSeasonNumber: Int?,
    onDismissModal: () -> Unit,
) {
    if (showAudioVideoOffsetDialog) {
        val audioVideoSyncEnabled by viewModel.audioVideoSyncEnabled.collectAsStateWithLifecycle()
        val audioVideoOffsetState by viewModel.audioVideoOffsetUiState.collectAsStateWithLifecycle()
        val castConnectionState by viewModel.castConnectionState.collectAsStateWithLifecycle()
        LaunchedEffect(audioVideoSyncEnabled) {
            if (!audioVideoSyncEnabled) {
                onDismissModal()
                viewModel.dismissAudioVideoOffsetPreview()
            }
        }
        if (!isInPictureInPictureMode) {
            PlayerAudioVideoOffsetDialog(
                visible = audioVideoSyncEnabled && castConnectionState != com.streamvault.feature.playback.cast.CastConnectionState.CONNECTED,
                state = audioVideoOffsetState,
                canSaveChannel = canSaveChannel,
                onDismiss = {
                    onDismissModal()
                    viewModel.dismissAudioVideoOffsetPreview()
                },
                onAdjust = viewModel::adjustAudioVideoOffset,
                onReset = viewModel::resetAudioVideoOffsetPreview,
                onSaveForChannel = viewModel::saveAudioVideoOffsetForChannel,
                onSaveAsGlobal = viewModel::saveAudioVideoOffsetAsGlobal,
                onUseGlobal = viewModel::useGlobalAudioVideoOffset
            )
        }
    }

    if (!isInPictureInPictureMode && showTrackSelection != null) {
        val availableAudioTracks by viewModel.availableAudioTracks.collectAsStateWithLifecycle()
        val availableSubtitleTracks by viewModel.availableSubtitleTracks.collectAsStateWithLifecycle()
        val availableVideoQualities by viewModel.availableVideoQualities.collectAsStateWithLifecycle()
        val liveTranslationAvailable by viewModel.liveTranslationAvailable.collectAsStateWithLifecycle()
        val liveTranslationActive by viewModel.liveTranslationActive.collectAsStateWithLifecycle()
        PlayerTrackSelectionDialog(
            trackType = showTrackSelection,
            audioTracks = availableAudioTracks,
            subtitleTracks = availableSubtitleTracks,
            videoTracks = availableVideoQualities,
            liveTranslationAvailable = liveTranslationAvailable,
            liveTranslationActive = liveTranslationActive,
            onDismiss = onDismissModal,
            onSelectAudio = viewModel::selectAudioTrack,
            onSelectVideo = viewModel::selectVideoQuality,
            onSelectSubtitle = { trackId ->
                viewModel.deactivateLiveTranslation()
                viewModel.selectSubtitleTrack(trackId)
            },
            onSelectLiveTranslation = {
                viewModel.selectSubtitleTrack(null)
                viewModel.activateLiveTranslation()
            }
        )
    }
    if (!isInPictureInPictureMode && showVariantSelection) {
        ChannelVariantSelectionDialog(
            visible = true,
            channel = currentChannel,
            onDismiss = onDismissModal,
            onSelectVariant = viewModel::selectLiveVariant
        )
    }
    if (!isInPictureInPictureMode && showSpeedSelection) {
        val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
        PlayerSpeedSelectionDialog(
            visible = true,
            selectedSpeed = playbackSpeed,
            onDismiss = onDismissModal,
            onSelectSpeed = viewModel::setPlaybackSpeed
        )
    }
    if (!isInPictureInPictureMode && showStopPlaybackTimerDialog) {
        val sleepTimerUiState by viewModel.sleepTimerUiState.collectAsStateWithLifecycle()
        PlayerSleepTimerDialog(
            visible = true,
            title = stringResource(R.string.player_stop_playback_after),
            selectedMinutes = sleepTimerUiState.stopTimerMinutes,
            onDismiss = onDismissModal,
            onSelectMinutes = { minutes ->
                viewModel.notifyUserActivity()
                viewModel.setStopPlaybackTimer(minutes)
                onDismissModal()
            }
        )
    }
    if (!isInPictureInPictureMode && showIdleStandbyTimerDialog) {
        val sleepTimerUiState by viewModel.sleepTimerUiState.collectAsStateWithLifecycle()
        PlayerSleepTimerDialog(
            visible = true,
            title = stringResource(R.string.player_idle_standby_after),
            selectedMinutes = sleepTimerUiState.idleTimerMinutes,
            onDismiss = onDismissModal,
            onSelectMinutes = { minutes ->
                viewModel.notifyUserActivity()
                viewModel.setIdleStandbyTimer(minutes)
                onDismissModal()
            }
        )
    }
    if (!isInPictureInPictureMode && showEpisodePicker) {
        val playbackTitle by viewModel.playbackTitle.collectAsStateWithLifecycle()
        val currentSeries by viewModel.currentSeries.collectAsStateWithLifecycle()
        val currentEpisode by viewModel.currentEpisode.collectAsStateWithLifecycle()
        val seasons = remember(currentSeries) {
            currentSeries?.seasons.sanitizedForPlayer().orEmpty()
        }
        PlayerEpisodeSelectionDialog(
            visible = true,
            seriesTitle = currentSeries?.name ?: playbackTitle.ifBlank { fallbackTitle },
            seasons = seasons,
            currentEpisodeId = currentEpisode?.id ?: fallbackEpisodeId,
            currentSeasonNumber = currentEpisode?.seasonNumber ?: fallbackSeasonNumber,
            onDismiss = onDismissModal,
            onSelectEpisode = { episode ->
                onDismissModal()
                viewModel.playEpisode(episode)
            }
        )
    }
}
