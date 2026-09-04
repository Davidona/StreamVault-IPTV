package com.streamvault.player

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultLivePlaybackSpeedControl
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.session.MediaSession
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.PlaybackCompatibilityKey
import com.streamvault.domain.model.PlaybackCompatibilityRecord
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import com.streamvault.domain.repository.PlaybackCompatibilityRepository
import com.streamvault.player.audio.PlayerAudioFocusController
import com.streamvault.player.playback.ActiveDecoderPolicy
import com.streamvault.player.playback.DefaultDecoderPreferencePolicy
import com.streamvault.player.playback.DefaultPlaybackCompatibilityProfile
import com.streamvault.player.playback.AudioVideoOffsetAudioSink
import com.streamvault.player.playback.LiveAudioTapAudioSink
import com.streamvault.player.playback.PlaybackCodecSelector
import com.streamvault.player.playback.PlaybackCompatibilityProfile
import com.streamvault.player.playback.PlaybackBufferPolicies
import com.streamvault.player.playback.PlaybackBufferPolicy
import com.streamvault.player.playback.PlaybackErrorCategory
import com.streamvault.player.playback.FfmpegAudioFallbackRequest
import com.streamvault.player.playback.FfmpegExtensionSupport
import com.streamvault.player.playback.LiveHlsBufferPromotionDecider
import com.streamvault.player.playback.PlaybackLogSanitizer
import com.streamvault.player.playback.PlaybackPreparationPlan
import com.streamvault.player.playback.PlaybackExtensionRendererMode
import com.streamvault.player.playback.PlaybackRetryContext
import com.streamvault.player.playback.PlayerDataSourceFactoryProvider
import com.streamvault.player.playback.PlayerErrorClassifier
import com.streamvault.player.playback.PlayerMediaSourceFactory
import com.streamvault.player.playback.PlayerRetryPolicy
import com.streamvault.player.playback.PlayerTimeoutProfile
import com.streamvault.player.playback.PreloadCoordinator
import com.streamvault.player.playback.ResolvedStreamType
import com.streamvault.player.playback.resolveRetryAttemptAfterPlaybackStarted
import com.streamvault.player.playback.resolveRetryAttemptAfterReady
import com.streamvault.player.playback.resolveRetryAttemptForCategory
import com.streamvault.player.playback.resolveRetrySeekPositionMs
import com.streamvault.player.playback.StreamTypeResolver
import com.streamvault.player.playback.VideoStallDetector
import com.streamvault.player.playback.AutomaticRecoveryAction
import com.streamvault.player.playback.buildLiveTsFallbackStreamInfo
import com.streamvault.player.playback.buildPlaybackRendererPlan
import com.streamvault.player.playback.shouldAttemptAutomaticRecovery
import com.streamvault.player.playback.hasEffectivePlaybackStarted
import com.streamvault.player.playback.shouldArmPlaybackStartedRecovery
import com.streamvault.player.playback.shouldFallbackMalformedHlsToLiveTs
import com.streamvault.player.playback.shouldFallbackStalledHlsToLiveTs
import com.streamvault.player.playback.shouldPreservePlaybackStateForRetry
import com.streamvault.player.playback.shouldRecoverFrameSilentReadyStalls
import com.streamvault.player.playback.shouldRecoverPositionAdvancingReadyStalls
import com.streamvault.player.playback.shouldRecoverReadyStalls
import com.streamvault.player.playback.shouldReconnectLiveStall
import com.streamvault.player.playback.shouldForceSoftwareForAmbiguousDecoderFallback
import com.streamvault.player.stats.FrameRateDetector
import com.streamvault.player.stats.PlayerStatsCollector
import com.streamvault.player.stats.PlayerTransferByteCounter
import com.streamvault.player.timeshift.DefaultLiveTimeshiftManager
import com.streamvault.player.timeshift.LiveTimeshiftBackend
import com.streamvault.player.timeshift.LiveTimeshiftState
import com.streamvault.player.timeshift.LiveTimeshiftStatus
import com.streamvault.player.timeshift.TimeshiftConfig
import com.streamvault.player.tracks.PlayerTrackController
import com.streamvault.player.ui.PlayerViewBinder
import com.streamvault.player.ui.SubtitleStyleController
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
class Media3PlayerEngine @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val playbackCompatibilityRepository: PlaybackCompatibilityRepository,
    private val audioCompatibilityMemoryStore: AudioCompatibilityMemoryStore,
    private val playbackSupportSnapshotStore: PlaybackSupportSnapshotStore
) : PlayerEngine {

    companion object {
        private const val TAG = "Media3PlayerEngine"
        private const val AUDIO_RENDERER_RECOVERY_COOLDOWN_MS = 15_000L
        private const val LEGACY_TEXTURE_VIEW_MAX_SDK = Build.VERSION_CODES.N_MR1
        private const val FIRE_TV_MEDIATEK_TEXTURE_VIEW_MAX_SDK = Build.VERSION_CODES.P
        private const val TEXTURE_VIEW_STARTUP_TIMEOUT_MS = 9_000L
        private const val TEXTURE_VIEW_BUFFERED_STARTUP_THRESHOLD_MS = 4_000L
        private const val LIVE_HLS_STARTUP_GRACE_MS = 15_000L
        private const val KNOWN_BAD_FAILURE_THRESHOLD = 3
        private const val MEDIA_SESSION_ID_PREFIX = "streamvault"
        private val nextMediaSessionInstanceId = AtomicLong(1L)
    }

    var constrainResolutionForMultiView: Boolean = false
    var bypassAudioFocus: Boolean = false
        set(value) {
            field = value
            audioFocusController.bypassAudioFocus = value
        }
    var mediaSessionId: String = "$MEDIA_SESSION_ID_PREFIX-${nextMediaSessionInstanceId.getAndIncrement()}"
    var enableMediaSession: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) {
                exoPlayer?.let { player ->
                    if (mediaSession == null) {
                        mediaSession = MediaSession.Builder(context, player)
                            .setId(mediaSessionId)
                            .build()
                    }
                }
            } else {
                mediaSession?.release()
                mediaSession = null
            }
        }

    // All mutable engine state below is read/written on Dispatchers.Main.immediate
    // (the engine scope dispatcher). No @Volatile or synchronisation is needed as long
    // as the scope is not replaced with a different dispatcher in tests.
    //
    // scope is a var because resetForReuse() replaces it after cancelling the old
    // collectors. Terminal release() never recreates the scope.
    private var scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
        private set
    private var isDisposed = false
    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var requestedAudioDecoderMode: DecoderMode = DecoderMode.AUTO
    private var requestedVideoDecoderMode: DecoderMode = DecoderMode.AUTO
    private var activeAudioDecoderMode: DecoderMode = DecoderMode.HARDWARE
    private var activeVideoDecoderMode: DecoderMode = DecoderMode.HARDWARE
    private var requestedPlaybackBufferMode: PlaybackBufferMode = PlaybackBufferMode.AUTO
    private var requestedSurfaceMode: PlayerSurfaceMode = PlayerSurfaceMode.AUTO
    private var requestedVodHttpProtocolMode: VodHttpProtocolMode = VodHttpProtocolMode.COMPATIBILITY_HTTP1
    private var sessionSurfaceModeOverride: PlayerSurfaceMode? = null
    private var activeAudioDecoderPolicy: ActiveDecoderPolicy = ActiveDecoderPolicy.AUTO
    private var activeVideoDecoderPolicy: ActiveDecoderPolicy = ActiveDecoderPolicy.AUTO
    private var recoveryAudioDecoderModeOverride: DecoderMode? = null
    private var recoveryVideoDecoderModeOverride: DecoderMode? = null
    private var recoveryAudioDecoderPolicyOverride: ActiveDecoderPolicy? = null
    private var recoveryVideoDecoderPolicyOverride: ActiveDecoderPolicy? = null
    private var knownBadDecoderNames: Set<String> = emptySet()
    private var knownBadSurfaceTypes: Set<String> = emptySet()
    private var selectedVideoDecoderName: String = "Unknown"
    private var selectedAudioDecoderName: String = "Unknown"
    private var audioOutputPreference: AudioOutputPreference = AudioOutputPreference.AUTO
    private var compatibilityMemoryEnabled: Boolean = true
    private var fastRetryOnTransientFailures: Boolean = false
    private var audioOutputPath: String = "UNKNOWN"
    private var compatibilityDecisionSource: String = "DEFAULT"
    @Volatile
    private var pendingLearnedAudioFallback: PendingLearnedAudioFallback? = null
    private var videoStallCount = 0
    private var videoStallRecoveryAttempt = 0
    private var videoStallSafeRecoveryPerformed = false
    private var textureViewSessionFallbackAttempted = false
    private var audioVideoSyncSinkActive = false
    @Volatile
    private var liveAudioTap: LiveAudioTap? = null
    private var lastStreamInfo: StreamInfo? = null
    private var lastMediaId: String? = null
    private var currentResolvedStreamType: ResolvedStreamType = ResolvedStreamType.UNKNOWN
    private var currentTimeoutProfile: PlayerTimeoutProfile = PlayerTimeoutProfile.VOD
    private var currentRetryPolicy: PlayerRetryPolicy? = null
    private var currentRetryContext: PlaybackRetryContext? = null
    private var playbackStarted = false
    private var liveBufferingRecoveryArmed = false
    private var playbackStartedRecoveryArmed = false
    private var hasRenderedFirstVideoFrame = false
    private var prepareStartMs = 0L
    private var retryAttempt = 0
    private var lastRetryCategory: PlaybackErrorCategory? = null
    private var retryJob: Job? = null
    private var retryGeneration = 0L
    private var currentBufferIsLive: Boolean? = null
    private var currentBufferPolicyLabel: String? = null
    private var currentBufferPolicy: PlaybackBufferPolicy? = null
    private val promotedLiveHlsBufferReasonsByMediaId =
        com.streamvault.domain.util.BoundedExpiringCache<String, String>(
            maxEntries = 512,
            ttlMillis = 24L * 60L * 60L * 1_000L
        )
    private var audioCodecUnsupportedReported = false
    private var lastSupportErrorMessage: String? = null
    private val isLowMemoryPlaybackDevice: Boolean = run {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memoryClassMb = activityManager?.memoryClass ?: 256
        val lowRam = activityManager?.isLowRamDevice ?: false
        lowRam || memoryClassMb <= 256
    }

    private val _playbackState = MutableStateFlow(PlaybackState.IDLE)
    override val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    override val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    override val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    override val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _videoFormat = MutableStateFlow(VideoFormat(0, 0))
    override val videoFormat: StateFlow<VideoFormat> = _videoFormat.asStateFlow()

    private val _error = MutableSharedFlow<PlayerError?>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val error: Flow<PlayerError?> = _error.asSharedFlow()

    private val _retryStatus = MutableStateFlow<PlayerRetryStatus?>(null)
    override val retryStatus: StateFlow<PlayerRetryStatus?> = _retryStatus.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1f)
    override val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val audioVideoOffsetUs = AtomicLong(0L)
    private val _audioVideoOffsetMs = MutableStateFlow(0)
    override val audioVideoOffsetMs: StateFlow<Int> = _audioVideoOffsetMs.asStateFlow()
    private val _audioVideoSyncEnabled = MutableStateFlow(false)
    override val audioVideoSyncEnabled: StateFlow<Boolean> = _audioVideoSyncEnabled.asStateFlow()

    private val _renderSurfaceType = MutableStateFlow(PlayerRenderSurfaceType.SURFACE_VIEW)
    override val renderSurfaceType: StateFlow<PlayerRenderSurfaceType> = _renderSurfaceType.asStateFlow()
    private var lastPlaybackSupportSnapshotAtMs = 0L

    private val liveTimeshiftManager = DefaultLiveTimeshiftManager(context, okHttpClient)
    private val timeshiftCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timeshiftCleanupJob: Job? = null
    private val _timeshiftState = MutableStateFlow(LiveTimeshiftState())
    override val timeshiftState: StateFlow<LiveTimeshiftState> = _timeshiftState.asStateFlow()

    private val _playerStats = MutableStateFlow(PlayerStats())
    override val playerStats: StateFlow<PlayerStats> = _playerStats.asStateFlow()

    private val _mediaTitle = MutableStateFlow<String?>(null)
    override val mediaTitle: StateFlow<String?> = _mediaTitle.asStateFlow()

    private val subtitleStyleController = SubtitleStyleController()
    private val viewBinder = PlayerViewBinder(subtitleStyleController)
    private val trackController = PlayerTrackController(context)
    private val ffmpegExtensionSupport = FfmpegExtensionSupport()
    private var ffmpegAvailability = ffmpegExtensionSupport.availability()
    override val availableAudioTracks: StateFlow<List<PlayerTrack>> = trackController.availableAudioTracks
    override val availableSubtitleTracks: StateFlow<List<PlayerTrack>> = trackController.availableSubtitleTracks
    override val availableVideoTracks: StateFlow<List<PlayerTrack>> = trackController.availableVideoTracks

    private val _audioFocusDenied = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val audioFocusDenied: Flow<Unit> = _audioFocusDenied.asSharedFlow()

    private val audioFocusController = PlayerAudioFocusController(
        context = context,
        applyVolume = { volume -> exoPlayer?.volume = volume },
        setPlayWhenReady = { playWhenReady -> exoPlayer?.playWhenReady = playWhenReady },
        onAudioFocusDenied = { _audioFocusDenied.tryEmit(Unit) }
    ).also {
        it.bypassAudioFocus = bypassAudioFocus
    }
    override val isMuted: StateFlow<Boolean> = audioFocusController.isMuted

    private val transferByteCounter = PlayerTransferByteCounter()
    private val frameRateDetector = FrameRateDetector()
    private val statsCollector = PlayerStatsCollector(
        scopeProvider = { scope },
        currentPosition = _currentPosition,
        duration = _duration,
        videoFormat = _videoFormat,
        playerStats = _playerStats,
        playbackState = _playbackState,
        networkBytesProvider = { transferByteCounter.totalNetworkBytes },
        frameRateDetector = frameRateDetector
    ).also {
        it.bind { exoPlayer }
    }
    private val dataSourceFactoryProvider =
        PlayerDataSourceFactoryProvider(context, okHttpClient, transferByteCounter)
    private val mediaSourceFactory = PlayerMediaSourceFactory(dataSourceFactoryProvider)
    private val preloadCoordinator = PreloadCoordinator()
    private val compatibilityProfile: PlaybackCompatibilityProfile = DefaultPlaybackCompatibilityProfile
    private val audioDecoderPreferencePolicy = DefaultDecoderPreferencePolicy()
    private val videoDecoderPreferencePolicy = DefaultDecoderPreferencePolicy()
    private val videoStallDetector = VideoStallDetector()
    // All reads/writes on Dispatchers.Main.immediate (engine scope).
    @get:MainThread private var activeLiveTimeshiftStreamInfo: StreamInfo? = null
    @get:MainThread private var activeLiveTimeshiftChannelKey: String? = null
    @get:MainThread private var isPlayingTimeshiftSnapshot: Boolean = false
    private var pendingTimeshiftSeekMs: Long? = null
    private var pendingTimeshiftSeekToEnd: Boolean = false
    private var pendingTimeshiftAutoPlay: Boolean = false
    private var lastAudioRendererRecoveryAtMs: Long = 0L

    private data class PendingLearnedAudioFallback(
        val mediaId: String,
        val streamType: String,
        val audioMimeTypes: List<String>,
        val detail: String?
    )

    init {
        startEngineCollectors()
    }

    private fun startEngineCollectors() {
        if (isDisposed) return
        scope.launch {
            liveTimeshiftManager.state.collectLatest {
                syncTimeshiftState()
            }
        }
        scope.launch {
            while (true) {
                delay(1_000L)
                val stats = _playerStats.value
                val liveStream = isCurrentStreamLive()
                val effectivePlaybackStarted = isEffectivelyPlaybackStarted()
                val liveBufferingRecoveryEligible = liveStream && effectivePlaybackStarted
                val stalled = videoStallDetector.shouldReportStall(
                    playbackState = _playbackState.value,
                    isPlaying = _isPlaying.value,
                    playbackStarted = effectivePlaybackStarted,
                    currentPositionMs = _currentPosition.value,
                    bufferedDurationMs = stats.bufferedDurationMs,
                    playWhenReady = exoPlayer?.playWhenReady == true,
                    recoverBufferingStalls = liveBufferingRecoveryEligible,
                    recoverReadyStalls = shouldRecoverReadyStalls(currentResolvedStreamType),
                    recoverPositionAdvancingReadyStalls =
                        shouldRecoverPositionAdvancingReadyStalls(currentResolvedStreamType),
                    recoverFrameSilentReadyStalls =
                        shouldRecoverFrameSilentReadyStalls(currentResolvedStreamType)
                )
                _playerStats.value = _playerStats.value.copy(
                    lastVideoFrameAgoMs = videoStallDetector.lastVideoFrameAgoMs(),
                    videoStallCount = videoStallCount,
                    videoDecoderName = selectedVideoDecoderName,
                    audioDecoderName = selectedAudioDecoderName,
                    ffmpegAvailable = ffmpegAvailability.available,
                    ffmpegVersion = ffmpegAvailability.version,
                    audioOutputPath = audioOutputPath,
                    compatibilityDecisionSource = compatibilityDecisionSource,
                    activeDecoderPolicy = activeDecoderPolicySummary(),
                    renderSurfaceType = _renderSurfaceType.value.name,
                    audioVideoSyncEnabled = _audioVideoSyncEnabled.value,
                    audioVideoSyncSinkActive = audioVideoSyncSinkActive
                )
                if (shouldRefreshPlaybackSupportSnapshot()) {
                    val now = System.currentTimeMillis()
                    if (now - lastPlaybackSupportSnapshotAtMs >= 30_000L) {
                        lastPlaybackSupportSnapshotAtMs = now
                        val snapshot = buildPlaybackSupportSnapshot()
                        playbackSupportSnapshotStore.write(snapshot)
                    }
                }
                if (promoteLiveHlsBufferIfNeeded()) {
                    continue
                }
                if (shouldFallbackTextureViewBeforeFirstFrame(stats)) {
                    fallbackTextureViewSurface("NO_FIRST_FRAME")
                    continue
                }
                if (stalled) {
                    if (shouldIgnoreLiveHlsStartupStall()) {
                        Log.w(
                            TAG,
                            "video-stall startup grace retained for live-hls surface=${_renderSurfaceType.value} target=${PlaybackLogSanitizer.sanitizeUrl(lastStreamInfo?.url)}"
                        )
                    } else {
                        handleVideoStall()
                    }
                }
            }
        }
    }

    override fun prepare(streamInfo: StreamInfo) {
        if (ensureNotDisposed("prepare")) return
        prepareInternal(streamInfo = streamInfo, preserveRetryState = false, seekPositionMs = null, autoPlay = true)
    }

    override fun renewStreamUrl(streamInfo: StreamInfo) {
        if (ensureNotDisposed("renewStreamUrl")) return
        val player = exoPlayer ?: return
        val playbackPlan = buildPlaybackPreparationPlan(
            streamInfo = streamInfo,
            preload = false,
            playbackStarted = { isEffectivelyPlaybackStarted() }
        )

        lastStreamInfo = streamInfo
        lastMediaId = mediaSourceFactory.mediaIdFor(streamInfo)
        currentResolvedStreamType = playbackPlan.resolvedStreamType
        currentTimeoutProfile = playbackPlan.timeoutProfile
        currentRetryContext = playbackPlan.retryContext
        currentRetryPolicy = playbackPlan.retryPolicy
        retryAttempt = 0
        lastRetryCategory = null
        _retryStatus.value = null
        playbackStarted = false
        liveBufferingRecoveryArmed = false
        playbackStartedRecoveryArmed = false

        val mediaSource = mediaSourceFactory.create(
            streamInfo = streamInfo,
            resolvedStreamType = currentResolvedStreamType,
            retryPolicy = playbackPlan.retryPolicy,
            vodHttpProtocolMode = requestedVodHttpProtocolMode,
            preload = false
        ).second

        player.setMediaSource(mediaSource, /* resetPosition= */ false)
        player.prepare()

        Log.i(
            TAG,
            "renew-url resolvedStreamType=$currentResolvedStreamType target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )
    }

    override fun play() {
        if (audioFocusController.requestAudioFocusIfNeeded()) {
            exoPlayer?.playWhenReady = true
            syncTimeshiftState()
        }
    }

    override fun pause() {
        retryJob?.cancel()
        exoPlayer?.playWhenReady = false
        audioFocusController.onPauseOrStop()
    }

    override fun stop() {
        retryJob?.cancel()
        exoPlayer?.stop()
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
        _mediaTitle.value = null
        lastStreamInfo = null
        lastMediaId = null
        playbackStarted = false
        hasRenderedFirstVideoFrame = false
        clearInjectedSubtitleCues()
        statsCollector.stop()
        statsCollector.reset()
        audioFocusController.onPauseOrStop()
    }

    override fun seekTo(positionMs: Long) {
        if (activeLiveTimeshiftStreamInfo != null && !isPlayingTimeshiftSnapshot && _timeshiftState.value.supported) {
            switchToTimeshiftSnapshot(positionMs = positionMs.coerceAtLeast(0L), autoPlay = true)
            return
        }
        exoPlayer?.seekTo(positionMs)
    }

    override fun seekForward(ms: Long) {
        if (isPlayingTimeshiftSnapshot) {
            exoPlayer?.let { player ->
                val duration = player.duration
                if (duration != C.TIME_UNSET && player.currentPosition + ms >= duration) {
                    seekToLiveEdge()
                    return
                }
                val newPosition = if (duration != C.TIME_UNSET) {
                    (player.currentPosition + ms).coerceAtMost(duration)
                } else {
                    player.currentPosition + ms
                }
                player.seekTo(newPosition)
            }
            return
        }
        exoPlayer?.let { player ->
            val duration = player.duration
            val newPosition = if (duration != C.TIME_UNSET) {
                (player.currentPosition + ms).coerceAtMost(duration)
            } else {
                player.currentPosition + ms
            }
            player.seekTo(newPosition)
        }
    }

    override fun seekBackward(ms: Long) {
        if (activeLiveTimeshiftStreamInfo != null && !isPlayingTimeshiftSnapshot && _timeshiftState.value.supported) {
            val liveEdge = _timeshiftState.value.liveEdgePositionMs
            val target = (liveEdge - ms).coerceAtLeast(0L)
            switchToTimeshiftSnapshot(positionMs = target, autoPlay = true)
            return
        }
        exoPlayer?.let { player ->
            player.seekTo((player.currentPosition - ms).coerceAtLeast(0L))
        }
    }

    override fun setDecoderModes(audioMode: DecoderMode, videoMode: DecoderMode) {
        if (requestedAudioDecoderMode == audioMode && requestedVideoDecoderMode == videoMode) return
        requestedAudioDecoderMode = audioMode
        requestedVideoDecoderMode = videoMode
        lastStreamInfo?.let { streamInfo ->
            val wasPlaying = exoPlayer?.playWhenReady == true
            val position = exoPlayer?.currentPosition
            prepareInternal(streamInfo, preserveRetryState = false, seekPositionMs = position, autoPlay = wasPlaying)
        }
    }

    override fun setPlaybackBufferMode(mode: PlaybackBufferMode) {
        if (requestedPlaybackBufferMode == mode) return
        requestedPlaybackBufferMode = mode
        lastStreamInfo?.let { streamInfo ->
            val wasPlaying = exoPlayer?.playWhenReady == true
            val position = exoPlayer?.currentPosition
            prepareInternal(streamInfo, preserveRetryState = false, seekPositionMs = position, autoPlay = wasPlaying)
        }
    }

    override fun setSurfaceMode(mode: PlayerSurfaceMode) {
        if (requestedSurfaceMode == mode) return
        requestedSurfaceMode = mode
        sessionSurfaceModeOverride = null
        textureViewSessionFallbackAttempted = false
        updateRenderSurfaceForMode()
        lastStreamInfo?.let { streamInfo ->
            val wasPlaying = exoPlayer?.playWhenReady == true
            val position = exoPlayer?.currentPosition
            prepareInternal(streamInfo, preserveRetryState = false, seekPositionMs = position, autoPlay = wasPlaying)
        }
    }

    override fun setVodHttpProtocolMode(mode: VodHttpProtocolMode) {
        if (requestedVodHttpProtocolMode == mode) return
        requestedVodHttpProtocolMode = mode
        lastStreamInfo?.let { streamInfo ->
            val wasPlaying = exoPlayer?.playWhenReady == true
            val position = exoPlayer?.currentPosition
            prepareInternal(streamInfo, preserveRetryState = false, seekPositionMs = position, autoPlay = wasPlaying)
        }
    }

    override fun setMediaSessionEnabled(enabled: Boolean) {
        enableMediaSession = enabled
    }

    override fun setFastRetryOnTransientFailures(enabled: Boolean) {
        fastRetryOnTransientFailures = enabled
    }

    override fun setVolume(volume: Float) {
        audioFocusController.setVolume(volume)
    }

    override fun setMuted(muted: Boolean) {
        audioFocusController.setMuted(muted)
    }

    override fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2f)
        _playbackSpeed.value = clamped
        exoPlayer?.playbackParameters = PlaybackParameters(clamped)
    }

    override fun setAudioVideoSyncEnabled(enabled: Boolean) {
        if (_audioVideoSyncEnabled.value == enabled) return
        _audioVideoSyncEnabled.value = enabled
        if (!enabled) {
            _audioVideoOffsetMs.value = 0
            audioVideoOffsetUs.set(0L)
        }
        val streamInfo = lastStreamInfo ?: return
        val player = exoPlayer ?: return
        val wasPlaying = player.playWhenReady
        val position = player.currentPosition.takeIf { it > 0L }
        prepareInternal(streamInfo, preserveRetryState = true, seekPositionMs = position, autoPlay = wasPlaying)
    }

    override fun setAudioVideoOffsetMs(offsetMs: Int) {
        val clamped = offsetMs.coerceIn(AUDIO_VIDEO_OFFSET_MIN_MS, AUDIO_VIDEO_OFFSET_MAX_MS)
        val effectiveOffset = if (_audioVideoSyncEnabled.value) clamped else 0
        _audioVideoOffsetMs.value = effectiveOffset
        audioVideoOffsetUs.set(effectiveOffset * 1_000L)
    }

    override fun setAudioOutputPreference(preference: AudioOutputPreference) {
        if (audioOutputPreference == preference) return
        audioOutputPreference = preference
        val streamInfo = lastStreamInfo ?: return
        val player = exoPlayer ?: return
        prepareInternal(
            streamInfo = streamInfo,
            preserveRetryState = true,
            seekPositionMs = player.currentPosition.takeIf { it > 0L },
            autoPlay = player.playWhenReady
        )
    }

    override fun setCompatibilityMemoryEnabled(enabled: Boolean) {
        compatibilityMemoryEnabled = enabled
    }

    override fun clearLearnedPlaybackCompatibility() {
        audioCompatibilityMemoryStore.clear()
    }

    @MainThread
    override fun startLiveTimeshift(streamInfo: StreamInfo, channelKey: String, config: TimeshiftConfig) {
        if (ensureNotDisposed("startLiveTimeshift")) return
        activeLiveTimeshiftStreamInfo = streamInfo
        activeLiveTimeshiftChannelKey = channelKey
        scope.launch {
            timeshiftCleanupJob?.join()
            liveTimeshiftManager.startSession(streamInfo, channelKey, config)
            syncTimeshiftState()
        }
    }

    @MainThread
    override fun stopLiveTimeshift() {
        val wasSnapshot = isPlayingTimeshiftSnapshot
        val liveInfo = activeLiveTimeshiftStreamInfo
        activeLiveTimeshiftStreamInfo = null
        activeLiveTimeshiftChannelKey = null
        isPlayingTimeshiftSnapshot = false
        pendingTimeshiftSeekMs = null
        pendingTimeshiftSeekToEnd = false
        pendingTimeshiftAutoPlay = false
        if (wasSnapshot) {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        }
        scope.launch {
            timeshiftCleanupJob?.join()
            liveTimeshiftManager.stopSession()
            if (wasSnapshot && liveInfo != null) {
                prepareInternal(liveInfo, preserveRetryState = false, seekPositionMs = null, autoPlay = true)
            }
            syncTimeshiftState()
        }
    }

    @MainThread
    override fun seekToLiveEdge() {
        val liveInfo = activeLiveTimeshiftStreamInfo ?: return
        val wasSnapshot = isPlayingTimeshiftSnapshot
        isPlayingTimeshiftSnapshot = false
        pendingTimeshiftSeekMs = null
        pendingTimeshiftSeekToEnd = false
        pendingTimeshiftAutoPlay = false
        if (wasSnapshot) {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
        }
        prepareInternal(liveInfo, preserveRetryState = false, seekPositionMs = null, autoPlay = true)
        syncTimeshiftState()
        if (wasSnapshot) {
            scope.launch { liveTimeshiftManager.releaseRetiredSnapshots() }
        }
    }

    override fun pauseTimeshift() {
        if (activeLiveTimeshiftStreamInfo != null && !isPlayingTimeshiftSnapshot && _timeshiftState.value.supported) {
            switchToTimeshiftSnapshot(positionMs = null, autoPlay = false, seekToEnd = true)
            return
        }
        exoPlayer?.playWhenReady = false
        audioFocusController.onPauseOrStop()
        syncTimeshiftState()
    }

    override fun resumeTimeshift() {
        if (isPlayingTimeshiftSnapshot) {
            play()
            return
        }
        seekToLiveEdge()
    }

    override fun setPreferredAudioLanguage(languageTag: String?) {
        trackController.setPreferredAudioLanguage(exoPlayer, languageTag)
    }

    override fun setSubtitleStyle(style: PlayerSubtitleStyle) {
        subtitleStyleController.updateStyle(style)
        viewBinder.reapplyStyle()
    }

    override fun setNetworkQualityPreferences(wifiMaxHeight: Int?, ethernetMaxHeight: Int?) {
        trackController.setNetworkQualityPreferences(wifiMaxHeight, ethernetMaxHeight)
        exoPlayer?.let { player -> trackController.applyInitialParameters(player, constrainResolutionForMultiView) }
    }

    override fun selectAudioTrack(trackId: String) {
        exoPlayer?.let { trackController.selectAudioTrack(it, trackId) }
    }

    override fun selectVideoTrack(trackId: String) {
        exoPlayer?.let { trackController.selectVideoTrack(it, trackId) }
    }

    override fun selectSubtitleTrack(trackId: String?) {
        exoPlayer?.let { trackController.selectSubtitleTrack(it, trackId) }
    }

    override fun setInjectedSubtitleCues(cues: List<Cue>) {
        viewBinder.setInjectedSubtitleCues(cues)
    }

    override fun clearInjectedSubtitleCues() {
        viewBinder.clearInjectedSubtitleCues()
    }

    override fun setLiveAudioTap(tap: LiveAudioTap?) {
        liveAudioTap = tap
    }

    override fun addExternalSubtitle(subtitleUri: android.net.Uri, language: String) {
        val player = exoPlayer ?: return
        val streamInfo = lastStreamInfo ?: return
        val retryPolicy = currentRetryPolicy ?: return
        val transition = buildExternalSubtitlePlaybackTransition(
            currentPositionMs = player.currentPosition,
            playWhenReady = player.playWhenReady
        )

        val (_, mainMediaSource) = mediaSourceFactory.create(
            streamInfo = streamInfo,
            resolvedStreamType = currentResolvedStreamType,
            retryPolicy = retryPolicy,
            vodHttpProtocolMode = requestedVodHttpProtocolMode,
            preload = false
        )
        val subtitleConfig = androidx.media3.common.MediaItem.SubtitleConfiguration.Builder(subtitleUri)
            .setMimeType(androidx.media3.common.MimeTypes.APPLICATION_SUBRIP)
            .setLanguage(language)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .build()
        val subtitleSource = androidx.media3.exoplayer.source.SingleSampleMediaSource.Factory(
            androidx.media3.datasource.DefaultDataSource.Factory(context)
        ).createMediaSource(subtitleConfig, C.TIME_UNSET)
        val merged = androidx.media3.exoplayer.source.MergingMediaSource(mainMediaSource, subtitleSource)

        player.trackSelectionParameters = player.trackSelectionParameters.withExternalSubtitleEnabled()
        player.setMediaSource(merged, /* resetPosition= */ false)
        player.prepare()
        transition.resumePositionMs.takeIf { it > 0L }?.let(player::seekTo)
        player.playWhenReady = transition.playWhenReady
        Log.i(TAG, "addExternalSubtitle language=$language uri=$subtitleUri")
    }

    override fun toggleMute() {
        audioFocusController.toggleMute()
    }

    override fun setScrubbingMode(enabled: Boolean) {
        val player = exoPlayer ?: return
        if (enabled) {
            player.setScrubbingModeEnabled(true)
        } else {
            player.setScrubbingModeEnabled(false)
            player.setScrubbingModeParameters(ScrubbingModeParameters.DEFAULT)
        }
    }

    override fun preload(streamInfo: StreamInfo?) {
        if (streamInfo == null) {
            preloadCoordinator.invalidate("cleared")
            return
        }
        if (streamInfo.url == lastStreamInfo?.url) return

        val mediaId = mediaSourceFactory.mediaIdFor(streamInfo)
        val playbackPlan = buildPlaybackPreparationPlan(
            streamInfo = streamInfo,
            preload = true,
            playbackStarted = { false }
        )
        val (_, mediaSource) = mediaSourceFactory.create(
            streamInfo = streamInfo,
            resolvedStreamType = playbackPlan.resolvedStreamType,
            retryPolicy = playbackPlan.retryPolicy,
            vodHttpProtocolMode = requestedVodHttpProtocolMode,
            preload = true
        )
        preloadCoordinator.store(mediaId, streamInfo, playbackPlan.resolvedStreamType, mediaSource)
    }

    override fun createRenderView(
        context: Context,
        resizeMode: PlayerSurfaceResizeMode,
        surfaceType: PlayerRenderSurfaceType
    ) = viewBinder.createRenderView(context, resizeMode, surfaceType)

    override fun bindRenderView(renderView: android.view.View, resizeMode: PlayerSurfaceResizeMode) {
        if (ensureNotDisposed("bindRenderView")) return
        viewBinder.bind(renderView, getOrCreatePlayer(), resizeMode)
    }

    override fun clearRenderBinding() {
        viewBinder.attachPlayer(null)
    }

    override fun releaseRenderView(renderView: android.view.View) {
        viewBinder.release(renderView)
    }

    override fun resetLiveHandoffGrace() {
        if (isDisposed) return
        prepareStartMs = System.currentTimeMillis()
        videoStallDetector.reset()
        videoStallRecoveryAttempt = 0
        videoStallSafeRecoveryPerformed = false
        liveBufferingRecoveryArmed = false
        playbackStartedRecoveryArmed = false
        retryAttempt = 0
        lastRetryCategory = null
    }

    override fun release() {
        if (isDisposed) return
        isDisposed = true
        liveTimeshiftManager.detachComponentCallbacks()
        resetEngineState(restartCollectors = false)
    }

    override fun resetForReuse() {
        if (isDisposed) {
            Log.w(TAG, "resetForReuse ignored after terminal release")
            return
        }
        resetEngineState(restartCollectors = true)
    }

    private fun resetEngineState(restartCollectors: Boolean) {
        retryJob?.cancel()
        retryJob = null
        preloadCoordinator.release()
        statsCollector.stop()
        audioFocusController.release()
        mediaSession?.release()
        mediaSession = null
        viewBinder.clear()
        exoPlayer?.release()
        exoPlayer = null
        lastStreamInfo = null
        lastMediaId = null
        currentRetryPolicy = null
        currentRetryContext = null
        currentBufferIsLive = null
        currentBufferPolicyLabel = null
        currentBufferPolicy = null
        playbackStarted = false
        liveBufferingRecoveryArmed = false
        playbackStartedRecoveryArmed = false
        hasRenderedFirstVideoFrame = false
        retryAttempt = 0
        lastRetryCategory = null
        _retryStatus.value = null
        _playbackState.value = PlaybackState.IDLE
        _isPlaying.value = false
        _mediaTitle.value = null
        clearInjectedSubtitleCues()
        trackController.resetSelections()
        statsCollector.reset()
        videoStallDetector.reset()
        selectedVideoDecoderName = "Unknown"
        selectedAudioDecoderName = "Unknown"
        videoStallCount = 0
        videoStallRecoveryAttempt = 0
        clearDecoderRecoveryOverrides()
        knownBadDecoderNames = emptySet()
        knownBadSurfaceTypes = emptySet()
        sessionSurfaceModeOverride = null
        textureViewSessionFallbackAttempted = false
        audioVideoSyncSinkActive = false
        activeLiveTimeshiftStreamInfo = null
        activeLiveTimeshiftChannelKey = null
        isPlayingTimeshiftSnapshot = false
        _timeshiftState.value = LiveTimeshiftState()
        val previousTimeshiftCleanup = timeshiftCleanupJob
        timeshiftCleanupJob = timeshiftCleanupScope.launch {
            previousTimeshiftCleanup?.join()
            liveTimeshiftManager.stopSession()
        }
        scope.cancel()
        if (restartCollectors) {
            scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
            startEngineCollectors()
        }
        if (!restartCollectors) {
            timeshiftCleanupScope.launch {
                timeshiftCleanupJob?.join()
                liveTimeshiftManager.close()
                timeshiftCleanupScope.coroutineContext[Job]?.cancel()
            }
        }
    }

    private fun ensureNotDisposed(action: String): Boolean {
        if (!isDisposed) return false
        Log.w(TAG, "$action ignored after terminal release")
        return true
    }

    private fun prepareInternal(
        streamInfo: StreamInfo,
        preserveRetryState: Boolean,
        seekPositionMs: Long?,
        autoPlay: Boolean
    ) {
        retryJob?.cancel()
        retryJob = null
        retryGeneration++
        lastStreamInfo = streamInfo
        val mediaId = mediaSourceFactory.mediaIdFor(streamInfo)
        val mediaChanged = lastMediaId != mediaId
        val armPlaybackStartedRecovery = shouldArmPlaybackStartedRecovery(
            preserveRetryState = preserveRetryState,
            mediaChanged = mediaChanged,
            playbackStarted = playbackStarted,
            playbackStartedRecoveryArmed = liveBufferingRecoveryArmed || playbackStartedRecoveryArmed
        )
        val shouldArmLiveBufferingRecovery = armPlaybackStartedRecovery && isCurrentStreamLive()
        if (!preserveRetryState || mediaChanged) {
            retryAttempt = 0
            lastRetryCategory = null
            _retryStatus.value = null
        }
        if (mediaChanged) {
            audioDecoderPreferencePolicy.resetForMedia(mediaId)
            videoDecoderPreferencePolicy.resetForMedia(mediaId)
            clearDecoderRecoveryOverrides()
            sessionSurfaceModeOverride = null
            textureViewSessionFallbackAttempted = false
            knownBadDecoderNames = emptySet()
            knownBadSurfaceTypes = emptySet()
        }
        lastMediaId = mediaId
        playbackStarted = false
        liveBufferingRecoveryArmed = shouldArmLiveBufferingRecovery
        playbackStartedRecoveryArmed = armPlaybackStartedRecovery && !shouldArmLiveBufferingRecovery
        hasRenderedFirstVideoFrame = false
        prepareStartMs = System.currentTimeMillis()
        audioCodecUnsupportedReported = false
        pendingLearnedAudioFallback = null
        lastSupportErrorMessage = null
        _error.tryEmit(null)
        _mediaTitle.value = null
        trackController.resetSelections()
        statsCollector.reset()
        videoStallDetector.reset()
        if (!preserveRetryState) {
            liveBufferingRecoveryArmed = false
            playbackStartedRecoveryArmed = false
            videoStallRecoveryAttempt = 0
            videoStallSafeRecoveryPerformed = false
        }
        selectedVideoDecoderName = "Unknown"
        selectedAudioDecoderName = "Unknown"
        ffmpegAvailability = ffmpegExtensionSupport.availability()
        audioOutputPath = resolveStartupAudioOutputPath()

        val playbackPlan = buildPlaybackPreparationPlan(
            streamInfo = streamInfo,
            preload = false,
            playbackStarted = { isEffectivelyPlaybackStarted() }
        )
        currentResolvedStreamType = playbackPlan.resolvedStreamType
        currentTimeoutProfile = playbackPlan.timeoutProfile
        currentRetryContext = playbackPlan.retryContext
        currentRetryPolicy = playbackPlan.retryPolicy
        if (!preserveRetryState) {
            clearDecoderRecoveryOverrides()
        }
        val learnedAudioCompatibility = if (requestedAudioDecoderMode == DecoderMode.AUTO && compatibilityMemoryEnabled) {
            audioCompatibilityMemoryStore.lookup(mediaId, currentResolvedStreamType.name)
        } else {
            null
        }
        val preferredAudioDecoderMode = recoveryAudioDecoderModeOverride ?: when {
            requestedAudioDecoderMode != DecoderMode.AUTO ->
                audioDecoderPreferencePolicy.preferredMode(requestedAudioDecoderMode, mediaId)
            learnedAudioCompatibility?.decision == AudioCompatibilityMemoryStore.DECISION_SOFTWARE_FFMPEG -> DecoderMode.SOFTWARE
            else -> audioDecoderPreferencePolicy.preferredMode(requestedAudioDecoderMode, mediaId)
        }
        val preferredVideoDecoderMode = recoveryVideoDecoderModeOverride ?: videoDecoderPreferencePolicy.preferredMode(
            requestedVideoDecoderMode,
            mediaId
        )
        val nextAudioDecoderPolicy = recoveryAudioDecoderPolicyOverride ?: resolveActiveDecoderPolicy(
            policyModeFor(requestedAudioDecoderMode, preferredAudioDecoderMode)
        )
        val nextVideoDecoderPolicy = recoveryVideoDecoderPolicyOverride ?: resolveActiveDecoderPolicy(
            policyModeFor(requestedVideoDecoderMode, preferredVideoDecoderMode)
        )
        compatibilityDecisionSource = when {
            requestedAudioDecoderMode != DecoderMode.AUTO || requestedVideoDecoderMode != DecoderMode.AUTO -> "USER_SELECTED"
            learnedAudioCompatibility != null -> "LEARNED_AUDIO_FALLBACK"
            else -> "DEFAULT"
        }
        val isLiveBuffer = currentResolvedStreamType in setOf(
            ResolvedStreamType.HLS,
            ResolvedStreamType.SMOOTH_STREAMING,
            ResolvedStreamType.MPEG_TS_LIVE,
            ResolvedStreamType.RTSP
        )
        val previousAudioDecoderPolicy = activeAudioDecoderPolicy
        val previousVideoDecoderPolicy = activeVideoDecoderPolicy
        val nextBufferPolicy = PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = currentResolvedStreamType,
            compatibilityMode = nextVideoDecoderPolicy == ActiveDecoderPolicy.COMPATIBILITY,
            lowMemoryDevice = isLowMemoryPlaybackDevice,
            bufferMode = requestedPlaybackBufferMode,
            streamInfo = streamInfo,
            observedVideoFormat = _videoFormat.value,
            qualityReasonOverride = promotedLiveHlsBufferReasonsByMediaId.get(mediaId)
        )
        val needsRecreate = activeAudioDecoderMode != preferredAudioDecoderMode ||
            activeVideoDecoderMode != preferredVideoDecoderMode ||
            previousAudioDecoderPolicy != nextAudioDecoderPolicy ||
            previousVideoDecoderPolicy != nextVideoDecoderPolicy ||
            isLiveBuffer != currentBufferIsLive ||
            nextBufferPolicy.label != currentBufferPolicyLabel ||
            requestedAudioDecoderMode == DecoderMode.COMPATIBILITY ||
            requestedVideoDecoderMode == DecoderMode.COMPATIBILITY
        activeAudioDecoderMode = preferredAudioDecoderMode
        activeVideoDecoderMode = preferredVideoDecoderMode
        activeAudioDecoderPolicy = nextAudioDecoderPolicy
        activeVideoDecoderPolicy = nextVideoDecoderPolicy
        currentBufferIsLive = isLiveBuffer
        currentBufferPolicyLabel = nextBufferPolicy.label
        currentBufferPolicy = nextBufferPolicy
        updateRenderSurfaceForMode()
        if (needsRecreate) {
            recreatePlayer()
        }

        Log.i(
            TAG,
            "prepare resolvedStreamType=$currentResolvedStreamType timeoutProfile=$currentTimeoutProfile audioDecoderPreference=$activeAudioDecoderMode videoDecoderPreference=$activeVideoDecoderMode audioPolicy=$activeAudioDecoderPolicy videoPolicy=$activeVideoDecoderPolicy audioOutputPreference=$audioOutputPreference compatibilitySource=$compatibilityDecisionSource surface=${_renderSurfaceType.value} target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )
        Log.i(TAG, nextBufferPolicy.describeForLog(currentResolvedStreamType, requestedPlaybackBufferMode))

        try {
            val player = getOrCreatePlayer()
            trackController.applyInitialParameters(player, constrainResolutionForMultiView)
            if (activeVideoDecoderPolicy == ActiveDecoderPolicy.COMPATIBILITY) {
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setMaxVideoSize(Int.MAX_VALUE, 720)
                    .build()
            }
            player.playbackParameters = PlaybackParameters(_playbackSpeed.value)

            val mediaSource = preloadCoordinator.tryReuse(mediaId, streamInfo, currentResolvedStreamType)
                ?: mediaSourceFactory.create(
                    streamInfo = streamInfo,
                    resolvedStreamType = currentResolvedStreamType,
                    retryPolicy = currentRetryPolicy!!,
                    vodHttpProtocolMode = requestedVodHttpProtocolMode,
                    preload = false
                ).second
            preloadCoordinator.onPlaybackStarted(mediaId)
            player.setMediaSource(mediaSource)
            player.prepare()
            seekPositionMs?.takeIf { it > 0L }?.let(player::seekTo)

            val isLive = currentResolvedStreamType in setOf(
                ResolvedStreamType.HLS,
                ResolvedStreamType.SMOOTH_STREAMING,
                ResolvedStreamType.MPEG_TS_LIVE,
                ResolvedStreamType.RTSP
            )
            val osContentType = if (isLive) {
                android.media.AudioAttributes.CONTENT_TYPE_MUSIC
            } else {
                android.media.AudioAttributes.CONTENT_TYPE_MOVIE
            }
            val media3ContentType = if (isLive) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE
            player.setAudioAttributes(
                Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(media3ContentType)
                    .build(),
                false
            )
            val focusGranted = audioFocusController.requestAudioFocusIfNeeded(osContentType)
            player.playWhenReady = autoPlay && focusGranted
            if (autoPlay && !focusGranted) {
                _audioFocusDenied.tryEmit(Unit)
            }
            viewBinder.attachPlayer(player)
        } catch (error: Exception) {
            handlePlaybackSetupFailure(error, streamInfo, mediaId, seekPositionMs, autoPlay)
        }
    }

    private fun recreatePlayer() {
        val existing = exoPlayer ?: return
        mediaSession?.release()
        mediaSession = null
        existing.release()
        exoPlayer = null
        viewBinder.attachPlayer(null)
        // The caller (prepareInternal) will create a fresh player and set it up fully.
    }

    private fun getOrCreatePlayer(): ExoPlayer {
        check(!isDisposed) { "Cannot create a player after terminal release" }
        return exoPlayer ?: createPlayer().also { player ->
            exoPlayer = player
            statsCollector.bind { exoPlayer }
            audioFocusController.reapplyVolume()
            if (enableMediaSession) {
                mediaSession = MediaSession.Builder(context, player)
                    .setId(mediaSessionId)
                    .build()
            }
        }
    }

    private fun createPlayer(): ExoPlayer {
        val renderersFactory = buildRenderersFactory()
        val bufferPolicy = currentBufferPolicy ?: PlaybackBufferPolicies.forPlayback(
            resolvedStreamType = currentResolvedStreamType,
            compatibilityMode = activeVideoDecoderPolicy == ActiveDecoderPolicy.COMPATIBILITY,
            lowMemoryDevice = isLowMemoryPlaybackDevice,
            bufferMode = requestedPlaybackBufferMode,
            streamInfo = lastStreamInfo,
            observedVideoFormat = _videoFormat.value,
            qualityReasonOverride = lastMediaId?.let(promotedLiveHlsBufferReasonsByMediaId::get)
        )
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                bufferPolicy.minBufferMs,
                bufferPolicy.maxBufferMs,
                bufferPolicy.playbackBufferMs,
                bufferPolicy.rebufferMs
            )
            .setTargetBufferBytes(bufferPolicy.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholds(bufferPolicy.prioritizeTimeOverSizeThresholds)
            .build()
        val livePlaybackSpeedControl = DefaultLivePlaybackSpeedControl.Builder()
            .setFallbackMinPlaybackSpeed(1.0f)
            .setFallbackMaxPlaybackSpeed(1.0f)
            .build()

        return ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setLivePlaybackSpeedControl(livePlaybackSpeedControl)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setAudioAttributes(
                Media3AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN)
                    .build(),
                false
            )
            .build()
            .apply {
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                playbackParameters = PlaybackParameters(_playbackSpeed.value)
                setVideoFrameMetadataListener { presentationTimeUs, _, _, _ ->
                    videoStallDetector.onVideoFrameRendered((presentationTimeUs / 1_000L).coerceAtLeast(0L))
                    frameRateDetector.onFrame(presentationTimeUs)
                }
                addAnalyticsListener(createAnalyticsListener())
                addListener(createPlayerListener())
            }
    }

    private fun PlaybackBufferPolicy.describeForLog(
        resolvedStreamType: ResolvedStreamType,
        bufferMode: PlaybackBufferMode
    ): String {
        return "buffer-policy label=$label streamType=$resolvedStreamType mode=$bufferMode " +
            "reason=$qualityReason minMs=$minBufferMs maxMs=$maxBufferMs " +
            "playbackMs=$playbackBufferMs rebufferMs=$rebufferMs " +
            "targetBytes=$targetBufferBytes lowMemoryCapped=$lowMemoryCapped"
    }


    private fun buildRenderersFactory(): DefaultRenderersFactory {
        logFfmpegAvailability()
        val audioDecoderPolicy = activeAudioDecoderPolicy
        val videoDecoderPolicy = activeVideoDecoderPolicy
        val badDecoderNames = knownBadDecoderNames
        val rendererPlan = buildPlaybackRendererPlan(
            requestedAudioMode = requestedAudioDecoderMode,
            activeAudioDecoderMode = activeAudioDecoderMode,
            audioDecoderPolicy = audioDecoderPolicy,
            requestedVideoMode = requestedVideoDecoderMode,
            activeVideoDecoderMode = activeVideoDecoderMode,
            videoDecoderPolicy = videoDecoderPolicy,
            useAudioVideoSyncSink = _audioVideoSyncEnabled.value,
            useVideoRendererWorkaround = compatibilityProfile.shouldDisableDecoderReuseWorkaround()
        )
        audioVideoSyncSinkActive = false
        val audioCodecSelector = if (rendererPlan.useAudioManagedCodecSelector) {
            PlaybackCodecSelector(
                policyProvider = { audioDecoderPolicy },
                knownBadProvider = { emptySet() }
            )
        } else {
            MediaCodecSelector.DEFAULT
        }
        val videoCodecSelector = if (rendererPlan.useVideoManagedCodecSelector) {
            PlaybackCodecSelector(
                policyProvider = { videoDecoderPolicy },
                knownBadProvider = { badDecoderNames }
            )
        } else {
            MediaCodecSelector.DEFAULT
        }

        val factory = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                val audioSink = requireNotNull(
                    super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams)
                ) {
                    "DefaultRenderersFactory did not provide an AudioSink."
                }
                val syncAwareSink = if (rendererPlan.useAudioVideoSyncSink) {
                    audioVideoSyncSinkActive = true
                    AudioVideoOffsetAudioSink(
                        delegate = audioSink,
                        offsetUsProvider = { audioVideoOffsetUs.get() }
                    )
                } else {
                    audioVideoSyncSinkActive = false
                    audioSink
                }
                return LiveAudioTapAudioSink(
                    delegate = syncAwareSink,
                    tapProvider = { liveAudioTap }
                )
            }

            override fun buildAudioRenderers(
                context: Context,
                extensionRendererMode: Int,
                mediaCodecSelector: MediaCodecSelector,
                enableDecoderFallback: Boolean,
                audioSink: AudioSink,
                eventHandler: Handler,
                eventListener: AudioRendererEventListener,
                out: ArrayList<Renderer>
            ) {
                super.buildAudioRenderers(
                    context,
                    rendererPlan.audioExtensionRendererMode.toMedia3ExtensionRendererMode(),
                    audioCodecSelector,
                    enableDecoderFallback,
                    audioSink,
                    eventHandler,
                    eventListener,
                    out
                )
            }

            override fun buildVideoRenderers(
                    context: Context,
                    extensionRendererMode: Int,
                    mediaCodecSelector: MediaCodecSelector,
                    enableDecoderFallback: Boolean,
                    eventHandler: Handler,
                    eventListener: VideoRendererEventListener,
                    allowedVideoJoiningTimeMs: Long,
                    out: ArrayList<Renderer>
            ) {
                if (!rendererPlan.useVideoRendererWorkaround) {
                    super.buildVideoRenderers(
                        context,
                        rendererPlan.videoExtensionRendererMode.toMedia3ExtensionRendererMode(),
                        videoCodecSelector,
                        enableDecoderFallback,
                        eventHandler,
                        eventListener,
                        allowedVideoJoiningTimeMs,
                        out
                    )
                    return
                }

                out.add(object : MediaCodecVideoRenderer(
                    context,
                    videoCodecSelector,
                    allowedVideoJoiningTimeMs,
                    enableDecoderFallback,
                    eventHandler,
                    eventListener,
                    50
                ) {
                    override fun canReuseCodec(
                        codecInfo: MediaCodecInfo,
                        oldFormat: Format,
                        newFormat: Format
                    ): DecoderReuseEvaluation {
                        return DecoderReuseEvaluation(
                            codecInfo.name,
                            oldFormat,
                            newFormat,
                            DecoderReuseEvaluation.REUSE_RESULT_NO,
                            DecoderReuseEvaluation.DISCARD_REASON_MAX_INPUT_SIZE_EXCEEDED
                        )
                    }
                })
            }
        }

        return factory.apply {
            setEnableDecoderFallback(true)
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        }
    }

    private fun PlaybackExtensionRendererMode.toMedia3ExtensionRendererMode(): Int = when (this) {
        PlaybackExtensionRendererMode.PLATFORM_FIRST -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
        PlaybackExtensionRendererMode.EXTENSIONS_FIRST -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
    }

    private fun createAnalyticsListener(): AnalyticsListener {
        return object : AnalyticsListener {
            override fun onVideoInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                statsCollector.onVideoFormatChanged(format)
                refreshKnownBadCompatibilityRecords()
            }

            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                selectedVideoDecoderName = decoderName
                _playerStats.value = _playerStats.value.copy(videoDecoderName = decoderName)
                Log.i(TAG, "video-decoder name=$decoderName policy=$activeVideoDecoderPolicy surface=${_renderSurfaceType.value}")
                refreshKnownBadCompatibilityRecords()
            }

            override fun onAudioDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                selectedAudioDecoderName = decoderName
                audioOutputPath = classifyAudioOutputPath(decoderName)
                _playerStats.value = _playerStats.value.copy(
                    audioDecoderName = decoderName,
                    audioOutputPath = audioOutputPath
                )
                Log.i(TAG, "audio-decoder name=$decoderName policy=$activeAudioDecoderPolicy outputPath=$audioOutputPath")
            }

            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?
            ) {
                statsCollector.onAudioFormatChanged(format)
            }

            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long
            ) {
                Log.w(
                    TAG,
                    "audio-underrun bufferSize=$bufferSize bufferSizeMs=$bufferSizeMs elapsedSinceLastFeedMs=$elapsedSinceLastFeedMs"
                )
            }

            override fun onAudioSinkError(
                eventTime: AnalyticsListener.EventTime,
                audioSinkError: Exception
            ) {
                handleAudioRendererIssue(audioSinkError, "audio-sink")
            }

            override fun onAudioCodecError(
                eventTime: AnalyticsListener.EventTime,
                audioCodecError: Exception
            ) {
                handleAudioRendererIssue(audioCodecError, "audio-codec")
            }

            override fun onDroppedVideoFrames(
                eventTime: AnalyticsListener.EventTime,
                droppedFrames: Int,
                elapsedMs: Long
            ) {
                statsCollector.onDroppedFrames(droppedFrames)
            }

            override fun onBandwidthEstimate(
                eventTime: AnalyticsListener.EventTime,
                totalLoadTimeMs: Int,
                totalBytesLoaded: Long,
                bitrateEstimate: Long
            ) {
                statsCollector.onBandwidthEstimate(bitrateEstimate)
            }

            override fun onRenderedFirstFrame(
                eventTime: AnalyticsListener.EventTime,
                output: Any,
                renderTimeMs: Long
            ) {
                hasRenderedFirstVideoFrame = true
                if (prepareStartMs > 0L) {
                    val ttff = System.currentTimeMillis() - prepareStartMs
                    _playerStats.value = _playerStats.value.copy(ttffMs = ttff)
                }
                videoStallDetector.onVideoFrameRendered(eventTime.currentPlaybackPositionMs)
                markPlaybackStarted("first-frame-success")
            }
        }
    }

    private fun createPlayerListener(): Player.Listener {
        return object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val previousState = _playbackState.value
                _playbackState.value = when (state) {
                    Player.STATE_IDLE -> PlaybackState.IDLE
                    Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                    Player.STATE_READY -> PlaybackState.READY
                    Player.STATE_ENDED -> PlaybackState.ENDED
                    else -> PlaybackState.IDLE
                }
                if (previousState == PlaybackState.READY && _playbackState.value == PlaybackState.BUFFERING) {
                    statsCollector.incrementRebufferCount()
                }
                if (_playbackState.value == PlaybackState.READY) {
                    _retryStatus.value = null
                    retryAttempt = resolveRetryAttemptAfterReady(
                        currentAttempt = retryAttempt,
                        playbackStarted = playbackStarted
                    )
                    if (retryAttempt == 0) {
                        lastRetryCategory = null
                    }
                    if (isPlayingTimeshiftSnapshot && pendingTimeshiftSeekToEnd) {
                        pendingTimeshiftSeekToEnd = false
                        playerOrNull()?.duration?.takeIf { it > 0L && it != C.TIME_UNSET }?.let { duration ->
                            playerOrNull()?.seekTo((duration - 200L).coerceAtLeast(0L))
                        }
                    } else if (isPlayingTimeshiftSnapshot) {
                        pendingTimeshiftSeekMs?.let { target ->
                            pendingTimeshiftSeekMs = null
                            playerOrNull()?.seekTo(target)
                        }
                    }
                }
                if (_playbackState.value == PlaybackState.READY && _isPlaying.value) {
                    markPlaybackStarted("ready-while-playing")
                }
                syncTimeshiftState()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                if (isPlaying) {
                    if (_playbackState.value == PlaybackState.READY) {
                        markPlaybackStarted("playing-ready")
                    }
                    statsCollector.start()
                    audioFocusController.onPlaybackStarted()
                } else {
                    statsCollector.stop()
                }
                syncTimeshiftState()
            }

            override fun onPlayerError(error: PlaybackException) {
                handlePlaybackError(error)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                _currentPosition.value = newPosition.positionMs
                syncTimeshiftState()
            }

            override fun onMediaMetadataChanged(metadata: androidx.media3.common.MediaMetadata) {
                _mediaTitle.value = metadata.title?.toString()?.takeIf { it.isNotBlank() }
            }

            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                trackController.onTracksChanged(tracks)
                // Detect the silent failure: the stream contains audio groups but no track
                // is decodable on this device (e.g. EAC3/AC3 without passthrough or a
                // software decoder). ExoPlayer simply skips the audio renderer without
                // throwing an error, resulting in mute playback with no user feedback.
                if (!audioCodecUnsupportedReported) {
                    val hasAudioGroups = tracks.groups.any {
                        it.mediaTrackGroup.type == C.TRACK_TYPE_AUDIO && it.length > 0
                    }
                    if (hasAudioGroups && trackController.availableAudioTracks.value.isEmpty()) {
                        val mimeTypes = audioMimeTypes(tracks)
                        if (tryFfmpegAudioFallback("audio-track-missing", mimeTypes, detail = null)) {
                            return
                        }
                        audioCodecUnsupportedReported = true
                        val mimeTypeLabel = mimeTypes.joinToString()
                        Log.w(
                            TAG,
                            "audio-codec-unsupported mimeTypes=$mimeTypeLabel target=${PlaybackLogSanitizer.sanitizeUrl(lastStreamInfo?.url.orEmpty())}"
                        )
                        lastSupportErrorMessage = "No compatible audio decoder for this stream ($mimeTypeLabel)."
                        _error.tryEmit(
                            PlayerError.DecoderError(
                                "No compatible audio decoder for this stream ($mimeTypeLabel). " +
                                    "The audio codec is not supported on this device."
                            )
                        )
                    }
                }
            }
        }
    }

    private fun playerOrNull(): ExoPlayer? = exoPlayer

    private fun switchToTimeshiftSnapshot(
        positionMs: Long?,
        autoPlay: Boolean,
        seekToEnd: Boolean = false
    ) {
        val liveInfo = activeLiveTimeshiftStreamInfo ?: return
        scope.launch {
            val snapshot = liveTimeshiftManager.createSnapshot() ?: run {
                syncTimeshiftState(messageOverride = "Local live rewind is still buffering.")
                return@launch
            }
            if (activeLiveTimeshiftStreamInfo !== liveInfo) return@launch
            isPlayingTimeshiftSnapshot = true
            pendingTimeshiftSeekMs = positionMs
            pendingTimeshiftSeekToEnd = seekToEnd
            pendingTimeshiftAutoPlay = autoPlay
            val snapshotInfo = liveInfo.copy(url = snapshot.url, streamType = inferSnapshotStreamType(snapshot.url))
            prepareInternal(snapshotInfo, preserveRetryState = false, seekPositionMs = null, autoPlay = autoPlay)
            liveTimeshiftManager.releaseRetiredSnapshots()
            syncTimeshiftState()
        }
    }

    private fun inferSnapshotStreamType(url: String) = when {
        url.lowercase().endsWith(".m3u8") -> com.streamvault.domain.model.StreamType.HLS
        else -> com.streamvault.domain.model.StreamType.PROGRESSIVE
    }

    private fun syncTimeshiftState(messageOverride: String? = null) {
        val managerState = liveTimeshiftManager.state.value
        if (!managerState.enabled) {
            _timeshiftState.value = managerState
            return
        }
        val player = exoPlayer
        val duration = player?.duration?.takeIf { it != C.TIME_UNSET } ?: managerState.liveEdgePositionMs
        val currentPosition = player?.currentPosition ?: duration
        val offsetFromLive = when {
            isPlayingTimeshiftSnapshot -> (managerState.liveEdgePositionMs - currentPosition).coerceAtLeast(0L)
            else -> 0L
        }
        val status = when {
            managerState.status == LiveTimeshiftStatus.FAILED -> LiveTimeshiftStatus.FAILED
            !managerState.supported -> LiveTimeshiftStatus.UNSUPPORTED
            isPlayingTimeshiftSnapshot && _playbackState.value == PlaybackState.BUFFERING -> LiveTimeshiftStatus.BUFFERING
            isPlayingTimeshiftSnapshot && _isPlaying.value -> LiveTimeshiftStatus.PLAYING_BEHIND_LIVE
            isPlayingTimeshiftSnapshot -> LiveTimeshiftStatus.PAUSED_BEHIND_LIVE
            managerState.status == LiveTimeshiftStatus.PREPARING -> LiveTimeshiftStatus.PREPARING
            else -> LiveTimeshiftStatus.LIVE
        }
        _timeshiftState.value = managerState.copy(
            backend = managerState.backend,
            status = status,
            liveEdgePositionMs = managerState.liveEdgePositionMs,
            currentOffsetFromLiveMs = offsetFromLive,
            message = messageOverride ?: managerState.message
        )
    }

    private fun markPlaybackStarted(reason: String) {
        if (playbackStarted) return
        playbackStarted = true
        liveBufferingRecoveryArmed = false
        playbackStartedRecoveryArmed = false
        retryAttempt = resolveRetryAttemptAfterPlaybackStarted(retryAttempt)
        if (retryAttempt == 0) {
            lastRetryCategory = null
        }
        Log.i(
            TAG,
            "$reason streamType=$currentResolvedStreamType timeoutProfile=$currentTimeoutProfile audioPath=$audioOutputPath compatibilitySource=$compatibilityDecisionSource target=${PlaybackLogSanitizer.sanitizeUrl(lastStreamInfo?.url)}"
        )
        pendingLearnedAudioFallback?.let { learned ->
            if (compatibilityMemoryEnabled && activeAudioDecoderMode == DecoderMode.SOFTWARE) {
                audioCompatibilityMemoryStore.rememberSoftwareAudioFallback(
                    mediaId = learned.mediaId,
                    streamType = learned.streamType,
                    audioMimeTypes = learned.audioMimeTypes,
                    detail = learned.detail
                )
            }
            pendingLearnedAudioFallback = null
        }
        recordCompatibilitySuccess()
        refreshKnownBadCompatibilityRecords()
    }

    private fun resolveActiveDecoderPolicy(mode: DecoderMode): ActiveDecoderPolicy = when (mode) {
        DecoderMode.AUTO -> ActiveDecoderPolicy.AUTO
        DecoderMode.HARDWARE -> ActiveDecoderPolicy.HARDWARE_PREFERRED
        DecoderMode.SOFTWARE -> ActiveDecoderPolicy.SOFTWARE_PREFERRED
        DecoderMode.COMPATIBILITY -> ActiveDecoderPolicy.COMPATIBILITY
    }

    private fun activeDecoderPolicySummary(): String =
        "audio=${activeAudioDecoderPolicy.name},video=${activeVideoDecoderPolicy.name}"

    private fun policyModeFor(requestedMode: DecoderMode, preferredMode: DecoderMode): DecoderMode = when {
        requestedMode == DecoderMode.COMPATIBILITY -> DecoderMode.COMPATIBILITY
        preferredMode == DecoderMode.SOFTWARE -> DecoderMode.SOFTWARE
        else -> requestedMode
    }

    private fun updateRenderSurfaceForMode() {
        val surfaceMode = when {
            shouldForceSurfaceViewForFireTvLiveHls() -> PlayerSurfaceMode.SURFACE_VIEW
            else -> sessionSurfaceModeOverride
            ?: when {
                requestedSurfaceMode == PlayerSurfaceMode.TEXTURE_VIEW &&
                    knownBadSurfaceTypes.contains(PlayerRenderSurfaceType.TEXTURE_VIEW.name) -> {
                    PlayerSurfaceMode.SURFACE_VIEW
                }
                else -> requestedSurfaceMode
            }
        }
        _renderSurfaceType.value = when (surfaceMode) {
            PlayerSurfaceMode.SURFACE_VIEW -> PlayerRenderSurfaceType.SURFACE_VIEW
            PlayerSurfaceMode.TEXTURE_VIEW -> PlayerRenderSurfaceType.TEXTURE_VIEW
            PlayerSurfaceMode.AUTO -> if (shouldPreferTextureViewForAutoSurface()) {
                PlayerRenderSurfaceType.TEXTURE_VIEW
            } else {
                PlayerRenderSurfaceType.SURFACE_VIEW
            }
        }
    }

    private fun shouldPreferTextureViewForAutoSurface(): Boolean {
        if (Build.VERSION.SDK_INT <= LEGACY_TEXTURE_VIEW_MAX_SDK) return true
        return Build.MANUFACTURER.equals("Amazon", ignoreCase = true) &&
            Build.HARDWARE.orEmpty().startsWith("mt", ignoreCase = true) &&
            Build.VERSION.SDK_INT <= FIRE_TV_MEDIATEK_TEXTURE_VIEW_MAX_SDK
    }

    private fun shouldForceSurfaceViewForFireTvLiveHls(): Boolean {
        return Build.MANUFACTURER.equals("Amazon", ignoreCase = true) &&
            Build.HARDWARE.orEmpty().startsWith("mt", ignoreCase = true) &&
            currentResolvedStreamType == ResolvedStreamType.HLS
    }

    private fun refreshKnownBadCompatibilityRecords() {
        if (!shouldUseCompatibilityHistory()) {
            knownBadDecoderNames = emptySet()
            knownBadSurfaceTypes = emptySet()
            return
        }
        val video = _videoFormat.value
        val videoMime = video.codecV ?: _playerStats.value.videoCodec
        val bucket = resolutionBucket(video.width.takeIf { it > 0 } ?: _playerStats.value.width)
        if (selectedVideoDecoderName.isUnknownLike() || videoMime.isUnknownLike() || bucket.isUnknownLike()) {
            knownBadDecoderNames = emptySet()
            knownBadSurfaceTypes = emptySet()
            return
        }
        val lookupGeneration = retryGeneration
        scope.launch(Dispatchers.IO) {
            val records = runCatching {
                playbackCompatibilityRepository.getKnownBadRecords(
                    deviceFingerprint = Build.FINGERPRINT.orEmpty(),
                    streamType = currentResolvedStreamType.name,
                    videoMimeType = videoMime,
                    resolutionBucket = bucket
                )
            }.getOrElse { throwable ->
                Log.w(TAG, "compatibility-history lookup failed: ${throwable.sanitizedSummary()}")
                emptyList()
            }
            scope.launch {
                if (lookupGeneration != retryGeneration) return@launch
                val actionableRecords = records.filter(::isHighConfidenceKnownBad)
                val names = PlaybackCodecSelector.knownBadDecoderNames(actionableRecords)
                val surfaces = actionableRecords.map { it.key.surfaceType }.toSet()
                knownBadDecoderNames = names
                knownBadSurfaceTypes = surfaces
            }
        }
    }

    private fun shouldUseCompatibilityHistory(): Boolean {
        return isEffectivelyPlaybackStarted() ||
            retryAttempt > 0 ||
            videoStallRecoveryAttempt > 0 ||
            recoveryAudioDecoderPolicyOverride != null ||
            recoveryVideoDecoderPolicyOverride != null ||
            textureViewSessionFallbackAttempted
    }

    private fun isEffectivelyPlaybackStarted(): Boolean =
        hasEffectivePlaybackStarted(
            playbackStarted = playbackStarted,
            liveBufferingRecoveryArmed = liveBufferingRecoveryArmed || playbackStartedRecoveryArmed
        )

    private fun promoteLiveHlsBufferIfNeeded(): Boolean {
        val streamInfo = lastStreamInfo ?: return false
        val mediaId = lastMediaId ?: return false
        val observedFormat = _videoFormat.value.takeUnless(VideoFormat::isEmpty) ?: return false
        val decision = LiveHlsBufferPromotionDecider.decide(
            bufferMode = requestedPlaybackBufferMode,
            resolvedStreamType = currentResolvedStreamType,
            isLive = isCurrentStreamLive(),
            mediaAlreadyPromoted = promotedLiveHlsBufferReasonsByMediaId.get(mediaId) != null,
            currentPolicyLabel = currentBufferPolicyLabel,
            streamInfo = streamInfo,
            observedVideoFormat = observedFormat,
            compatibilityMode = activeVideoDecoderPolicy == ActiveDecoderPolicy.COMPATIBILITY,
            lowMemoryDevice = isLowMemoryPlaybackDevice
        ) ?: return false

        promotedLiveHlsBufferReasonsByMediaId.put(mediaId, decision.qualityReason)
        val wasPlaying = exoPlayer?.playWhenReady ?: true
        Log.i(
            TAG,
            "buffer-policy promote mediaId=$mediaId from=${currentBufferPolicyLabel.orEmpty()} " +
                "to=${decision.policy.label} reason=${decision.qualityReason} " +
                "format=${observedFormat.width}x${observedFormat.height} " +
                "bitrate=${observedFormat.bitrate} hdr=${observedFormat.isHdr} " +
                "target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )
        prepareInternal(
            streamInfo = streamInfo,
            preserveRetryState = true,
            seekPositionMs = null,
            autoPlay = wasPlaying
        )
        return true
    }

    private fun handleVideoStall() {
        val streamInfo = lastStreamInfo ?: return
        videoStallCount++
        _playerStats.value = _playerStats.value.copy(videoStallCount = videoStallCount)

        val wasPlaying = exoPlayer?.playWhenReady ?: true
        val seekPosition = exoPlayer?.currentPosition?.takeIf { it > 0L }
        val nextRecoveryAttempt = videoStallRecoveryAttempt + 1
        val liveReconnectionStall = shouldReconnectLiveStall(
            playbackState = _playbackState.value,
            resolvedStreamType = currentResolvedStreamType,
            recoveryAttempt = nextRecoveryAttempt
        )
        Log.w(
            TAG,
            "video-stall count=$videoStallCount recovered=$videoStallSafeRecoveryPerformed decoder=$selectedVideoDecoderName policy=$activeVideoDecoderPolicy surface=${_renderSurfaceType.value} target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )

        if (!isEffectivelyPlaybackStarted()) {
            Log.w(TAG, "video-stall ignored because playback has not started yet")
            return
        }

        videoStallRecoveryAttempt = nextRecoveryAttempt
        if (liveReconnectionStall) {
            Log.w(TAG, "live-${_playbackState.value.name.lowercase()}-stall reconnect attempt=$videoStallRecoveryAttempt")
            prepareInternal(
                streamInfo = streamInfo,
                preserveRetryState = true,
                seekPositionMs = null,
                autoPlay = wasPlaying
            )
            return
        }

        if (
            shouldFallbackStalledHlsToLiveTs(
                resolvedStreamType = currentResolvedStreamType,
                recoveryAttempt = videoStallRecoveryAttempt
            )
        ) {
            val fallbackStreamInfo = buildLiveTsFallbackStreamInfo(streamInfo)
            if (fallbackStreamInfo != null) {
                Log.w(
                    TAG,
                    "video-stall live-ts-fallback from=HLS attempt=$videoStallRecoveryAttempt target=${PlaybackLogSanitizer.sanitizeUrl(fallbackStreamInfo.url)}"
                )
                prepareInternal(
                    streamInfo = fallbackStreamInfo,
                    preserveRetryState = false,
                    seekPositionMs = null,
                    autoPlay = wasPlaying
                )
                return
            }
        }

        recordCompatibilityFailure("VIDEO_STALL")
        if (!videoStallSafeRecoveryPerformed) {
            videoStallSafeRecoveryPerformed = true
            if (!shouldAttemptAutomaticRecovery(
                    action = AutomaticRecoveryAction.FULL_REPREPARE,
                    resolvedStreamType = currentResolvedStreamType,
                    playbackStarted = playbackStarted
                )
            ) {
                Log.w(
                    TAG,
                    "video-stall reprepare suppressed after playback start for provider streamType=$currentResolvedStreamType"
                )
                _error.tryEmit(
                    PlayerError.DecoderError(
                        "Video playback stalled. Automatic recovery was not attempted to avoid opening another provider connection."
                    )
                )
                return
            }
            Log.w(TAG, "video-stall safe reprepare attempt=$videoStallRecoveryAttempt")
            prepareInternal(
                streamInfo = streamInfo,
                preserveRetryState = true,
                seekPositionMs = seekPosition,
                autoPlay = wasPlaying
            )
            return
        }

        if (tryVideoStallDecoderFallback(streamInfo, seekPosition, wasPlaying)) {
            return
        }

        if (_renderSurfaceType.value == PlayerRenderSurfaceType.TEXTURE_VIEW && !textureViewSessionFallbackAttempted) {
            fallbackTextureViewSurface("VIDEO_STALL")
            return
        }

        _error.tryEmit(
            PlayerError.DecoderError(
                "Video playback stalled on this device. Try another stream or open diagnostics."
            )
        )
    }

    private fun shouldIgnoreLiveHlsStartupStall(): Boolean {
        if (currentResolvedStreamType != ResolvedStreamType.HLS) return false
        if (!isCurrentStreamLive()) return false
        if (hasRenderedFirstVideoFrame) return false
        if (prepareStartMs <= 0L) return false
        return System.currentTimeMillis() - prepareStartMs < LIVE_HLS_STARTUP_GRACE_MS
    }

    private fun tryVideoStallDecoderFallback(
        streamInfo: StreamInfo,
        seekPositionMs: Long?,
        autoPlay: Boolean
    ): Boolean {
        val mediaId = lastMediaId ?: return false
        val fallbackMode = videoDecoderPreferencePolicy.onDecoderInitFailure(requestedVideoDecoderMode, mediaId) ?: return false
        val fallbackPolicy = resolveActiveDecoderPolicy(policyModeFor(requestedVideoDecoderMode, fallbackMode))
        recoveryVideoDecoderPolicyOverride = fallbackPolicy
        Log.w(
            TAG,
            "video-stall decoder fallback=$fallbackMode policy=$fallbackPolicy attempt=$videoStallRecoveryAttempt mediaId=$mediaId target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )
        prepareInternal(
            streamInfo = streamInfo,
            preserveRetryState = true,
            seekPositionMs = seekPositionMs,
            autoPlay = autoPlay
        )
        return true
    }

    private fun recordCompatibilityFailure(failureType: String) {
        val key = currentCompatibilityKey() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                playbackCompatibilityRepository.recordFailure(key, failureType)
            }.onFailure { throwable ->
                Log.w(TAG, "compatibility-history failure record skipped: ${throwable.sanitizedSummary()}")
            }
        }
    }

    private fun recordCompatibilitySuccess() {
        val key = currentCompatibilityKey() ?: return
        scope.launch(Dispatchers.IO) {
            runCatching {
                playbackCompatibilityRepository.recordSuccess(key)
            }.onFailure { throwable ->
                Log.w(TAG, "compatibility-history success record skipped: ${throwable.sanitizedSummary()}")
            }
        }
    }

    private fun currentCompatibilityKey(): PlaybackCompatibilityKey? {
        val decoder = selectedVideoDecoderName.takeUnless { it.isUnknownLike() } ?: return null
        val video = _videoFormat.value
        val width = video.width.takeIf { it > 0 } ?: _playerStats.value.width
        val mime = video.codecV ?: _playerStats.value.videoCodec
        val videoMimeType = mime.takeUnless { it.isUnknownLike() } ?: return null
        val resolutionBucket = resolutionBucket(width).takeUnless { it.isUnknownLike() } ?: return null
        return PlaybackCompatibilityKey(
            deviceFingerprint = Build.FINGERPRINT.orEmpty(),
            deviceModel = Build.MODEL.orEmpty(),
            androidSdk = Build.VERSION.SDK_INT,
            streamType = currentResolvedStreamType.name,
            videoMimeType = videoMimeType,
            resolutionBucket = resolutionBucket,
            decoderName = decoder,
            surfaceType = _renderSurfaceType.value.name
        )
    }

    private fun resolutionBucket(width: Int): String = when {
        width >= 3800 -> "4K"
        width >= 1900 -> "1080P"
        width >= 1200 -> "720P"
        width > 0 -> "SD"
        else -> "UNKNOWN"
    }

    private fun isHighConfidenceKnownBad(record: PlaybackCompatibilityRecord): Boolean {
        val key = record.key
        if (record.failureCount < KNOWN_BAD_FAILURE_THRESHOLD) return false
        if (record.lastSucceededAt > record.lastFailedAt) return false
        if (key.videoMimeType.isUnknownLike()) return false
        if (key.resolutionBucket.isUnknownLike()) return false
        if (key.decoderName.isUnknownLike()) return false
        if (key.surfaceType.isUnknownLike()) return false
        return true
    }

    private fun String.isUnknownLike(): Boolean {
        val normalized = trim().lowercase(java.util.Locale.ROOT)
        return normalized.isEmpty() || normalized == "unknown"
    }

    private fun Throwable.sanitizedSummary(): String {
        val type = this::class.java.simpleName.ifBlank { "Throwable" }
        return "$type: ${PlaybackLogSanitizer.sanitizeMessage(message)}"
    }

    private fun shouldFallbackTextureViewBeforeFirstFrame(stats: PlayerStats): Boolean {
        return shouldFallbackTextureViewWithoutFirstFrame(
            renderSurfaceType = _renderSurfaceType.value,
            sessionSurfaceModeOverride = sessionSurfaceModeOverride,
            fallbackAttempted = textureViewSessionFallbackAttempted,
            hasStreamInfo = lastStreamInfo != null,
            hasRenderedFirstVideoFrame = hasRenderedFirstVideoFrame,
            isCurrentStreamLive = isCurrentStreamLive(),
            playbackState = _playbackState.value,
            elapsedSincePrepareMs = System.currentTimeMillis() - prepareStartMs,
            startupTimeoutMs = TEXTURE_VIEW_STARTUP_TIMEOUT_MS,
            bufferedDurationMs = stats.bufferedDurationMs,
            bufferedStartupThresholdMs = TEXTURE_VIEW_BUFFERED_STARTUP_THRESHOLD_MS,
            selectedVideoDecoderName = selectedVideoDecoderName
        )
    }

    private fun fallbackTextureViewSurface(reason: String) {
        val streamInfo = lastStreamInfo ?: return
        if (_renderSurfaceType.value != PlayerRenderSurfaceType.TEXTURE_VIEW) return
        if (textureViewSessionFallbackAttempted) return

        textureViewSessionFallbackAttempted = true
        sessionSurfaceModeOverride = PlayerSurfaceMode.SURFACE_VIEW
        updateRenderSurfaceForMode()
        recordCompatibilityFailure("TEXTURE_VIEW_$reason")

        val wasPlaying = exoPlayer?.playWhenReady ?: true
        val seekPosition = exoPlayer?.currentPosition?.takeIf {
            currentResolvedStreamType == ResolvedStreamType.PROGRESSIVE && it > 0L
        }
        Log.w(
            TAG,
            "texture-view fallback reason=$reason decoder=$selectedVideoDecoderName policy=$activeVideoDecoderPolicy target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
        )

        viewBinder.attachPlayer(null)
        prepareInternal(
            streamInfo = streamInfo,
            preserveRetryState = true,
            seekPositionMs = seekPosition,
            autoPlay = wasPlaying
        )
    }

    private fun isCurrentStreamLive(): Boolean = currentResolvedStreamType in setOf(
        ResolvedStreamType.HLS,
        ResolvedStreamType.SMOOTH_STREAMING,
        ResolvedStreamType.MPEG_TS_LIVE,
        ResolvedStreamType.RTSP
    )

    private fun handleAudioRendererIssue(error: Exception, source: String) {
        val streamInfo = lastStreamInfo
        if (streamInfo == null) {
            lastSupportErrorMessage = error.message ?: "Audio playback failed."
            _error.tryEmit(PlayerError.DecoderError(error.message ?: "Audio playback failed."))
            return
        }

        if (
            source == "audio-codec" &&
            tryFfmpegAudioFallback(
                trigger = "audio-renderer-$source",
                mimeTypes = audioMimeTypes(),
                detail = error.message
            )
        ) {
            return
        }

        val now = System.currentTimeMillis()
        if (now - lastAudioRendererRecoveryAtMs < AUDIO_RENDERER_RECOVERY_COOLDOWN_MS) {
            Log.e(
                TAG,
                "audio-renderer-failed source=$source target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)} message=${PlaybackLogSanitizer.sanitizeMessage(error.message)}"
            )
            lastSupportErrorMessage = error.message ?: "Audio playback failed for this stream."
            _error.tryEmit(
                PlayerError.DecoderError(
                    error.message ?: "Audio playback failed for this stream."
                )
            )
            return
        }

        lastAudioRendererRecoveryAtMs = now
        val wasPlaying = exoPlayer?.playWhenReady ?: true
        val seekPosition = exoPlayer?.currentPosition
            ?.takeIf { currentResolvedStreamType == ResolvedStreamType.PROGRESSIVE && it > 0L }

        Log.w(
            TAG,
            "audio-renderer-recover source=$source target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)} message=${PlaybackLogSanitizer.sanitizeMessage(error.message)}"
        )

        retryJob?.cancel()
        retryJob = null
        _retryStatus.value = null
        prepareInternal(
            streamInfo = streamInfo,
            preserveRetryState = false,
            seekPositionMs = seekPosition,
            autoPlay = wasPlaying
        )
    }

    private fun tryFfmpegAudioFallback(
        trigger: String,
        mimeTypes: List<String>,
        detail: String?
    ): Boolean {
        val streamInfo = lastStreamInfo ?: return false
        val mediaId = lastMediaId ?: return false
        val availability = ffmpegExtensionSupport.availability()
        ffmpegAvailability = availability
        if (!availability.available) {
            compatibilityDecisionSource = "FFMPEG_UNAVAILABLE"
            Log.w(
                TAG,
                "ffmpeg-audio-fallback unavailable trigger=$trigger target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
            )
            return false
        }
        val supportedMimeTypes = ffmpegExtensionSupport.supportedAudioMimeTypes(mimeTypes)
        val effectiveSupportedMimeTypes = if (mimeTypes.isEmpty()) listOf("<unknown>") else supportedMimeTypes
        if (mimeTypes.isNotEmpty() && supportedMimeTypes.isEmpty()) {
            compatibilityDecisionSource = "FFMPEG_UNSUPPORTED_AUDIO"
            Log.w(
                TAG,
                "ffmpeg-audio-fallback unsupported trigger=$trigger mimeTypes=${mimeTypes.joinToString()} target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
            )
            return false
        }
        val fallbackMode = audioDecoderPreferencePolicy.onDecoderInitFailure(requestedAudioDecoderMode, mediaId)
        if (
            !shouldAttemptFfmpegAudioFallback(
                FfmpegAudioFallbackRequest(
                    requestedMode = requestedAudioDecoderMode,
                    extensionAvailable = availability.available,
                    supportedMimeTypes = effectiveSupportedMimeTypes,
                    fallbackMode = fallbackMode
                )
            )
        ) {
            return false
        }
        compatibilityDecisionSource = "FFMPEG_RETRY:$trigger"
        pendingLearnedAudioFallback = PendingLearnedAudioFallback(
            mediaId = mediaId,
            streamType = currentResolvedStreamType.name,
            audioMimeTypes = supportedMimeTypes,
            detail = detail
        )
        audioOutputPath = "FFMPEG_RETRY_PENDING"

        val wasPlaying = exoPlayer?.playWhenReady ?: true
        val seekPosition = exoPlayer?.currentPosition
            ?.takeIf { currentResolvedStreamType == ResolvedStreamType.PROGRESSIVE && it > 0L }
        Log.w(
            TAG,
            "ffmpeg-audio-fallback retry trigger=$trigger supportedMimeTypes=${effectiveSupportedMimeTypes.joinToString()} version=${availability.version ?: "unknown"} target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)} detail=${PlaybackLogSanitizer.sanitizeMessage(detail)}"
        )

        retryJob?.cancel()
        retryJob = null
        _retryStatus.value = null
        prepareInternal(
            streamInfo = streamInfo,
            preserveRetryState = false,
            seekPositionMs = seekPosition,
            autoPlay = wasPlaying
        )
        return true
    }

    private fun audioMimeTypes(
        tracks: androidx.media3.common.Tracks? = exoPlayer?.currentTracks
    ): List<String> {
        val fromTracks = tracks?.groups
            ?.filter { it.mediaTrackGroup.type == C.TRACK_TYPE_AUDIO }
            ?.flatMap { group ->
                (0 until group.length).mapNotNull { index ->
                    group.mediaTrackGroup.getFormat(index).sampleMimeType
                }
            }
            ?.distinct()
            .orEmpty()
        if (fromTracks.isNotEmpty()) return fromTracks
        val codec = _playerStats.value.audioCodec
        return codec.takeUnless { it.isBlank() || it.equals("Unknown", ignoreCase = true) }
            ?.let(::listOf)
            .orEmpty()
    }

    private fun logFfmpegAvailability() {
        val availability = ffmpegAvailability
        Log.i(
            TAG,
            "ffmpeg-extension available=${availability.available} version=${availability.version ?: "unavailable"}"
        )
    }

    private fun buildPlaybackSupportSnapshot(): String = buildString {
        appendLine("StreamVault Playback Support Snapshot")
        appendLine("requestedAudioDecoderMode=$requestedAudioDecoderMode")
        appendLine("requestedVideoDecoderMode=$requestedVideoDecoderMode")
        appendLine("activeAudioDecoderMode=$activeAudioDecoderMode")
        appendLine("activeVideoDecoderMode=$activeVideoDecoderMode")
        appendLine("activeAudioDecoderPolicy=$activeAudioDecoderPolicy")
        appendLine("activeVideoDecoderPolicy=$activeVideoDecoderPolicy")
        appendLine("audioOutputPreference=$audioOutputPreference")
        appendLine("compatibilityDecisionSource=$compatibilityDecisionSource")
        appendLine("ffmpegAvailable=${ffmpegAvailability.available}")
        appendLine("ffmpegVersion=${ffmpegAvailability.version ?: "unavailable"}")
        appendLine("audioOutputPath=$audioOutputPath")
        appendLine("videoDecoderName=$selectedVideoDecoderName")
        appendLine("audioDecoderName=$selectedAudioDecoderName")
        appendLine("videoCodec=${_playerStats.value.videoCodec}")
        appendLine("audioCodec=${_playerStats.value.audioCodec}")
        appendLine("streamType=$currentResolvedStreamType")
        appendLine("target=${PlaybackLogSanitizer.sanitizeUrl(lastStreamInfo?.url)}")
        appendLine("lastError=${PlaybackLogSanitizer.sanitizeMessage(lastSupportErrorMessage)}")
    }

    private fun shouldRefreshPlaybackSupportSnapshot(): Boolean {
        if (!isLowMemoryPlaybackDevice) return true
        return _playbackState.value != PlaybackState.BUFFERING || _playerStats.value.ttffMs > 0L
    }

    private fun resolveStartupAudioOutputPath(): String = when {
        audioOutputPreference == AudioOutputPreference.PREFER_PASSTHROUGH -> "PASSTHROUGH_PREFERRED"
        audioOutputPreference == AudioOutputPreference.DISABLE_PASSTHROUGH -> "PASSTHROUGH_DISABLED"
        activeAudioDecoderMode == DecoderMode.SOFTWARE -> "SOFTWARE_PREFERRED"
        else -> "AUTO"
    }

    private fun classifyAudioOutputPath(decoderName: String): String {
        val normalized = decoderName.lowercase()
        return when {
            "ffmpeg" in normalized -> "FFMPEG_SOFTWARE"
            activeAudioDecoderMode == DecoderMode.SOFTWARE -> "PLATFORM_SOFTWARE"
            else -> "PLATFORM_DECODER"
        }
    }

    private fun buildPlaybackPreparationPlan(
        streamInfo: StreamInfo,
        preload: Boolean,
        playbackStarted: () -> Boolean
    ): PlaybackPreparationPlan {
        val resolvedStreamType = StreamTypeResolver.resolve(streamInfo)
        val timeoutProfile = PlayerTimeoutProfile.resolve(streamInfo, resolvedStreamType, preload = preload)
        val retryContext = PlaybackRetryContext(resolvedStreamType, timeoutProfile)
        return PlaybackPreparationPlan(
            resolvedStreamType = resolvedStreamType,
            timeoutProfile = timeoutProfile,
            retryContext = retryContext,
            retryPolicy = PlayerRetryPolicy(
                streamContext = retryContext,
                fastRetryOnTransientFailures = { fastRetryOnTransientFailures },
                playbackStarted = { playbackStarted() }
            )
        )
    }

    private fun shouldAttemptFfmpegAudioFallback(request: FfmpegAudioFallbackRequest): Boolean {
        if (request.requestedMode != DecoderMode.AUTO) return false
        if (!request.extensionAvailable) return false
        if (request.fallbackMode != DecoderMode.SOFTWARE) return false
        return request.supportedMimeTypes.isNotEmpty()
    }

    private fun forceAmbiguousDecoderSoftwareFallback(
        audioFallbackMode: DecoderMode?,
        videoFallbackMode: DecoderMode?
    ): Boolean {
        if (!shouldForceSoftwareForAmbiguousDecoderFallback(audioFallbackMode, videoFallbackMode)) {
            return false
        }
        recoveryAudioDecoderModeOverride = DecoderMode.SOFTWARE
        recoveryVideoDecoderModeOverride = DecoderMode.SOFTWARE
        recoveryAudioDecoderPolicyOverride = ActiveDecoderPolicy.SOFTWARE_PREFERRED
        recoveryVideoDecoderPolicyOverride = ActiveDecoderPolicy.SOFTWARE_PREFERRED
        return true
    }

    private fun clearDecoderRecoveryOverrides() {
        recoveryAudioDecoderModeOverride = null
        recoveryVideoDecoderModeOverride = null
        recoveryAudioDecoderPolicyOverride = null
        recoveryVideoDecoderPolicyOverride = null
    }

    private fun handlePlaybackSetupFailure(
        error: Exception,
        streamInfo: StreamInfo,
        mediaId: String,
        seekPositionMs: Long?,
        autoPlay: Boolean
    ) {
        val category = PlayerErrorClassifier.classify(error)
        Log.e(
            TAG,
            "prepare-failed category=$category audioDecoderPreference=$activeAudioDecoderMode videoDecoderPreference=$activeVideoDecoderMode audioPolicy=$activeAudioDecoderPolicy videoPolicy=$activeVideoDecoderPolicy target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)} message=${PlaybackLogSanitizer.sanitizeMessage(error.message)}",
            error
        )

        if (category == PlaybackErrorCategory.DECODER || category == PlaybackErrorCategory.FORMAT_UNSUPPORTED) {
            recordCompatibilityFailure("DECODER_INIT")
            val audioFallbackMode = audioDecoderPreferencePolicy.onDecoderInitFailure(
                requestedAudioDecoderMode,
                mediaId
            )
            val videoFallbackMode = videoDecoderPreferencePolicy.onDecoderInitFailure(
                requestedVideoDecoderMode,
                mediaId
            )
            if (forceAmbiguousDecoderSoftwareFallback(audioFallbackMode, videoFallbackMode)) {
                Log.w(
                    TAG,
                    "decoder-setup audioFallback=$audioFallbackMode videoFallback=$videoFallbackMode mediaId=$mediaId target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
                )
                recreatePlayer()
                prepareInternal(streamInfo, preserveRetryState = true, seekPositionMs = seekPositionMs, autoPlay = autoPlay)
                return
            }
        }

        recreatePlayer()
        _retryStatus.value = null
        lastSupportErrorMessage = error.message
        _error.tryEmit(PlayerError.fromException(error))
        _playbackState.value = PlaybackState.ERROR
    }

    private fun handlePlaybackError(error: PlaybackException) {
        retryJob?.cancel()
        retryJob = null
        val streamInfo = lastStreamInfo
        val mediaId = lastMediaId
        val retryPolicy = currentRetryPolicy
        val retryContext = currentRetryContext
        val category = PlayerErrorClassifier.classify(error)
        val effectivePlaybackStarted = isEffectivelyPlaybackStarted()

        if (streamInfo == null || mediaId == null || retryPolicy == null || retryContext == null) {
            _retryStatus.value = null
            lastSupportErrorMessage = error.message
            _error.tryEmit(PlayerError.fromException(error))
            _playbackState.value = PlaybackState.ERROR
            return
        }

        if (category == PlaybackErrorCategory.DECODER || category == PlaybackErrorCategory.FORMAT_UNSUPPORTED) {
            val keepHardwareForLiveHls = currentResolvedStreamType == ResolvedStreamType.HLS && isCurrentStreamLive()
            val fallbackMode = if (keepHardwareForLiveHls) {
                null
            } else {
                val audioFallbackMode = audioDecoderPreferencePolicy.onDecoderInitFailure(
                    requestedAudioDecoderMode,
                    mediaId
                )
                val videoFallbackMode = videoDecoderPreferencePolicy.onDecoderInitFailure(
                    requestedVideoDecoderMode,
                    mediaId
                )
                if (forceAmbiguousDecoderSoftwareFallback(audioFallbackMode, videoFallbackMode)) {
                    DecoderMode.SOFTWARE
                } else {
                    null
                }
            }
            if (fallbackMode != null) {
                Log.w(
                    TAG,
                    "decoder-preference fallback=$fallbackMode mediaId=$mediaId target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
                )
                prepareInternal(streamInfo, preserveRetryState = true, seekPositionMs = exoPlayer?.currentPosition, autoPlay = true)
                return
            } else if (keepHardwareForLiveHls) {
                Log.w(
                    TAG,
                    "decoder-preference retained-hardware live-hls target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
                )
            }
        }

        if (
            shouldFallbackMalformedHlsToLiveTs(
                category = category,
                resolvedStreamType = currentResolvedStreamType,
                playbackStarted = effectivePlaybackStarted
            )
        ) {
            val fallbackStreamInfo = buildLiveTsFallbackStreamInfo(streamInfo)
            if (fallbackStreamInfo != null) {
                Log.w(
                    TAG,
                    "source-malformed live-ts-fallback from=HLS target=${PlaybackLogSanitizer.sanitizeUrl(fallbackStreamInfo.url)}"
                )
                prepareInternal(
                    fallbackStreamInfo,
                    preserveRetryState = false,
                    seekPositionMs = null,
                    autoPlay = true
                )
                return
            }
        }

        val nextAttempt = resolveRetryAttemptForCategory(
            currentAttempt = retryAttempt,
            lastCategory = lastRetryCategory,
            nextCategory = category
        )
        if (retryPolicy.shouldRetry(error, retryContext, effectivePlaybackStarted, nextAttempt)) {
            val delayMs = retryPolicy.retryDelayMs(error, nextAttempt)
            val player = exoPlayer
            val retrySeekPositionMs = resolveRetrySeekPositionMs(
                category = category,
                resolvedStreamType = currentResolvedStreamType,
                currentPositionMs = player?.currentPosition,
                durationMs = player?.duration,
                isCurrentMediaItemLive = player?.isCurrentMediaItemLive == true,
                playbackStarted = effectivePlaybackStarted
            )
            // retryGeneration captured and checked on Main — safe with
            // Dispatchers.Main.immediate. If the scope dispatcher is ever changed
            // (e.g. in tests), convert retryGeneration to AtomicLong.
            val scheduledRetryGeneration = retryGeneration
            retryAttempt = nextAttempt
            lastRetryCategory = category
            _retryStatus.value = PlayerRetryStatus(
                attempt = nextAttempt,
                maxAttempts = retryPolicy.maxAttempts(error, effectivePlaybackStarted),
                delayMs = delayMs
            )
            Log.w(
                TAG,
                "retry category=$category attempt=$nextAttempt delayMs=$delayMs reason=${retryPolicy.retryReason(error)} " +
                    "errorCode=${error.errorCodeName} causeChain=${formatErrorCauseChain(error)} " +
                    "target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)}"
            )
            retryJob = scope.launch {
                delay(delayMs)
                if (
                    scheduledRetryGeneration != retryGeneration ||
                    lastMediaId != mediaId ||
                    retryAttempt != nextAttempt ||
                    lastRetryCategory != category
                ) {
                    return@launch
                }
                retryJob = null
                if (category == PlaybackErrorCategory.LIVE_WINDOW &&
                    currentResolvedStreamType != ResolvedStreamType.MPEG_TS_LIVE
                ) {
                    exoPlayer?.seekToDefaultPosition()
                }
                prepareInternal(
                    streamInfo,
                    preserveRetryState = shouldPreservePlaybackStateForRetry(
                        category = category,
                        playbackStarted = effectivePlaybackStarted,
                        liveBufferingRecoveryArmed = false
                    ),
                    seekPositionMs = retrySeekPositionMs,
                    autoPlay = true
                )
            }
            return
        }

        _retryStatus.value = null
        Log.e(
            TAG,
            "fatal-error category=$category reason=${retryPolicy.retryReason(error)} " +
                "errorCode=${error.errorCodeName} causeChain=${formatErrorCauseChain(error)} " +
                "target=${PlaybackLogSanitizer.sanitizeUrl(streamInfo.url)} message=${PlaybackLogSanitizer.sanitizeMessage(error.message)}"
        )
        lastSupportErrorMessage = error.message
        _error.tryEmit(PlayerError.fromException(error))
        _playbackState.value = PlaybackState.ERROR
    }

    private fun formatErrorCauseChain(error: Throwable): String {
        return generateSequence(error) { it.cause }
            .take(6)
            .joinToString(" <- ") { cause ->
                val className = cause::class.java.simpleName.ifBlank { cause::class.java.name }
                val message = PlaybackLogSanitizer.sanitizeMessage(cause.message)
                "$className($message)"
            }
            .take(600)
    }
}
