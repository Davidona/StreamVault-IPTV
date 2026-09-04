package com.streamvault.player

import android.content.Context
import android.view.View
import com.streamvault.domain.model.AudioOutputPreference
import com.streamvault.domain.model.DecoderMode
import com.streamvault.domain.model.PlaybackBufferMode
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.PlayerSurfaceMode
import com.streamvault.domain.model.DrmScheme
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.VideoFormat
import androidx.media3.common.PlaybackException
import androidx.media3.common.text.Cue
import androidx.media3.datasource.HttpDataSource
import com.streamvault.player.playback.PlaybackErrorCategory
import com.streamvault.player.playback.PlayerErrorClassifier
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import com.streamvault.player.timeshift.LiveTimeshiftState
import com.streamvault.player.timeshift.TimeshiftConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

const val AUDIO_VIDEO_OFFSET_MIN_MS = -2_000
const val AUDIO_VIDEO_OFFSET_MAX_MS = 2_000
const val AUDIO_VIDEO_OFFSET_STEP_MS = 50

const val PLAYER_TRACK_AUTO_ID = "__auto__"

enum class PlayerRenderSurfaceType {
    AUTO,
    SURFACE_VIEW,
    TEXTURE_VIEW
}

/**
 * Abstraction over the underlying media player.
 * UI layer talks to this interface, never to ExoPlayer directly.
 */
interface PlayerEngine {
    val playbackState: StateFlow<PlaybackState>
    val isPlaying: StateFlow<Boolean>
    val currentPosition: StateFlow<Long>
    val duration: StateFlow<Long>
    val videoFormat: StateFlow<VideoFormat>
    val error: Flow<PlayerError?>
    val retryStatus: StateFlow<PlayerRetryStatus?>
    val playerStats: StateFlow<PlayerStats>

    // Tracks
    val availableAudioTracks: StateFlow<List<PlayerTrack>>
    val availableSubtitleTracks: StateFlow<List<PlayerTrack>>
    val availableVideoTracks: StateFlow<List<PlayerTrack>>
    val playbackSpeed: StateFlow<Float>
    val audioVideoOffsetMs: StateFlow<Int>
    val audioVideoSyncEnabled: StateFlow<Boolean>
    val timeshiftState: StateFlow<LiveTimeshiftState>
    val renderSurfaceType: StateFlow<PlayerRenderSurfaceType>

    /** In-stream metadata title (ICY / HLS). Null when the stream sends nothing. */
    val mediaTitle: StateFlow<String?>

    fun prepare(streamInfo: StreamInfo)
    fun renewStreamUrl(streamInfo: StreamInfo)
    fun play()
    fun pause()

    /** Stop playback and release the active media source/network connection. */
    fun stop()
    fun seekTo(positionMs: Long)
    fun seekForward(ms: Long = 10_000)
    fun seekBackward(ms: Long = 10_000)
    fun setDecoderModes(audioMode: DecoderMode, videoMode: DecoderMode)
    fun setPlaybackBufferMode(mode: PlaybackBufferMode)
    fun setSurfaceMode(mode: PlayerSurfaceMode)
    fun setVodHttpProtocolMode(mode: VodHttpProtocolMode)
    fun setMediaSessionEnabled(enabled: Boolean)
    fun setFastRetryOnTransientFailures(enabled: Boolean)
    fun setVolume(volume: Float)
    fun setMuted(muted: Boolean)
    fun setPlaybackSpeed(speed: Float)
    fun setAudioVideoSyncEnabled(enabled: Boolean)
    fun setAudioVideoOffsetMs(offsetMs: Int)
    fun setAudioOutputPreference(preference: AudioOutputPreference)
    fun setCompatibilityMemoryEnabled(enabled: Boolean)
    fun clearLearnedPlaybackCompatibility()
    fun startLiveTimeshift(streamInfo: StreamInfo, channelKey: String, config: TimeshiftConfig)
    fun stopLiveTimeshift()
    fun seekToLiveEdge()
    fun pauseTimeshift()
    fun resumeTimeshift()
    fun setPreferredAudioLanguage(languageTag: String?)
    fun setSubtitleStyle(style: PlayerSubtitleStyle)
    fun setNetworkQualityPreferences(wifiMaxHeight: Int?, ethernetMaxHeight: Int?)
    fun selectAudioTrack(trackId: String)
    fun selectVideoTrack(trackId: String)
    fun selectSubtitleTrack(trackId: String?) // null to disable subtitles
    fun addExternalSubtitle(subtitleUri: android.net.Uri, language: String)
    fun setInjectedSubtitleCues(cues: List<Cue>)
    fun clearInjectedSubtitleCues()
    fun setLiveAudioTap(tap: LiveAudioTap?)
    fun clearLiveAudioTap() = setLiveAudioTap(null)
    fun release()
    fun resetForReuse() = release()

    /** Toggle mute without losing the remembered volume level. */
    fun toggleMute()
    val isMuted: StateFlow<Boolean>

    /** Emits when audio focus is denied (e.g., device muted at OS level). */
    val audioFocusDenied: Flow<Unit>

    /**
     * Enable scrubbing mode for rapid switching (channel zapping).
     * While enabled, the player skips re-buffering and favours lowest-latency
     * key-frame-only decoding so each channel appears on screen faster.
     */
    fun setScrubbingMode(enabled: Boolean)

    /**
     * Pre-warm the player with a stream that will likely be played next.
     * Only the initial manifest/key-frames are fetched — no full buffering.
     * Call with `null` to discard any preloaded data.
     */
    fun preload(streamInfo: StreamInfo?)

    fun createRenderView(
        context: Context,
        resizeMode: PlayerSurfaceResizeMode,
        surfaceType: PlayerRenderSurfaceType = PlayerRenderSurfaceType.AUTO
    ): View
    fun bindRenderView(renderView: View, resizeMode: PlayerSurfaceResizeMode)
    fun clearRenderBinding()
    fun releaseRenderView(renderView: View)
    fun resetLiveHandoffGrace() {}
}

data class PlayerRetryStatus(
    val attempt: Int,
    val maxAttempts: Int,
    val delayMs: Long
)

enum class PlayerSurfaceResizeMode {
    FIT,
    FILL,
    ZOOM
}

enum class PlaybackState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR
}

data class PlayerStats(
    val videoCodec: String = "Unknown",
    val audioCodec: String = "Unknown",
    val videoDecoderName: String = "Unknown",
    val audioDecoderName: String = "Unknown",
    val ffmpegAvailable: Boolean = false,
    val ffmpegVersion: String? = null,
    val audioOutputPath: String = "UNKNOWN",
    val compatibilityDecisionSource: String = "DEFAULT",
    val activeDecoderPolicy: String = "AUTO",
    val renderSurfaceType: String = "SURFACE_VIEW",
    val audioVideoSyncEnabled: Boolean = false,
    val audioVideoSyncSinkActive: Boolean = false,
    val videoStallCount: Int = 0,
    val lastVideoFrameAgoMs: Long = 0,
    val videoBitrate: Int = 0,
    /** Frame rate declared by the stream/container; 0 when not declared (common for MPEG-TS). */
    val frameRate: Float = 0f,
    /** Nominal frame rate detected once from frame timestamps and then locked; 0 until detected. */
    val measuredFrameRate: Float = 0f,
    /** Running average of the whole-stream network bitrate in bps, rounded to 0.1 Mbps; 0 until measured. */
    val measuredBitrate: Long = 0L,
    val droppedFrames: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val bandwidthEstimate: Long = 0,
    val bufferedDurationMs: Long = 0,
    val rebufferCount: Int = 0,
    val ttffMs: Long = 0
) {
    @Suppress("LongParameterList")
    constructor(
        videoCodec: String,
        audioCodec: String,
        videoDecoderName: String,
        audioDecoderName: String,
        activeDecoderPolicy: String,
        renderSurfaceType: String,
        audioVideoSyncEnabled: Boolean,
        audioVideoSyncSinkActive: Boolean,
        videoStallCount: Int,
        lastVideoFrameAgoMs: Long,
        videoBitrate: Int,
        droppedFrames: Int,
        width: Int,
        height: Int,
        bandwidthEstimate: Long,
        bufferedDurationMs: Long,
        rebufferCount: Int,
        ttffMs: Long
    ) : this(
        videoCodec = videoCodec,
        audioCodec = audioCodec,
        videoDecoderName = videoDecoderName,
        audioDecoderName = audioDecoderName,
        ffmpegAvailable = false,
        ffmpegVersion = null,
        audioOutputPath = "UNKNOWN",
        compatibilityDecisionSource = "DEFAULT",
        activeDecoderPolicy = activeDecoderPolicy,
        renderSurfaceType = renderSurfaceType,
        audioVideoSyncEnabled = audioVideoSyncEnabled,
        audioVideoSyncSinkActive = audioVideoSyncSinkActive,
        videoStallCount = videoStallCount,
        lastVideoFrameAgoMs = lastVideoFrameAgoMs,
        videoBitrate = videoBitrate,
        droppedFrames = droppedFrames,
        width = width,
        height = height,
        bandwidthEstimate = bandwidthEstimate,
        bufferedDurationMs = bufferedDurationMs,
        rebufferCount = rebufferCount,
        ttffMs = ttffMs
    )
}

sealed class PlayerError(val message: String) {
    class NetworkError(message: String) : PlayerError(message)
    class SourceError(message: String) : PlayerError(message)
    class DecoderError(message: String) : PlayerError(message)
    class DrmError(message: String, val scheme: DrmScheme? = null) : PlayerError(message)
    class UnknownError(message: String) : PlayerError(message)

    companion object {
        fun fromException(e: Throwable): PlayerError {
            val category = PlayerErrorClassifier.classify(e)
            val chain = generateSequence(e) { it.cause }.toList()
            val msg = when (category) {
                PlaybackErrorCategory.DECODER,
                PlaybackErrorCategory.FORMAT_UNSUPPORTED -> buildCodecErrorMessage(e)
                PlaybackErrorCategory.NETWORK -> buildNetworkErrorMessage(chain)
                PlaybackErrorCategory.HTTP_AUTH -> buildHttpErrorMessage(chain, "Access denied")
                PlaybackErrorCategory.HTTP_SERVER -> buildHttpErrorMessage(chain, "Server error")
                PlaybackErrorCategory.PROVIDER_LIMIT ->
                    "Provider rejected playback, likely max connections or bandwidth limit (HTTP 509)."
                PlaybackErrorCategory.EMPTY_RESPONSE ->
                    "Provider returned an empty stream response (HTTP 204)."
                PlaybackErrorCategory.SSL -> "Secure connection failed (SSL/TLS error)."
                PlaybackErrorCategory.CLEAR_TEXT_BLOCKED ->
                    "This stream requires a secure (HTTPS) connection."
                PlaybackErrorCategory.SOURCE_MALFORMED ->
                    "Unable to parse this stream format."
                PlaybackErrorCategory.LIVE_WINDOW ->
                    "Live stream position expired."
                PlaybackErrorCategory.DRM -> buildDrmErrorMessage(chain)
                PlaybackErrorCategory.UNKNOWN ->
                    chain.firstNotNullOfOrNull { it.takeIf { it !is PlaybackException }?.message }
                        ?: "Unknown playback error"
            }
            return when (category) {
                PlaybackErrorCategory.NETWORK,
                PlaybackErrorCategory.HTTP_SERVER,
                PlaybackErrorCategory.HTTP_AUTH,
                PlaybackErrorCategory.CLEAR_TEXT_BLOCKED,
                PlaybackErrorCategory.SSL -> NetworkError(msg)

                PlaybackErrorCategory.PROVIDER_LIMIT,
                PlaybackErrorCategory.EMPTY_RESPONSE,
                PlaybackErrorCategory.SOURCE_MALFORMED,
                PlaybackErrorCategory.LIVE_WINDOW -> SourceError(msg)

                PlaybackErrorCategory.DECODER,
                PlaybackErrorCategory.FORMAT_UNSUPPORTED -> DecoderError(msg)

                PlaybackErrorCategory.DRM -> DrmError(msg)
                PlaybackErrorCategory.UNKNOWN -> UnknownError(msg)
            }
        }

        private fun buildCodecErrorMessage(e: Throwable): String {
            val chain = generateSequence(e) { it.cause }.toList()
            val decoderEx = chain
                .filterIsInstance<androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException>()
                .firstOrNull()
            if (decoderEx != null) {
                val mime = decoderEx.mimeType
                val codecLabel = when (mime) {
                    "video/hevc" -> "H.265/HEVC"
                    "video/av01" -> "AV1"
                    "video/avc" -> "H.264/AVC"
                    "video/x-vnd.on2.vp9", "video/vp9" -> "VP9"
                    "audio/ac3" -> "AC-3 (Dolby Digital)"
                    "audio/eac3" -> "E-AC-3 (Dolby Digital Plus)"
                    "audio/mp4a-latm", "audio/mp4a" -> "AAC"
                    else -> mime
                }
                val codecInfo = decoderEx.codecInfo
                return if (codecInfo != null) {
                    "Codec $codecLabel is not supported by decoder ${codecInfo.name} on this device."
                } else {
                    "No decoder available for codec $codecLabel on this device."
                }
            }
            return e.message ?: "Unsupported media format."
        }

        private fun buildNetworkErrorMessage(chain: List<Throwable>): String = when {
            chain.any { it is SocketTimeoutException } -> "Connection timed out."
            chain.any { it is UnknownHostException } -> "Server not found — check your network connection."
            chain.any { it is ConnectException } -> "Could not connect to server."
            else -> "Network error — check your internet connection."
        }

        private fun buildHttpErrorMessage(chain: List<Throwable>, label: String): String {
            val httpCode = chain
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()?.responseCode
            return if (httpCode != null) "$label (HTTP $httpCode)." else "$label."
        }

        private fun buildDrmErrorMessage(chain: List<Throwable>): String {
            val playbackEx = chain.filterIsInstance<PlaybackException>().firstOrNull()
            return when (playbackEx?.errorCode) {
                PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
                    "DRM license could not be obtained."
                PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED ->
                    "DRM license has expired."
                PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED ->
                    "DRM scheme is not supported on this device."
                PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED ->
                    "This device has been revoked for DRM playback."
                PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED ->
                    "DRM provisioning failed."
                else -> "DRM error — this content may require a license."
            }
        }
    }
}
