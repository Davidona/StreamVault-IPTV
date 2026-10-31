@file:androidx.media3.common.util.UnstableApi

package com.streamvault.app.tvinput

import android.content.ContentUris
import android.content.Context
import android.media.tv.TvInputManager
import android.media.tv.TvInputService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.smoothstreaming.SsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.util.BoundedExpiringCache
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.sync.AutomaticSyncActivity
import com.streamvault.player.playback.LiveStartupWatchdog
import com.streamvault.player.playback.PlaybackTransferTracker
import com.streamvault.player.playback.StartupRefreshGate
import com.streamvault.player.playback.LiveMpegTsEndRecovery
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.player.playback.applyUnsafeTlsBypass
import com.streamvault.player.playback.applyPlaybackTransportPolicy
import com.streamvault.player.playback.applyPlaybackTimestampInitializationPolicy
import com.streamvault.player.playback.applyPlaybackRenditionLoadingPolicy
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Proxy
import okhttp3.OkHttpClient
import javax.inject.Inject
import java.io.IOException

@AndroidEntryPoint
class StreamVaultTvInputService : TvInputService() {

    @Inject
    lateinit var channelRepository: ChannelRepository

    @Inject
    lateinit var okHttpClient: OkHttpClient

    // TV input sessions can observe many proxy/transport combinations over the service
    // lifetime. Keep only a small, expiring set of derived clients; eviction drops the cache
    // reference and never closes a client that may still be serving an active tune.
    private val playbackClients = BoundedExpiringCache<PlaybackClientKey, OkHttpClient>(
        maxEntries = 8,
        ttlMillis = 15L * 60L * 1_000L
    )

    override fun onCreateSession(inputId: String): Session = StreamVaultSession(this)

    private inner class StreamVaultSession(context: Context) : Session(context) {
        private val playbackOwner = Any()
        private var tunedProviderId: Long? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val startupWatchdog = LiveStartupWatchdog()
        private val startupTransfers = PlaybackTransferTracker()
        private val startupRefreshGate = StartupRefreshGate()
        private val endRecovery = LiveMpegTsEndRecovery()
        private val surfaceRecovery = LiveMpegTsEndRecovery()
        private var surfaceRecoveryGeneration: Long? = null
        private var endRecoveryGeneration: Long? = null
        private val pendingLoadRetries = mutableSetOf<Long>()
        private var startupStream: StreamInfo? = null
        private var firstVideoFrame = false
        private var outputSurface: Surface? = null
        private var startupTerminal = false
        private var tuneGeneration = 0L
        private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() { firstVideoFrame = true }

                override fun onPlayerError(error: PlaybackException) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG,
                        "lifecycle event=player-error code=${error.errorCode} surfaceValid=${outputSurface?.isValid} stream=${startupStream != null} terminal=$startupTerminal")
                    val info = startupStream ?: return
                    if (startupTerminal || startupRefreshGate.isPending || endRecovery.isPending || surfaceRecovery.isPending) return
                    val eligible = canRecoverTvInputSurfaceError(info, inferStreamType(info), firstVideoFrame,
                        player.playWhenReady, pendingLoadRetries.isNotEmpty(), outputSurface != null, error.errorCode)
                    if (surfaceRecovery.onEnded(android.os.SystemClock.elapsedRealtime(), eligible)) {
                        surfaceRecoveryGeneration = tuneGeneration
                        AutomaticSyncActivity.stopped(playbackOwner)
                        notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
                        Log.i(TAG, "surface-recovery awaiting-surface attempt=1")
                        player.stop()
                        return
                    }
                    startupTerminal = true
                    AutomaticSyncActivity.stopped(playbackOwner)
                    notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> notifyVideoAvailable()
                        Player.STATE_BUFFERING -> notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
                        Player.STATE_ENDED -> handleStreamEnded()
                        Player.STATE_IDLE -> Unit
                    }
                }
            })
            addAnalyticsListener(object : AnalyticsListener {
                override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo,
                    mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                    if (wasCanceled) pendingLoadRetries.remove(loadEventInfo.loadTaskId)
                    else pendingLoadRetries.add(loadEventInfo.loadTaskId)
                }

                override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                    pendingLoadRetries.remove(loadEventInfo.loadTaskId)
                }

                override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime,
                    loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                    pendingLoadRetries.remove(loadEventInfo.loadTaskId)
                }
            })
        }

        private val mainHandler = Handler(Looper.getMainLooper())

        init {
            scope.launch {
                while (isActive) {
                    delay(1_000L)
                    val info = startupStream
                    // Opt-in diagnostics for the separate Android TV input player. Avoid
                    // URLs, provider identifiers and credentials in playback health logs.
                    if (info != null && Log.isLoggable(TAG, Log.DEBUG)) {
                        val counters = player.videoDecoderCounters
                        Log.d(TAG, "health generation=$tuneGeneration state=${player.playbackState}" +
                            " playing=${player.isPlaying} positionMs=${player.currentPosition}" +
                            " bufferedMs=${player.totalBufferedDuration} firstFrame=$firstVideoFrame" +
                            " rendered=${counters?.renderedOutputBufferCount ?: 0}" +
                            " dropped=${counters?.droppedBufferCount ?: 0}" +
                            " error=${player.playerError?.errorCode}" +
                            " owned=${tunedProviderId?.let { AutomaticSyncActivity.isProviderPlaybackActive(it) } ?: false}" +
                            " globalOwned=${AutomaticSyncActivity.hasPlayback}")
                    }
                    if (surfaceRecovery.isPending) {
                        when (surfaceRecovery.evaluate(
                            android.os.SystemClock.elapsedRealtime(),
                            canResume = info != null && !startupTerminal &&
                                surfaceRecoveryGeneration == tuneGeneration && player.playWhenReady &&
                                outputSurface?.isValid == true,
                            hasActiveTransfers = startupTransfers.hasActiveTransfers
                        )) {
                            StartupRefreshGate.Action.READY -> info?.let {
                                Log.i(TAG, "surface-recovery transfers-drained refresh=1")
                                prepareStream(it, preserveEpisode = true)
                            }
                            StartupRefreshGate.Action.GIVE_UP -> {
                                Log.w(TAG, "surface-recovery abandoned reason=transfers-not-drained")
                                failStartup()
                            }
                            StartupRefreshGate.Action.WAIT -> Unit
                        }
                        continue
                    }
                    if (endRecovery.isPending) {
                        when (endRecovery.evaluate(
                            android.os.SystemClock.elapsedRealtime(),
                            canResume = info != null && !startupTerminal &&
                                endRecoveryGeneration == tuneGeneration && player.playWhenReady &&
                                outputSurface?.isValid == true,
                            hasActiveTransfers = startupTransfers.hasActiveTransfers
                        )) {
                            StartupRefreshGate.Action.READY -> info?.let {
                                Log.i(TAG, "live-ts-end transfers-drained refresh=1")
                                prepareStream(it, preserveEpisode = true)
                            }
                            StartupRefreshGate.Action.GIVE_UP -> {
                                Log.w(TAG, "live-ts-end refresh-abandoned reason=transfers-not-drained")
                                failStartup()
                            }
                            StartupRefreshGate.Action.WAIT -> Unit
                        }
                        continue
                    }
                    if (startupRefreshGate.isPending) {
                        when (startupRefreshGate.evaluate(
                            android.os.SystemClock.elapsedRealtime(),
                            canResume = info != null && !startupTerminal && player.playWhenReady && outputSurface?.isValid == true,
                            hasActiveTransfers = startupTransfers.hasActiveTransfers
                        )) {
                            StartupRefreshGate.Action.READY -> info?.let { prepareStream(it, true) }
                            StartupRefreshGate.Action.GIVE_UP -> failStartup()
                            StartupRefreshGate.Action.WAIT -> Unit
                        }
                        continue
                    }
                    val action = startupWatchdog.evaluate(
                        nowMs = android.os.SystemClock.elapsedRealtime(),
                        eligible = info != null && !startupTerminal &&
                            info.drmInfo == null && inferStreamType(info) != StreamType.RTSP &&
                            info.playbackContentKind !in setOf(PlaybackContentKind.ON_DEMAND, PlaybackContentKind.CATCH_UP) &&
                            outputSurface?.isValid == true && player.playWhenReady && player.playerError == null &&
                            player.playbackState in setOf(Player.STATE_BUFFERING, Player.STATE_READY) &&
                            pendingLoadRetries.isEmpty(),
                        firstVideoFrameRendered = firstVideoFrame,
                        audioOnlyPlaying = player.isPlaying && !player.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO),
                        bufferedDurationMs = player.totalBufferedDuration,
                        sourceRetryAlreadyUsed = endRecovery.hasAttempted
                    )
                    if (action == LiveStartupWatchdog.Action.NONE || info == null) continue
                    Log.w("StreamVaultTvInput", "startup-watchdog action=$action")
                    if (action == LiveStartupWatchdog.Action.REFRESH_SOURCE) {
                        startupRefreshGate.begin(android.os.SystemClock.elapsedRealtime())
                        player.stop()
                    } else {
                        failStartup()
                    }
                }
            }
        }

        override fun onTune(channelUri: Uri): Boolean {
            val generation = ++tuneGeneration
            startupStream = null
            startupTerminal = false
            startupWatchdog.reset()
            surfaceRecovery.reset()
            surfaceRecoveryGeneration = null
            startupRefreshGate.clear()
            endRecovery.reset()
            endRecoveryGeneration = null
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
            scope.launch {
                tune(channelUri, generation)
            }
            return true
        }

        override fun onSetSurface(surface: Surface?): Boolean {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG,
                "lifecycle event=set-surface-begin newPresent=${surface != null} newValid=${surface?.isValid} oldPresent=${outputSurface != null} oldValid=${outputSurface?.isValid}")
            if (outputSurface !== surface) {
                startupWatchdog.pauseClock()
                startupRefreshGate.pauseClock()
                endRecovery.pauseClock()
                surfaceRecovery.pauseClock()
            }
            outputSurface = surface
            player.setVideoSurface(surface)
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "lifecycle event=set-surface-end")
            return true
        }

        override fun onSetStreamVolume(volume: Float) {
            player.volume = volume
        }

        override fun onSetCaptionEnabled(enabled: Boolean) = Unit

        override fun onRelease() {
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG,
                "lifecycle event=release-begin surfacePresent=${outputSurface != null} surfaceValid=${outputSurface?.isValid}")
            AutomaticSyncActivity.stopped(playbackOwner)
            tuneGeneration++
            startupStream = null
            surfaceRecovery.reset()
            surfaceRecoveryGeneration = null
            startupRefreshGate.clear()
            endRecovery.reset()
            endRecoveryGeneration = null
            scope.cancel()
            player.release()
            if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG,
                "lifecycle event=release-end owned=${tunedProviderId?.let { AutomaticSyncActivity.isProviderPlaybackActive(it) } ?: false} globalOwned=${AutomaticSyncActivity.hasPlayback}")
        }

        private suspend fun tune(channelUri: Uri, generation: Long) {
            val channelRef = withContext(Dispatchers.IO) { loadChannelRef(channelUri) }
            if (generation != tuneGeneration) return
            if (channelRef == null) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return
            }

            val channel = channelRepository.getChannel(channelRef.channelId)
            if (generation != tuneGeneration) return
            if (channel == null) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return
            }

            val streamInfo = channelRepository.getStreamInfo(channel).getOrNull()
            if (generation != tuneGeneration) return
            if (streamInfo == null) {
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
                return
            }

            notifyContentAllowed()
            notifyChannelRetuned(channelUri)
            tunedProviderId = channel.providerId
            prepareStream(streamInfo, preserveEpisode = false)
        }

        private fun prepareStream(streamInfo: StreamInfo, preserveEpisode: Boolean) {
            surfaceRecovery.reset(preserveAttempt = preserveEpisode)
            surfaceRecoveryGeneration = null
            startupRefreshGate.clear()
            endRecovery.reset(preserveAttempt = preserveEpisode)
            endRecoveryGeneration = null
            startupStream = streamInfo
            startupTerminal = false
            firstVideoFrame = false
            pendingLoadRetries.clear()
            startupWatchdog.reset(preserveEpisode)
            // This player bypasses Media3PlayerEngine; own its background-sync signal
            // for the whole tune, including buffering and drain-safe source refreshes.
            AutomaticSyncActivity.started(playbackOwner, tunedProviderId)
            player.setMediaSource(buildMediaSource(streamInfo))
            player.prepare()
            player.playWhenReady = true
        }

        private fun failStartup() {
            surfaceRecovery.reset(preserveAttempt = true)
            surfaceRecoveryGeneration = null
            startupRefreshGate.clear()
            endRecovery.reset(preserveAttempt = true)
            endRecoveryGeneration = null
            startupTerminal = true
            player.playWhenReady = false
            player.stop()
            AutomaticSyncActivity.stopped(playbackOwner)
            notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
        }

        private fun handleStreamEnded() {
            val info = startupStream ?: return
            if (startupTerminal || endRecovery.isPending) return
            val eligible = canRecoverTvInputEnd(info, inferStreamType(info), firstVideoFrame,
                player.playWhenReady, pendingLoadRetries.isNotEmpty() || startupRefreshGate.isPending)
            if (endRecovery.onEnded(android.os.SystemClock.elapsedRealtime(), eligible)) {
                endRecoveryGeneration = tuneGeneration
                Log.w(TAG, "live-ts-end refresh-requested attempt=1")
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_TUNING)
                player.stop()
            } else {
                Log.w(TAG, "live-ts-end terminal eligible=$eligible exhausted=${endRecovery.hasAttempted}")
                startupTerminal = true
                player.playWhenReady = false
                AutomaticSyncActivity.stopped(playbackOwner)
                notifyVideoUnavailable(TvInputManager.VIDEO_UNAVAILABLE_REASON_UNKNOWN)
            }
        }

        private fun buildMediaSource(streamInfo: StreamInfo): MediaSource {
            val streamType = inferStreamType(streamInfo)
            val mediaItem = MediaItem.fromUri(streamInfo.url)
            return when (streamType) {
                StreamType.HLS -> HlsMediaSource.Factory(buildHttpDataSource(streamInfo))
                    .applyPlaybackTimestampInitializationPolicy(streamInfo.playbackContentKind)
                    .applyPlaybackRenditionLoadingPolicy(streamInfo.playbackContentKind)
                    .createMediaSource(mediaItem)
                StreamType.DASH -> DashMediaSource.Factory(buildHttpDataSource(streamInfo)).createMediaSource(mediaItem)
                StreamType.SMOOTH_STREAMING -> SsMediaSource.Factory(buildHttpDataSource(streamInfo)).createMediaSource(mediaItem)
                StreamType.RTSP -> RtspMediaSource.Factory().createMediaSource(mediaItem)
                StreamType.MPEG_TS,
                StreamType.PROGRESSIVE,
                StreamType.UNKNOWN -> ProgressiveMediaSource.Factory(buildHttpDataSource(streamInfo)).createMediaSource(mediaItem)
            }
        }

        private fun buildHttpDataSource(streamInfo: StreamInfo): DataSource.Factory {
            val factory = OkHttpDataSource.Factory(okHttpClient.forPlayback(streamInfo)).apply {
                setUserAgent(streamInfo.userAgent ?: DEFAULT_USER_AGENT)
                setDefaultRequestProperties(streamInfo.headers)
            }
            return if (streamInfo.playbackContentKind !in setOf(PlaybackContentKind.ON_DEMAND, PlaybackContentKind.CATCH_UP) &&
                streamInfo.drmInfo == null && inferStreamType(streamInfo) != StreamType.RTSP
            ) startupTransfers.wrap(factory) else factory
        }

        private fun inferStreamType(streamInfo: StreamInfo): StreamType {
            if (streamInfo.streamType != StreamType.UNKNOWN) return streamInfo.streamType
            val url = streamInfo.url.lowercase()
            return when {
                url.endsWith(".m3u8") -> StreamType.HLS
                url.endsWith(".mpd") -> StreamType.DASH
                url.contains(".isml/manifest") || url.contains(".ism/manifest") || url.endsWith(".ism") || url.endsWith(".isml") ->
                    StreamType.SMOOTH_STREAMING
                url.startsWith("rtsp") -> StreamType.RTSP
                url.endsWith(".ts") -> StreamType.MPEG_TS
                else -> StreamType.PROGRESSIVE
            }
        }

        private fun loadChannelRef(channelUri: Uri): ChannelRef? {
            val resolvedUri = if (ContentUris.parseId(channelUri) > 0) channelUri else return null
            return contentResolver.query(
                resolvedUri,
                arrayOf(CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA),
                null,
                null,
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val rawData = cursor.getString(cursor.getColumnIndexOrThrow(CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA))
                decodeChannelRef(rawData)
            }
        }

        private fun decodeChannelRef(rawData: String?): ChannelRef? {
            val parts = rawData?.split(ENTRY_SEPARATOR).orEmpty()
            if (parts.size < 2) return null
            val providerId = parts[0].toLongOrNull() ?: return null
            val channelId = parts[1].toLongOrNull() ?: return null
            return ChannelRef(providerId = providerId, channelId = channelId)
        }
    }

    private data class ChannelRef(
        val providerId: Long,
        val channelId: Long
    )

    private fun OkHttpClient.forPlayback(streamInfo: StreamInfo): OkHttpClient {
        val proxy = streamInfo.httpProxy()
        if (streamInfo.playbackTransportPolicy == null &&
            !streamInfo.allowInvalidSsl &&
            proxy == null
        ) {
            return this
        }
        val key = PlaybackClientKey(
            transportPolicy = streamInfo.playbackTransportPolicy,
            allowInvalidSsl = streamInfo.allowInvalidSsl,
            proxyHost = streamInfo.proxyHost.trim(),
            proxyPort = streamInfo.proxyPort
        )
        return playbackClients.getOrPut(key) {
            newBuilder()
                .apply {
                    val transportPolicy = streamInfo.playbackTransportPolicy
                    if (transportPolicy != null) {
                        applyPlaybackTransportPolicy(transportPolicy)
                    } else if (streamInfo.allowInvalidSsl) {
                        applyUnsafeTlsBypass()
                    }
                    proxy?.let { proxy(it) }
                }
                .build()
        }
    }

    private fun StreamInfo.httpProxy(): Proxy? {
        val host = proxyHost.trim().takeIf { it.isNotBlank() } ?: return null
        val port = proxyPort ?: return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }

    private data class PlaybackClientKey(
        val transportPolicy: com.streamvault.domain.model.PlaybackTransportPolicy?,
        val allowInvalidSsl: Boolean,
        val proxyHost: String,
        val proxyPort: Int?
    )

    private companion object {
        const val TAG = "StreamVaultTvInput"
        const val DEFAULT_USER_AGENT = "StreamVaultTvInput"
        const val CHANNEL_COLUMN_INTERNAL_PROVIDER_DATA = "internal_provider_data"
        const val ENTRY_SEPARATOR = ":"
    }
}
