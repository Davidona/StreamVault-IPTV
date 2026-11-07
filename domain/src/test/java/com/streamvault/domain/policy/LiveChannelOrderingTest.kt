package com.streamvault.domain.policy

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import org.junit.Test

class LiveChannelOrderingTest {
    @Test fun `editing visible channels retains hidden slots and appends new visible members`() {
        assertThat(mergeLiveOrderVisibleKeys(listOf("a", "hidden", "b"), listOf("b", "new", "a")))
            .containsExactly("b", "hidden", "new", "a").inOrder()
    }

    @Test fun `source and category keys survive local id and display name changes`() {
        val provider = LegacyProvider(id = 1, name = "Old", type = ProviderType.M3U, serverUrl = "https://example.test", createdAt = 123)
        val category = Category(id = 42, roomId = 8, name = "News")
        val scope = liveChannelOrderScope(provider, category)
        assertThat(liveChannelOrderScope(provider.copy(id = 99, name = "New"), category.copy(roomId = 88, name = "Renamed"))).isEqualTo(scope)
        assertThat(liveChannelOrderScope(provider.copy(createdAt = 456), category)).isNotEqualTo(scope)
        assertThat(liveChannelOrderScope(provider, category.copy(id = 43))).isNotEqualTo(scope)
    }

    @Test fun `combined scope follows portable profile identity and normalized category identity`() {
        val profile = CombinedM3uProfile(id = 12L, identityKey = "portable-profile", name = "Family")
        val scope = combinedLiveChannelOrderScope(profile, Category(id = -500L, name = "Sports News"))
        assertThat(combinedLiveChannelOrderScope(
            profile.copy(id = 99L, name = "Renamed family"),
            Category(id = -900L, name = "  SPORTS   NEWS  ")
        )).isEqualTo(scope)
        assertThat(combinedLiveChannelOrderScope(profile.copy(identityKey = "different-profile"),
            Category(id = -500L, name = "Sports News"))).isNotEqualTo(scope)
    }

    @Test fun `combined order keys distinguish equal remote ids from separate providers`() {
        val providerA = LegacyProvider(id = 1, name = "A", type = ProviderType.M3U,
            serverUrl = "https://a.example.test/list.m3u", createdAt = 10)
        val providerB = LegacyProvider(id = 2, name = "B", type = ProviderType.M3U,
            serverUrl = "https://b.example.test/list.m3u", createdAt = 20)
        val first = Channel(id = 100, name = "First", providerId = providerA.id, streamId = 700)
        val second = Channel(id = 200, name = "Second", providerId = providerB.id, streamId = 700)
        val identityByProvider = mapOf(
            providerA.id to liveProviderOrderIdentity(providerA),
            providerB.id to liveProviderOrderIdentity(providerB)
        )
        val keySelector: (Channel) -> List<String> = { channel ->
            channel.liveOrderKeys(checkNotNull(identityByProvider[channel.providerId]))
        }
        val order = LiveChannelOrder(
            mode = LiveChannelOrderMode.MANUAL,
            manualKeys = second.liveOrderKeys(identityByProvider.getValue(providerB.id)) +
                first.liveOrderKeys(identityByProvider.getValue(providerA.id))
        )
        assertThat(orderLiveChannels(listOf(first, second), order, LiveChannelOrderMode.PROVIDER, keySelector = keySelector)
            .map { it.id }).containsExactly(200L, 100L).inOrder()
    }

    @Test fun `quality switches and local id remapping preserve saved placement`() {
        val first = Channel(id = 12, name = "Same name", providerId = 9, streamId = 120)
        val variants = listOf(variant(20, 200), variant(30, 300))
        val grouped = Channel(id = 20, name = "Same name", providerId = 9, streamId = 200, variants = variants)
        val saved = grouped.liveOrderKeys() + first.liveOrderKeys()
        val switched = grouped.copy(id = 999, streamId = 300, variants = variants.reversed())
        assertThat(applyLiveChannelOrder(listOf(first.copy(id = 888), switched), saved).map { it.id })
            .containsExactly(999L, 888L).inOrder()
    }

    @Test fun `ungrouping restores each remote variant adjacent to its former group`() {
        val channels = listOf(Channel(id = 1, name = "New", streamId = 100), Channel(id = 2, name = "SD", streamId = 200), Channel(id = 3, name = "HD", streamId = 300))
        assertThat(applyLiveChannelOrder(channels, listOf("stream:300", "stream:200")).map { it.id })
            .containsExactly(3L, 2L, 1L).inOrder()
    }

    @Test fun `default changes only inherited categories and never erases saved manual keys`() {
        val rows = listOf(Channel(1, "Zulu", streamId = 1), Channel(2, "Alpha", streamId = 2), Channel(3, "alpha", streamId = 3))
        val order = LiveChannelOrder(manualKeys = listOf("stream:3", "stream:1", "stream:2"))
        assertThat(orderLiveChannels(rows, order, LiveChannelOrderMode.TITLE_ASC).map { it.id })
            .containsExactly(2L, 3L, 1L).inOrder()
        assertThat(orderLiveChannels(rows, order.copy(mode = LiveChannelOrderMode.PROVIDER), LiveChannelOrderMode.TITLE_ASC)).isEqualTo(rows)
        assertThat(orderLiveChannels(rows, order.copy(mode = LiveChannelOrderMode.MANUAL), LiveChannelOrderMode.TITLE_ASC).map { it.id })
            .containsExactly(3L, 1L, 2L).inOrder()
        assertThat(orderLiveChannels(rows, order.copy(mode = LiveChannelOrderMode.TITLE_DESC), LiveChannelOrderMode.TITLE_ASC).map { it.id })
            .containsExactly(1L, 2L, 3L).inOrder()
        assertThat(order.manualKeys).containsExactly("stream:3", "stream:1", "stream:2").inOrder()
    }

    @Test fun `dynamic sorts aggregate quality aliases once and keep zero usage tails in provider order`() {
        val rows = listOf(Channel(1, "Unwatched", streamId = 1), Channel(2, "Expired usage", streamId = 2),
            Channel(3, "Group", streamId = 200, variants = listOf(variant(20, 200), variant(30, 300))),
            Channel(4, "Other", streamId = 4))
        val stats = mapOf("stream:2" to LiveWatchUsage(900, 0), "stream:200" to LiveWatchUsage(300, 400),
            "stream:300" to LiveWatchUsage(400, 600), "stream:4" to LiveWatchUsage(500, 1000))
        assertThat(orderLiveChannels(rows, LiveChannelOrder(LiveChannelOrderMode.RECENTLY_WATCHED), LiveChannelOrderMode.PROVIDER, stats).map { it.id })
            .containsExactly(2L, 4L, 3L, 1L).inOrder()
        assertThat(orderLiveChannels(rows, LiveChannelOrder(LiveChannelOrderMode.MOST_WATCHED), LiveChannelOrderMode.PROVIDER, stats).map { it.id })
            .containsExactly(4L, 3L, 1L, 2L).inOrder()
        val watchedAgain = stats + ("stream:200" to LiveWatchUsage(1000, 400))
        assertThat(orderLiveChannels(rows, LiveChannelOrder(LiveChannelOrderMode.MOST_WATCHED), LiveChannelOrderMode.PROVIDER, watchedAgain).map { it.id })
            .containsExactly(3L, 4L, 1L, 2L).inOrder()
    }

    private fun variant(rawId: Long, remoteId: Long) = LiveChannelVariant(
        rawChannelId = rawId, providerId = 9, streamId = remoteId, logicalGroupId = "group",
        originalName = "Channel", canonicalName = "Channel", streamUrl = ""
    )
}
