package com.streamvault.feature.playback.player

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.feature.playback.cast.CastConnectionState
import com.streamvault.feature.playback.cast.CastPlaybackReportMode
import com.streamvault.domain.playback.isPlaybackComplete
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.CombinedCategory
import com.streamvault.domain.model.CombinedM3uProfileMember
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.LiveChannelObservedQuality
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.settings.VodTrackPreferences
import com.streamvault.player.AUDIO_VIDEO_OFFSET_MAX_MS
import com.streamvault.player.AUDIO_VIDEO_OFFSET_MIN_MS
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import com.streamvault.player.PlayerPreloadContentType
import com.streamvault.player.PlayerPreloadItem
import com.streamvault.player.PlayerSubtitleStyle
import com.streamvault.player.timeshift.LiveTimeshiftBackend
import com.streamvault.player.timeshift.LiveTimeshiftState
import com.streamvault.player.timeshift.LiveTimeshiftStatus
import com.streamvault.player.timeshift.TimeshiftConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import android.content.Context
import javax.inject.Inject

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel @Inject constructor(
    @ApplicationContext
    internal val appContext: Context,
    internal val playerEngineCoordinator: PlayerEngineCoordinator,
    internal val playerPreparationCoordinator: PlayerPreparationCoordinator,
    internal val playerTimeshiftCoordinator: PlayerTimeshiftCoordinator,
    internal val epgCoordinator: PlayerEpgCoordinator,
    internal val guideTimelineCoordinator: PlayerGuideTimelineCoordinator,
    internal val playerChannelCoordinator: PlayerChannelCoordinator,
    internal val playerPlaylistCoordinator: PlayerPlaylistCoordinator,
    internal val playbackHistoryCoordinator: PlayerHistoryCoordinator,
    internal val playerProviderCoordinator: PlayerProviderCoordinator,
    internal val playerPreferencesCoordinator: PlayerPreferencesCoordinator,
    internal val playerPreviewCoordinator: PlayerPreviewCoordinator,
    internal val playerThumbnailCoordinator: PlayerThumbnailCoordinator,
    internal val playerRecordingCoordinator: PlayerRecordingCoordinator,
    internal val playerCastCoordinator: PlayerCastCoordinator,
    internal val playerTranslationCoordinator: PlayerTranslationCoordinator,
    internal val playerContentResolver: PlayerContentResolver,
    internal val playerPreloadWindowCoordinator: PlayerPreloadWindowCoordinator,
    internal val playerPlaybackContextCoordinator: PlayerPlaybackContextCoordinator,
    internal val playerRecoveryCoordinator: PlayerRecoveryCoordinator,
    internal val playerRecoveryExecutionCoordinator: PlayerRecoveryExecutionCoordinator,
) : ViewModel() {
    companion object {
        private const val MIN_WATCHED_FOR_AUTO_PLAY_MS = 5_000L
        private const val AUTO_PLAY_COUNTDOWN_SECONDS = 10
        private const val TOKEN_RENEWAL_LEAD_MS = 60_000L
        private const val TOKEN_RENEWAL_CHECK_INTERVAL_MS = 10_000L
        private const val LOW_BANDWIDTH_THRESHOLD_BPS = 500_000L
        private const val LOW_BANDWIDTH_DURATION_SECONDS = 30
        internal val PLAYBACK_TIMER_PRESETS_MINUTES = setOf(0, 15, 30, 45, 60, 90, 120)
        internal const val TIMER_TICK_MS = 1_000L
    }

    private val activePlayerEngineFlow = playerEngineCoordinator.activeEngine
    val activePlayerEngine: StateFlow<PlayerEngine> = activePlayerEngineFlow
    val playerEngine: PlayerEngine
        get() = activePlayerEngineFlow.value

    internal val showControlsFlow = MutableStateFlow(false)
    val showControls: StateFlow<Boolean> = showControlsFlow.asStateFlow()

    private val _isCatchUpPlayback = MutableStateFlow(false)
    val isCatchUpPlayback: StateFlow<Boolean> = _isCatchUpPlayback.asStateFlow()

    internal val showZapOverlayFlow = MutableStateFlow(false)
    val showZapOverlay: StateFlow<Boolean> = showZapOverlayFlow.asStateFlow()
    
    val currentProgram: StateFlow<Program?> = guideTimelineCoordinator.currentProgram
    val nextProgram: StateFlow<Program?> = guideTimelineCoordinator.nextProgram
    val programHistory: StateFlow<List<Program>> = guideTimelineCoordinator.programHistory
    val upcomingPrograms: StateFlow<List<Program>> = guideTimelineCoordinator.upcomingPrograms

    internal val currentChannelFlow = MutableStateFlow<com.streamvault.domain.model.Channel?>(null)
    val currentChannel: StateFlow<com.streamvault.domain.model.Channel?> = currentChannelFlow.asStateFlow()

    private val _currentSeries = MutableStateFlow<Series?>(null)
    val currentSeries: StateFlow<Series?> = _currentSeries.asStateFlow()

    private val _currentEpisode = MutableStateFlow<Episode?>(null)
    val currentEpisode: StateFlow<Episode?> = _currentEpisode.asStateFlow()

    private val _nextEpisode = MutableStateFlow<Episode?>(null)
    val nextEpisode: StateFlow<Episode?> = _nextEpisode.asStateFlow()

    internal val _autoPlayCountdown = MutableStateFlow<AutoPlayCountdownUiState?>(null)
    val autoPlayCountdown: StateFlow<AutoPlayCountdownUiState?> = _autoPlayCountdown.asStateFlow()

    internal val _skipChapter = MutableStateFlow<SkipChapterUiState?>(null)
    val skipChapter: StateFlow<SkipChapterUiState?> = _skipChapter.asStateFlow()

    internal val playbackTitleFlow = MutableStateFlow("")
    val playbackTitle: StateFlow<String> = playbackTitleFlow.asStateFlow()
    
    internal val _resumePrompt = MutableStateFlow(ResumePromptState())
    val resumePrompt: StateFlow<ResumePromptState> = _resumePrompt.asStateFlow()

    internal val _aspectRatio = MutableStateFlow(AspectRatio.FIT)
    val aspectRatio: StateFlow<AspectRatio> = _aspectRatio.asStateFlow()

    internal val showChannelListOverlayFlow = MutableStateFlow(false)
    val showChannelListOverlay: StateFlow<Boolean> = showChannelListOverlayFlow.asStateFlow()

    internal val showEpgOverlayFlow = MutableStateFlow(false)
    val showEpgOverlay: StateFlow<Boolean> = showEpgOverlayFlow.asStateFlow()

    internal val showFullGuideOverlayFlow = MutableStateFlow(false)
    val showFullGuideOverlay: StateFlow<Boolean> = showFullGuideOverlayFlow.asStateFlow()

    internal val currentChannelFlowList = MutableStateFlow<List<com.streamvault.domain.model.Channel>>(emptyList())
    val currentChannelList: StateFlow<List<com.streamvault.domain.model.Channel>> = currentChannelFlowList.asStateFlow()

    internal val recentChannelsFlow = MutableStateFlow<List<com.streamvault.domain.model.Channel>>(emptyList())
    val recentChannels: StateFlow<List<com.streamvault.domain.model.Channel>> = recentChannelsFlow.asStateFlow()

    internal val _lastVisitedCategory = MutableStateFlow<Category?>(null)
    val lastVisitedCategory: StateFlow<Category?> = _lastVisitedCategory.asStateFlow()

    internal val showCategoryListOverlayFlow = MutableStateFlow(false)
    val showCategoryListOverlay: StateFlow<Boolean> = showCategoryListOverlayFlow.asStateFlow()

    internal val availableCategoriesFlow = MutableStateFlow<List<Category>>(emptyList())
    val availableCategories: StateFlow<List<Category>> = availableCategoriesFlow.asStateFlow()

    internal val parentalControlLevelFlow = MutableStateFlow(0)
    val parentalControlLevel: StateFlow<Int> = parentalControlLevelFlow.asStateFlow()

    internal val activeCategoryIdFlow = MutableStateFlow(-1L)
    val activeCategoryId: StateFlow<Long> = activeCategoryIdFlow.asStateFlow()

    internal val displayChannelNumberFlow = MutableStateFlow(0)
    val displayChannelNumber: StateFlow<Int> = displayChannelNumberFlow.asStateFlow()

    internal val showChannelInfoOverlayFlow = MutableStateFlow(false)
    val showChannelInfoOverlay: StateFlow<Boolean> = showChannelInfoOverlayFlow.asStateFlow()

    internal val numericChannelInputFlow = MutableStateFlow<NumericChannelInputState?>(null)
    val numericChannelInput: StateFlow<NumericChannelInputState?> = numericChannelInputFlow.asStateFlow()

    internal val showDiagnosticsFlow = MutableStateFlow(false)
    val showDiagnostics: StateFlow<Boolean> = showDiagnosticsFlow.asStateFlow()

    internal val _playerNotice = MutableStateFlow<PlayerNoticeState?>(null)
    val playerNotice: StateFlow<PlayerNoticeState?> = _playerNotice.asStateFlow()
    private val _playerDiagnostics = MutableStateFlow(PlayerDiagnosticsUiState())
    val playerDiagnostics: StateFlow<PlayerDiagnosticsUiState> = _playerDiagnostics.asStateFlow()
    internal val audioVideoOffsetPreviewMs = MutableStateFlow<Int?>(null)
    internal val _audioVideoOffsetUiState = MutableStateFlow(PlayerAudioVideoOffsetUiState())
    val audioVideoOffsetUiState: StateFlow<PlayerAudioVideoOffsetUiState> = _audioVideoOffsetUiState.asStateFlow()
    internal val _seekPreview = MutableStateFlow(SeekPreviewState())
    val seekPreview: StateFlow<SeekPreviewState> = _seekPreview.asStateFlow()
    private val _recordingItems = MutableStateFlow<List<RecordingItem>>(emptyList())
    val recordingItems: StateFlow<List<RecordingItem>> = _recordingItems.asStateFlow()
    private val currentChannelFlowRecording = MutableStateFlow<RecordingItem?>(null)
    val currentChannelRecording: StateFlow<RecordingItem?> = currentChannelFlowRecording.asStateFlow()
    private val _timeshiftUiState = MutableStateFlow(PlayerTimeshiftUiState())
    val timeshiftUiState: StateFlow<PlayerTimeshiftUiState> = _timeshiftUiState.asStateFlow()
    internal val _sleepTimerUiState = MutableStateFlow(SleepTimerUiState())
    val sleepTimerUiState: StateFlow<SleepTimerUiState> = _sleepTimerUiState.asStateFlow()
    internal val _sleepTimerExitEvent = MutableStateFlow(0)
    val sleepTimerExitEvent: StateFlow<Int> = _sleepTimerExitEvent.asStateFlow()
    val remoteShortcutPreferences = playerPreferencesCoordinator.remoteShortcutPreferences
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), com.streamvault.domain.model.RemoteShortcutPreferences())
    private val _playerPreferencesUiState = MutableStateFlow(PlayerPreferencesUiState())
    val playerPreferencesUiState: StateFlow<PlayerPreferencesUiState> = _playerPreferencesUiState.asStateFlow()
    private val _externalPlaybackUrl = MutableStateFlow("")
    val externalPlaybackUrl: StateFlow<String> = _externalPlaybackUrl.asStateFlow()
    private val _playbackResolutionUiState = MutableStateFlow<PlaybackResolutionUiState>(
        PlaybackResolutionUiState.Idle
    )
    val playbackResolutionUiState: StateFlow<PlaybackResolutionUiState> =
        _playbackResolutionUiState.asStateFlow()

    internal var channelInfoHideJob: Job? = null
    internal var liveOverlayHideJob: Job? = null
    internal var diagnosticsHideJob: Job? = null
    internal var numericInputCommitJob: Job? = null
    internal var numericInputFeedbackJob: Job? = null
    internal var playerNoticeHideJob: Job? = null
    internal var mutePersistJob: Job? = null
    internal var numericInputBuffer: String = ""
    internal val probePassedPlaybackKeys = mutableSetOf<String>()
    private val notifiedRecordingFailureIds = mutableSetOf<String>()
    internal val livePlaybackRecordCoordinator =
        LivePlaybackRecordCoordinator(playbackHistoryCoordinator::recordPlayback)
    private var currentStreamClassLabel: String = "Primary"
    internal var lastRecordedVariantObservationSignature: String? = null
    internal var lastRecordedVodVariantObservationSignature: String? = null
    internal val playbackSessionCoordinator = PlaybackSessionCoordinator(viewModelScope)
    private val playerRecoveryExecutionPort = PlayerRecoveryExecutionPortAdapter(
        appPackageName = appContext.packageName,
        engineCoordinator = playerEngineCoordinator,
        recoveryState = playerRecoveryCoordinator,
        isActivePlaybackSession = { requestVersion, playbackUrl ->
            isActivePlaybackSession(requestVersion, playbackUrl)
        },
        tryAlternateStream = { channel, preferXtreamTsFallback, allowXtreamTsFallback ->
            tryAlternateStreamInternal(
                channel = channel,
                preferXtreamTsFallback = preferXtreamTsFallback,
                allowXtreamTsFallback = allowXtreamTsFallback
            )
        },
        tryNextCatchUpVariant = { tryNextCatchUpVariantInternal() },
        tryFallbackToAvcMovieVariant = { requestVersion, playbackUrl ->
            tryFallbackToAvcMovieVariant(requestVersion, playbackUrl)
        },
        tryRefreshXtreamPlaybackAfterAuthError = { error, requestVersion, playbackUrl ->
            tryRefreshXtreamPlaybackAfterAuthError(error, requestVersion, playbackUrl)
        },
        recordMovieVariantFailureObservation = { error ->
            recordMovieVariantFailureObservation(error)
        },
        updateDecoderModes = { audioMode, videoMode -> updateDecoderModes(audioMode, videoMode) },
        setLastFailureReason = { message -> setLastFailureReason(message) },
        appendRecoveryAction = { action -> appendRecoveryAction(action) },
        buildRecoveryActions = { recoveryType -> buildRecoveryActions(recoveryType) },
        showPlayerNotice = { message, recoveryType, actions, isRetryNotice ->
            showPlayerNotice(
                message = message,
                recoveryType = recoveryType,
                actions = actions,
                isRetryNotice = isRetryNotice
            )
        },
        markStreamFailure = { streamUrl -> markStreamFailure(streamUrl) },
        incrementChannelErrorCount = { channelId ->
            playerChannelCoordinator.incrementChannelErrorCount(channelId)
        },
        logRepositoryFailure = { operation, result -> logRepositoryFailure(operation, result) },
        fallbackToPreviousChannel = { reason -> fallbackToPreviousChannel(reason) },
        hasLastChannel = { hasLastChannel() }
    )
    internal val prepareRequestVersion: Long
        get() = playbackSessionCoordinator.currentId
    internal var readySideEffectsRequestVersion: Long? = null
    internal var currentArtworkUrl: String?
        get() = playerPlaybackContextCoordinator.currentArtworkUrl
        set(value) {
            playerPlaybackContextCoordinator.currentArtworkUrl = value
        }
    internal var currentResolvedPlaybackUrl: String
        get() = playerPlaybackContextCoordinator.currentResolvedPlaybackUrl
        set(value) {
            playerPlaybackContextCoordinator.currentResolvedPlaybackUrl = value
            _externalPlaybackUrl.value = value.trim()
        }
    internal var currentResolvedStreamInfo: StreamInfo?
        get() = playerPlaybackContextCoordinator.currentResolvedStreamInfo
        set(value) {
            playerPlaybackContextCoordinator.currentResolvedStreamInfo = value
        }
    internal fun clearResolvedStream() {
        playerPlaybackContextCoordinator.clearResolvedStream()
        _externalPlaybackUrl.value = ""
    }
    internal var pendingCatchUpUrls: List<String>
        get() = playerPlaybackContextCoordinator.pendingCatchUpUrls
        set(value) {
            playerPlaybackContextCoordinator.pendingCatchUpUrls = value
        }
    internal var livePlaybackReadyForCurrentSession: Boolean = false
    internal var channelNumberingMode: ChannelNumberingMode = ChannelNumberingMode.GROUP
        set(value) {
            field = value
            rebuildChannelNumberIndex()
        }
    internal var playerControlsTimeoutMs: Long = 5_000L
    internal var liveOverlayTimeoutMs: Long = 4_000L
    internal var playerNoticeTimeoutMs: Long = 6_000L
    internal var diagnosticsTimeoutMs: Long = 15_000L
    private var preferredAudioDecoderMode: DecoderMode = DecoderMode.AUTO
    private var preferredVideoDecoderMode: DecoderMode = DecoderMode.AUTO
    private var preferredSurfaceMode: com.streamvault.domain.model.PlayerSurfaceMode =
        com.streamvault.domain.model.PlayerSurfaceMode.AUTO
    internal var timeshiftConfig: TimeshiftConfig = TimeshiftConfig()

    // Zapping state
    //
    // Invariant: `currentChannelIndex` is always -1 (no channel loaded) or a valid index
    // into `channelList`. Code that updates either field must maintain this relationship.
    // `channelList` is replaced asynchronously by the playlist flow collector; after
    // replacement, `currentChannelIndex` is recomputed in the same collector block,
    // so the invariant holds at rest. `changeChannel()` verifies the invariant at entry.
    /**
     * Ordered list of channels in the current category, set by the playlist [combine]
     * collector. Linked to [currentChannelIndex] ג€” see invariant comment above.
     */
    internal var channelList: List<com.streamvault.domain.model.Channel> = emptyList()
        set(value) {
            field = value
            rebuildChannelNumberIndex()
        }
    internal var channelNumberIndex: Map<Int, com.streamvault.domain.model.Channel> = emptyMap()
        private set

    private fun rebuildChannelNumberIndex() {
        channelNumberIndex = channelList.withIndex().associate { (index, channel) ->
            resolveChannelNumber(channel, index) to channel
        }
    }

    internal var currentChannelIndex = -1
    internal var previousChannelIndex = -1
    internal var currentCategoryId: Long = -1
    internal var currentProviderId: Long
        get() = playerPlaybackContextCoordinator.currentProviderId
        set(value) {
            playerPlaybackContextCoordinator.currentProviderId = value
        }
    internal var currentCombinedProfileId: Long? = null
    internal var currentCombinedSourceFilterProviderId: Long? = null
    internal var currentContentId: Long
        get() = playerPlaybackContextCoordinator.currentContentId
        set(value) {
            playerPlaybackContextCoordinator.currentContentId = value
        }
    internal var currentContentType: ContentType
        get() = playerPlaybackContextCoordinator.currentContentType
        set(value) {
            playerPlaybackContextCoordinator.currentContentType = value
        }
    internal var currentTitle: String
        get() = playerPlaybackContextCoordinator.currentTitle
        set(value) {
            playerPlaybackContextCoordinator.currentTitle = value
        }
    internal var currentSeriesId: Long? = null
    internal var currentSeasonNumber: Int? = null
    internal var currentEpisodeNumber: Int? = null
    internal var currentStableEpisodeId: Long? = null
    internal var activeVodTrackPreferences: VodTrackPreferences? = null
    internal val vodTrackPreferenceSaveMutex = Mutex()
    internal var isVirtualCategory: Boolean = false
    internal var currentCombinedProfileMembers: List<CombinedM3uProfileMember> = emptyList()
    internal var combinedCategoriesById: Map<Long, CombinedCategory> = emptyMap()
    private var lastObservedPlaybackState: PlaybackState = PlaybackState.IDLE

    internal var playlistJob: Job? = null
    internal var recentChannelsJob: Job? = null
    internal var lastVisitedCategoryJob: Job? = null
    internal var controlsHideJob: Job? = null
    internal var seekPreviewJob: Job? = null
    internal var thumbnailPreloadJob: Job? = null
    private var preloadWindowJob: Job? = null
    private var preloadWindowInFlightFingerprint: String? = null
    private var acceptedPreloadWindowFingerprint: String? = null
    internal var inFlightThumbnailPreloadKey: String? = null
    internal var lastCompletedThumbnailPreloadKey: String? = null
    private var lowBandwidthMonitorJob: Job? = null
    internal var progressTrackingJob: Job? = null
    internal var tokenRenewalJob: Job? = null
    internal var stopPlaybackTimerJob: Job? = null
    internal var idleStandbyTimerJob: Job? = null
    internal var zapOverlayJob: Job? = null
    internal var aspectRatioJob: Job? = null
    internal var zapBufferWatchdogJob: Job? = null
    internal var autoPlayCountdownJob: Job? = null
    internal var lastTriggeredCreditsChapterStartMs: Long? = null
    internal var creditsAutoPlayTriggeredForSession: Boolean = false
    internal var zapAutoRevertEnabled: Boolean = true
    internal var autoPlayNextEpisodeEnabled: Boolean = true
    internal var isAppInForeground: Boolean = true
    internal var shouldResumeAfterForeground: Boolean = false
    internal var seekPreviewRequestVersion: Long = 0L
    internal var lastMuteToggleAtMs: Long = 0L
    internal var stopPlaybackTimerEndsAtMs: Long = 0L
    internal var idleStandbyTimerEndsAtMs: Long = 0L
    private var defaultStopPlaybackTimerMinutes: Int = 0
    private var defaultIdleStandbyTimerMinutes: Int = 0
    internal var playbackTimerDefaultsApplied = false
    internal var sleepTimerExitEmitted = false
    internal var activeStalkerPlaybackProviderId: Long? = null
    internal val _liveTranslationAvailable = MutableStateFlow(false)
    val liveTranslationAvailable: StateFlow<Boolean> = _liveTranslationAvailable.asStateFlow()
    internal val _liveTranslationActive = MutableStateFlow(false)
    val liveTranslationActive: StateFlow<Boolean> = _liveTranslationActive.asStateFlow()
    internal val _liveTranslationDetectedLanguage = MutableStateFlow<String?>(null)
    val liveTranslationDetectedLanguage: StateFlow<String?> = _liveTranslationDetectedLanguage.asStateFlow()
    internal var castPlaybackReportMode: CastPlaybackReportMode = CastPlaybackReportMode.NONE
    private var downloadPlaybackSlotActive = false
    private var currentPlaybackUsesDownloadSlot = false
    private var externalProviderPlaybackHold = false

    val castConnectionState: StateFlow<CastConnectionState> = playerCastCoordinator.connectionState

    private fun <T> activeEngineState(
        initialValue: T,
        selector: (PlayerEngine) -> StateFlow<T>
    ): StateFlow<T> = activePlayerEngineFlow
        .flatMapLatest(selector)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue)

    private fun <T> activeEngineFlowState(
        initialValue: T,
        selector: (PlayerEngine) -> Flow<T>
    ): StateFlow<T> = activePlayerEngineFlow
        .flatMapLatest(selector)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initialValue)

    internal fun logRepositoryFailure(operation: String, result: com.streamvault.domain.model.Result<Unit>) {
        if (result is com.streamvault.domain.model.Result.Error) {
            android.util.Log.w("PlayerVM", "$operation failed: ${result.message}", result.exception)
        }
    }

    private fun applyTimeshiftState(state: LiveTimeshiftState) {
        val backendLabel = when (state.backend) {
            LiveTimeshiftBackend.DISK -> "Disk"
            LiveTimeshiftBackend.MEMORY -> "Memory"
            LiveTimeshiftBackend.NONE -> ""
        }
        val visibleForLiveUi = timeshiftConfig.enabled &&
            state.status != LiveTimeshiftStatus.DISABLED &&
            state.status != LiveTimeshiftStatus.UNSUPPORTED &&
            state.status != LiveTimeshiftStatus.FAILED
        _timeshiftUiState.value = PlayerTimeshiftUiState(
            available = visibleForLiveUi,
            enabledForSession = timeshiftConfig.enabled,
            backendLabel = backendLabel,
            bufferedBehindLiveMs = state.currentOffsetFromLiveMs,
            bufferDepthMs = state.bufferedDurationMs.takeIf { it > 0L } ?: timeshiftConfig.depthMs,
            canSeekToLive = state.canSeekToLive,
            statusMessage = state.message.orEmpty(),
            engineState = state
        )
    }

    private fun maybeStartLiveTimeshift(streamInfoOverride: StreamInfo? = null) {
        playerTimeshiftCoordinator.startOrStop(
            request = PlayerTimeshiftCoordinator.Request(
                contentType = currentContentType,
                enabled = timeshiftConfig.enabled,
                streamClassLabel = currentStreamClassLabel,
                config = timeshiftConfig,
                streamInfoOverride = streamInfoOverride,
                currentResolvedStreamInfo = currentResolvedStreamInfo,
                currentResolvedPlaybackUrl = currentResolvedPlaybackUrl,
                currentStreamUrl = currentStreamUrl,
                playbackTitle = playbackTitleFlow.value,
                currentTitle = currentTitle,
                channelKey = currentChannel.value?.id?.toString()
                    ?: currentContentId.takeIf { it > 0L }?.toString()
            ),
            onUnavailable = {
                _timeshiftUiState.update {
                    it.copy(
                        available = false,
                        enabledForSession = timeshiftConfig.enabled,
                        statusMessage = "Local live rewind is unavailable for this stream."
                    )
                }
            },
            onPreparing = {
                _timeshiftUiState.update {
                    it.copy(
                        available = true,
                        enabledForSession = true,
                        statusMessage = "Preparing local live rewind…",
                        bufferDepthMs = timeshiftConfig.depthMs
                    )
                }
            }
        )
    }

    init {
        observeCastPlaybackEvents()
        viewModelScope.launch {
            activePlayerEngineFlow.flatMapLatest { it.error }.collect { error ->
                if (error != null) {
                    handlePlaybackError(error)
                }
            }
        }
        viewModelScope.launch {
            activePlayerEngineFlow.flatMapLatest { it.playbackState }.collect { state ->
                _playerDiagnostics.update { it.copy(playbackStateLabel = state.name.replace('_', ' ')) }
                if (state == PlaybackState.ENDED && lastObservedPlaybackState != PlaybackState.ENDED) {
                    handlePlaybackEnded()
                }
                lastObservedPlaybackState = state
                if (state == PlaybackState.READY && readySideEffectsRequestVersion == prepareRequestVersion) {
                    zapBufferWatchdogJob?.cancel()
                    dismissRecoveredNoticeIfPresent()
                    if (currentContentType == ContentType.LIVE && !isCatchUpPlayback()) {
                        livePlaybackReadyForCurrentSession = true
                        recordActiveLivePlayback()
                        currentChannelFlow.value?.sanitizedForPlayer()?.let { channel ->
                            if (channel.errorCount > 0) {
                                logRepositoryFailure(
                                    operation = "Reset channel error count",
                                    result = playerChannelCoordinator.resetChannelErrorCount(channel.id)
                                )
                            }
                        }
                    } else {
                        if (currentContentType != ContentType.LIVE) {
                            recordMovieVariantSuccessObservation()
                            startThumbnailPreload()
                        }
                        refreshPreloadWindow(prepareRequestVersion)
                    }
                }
            }
        }
        viewModelScope.launch {
            combine(
                activePlayerEngineFlow.flatMapLatest { engine ->
                    combine(engine.currentPosition, engine.duration, engine.chapters) { positionMs, durationMs, chapters ->
                        Triple(positionMs, durationMs, chapters)
                    }
                },
                nextEpisode,
                playerPreferencesCoordinator.autoPlayNextEpisode
            ) { positionAndChapters, nextEpisode, autoPlayEnabled ->
                PlayerChapterObservation(
                    positionMs = positionAndChapters.first,
                    durationMs = positionAndChapters.second,
                    chapters = positionAndChapters.third,
                    nextEpisode = nextEpisode,
                    autoPlayEnabled = autoPlayEnabled
                )
            }.collect { observation ->
                _skipChapter.value = if (
                    readySideEffectsRequestVersion == prepareRequestVersion &&
                    currentContentType.isSkippableChapterContent()
                ) {
                    findSkippableChapterAction(
                        chapters = observation.chapters,
                        positionMs = observation.positionMs
                    )?.let { action ->
                        SkipChapterUiState(
                            type = action.type,
                            targetPositionMs = action.targetPositionMs
                        )
                    }
                } else {
                    null
                }
                handleCreditsChapterObservation(observation)
            }
        }
        viewModelScope.launch {
            activePlayerEngineFlow.flatMapLatest { it.retryStatus }.collect { status ->
                status ?: return@collect
                showRetryNotice(status)
            }
        }
        viewModelScope.launch {
            activePlayerEngineFlow.flatMapLatest { it.audioFocusDenied }.collect {
                showPlayerNotice(
                    message = "Waiting for audio \u2014 unmute device and press Play",
                    durationMs = 8_000L
                )
            }
        }
        viewModelScope.launch {
            playerRecordingCoordinator.observeRecordingItems().collect { items ->
                handleRecordingStateChanges(previousItems = _recordingItems.value, newItems = items)
                _recordingItems.value = items
                refreshCurrentChannelRecording(items)
            }
        }
        viewModelScope.launch {
            combine(
                playerPreferencesCoordinator.playerSubtitleTextScale,
                playerPreferencesCoordinator.playerSubtitleTextColor,
                playerPreferencesCoordinator.playerSubtitleBackgroundColor
            ) { textScale, textColor, backgroundColor ->
                PlayerSubtitleStyle(
                    textScale = textScale,
                    foregroundColorArgb = textColor,
                    backgroundColorArgb = backgroundColor
                )
            }.combine(activePlayerEngineFlow) { style, engine -> engine to style }
                .collect { (engine, style) -> engine.setSubtitleStyle(style) }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.playerLiveTranslationEnabled.collect {
                refreshLiveTranslationAvailability()
            }
        }
        viewModelScope.launch {
            combine(
                playerPreferencesCoordinator.playerControlsTimeoutSeconds,
                playerPreferencesCoordinator.playerLiveOverlayTimeoutSeconds,
                playerPreferencesCoordinator.playerNoticeTimeoutSeconds,
                playerPreferencesCoordinator.playerDiagnosticsTimeoutSeconds
            ) { controlsSeconds, liveOverlaySeconds, noticeSeconds, diagnosticsSeconds ->
                PlayerUiTimeouts(
                    controlsMs = controlsSeconds * 1000L,
                    liveOverlayMs = liveOverlaySeconds * 1000L,
                    noticeMs = noticeSeconds * 1000L,
                    diagnosticsMs = diagnosticsSeconds * 1000L
                )
            }.collect { timeouts ->
                playerControlsTimeoutMs = timeouts.controlsMs
                liveOverlayTimeoutMs = timeouts.liveOverlayMs
                playerNoticeTimeoutMs = timeouts.noticeMs
                diagnosticsTimeoutMs = timeouts.diagnosticsMs
            }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.zapAutoRevert.collect { zapAutoRevertEnabled = it }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.autoPlayNextEpisode.collect { autoPlayNextEpisodeEnabled = it }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.parentalControlLevel.collect { parentalControlLevelFlow.value = it }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.defaultStopPlaybackTimerMinutes.collect {
                defaultStopPlaybackTimerMinutes = sanitizePlaybackTimerMinutes(it, PLAYBACK_TIMER_PRESETS_MINUTES)
            }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.defaultIdleStandbyTimerMinutes.collect {
                defaultIdleStandbyTimerMinutes = sanitizePlaybackTimerMinutes(it, PLAYBACK_TIMER_PRESETS_MINUTES)
            }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.playerMediaSessionEnabled
                .combine(activePlayerEngineFlow) { enabled, engine -> engine to enabled }
                .collect { (engine, enabled) -> engine.setMediaSessionEnabled(enabled) }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.playerFastRetryOnTransientFailures
                .combine(activePlayerEngineFlow) { enabled, engine -> engine to enabled }
                .collect { (engine, enabled) -> engine.setFastRetryOnTransientFailures(enabled) }
        }
        viewModelScope.launch {
            currentChannelFlow
                .map { it?.id }
                .distinctUntilChanged()
                .collect { audioVideoOffsetPreviewMs.value = null }
        }
        viewModelScope.launch {
            val channelOffsetFlow = currentChannelFlow
                .map { it?.id?.takeIf { channelId -> channelId > 0L } }
                .distinctUntilChanged()
                .flatMapLatest { channelId ->
                    if (channelId == null) {
                        flowOf(null)
                    } else {
                        playerPreferencesCoordinator.observeAudioVideoOffsetForChannel(channelId)
                    }
                }

            combine(
                playerPreferencesCoordinator.playerAudioVideoOffsetMs,
                channelOffsetFlow,
                audioVideoOffsetPreviewMs,
                activePlayerEngineFlow.flatMapLatest { it.audioVideoSyncEnabled },
                activePlayerEngineFlow
            ) { globalOffset, channelOffset, previewOffset, enabled, engine ->
                val effectiveOffset = (previewOffset ?: channelOffset ?: globalOffset)
                    .coerceIn(AUDIO_VIDEO_OFFSET_MIN_MS, AUDIO_VIDEO_OFFSET_MAX_MS)
                AudioVideoOffsetSnapshot(
                    globalOffsetMs = globalOffset,
                    channelOverrideMs = channelOffset,
                    previewOffsetMs = previewOffset,
                    effectiveOffsetMs = effectiveOffset,
                    engine = engine,
                    enabled = enabled
                )
            }.collect { snapshot ->
                _audioVideoOffsetUiState.value = PlayerAudioVideoOffsetUiState(
                    globalOffsetMs = snapshot.globalOffsetMs,
                    channelOverrideMs = snapshot.channelOverrideMs,
                    previewOffsetMs = snapshot.previewOffsetMs,
                    effectiveOffsetMs = snapshot.effectiveOffsetMs
                )
                snapshot.engine.setAudioVideoOffsetMs(snapshot.effectiveOffsetMs)
                _playerDiagnostics.update {
                    it.copy(audioVideoOffsetMs = snapshot.effectiveOffsetMs)
                }
            }
        }
        viewModelScope.launch {
            combine(
                playerPreferencesCoordinator.playerTimeshiftEnabled,
                playerPreferencesCoordinator.playerTimeshiftDepthMinutes,
                playerPreferencesCoordinator.playerTimeshiftBackend
            ) { enabled, depthMinutes, backendPreference ->
                TimeshiftConfig(
                    enabled = enabled,
                    depthMinutes = depthMinutes,
                    backendPreference = backendPreference
                )
            }.collect { config ->
                timeshiftConfig = config
                _timeshiftUiState.update { current ->
                    current.copy(
                        enabledForSession = config.enabled,
                        bufferDepthMs = config.depthMs
                    )
                }
                maybeStartLiveTimeshift()
            }
        }
        viewModelScope.launch {
            activePlayerEngineFlow.flatMapLatest { it.timeshiftState }.collect(::applyTimeshiftState)
        }
        viewModelScope.launch {
            combine(
                combine(
                    playerPreferencesCoordinator.playerExternalPlaybackMode,
                    playerPreferencesCoordinator.appTimeFormat
                ) { externalPlaybackMode, timeFormat -> externalPlaybackMode to timeFormat },
                playerPreferencesCoordinator.playerLiveClockEnabled,
                playerPreferencesCoordinator.playerLiveClockPosition,
                playerPreferencesCoordinator.playerLiveClockSize,
                playerPreferencesCoordinator.playerLiveClockFont
            ) { (externalPlaybackMode, timeFormat), liveClockEnabled, liveClockPosition, liveClockSize, liveClockFont ->
                PlayerPreferencesUiState(
                    externalPlaybackMode = externalPlaybackMode,
                    timeFormat = timeFormat,
                    liveClockEnabled = liveClockEnabled,
                    liveClockPosition = liveClockPosition,
                    liveClockSize = liveClockSize,
                    liveClockFont = liveClockFont
                )
            }
                .combine(playerPreferencesCoordinator.playerBackButtonVisibility) { state, backButtonVisibility ->
                    state.copy(backButtonVisibility = backButtonVisibility)
                }
                .combine(playerPreferencesCoordinator.playerConfirmClosePlayback) { state, confirmClosePlayback ->
                    state.copy(confirmClosePlayback = confirmClosePlayback)
                }
                .collect { state ->
                _playerPreferencesUiState.value = state
            }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.playerAudioDecoderMode
                .combine(playerPreferencesCoordinator.playerVideoDecoderMode) { audioMode, videoMode ->
                    audioMode to videoMode
                }
                .combine(activePlayerEngineFlow) { modes, engine -> engine to modes }
                .collect { (engine, modes) ->
                    val (audioMode, videoMode) = modes
                    preferredAudioDecoderMode = audioMode
                    preferredVideoDecoderMode = videoMode
                    if (!playerRecoveryCoordinator.retriedWithSoftwareDecoder) {
                        engine.setDecoderModes(audioMode = audioMode, videoMode = videoMode)
                        if (engine === playerEngine) {
                            updateDecoderModes(audioMode = audioMode, videoMode = videoMode)
                        }
                    }
                }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.playerPlaybackBufferMode
                .combine(activePlayerEngineFlow) { mode, engine -> engine to mode }
                .collect { (engine, mode) ->
                    engine.setPlaybackBufferMode(mode)
                }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.playerAudioOutputPreference
                .combine(activePlayerEngineFlow) { preference, engine -> engine to preference }
                .collect { (engine, preference) ->
                    engine.setAudioOutputPreference(preference)
                }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.playerCompatibilityMemoryEnabled
                .combine(activePlayerEngineFlow) { enabled, engine -> engine to enabled }
                .collect { (engine, enabled) ->
                    engine.setCompatibilityMemoryEnabled(enabled)
                }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.playerSurfaceMode
                .combine(activePlayerEngineFlow) { mode, engine -> engine to mode }
                .collect { (engine, mode) ->
                    preferredSurfaceMode = mode
                    engine.setSurfaceMode(mode)
                }
        }
        viewModelScope.launch {
            playerPreferencesCoordinator.playerVodHttpProtocolMode
                .combine(activePlayerEngineFlow) { mode, engine -> engine to mode }
                .collect { (engine, mode) ->
                    engine.setVodHttpProtocolMode(mode)
                }
        }
        viewModelScope.launch {
            var consecutiveLowBandwidthSeconds = 0
            var noticeShown = false
            activePlayerEngineFlow.flatMapLatest { it.playerStats }.collect { stats ->
                _playerDiagnostics.update {
                    it.copy(
                        activeDecoderName = stats.videoDecoderName,
                        activeAudioDecoderName = stats.audioDecoderName,
                        ffmpegAvailable = stats.ffmpegAvailable,
                        ffmpegVersion = stats.ffmpegVersion,
                        audioOutputPath = stats.audioOutputPath,
                        compatibilityDecisionSource = stats.compatibilityDecisionSource,
                        activeDecoderPolicy = stats.activeDecoderPolicy,
                        renderSurfaceType = stats.renderSurfaceType,
                        videoStallCount = stats.videoStallCount,
                        lastVideoFrameAgoMs = stats.lastVideoFrameAgoMs
                    )
                }
                if (!playerEngine.isPlaying.value || currentContentType != ContentType.LIVE) {
                    consecutiveLowBandwidthSeconds = 0
                    noticeShown = false
                    return@collect
                }
                val bps = stats.bandwidthEstimate
                if (bps in 1 until LOW_BANDWIDTH_THRESHOLD_BPS) {
                    consecutiveLowBandwidthSeconds++
                } else {
                    consecutiveLowBandwidthSeconds = 0
                    noticeShown = false
                }
                if (consecutiveLowBandwidthSeconds >= LOW_BANDWIDTH_DURATION_SECONDS && !noticeShown) {
                    noticeShown = true
                    showPlayerNotice(
                        message = "Network speed is low \u2014 stream quality reduced",
                        recoveryType = PlayerRecoveryType.NETWORK,
                        durationMs = 10_000L
                    )
                }
            }
        }
    }

    private fun handlePlaybackError(error: PlayerError) {
        val requestVersion = prepareRequestVersion
        val playbackUrl = resolvePlaybackIdentityUrl(
            currentResolvedPlaybackUrl = currentResolvedPlaybackUrl,
            currentStreamUrl = currentStreamUrl
        )
        playerRecoveryExecutionCoordinator.handlePlaybackError(
            request = PlayerRecoveryRequest(
                error = error,
                requestVersion = requestVersion,
                playbackUrl = playbackUrl,
                contentType = currentContentType,
                currentStreamUrl = currentStreamUrl,
                resolvedStreamInfo = currentResolvedStreamInfo,
                channel = currentChannelFlow.value?.sanitizedForPlayer(),
                isCatchUp = isCatchUpPlayback(),
                currentProgramHasArchive = currentProgram.value?.hasArchive == true,
                livePlaybackReady = livePlaybackReadyForCurrentSession
            ),
            sessionScope = playbackSessionScope(requestVersion),
            port = playerRecoveryExecutionPort
        )
    }

    internal fun refreshCurrentChannelRecording(items: List<RecordingItem> = _recordingItems.value) {
        val channelId = currentChannelFlow.value?.id ?: -1L
        currentChannelFlowRecording.value = items.firstOrNull {
            it.providerId == currentProviderId &&
                it.channelId == channelId &&
                (it.status == RecordingStatus.RECORDING || it.status == RecordingStatus.SCHEDULED)
        }
    }

    private fun handleRecordingStateChanges(
        previousItems: List<RecordingItem>,
        newItems: List<RecordingItem>
    ) {
        val previousStatuses = previousItems.associateBy(RecordingItem::id)
        val failedNow = newItems.firstOrNull { item ->
            previousStatuses[item.id]?.status == RecordingStatus.RECORDING &&
                item.status == RecordingStatus.FAILED &&
                notifiedRecordingFailureIds.add(item.id)
        }

        notifiedRecordingFailureIds.retainAll(
            newItems.asSequence()
                .filter { it.status == RecordingStatus.FAILED }
                .map { it.id }
                .toSet()
        )

        failedNow?.let { item ->
            val title = item.programTitle?.takeIf { it.isNotBlank() } ?: item.channelName
            val detail = item.failureReason?.takeIf { it.isNotBlank() } ?: "The provider stopped serving the recording stream."
            showPlayerNotice(
                message = "Recording failed for $title. $detail",
                durationMs = maxOf(playerNoticeTimeoutMs, 8_000L)
            )
        }
    }

    val playerError: StateFlow<PlayerError?> by lazy(LazyThreadSafetyMode.NONE) {
        activeEngineFlowState(initialValue = null) { it.error }
    }

    val videoFormat: StateFlow<VideoFormat> = activeEngineState(VideoFormat(0, 0)) { it.videoFormat }

    val playerStats: StateFlow<com.streamvault.player.PlayerStats> =
        activeEngineState(com.streamvault.player.PlayerStats()) { it.playerStats }
    val availableAudioTracks: StateFlow<List<com.streamvault.player.PlayerTrack>> by lazy(LazyThreadSafetyMode.NONE) {
        activeEngineState(emptyList()) { it.availableAudioTracks }
    }
    val availableSubtitleTracks: StateFlow<List<com.streamvault.player.PlayerTrack>> by lazy(LazyThreadSafetyMode.NONE) {
        activeEngineState(emptyList()) { it.availableSubtitleTracks }
    }
    val availableVideoQualities: StateFlow<List<com.streamvault.player.PlayerTrack>> by lazy(LazyThreadSafetyMode.NONE) {
        activeEngineState(emptyList()) { it.availableVideoTracks }
    }
    val isMuted: StateFlow<Boolean> = activeEngineState(false) { it.isMuted }
    val mediaTitle: StateFlow<String?> = activeEngineState<String?>(null) { it.mediaTitle }
    val playbackSpeed: StateFlow<Float> = activeEngineState(1f) { it.playbackSpeed }
    val audioVideoSyncEnabled: StateFlow<Boolean> = activeEngineState(false) { it.audioVideoSyncEnabled }

    val preventStandbyDuringPlayback: StateFlow<Boolean> by lazy(LazyThreadSafetyMode.NONE) {
        playerPreferencesCoordinator.preventStandbyDuringPlayback
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), true)
    }

    internal fun beginPlaybackSession(): Long {
        val sessionId = playbackSessionCoordinator.begin().id
        lastTriggeredCreditsChapterStartMs = null
        creditsAutoPlayTriggeredForSession = false
        _skipChapter.value = null
        playerRecoveryCoordinator.beginSession(sessionId)
        playerRecoveryExecutionCoordinator.cancel()
        thumbnailPreloadJob?.cancel()
        preloadWindowJob?.cancel()
        preloadWindowJob = null
        preloadWindowInFlightFingerprint = null
        tokenRenewalJob?.cancel()
        zapBufferWatchdogJob?.cancel()
        stopLiveTranslationSession()
        lastRecordedVariantObservationSignature = null
        lastRecordedVodVariantObservationSignature = null
        livePlaybackReadyForCurrentSession = false
        readySideEffectsRequestVersion = null
        playerEngine.setScrubbingMode(false)
        return sessionId
    }

    internal fun clearPreloadWindow() {
        preloadWindowJob?.cancel()
        preloadWindowJob = null
        preloadWindowInFlightFingerprint = null
        acceptedPreloadWindowFingerprint = null
        playerEngine.clearPreloadWindow()
    }

    private data class PreloadWindowSnapshot(
        val providerId: Long,
        val current: PlayerPreloadItem,
        val neighborKeys: List<String>,
        val series: Series? = null,
        val episode: Episode? = null,
        val selectedProgram: Program? = null,
        val channel: com.streamvault.domain.model.Channel? = null,
        val timelinePrograms: List<Program> = emptyList()
    )

    private fun buildPreloadWindowSnapshot(): PreloadWindowSnapshot? {
        val streamInfo = currentResolvedStreamInfo ?: return null
        val providerId = currentProviderId.takeIf { it > 0L } ?: return null

        if (currentContentType == ContentType.SERIES_EPISODE) {
            val series = currentSeries.value ?: return null
            val episode = currentEpisode.value ?: return null
            val neighbors = buildEpisodePreloadNeighbors(series, episode) ?: return null
            val currentKey = episodePreloadKey(providerId, episode)
            return PreloadWindowSnapshot(
                providerId = providerId,
                current = PlayerPreloadItem(
                    key = currentKey,
                    streamInfo = streamInfo,
                    contentType = PlayerPreloadContentType.VOD
                ),
                neighborKeys = neighbors.map { episodePreloadKey(providerId, it) },
                series = series,
                episode = episode
            )
        }

        if (!isCatchUpPlayback()) return null
        val selectedProgram = playerPlaybackContextCoordinator.selectedCatchUpProgram ?: return null
        val channel = currentChannelFlow.value ?: return null
        val timelinePrograms = buildCatchUpPreloadTimeline(
            programHistory = programHistory.value,
            currentProgram = currentProgram.value,
            upcomingPrograms = upcomingPrograms.value,
            selectedProgram = selectedProgram
        )
        val neighbors = buildCatchUpPreloadNeighbors(
            selectedProgram = selectedProgram,
            channel = channel,
            timelinePrograms = timelinePrograms,
            now = System.currentTimeMillis()
        ) ?: return null
        return PreloadWindowSnapshot(
            providerId = providerId,
            current = PlayerPreloadItem(
                key = catchUpPreloadKey(providerId, channel, selectedProgram),
                streamInfo = streamInfo,
                contentType = PlayerPreloadContentType.CATCH_UP
            ),
            neighborKeys = neighbors.map { catchUpPreloadKey(providerId, channel, it) },
            selectedProgram = selectedProgram,
            channel = channel,
            timelinePrograms = timelinePrograms
        )
    }

    internal fun refreshPreloadWindow(requestVersion: Long) {
        if (!isActivePlaybackSession(requestVersion)) return
        val snapshot = buildPreloadWindowSnapshot() ?: return
        val fingerprint = buildPreloadWindowRefreshFingerprint(
            contentType = snapshot.current.contentType,
            providerId = snapshot.providerId,
            currentKey = snapshot.current.key,
            neighborKeys = snapshot.neighborKeys,
            currentStreamInfo = snapshot.current.streamInfo
        )
        if (
            fingerprint == acceptedPreloadWindowFingerprint ||
            fingerprint == preloadWindowInFlightFingerprint
        ) {
            return
        }

        preloadWindowJob?.cancel()
        preloadWindowInFlightFingerprint = fingerprint
        preloadWindowJob = playbackSessionScope(requestVersion)?.launch {
            val window = when {
                snapshot.series != null && snapshot.episode != null ->
                    playerPreloadWindowCoordinator.buildEpisodeWindow(
                        current = snapshot.current,
                        series = snapshot.series,
                        currentEpisode = snapshot.episode,
                        providerId = snapshot.providerId,
                        isCurrent = { isActivePlaybackSession(requestVersion) }
                    )

                snapshot.selectedProgram != null && snapshot.channel != null ->
                    playerPreloadWindowCoordinator.buildCatchUpWindow(
                        current = snapshot.current,
                        selectedProgram = snapshot.selectedProgram,
                        channel = snapshot.channel,
                        timelinePrograms = snapshot.timelinePrograms,
                        providerId = snapshot.providerId,
                        isCurrent = { isActivePlaybackSession(requestVersion) }
                    )

                else -> null
            }
            if (window == null || !isActivePlaybackSession(requestVersion)) {
                if (preloadWindowInFlightFingerprint == fingerprint) {
                    preloadWindowInFlightFingerprint = null
                }
                return@launch
            }
            playerEngine.preloadWindow(window)
            android.util.Log.i(
                "PlayerVM",
                "preload-window submitted kind=${snapshot.current.contentType} " +
                    "currentKey=${snapshot.current.key} sourceCount=${window.items.size}"
            )
            if (preloadWindowInFlightFingerprint == fingerprint) {
                acceptedPreloadWindowFingerprint = fingerprint
                preloadWindowInFlightFingerprint = null
            }
        }
    }

    /**
     * Launches work owned by a playback session. UI-only jobs should continue to use
     * [viewModelScope]; preparation, recovery, renewal, and other session work belongs here.
     */
    internal fun launchPlaybackSession(
        requestVersion: Long = prepareRequestVersion,
        block: suspend () -> Unit
    ): Job? = playbackSessionCoordinator.launch(requestVersion, block)

    internal fun playbackSessionScope(requestVersion: Long = prepareRequestVersion): CoroutineScope? =
        playbackSessionCoordinator.scope(requestVersion)

    internal fun clearSeriesEpisodeContext() {
        currentSeriesId = null
        currentSeasonNumber = null
        currentEpisodeNumber = null
        currentStableEpisodeId = null
        _currentSeries.value = null
        _currentEpisode.value = null
        _nextEpisode.value = null
        cancelAutoPlay()
    }

    private suspend fun loadSeriesEpisodeContext(
        requestVersion: Long,
        providerId: Long,
        seriesId: Long,
        episodeId: Long,
        seasonNumber: Int?,
        episodeNumber: Int?
    ): Episode? {
        return when (val result = playerContentResolver.getSeriesDetails(providerId, seriesId)) {
            is Result.Success -> {
                if (!isActivePlaybackSession(requestVersion)) return null
                val series = result.data.sanitizedForPlayer()
                val resolution = buildSeriesEpisodeResolution(
                    series = series,
                    episodeId = episodeId,
                    seasonNumber = seasonNumber,
                    episodeNumber = episodeNumber,
                    currentContentType = currentContentType,
                    currentArtworkUrl = currentArtworkUrl
                )
                _currentSeries.value = series
                _currentEpisode.value = resolution.resolvedEpisode
                _nextEpisode.value = resolution.nextEpisode
                currentSeriesId = seriesId
                currentSeasonNumber = resolution.resolvedSeasonNumber
                currentEpisodeNumber = resolution.resolvedEpisodeNumber
                if (resolution.resolvedEpisode != null && currentContentType == ContentType.SERIES_EPISODE) {
                    currentArtworkUrl = resolution.resolvedArtworkUrl
                    currentTitle = resolution.resolvedTitle ?: currentTitle
                    playbackTitleFlow.value = currentTitle
                    resolution.resolvedEpisode.id.takeIf { it > 0L }?.let { resolvedId ->
                        if (currentContentId != resolvedId) {
                            currentContentId = resolvedId
                        }
                    }
                    if (currentResolvedStreamInfo != null) {
                        refreshPreloadWindow(requestVersion)
                    }
                }
                resolution.resolvedEpisode
            }

            else -> {
                if (!isActivePlaybackSession(requestVersion)) return null
                _currentSeries.value = null
                _currentEpisode.value = null
                _nextEpisode.value = null
                currentSeriesId = seriesId
                currentSeasonNumber = seasonNumber
                currentEpisodeNumber = episodeNumber
                null
            }
        }
    }

    internal fun buildPlaybackHistorySnapshot(
        positionMs: Long,
        durationMs: Long
    ): PlaybackHistory? = buildPlaybackHistorySnapshot(
        positionMs = positionMs,
        durationMs = durationMs,
        currentContentId = currentContentId,
        currentProviderId = currentProviderId,
        currentContentType = currentContentType,
        currentTitle = currentTitle,
        currentArtworkUrl = currentArtworkUrl,
        currentStreamUrl = currentStreamUrl,
        currentSeriesId = currentSeriesId,
        currentEpisode = _currentEpisode.value,
        currentSeasonNumber = currentSeasonNumber,
        currentEpisodeNumber = currentEpisodeNumber
    )

    internal fun isActivePlaybackSession(
        requestVersion: Long,
        expectedLogicalUrl: String? = null
    ): Boolean = playbackSessionCoordinator.isCurrent(requestVersion) && matchesActivePlaybackSession(
        requestVersion = requestVersion,
        activeRequestVersion = prepareRequestVersion,
        expectedLogicalUrl = expectedLogicalUrl,
        currentResolvedPlaybackUrl = currentResolvedPlaybackUrl,
        currentStreamUrl = currentStreamUrl
    )

    internal fun requestEpg(
        providerId: Long,
        epgChannelId: String?,
        streamId: Long = 0L,
        internalChannelId: Long = 0L
    ) {
        val normalizedChannelId = epgChannelId?.trim()?.takeIf { it.isNotEmpty() }
        if (providerId <= 0L || (internalChannelId <= 0L && normalizedChannelId == null && streamId <= 0L)) {
            fetchEpg(providerId = -1L, internalChannelId = 0L, epgChannelId = null)
            return
        }

        val key = EpgRequestKey(
            providerId = providerId,
            internalChannelId = internalChannelId,
            epgChannelId = normalizedChannelId,
            streamId = streamId.takeIf { it > 0L } ?: 0L
        )
        fetchEpg(
            providerId = key.providerId,
            internalChannelId = key.internalChannelId,
            epgChannelId = key.epgChannelId,
            streamId = key.streamId
        )
    }

    internal suspend fun preparePlayer(
        streamInfo: com.streamvault.domain.model.StreamInfo,
        requestVersion: Long,
        probeBeforePlayback: Boolean = true,
        showFailureNotice: Boolean = true
    ): Boolean {
        if (!isActivePlaybackSession(requestVersion)) return false

        activeVodTrackPreferences = playerPreparationCoordinator.resolveVodTrackPreferences(
            contentType = currentContentType,
            providerId = currentProviderId,
            contentId = currentContentId,
            seriesId = currentSeriesId
        )

        val preparationResult = playerPreparationCoordinator.prepare(
            request = PlayerPreparationCoordinator.Request(
                streamInfo = streamInfo,
                contentType = currentContentType,
                providerId = currentProviderId,
                currentStreamUrl = currentStreamUrl,
                probePassedPlaybackKeys = probePassedPlaybackKeys.toSet(),
                probeBeforePlayback = probeBeforePlayback,
                audioVideoOffsetMs = _audioVideoOffsetUiState.value.effectiveOffsetMs,
                vodTrackPreferences = activeVodTrackPreferences
            ),
            isCurrent = { isActivePlaybackSession(requestVersion) }
        ) ?: return false

        if (preparationResult is PlayerPreparationCoordinator.Result.Failure) {
            if (!isActivePlaybackSession(requestVersion)) return false
            setLastFailureReason(preparationResult.message)
            if (showFailureNotice) {
                showPlayerNotice(
                    message = preparationResult.message,
                    recoveryType = preparationResult.recoveryType,
                    actions = buildRecoveryActions(preparationResult.recoveryType)
                )
            }
            return false
        }

        val success = preparationResult as PlayerPreparationCoordinator.Result.Success
        success.probeCacheKey?.let(probePassedPlaybackKeys::add)
        if (!isActivePlaybackSession(requestVersion)) return false
        currentResolvedPlaybackUrl = success.streamInfo.url
        currentResolvedStreamInfo = success.streamInfo
        readySideEffectsRequestVersion = requestVersion
        refreshLiveTranslationAvailability()
        startTokenRenewalMonitoring(success.streamInfo.expirationTime)
        maybeStartLiveTimeshift(success.streamInfo)
        return true
    }

    fun prepare(
        streamUrl: String, 
        epgChannelId: String?, 
        internalChannelId: Long, 
        categoryId: Long = -1, 
        providerId: Long = -1, 
        isVirtual: Boolean = false,
        combinedProfileId: Long? = null,
        combinedSourceFilterProviderId: Long? = null,
        contentType: String = "CHANNEL",
        title: String = "",
        artworkUrl: String? = null,
        archiveStartMs: Long? = null,
        archiveEndMs: Long? = null,
        archiveTitle: String? = null,
        seriesId: Long? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeId: Long? = null,
        showResumePrompt: Boolean = true
    ) {
        val hasArchiveRequest = hasArchivePlaybackIdentity(
            contentType = contentType,
            archiveStartMs = archiveStartMs,
            archiveEndMs = archiveEndMs
        )
        _playbackResolutionUiState.value = if (
            hasArchiveRequest || contentType.equals(ContentType.LIVE.name, ignoreCase = true)
        ) {
            PlaybackResolutionUiState.Idle
        } else {
            PlaybackResolutionUiState.Resolving
        }
        val contentSwitchFlush = queueContentSwitchProgressFlush()
        val requestVersion = beginPlaybackSession()
        val shouldReloadPlaylist = applyPrepareSessionState(
            streamUrl = streamUrl,
            internalChannelId = internalChannelId,
            categoryId = categoryId,
            providerId = providerId,
            combinedProfileId = combinedProfileId,
            combinedSourceFilterProviderId = combinedSourceFilterProviderId,
            contentType = contentType,
            title = title,
            artworkUrl = artworkUrl,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeId = episodeId,
            hasArchiveRequest = hasArchiveRequest,
            preferredAudioDecoderMode = preferredAudioDecoderMode,
            preferredVideoDecoderMode = preferredVideoDecoderMode,
            preferredSurfaceMode = preferredSurfaceMode
        )

        if (!hasArchiveRequest) {
            playbackSessionScope(requestVersion)?.launch {
                contentSwitchFlush?.join()
                if (!isActivePlaybackSession(requestVersion)) return@launch
                if (playerPreviewCoordinator.tryAdoptFullscreenHandoff(
                        channelId = internalChannelId,
                        providerId = providerId,
                        contentType = currentContentType,
                        audioVideoOffsetMs = _audioVideoOffsetUiState.value.effectiveOffsetMs,
                        isCurrent = { isActivePlaybackSession(requestVersion) },
                        onAdopted = { streamInfo ->
                            currentResolvedPlaybackUrl = streamInfo.url
                            currentResolvedStreamInfo = streamInfo
                            readySideEffectsRequestVersion = requestVersion
                            probePassedPlaybackKeys.add(
                                resolvePlaybackProbeCacheKey(
                                    currentStreamUrl = currentStreamUrl,
                                    url = streamInfo.url
                                )
                            )
                        },
                        onStarted = { streamInfo ->
                            startTokenRenewalMonitoring(streamInfo.expirationTime)
                            maybeStartLiveTimeshift(streamInfo)
                        }
                    )) {
                    return@launch
                }
                var playbackLogicalUrl = streamUrl
                var playbackContentId = internalChannelId
                if (currentContentType == ContentType.SERIES_EPISODE && providerId > 0 && currentSeriesId != null) {
                    val providerType = playerProviderCoordinator.getProvider(providerId)?.type
                    val shouldAwaitRefreshedEpisode = providerType == ProviderType.STALKER_PORTAL ||
                        playerContentResolver.isInternalStreamUrl(streamUrl)
                    if (shouldAwaitRefreshedEpisode) {
                        val resolvedEpisode = loadSeriesEpisodeContext(
                            requestVersion = requestVersion,
                            providerId = providerId,
                            seriesId = currentSeriesId ?: -1L,
                            episodeId = currentStableEpisodeId?.takeIf { it > 0 } ?: internalChannelId,
                            seasonNumber = seasonNumber,
                            episodeNumber = episodeNumber
                        )
                        if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                        resolvedEpisode?.streamUrl
                            ?.takeIf { it.isNotBlank() }
                            ?.let { refreshedUrl -> playbackLogicalUrl = refreshedUrl }
                        resolvedEpisode?.id
                            ?.takeIf { it > 0L }
                            ?.let { resolvedId -> playbackContentId = resolvedId }
                    } else {
                        launch {
                            loadSeriesEpisodeContext(
                                requestVersion = requestVersion,
                                providerId = providerId,
                                seriesId = currentSeriesId ?: -1L,
                                episodeId = currentStableEpisodeId?.takeIf { it > 0 } ?: internalChannelId,
                                seasonNumber = seasonNumber,
                                episodeNumber = episodeNumber
                            )
                        }
                    }
                }
                val resolution = resolvePlaybackStreamResolution(
                    playbackLogicalUrl,
                    playbackContentId,
                    providerId,
                    currentContentType
                )
                if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                val streamInfo = resolution.streamInfo
                if (streamInfo == null) {
                    if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                    resolution.failureMessage?.let(::setLastFailureReason)
                    _playbackResolutionUiState.value = PlaybackResolutionUiState.Failure(
                        resolution.failureMessage
                            ?: "No playable stream URL was available."
                    )
                    return@launch
                }
                _playbackResolutionUiState.value = PlaybackResolutionUiState.Idle
                currentStreamUrl = playbackLogicalUrl
                currentContentId = playbackContentId
                if (!isActivePlaybackSession(requestVersion, playbackLogicalUrl)) return@launch
                if (!preparePlayer(streamInfo, requestVersion)) return@launch

                // Check for resume position after the player is fully prepared (VOD only).
                // Doing this after preparePlayer ensures pause() acts on the live player instance,
                // not a stale one that may have already been replaced by prepareInternal().
                if (showResumePrompt && currentContentType != ContentType.LIVE && currentContentId != -1L && currentProviderId != -1L) {
                    val history = playbackHistoryCoordinator.getPlaybackHistory(
                        contentId = currentContentId,
                        contentType = currentContentType,
                        providerId = currentProviderId,
                        seriesId = currentSeriesId,
                        seasonNumber = currentSeasonNumber,
                        episodeNumber = currentEpisodeNumber
                    )
                    if (isActivePlaybackSession(requestVersion, playbackLogicalUrl)) {
                        if (history != null && history.resumePositionMs > 5000L && !isPlaybackComplete(history.resumePositionMs, history.totalDurationMs)) {
                            playerEngine.pause()
                            _resumePrompt.value = ResumePromptState(
                                show = true,
                                positionMs = history.resumePositionMs,
                                title = currentTitle
                            )
                        }
                    }
                }
            }
        }
        
        // Show context info on entry for both Live and VOD
        openChannelInfoOverlay()

        if (providerId > 0) {
            playbackSessionScope(requestVersion)?.launch {
                playerProviderCoordinator.getProvider(providerId)?.let { provider ->
                    _playerDiagnostics.update {
                        it.copy(
                            providerName = provider.name,
                            providerSourceLabel = when (provider.type) {
                                com.streamvault.domain.model.ProviderType.XTREAM_CODES -> "Xtream Codes"
                                com.streamvault.domain.model.ProviderType.M3U -> "M3U Playlist"
                                com.streamvault.domain.model.ProviderType.STALKER_PORTAL -> "Stalker/MAG Portal"
                                com.streamvault.domain.model.ProviderType.JELLYFIN -> "Jellyfin"
                            }
                        )
                    }
                }
            }
        } else {
            _playerDiagnostics.update { it.copy(providerName = "", providerSourceLabel = "") }
        }
        
        finalizePreparedPlaybackContext(
            requestVersion = requestVersion,
            streamUrl = streamUrl,
            providerId = providerId,
            categoryId = categoryId,
            isVirtual = isVirtual,
            internalChannelId = internalChannelId,
            epgChannelId = epgChannelId,
            shouldReloadPlaylist = shouldReloadPlaylist,
            hasArchiveRequest = hasArchiveRequest,
            archiveStartMs = archiveStartMs,
            archiveEndMs = archiveEndMs,
            archiveTitle = archiveTitle,
            contentSwitchFlush = contentSwitchFlush
        )
    }

    fun updatePreparedRouteMetadata(
        title: String,
        artworkUrl: String?,
        contentType: String,
        providerId: Long,
        internalChannelId: Long,
        archiveStartMs: Long?,
        archiveEndMs: Long?,
        archiveTitle: String?,
        seriesId: Long?,
        seasonNumber: Int?,
        episodeNumber: Int?
    ) {
        currentTitle = resolveRouteDisplayTitle(
            title = title,
            contentType = contentType,
            archiveStartMs = archiveStartMs,
            archiveEndMs = archiveEndMs,
            archiveTitle = archiveTitle
        )
        playbackTitleFlow.value = currentTitle
        currentArtworkUrl = artworkUrl

        if (prepareRequestVersion <= 0L) return

        val normalizedSeriesId = seriesId?.takeIf { it > 0L }
        val resolvedContentType = try {
            ContentType.valueOf(contentType)
        } catch (_: Exception) {
            ContentType.LIVE
        }
        if (resolvedContentType != ContentType.SERIES_EPISODE || providerId <= 0L || internalChannelId <= 0L) {
            return
        }

        val requestVersion = prepareRequestVersion
        playbackSessionScope(requestVersion)?.launch {
            val identity = resolveSeriesEpisodeIdentity(
                providerId = providerId,
                internalChannelId = internalChannelId,
                seriesId = normalizedSeriesId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                lookupEpisode = playerContentResolver::getEpisodeById
            ) ?: return@launch
            if (
                identity.seriesId == currentSeriesId &&
                identity.seasonNumber == currentSeasonNumber &&
                identity.episodeNumber == currentEpisodeNumber
            ) {
                return@launch
            }
            loadSeriesEpisodeContext(
                requestVersion = requestVersion,
                providerId = providerId,
                seriesId = identity.seriesId,
                episodeId = internalChannelId,
                seasonNumber = identity.seasonNumber,
                episodeNumber = identity.episodeNumber
            )
        }
    }

    internal fun applyProgramTimeline(programs: List<Program>, now: Long) {
        guideTimelineCoordinator.apply(
            programs = programs,
            now = now,
            channel = currentChannelFlow.value
        )
    }

    internal fun clearEpgState() {
        guideTimelineCoordinator.clear()
    }

    // Store current URL to find index later
    internal var currentStreamUrl: String
        get() = playerPlaybackContextCoordinator.currentStreamUrl
        set(value) {
            playerPlaybackContextCoordinator.currentStreamUrl = value
        }

    private fun resolveCurrentLiveChannelIndex(): Int {
        currentChannelIndex = resolveLiveChannelIndex(
            channelList = channelList,
            currentChannelIndex = currentChannelIndex,
            currentContentId = currentContentId,
            currentStreamUrl = currentStreamUrl
        )
        return currentChannelIndex
    }

    internal fun wrappedChannelIndex(offset: Int): Int {
        val resolvedIndex = resolveCurrentLiveChannelIndex()
        return computeWrappedChannelIndex(
            resolvedIndex = resolvedIndex,
            channelCount = channelList.size,
            offset = offset
        )
    }

    internal fun markStreamFailure(streamUrl: String) {
        playerRecoveryCoordinator.markStreamFailure(streamUrl)
    }

    internal fun updateDecoderModes(audioMode: DecoderMode, videoMode: DecoderMode) {
        _playerDiagnostics.update {
            it.copy(
                audioDecoderMode = audioMode,
                videoDecoderMode = videoMode
            )
        }
    }

    internal fun updateStreamClass(label: String) {
        currentStreamClassLabel = label
        _isCatchUpPlayback.value = (label == "Catch-up")
        _playerDiagnostics.update { it.copy(streamClassLabel = label) }
    }

    internal fun updateChannelDiagnostics(channel: com.streamvault.domain.model.Channel) {
        _playerDiagnostics.update { currentState ->
            updateChannelDiagnosticsState(
                currentState = currentState,
                channel = channel
            )
        }
    }

    internal fun setLastFailureReason(message: String?) {
        _playerDiagnostics.update { it.copy(lastFailureReason = message?.takeIf { reason -> reason.isNotBlank() }) }
    }

    internal fun appendRecoveryAction(action: String) {
        if (action.isBlank()) return
        _playerDiagnostics.update { state ->
            state.copy(recentRecoveryActions = (listOf(action) + state.recentRecoveryActions).distinct().take(5))
        }
    }

    internal suspend fun resolvePlaybackStreamResolution(
        logicalUrl: String,
        internalContentId: Long,
        providerId: Long,
        contentType: ContentType
    ): PlayerPlaybackStreamResolution = playerContentResolver.resolvePlaybackStream(
            logicalUrl = logicalUrl,
            internalContentId = internalContentId,
            providerId = providerId,
            contentType = contentType,
            currentTitle = currentTitle,
            currentSeries = currentSeries.value,
            currentEpisode = currentEpisode.value
        )

    internal suspend fun resolvePlaybackStreamInfo(
        logicalUrl: String,
        internalContentId: Long,
        providerId: Long,
        contentType: ContentType
    ): com.streamvault.domain.model.StreamInfo? {
        val resolution = resolvePlaybackStreamResolution(
            logicalUrl = logicalUrl,
            internalContentId = internalContentId,
            providerId = providerId,
            contentType = contentType
        )
        resolution.failureMessage?.let { failureMessage ->
            setLastFailureReason(failureMessage)
        }
        if (resolution.streamInfo == null) {
            return null
        }
        return resolution.streamInfo
    }

    internal suspend fun resolvePlaybackUrl(
        logicalUrl: String,
        internalContentId: Long,
        providerId: Long,
        contentType: ContentType
    ): String? = resolvePlaybackStreamInfo(
        logicalUrl = logicalUrl,
        internalContentId = internalContentId,
        providerId = providerId,
        contentType = contentType
    )?.url

    override fun onCleared() {
        playbackSessionCoordinator.invalidate()
        super.onCleared()
        cleanupAfterCleared(playerEngineCoordinator.mainEngine)
    }
}
