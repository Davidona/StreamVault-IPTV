package com.streamvault.player.stats

import android.os.SystemClock
import androidx.media3.common.Format
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import com.streamvault.domain.model.VideoFormat
import com.streamvault.player.PlaybackState
import com.streamvault.player.PlayerStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Collects playback position, duration, and diagnostic statistics from an [ExoPlayer] instance
 * and exposes them as [MutableStateFlow]s for Compose UI observation.
 *
 * ## Threading Model
 * **All public methods must be called on the Main/UI thread.** This is safe because:
 * - ExoPlayer delivers all [androidx.media3.exoplayer.analytics.AnalyticsListener] callbacks
 *   on the application looper (Main thread).
 * - [androidx.media3.common.Player.Listener] callbacks are also delivered on Main.
 * - The polling coroutine runs on [Dispatchers.Main.immediate], which dispatches
 *   synchronously if already on Main and never bounces to a background thread pool.
 *
 * This design removes the original cross-dispatcher hop pattern
 * (`launch(Dispatchers.Default)` → `withContext(Dispatchers.Main.immediate)` → back),
 * which caused 2–3 unnecessary thread context switches every 250 ms per active player.
 * For a 4-player multiview layout, that was up to 12 wasted thread hops per second.
 *
 * Because everything runs on one thread, fields are plain `var`s with no `@Volatile`
 * or `AtomicInteger` overhead.
 */
class PlayerStatsCollector(
    private val scopeProvider: () -> CoroutineScope,
    private val currentPosition: MutableStateFlow<Long>,
    private val duration: MutableStateFlow<Long>,
    private val videoFormat: MutableStateFlow<VideoFormat>,
    private val playerStats: MutableStateFlow<PlayerStats>,
    private val playbackState: MutableStateFlow<PlaybackState>,
    /** Cumulative network bytes received for playback; see [PlayerTransferByteCounter]. */
    private val networkBytesProvider: () -> Long = { 0L },
    /** Locked nominal frame rate detected from rendered-frame timestamps; see [FrameRateDetector]. */
    private val frameRateDetector: FrameRateDetector = FrameRateDetector(),
    private val clockMs: () -> Long = SystemClock::elapsedRealtime
) {
    // All fields are Main-thread-only. No @Volatile or AtomicInteger required.
    private var pollingJob: Job? = null
    private var playerProvider: (() -> ExoPlayer?)? = null
    private var lastVideoFormat: Format? = null
    private var lastAudioFormat: Format? = null
    private var lastFrameRate = 0f
    private var lastBandwidthEstimate = 0L
    private var droppedFrames = 0
    private val bitrateMeter = BitrateMeter()

    fun bind(playerProvider: () -> ExoPlayer?) {
        this.playerProvider = playerProvider
    }

    fun reset() {
        lastVideoFormat = null
        lastAudioFormat = null
        lastFrameRate = 0f
        lastBandwidthEstimate = 0L
        droppedFrames = 0
        bitrateMeter.reset()
        frameRateDetector.reset()
        currentPosition.value = 0L
        duration.value = 0L
        videoFormat.value = VideoFormat(0, 0)
        playerStats.value = PlayerStats()
    }

    /** Called by [androidx.media3.exoplayer.analytics.AnalyticsListener] — always on Main. */
    fun onVideoFormatChanged(format: Format) {
        if (format != lastVideoFormat) frameRateDetector.reset()
        lastVideoFormat = format
        // A new rendition may omit FPS or use a different cadence. Never reuse its predecessor's
        // declared/measured rate when choosing a display refresh rate.
        lastFrameRate = format.frameRate.takeIf { it.isFinite() && it > 0f } ?: 0f
    }

    /** Called by [androidx.media3.exoplayer.analytics.AnalyticsListener] — always on Main. */
    fun onAudioFormatChanged(format: Format) {
        lastAudioFormat = format
    }

    /**
     * Media3 reports batches, so this total can lag DecoderCounters.droppedBufferCount
     * until the renderer reaches its notification threshold or stops. They are overlapping
     * measurements of the same drops; adding the native count would double count them.
     * Called by [androidx.media3.exoplayer.analytics.AnalyticsListener] on Main.
     */
    fun onDroppedFrames(count: Int) {
        droppedFrames += count
    }

    /** Called by [androidx.media3.exoplayer.analytics.AnalyticsListener] — always on Main. */
    fun onBandwidthEstimate(bitrateEstimate: Long) {
        if (bitrateEstimate > 0L) {
            lastBandwidthEstimate = bitrateEstimate
        }
    }

    /** Called by [androidx.media3.common.Player.Listener] — always on Main. */
    fun incrementRebufferCount() {
        playerStats.value = playerStats.value.copy(rebufferCount = playerStats.value.rebufferCount + 1)
    }

    /**
     * Starts the stat polling loop on [Dispatchers.Main.immediate].
     *
     * Updates are throttled into three tiers so we don't emit more StateFlow updates
     * than the UI can meaningfully display:
     *
     * | Data | Interval |
     * |---|---|
     * | `currentPosition` / `duration` | Every 250 ms |
     * | `bufferedDurationMs` | Every 500 ms, including requested buffering |
     * | Codec / bitrate / dropped frames | Every 1 000 ms |
     */
    fun updatePlaybackActivity(isPlaying: Boolean, playWhenReady: Boolean) {
        if (isPlaying || playWhenReady && playbackState.value == PlaybackState.BUFFERING) {
            start()
        } else {
            stop()
        }
    }

    fun start() {
        if (pollingJob?.isActive == true) return
        pollingJob = scopeProvider().launch(Dispatchers.Main.immediate) {
            // Initialise to their trigger thresholds so the first poll emits
            // buffered and stats updates immediately (consistent with player-start UX).
            var bufferedElapsedMs = BUFFERED_UPDATE_INTERVAL_MS
            var statsElapsedMs = STATS_UPDATE_INTERVAL_MS

            while (isActive) {
                val player = playerProvider?.invoke()

                if (player != null) {
                    // --- Tier 1: Position + duration (every tick, READY only) -----
                    // During BUFFERING the position is stalled and live duration is
                    // C.TIME_UNSET (-1), making every emit a no-op equality check.
                    // Skipping avoids wasted CPU on the main thread.
                    if (playbackState.value == PlaybackState.READY) {
                        currentPosition.value = player.currentPosition
                        duration.value = player.duration.coerceAtLeast(0L)
                    }

                    // --- Tier 2: Buffered duration (every 500 ms) ------------------
                    if (bufferedElapsedMs >= BUFFERED_UPDATE_INTERVAL_MS) {
                        val buffered = player.totalBufferedDuration.coerceAtLeast(0L)
                        playerStats.value = playerStats.value.copy(bufferedDurationMs = buffered)
                        bufferedElapsedMs = 0L
                    }

                    // --- Tier 3: Codec / bitrate / dropped frames (every 1 000 ms) -
                    if (statsElapsedMs >= STATS_UPDATE_INTERVAL_MS) {
                        val video = lastVideoFormat
                        val audio = lastAudioFormat
                        val dropped = droppedFrames
                        val measuredBitrate = bitrateMeter.addSample(clockMs(), networkBytesProvider())
                        val measuredFrameRate = frameRateDetector.lockedFrameRate

                        videoFormat.value = VideoFormat(
                            width  = video?.width?.takeIf  { it > 0 } ?: 0,
                            height = video?.height?.takeIf { it > 0 } ?: 0,
                            frameRate = if (lastFrameRate > 0f) lastFrameRate else measuredFrameRate,
                            bitrate   = video?.bitrate?.takeIf { it > 0 } ?: 0,
                            codecV = video?.sampleMimeType ?: video?.codecs,
                            codecA = audio?.sampleMimeType ?: audio?.codecs,
                            pixelWidthHeightRatio = video?.pixelWidthHeightRatio?.takeIf { it > 0f } ?: 1f,
                            isHdr = video?.colorInfo?.colorTransfer in setOf(
                                C.COLOR_TRANSFER_ST2084,
                                C.COLOR_TRANSFER_HLG
                            )
                        )
                        playerStats.value = playerStats.value.copy(
                            videoCodec        = video?.sampleMimeType ?: video?.codecs
                                                ?: playerStats.value.videoCodec,
                            audioCodec        = audio?.sampleMimeType ?: audio?.codecs
                                                ?: playerStats.value.audioCodec,
                            videoBitrate      = video?.bitrate?.takeIf { it > 0 }
                                                ?: playerStats.value.videoBitrate,
                            frameRate         = lastFrameRate,
                            measuredFrameRate = measuredFrameRate,
                            measuredBitrate   = measuredBitrate,
                            droppedFrames     = dropped,
                            width             = video?.width?.takeIf  { it > 0 }
                                                ?: playerStats.value.width,
                            height            = video?.height?.takeIf { it > 0 }
                                                ?: playerStats.value.height,
                            bandwidthEstimate = lastBandwidthEstimate
                        )
                        statsElapsedMs = 0L
                    }
                }

                // During buffering only diagnostics advance; do not poll stalled position at 4 Hz.
                val intervalMs = if (playbackState.value == PlaybackState.BUFFERING) {
                    BUFFERED_UPDATE_INTERVAL_MS
                } else POSITION_UPDATE_INTERVAL_MS
                delay(intervalMs)
                bufferedElapsedMs += intervalMs
                statsElapsedMs += intervalMs
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private companion object {
        /** Base polling interval. All other intervals are multiples of this. */
        private const val POSITION_UPDATE_INTERVAL_MS = 250L

        /** Buffer diagnostics remain current while position polling is suspended. */
        private const val BUFFERED_UPDATE_INTERVAL_MS = 500L

        /** Heavy stats (codec / bitrate / dropped frames) once per second. */
        private const val STATS_UPDATE_INTERVAL_MS = 1_000L
    }
}
