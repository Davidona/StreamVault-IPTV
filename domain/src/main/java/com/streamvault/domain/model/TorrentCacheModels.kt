package com.streamvault.domain.model

data class TorrentCacheStatus(
    val cachedBytes: Long,
    val cacheLimitBytes: Long,
    val availableStorageBytes: Long?,
    val activeSessionCount: Int
)

enum class TorrentCacheClearResult {
    CLEARED,
    PLAYBACK_ACTIVE,
    FAILED
}
