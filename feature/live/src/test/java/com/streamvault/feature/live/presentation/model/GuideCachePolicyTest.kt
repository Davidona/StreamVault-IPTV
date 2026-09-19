package com.streamvault.feature.live.presentation.model

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import org.junit.Test

class GuideCachePolicyTest {
    @Test
    fun emptyKeyFromAnotherProviderDoesNotSuppressRequest() {
        val channel = Channel(
            id = 2L,
            name = "Provider two",
            providerId = 2L,
            streamId = 42L
        )

        val requestable = GuideCachePolicy.requestableChannels(
            providerId = 2L,
            channels = listOf(channel),
            sessionEmptyKeys = setOf(GuideCacheKey(providerId = 1L, lookupKey = "42")),
            persistedEmptyAt = emptyMap(),
            existingProgramsByChannel = emptyMap(),
            now = 1_000L
        )

        assertThat(requestable).containsExactly(channel)
    }

    @Test
    fun sentinelUsesRawEpgIdEvenWhenStreamIdIsTheLookupKey() {
        val channel = Channel(
            id = 1L,
            name = "Sentinel",
            epgChannelId = "  GLOTV  ",
            providerId = 1L,
            streamId = 42L
        )

        assertThat(GuideCachePolicy.isSentinelChannel(channel)).isTrue()
        assertThat(GuideCachePolicy.cacheKey(1L, channel))
            .isEqualTo(GuideCacheKey(providerId = 1L, lookupKey = "42"))
    }

    @Test
    fun expiredPersistedEmptyKeyDoesNotSuppressRequest() {
        val channel = Channel(
            id = 1L,
            name = "Expired",
            providerId = 1L,
            streamId = 42L
        )

        val requestable = GuideCachePolicy.requestableChannels(
            providerId = 1L,
            channels = listOf(channel),
            sessionEmptyKeys = emptySet(),
            persistedEmptyAt = mapOf("42" to 1_000L),
            existingProgramsByChannel = emptyMap(),
            now = 1_000L + GuideCachePolicy.GUIDE_EMPTY_KEY_TTL_MILLIS
        )

        assertThat(requestable).containsExactly(channel)
    }

    @Test
    fun sentinelChannelIsNeverRequestable() {
        val channel = Channel(
            id = 1L,
            name = "Sentinel",
            epgChannelId = "glotv",
            providerId = 1L,
            streamId = 42L
        )

        val requestable = GuideCachePolicy.requestableChannels(
            providerId = 1L,
            channels = listOf(channel),
            sessionEmptyKeys = emptySet(),
            persistedEmptyAt = emptyMap(),
            existingProgramsByChannel = emptyMap(),
            now = 1_000L
        )

        assertThat(requestable).isEmpty()
    }
}
