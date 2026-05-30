package com.streamvault.app.ui.screens.player

import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.app.cast.CastConnectionState
import com.streamvault.app.cast.CastManager
import com.streamvault.app.cast.CastMediaRequest
import com.streamvault.app.cast.CastStartResult
import com.streamvault.app.di.MainPlayerEngine
import com.streamvault.app.player.LivePreviewHandoffManager
import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.app.util.isPlaybackComplete
import com.streamvault.app.tv.LauncherRecommendationsManager
import com.streamvault.app.tv.WatchNextManager
import com.streamvault.data.remote.stalker.StalkerUrlFactory
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.security.CredentialDecryptionException
import com.streamvault.data.sync.SyncManager
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ChannelNumberingMode
import com.streamvault.domain.model.CombinedCategory
import com.streamvault.domain.model.CombinedM3uProfileMember
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.LiveChannelObservedQuality
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.RecordingItem
import com.streamvault.domain.model.RecordingRecurrence
import com.streamvault.domain.model.RecordingRequest
import com.streamvault.domain.model.RecordingStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.usecase.GetCustomCategories
import com.streamvault.domain.usecase.MarkAsWatched
import com.streamvault.domain.usecase.ScheduleRecording
import com.streamvault.domain.usecase.ScheduleRecordingCommand
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.player.Media3PlayerEngine
import com.streamvault.player.AUDIO_VIDEO_OFFSET_MAX_MS
import com.streamvault.player.AUDIO_VIDEO_OFFSET_MIN_MS
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerError
import com.streamvault.player.PlayerSubtitleStyle
import com.streamvault.player.timeshift.LiveTimeshiftBackend
import com.streamvault.player.timeshift.LiveTimeshiftState
import com.streamvault.player.timeshift.LiveTimeshiftStatus
import com.streamvault.player.timeshift.TimeshiftConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import okhttp3.OkHttpClient
import okhttp3.Request

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModel @Inject constructor(
    @param:MainPlayerEngine
    private val mainPlayerEngine: PlayerEngine,
    internal val epgRepository: EpgRepository,
    internal val channelRepository: ChannelRepository,
    internal val movieRepository: MovieRepository,
    private val seriesRepository: SeriesRepository,
    internal val favoriteRepository: com.streamvault.domain.repository.FavoriteRepository,
    internal val playbackHistoryRepository: PlaybackHistoryRepository,
    internal val providerRepository: com.streamvault.domain.repository.ProviderRepository,
    internal val combinedM3uRepository: CombinedM3uRepository,
    internal val preferencesRepository: com.streamvault.data.preferences.PreferencesRepository,
    internal val getCustomCategories: GetCustomCategories,
    internal val markAsWatched: MarkAsWatched,
    internal val scheduleRecordingUseCase: ScheduleRecording,
    internal val recordingManager: RecordingManager,
    internal val watchNextManager: WatchNextManager,
    internal val launcherRecommendationsManager: LauncherRecommendationsManager,
    internal val castManager: CastManager,
    internal val pluginManager: StreamVaultPluginManager,
    internal val xtreamStreamUrlResolver: XtreamStreamUrlResolver,
    internal val seekThumbnailProvider: SeekThumbnailProvider,
    internal val livePreviewHandoffManager: LivePreviewHandoffManager,
    internal val syncManager: SyncManager,
    private val okHttpClient: OkHttpClient,
) : ViewModel() {
    companion object {
        private const val TAG = "PlayerViewModel"
        private const val MAX_PROGRAM_HISTORY_ITEMS = 18
        private const val MAX_UPCOMING_PROGRAM_ITEMS = 24
        private const val PLAYER_EPG_REFRESH_INTERVAL_MS = 30_000L
        private const val MIN_WATCHED_FOR_AUTO_PLAY_MS = 5_000L
        private const val AUTO_PLAY_COUNTDOWN_SECONDS = 10
        private const val TOKEN_RENEWAL_LEAD_MS = 60_000L
        private const val TOKEN_RENEWAL_CHECK_INTERVAL_MS = 10_000L
        private const val LOW_BANDWIDTH_THRESHOLD_BPS = 500_000L
        private const val LOW_BANDWIDTH_DURATION_SECONDS = 30
        internal val PLAYBACK_TIMER_PRESETS_MINUTES = setOf(0, 15, 30, 45, 60, 90, 120)
        internal const val TIMER_TICK_MS = 1_000L
    }

    private val activePlayerEngineFlow = MutableStateFlow(mainPlayerEngine)
    val activePlayerEngine: StateFlow<PlayerEngine> = activePlayerEngineFlow.asStateFlow()
    val playerEngine: PlayerEngine
        get() = activePlayerEngineFlow.value

    internal val showControlsFlow = MutableStateFlow(false)
    val showControls: StateFlow<Boolean> = showControlsFlow.asStateFlow()

    private val _isCatchUpPlayback = MutableStateFlow(false)
    val isCatchUpPlayback: StateFlow<Boolean> = _isCatchUpPlayback.asStateFlow()

    internal val showZapOverlayFlow = MutableStateFlow(false)
    val showZapOverlay: StateFlow<Boolean> = showZapOverlayFlow.asStateFlow()
    
    private val _currentProgram = MutableStateFlow<Program?>(null)
    val currentProgram: StateFlow<Program?> = _currentProgram.asStateFlow()

    private val _nextProgram = MutableStateFlow<Program?>(null)
    val nextProgram: StateFlow<Program?> = _nextProgram.asStateFlow()

    private val _programHistory = MutableStateFlow<List<Program>>(emptyList())
    val programHistory: StateFlow<List<Program>> = _programHistory.asStateFlow()

    private val _upcomingPrograms = MutableStateFlow<List<Program>>(emptyList())
    val upcomingPrograms: StateFlow<List<Program>> = _upcomingPrograms.asStateFlow()

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

    internal var channelInfoHideJob: Job? = null
    internal var liveOverlayHideJob: Job? = null
    internal var diagnosticsHideJob: Job? = null
    internal var numericInputCommitJob: Job? = null
    internal var numericInputFeedbackJob: Job? = null
    internal var playerNoticeHideJob: Job? = null
    internal var mutePersistJob: Job? = null
    private var recoveryJob: Job? = null
    internal var numericInputBuffer: String = ""
    internal val triedAlternativeStreams = mutableSetOf<String>()
    internal val failedStreamsThisSession = mutableMapOf<String, Int>()
    internal var hasRetriedWithSoftwareDecoder = false
    internal var hasRetriedXtreamAuthRefresh = false
    internal val probePassedPlaybackKeys = mutableSetOf<String>()
    internal val livePreloadCooldownProviderIds = mutableSetOf<Long>()
    private val notifiedRecordingFailureIds = mutableSetOf<String>()
    internal var lastRecordedLivePlaybackKey: Pair<Long, Long>? = null
    private var currentStreamClassLabel: String = "Primary"
    internal var lastRecordedVariantObservationSignature: String? = null
    internal var prepareRequestVersion: Long = 0L
    internal var readySideEffectsRequestVersion: Long? = null
    internal var currentArtworkUrl: String? = null
    internal var currentResolvedPlaybackUrl: String = ""
    internal var currentResolvedStreamInfo: StreamInfo? = null
    internal var pendingCatchUpUrls: List<String> = emptyList()
    internal var channelNumberingMode: ChannelNumberingMode = ChannelNumberingMode.GROUP
        set(value) {
            field = value
            rebuildChannelNumberIndex()
        }
    internal var activeEpgRequestKey: EpgRequestKey? = null
    internal var playerControlsTimeoutMs: Long = 5_000L
    internal var liveOverlayTimeoutMs: Long = 4_000L
    internal var playerNoticeTimeoutMs: Long = 6_000L
    internal var diagnosticsTimeoutMs: Long = 15_000L
    private var preferredDecoderMode: DecoderMode = DecoderMode.AUTO
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
     * collector. Linked to [currentChannelIndex] — see invariant comment above.
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
    internal var currentProviderId: Long = -1L
    internal var currentCombinedProfileId: Long? = null
    internal var currentCombinedSourceFilterProviderId: Long? = null
    internal var currentContentId: Long = -1L
    internal var currentContentType: ContentType = ContentType.LIVE
    internal var currentTitle: String = ""
    internal var currentSeriesId: Long? = null
    internal var currentSeasonNumber: Int? = null
    internal var currentEpisodeNumber: Int? = null
    internal var currentStableEpisodeId: Long? = null
    internal var isVirtualCategory: Boolean = false
    internal var currentCombinedProfileMembers: List<CombinedM3uProfileMember> = emptyList()
    internal var combinedCategoriesById: Map<Long, CombinedCategory> = emptyMap()
    private var lastObservedPlaybackState: PlaybackState = PlaybackState.IDLE

    internal var epgJob: Job? = null
    internal var playlistJob: Job? = null
    internal var recentChannelsJob: Job? = null
    internal var lastVisitedCategoryJob: Job? = null
    internal var controlsHideJob: Job? = null
    internal var seekPreviewJob: Job? = null
    internal var thumbnailPreloadJob: Job? = null
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

    val castConnectionState: StateFlow<CastConnectionState> = castManager.connectionState

    private fun setActivePlayerEngine(engine: PlayerEngine) {
        if (activePlayerEngineFlow.value === engine) return
        // Media3 session IDs must be globally unique. Release the outgoing engine's
        // MediaSession before the incoming engine's session is created by the combine
        // flow at the bottom of init. Mirrors the explicit release done in the forward
        // handoff path (mainPlayerEngine.setMediaSessionEnabled(false)) for the reverse.
        activePlayerEngineFlow.value.setMediaSessionEnabled(false)
        activePlayerEngineFlow.value = engine
    }

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
        if (currentContentType != ContentType.LIVE || !timeshiftConfig.enabled) {
            playerEngine.stopLiveTimeshift()
            return
        }
        if (currentStreamClassLabel == "Catch-up") {
            playerEngine.stopLiveTimeshift()
            return
        }
        val streamInfo = resolveTimeshiftStreamInfo(
            streamInfoOverride = streamInfoOverride,
            currentResolvedStreamInfo = currentResolvedStreamInfo,
            currentResolvedPlaybackUrl = currentResolvedPlaybackUrl,
            currentStreamUrl = currentStreamUrl,
            playbackTitle = playbackTitleFlow.value,
            currentTitle = currentTitle
        ) ?: run {
            playerEngine.stopLiveTimeshift()
            _timeshiftUiState.update {
                it.copy(
                    available = false,
                    enabledForSession = timeshiftConfig.enabled,
                    statusMessage = "Local live rewind is unavailable for this stream."
                )
            }
            return
        }
        val fallbackUrl = streamInfo.url
        val channelKey = currentChannel.value?.id?.toString()
            ?: currentContentId.takeIf { it > 0L }?.toString()
            ?: fallbackUrl
        _timeshiftUiState.update {
            it.copy(
                available = true,
                enabledForSession = true,
                statusMessage = "Preparing local live rewind…",
                bufferDepthMs = timeshiftConfig.depthMs
            )
        }
        playerEngine.startLiveTimeshift(streamInfo, channelKey, timeshiftConfig)
    }

    init {
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
                    if (currentContentType == ContentType.LIVE) {
                        recordActiveLivePlayback()
                        currentChannelFlow.value?.sanitizedForPlayer()?.let { channel ->
                            if (channel.errorCount > 0) {
                                logRepositoryFailure(
                                    operation = "Reset channel error count",
                                    result = channelRepository.resetChannelErrorCount(channel.id)
                                )
                            }
                        }
                    } else {
                        startThumbnailPreload()
                    }
                }
            }
        }
        viewModelScope.launch {
            activePlayerEngineFlow
                .flatMapLatest { it.isPlaying }
                .distinctUntilChanged()
                .collect { isPlaying ->
                    synchronizeStalkerPlaybackFetchDeferral(isPlaying)
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
            recordingManager.observeRecordingItems().collect { items ->
                handleRecordingStateChanges(previousItems = _recordingItems.value, newItems = items)
                _recordingItems.value = items
                refreshCurrentChannelRecording(items)
            }
        }
        viewModelScope.launch {
            combine(
                preferencesRepository.playerSubtitleTextScale,
                preferencesRepository.playerSubtitleTextColor,
                preferencesRepository.playerSubtitleBackgroundColor
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
            combine(
                preferencesRepository.playerControlsTimeoutSeconds,
                preferencesRepository.playerLiveOverlayTimeoutSeconds,
                preferencesRepository.playerNoticeTimeoutSeconds,
                preferencesRepository.playerDiagnosticsTimeoutSeconds
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
            preferencesRepository.zapAutoRevert.collect { zapAutoRevertEnabled = it }
        }
        viewModelScope.launch {
            preferencesRepository.autoPlayNextEpisode.collect { autoPlayNextEpisodeEnabled = it }
        }
        viewModelScope.launch {
            preferencesRepository.parentalControlLevel.collect { parentalControlLevelFlow.value = it }
        }
        viewModelScope.launch {
            preferencesRepository.defaultStopPlaybackTimerMinutes.collect {
                defaultStopPlaybackTimerMinutes = sanitizePlaybackTimerMinutes(it, PLAYBACK_TIMER_PRESETS_MINUTES)
            }
        }
        viewModelScope.launch {
            preferencesRepository.defaultIdleStandbyTimerMinutes.collect {
                defaultIdleStandbyTimerMinutes = sanitizePlaybackTimerMinutes(it, PLAYBACK_TIMER_PRESETS_MINUTES)
            }
        }
        viewModelScope.launch {
            preferencesRepository.playerMediaSessionEnabled
                .combine(activePlayerEngineFlow) { enabled, engine -> engine to enabled }
                .collect { (engine, enabled) -> engine.setMediaSessionEnabled(enabled) }
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
                        preferencesRepository.observeAudioVideoOffsetForChannel(channelId)
                    }
                }

            combine(
                preferencesRepository.playerAudioVideoOffsetMs,
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
                preferencesRepository.playerTimeshiftEnabled,
                preferencesRepository.playerTimeshiftDepthMinutes
            ) { enabled, depthMinutes ->
                TimeshiftConfig(enabled = enabled, depthMinutes = depthMinutes)
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
            preferencesRepository.playerDecoderMode
                .combine(activePlayerEngineFlow) { mode, engine -> engine to mode }
                .collect { (engine, mode) ->
                    preferredDecoderMode = mode
                    if (!hasRetriedWithSoftwareDecoder) {
                        engine.setDecoderMode(mode)
                        if (engine === playerEngine) {
                            updateDecoderMode(mode)
                        }
                    }
                }
        }
        viewModelScope.launch {
            preferencesRepository.playerAudioOutputPreference
                .combine(activePlayerEngineFlow) { preference, engine -> engine to preference }
                .collect { (engine, preference) ->
                    engine.setAudioOutputPreference(preference)
                }
        }
        viewModelScope.launch {
            preferencesRepository.playerCompatibilityMemoryEnabled
                .combine(activePlayerEngineFlow) { enabled, engine -> engine to enabled }
                .collect { (engine, enabled) ->
                    engine.setCompatibilityMemoryEnabled(enabled)
                }
        }
        viewModelScope.launch {
            preferencesRepository.playerSurfaceMode
                .combine(activePlayerEngineFlow) { mode, engine -> engine to mode }
                .collect { (engine, mode) ->
                    preferredSurfaceMode = mode
                    engine.setSurfaceMode(mode)
                }
        }
        viewModelScope.launch {
            preferencesRepository.playerVodHttpProtocolMode
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
        recoveryJob?.cancel()
        if (error is PlayerError.DecoderError && !hasRetriedWithSoftwareDecoder) {
            if (!isActivePlaybackSession(requestVersion, playbackUrl)) return
            hasRetriedWithSoftwareDecoder = true
            android.util.Log.w("PlayerVM", "Decoder error detected. Retrying with software decoder mode.")
            playerEngine.setDecoderMode(DecoderMode.SOFTWARE)
            updateDecoderMode(DecoderMode.SOFTWARE)
            setLastFailureReason(error.message)
            appendRecoveryAction("Switched to software decoder")
            playerEngine.play()
            showPlayerNotice(
                message = "Retrying with software decoding for this stream.",
                recoveryType = PlayerRecoveryType.DECODER,
                actions = buildRecoveryActions(PlayerRecoveryType.DECODER)
            )
            return
        }
        // After software decoder retry fails, also try an alternate stream format
        // (e.g. HLS→MPEG-TS or MPEG-TS→HLS) before giving up.
        if (error is PlayerError.DecoderError && hasRetriedWithSoftwareDecoder) {
            if (!isActivePlaybackSession(requestVersion, playbackUrl)) return
            val channel = currentChannelFlow.value?.sanitizedForPlayer() ?: return
            val switched = tryAlternateStreamInternal(channel)
            if (switched) {
                appendRecoveryAction("Trying alternate stream format after decoder error")
                showPlayerNotice(
                    message = "Trying alternate stream format for ${channel.name}.",
                    recoveryType = PlayerRecoveryType.DECODER,
                    actions = buildRecoveryActions(PlayerRecoveryType.DECODER),
                    isRetryNotice = true
                )
                return
            }
        }
        recoveryJob = viewModelScope.launch {
            if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
            if (tryRefreshXtreamPlaybackAfterAuthError(error, requestVersion, playbackUrl)) {
                return@launch
            }

            val recoveryType = classifyPlaybackError(error)
            val channel = currentChannelFlow.value?.sanitizedForPlayer()

            if (recoveryType == PlayerRecoveryType.DRM) {
                if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
                showPlayerNotice(
                    message = "This channel requires DRM support that is not available. " +
                        "Your subscription may not include this content.",
                    recoveryType = PlayerRecoveryType.DRM,
                    actions = buildRecoveryActions(PlayerRecoveryType.DRM)
                )
                return@launch
            }

            if (currentContentType != ContentType.LIVE || channel == null) {
                if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
                showPlayerNotice(
                    message = resolvePlaybackErrorMessage(error),
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType)
                )
                return@launch
            }

            if (isCatchUpPlayback()) {
                markStreamFailure(currentStreamUrl)
                setLastFailureReason(error.message)

                val switched = when (recoveryType) {
                    PlayerRecoveryType.NETWORK,
                    PlayerRecoveryType.SOURCE,
                    PlayerRecoveryType.BUFFER_TIMEOUT -> tryNextCatchUpVariantInternal()
                    else -> false
                }

                if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
                if (switched) {
                    appendRecoveryAction("Trying alternate catch-up URL")
                    showPlayerNotice(
                        message = "Trying another replay path for ${channel.name}.",
                        recoveryType = PlayerRecoveryType.CATCH_UP,
                        actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP),
                        isRetryNotice = true
                    )
                    return@launch
                }

                showPlayerNotice(
                    message = resolveCatchUpFailureMessage(
                        channel = channel,
                        archiveRequested = true,
                        programHasArchive = _currentProgram.value?.hasArchive == true
                    ),
                    recoveryType = PlayerRecoveryType.CATCH_UP,
                    actions = buildRecoveryActions(PlayerRecoveryType.CATCH_UP)
                )
                return@launch
            }

            if (shouldCooldownLivePreloadAfterError(error.message)) {
                cooldownLivePreloadForCurrentProvider("playback error")
            }
            markStreamFailure(currentStreamUrl)
            setLastFailureReason(error.message)
            logRepositoryFailure(
                operation = "Increment channel error count",
                result = channelRepository.incrementChannelErrorCount(channel.id)
            )

            val switched = when (recoveryType) {
                PlayerRecoveryType.NETWORK,
                PlayerRecoveryType.SOURCE,
                PlayerRecoveryType.BUFFER_TIMEOUT -> tryAlternateStreamInternal(channel)
                else -> false
            }

            if (!isActivePlaybackSession(requestVersion, playbackUrl)) return@launch
            if (switched) {
                appendRecoveryAction("Trying alternate stream")
                showPlayerNotice(
                    message = "Trying an alternate stream for ${channel.name}.",
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType),
                    isRetryNotice = true
                )
                return@launch
            }

            if (fallbackToPreviousChannel("Recovery path exhausted for ${recoveryType.name.lowercase()}")) {
                appendRecoveryAction("Returned to last channel")
                showPlayerNotice(
                    message = "Playback failed on this stream. Returned to the last channel.",
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType)
                )
            } else {
                showPlayerNotice(
                    message = resolvePlaybackErrorMessage(error),
                    recoveryType = recoveryType,
                    actions = buildRecoveryActions(recoveryType)
                )
            }
        }
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
        preferencesRepository.preventStandbyDuringPlayback
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), true)
    }

    internal fun beginPlaybackSession(): Long {
        recoveryJob?.cancel()
        thumbnailPreloadJob?.cancel()
        hasRetriedXtreamAuthRefresh = false
        lastRecordedVariantObservationSignature = null
        readySideEffectsRequestVersion = null
        playerEngine.setScrubbingMode(false)
        return ++prepareRequestVersion
    }

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
        return when (val result = seriesRepository.getSeriesDetails(providerId, seriesId)) {
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
    ): Boolean = matchesActivePlaybackSession(
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
            activeEpgRequestKey = null
            fetchEpg(providerId = -1L, internalChannelId = 0L, epgChannelId = null)
            return
        }

        val key = EpgRequestKey(
            providerId = providerId,
            internalChannelId = internalChannelId,
            epgChannelId = normalizedChannelId,
            streamId = streamId.takeIf { it > 0L } ?: 0L
        )
        if (key == activeEpgRequestKey) return
        activeEpgRequestKey = key
        fetchEpg(
            providerId = key.providerId,
            internalChannelId = key.internalChannelId,
            epgChannelId = key.epgChannelId,
            streamId = key.streamId
        )
    }

    private suspend fun applyPlaybackPreferences() {
        playerEngine.setMuted(preferencesRepository.playerMuted.first())
        playerEngine.setPlaybackSpeed(
            if (currentContentType == ContentType.LIVE) {
                1f
            } else {
                preferencesRepository.playerPlaybackSpeed.first()
            }
        )
        playerEngine.setPreferredAudioLanguage(
            resolvePreferredAudioLanguage(
                preferredAudioLanguage = preferencesRepository.preferredAudioLanguage.first(),
                appLanguage = preferencesRepository.appLanguage.first()
            )
        )
        playerEngine.setNetworkQualityPreferences(
            wifiMaxHeight = preferencesRepository.playerWifiMaxVideoHeight.first(),
            ethernetMaxHeight = preferencesRepository.playerEthernetMaxVideoHeight.first()
        )
        playerEngine.setSurfaceMode(preferencesRepository.playerSurfaceMode.first())
        playerEngine.setVodHttpProtocolMode(preferencesRepository.playerVodHttpProtocolMode.first())
        playerEngine.setAudioVideoOffsetMs(_audioVideoOffsetUiState.value.effectiveOffsetMs)
    }

    private suspend fun tryAdoptPreviewHandoff(
        requestVersion: Long,
        internalChannelId: Long,
        providerId: Long
    ): Boolean {
        if (currentContentType != ContentType.LIVE) return false

        val session = livePreviewHandoffManager.consumeFullscreenHandoff(
            channelId = internalChannelId,
            providerId = providerId.takeIf { it > 0L }
        ) ?: return false

        val adoptedEngine = session.engine
        return runCatching {
            val shouldRenewAdoptedPreview = shouldRenewAdoptedPreviewOnFullscreen(
                playbackState = adoptedEngine.playbackState.value,
                playerStats = adoptedEngine.playerStats.value
            )
            // Detach the Home preview surface before Player binds its own.
            adoptedEngine.clearRenderBinding()
            // Media3 requires a globally unique session ID. Release the main engine's
            // session before the adopted live engine enables its own replacement.
            mainPlayerEngine.setMediaSessionEnabled(false)
            setActivePlayerEngine(adoptedEngine)
            (adoptedEngine as? Media3PlayerEngine)?.let {
                it.bypassAudioFocus = false
                it.enableMediaSession = preferencesRepository.playerMediaSessionEnabled.first()
                it.constrainResolutionForMultiView = false
            }
            applyPlaybackPreferences()
            if (!isActivePlaybackSession(requestVersion)) {
                setActivePlayerEngine(mainPlayerEngine)
                adoptedEngine.release()
                false
            } else {
                currentResolvedPlaybackUrl = session.streamInfo.url
                currentResolvedStreamInfo = session.streamInfo
                readySideEffectsRequestVersion = requestVersion
                probePassedPlaybackKeys.add(
                    resolvePlaybackProbeCacheKey(
                        currentStreamUrl = currentStreamUrl,
                        url = session.streamInfo.url
                    )
                )
                if (shouldRenewAdoptedPreview) {
                    // Re-prime when the preview has not produced video yet. This keeps
                    // the previous audio-only/stale-surface recovery path without
                    // rebuffering a preview that is already rendering after the
                    // PlayerView handoff switches targets in Media3's recommended order.
                    playerEngine.renewStreamUrl(session.streamInfo)
                }
                playerEngine.play()
                startTokenRenewalMonitoring(session.streamInfo.expirationTime)
                maybeStartLiveTimeshift(session.streamInfo)
                true
            }
        }.getOrElse {
            livePreviewHandoffManager.clear(adoptedEngine)
            if (playerEngine === adoptedEngine) {
                setActivePlayerEngine(mainPlayerEngine)
            }
            adoptedEngine.release()
            false
        }
    }

    internal suspend fun preparePlayer(
        streamInfo: com.streamvault.domain.model.StreamInfo,
        requestVersion: Long,
        probeBeforePlayback: Boolean = true
    ): Boolean {
        if (!isActivePlaybackSession(requestVersion)) return false

        // Fast-path expiry check: if the stream URL already carries an expiration timestamp
        // that is in the past, skip the network probe entirely and surface a clear message.
        val expiry = streamInfo.expirationTime
        if (expiry != null && expiry > 0L && expiry < System.currentTimeMillis()) {
            if (!isActivePlaybackSession(requestVersion)) return false
            val expiryMessage = "This stream's subscription has expired. " +
                "Please renew your subscription with the provider."
            setLastFailureReason(expiryMessage)
            showPlayerNotice(
                message = expiryMessage,
                recoveryType = PlayerRecoveryType.SOURCE,
                actions = buildRecoveryActions(PlayerRecoveryType.SOURCE)
            )
            return false
        }

        var preparedStreamInfo = streamInfo
        when (val pluginPrepareResult = pluginManager.preparePlaybackStreamInfo(streamInfo)) {
            is Result.Error -> {
                if (!isActivePlaybackSession(requestVersion)) return false
                setLastFailureReason(pluginPrepareResult.message)
                showPlayerNotice(
                    message = pluginPrepareResult.message,
                    recoveryType = PlayerRecoveryType.NETWORK,
                    actions = buildRecoveryActions(PlayerRecoveryType.NETWORK)
                )
                return false
            }
            Result.Loading -> Unit
            is Result.Success -> preparedStreamInfo = pluginPrepareResult.data
        }

        if (probeBeforePlayback) {
            probePlaybackUrl(preparedStreamInfo)?.let { failure ->
                if (!isActivePlaybackSession(requestVersion)) return false
                setLastFailureReason(failure.message)
                showPlayerNotice(
                    message = failure.message,
                    recoveryType = failure.recoveryType,
                    actions = buildRecoveryActions(failure.recoveryType)
                )
                return false
            }
            probePassedPlaybackKeys.add(
                resolvePlaybackProbeCacheKey(
                    currentStreamUrl = currentStreamUrl,
                    url = streamInfo.url
                )
            )
        }
        applyPlaybackPreferences()
        if (!isActivePlaybackSession(requestVersion)) return false
        currentResolvedPlaybackUrl = preparedStreamInfo.url
        currentResolvedStreamInfo = preparedStreamInfo
        readySideEffectsRequestVersion = requestVersion
        playerEngine.prepare(preparedStreamInfo)
        startTokenRenewalMonitoring(preparedStreamInfo.expirationTime)
        return true
    }

    private suspend fun probePlaybackUrl(streamInfo: com.streamvault.domain.model.StreamInfo): PlaybackProbeFailure? {
        val url = streamInfo.url
        val providerId = currentProviderId.takeIf { it > 0L } ?: return null
        val provider = providerRepository.getProvider(providerId) ?: return null
        if (!shouldProbePlaybackUrl(url, provider)) return null
        if (shouldSkipPlaybackProbe(provider.type, url)) {
            Log.i(
                TAG,
                "Skipping playback probe provider=${provider.type.name} host=${runCatching { java.net.URI(url).host }.getOrNull().orEmpty()} " +
                    "path=${runCatching { java.net.URI(url).path }.getOrNull().orEmpty()} reason=connection-sensitive-provider-link"
            )
            return null
        }

        Log.d(
            TAG,
            "Playback probe request provider=${provider.type.name} host=${runCatching { java.net.URI(url).host }.getOrNull().orEmpty()} " +
                "path=${runCatching { java.net.URI(url).path }.getOrNull().orEmpty()} range=true " +
                "ua=${!streamInfo.userAgent.isNullOrBlank()} referer=${streamInfo.headers.containsKey("Referer")} " +
                "cookie=${streamInfo.headers.containsKey("Cookie")} auth=${streamInfo.headers.containsKey("Authorization")} " +
                "xua=${streamInfo.headers.containsKey("X-User-Agent")}"
        )

        return runCatching {
            withContext(Dispatchers.IO) {
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .header("Range", "bytes=0-0")
                    .apply {
                        streamInfo.userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) }
                        streamInfo.headers.forEach { (name, value) ->
                            header(name, value)
                        }
                    }
                    .build()
                okHttpClient.newCall(request).execute().use { response ->
                    resolvePlaybackProbeFailure(response.code)
                }
            }
        }.getOrNull()
    }

    private fun shouldProbePlaybackUrl(
        url: String,
        provider: com.streamvault.domain.model.Provider
    ): Boolean {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false
        val cacheKey = resolvePlaybackProbeCacheKey(
            currentStreamUrl = currentStreamUrl,
            url = url
        )
        if (provider.type != com.streamvault.domain.model.ProviderType.STALKER_PORTAL && cacheKey in probePassedPlaybackKeys) {
            return false
        }
        return (
            provider.type == com.streamvault.domain.model.ProviderType.XTREAM_CODES ||
                provider.type == com.streamvault.domain.model.ProviderType.STALKER_PORTAL
            ) &&
            (xtreamStreamUrlResolver.isInternalStreamUrl(currentStreamUrl) || xtreamStreamUrlResolver.isInternalStreamUrl(url))
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
            preferredDecoderMode = preferredDecoderMode,
            preferredSurfaceMode = preferredSurfaceMode
        )

        if (!hasArchiveRequest) {
            viewModelScope.launch {
                if (tryAdoptPreviewHandoff(requestVersion, internalChannelId, providerId)) {
                    return@launch
                }
                var playbackLogicalUrl = streamUrl
                var playbackContentId = internalChannelId
                if (currentContentType == ContentType.SERIES_EPISODE && providerId > 0 && currentSeriesId != null) {
                    val providerType = providerRepository.getProvider(providerId)?.type
                    val shouldAwaitRefreshedEpisode = providerType == ProviderType.STALKER_PORTAL ||
                        StalkerUrlFactory.isInternalStreamUrl(streamUrl)
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
                val streamInfo = resolvePlaybackStreamInfo(
                    playbackLogicalUrl,
                    playbackContentId,
                    providerId,
                    currentContentType
                )
                if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                if (streamInfo == null) {
                    if (!isActivePlaybackSession(requestVersion, streamUrl)) return@launch
                    showPlayerNotice(message = "No playable stream URL was available.", recoveryType = PlayerRecoveryType.SOURCE)
                    return@launch
                }
                currentStreamUrl = playbackLogicalUrl
                currentContentId = playbackContentId
                if (!isActivePlaybackSession(requestVersion, playbackLogicalUrl)) return@launch
                if (!preparePlayer(streamInfo, requestVersion)) return@launch

                // Check for resume position after the player is fully prepared (VOD only).
                // Doing this after preparePlayer ensures pause() acts on the live player instance,
                // not a stale one that may have already been replaced by prepareInternal().
                if (showResumePrompt && currentContentType != ContentType.LIVE && currentContentId != -1L && currentProviderId != -1L) {
                    val history = playbackHistoryRepository.getPlaybackHistory(
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

                maybeStartLiveTimeshift(streamInfo)
            }
        }
        
        // Show context info on entry for both Live and VOD
        openChannelInfoOverlay()

        if (providerId > 0) {
            viewModelScope.launch {
                providerRepository.getProvider(providerId)?.let { provider ->
                    _playerDiagnostics.update {
                        it.copy(
                            providerName = provider.name,
                            providerSourceLabel = when (provider.type) {
                                com.streamvault.domain.model.ProviderType.XTREAM_CODES -> "Xtream Codes"
                                com.streamvault.domain.model.ProviderType.M3U -> "M3U Playlist"
                                com.streamvault.domain.model.ProviderType.STALKER_PORTAL -> "Stalker/MAG Portal"
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
            archiveTitle = archiveTitle
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
        viewModelScope.launch {
            val identity = resolveSeriesEpisodeIdentity(
                providerId = providerId,
                internalChannelId = internalChannelId,
                seriesId = normalizedSeriesId,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                lookupEpisode = seriesRepository::getEpisodeById
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
        val timeline = buildProgramTimeline(
            programs = programs,
            now = now,
            catchUpSupported = currentChannelFlow.value?.catchUpSupported == true,
            maxHistoryItems = MAX_PROGRAM_HISTORY_ITEMS,
            maxUpcomingItems = MAX_UPCOMING_PROGRAM_ITEMS
        )
        _currentProgram.value = timeline.currentProgram
        _nextProgram.value = timeline.nextProgram
        _programHistory.value = timeline.programHistory
        _upcomingPrograms.value = timeline.upcomingPrograms
    }

    internal fun clearEpgState() {
        _currentProgram.value = null
        _nextProgram.value = null
        _programHistory.value = emptyList()
        _upcomingPrograms.value = emptyList()
    }

    // Store current URL to find index later
    internal var currentStreamUrl: String = ""

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
        if (streamUrl.isBlank()) return
        failedStreamsThisSession[streamUrl] = (failedStreamsThisSession[streamUrl] ?: 0) + 1
    }

    internal fun updateDecoderMode(mode: DecoderMode) {
        _playerDiagnostics.update { it.copy(decoderMode = mode) }
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

    internal suspend fun resolvePlaybackStreamInfo(
        logicalUrl: String,
        internalContentId: Long,
        providerId: Long,
        contentType: ContentType
    ): com.streamvault.domain.model.StreamInfo? {
        val resolution = resolvePlayerPlaybackStreamInfo(
            logicalUrl = logicalUrl,
            internalContentId = internalContentId,
            providerId = providerId,
            contentType = contentType,
            currentTitle = currentTitle,
            currentSeries = currentSeries.value,
            currentEpisode = currentEpisode.value,
            channelRepository = channelRepository,
            movieRepository = movieRepository,
            seriesRepository = seriesRepository,
            xtreamStreamUrlResolver = xtreamStreamUrlResolver
        )
        resolution.credentialFailureMessage?.let { message ->
            setLastFailureReason(message)
            showPlayerNotice(message = message, recoveryType = PlayerRecoveryType.SOURCE)
            return null
        }
        resolution.resolutionFailureMessage?.let { message ->
            setLastFailureReason(message)
            showPlayerNotice(message = message, recoveryType = PlayerRecoveryType.SOURCE)
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
        super.onCleared()
        cleanupAfterCleared(mainPlayerEngine)
    }
}
