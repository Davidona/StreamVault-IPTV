package com.streamvault.app.ui.screens.player

import androidx.compose.runtime.Composable
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.streamvault.app.ui.components.dialogs.ProgramHistoryDialog
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
import com.streamvault.domain.model.Season
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType

@Composable
internal fun PlayerTopLevelModalHost(
    isInPictureInPictureMode: Boolean,
    showProgramHistory: Boolean,
    programHistory: List<Program>,
    onDismissProgramHistory: () -> Unit,
    onSelectProgramHistory: (Program) -> Unit,
    showSplitDialog: Boolean,
    currentChannel: Channel?,
    onDismissSplitDialog: () -> Unit,
    onLaunchMultiView: () -> Unit,
) {
    if (!isInPictureInPictureMode && showProgramHistory) {
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
    isInPictureInPictureMode: Boolean,
    showTrackSelection: TrackType?,
    availableAudioTracks: List<PlayerTrack>,
    availableSubtitleTracks: List<PlayerTrack>,
    availableVideoQualities: List<PlayerTrack>,
    liveTranslationAvailable: Boolean,
    liveTranslationActive: Boolean,
    onDismissTrackSelection: () -> Unit,
    onSelectAudio: (String) -> Unit,
    onSelectVideo: (String) -> Unit,
    onSelectSubtitle: (String?) -> Unit,
    onSelectLiveTranslation: () -> Unit,
    showVariantSelection: Boolean,
    currentChannel: Channel?,
    onDismissVariantSelection: () -> Unit,
    onSelectVariant: (Long) -> Unit,
    showSpeedSelection: Boolean,
    playbackSpeed: Float,
    onDismissSpeedSelection: () -> Unit,
    onSelectSpeed: (Float) -> Unit,
    showStopPlaybackTimerDialog: Boolean,
    stopPlaybackTimerTitle: String,
    stopPlaybackTimerMinutes: Int,
    onDismissStopPlaybackTimer: () -> Unit,
    onSelectStopPlaybackTimer: (Int) -> Unit,
    showIdleStandbyTimerDialog: Boolean,
    idleStandbyTimerTitle: String,
    idleStandbyTimerMinutes: Int,
    onDismissIdleStandbyTimer: () -> Unit,
    onSelectIdleStandbyTimer: (Int) -> Unit,
    audioVideoOffsetVisible: Boolean,
    audioVideoOffsetState: PlayerAudioVideoOffsetUiState,
    canSaveChannel: Boolean,
    onDismissAudioVideoOffset: () -> Unit,
    onAdjustAudioVideoOffset: (Int) -> Unit,
    onResetAudioVideoOffset: () -> Unit,
    onSaveAudioVideoOffsetForChannel: () -> Unit,
    onSaveAudioVideoOffsetAsGlobal: () -> Unit,
    onUseGlobalAudioVideoOffset: () -> Unit,
    showEpisodePicker: Boolean,
    seriesTitle: String,
    seasons: List<Season>,
    currentEpisodeId: Long,
    currentSeasonNumber: Int?,
    onDismissEpisodePicker: () -> Unit,
    onSelectEpisode: (Episode) -> Unit,
) {
    if (!isInPictureInPictureMode) {
        PlayerTrackSelectionDialog(
            trackType = showTrackSelection,
            audioTracks = availableAudioTracks,
            subtitleTracks = availableSubtitleTracks,
            videoTracks = availableVideoQualities,
            liveTranslationAvailable = liveTranslationAvailable,
            liveTranslationActive = liveTranslationActive,
            onDismiss = onDismissTrackSelection,
            onSelectAudio = onSelectAudio,
            onSelectVideo = onSelectVideo,
            onSelectSubtitle = onSelectSubtitle,
            onSelectLiveTranslation = onSelectLiveTranslation
        )
        ChannelVariantSelectionDialog(
            visible = showVariantSelection,
            channel = currentChannel,
            onDismiss = onDismissVariantSelection,
            onSelectVariant = onSelectVariant
        )
        PlayerSpeedSelectionDialog(
            visible = showSpeedSelection,
            selectedSpeed = playbackSpeed,
            onDismiss = onDismissSpeedSelection,
            onSelectSpeed = onSelectSpeed
        )
        PlayerSleepTimerDialog(
            visible = showStopPlaybackTimerDialog,
            title = stopPlaybackTimerTitle,
            selectedMinutes = stopPlaybackTimerMinutes,
            onDismiss = onDismissStopPlaybackTimer,
            onSelectMinutes = onSelectStopPlaybackTimer
        )
        PlayerSleepTimerDialog(
            visible = showIdleStandbyTimerDialog,
            title = idleStandbyTimerTitle,
            selectedMinutes = idleStandbyTimerMinutes,
            onDismiss = onDismissIdleStandbyTimer,
            onSelectMinutes = onSelectIdleStandbyTimer
        )
        PlayerAudioVideoOffsetDialog(
            visible = audioVideoOffsetVisible,
            state = audioVideoOffsetState,
            canSaveChannel = canSaveChannel,
            onDismiss = onDismissAudioVideoOffset,
            onAdjust = onAdjustAudioVideoOffset,
            onReset = onResetAudioVideoOffset,
            onSaveForChannel = onSaveAudioVideoOffsetForChannel,
            onSaveAsGlobal = onSaveAudioVideoOffsetAsGlobal,
            onUseGlobal = onUseGlobalAudioVideoOffset
        )
        PlayerEpisodeSelectionDialog(
            visible = showEpisodePicker,
            seriesTitle = seriesTitle,
            seasons = seasons,
            currentEpisodeId = currentEpisodeId,
            currentSeasonNumber = currentSeasonNumber,
            onDismiss = onDismissEpisodePicker,
            onSelectEpisode = onSelectEpisode
        )
    }
}
