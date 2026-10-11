package com.streamvault.domain.model.stremio

enum class StremioStreamKind {
    DIRECT,
    TORRENT,
    YOUTUBE,
    EXTERNAL,
    UNKNOWN
}

data class StremioProxyHeaders(
    val request: Map<String, String> = emptyMap(),
    val response: Map<String, String> = emptyMap()
)

data class StremioStreamBehaviorHints(
    val notWebReady: Boolean = false,
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
    val proxyHeaders: StremioProxyHeaders? = null
)

data class StremioStream(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val sources: List<String> = emptyList(),
    val behaviorHints: StremioStreamBehaviorHints? = null
) {
    val kind: StremioStreamKind
        get() = when {
            !infoHash.isNullOrBlank() -> StremioStreamKind.TORRENT
            !url.isNullOrBlank() -> StremioStreamKind.DIRECT
            !ytId.isNullOrBlank() -> StremioStreamKind.YOUTUBE
            !externalUrl.isNullOrBlank() -> StremioStreamKind.EXTERNAL
            else -> StremioStreamKind.UNKNOWN
        }
}
