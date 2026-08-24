package com.streamvault.feature.playback.cast

import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.feature.playback.api.CastMediaRequest
import com.streamvault.feature.playback.api.CastRewriteRequiredReason
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CastMediaRequestFactory @Inject constructor() {

    fun buildFromStreamInfo(
        streamInfo: StreamInfo,
        title: String,
        subtitle: String?,
        artworkUrl: String?,
        isLive: Boolean,
        startPositionMs: Long
    ): CastMediaRequestBuildResult {
        val resolvedUrl = streamInfo.url.takeIf { it.isNotBlank() }
            ?: return CastMediaRequestBuildResult.Unsupported(CastMediaRequestUnsupportedReason.EMPTY_URL)
        if (streamInfo.drmInfo != null) {
            return CastMediaRequestBuildResult.Unsupported(CastMediaRequestUnsupportedReason.DRM_PROTECTED)
        }
        val mimeType = when (streamInfo.inferCastStreamType()) {
            StreamType.HLS -> MIME_HLS
            StreamType.DASH -> MIME_DASH
            StreamType.SMOOTH_STREAMING -> MIME_SMOOTH_STREAMING
            StreamType.MPEG_TS -> MIME_MPEG_TS
            StreamType.RTSP -> {
                return CastMediaRequestBuildResult.Unsupported(
                    CastMediaRequestUnsupportedReason.UNSUPPORTED_PROTOCOL
                )
            }
            StreamType.PROGRESSIVE,
            StreamType.UNKNOWN -> MIME_VIDEO
        }
        return CastMediaRequestBuildResult.Success(
            CastMediaRequest(
                url = resolvedUrl,
                title = title.ifBlank { streamInfo.title ?: DEFAULT_TITLE },
                subtitle = subtitle,
                artworkUrl = artworkUrl,
                mimeType = mimeType,
                isLive = isLive,
                startPositionMs = if (isLive) 0L else startPositionMs.coerceAtLeast(0L),
                rewriteRequiredReason = streamInfo.castRewriteRequiredReason(resolvedUrl),
                headers = streamInfo.headers,
                userAgent = streamInfo.userAgent,
                playbackTransportPolicy = streamInfo.playbackTransportPolicy,
                allowInvalidSsl = streamInfo.allowInvalidSsl,
                proxyHost = streamInfo.proxyHost,
                proxyPort = streamInfo.proxyPort
            )
        )
    }

    fun fromStreamInfo(
        streamInfo: StreamInfo,
        title: String,
        subtitle: String?,
        artworkUrl: String?,
        isLive: Boolean,
        startPositionMs: Long
    ): CastMediaRequest? {
        return (buildFromStreamInfo(
            streamInfo = streamInfo,
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            isLive = isLive,
            startPositionMs = startPositionMs
        ) as? CastMediaRequestBuildResult.Success)?.request
    }

    companion object {
        const val MIME_HLS = "application/x-mpegURL"
        const val MIME_DASH = "application/dash+xml"
        const val MIME_SMOOTH_STREAMING = "application/vnd.ms-sstr+xml"
        const val MIME_MPEG_TS = "video/mp2t"
        const val MIME_VIDEO = "video/*"
        private const val DEFAULT_TITLE = "StreamVault"
    }
}

sealed interface CastMediaRequestBuildResult {
    data class Success(val request: CastMediaRequest) : CastMediaRequestBuildResult
    data class Unsupported(
        val reason: CastMediaRequestUnsupportedReason
    ) : CastMediaRequestBuildResult
}

enum class CastMediaRequestUnsupportedReason {
    STREAM_UNAVAILABLE,
    EMPTY_URL,
    UNSUPPORTED_PROTOCOL,
    DRM_PROTECTED
}

internal fun StreamInfo.inferCastStreamType(): StreamType {
    if (streamType != StreamType.UNKNOWN) {
        return streamType
    }
    val normalizedUrl = url.substringBefore('?').substringBefore('#').lowercase()
    val extensionType = StreamType.fromContainerExtension(containerExtension)
    if (extensionType != StreamType.UNKNOWN) {
        return extensionType
    }
    return when {
        normalizedUrl.endsWith(".m3u8") -> StreamType.HLS
        normalizedUrl.endsWith(".mpd") -> StreamType.DASH
        normalizedUrl.contains(".isml/manifest") ||
            normalizedUrl.contains(".ism/manifest") ||
            normalizedUrl.endsWith(".ism") ||
            normalizedUrl.endsWith(".isml") -> StreamType.SMOOTH_STREAMING
        normalizedUrl.endsWith(".ts") -> StreamType.MPEG_TS
        normalizedUrl.startsWith("rtsp://") ||
            normalizedUrl.startsWith("rtsps://") ||
            normalizedUrl.startsWith("rtmp://") ||
            normalizedUrl.startsWith("rtmps://") -> StreamType.RTSP
        else -> StreamType.PROGRESSIVE
    }
}

private fun StreamInfo.castRewriteRequiredReason(url: String): CastRewriteRequiredReason? {
    if (url.requiresDeviceLocalAccess()) {
        return CastRewriteRequiredReason.LOCAL_URI
    }
    if (headers.any { (name, value) -> name.isNotBlank() && value.isNotBlank() }) {
        return CastRewriteRequiredReason.CUSTOM_HEADERS
    }
    if (!userAgent.isNullOrBlank()) {
        return CastRewriteRequiredReason.CUSTOM_USER_AGENT
    }
    if (proxyHost.isNotBlank() || proxyPort != null) {
        return CastRewriteRequiredReason.PROXY
    }
    if (playbackTransportPolicy != null) {
        return CastRewriteRequiredReason.SCOPED_TRANSPORT
    }
    if (allowInvalidSsl) {
        return CastRewriteRequiredReason.INVALID_SSL
    }
    return null
}

private fun String.requiresDeviceLocalAccess(): Boolean {
    val normalizedUrl = trim().lowercase()
    if (normalizedUrl.startsWith("content://") || normalizedUrl.startsWith("file://")) {
        return true
    }
    val host = runCatching { URI(trim()).host?.lowercase() }.getOrNull() ?: return false
    return host == "localhost" ||
        host == "::1" ||
        host == "0:0:0:0:0:0:0:1" ||
        host == "10.0.2.2" ||
        host.startsWith("127.")
}
