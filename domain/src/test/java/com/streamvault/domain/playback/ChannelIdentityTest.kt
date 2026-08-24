package com.streamvault.domain.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LiveChannelVariant
import org.junit.Test

class ChannelIdentityTest {

    @Test
    fun `orderedByRequestedRawIds preserves raw variants from same logical group`() {
        val sd = rawChannel(id = 101L, name = "Channel X SD", logicalGroupId = "provider_channel_x")
        val hd = rawChannel(id = 102L, name = "Channel X HD", logicalGroupId = "provider_channel_x")

        val ordered = listOf(sd, hd).orderedByRequestedRawIds(listOf(101L, 102L))

        assertThat(ordered.map(Channel::id)).containsExactly(101L, 102L).inOrder()
    }

    @Test
    fun `orderedByRequestedRawIds keeps grouped channels collapsed`() {
        val grouped = Channel(
            id = 101L,
            name = "Channel X",
            providerId = 7L,
            logicalGroupId = "provider_channel_x",
            selectedVariantId = 101L,
            variants = listOf(
                LiveChannelVariant(
                    rawChannelId = 101L,
                    logicalGroupId = "provider_channel_x",
                    providerId = 7L,
                    originalName = "Channel X SD",
                    canonicalName = "Channel X",
                    streamUrl = "https://example.com/sd"
                ),
                LiveChannelVariant(
                    rawChannelId = 102L,
                    logicalGroupId = "provider_channel_x",
                    providerId = 7L,
                    originalName = "Channel X HD",
                    canonicalName = "Channel X",
                    streamUrl = "https://example.com/hd"
                )
            )
        )

        val ordered = listOf(grouped).orderedByRequestedRawIds(listOf(101L, 102L))

        assertThat(ordered.map(Channel::id)).containsExactly(101L)
    }

    private fun rawChannel(id: Long, name: String, logicalGroupId: String): Channel = Channel(
        id = id,
        name = name,
        providerId = 7L,
        logicalGroupId = logicalGroupId,
        selectedVariantId = id,
        variants = listOf(
            LiveChannelVariant(
                rawChannelId = id,
                logicalGroupId = logicalGroupId,
                providerId = 7L,
                originalName = name,
                canonicalName = "Channel X",
                streamUrl = "https://example.com/$id"
            )
        )
    )
}
