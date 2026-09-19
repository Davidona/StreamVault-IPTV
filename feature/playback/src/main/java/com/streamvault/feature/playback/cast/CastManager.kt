package com.streamvault.feature.playback.cast

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import com.streamvault.feature.playback.api.CastMediaRequest
import com.streamvault.feature.playback.api.CastUrlRewriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CastManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val castUrlRewriter: CastUrlRewriter
) {

    private val _connectionState = MutableStateFlow(CastConnectionState.UNAVAILABLE)
    val connectionState: StateFlow<CastConnectionState> = _connectionState.asStateFlow()

    private val _playbackEvents = MutableSharedFlow<CastPlaybackEvent>(extraBufferCapacity = 8)
    val playbackEvents: SharedFlow<CastPlaybackEvent> = _playbackEvents.asSharedFlow()

    private var castContext: CastContext? = null
    private var initialized = false
    private var pendingRequest: CastMediaRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sessionManagerListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) {
            _connectionState.value = CastConnectionState.CONNECTING
        }

        override fun onSessionStarted(session: CastSession, sessionId: String) {
            _connectionState.value = CastConnectionState.CONNECTED
            loadPendingRequest(session)
        }

        override fun onSessionStartFailed(session: CastSession, error: Int) {
            _connectionState.value = CastConnectionState.DISCONNECTED
            val request = pendingRequest
            pendingRequest = null
            if (request != null) {
                _playbackEvents.tryEmit(CastPlaybackEvent.SessionStartFailed(error))
            }
            Log.w(TAG, "Cast session failed to start: $error")
        }

        override fun onSessionEnding(session: CastSession) {
            _connectionState.value = CastConnectionState.DISCONNECTED
        }

        override fun onSessionEnded(session: CastSession, error: Int) {
            _connectionState.value = CastConnectionState.DISCONNECTED
            pendingRequest = null
        }

        override fun onSessionResuming(session: CastSession, sessionId: String) {
            _connectionState.value = CastConnectionState.CONNECTING
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            _connectionState.value = CastConnectionState.CONNECTED
            loadPendingRequest(session)
        }

        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            _connectionState.value = CastConnectionState.DISCONNECTED
            val request = pendingRequest
            pendingRequest = null
            if (request != null) {
                _playbackEvents.tryEmit(CastPlaybackEvent.SessionStartFailed(error))
            }
        }

        override fun onSessionSuspended(session: CastSession, reason: Int) {
            _connectionState.value = CastConnectionState.CONNECTING
        }
    }

    fun ensureInitialized() {
        if (initialized) return
        initialized = true
        runCatching {
            CastContext.getSharedInstance(context)
        }.onSuccess { resolvedContext ->
            castContext = resolvedContext
            resolvedContext.sessionManager.addSessionManagerListener(sessionManagerListener, CastSession::class.java)
            _connectionState.value = currentConnectionState(resolvedContext)
        }.onFailure { throwable ->
            _connectionState.value = CastConnectionState.UNAVAILABLE
            Log.w(TAG, "Google Cast is unavailable on this device", throwable)
        }
    }

    fun buildRouteSelector(): MediaRouteSelector = MediaRouteSelector.Builder()
        .addControlCategory(
            CastMediaControlIntent.categoryForCast(
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            )
        )
        .build()

    suspend fun startCasting(request: CastMediaRequest): CastStartResult {
        ensureInitialized()
        val resolvedContext = castContext ?: return CastStartResult.UNAVAILABLE
        if (!isRequestSupported(request)) {
            return CastStartResult.UNSUPPORTED
        }
        val rewrittenUrl = castUrlRewriter.rewrite(request) ?: return CastStartResult.UNSUPPORTED
        if (request.requiresCastRewrite && rewrittenUrl.trim() == request.url.trim()) {
            Log.w(TAG, "Cast request requires URL rewrite but no receiver-safe URL was returned")
            return CastStartResult.UNSUPPORTED
        }
        val resolvedRequest = request.copy(url = rewrittenUrl)

        pendingRequest = resolvedRequest
        val activeSession = resolvedContext.sessionManager.currentCastSession
        return if (activeSession?.isConnected == true) {
            if (!loadMedia(activeSession, resolvedRequest)) {
                pendingRequest = null
                return CastStartResult.UNAVAILABLE
            }
            pendingRequest = null
            CastStartResult.STARTED
        } else {
            CastStartResult.ROUTE_SELECTION_REQUIRED
        }
    }

    fun stopCasting() {
        ensureInitialized()
        pendingRequest = null
        castContext?.sessionManager?.endCurrentSession(true)
        _connectionState.value = CastConnectionState.DISCONNECTED
    }

    fun onRouteChooserClosed() {
        val request = pendingRequest ?: return
        mainHandler.postDelayed({
            if (pendingRequest == request && _connectionState.value == CastConnectionState.DISCONNECTED) {
                pendingRequest = null
                _playbackEvents.tryEmit(CastPlaybackEvent.RouteSelectionCancelled)
            }
        }, ROUTE_SELECTION_CANCEL_CHECK_DELAY_MS)
    }

    private fun loadPendingRequest(session: CastSession) {
        val request = pendingRequest ?: return
        loadMedia(session, request)
        pendingRequest = null
    }

    private fun loadMedia(session: CastSession, request: CastMediaRequest): Boolean {
        val remoteMediaClient = session.remoteMediaClient ?: run {
            _playbackEvents.tryEmit(CastPlaybackEvent.ReceiverUnavailable(request.title))
            return false
        }
        val metadataType = if (request.isLive) MediaMetadata.MEDIA_TYPE_TV_SHOW else MediaMetadata.MEDIA_TYPE_MOVIE
        val metadata = MediaMetadata(metadataType).apply {
            putString(MediaMetadata.KEY_TITLE, request.title)
            request.subtitle?.takeIf { it.isNotBlank() }?.let {
                putString(MediaMetadata.KEY_SUBTITLE, it)
            }
            request.artworkUrl?.takeIf { it.isNotBlank() }?.let {
                addImage(com.google.android.gms.common.images.WebImage(Uri.parse(it)))
            }
        }

        val mediaInfo = MediaInfo.Builder(request.url)
            .setContentType(request.mimeType ?: DEFAULT_CONTENT_TYPE)
            .setStreamType(if (request.isLive) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED)
            .setMetadata(metadata)
            .build()

        remoteMediaClient.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(mediaInfo)
                .setAutoplay(true)
                .setCurrentTime(request.startPositionMs)
                .build()
        ).setResultCallback { result ->
            if (result.status.isSuccess) {
                _playbackEvents.tryEmit(CastPlaybackEvent.MediaLoadSucceeded(request.title))
            } else {
                Log.w(TAG, "Cast media load failed: ${result.status.statusCode}")
                _playbackEvents.tryEmit(
                    CastPlaybackEvent.MediaLoadFailed(
                        title = request.title,
                        statusCode = result.status.statusCode
                    )
                )
            }
        }
        return true
    }

    private fun currentConnectionState(castContext: CastContext): CastConnectionState {
        val activeSession = castContext.sessionManager.currentCastSession
        return when {
            activeSession?.isConnected == true -> CastConnectionState.CONNECTED
            activeSession != null -> CastConnectionState.CONNECTING
            else -> CastConnectionState.DISCONNECTED
        }
    }

    private fun isRequestSupported(request: CastMediaRequest): Boolean {
        val normalizedUrl = request.url.trim().lowercase()
        val mimeType = request.mimeType.orEmpty().lowercase()
        if (
            normalizedUrl.startsWith("rtsp://") ||
            normalizedUrl.startsWith("rtsps://") ||
            normalizedUrl.startsWith("rtmp://") ||
            normalizedUrl.startsWith("rtmps://")
        ) {
            return false
        }
        return mimeType.isBlank() || mimeType != "application/x-rtsp"
    }

    private companion object {
        const val TAG = "CastManager"
        const val DEFAULT_CONTENT_TYPE = "application/x-mpegURL"
        const val ROUTE_SELECTION_CANCEL_CHECK_DELAY_MS = 1_000L
    }
}
