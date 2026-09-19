package com.streamvault.feature.live.presentation.model

import com.streamvault.domain.model.Channel

data class GuideCacheKey(
    val providerId: Long,
    val lookupKey: String
)

object GuideCachePolicy {
    const val GUIDE_EMPTY_KEY_TTL_MILLIS = 24L * 60L * 60L * 1000L
    private const val SENTINEL_GLOTV = "glotv"

    fun cacheKey(providerId: Long, channel: Channel): GuideCacheKey? =
        channel.guideLookupKey()?.let { lookupKey ->
            GuideCacheKey(providerId = providerId, lookupKey = lookupKey)
        }

    fun isSentinelChannel(channel: Channel): Boolean =
        channel.epgChannelId
            ?.trim()
            ?.equals(SENTINEL_GLOTV, ignoreCase = true) == true

    fun isEmptyKeyFresh(
        persistedEmptyAt: Map<String, Long>,
        lookupKey: String,
        now: Long
    ): Boolean = persistedEmptyAt[lookupKey]?.let { timestamp ->
        timestamp > 0L && now - timestamp < GUIDE_EMPTY_KEY_TTL_MILLIS
    } == true

    fun requestableChannels(
        providerId: Long,
        channels: List<Channel>,
        sessionEmptyKeys: Set<GuideCacheKey>,
        persistedEmptyAt: Map<String, Long>,
        existingProgramsByChannel: Map<String, List<*>>,
        now: Long
    ): List<Channel> = channels.filter { channel ->
        val cacheKey = cacheKey(providerId, channel)
        cacheKey != null &&
            !isSentinelChannel(channel) &&
            channel.streamId > 0L &&
            cacheKey !in sessionEmptyKeys &&
            !isEmptyKeyFresh(persistedEmptyAt, cacheKey.lookupKey, now) &&
            existingProgramsByChannel[cacheKey.lookupKey].isNullOrEmpty()
    }
}
