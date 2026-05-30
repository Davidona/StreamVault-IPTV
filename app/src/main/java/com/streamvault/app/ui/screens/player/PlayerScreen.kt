package com.streamvault.app.ui.screens.player

import android.app.Activity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalContext
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
import androidx.media3.common.util.UnstableApi
import androidx.tv.material3.*
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.model.Program
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
import com.streamvault.app.ui.components.dialogs.ProgramHistoryDialog
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import com.streamvault.app.R
import com.streamvault.app.MainActivity
import com.streamvault.app.cast.CastConnectionState
import com.streamvault.app.ui.components.PlayerRenderView
import com.streamvault.app.ui.design.requestFocusSafely
import com.streamvault.app.ui.notifications.rememberNotificationPermissionGate
import com.streamvault.app.ui.screens.player.overlay.ChannelInfoOverlay
import com.streamvault.app.ui.screens.player.overlay.ChannelVariantSelectionDialog
import com.streamvault.app.ui.screens.player.overlay.CategoryListOverlay
import com.streamvault.app.ui.screens.player.overlay.ChannelListOverlay
import com.streamvault.app.ui.screens.player.overlay.DiagnosticsOverlay
import com.streamvault.app.ui.screens.player.overlay.EpgOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerErrorOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerNoticeBanner
import com.streamvault.app.ui.screens.player.overlay.PlayerEpisodeSelectionDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerResumePrompt
import com.streamvault.app.ui.screens.player.overlay.PlayerTrackSelectionDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerAspectRatioToast
import com.streamvault.app.ui.screens.player.overlay.PlayerControlsOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerNumericInputOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerResolutionBadge
import com.streamvault.app.ui.screens.player.overlay.PlayerAudioVideoOffsetDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerSpeedSelectionDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerSleepTimerDialog
import com.streamvault.app.ui.screens.player.overlay.PlayerSleepTimerWarningOverlay
import com.streamvault.app.ui.screens.player.overlay.NextEpisodeCountdownOverlay
import com.streamvault.app.ui.screens.multiview.MultiViewViewModel
import com.streamvault.app.ui.screens.multiview.MultiViewPlannerDialog
import com.streamvault.app.navigation.Routes



@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
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
    returnRoute: String? = null,
    onBack: () -> Unit,
    onNavigate: ((String) -> Unit)? = null,
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
    val mainActivity = LocalContext.current.findMainActivity()
    val notificationPermissionGate = rememberNotificationPermissionGate(
        onNotificationsBlocked = { message -> viewModel.showPlayerNotice(message = message) },
        reminderBlockedMessage = stringResource(R.string.notification_permission_reminder_required),
        recordingBlockedMessage = stringResource(R.string.notification_permission_recording_alert_required)
    )
    val isInPictureInPictureMode = mainActivity
        ?.pictureInPictureModeFlow
        ?.collectAsState(initial = mainActivity.isInPictureInPictureMode)
        ?.value
        ?: false
    val playerEngine by viewModel.activePlayerEngine.collectAsStateWithLifecycle()
    val playbackState by playerEngine.playbackState.collectAsStateWithLifecycle()
    val isPlaying by playerEngine.isPlaying.collectAsStateWithLifecycle()
    val renderSurfaceType by playerEngine.renderSurfaceType.collectAsStateWithLifecycle()
    val showControls by viewModel.showControls.collectAsStateWithLifecycle()
    val videoFormat by viewModel.videoFormat.collectAsStateWithLifecycle()
    val playerError by viewModel.playerError.collectAsStateWithLifecycle()
    val currentProgram by viewModel.currentProgram.collectAsStateWithLifecycle()
    val nextProgram by viewModel.nextProgram.collectAsStateWithLifecycle()
    val programHistory by viewModel.programHistory.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val currentSeries by viewModel.currentSeries.collectAsStateWithLifecycle()
    val currentEpisode by viewModel.currentEpisode.collectAsStateWithLifecycle()
    val autoPlayCountdown by viewModel.autoPlayCountdown.collectAsStateWithLifecycle()
    val playbackTitle by viewModel.playbackTitle.collectAsStateWithLifecycle()
    val resumePrompt by viewModel.resumePrompt.collectAsStateWithLifecycle()
    val currentSeriesSeasons = remember(currentSeries) {
        currentSeries?.seasons.sanitizedForPlayer()
    }
    val canOpenEpisodePicker = contentType == "SERIES_EPISODE" &&
        currentSeriesSeasons?.any { it.episodes.isNotEmpty() } == true
    
    val isCatchUpPlayback by viewModel.isCatchUpPlayback.collectAsStateWithLifecycle()
    val showChannelListOverlay by viewModel.showChannelListOverlay.collectAsStateWithLifecycle()
    val showCategoryListOverlay by viewModel.showCategoryListOverlay.collectAsStateWithLifecycle()
    val availableCategories by viewModel.availableCategories.collectAsStateWithLifecycle()
    val parentalControlLevel by viewModel.parentalControlLevel.collectAsStateWithLifecycle()
    val activeCategoryId by viewModel.activeCategoryId.collectAsStateWithLifecycle()
    val showEpgOverlay by viewModel.showEpgOverlay.collectAsStateWithLifecycle()
    val currentChannelList by viewModel.currentChannelList.collectAsStateWithLifecycle()
    val recentChannels by viewModel.recentChannels.collectAsStateWithLifecycle()
    val lastVisitedCategory by viewModel.lastVisitedCategory.collectAsStateWithLifecycle()
    val displayChannelNumber by viewModel.displayChannelNumber.collectAsStateWithLifecycle()
    val upcomingPrograms by viewModel.upcomingPrograms.collectAsStateWithLifecycle()
    val showChannelInfoOverlay by viewModel.showChannelInfoOverlay.collectAsStateWithLifecycle()
    val numericChannelInput by viewModel.numericChannelInput.collectAsStateWithLifecycle()
    
    val availableAudioTracks by viewModel.availableAudioTracks.collectAsStateWithLifecycle()
    val availableSubtitleTracks by viewModel.availableSubtitleTracks.collectAsStateWithLifecycle()
    val availableVideoQualities by viewModel.availableVideoQualities.collectAsStateWithLifecycle()
    val aspectRatio by viewModel.aspectRatio.collectAsStateWithLifecycle()
    val showDiagnostics by viewModel.showDiagnostics.collectAsStateWithLifecycle()
    val playerDiagnostics by viewModel.playerDiagnostics.collectAsStateWithLifecycle()
    val playerNotice by viewModel.playerNotice.collectAsStateWithLifecycle()
    val currentChannelRecording by viewModel.currentChannelRecording.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val mediaTitle by viewModel.mediaTitle.collectAsStateWithLifecycle()
    val playbackSpeed by viewModel.playbackSpeed.collectAsStateWithLifecycle()
    val audioVideoSyncEnabled by viewModel.audioVideoSyncEnabled.collectAsStateWithLifecycle()
    val audioVideoOffsetState by viewModel.audioVideoOffsetUiState.collectAsStateWithLifecycle()
    val castConnectionState by viewModel.castConnectionState.collectAsStateWithLifecycle()
    val seekPreview by viewModel.seekPreview.collectAsStateWithLifecycle()
    val preventStandbyDuringPlayback by viewModel.preventStandbyDuringPlayback.collectAsStateWithLifecycle()
    val timeshiftUiState by viewModel.timeshiftUiState.collectAsStateWithLifecycle()
    val sleepTimerUiState by viewModel.sleepTimerUiState.collectAsStateWithLifecycle()
    val sleepTimerExitEvent by viewModel.sleepTimerExitEvent.collectAsStateWithLifecycle()

    var showTrackSelection by remember { mutableStateOf<TrackType?>(null) }
    var showVariantSelection by remember { mutableStateOf(false) }
    var showStreamFormatSelection by remember { mutableStateOf(false) }
    var showSpeedSelection by remember { mutableStateOf(false) }
    var showAudioVideoOffsetDialog by remember { mutableStateOf(false) }
    var showStopPlaybackTimerDialog by remember { mutableStateOf(false) }
    var showIdleStandbyTimerDialog by remember { mutableStateOf(false) }
    var showProgramHistory by remember { mutableStateOf(false) }
    var showSplitDialog by remember { mutableStateOf(false) }
    var showEpisodePicker by remember { mutableStateOf(false) }
    var channelInfoSubPanelOpen by remember { mutableStateOf(false) }
    
    val focusRequester = remember { FocusRequester() }
    val channelListFocusRequester = remember { FocusRequester() }
    val categoryListFocusRequester = remember { FocusRequester() }
    val playButtonFocusRequester = remember { FocusRequester() }
    val quickActionsFocusRequester = remember { FocusRequester() }
    val channelInfoFocusRequester = remember { FocusRequester() }
    val layoutDirection = LocalLayoutDirection.current
    val isRtl = layoutDirection == LayoutDirection.Rtl
    val currentPictureInPictureMode by rememberUpdatedState(isInPictureInPictureMode)
    val enterPictureInPicture = remember(mainActivity) {
        {
            mainActivity?.enterPlayerPictureInPictureModeFromPlayer()
            Unit
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(mainActivity, streamUrl, playbackState, isPlaying, videoFormat.width, videoFormat.height, videoFormat.pixelWidthHeightRatio) {
        mainActivity?.updatePlayerPictureInPictureState(
            enabled = streamUrl.isNotBlank()
                && playbackState != PlaybackState.ERROR
                && (isPlaying || playbackState == PlaybackState.READY || playbackState == PlaybackState.BUFFERING),
            isPlaying = isPlaying,
            videoWidth = videoFormat.width,
            videoHeight = videoFormat.height,
            pixelWidthHeightRatio = videoFormat.pixelWidthHeightRatio
        )
    }

    LaunchedEffect(sleepTimerExitEvent) {
        if (sleepTimerExitEvent > 0) {
            viewModel.consumeSleepTimerExitEvent()
            onBack()
        }
    }

    LaunchedEffect(audioVideoSyncEnabled) {
        if (!audioVideoSyncEnabled && showAudioVideoOffsetDialog) {
            showAudioVideoOffsetDialog = false
            viewModel.dismissAudioVideoOffsetPreview()
        }
    }

    LaunchedEffect(isInPictureInPictureMode) {
        if (isInPictureInPictureMode) {
            viewModel.closeOverlays()
            if (showControls) {
                viewModel.toggleControls()
            }
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.onAppForegrounded()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (currentPictureInPictureMode) {
            viewModel.closeOverlays()
        } else {
            viewModel.onAppBackgrounded()
        }
    }

    DisposableEffect(mainActivity) {
        onDispose {
            mainActivity?.clearPlayerPictureInPictureState()
            viewModel.onPlayerScreenDisposed()
        }
    }

    // Prevent screen from sleeping during active playback
    val playerWindow = mainActivity?.window
    DisposableEffect(Unit) {
        onDispose { playerWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(preventStandbyDuringPlayback, isPlaying, playbackState) {
        if (preventStandbyDuringPlayback) {
            // Keep screen always on while in player — prevents TV OS standby nag
            playerWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else if (isPlaying || playbackState == PlaybackState.BUFFERING) {
            playerWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            playerWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Consolidated focus management for all overlays
    val liveOverlayVisible = contentType == "LIVE" && (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay)
    val nextEpisodeCountdownVisible = !isInPictureInPictureMode && autoPlayCountdown != null
    val anyOverlayVisible = liveOverlayVisible || nextEpisodeCountdownVisible || showTrackSelection != null || showVariantSelection || showStreamFormatSelection || showSpeedSelection || showAudioVideoOffsetDialog || showStopPlaybackTimerDialog || showIdleStandbyTimerDialog || showProgramHistory || showSplitDialog || showEpisodePicker || showDiagnostics

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

    val resolutionBadgeLabel = buildResolutionBadgeLabel(
        videoFormat = videoFormat,
        videoTracks = availableVideoQualities,
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
        if (nextLabel == lastResolutionBadgeLabel) {
            return@LaunchedEffect
        }
        lastResolutionBadgeLabel = nextLabel
        showResolution = true
        delay(3000)
        if (lastResolutionBadgeLabel == nextLabel) {
            showResolution = false
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

    if (!isInPictureInPictureMode && showProgramHistory) {
        ProgramHistoryDialog(
            programs = programHistory,
            onDismiss = { showProgramHistory = false },
            onProgramSelect = { program ->
                viewModel.playCatchUp(program)
                showProgramHistory = false
            }
        )
    }

    // Split Screen Manager dialog
    if (showSplitDialog && currentChannel != null) {
        val multiViewViewModel: MultiViewViewModel = hiltViewModel()
        MultiViewPlannerDialog(
            pendingChannel = currentChannel,
            onDismiss = { showSplitDialog = false },
            onLaunch = {
                showSplitDialog = false
                viewModel.handOffPlaybackToMultiView()
                onNavigate?.invoke(Routes.MULTI_VIEW)
            },
            viewModel = multiViewViewModel
        )
    }

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

    LaunchedEffect(showControls, showTrackSelection, showVariantSelection, showStreamFormatSelection, showSpeedSelection, showAudioVideoOffsetDialog, showStopPlaybackTimerDialog, showIdleStandbyTimerDialog, showProgramHistory, showSplitDialog, showEpisodePicker) {
        if (!showControls) {
            viewModel.cancelControlsAutoHide()
        } else if (showTrackSelection != null || showVariantSelection || showStreamFormatSelection || showSpeedSelection || showAudioVideoOffsetDialog || showStopPlaybackTimerDialog || showIdleStandbyTimerDialog || showProgramHistory || showSplitDialog || showEpisodePicker) {
            viewModel.cancelControlsAutoHide()
        } else {
            viewModel.hideControlsAfterDelay()
        }
    }

    val handlePlayerNoticeAction: (PlayerNoticeAction) -> Unit = remember(returnRoute, onNavigate) {
        { action ->
            if (action == PlayerNoticeAction.OPEN_GUIDE && !returnRoute.isNullOrBlank() && onNavigate != null) {
                viewModel.dismissPlayerNotice()
                onNavigate(returnRoute)
            } else {
                viewModel.runPlayerNoticeAction(action)
            }
        }
    }

    val handleBackPress = remember(
        autoPlayCountdown,
        playerNotice,
        showProgramHistory,
        showSplitDialog,
        showEpisodePicker,
        showSpeedSelection,
        showAudioVideoOffsetDialog,
        showStopPlaybackTimerDialog,
        showIdleStandbyTimerDialog,
        showTrackSelection,
        showVariantSelection,
        showStreamFormatSelection,
        showDiagnostics,
        showChannelInfoOverlay,
        showChannelListOverlay,
        showCategoryListOverlay,
        showEpgOverlay,
        showControls,
        numericChannelInput
    ) {
        {
            when {
                viewModel.hasPendingNumericChannelInput() -> viewModel.clearNumericChannelInput()
                autoPlayCountdown != null -> viewModel.cancelAutoPlay()
                playerNotice != null -> viewModel.dismissPlayerNotice()
                showProgramHistory -> showProgramHistory = false
                showSplitDialog -> showSplitDialog = false
                showEpisodePicker -> showEpisodePicker = false
                showSpeedSelection -> showSpeedSelection = false
                showAudioVideoOffsetDialog -> {
                    showAudioVideoOffsetDialog = false
                    viewModel.dismissAudioVideoOffsetPreview()
                }
                showStopPlaybackTimerDialog -> showStopPlaybackTimerDialog = false
                showIdleStandbyTimerDialog -> showIdleStandbyTimerDialog = false
                showVariantSelection -> showVariantSelection = false
                showStreamFormatSelection -> showStreamFormatSelection = false
                showTrackSelection != null -> showTrackSelection = null
                showDiagnostics -> viewModel.toggleDiagnostics()
                showChannelInfoOverlay -> viewModel.closeChannelInfoOverlay()
                showChannelListOverlay || showCategoryListOverlay || showEpgOverlay -> viewModel.closeOverlays()
                showControls -> viewModel.toggleControls()
                else -> onBack()
            }
        }
    }

    BackHandler(enabled = !resumePrompt.show) {
        handleBackPress()
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
            // onKeyEvent (bottom-up): all other keys — DPAD_CENTER, BACK, MEDIA_*,
            //   numeric digits, MUTE, GUIDE, INFO, MENU, and the CHANNEL_UP/DOWN
            //   fallback for non-LIVE content types or when channelInfoSubPanelOpen.
            // CHANNEL_UP/DOWN appear in BOTH handlers. onPreviewKeyEvent intercepts them
            // first for live content with no sub-panel; onKeyEvent handles the remaining
            // cases (non-LIVE content, sub-panel open). This is intentional — the preview
            // handler returns false for those remaining cases, letting onKeyEvent run.
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                viewModel.notifyUserActivity()
                if (nextEpisodeCountdownVisible) {
                    return@onPreviewKeyEvent false
                }
                if (contentType != "LIVE") {
                    return@onPreviewKeyEvent false
                }
                if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showDiagnostics) {
                    return@onPreviewKeyEvent false
                }
                if (showTrackSelection != null || showVariantSelection || showStreamFormatSelection || showSpeedSelection || showAudioVideoOffsetDialog || showStopPlaybackTimerDialog || showIdleStandbyTimerDialog || showProgramHistory || showSplitDialog || showEpisodePicker) {
                    return@onPreviewKeyEvent false
                }
                if (showChannelInfoOverlay && channelInfoSubPanelOpen) {
                    return@onPreviewKeyEvent false
                }

                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP,
                    KeyEvent.KEYCODE_CHANNEL_UP,
                    KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                        if (showChannelInfoOverlay || showDiagnostics) {
                            viewModel.onLiveOverlayInteraction()
                        }
                        viewModel.playNext()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN,
                    KeyEvent.KEYCODE_CHANNEL_DOWN,
                    KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                        if (showChannelInfoOverlay || showDiagnostics) {
                            viewModel.onLiveOverlayInteraction()
                        }
                        viewModel.playPrevious()
                        true
                    }
                    else -> false
                }
            }
            .onKeyEvent { event ->
                // Only handle KeyDown to avoid double actions
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    viewModel.notifyUserActivity()
                    if (nextEpisodeCountdownVisible) {
                        return@onKeyEvent when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_BACK -> {
                                viewModel.cancelAutoPlay()
                                true
                            }
                            else -> true
                        }
                    }
                    if (showTrackSelection != null || showVariantSelection || showStreamFormatSelection || showSpeedSelection || showAudioVideoOffsetDialog || showStopPlaybackTimerDialog || showIdleStandbyTimerDialog) {
                        if (showAudioVideoOffsetDialog) {
                            return@onKeyEvent when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_BACK -> {
                                    showAudioVideoOffsetDialog = false
                                    viewModel.dismissAudioVideoOffsetPreview()
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP,
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_RIGHT,
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER -> false
                                else -> true
                            }
                        }
                        if (showSpeedSelection) {
                            return@onKeyEvent when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_BACK -> {
                                    showSpeedSelection = false
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP,
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_RIGHT,
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER -> false
                                else -> true
                            }
                        }
                        if (showVariantSelection) {
                            return@onKeyEvent when (event.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_BACK -> {
                                    showVariantSelection = false
                                    true
                                }
                                KeyEvent.KEYCODE_DPAD_UP,
                                KeyEvent.KEYCODE_DPAD_DOWN,
                                KeyEvent.KEYCODE_DPAD_LEFT,
                                KeyEvent.KEYCODE_DPAD_RIGHT,
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                KeyEvent.KEYCODE_ENTER,
                                KeyEvent.KEYCODE_NUMPAD_ENTER -> false
                                else -> true
                            }
                        }
                        return@onKeyEvent when (event.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_BACK -> {
                                showStopPlaybackTimerDialog = false
                                showIdleStandbyTimerDialog = false
                                showTrackSelection = null
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_UP,
                            KeyEvent.KEYCODE_DPAD_DOWN,
                            KeyEvent.KEYCODE_DPAD_LEFT,
                            KeyEvent.KEYCODE_DPAD_RIGHT,
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> false
                            else -> true
                        }
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            if (showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (contentType == "LIVE" && !isCatchUpPlayback && viewModel.hasPendingNumericChannelInput()) {
                                viewModel.commitNumericChannelInput()
                                   true
                            } else if (contentType == "LIVE" && !isCatchUpPlayback) {
                                    if (showChannelInfoOverlay) viewModel.closeChannelInfoOverlay()
                                    else viewModel.openChannelInfoOverlay()
                                   true
                            } else if (showControls) {
                                false
                            } else {
                                viewModel.toggleControls()
                                true
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (showControls && (contentType != "LIVE" || isCatchUpPlayback)) return@onKeyEvent false
                            if (showChannelListOverlay && contentType == "LIVE" && !isCatchUpPlayback) {
                                // Second left press while channel list is open → open category list
                                viewModel.openCategoryListOverlay()
                                true
                            } else if (contentType == "LIVE" && !isCatchUpPlayback && !showChannelListOverlay && !showCategoryListOverlay && !showEpgOverlay && !showChannelInfoOverlay) {
                                if (isRtl) viewModel.openEpgOverlay() else viewModel.openChannelListOverlay()
                                true
                            } else if (!showChannelListOverlay && !showCategoryListOverlay && !showEpgOverlay && !showChannelInfoOverlay) {
                                if (isRtl) viewModel.seekForward() else viewModel.seekBackward()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (showControls && (contentType != "LIVE" || isCatchUpPlayback)) return@onKeyEvent false
                            if (contentType == "LIVE" && !isCatchUpPlayback && !showChannelListOverlay && !showEpgOverlay && !showChannelInfoOverlay) {
                                if (isRtl) viewModel.openChannelListOverlay() else viewModel.openEpgOverlay()
                                true
                            } else if (!showChannelListOverlay && !showEpgOverlay && !showChannelInfoOverlay) {
                                if (isRtl) viewModel.seekBackward() else viewModel.seekForward()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (showChannelInfoOverlay && channelInfoSubPanelOpen) return@onKeyEvent false
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) return@onKeyEvent false
                            if (showControls && (contentType != "LIVE" || isCatchUpPlayback)) return@onKeyEvent false

                            if (contentType == "LIVE" && !isCatchUpPlayback) {
                                viewModel.playNext()
                            } else if (canOpenEpisodePicker) {
                                showEpisodePicker = true
                            } else {
                                viewModel.toggleControls()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (showChannelInfoOverlay && channelInfoSubPanelOpen) return@onKeyEvent false
                            if (showChannelListOverlay || showCategoryListOverlay || showEpgOverlay || showDiagnostics) return@onKeyEvent false
                            if (showControls && (contentType != "LIVE" || isCatchUpPlayback)) return@onKeyEvent false

                            if (contentType == "LIVE" && !isCatchUpPlayback) {
                                viewModel.playPrevious()
                            } else {
                                viewModel.toggleControls()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            handleBackPress()
                            true
                        }
                        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                            if (isPlaying) viewModel.pause() else viewModel.play()
                            true
                        }
                        KeyEvent.KEYCODE_MUTE, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                            if (event.nativeKeyEvent.repeatCount == 0) {
                                viewModel.toggleMute()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                            if (showDiagnostics) {
                                true
                            } else if (showChannelInfoOverlay && channelInfoSubPanelOpen) {
                                true
                            } else if (contentType == "LIVE") {
                                viewModel.playNext()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                            if (showDiagnostics) {
                                true
                            } else if (showChannelInfoOverlay && channelInfoSubPanelOpen) {
                                true
                            } else if (contentType == "LIVE") {
                                viewModel.playPrevious()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            if (showChannelInfoOverlay && channelInfoSubPanelOpen) {
                                true
                            } else
                            if (contentType == "LIVE") {
                                viewModel.zapToLastChannel()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_GUIDE -> {
                            if (contentType == "LIVE") {
                                viewModel.openEpgOverlay()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_INFO -> {
                            if (showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            if (contentType == "LIVE") {
                                if (showChannelInfoOverlay) viewModel.closeChannelInfoOverlay()
                                else viewModel.openChannelInfoOverlay()
                            } else {
                                viewModel.toggleControls()
                            }
                            true
                        }
                        KeyEvent.KEYCODE_MENU -> {
                            if (showChannelListOverlay || showEpgOverlay || showChannelInfoOverlay || showDiagnostics) {
                                viewModel.onLiveOverlayInteraction()
                            }
                            viewModel.toggleControls()
                            true
                        }
                        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9,
                        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> {
                            if (contentType == "LIVE") {
                                val keyCode = event.nativeKeyEvent.keyCode
                                val digit = when (keyCode) {
                                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
                                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
                                    else -> return@onKeyEvent false
                                }
                                viewModel.inputNumericChannelDigit(digit)
                                true
                            } else {
                                false
                            }
                        }
                        else -> false
                    }
                } else {
                    false
                }
            }
    ) {
        // ExoPlayer Video Surface
        PlayerRenderView(
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

        if (currentChannelRecording?.status == com.streamvault.domain.model.RecordingStatus.RECORDING) {
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
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 18.dp, top = 18.dp)
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
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Error overlay
        if (playbackState == PlaybackState.ERROR) {
            PlayerErrorOverlay(
                playerError = playerError,
                contentType = contentType,
                hasAlternateStream = viewModel.hasAlternateStream(),
                hasLastChannel = viewModel.hasLastChannel(),
                onAction = handlePlayerNoticeAction
            )
        }

        PlayerControlsOverlayHost(
            playerEngine = playerEngine,
            visible = showControls,
            title = playbackTitle.ifBlank { title },
            contentType = contentType,
            isCatchUpPlayback = isCatchUpPlayback,
            isPlaying = isPlaying,
            currentProgram = currentProgram,
            currentChannelName = currentChannel?.name,
            displayChannelNumber = displayChannelNumber,
            aspectRatioLabel = aspectRatio.modeName,
            subtitleTrackCount = availableSubtitleTracks.size,
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
            modifier = Modifier.fillMaxSize(),
            onClose = viewModel::toggleControls,
            onTogglePlayPause = { if (isPlaying) viewModel.pause() else viewModel.play() },
            onSeekBackward = viewModel::seekBackward,
            onSeekForward = viewModel::seekForward,
            onRestartProgram = viewModel::restartCurrentProgram,
            onOpenArchive = { showProgramHistory = true },
            onStartRecording = {
                notificationPermissionGate.runRecordingAction {
                    viewModel.startManualRecording()
                }
            },
            onStopRecording = viewModel::stopCurrentRecording,
            onScheduleRecording = {
                notificationPermissionGate.runRecordingAction {
                    viewModel.scheduleRecording()
                }
            },
            onScheduleDailyRecording = {
                notificationPermissionGate.runRecordingAction {
                    viewModel.scheduleDailyRecording()
                }
            },
            onScheduleWeeklyRecording = {
                notificationPermissionGate.runRecordingAction {
                    viewModel.scheduleWeeklyRecording()
                }
            },
            onToggleAspectRatio = viewModel::toggleAspectRatio,
            onOpenSubtitleTracks = { showTrackSelection = TrackType.TEXT },
            onOpenAudioTracks = { showTrackSelection = TrackType.AUDIO },
            onOpenVideoTracks = { showTrackSelection = TrackType.VIDEO },
            onOpenPlaybackSpeed = { showSpeedSelection = true },
            onOpenStopPlaybackTimer = { showStopPlaybackTimerDialog = true },
            onOpenIdleStandbyTimer = { showIdleStandbyTimerDialog = true },
            onOpenAudioVideoSync = { showAudioVideoOffsetDialog = true },
            audioVideoSyncEnabled = audioVideoSyncEnabled,
            showEpisodesAction = canOpenEpisodePicker,
            onOpenEpisodes = { showEpisodePicker = true },
            onOpenSplitScreen = { showSplitDialog = true },
            onEnterPictureInPicture = enterPictureInPicture,
            onToggleMute = viewModel::toggleMute,
            isCastConnected = castConnectionState == CastConnectionState.CONNECTED,
            onCast = { viewModel.castCurrentMedia { mainActivity?.openCastRouteChooser() } },
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

        PlayerNumericInputOverlay(
            state = numericChannelInput,
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

        PlayerResolutionBadge(
            visible = showResolution && !showControls && resolutionBadgeLabel != null,
            resolutionLabel = resolutionBadgeLabel.orEmpty(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(32.dp)
        )

        if (!isInPictureInPictureMode) {
            PlayerSleepTimerWarningOverlay(
                state = sleepTimerUiState,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 88.dp),
                onExtendStopTimer = { viewModel.extendStopPlaybackTimer() },
                onDisableStopTimer = viewModel::disableStopPlaybackTimer,
                onExtendIdleTimer = { viewModel.extendIdleStandbyTimer() },
                onDisableIdleTimer = viewModel::disableIdleStandbyTimer
            )
        }

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
        
        // Track Selection Dialog
        if (!isInPictureInPictureMode) {
            PlayerTrackSelectionDialog(
                trackType = showTrackSelection,
                audioTracks = availableAudioTracks,
                subtitleTracks = availableSubtitleTracks,
                videoTracks = availableVideoQualities,
                onDismiss = { showTrackSelection = null },
                onSelectAudio = viewModel::selectAudioTrack,
                onSelectVideo = viewModel::selectVideoQuality,
                onSelectSubtitle = viewModel::selectSubtitleTrack
            )
            ChannelVariantSelectionDialog(
                visible = showVariantSelection,
                channel = currentChannel,
                onDismiss = { showVariantSelection = false },
                onSelectVariant = viewModel::selectLiveVariant
            )
            StreamFormatSelectionDialog(
                visible = showStreamFormatSelection,
                channel = currentChannel,
                onDismiss = { showStreamFormatSelection = false },
                onSelectFormat = viewModel::selectStreamFormat
            )
            PlayerSpeedSelectionDialog(
                visible = showSpeedSelection,
                selectedSpeed = playbackSpeed,
                onDismiss = { showSpeedSelection = false },
                onSelectSpeed = viewModel::setPlaybackSpeed
            )
            PlayerSleepTimerDialog(
                visible = showStopPlaybackTimerDialog,
                title = stringResource(R.string.player_stop_playback_after),
                selectedMinutes = sleepTimerUiState.stopTimerMinutes,
                onDismiss = { showStopPlaybackTimerDialog = false },
                onSelectMinutes = { minutes ->
                    viewModel.notifyUserActivity()
                    viewModel.setStopPlaybackTimer(minutes)
                    showStopPlaybackTimerDialog = false
                }
            )
            PlayerSleepTimerDialog(
                visible = showIdleStandbyTimerDialog,
                title = stringResource(R.string.player_idle_standby_after),
                selectedMinutes = sleepTimerUiState.idleTimerMinutes,
                onDismiss = { showIdleStandbyTimerDialog = false },
                onSelectMinutes = { minutes ->
                    viewModel.notifyUserActivity()
                    viewModel.setIdleStandbyTimer(minutes)
                    showIdleStandbyTimerDialog = false
                }
            )
            PlayerAudioVideoOffsetDialog(
                visible = showAudioVideoOffsetDialog &&
                    audioVideoSyncEnabled &&
                    castConnectionState != CastConnectionState.CONNECTED,
                state = audioVideoOffsetState,
                canSaveChannel = currentChannel != null,
                onDismiss = {
                    showAudioVideoOffsetDialog = false
                    viewModel.dismissAudioVideoOffsetPreview()
                },
                onAdjust = viewModel::adjustAudioVideoOffset,
                onReset = viewModel::resetAudioVideoOffsetPreview,
                onSaveForChannel = viewModel::saveAudioVideoOffsetForChannel,
                onSaveAsGlobal = viewModel::saveAudioVideoOffsetAsGlobal,
                onUseGlobal = viewModel::useGlobalAudioVideoOffset
            )
            PlayerEpisodeSelectionDialog(
                visible = showEpisodePicker,
                seriesTitle = currentSeries?.name ?: playbackTitle.ifBlank { title },
                seasons = currentSeriesSeasons.orEmpty(),
                currentEpisodeId = currentEpisode?.id ?: internalChannelId,
                currentSeasonNumber = currentEpisode?.seasonNumber ?: seasonNumber,
                onDismiss = { showEpisodePicker = false },
                onSelectEpisode = { episode ->
                    showEpisodePicker = false
                    viewModel.playEpisode(episode)
                }
            )
        }

        // --- Overlays ---
        if (!isInPictureInPictureMode && showDiagnostics) {
            val playerStats by viewModel.playerStats.collectAsStateWithLifecycle()
            DiagnosticsOverlay(
                stats = playerStats,
                diagnostics = playerDiagnostics,
                modifier = Modifier.align(Alignment.TopStart).padding(32.dp)
            )
        }

        if (contentType == "LIVE") {
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
                    onOpenLastGroup = { viewModel.openLastVisitedCategory() },
                    onSelectChannel = { channelId -> viewModel.zapToChannel(channelId) },
                    onOpenCategories = { viewModel.openCategoryListOverlay() },
                    onDismiss = { viewModel.closeOverlays() },
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
                    onSelectCategory = { category ->
                        viewModel.selectCategoryFromOverlay(category)
                    },
                    onDismiss = { viewModel.closeOverlays() },
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
                    onDismiss = { viewModel.closeOverlays() },
                    onOpenArchiveBrowser = {
                        showProgramHistory = true
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
                ChannelInfoOverlay(
                    currentChannel = currentChannel,
                    displayChannelNumber = displayChannelNumber,
                    currentProgram = currentProgram,
                    nextProgram = nextProgram,
                    focusRequester = channelInfoFocusRequester,
                    lastVisitedCategoryName = lastVisitedCategory?.name,
                    onDismiss = { viewModel.closeChannelInfoOverlay() },
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
                    onStartRecording = {
                        notificationPermissionGate.runRecordingAction {
                            viewModel.startManualRecording()
                        }
                    },
                    onStopRecording = viewModel::stopCurrentRecording,
                    onScheduleRecording = {
                        notificationPermissionGate.runRecordingAction {
                            viewModel.scheduleRecording()
                        }
                    },
                    onScheduleDailyRecording = {
                        notificationPermissionGate.runRecordingAction {
                            viewModel.scheduleDailyRecording()
                        }
                    },
                    onScheduleWeeklyRecording = {
                        notificationPermissionGate.runRecordingAction {
                            viewModel.scheduleWeeklyRecording()
                        }
                    },
                    onRestartProgram = { viewModel.restartCurrentProgram() },
                    onOpenArchive = { showProgramHistory = true },
                    onToggleAspectRatio = { viewModel.toggleAspectRatio() },
                    onToggleDiagnostics = { viewModel.toggleDiagnostics() },
                    onTogglePlayPause = { if (isPlaying) viewModel.pause() else viewModel.play() },
                    onSeekBackward = viewModel::seekBackward,
                    onSeekForward = viewModel::seekForward,
                    onSeekToLiveEdge = viewModel::seekToLiveEdge,
                    isPlaying = isPlaying,
                    currentAspectRatio = aspectRatio.modeName,
                    isDiagnosticsEnabled = showDiagnostics,
                    onOpenSplitScreen = { showSplitDialog = true },
                    subtitleTrackCount = availableSubtitleTracks.size,
                    audioTrackCount = availableAudioTracks.size,
                    videoQualityCount = availableVideoQualities.size,
                    channelVariantCount = currentChannel?.variants?.size ?: 0,
                    qualityOptionCount = currentChannel?.qualityOptions?.size ?: 0,
                    isMuted = isMuted,
                    onToggleMute = viewModel::toggleMute,
                    onOpenSubtitleTracks = { showTrackSelection = TrackType.TEXT },
                    onOpenAudioTracks = { showTrackSelection = TrackType.AUDIO },
                    onOpenVideoTracks = { showTrackSelection = TrackType.VIDEO },
                    onOpenVariants = { showVariantSelection = true },
                    onOpenStreamFormats = { showStreamFormatSelection = true },
                    onOpenAudioVideoSync = { showAudioVideoOffsetDialog = true },
                    audioVideoSyncEnabled = audioVideoSyncEnabled,
                    onEnterPictureInPicture = enterPictureInPicture,
                    isCastConnected = castConnectionState == CastConnectionState.CONNECTED,
                    onCast = { viewModel.castCurrentMedia { mainActivity?.openCastRouteChooser() } },
                    onStopCasting = viewModel::stopCasting,
                    timeshiftUiState = timeshiftUiState,
                    onTransientPanelVisibilityChanged = { channelInfoSubPanelOpen = it },
                    resolutionLabel = videoFormat.resolutionLabel.takeIf { it.isNotBlank() && !videoFormat.isEmpty }
                )
            }
        }
    }
}

private fun AspectRatio.toPlayerSurfaceResizeMode(): PlayerSurfaceResizeMode = when (this) {
    AspectRatio.FIT -> PlayerSurfaceResizeMode.FIT
    AspectRatio.FILL -> PlayerSurfaceResizeMode.FILL
    AspectRatio.ZOOM -> PlayerSurfaceResizeMode.ZOOM
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

@Composable
private fun PlayerControlsOverlayHost(
    playerEngine: PlayerEngine,
    visible: Boolean,
    title: String,
    contentType: String,
    isCatchUpPlayback: Boolean = false,
    isPlaying: Boolean,
    currentProgram: Program?,
    currentChannelName: String?,
    displayChannelNumber: Int,
    aspectRatioLabel: String,
    subtitleTrackCount: Int,
    audioTrackCount: Int,
    videoQualityCount: Int,
    currentRecordingStatus: com.streamvault.domain.model.RecordingStatus?,
    isMuted: Boolean,
    playbackSpeed: Float,
    mediaTitle: String?,
    sleepTimerUiState: SleepTimerUiState,
    timeshiftUiState: PlayerTimeshiftUiState,
    playButtonFocusRequester: FocusRequester,
    quickActionsFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onClose: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSeekBackward: () -> Unit,
    onSeekForward: () -> Unit,
    onRestartProgram: () -> Unit,
    onOpenArchive: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onScheduleRecording: () -> Unit,
    onScheduleDailyRecording: () -> Unit,
    onScheduleWeeklyRecording: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onOpenSubtitleTracks: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenVideoTracks: () -> Unit,
    onOpenPlaybackSpeed: () -> Unit,
    onOpenStopPlaybackTimer: () -> Unit,
    onOpenIdleStandbyTimer: () -> Unit,
    onOpenAudioVideoSync: () -> Unit,
    audioVideoSyncEnabled: Boolean,
    showEpisodesAction: Boolean,
    onOpenEpisodes: () -> Unit,
    onOpenSplitScreen: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onToggleMute: () -> Unit,
    isCastConnected: Boolean,
    onCast: () -> Unit,
    onStopCasting: () -> Unit,
    onSeekToLiveEdge: () -> Unit,
    onSeekToPosition: (Long) -> Unit,
    onSetScrubbingMode: (Boolean) -> Unit,
    seekPreview: SeekPreviewState,
    onSeekPreviewPositionChanged: (Long?) -> Unit,
    onUserInteraction: () -> Unit
) {
    val currentPosition by playerEngine.currentPosition.collectAsStateWithLifecycle()
    val duration by playerEngine.duration.collectAsStateWithLifecycle()

    PlayerControlsOverlay(
        visible = visible,
        title = title,
        contentType = contentType,
        isCatchUpPlayback = isCatchUpPlayback,
        isPlaying = isPlaying,
        currentProgram = currentProgram,
        currentChannelName = currentChannelName,
        displayChannelNumber = displayChannelNumber,
        currentPosition = currentPosition,
        duration = duration,
        aspectRatioLabel = aspectRatioLabel,
        subtitleTrackCount = subtitleTrackCount,
        audioTrackCount = audioTrackCount,
        videoQualityCount = videoQualityCount,
        currentRecordingStatus = currentRecordingStatus,
        isMuted = isMuted,
        playbackSpeed = playbackSpeed,
        mediaTitle = mediaTitle,
        sleepTimerUiState = sleepTimerUiState,
        timeshiftUiState = timeshiftUiState,
        playButtonFocusRequester = playButtonFocusRequester,
        quickActionsFocusRequester = quickActionsFocusRequester,
        modifier = modifier,
        onClose = onClose,
        onTogglePlayPause = onTogglePlayPause,
        onSeekBackward = onSeekBackward,
        onSeekForward = onSeekForward,
        onRestartProgram = onRestartProgram,
        onOpenArchive = onOpenArchive,
        onStartRecording = onStartRecording,
        onStopRecording = onStopRecording,
        onScheduleRecording = onScheduleRecording,
        onScheduleDailyRecording = onScheduleDailyRecording,
        onScheduleWeeklyRecording = onScheduleWeeklyRecording,
        onToggleAspectRatio = onToggleAspectRatio,
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
        onToggleMute = onToggleMute,
        isCastConnected = isCastConnected,
        onCast = onCast,
        onStopCasting = onStopCasting,
        onSeekToLiveEdge = onSeekToLiveEdge,
        onSeekToPosition = onSeekToPosition,
        onSetScrubbingMode = onSetScrubbingMode,
        seekPreview = seekPreview,
        onSeekPreviewPositionChanged = onSeekPreviewPositionChanged,
        onUserInteraction = onUserInteraction
    )
}

private tailrec fun android.content.Context.findMainActivity(): MainActivity? = when (this) {
    is MainActivity -> this
    is android.content.ContextWrapper -> baseContext.findMainActivity()
    else -> null
}
