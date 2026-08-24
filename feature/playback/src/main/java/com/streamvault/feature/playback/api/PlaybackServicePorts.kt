package com.streamvault.feature.playback.api

import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.PlaybackTransportPolicy
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo

data class CastMediaRequest(
    val url: String,
    val title: String,
    val subtitle: String? = null,
    val artworkUrl: String? = null,
    val mimeType: String? = null,
    val isLive: Boolean = false,
    val startPositionMs: Long = 0L,
    val rewriteRequiredReason: CastRewriteRequiredReason? = null,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val playbackTransportPolicy: PlaybackTransportPolicy? = null,
    val allowInvalidSsl: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int? = null
) {
    val requiresCastRewrite: Boolean
        get() = rewriteRequiredReason != null
}

enum class CastRewriteRequiredReason {
    LOCAL_URI,
    CUSTOM_HEADERS,
    CUSTOM_USER_AGENT,
    PROXY,
    INVALID_SSL,
    SCOPED_TRANSPORT
}

interface PlaybackStreamPreparer {
    suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo>
}

interface CastUrlRewriter {
    suspend fun rewrite(request: CastMediaRequest): String?
}

interface PlaybackSurfaceRefreshPort {
    suspend fun updateWatchNextProgress(history: PlaybackHistory)

    suspend fun refreshWatchNext()

    suspend fun refreshRecommendations()
}
