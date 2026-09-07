package com.streamvault.feature.playback.player

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.unit.dp
import androidx.compose.animation.*
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.*
import com.streamvault.core.ui.device.rememberIsTelevisionDevice
import com.streamvault.core.ui.theme.*
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.player.PlaybackState
import com.streamvault.player.PLAYER_TRACK_AUTO_ID
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import com.streamvault.player.PlayerRenderSurfaceType
import com.streamvault.player.PlayerSurfaceResizeMode
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.res.stringResource
import com.streamvault.feature.playback.R
import com.streamvault.feature.playback.cast.CastConnectionState
import com.streamvault.feature.playback.api.PlaybackPictureInPictureState
import com.streamvault.feature.playback.api.PlaybackPlatformHost
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.core.ui.platform.rememberNotificationPermissionGate
import com.streamvault.feature.playback.player.overlay.ChannelInfoOverlay
import com.streamvault.feature.playback.player.overlay.CategoryListOverlay
import com.streamvault.feature.playback.player.overlay.ChannelListOverlay
import com.streamvault.feature.playback.player.overlay.DiagnosticsOverlay
import com.streamvault.feature.playback.player.overlay.EpgOverlay
import com.streamvault.feature.playback.player.overlay.PlayerErrorOverlay
import com.streamvault.feature.playback.player.overlay.PlayerNoticeBanner
import com.streamvault.feature.playback.player.overlay.PlayerResumePrompt
import com.streamvault.feature.playback.player.overlay.PlayerAspectRatioToast
import com.streamvault.feature.playback.player.overlay.PlayerNumericInputOverlay
import com.streamvault.feature.playback.player.overlay.PlayerResolutionBadge
import com.streamvault.feature.playback.player.overlay.PlayerSleepTimerWarningOverlay
import com.streamvault.feature.playback.player.overlay.NextEpisodeCountdownOverlay
import com.streamvault.core.navigation.AppDestination



@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun PlayerScreen(
    streamUrl: String,
    title: String,
    artworkUrl: String? = null,
    epgChannelId: String? = null,
    internalChannelId: Long = -1L,
    categoryId: Long? = null,
    providerId: Long? = null,
    isVirtual: Boolean = false,
    combinedProfileId: Long? = null,
    combinedSourceFilterProviderId: Long? = null,
    contentType: String = "LIVE",
    archiveStartMs: Long? = null,
    archiveEndMs: Long? = null,
    archiveTitle: String? = null,
    seriesId: Long? = null,
    seasonNumber: Int? = null,
    episodeNumber: Int? = null,
    episodeId: Long? = null,
    returnDestination: AppDestination? = null,
    onBack: () -> Unit,
    onNavigate: ((AppDestination) -> Unit)? = null,
    playbackPlatformHost: PlaybackPlatformHost? = null,
    splitScreenPlanner: @Composable (Channel, () -> Unit, () -> Unit) -> Unit = { _, _, _ -> },
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val sideOverlayWidth = if (screenWidth < 700.dp) {
        (screenWidth * 0.62f).coerceIn(220.dp, 300.dp)
    } else if (!isTelevisionDevice && screenWidth < 1280.dp) {
        (screenWidth * 0.4f).coerceIn(320.dp, 420.dp)
    } else {
        350.dp
    }
    val epgOverlayWidth = if (screenWidth < 700.dp) {
        (screenWidth * 0.68f).coerceIn(240.dp, 320.dp)
    } else if (!isTelevisionDevice && screenWidth < 1280.dp) {
        (screenWidth * 0.46f).coerceIn(360.dp, 500.dp)
    } else {
        400.dp
    }
    val notificationPermissionGate = rememberNotificationPermissionGate(
        onNotificationsBlocked = { message -> viewModel.showPlayerNotice(message = message) },
        reminderBlockedMessage = stringResource(R.string.notification_permission_reminder_required),
        recordingBlockedMessage = stringResource(R.string.notification_permission_recording_alert_required)
    )
    val isInPictureInPictureMode = playbackPlatformHost
        ?.pictureInPictureMode
        ?.collectAsState(initial = false)
        ?.value
        ?: false
    val playerEngine by viewModel.activePlayerEngine.collectAsStateWithLifecycle()
    val playbackState by playerEngine.playbackState.collectAsStateWithLifecycle()
    val isPlaying by playerEngine.isPlaying.collectAsStateWithLifecycle()
    val renderSurfaceType by playerEngine.renderSurfaceType.collectAsStateWithLifecycle()
    val showControls by viewModel.showControls.collectAsStateWithLifecycle()
    val videoFormat by viewModel.videoFormat.collectAsStateWithLifecycle()
    val playerError by viewModel.playerError.collectAsStateWithLifecycle()
    val playbackResolutionUiState by viewModel.playbackResolutionUiState.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val autoPlayCountdown by viewModel.autoPlayCountdown.collectAsStateWithLifecycle()
    val resumePrompt by viewModel.resumePrompt.collectAsStateWithLifecycle()
    
    val isCatchUpPlayback by viewModel.isCatchUpPlayback.collectAsStateWithLifecycle()
    val showChannelListOverlay by viewModel.showChannelListOverlay.collectAsStateWithLifecycle()
    val showCategoryListOverlay by viewModel.showCategoryListOverlay.collectAsStateWithLifecycle()
    val showEpgOverlay by viewModel.showEpgOverlay.collectAsStateWithLifecycle()
    val displayChannelNumber by viewModel.displayChannelNumber.collectAsStateWithLifecycle()
    val showChannelInfoOverlay by viewModel.showChannelInfoOverlay.collectAsStateWithLifecycle()
    val aspectRatio by viewModel.aspectRatio.collectAsStateWithLifecycle()
    val showDiagnostics by viewModel.showDiagnostics.collectAsStateWithLifecycle()
    val playerNotice by viewModel.playerNotice.collectAsStateWithLifecycle()
    val preventStandbyDuringPlayback by viewModel.preventStandbyDuringPlayback.collectAsStateWithLifecycle()
    val sleepTimerExitEvent by viewModel.sleepTimerExitEvent.collectAsStateWithLifecycle()

    var modalState by remember { mutableStateOf(PlayerModalState()) }
    var channelInfoSubPanelOpen by remember { mutableStateOf(false) }
    
    val focusRequester = remember { FocusRequester() }
    val channelListFocusRequester = remember { FocusRequester() }
    val categoryListFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }
    val quickActionsFocusRequester = remember { FocusRequester() }
    val channelInfoFocusRequester = remember { FocusRequester() }
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val enterPictureInPicture = remember(playbackPlatformHost) {
        {
            playbackPlatformHost?.enterPictureInPicture()
            Unit
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(playbackPlatformHost, streamUrl, playbackState, isPlaying, videoFormat.width, videoFormat.height, videoFormat.pixelWidthHeightRatio) {
        playbackPlatformHost?.updatePictureInPictureState(
            PlaybackPictureInPictureState(
                enabled = streamUrl.isNotBlank()
                    && playbackState != PlaybackState.ERROR
                    && (isPlaying || playbackState == PlaybackState.READY || playbackState == PlaybackState.BUFFERING),
                isPlaying = isPlaying,
                videoWidth = videoFormat.width,
                videoHeight = videoFormat.height,
                pixelWidthHeightRatio = videoFormat.pixelWidthHeightRatio
            )
        )
    }

    LaunchedEffect(sleepTimerExitEvent) {
        if (sleepTimerExitEvent > 0) {
            viewModel.consumeSleepTimerExitEvent()
            onBack()
        }
    }

    PlayerLifecycleHost(
        playbackPlatformHost = playbackPlatformHost,
        playbackState = playbackState,
        isPlaying = isPlaying,
        isInPictureInPictureMode = isInPictureInPictureMode,
        showControls = showControls,
        preventStandbyDuringPlayback = preventStandbyDuringPlayback,
        viewModel = viewModel
    )

    // Consolidated focus management for all overlays
    val liveOverlayVisible = contentType == "LIVE" && (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay)
    val nextEpisodeCountdownVisible = !isInPictureInPictureMode && autoPlayCountdown != null
    val anyOverlayVisible = liveOverlayVisible || nextEpisodeCountdownVisible || modalState.hasVisibleModal || showDiagnostics

    LaunchedEffect(contentType, showCategoryListOverlay, showChannelListOverlay, showEpgOverlay, showChannelInfoOverlay) {
        if (contentType == "LIVE" && (showCategoryListOverlay || showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay)) {
            // Give overlays a moment to animate in before requesting focus
            delay(150)
            when {
                showCategoryListOverlay -> categoryListFocusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Category list overlay")
                showChannelListOverlay -> channelListFocusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Channel list overlay")
                showChannelInfoOverlay -> channelInfoFocusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Channel info overlay")
            }
        }
    }

    LaunchedEffect(anyOverlayVisible) {
        if (!anyOverlayVisible) {
            // Restore focus to main player when all overlays are gone
            focusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Player root")
        }
    }

    LaunchedEffect(
        playbackState,
        videoFormat.width,
        videoFormat.height,
        videoFormat.bitrate,
        videoFormat.frameRate,
        currentChannel?.selectedVariantId
    ) {
        viewModel.recordLiveVariantObservation(playbackState, videoFormat)
    }

    PlayerTopLevelModalHost(
        viewModel = viewModel,
        isInPictureInPictureMode = isInPictureInPictureMode,
        showProgramHistory = modalState.showProgramHistory,
        onDismissProgramHistory = { modalState = modalState.dismiss() },
        onSelectProgramHistory = { program ->
            viewModel.playCatchUp(program)
            modalState = modalState.dismiss()
        },
        showSplitDialog = modalState.showSplitDialog,
        currentChannel = currentChannel,
        onDismissSplitDialog = { modalState = modalState.dismiss() },
        splitScreenPlanner = splitScreenPlanner,
        onLaunchMultiView = {
            modalState = modalState.dismiss()
            viewModel.handOffPlaybackToMultiView()
            onNavigate?.invoke(AppDestination.MultiView)
        }
    )

    val prepareIdentity = buildPlayerPrepareIdentity(
        streamUrl = streamUrl,
        epgChannelId = epgChannelId,
        internalChannelId = internalChannelId,
        categoryId = categoryId,
        providerId = providerId,
        isVirtual = isVirtual,
        combinedProfileId = combinedProfileId,
        combinedSourceFilterProviderId = combinedSourceFilterProviderId,
        contentType = contentType,
        archiveStartMs = archiveStartMs,
        archiveEndMs = archiveEndMs
    )

    LaunchedEffect(prepareIdentity) {
        viewModel.prepare(
            streamUrl = streamUrl,
            epgChannelId = epgChannelId,
            internalChannelId = internalChannelId,
            categoryId = categoryId ?: -1,
            providerId = providerId ?: -1,
            isVirtual = isVirtual,
            combinedProfileId = combinedProfileId,
            combinedSourceFilterProviderId = combinedSourceFilterProviderId,
            contentType = contentType,
            title = title,
            artworkUrl = artworkUrl,
            archiveStartMs = archiveStartMs,
            archiveEndMs = archiveEndMs,
            archiveTitle = archiveTitle,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeId = episodeId
        )
    }

    LaunchedEffect(title, artworkUrl, archiveTitle, seriesId, seasonNumber, episodeNumber, prepareIdentity) {
        viewModel.updatePreparedRouteMetadata(
            title = title,
            artworkUrl = artworkUrl,
            contentType = contentType,
            providerId = providerId ?: -1L,
            internalChannelId = internalChannelId,
            archiveStartMs = archiveStartMs,
            archiveEndMs = archiveEndMs,
            archiveTitle = archiveTitle,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber
        )
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            delay(100)
            if (contentType == "LIVE") {
                quickActionsFocusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Player quick actions")
            } else {
                playButtonFocusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Player transport")
            }
        } else {
            viewModel.cancelControlsAutoHide()
            focusRequester.requestFocusSafely(tag = "PlayerScreen", target = "Player root")
        }
    }

    LaunchedEffect(showControls, modalState) {
        if (!showControls) {
            viewModel.cancelControlsAutoHide()
        } else if (modalState.hasVisibleModal) {
            viewModel.cancelControlsAutoHide()
        } else {
            viewModel.hideControlsAfterDelay()
        }
    }

    val handlePlayerNoticeAction: (PlayerNoticeAction) -> Unit = remember(returnDestination, onNavigate) {
        { action ->
            if (action == PlayerNoticeAction.OPEN_GUIDE && returnDestination != null && onNavigate != null) {
                viewModel.dismissPlayerNotice()
                onNavigate(returnDestination)
            } else {
                viewModel.runPlayerNoticeAction(action)
            }
        }
    }

    val handleBackPress: () -> Unit = {
            when (playerBackActionAtEvent {
                PlayerBackNavigationState(
                    hasPendingNumericChannelInput = viewModel.hasPendingNumericChannelInput(),
                    hasAutoPlayCountdown = autoPlayCountdown != null,
                    hasPlayerNotice = playerNotice != null,
                    showProgramHistory = modalState.showProgramHistory,
                    showSplitDialog = modalState.showSplitDialog,
                    showEpisodePicker = modalState.showEpisodePicker,
                    showSpeedSelection = modalState.showSpeedSelection,
                    showAudioVideoOffsetDialog = modalState.showAudioVideoOffsetDialog,
                    showStopPlaybackTimerDialog = modalState.showStopPlaybackTimerDialog,
                    showIdleStandbyTimerDialog = modalState.showIdleStandbyTimerDialog,
                    hasTrackSelection = modalState.trackSelection != null,
                    showVariantSelection = modalState.showVariantSelection,
                    showDiagnostics = showDiagnostics,
                    showChannelInfoOverlay = showChannelInfoOverlay,
                    showChannelListOverlay = showChannelListOverlay,
                    showCategoryListOverlay = showCategoryListOverlay,
                    showEpgOverlay = showEpgOverlay,
                    showControls = showControls
                )
            }) {
                PlayerBackAction.CLEAR_NUMERIC_CHANNEL_INPUT -> viewModel.clearNumericChannelInput()
                PlayerBackAction.CANCEL_AUTO_PLAY -> viewModel.cancelAutoPlay()
                PlayerBackAction.DISMISS_PLAYER_NOTICE -> viewModel.dismissPlayerNotice()
                PlayerBackAction.CLOSE_PROGRAM_HISTORY -> modalState = modalState.dismiss()
                PlayerBackAction.CLOSE_SPLIT_DIALOG -> modalState = modalState.dismiss()
                PlayerBackAction.CLOSE_EPISODE_PICKER -> modalState = modalState.dismiss()
                PlayerBackAction.CLOSE_SPEED_SELECTION -> modalState = modalState.dismiss()
                PlayerBackAction.CLOSE_AUDIO_VIDEO_OFFSET_DIALOG -> {
                    modalState = modalState.dismiss()
                    viewModel.dismissAudioVideoOffsetPreview()
                }
                PlayerBackAction.CLOSE_STOP_PLAYBACK_TIMER -> modalState = modalState.dismiss()
                PlayerBackAction.CLOSE_IDLE_STANDBY_TIMER -> modalState = modalState.dismiss()
                PlayerBackAction.CLOSE_VARIANT_SELECTION -> modalState = modalState.dismiss()
                PlayerBackAction.CLOSE_TRACK_SELECTION -> modalState = modalState.dismiss()
                PlayerBackAction.TOGGLE_DIAGNOSTICS -> viewModel.toggleDiagnostics()
                PlayerBackAction.CLOSE_CHANNEL_INFO -> viewModel.closeChannelInfoOverlay()
                PlayerBackAction.CLOSE_LIVE_OVERLAYS -> viewModel.closeOverlays()
                PlayerBackAction.TOGGLE_CONTROLS -> viewModel.toggleControls()
                PlayerBackAction.NAVIGATE_BACK -> onBack()
            }
    }

    BackHandler(enabled = !resumePrompt.show) {
        handleBackPress()
    }

    val playerInputState = {
        PlayerInputState(
            contentType = contentType,
            isCatchUpPlayback = isCatchUpPlayback,
            isRtl = isRtl,
            nextEpisodeCountdownVisible = nextEpisodeCountdownVisible,
            showChannelListOverlay = showChannelListOverlay,
            showCategoryListOverlay = showCategoryListOverlay,
            showEpgOverlay = showEpgOverlay,
            showChannelInfoOverlay = showChannelInfoOverlay,
            channelInfoSubPanelOpen = channelInfoSubPanelOpen,
            showDiagnostics = showDiagnostics,
            showTrackSelection = modalState.trackSelection != null,
            showVariantSelection = modalState.showVariantSelection,
            showSpeedSelection = modalState.showSpeedSelection,
            showAudioVideoOffsetDialog = modalState.showAudioVideoOffsetDialog,
            showStopPlaybackTimerDialog = modalState.showStopPlaybackTimerDialog,
            showIdleStandbyTimerDialog = modalState.showIdleStandbyTimerDialog,
            showProgramHistory = modalState.showProgramHistory,
            showSplitDialog = modalState.showSplitDialog,
            showEpisodePicker = modalState.showEpisodePicker,
            showControls = showControls,
            hasPendingNumericChannelInput = viewModel.hasPendingNumericChannelInput(),
            canOpenEpisodePicker = contentType == "SERIES_EPISODE" &&
                viewModel.currentSeries.value?.seasons.sanitizedForPlayer()
                    ?.any { it.episodes.isNotEmpty() } == true
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusProperties {
                // Only allow focus on the main background when no overlays are active
                canFocus = !anyOverlayVisible && !showControls
            }
            .focusable()
            .pointerInput(contentType, anyOverlayVisible, showControls) {
                detectTapGestures {
                    viewModel.notifyUserActivity()
                    when {
                        anyOverlayVisible -> return@detectTapGestures
                        showControls -> viewModel.toggleControls()
                        contentType == "LIVE" && !isCatchUpPlayback -> viewModel.openChannelInfoOverlay()
                        else -> viewModel.toggleControls()
                    }
                }
            }
            // --- Key handler ownership ---
            // onPreviewKeyEvent (top-down): DPAD_UP, DPAD_DOWN, CHANNEL_UP, CHANNEL_DOWN
            //   for live-TV channel zapping when no overlay/dialog is open. Fires BEFORE
            //   child composables see the event, so overlays that consume DPAD_UP/DOWN
            //   internally get priority (early returns above).
            // onKeyEvent (bottom-up): all other keys ג€” DPAD_CENTER, BACK, MEDIA_*,
            //   numeric digits, MUTE, GUIDE, INFO, MENU, and the CHANNEL_UP/DOWN
            //   fallback for non-LIVE content types or when channelInfoSubPanelOpen.
            // CHANNEL_UP/DOWN appear in BOTH handlers. onPreviewKeyEvent intercepts them
            // first for live content with no sub-panel; onKeyEvent handles the remaining
            // cases (non-LIVE content, sub-panel open). This is intentional ג€” the preview
            // handler returns false for those remaining cases, letting onKeyEvent run.
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                viewModel.notifyUserActivity()
                val decision = playerPreviewInputDecision(
                    state = playerInputState(),
                    key = playerInputKey(event.nativeKeyEvent)
                )
                if (decision.notifyLiveOverlayInteraction) {
                    viewModel.onLiveOverlayInteraction()
                }
                when (decision.action) {
                    PlayerInputAction.PlayNext -> {
                        viewModel.playNext()
                        true
                    }
                    PlayerInputAction.PlayPrevious -> {
                        viewModel.playPrevious()
                        true
                    }
                    else -> false
                }
            }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onKeyEvent false
                }
                viewModel.notifyUserActivity()
                val decision = playerInputDecisionAtEvent(
                    stateProvider = playerInputState,
                    key = playerInputKey(event.nativeKeyEvent)
                )
                if (decision.notifyLiveOverlayInteraction) {
                    viewModel.onLiveOverlayInteraction()
                }
                when (val action = decision.action) {
                    PlayerInputAction.Pass -> false
                    PlayerInputAction.Consume -> true
                    PlayerInputAction.CancelAutoPlay -> {
                        viewModel.cancelAutoPlay()
                        true
                    }
                    PlayerInputAction.DismissAudioVideoOffset -> {
                        modalState = modalState.dismiss()
                        viewModel.dismissAudioVideoOffsetPreview()
                        true
                    }
                    PlayerInputAction.CloseSpeedSelection -> {
                        modalState = modalState.dismiss()
                        true
                    }
                    PlayerInputAction.CloseVariantSelection -> {
                        modalState = modalState.dismiss()
                        true
                    }
                    PlayerInputAction.CloseStopIdleTimersAndTrackSelection -> {
                        modalState = modalState.dismiss()
                        true
                    }
                    PlayerInputAction.CommitNumericChannelInput -> {
                        viewModel.commitNumericChannelInput()
                        true
                    }
                    PlayerInputAction.OpenChannelInfo -> {
                        viewModel.openChannelInfoOverlay()
                        true
                    }
                    PlayerInputAction.CloseChannelInfo -> {
                        viewModel.closeChannelInfoOverlay()
                        true
                    }
                    PlayerInputAction.OpenChannelList -> {
                        viewModel.openChannelListOverlay()
                        true
                    }
                    PlayerInputAction.OpenCategoryList -> {
                        viewModel.openCategoryListOverlay()
                        true
                    }
                    PlayerInputAction.OpenEpg -> {
                        viewModel.openEpgOverlay()
                        true
                    }
                    PlayerInputAction.SeekBackward -> {
                        viewModel.seekBackward()
                        true
                    }
                    PlayerInputAction.SeekForward -> {
                        viewModel.seekForward()
                        true
                    }
                    PlayerInputAction.ToggleControls -> {
                        viewModel.toggleControls()
                        true
                    }
                    PlayerInputAction.TogglePlayback -> {
                        if (isPlaying) viewModel.pause() else viewModel.play()
                        true
                    }
                    PlayerInputAction.ToggleMute -> {
                        viewModel.toggleMute()
                        true
                    }
                    PlayerInputAction.PlayNext -> {
                        viewModel.playNext()
                        true
                    }
                    PlayerInputAction.PlayPrevious -> {
                        viewModel.playPrevious()
                        true
                    }
                    PlayerInputAction.ZapToLastChannel -> {
                        viewModel.zapToLastChannel()
                        true
                    }
                    PlayerInputAction.ShowEpisodePicker -> {
                        modalState = modalState.open(PlayerModal.EpisodePicker)
                        true
                    }
                    is PlayerInputAction.InputNumericDigit -> {
                        viewModel.inputNumericChannelDigit(action.digit)
                        true
                    }
                    PlayerInputAction.DelegateBack -> {
                        handleBackPress()
                        true
                    }
                }
            }
    ) {
        // ExoPlayer Video Surface
        PlayerVideoSurface(
            playerEngine = playerEngine,
            resizeMode = aspectRatio.toPlayerSurfaceResizeMode(),
            surfaceType = renderSurfaceType,
            modifier = Modifier.fillMaxSize()
        )

        // Buffering indicator
        if (playbackState == PlaybackState.BUFFERING) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 64.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        color = Primary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.player_buffering),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = playerNotice != null && !(playbackState == PlaybackState.BUFFERING && playerNotice?.isRetryNotice == false),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 116.dp)
        ) {
            PlayerNoticeBanner(
                notice = playerNotice,
                onDismiss = viewModel::dismissPlayerNotice,
                onAction = handlePlayerNoticeAction
            )
        }

        PlayerRecordingIndicatorHost(
            viewModel = viewModel,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 18.dp, top = 18.dp)
        )

        when (val resolutionState = playbackResolutionUiState) {
            PlaybackResolutionUiState.Resolving -> Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    CircularProgressIndicator(color = Primary)
                    Text("Resolving playback…", color = Color.White)
                }
            }
            is PlaybackResolutionUiState.Failure -> PlayerErrorOverlay(
                playerError = PlayerError.SourceError(resolutionState.message),
                contentType = contentType,
                hasAlternateStream = false,
                hasLastChannel = false,
                onAction = handlePlayerNoticeAction,
                onBack = onBack
            )
            PlaybackResolutionUiState.Idle -> Unit
        }

        // Engine error overlay
        if (playbackResolutionUiState == PlaybackResolutionUiState.Idle && playbackState == PlaybackState.ERROR) {
            PlayerErrorOverlay(
                playerError = playerError,
                contentType = contentType,
                hasAlternateStream = viewModel.hasAlternateStream(),
                hasLastChannel = viewModel.hasLastChannel(),
                onAction = handlePlayerNoticeAction,
                onBack = onBack
            )
        }

        PlayerControlsOverlayHost(
            playerEngine = playerEngine,
            viewModel = viewModel,
            visible = showControls,
            title = title,
            contentType = contentType,
            isCatchUpPlayback = isCatchUpPlayback,
            isPlaying = isPlaying,
            currentChannel = currentChannel,
            currentChannelName = currentChannel?.name,
            displayChannelNumber = displayChannelNumber,
            aspectRatioLabel = aspectRatio.modeName,
            playButtonFocusRequester = playButtonFocusRequester,
            quickActionsFocusRequester = quickActionsFocusRequester,
            modifier = Modifier.fillMaxSize(),
            onOpenArchive = { modalState = modalState.open(PlayerModal.ProgramHistory) },
            onOpenSubtitleTracks = { modalState = modalState.open(PlayerModal.TrackSelection(TrackType.TEXT)) },
            onOpenAudioTracks = { modalState = modalState.open(PlayerModal.TrackSelection(TrackType.AUDIO)) },
            onOpenVideoTracks = { modalState = modalState.open(PlayerModal.TrackSelection(TrackType.VIDEO)) },
            onOpenPlaybackSpeed = { modalState = modalState.open(PlayerModal.SpeedSelection) },
            onOpenStopPlaybackTimer = { modalState = modalState.open(PlayerModal.StopPlaybackTimer) },
            onOpenIdleStandbyTimer = { modalState = modalState.open(PlayerModal.IdleStandbyTimer) },
            onOpenAudioVideoSync = { modalState = modalState.open(PlayerModal.AudioVideoOffset) },
            onOpenEpisodes = { modalState = modalState.open(PlayerModal.EpisodePicker) },
            onOpenSplitScreen = { modalState = modalState.open(PlayerModal.Split) },
            onEnterPictureInPicture = enterPictureInPicture,
            onRunRecordingAction = notificationPermissionGate::runRecordingAction,
            onOpenCastRouteChooser = { playbackPlatformHost?.openCastRouteChooser() }
        )

        PlayerNumericInputOverlayHost(
            viewModel = viewModel,
            visible = contentType == "LIVE" && !showControls,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 40.dp)
        )

        PlayerAspectRatioToast(
            aspectRatioLabel = aspectRatio.modeName,
            controlsVisible = showControls,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 32.dp)
        )

        PlayerResolutionBadgeHost(
            viewModel = viewModel,
            streamUrl = streamUrl,
            videoFormat = videoFormat,
            controlsVisible = showControls,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(32.dp)
        )

        PlayerSleepTimerWarningHost(
            viewModel = viewModel,
            isInPictureInPictureMode = isInPictureInPictureMode,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 88.dp)
        )

        // Auto-Play Next Episode countdown overlay
        val countdownState = autoPlayCountdown
        if (!isInPictureInPictureMode && countdownState != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 32.dp, bottom = 32.dp)
            ) {
                NextEpisodeCountdownOverlay(
                    nextEpisode = countdownState.episode,
                    secondsRemaining = countdownState.secondsRemaining,
                    onPlayNow = { viewModel.playNextEpisodeNow() },
                    onCancel = { viewModel.cancelAutoPlay() }
                )
            }
        }

        // Resume Prompt Dialog
        if (!isInPictureInPictureMode && resumePrompt.show) {
            PlayerResumePrompt(
                title = resumePrompt.title,
                onStartOver = { viewModel.dismissResumePrompt(resume = false) },
                onResume = { viewModel.dismissResumePrompt(resume = true) }
            )
        }
        
        PlayerControlsModalHost(
            viewModel = viewModel,
            isInPictureInPictureMode = isInPictureInPictureMode,
            showTrackSelection = modalState.trackSelection,
            showVariantSelection = modalState.showVariantSelection,
            currentChannel = currentChannel,
            showSpeedSelection = modalState.showSpeedSelection,
            showStopPlaybackTimerDialog = modalState.showStopPlaybackTimerDialog,
            showIdleStandbyTimerDialog = modalState.showIdleStandbyTimerDialog,
            canSaveChannel = currentChannel != null,
            showAudioVideoOffsetDialog = modalState.showAudioVideoOffsetDialog,
            showEpisodePicker = modalState.showEpisodePicker,
            fallbackTitle = title,
            fallbackEpisodeId = internalChannelId,
            fallbackSeasonNumber = seasonNumber,
            onDismissModal = { modalState = modalState.dismiss() }
        )

        // --- Overlays ---
        PlayerDiagnosticsHost(
            viewModel = viewModel,
            visible = showDiagnostics,
            isInPictureInPictureMode = isInPictureInPictureMode,
            modifier = Modifier.align(Alignment.TopStart).padding(32.dp)
        )
        if (contentType == "LIVE") {
            PlayerLiveOverlayHost(
                viewModel = viewModel,
                isRtl = isRtl,
                sideOverlayWidth = sideOverlayWidth,
                epgOverlayWidth = epgOverlayWidth,
                showChannelListOverlay = showChannelListOverlay,
                showCategoryListOverlay = showCategoryListOverlay,
                showEpgOverlay = showEpgOverlay,
                showChannelInfoOverlay = showChannelInfoOverlay,
                currentChannel = currentChannel,
                internalChannelId = internalChannelId,
                displayChannelNumber = displayChannelNumber,
                channelListFocusRequester = channelListFocusRequester,
                categoryListFocusRequester = categoryListFocusRequester,
                channelInfoFocusRequester = channelInfoFocusRequester,
                isPlaying = isPlaying,
                aspectRatioLabel = aspectRatio.modeName,
                showDiagnostics = showDiagnostics,
                videoFormat = videoFormat,
                onOpenModal = { modal -> modalState = modalState.open(modal) },
                onEnterPictureInPicture = enterPictureInPicture,
                onRunRecordingAction = notificationPermissionGate::runRecordingAction,
                onOpenCastRouteChooser = { playbackPlatformHost?.openCastRouteChooser() },
                onTransientPanelVisibilityChanged = { channelInfoSubPanelOpen = it }
            )
        }
    }
}

private fun playerInputKey(event: KeyEvent): PlayerInputKey = when (event.keyCode) {
    KeyEvent.KEYCODE_DPAD_CENTER -> PlayerInputKey.DpadCenter
    KeyEvent.KEYCODE_ENTER -> PlayerInputKey.Enter
    KeyEvent.KEYCODE_NUMPAD_ENTER -> PlayerInputKey.NumpadEnter
    KeyEvent.KEYCODE_DPAD_LEFT -> PlayerInputKey.DpadLeft
    KeyEvent.KEYCODE_DPAD_RIGHT -> PlayerInputKey.DpadRight
    KeyEvent.KEYCODE_DPAD_UP -> PlayerInputKey.DpadUp
    KeyEvent.KEYCODE_DPAD_DOWN -> PlayerInputKey.DpadDown
    KeyEvent.KEYCODE_DPAD_UP_RIGHT -> PlayerInputKey.DpadUpRight
    KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> PlayerInputKey.DpadDownLeft
    KeyEvent.KEYCODE_BACK -> PlayerInputKey.Back
    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> PlayerInputKey.MediaPlayPause
    KeyEvent.KEYCODE_MUTE,
    KeyEvent.KEYCODE_VOLUME_MUTE -> PlayerInputKey.Mute(event.repeatCount > 0)
    KeyEvent.KEYCODE_CHANNEL_UP -> PlayerInputKey.ChannelUp
    KeyEvent.KEYCODE_CHANNEL_DOWN -> PlayerInputKey.ChannelDown
    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlayerInputKey.MediaPrevious
    KeyEvent.KEYCODE_GUIDE -> PlayerInputKey.Guide
    KeyEvent.KEYCODE_INFO -> PlayerInputKey.Info
    KeyEvent.KEYCODE_MENU -> PlayerInputKey.Menu
    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> PlayerInputKey.Digit(event.keyCode - KeyEvent.KEYCODE_0)
    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
        PlayerInputKey.Digit(event.keyCode - KeyEvent.KEYCODE_NUMPAD_0)
    else -> PlayerInputKey.Other
}

private fun AspectRatio.toPlayerSurfaceResizeMode(): PlayerSurfaceResizeMode = when (this) {
    AspectRatio.FIT -> PlayerSurfaceResizeMode.FIT
    AspectRatio.FILL -> PlayerSurfaceResizeMode.FILL
    AspectRatio.ZOOM -> PlayerSurfaceResizeMode.ZOOM
}
