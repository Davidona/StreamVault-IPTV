package com.streamvault.domain.model

/** Stable provider-scoped identity for a live channel while it is playing or recording. */
data class LivePlaybackIdentity(
    val providerId: Long,
    val channelId: Long
) {
    init {
        require(providerId > 0L) { "Live playback providerId must be positive" }
        require(channelId > 0L) { "Live playback channelId must be positive" }
    }
}
