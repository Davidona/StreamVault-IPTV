package com.streamvault.app.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamvault.feature.playback.cast.CastConnectionState
import com.streamvault.app.ui.screens.player.overlay.ChannelInfoOverlay
import com.streamvault.app.ui.screens.player.overlay.ChannelListOverlay
import com.streamvault.app.ui.screens.player.overlay.CategoryListOverlay
import com.streamvault.app.ui.screens.player.overlay.EpgOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerNumericInputOverlay
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.VideoFormat
import com.streamvault.player.TrackType

/** Keeps live-only flow reads and overlays outside the player screen coordinator. */
@Composable
internal fun PlayerNumericInputOverlayHost(
    viewModel: PlayerViewModel,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    val numericChannelInput by viewModel.numericChannelInput.collectAsStateWithLifecycle()
    PlayerNumericInputOverlay(
        state = numericChannelInput,
        visible = visible,
        modifier = modifier
    )
}

@Composable
internal fun BoxScope.PlayerLiveOverlayHost(
    viewModel: PlayerViewModel,
    isRtl: Boolean,
    sideOverlayWidth: Dp,
    epgOverlayWidth: Dp,
    showChannelListOverlay: Boolean,
    showCategoryListOverlay: Boolean,
    showEpgOverlay: Boolean,
    showChannelInfoOverlay: Boolean,
    currentChannel: Channel?,
    internalChannelId: Long,
    displayChannelNumber: Int,
    channelListFocusRequester: FocusRequester,
    categoryListFocusRequester: FocusRequester,
    channelInfoFocusRequester: FocusRequester,
    isPlaying: Boolean,
    aspectRatioLabel: String,
    showDiagnostics: Boolean,
    videoFormat: VideoFormat,
    onOpenModal: (PlayerModal) -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onRunRecordingAction: (() -> Unit) -> Unit,
    onOpenCastRouteChooser: () -> Unit,
    onTransientPanelVisibilityChanged: (Boolean) -> Unit
) {
    val availableCategories by viewModel.availableCategories.collectAsStateWithLifecycle()
    val parentalControlLevel by viewModel.parentalControlLevel.collectAsStateWithLifecycle()
    val activeCategoryId by viewModel.activeCategoryId.collectAsStateWithLifecycle()
    val currentChannelList by viewModel.currentChannelList.collectAsStateWithLifecycle()
    val recentChannels by viewModel.recentChannels.collectAsStateWithLifecycle()
    val lastVisitedCategory by viewModel.lastVisitedCategory.collectAsStateWithLifecycle()
    val currentProgram by viewModel.currentProgram.collectAsStateWithLifecycle()
    val nextProgram by viewModel.nextProgram.collectAsStateWithLifecycle()
    val upcomingPrograms by viewModel.upcomingPrograms.collectAsStateWithLifecycle()

    AnimatedVisibility(
        visible = showChannelListOverlay,
        enter = slideInHorizontally(initialOffsetX = { if (isRtl) it else -it }),
        exit = slideOutHorizontally(targetOffsetX = { if (isRtl) it else -it }),
        modifier = Modifier
            .align(if (isRtl) Alignment.TopEnd else Alignment.TopStart)
            .fillMaxHeight()
            .width(sideOverlayWidth)
            .focusGroup()
    ) {
        ChannelListOverlay(
            channels = currentChannelList,
            recentChannels = recentChannels,
            currentChannelId = currentChannel?.id ?: internalChannelId,
            overlayFocusRequester = channelListFocusRequester,
            lastVisitedCategoryName = lastVisitedCategory?.name,
            onOpenLastGroup = viewModel::openLastVisitedCategory,
            onSelectChannel = viewModel::zapToChannel,
            onOpenCategories = viewModel::openCategoryListOverlay,
            onDismiss = viewModel::closeOverlays,
            onOverlayInteracted = viewModel::onLiveOverlayInteraction
        )
    }

    AnimatedVisibility(
        visible = showCategoryListOverlay,
        enter = slideInHorizontally(initialOffsetX = { if (isRtl) it else -it }),
        exit = slideOutHorizontally(targetOffsetX = { if (isRtl) it else -it }),
        modifier = Modifier
            .align(if (isRtl) Alignment.TopEnd else Alignment.TopStart)
            .fillMaxHeight()
            .width(sideOverlayWidth)
            .focusGroup()
    ) {
        CategoryListOverlay(
            categories = availableCategories,
            currentCategoryId = activeCategoryId,
            overlayFocusRequester = categoryListFocusRequester,
            isCategoryLocked = { category ->
                parentalControlLevel in 1..2 && (category.isAdult || category.isUserProtected)
            },
            onSelectCategory = viewModel::selectCategoryFromOverlay,
            onDismiss = viewModel::closeOverlays,
            onOverlayInteracted = viewModel::onLiveOverlayInteraction
        )
    }

    AnimatedVisibility(
        visible = showEpgOverlay,
        enter = slideInHorizontally(initialOffsetX = { if (isRtl) -it else it }),
        exit = slideOutHorizontally(targetOffsetX = { if (isRtl) -it else it }),
        modifier = Modifier
            .align(if (isRtl) Alignment.TopStart else Alignment.TopEnd)
            .fillMaxHeight()
            .width(epgOverlayWidth)
            .focusGroup()
    ) {
        EpgOverlay(
            currentChannel = currentChannel,
            displayChannelNumber = displayChannelNumber,
            currentProgram = currentProgram,
            nextProgram = nextProgram,
            upcomingPrograms = upcomingPrograms,
            onDismiss = viewModel::closeOverlays,
            onOpenArchiveBrowser = {
                onOpenModal(PlayerModal.ProgramHistory)
                viewModel.closeOverlays()
            },
            onOverlayInteracted = viewModel::onLiveOverlayInteraction
        )
    }

    AnimatedVisibility(
        visible = showChannelInfoOverlay,
        enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            .focusGroup()
    ) {
        val availableAudioTracks by viewModel.availableAudioTracks.collectAsStateWithLifecycle()
        val availableSubtitleTracks by viewModel.availableSubtitleTracks.collectAsStateWithLifecycle()
        val availableVideoQualities by viewModel.availableVideoQualities.collectAsStateWithLifecycle()
        val liveTranslationAvailable by viewModel.liveTranslationAvailable.collectAsStateWithLifecycle()
        val currentChannelRecording by viewModel.currentChannelRecording.collectAsStateWithLifecycle()
        val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
        val audioVideoSyncEnabled by viewModel.audioVideoSyncEnabled.collectAsStateWithLifecycle()
        val castConnectionState by viewModel.castConnectionState.collectAsStateWithLifecycle()
        val timeshiftUiState by viewModel.timeshiftUiState.collectAsStateWithLifecycle()

        ChannelInfoOverlay(
            currentChannel = currentChannel,
            displayChannelNumber = displayChannelNumber,
            currentProgram = currentProgram,
            nextProgram = nextProgram,
            focusRequester = channelInfoFocusRequester,
            lastVisitedCategoryName = lastVisitedCategory?.name,
            onDismiss = viewModel::closeChannelInfoOverlay,
            onOverlayInteracted = viewModel::onLiveOverlayInteraction,
            onOpenFullEpg = {
                viewModel.closeChannelInfoOverlay()
                viewModel.openEpgOverlay()
            },
            onOpenLastGroup = {
                viewModel.closeChannelInfoOverlay()
                viewModel.openLastVisitedCategory()
            },
            currentRecordingStatus = currentChannelRecording?.status,
            onStartRecording = { onRunRecordingAction(viewModel::startManualRecording) },
            onStopRecording = viewModel::stopCurrentRecording,
            onScheduleRecording = { onRunRecordingAction(viewModel::scheduleRecording) },
            onScheduleDailyRecording = { onRunRecordingAction(viewModel::scheduleDailyRecording) },
            onScheduleWeeklyRecording = { onRunRecordingAction(viewModel::scheduleWeeklyRecording) },
            onRestartProgram = viewModel::restartCurrentProgram,
            onOpenArchive = { onOpenModal(PlayerModal.ProgramHistory) },
            onToggleAspectRatio = viewModel::toggleAspectRatio,
            onToggleDiagnostics = viewModel::toggleDiagnostics,
            onTogglePlayPause = { if (isPlaying) viewModel.pause() else viewModel.play() },
            onSeekBackward = viewModel::seekBackward,
            onSeekForward = viewModel::seekForward,
            onSeekToLiveEdge = viewModel::seekToLiveEdge,
            isPlaying = isPlaying,
            currentAspectRatio = aspectRatioLabel,
            isDiagnosticsEnabled = showDiagnostics,
            onOpenSplitScreen = { onOpenModal(PlayerModal.Split) },
            subtitleTrackCount = availableSubtitleTracks.size,
            liveTranslationAvailable = liveTranslationAvailable,
            audioTrackCount = availableAudioTracks.size,
            videoQualityCount = availableVideoQualities.size,
            channelVariantCount = currentChannel?.variants?.size ?: 0,
            isMuted = isMuted,
            onToggleMute = viewModel::toggleMute,
            onOpenSubtitleTracks = { onOpenModal(PlayerModal.TrackSelection(TrackType.TEXT)) },
            onOpenAudioTracks = { onOpenModal(PlayerModal.TrackSelection(TrackType.AUDIO)) },
            onOpenVideoTracks = { onOpenModal(PlayerModal.TrackSelection(TrackType.VIDEO)) },
            onOpenVariants = { onOpenModal(PlayerModal.VariantSelection) },
            onOpenAudioVideoSync = { onOpenModal(PlayerModal.AudioVideoOffset) },
            audioVideoSyncEnabled = audioVideoSyncEnabled,
            onEnterPictureInPicture = onEnterPictureInPicture,
            isCastConnected = castConnectionState == CastConnectionState.CONNECTED,
            onCast = { viewModel.castCurrentMedia(onOpenCastRouteChooser) },
            onStopCasting = viewModel::stopCasting,
            timeshiftUiState = timeshiftUiState,
            onTransientPanelVisibilityChanged = onTransientPanelVisibilityChanged,
            resolutionLabel = videoFormat.resolutionLabel.takeIf { it.isNotBlank() && !videoFormat.isEmpty }
        )
    }
}
