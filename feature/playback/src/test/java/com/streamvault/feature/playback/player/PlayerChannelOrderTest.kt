package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import com.streamvault.domain.policy.*
import com.streamvault.domain.repository.*
import com.streamvault.domain.usecase.ObserveLiveChannelOrdering
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.*

class PlayerChannelOrderTest {
    @Test fun `favorites playlist follows saved scoped order`() = runTest {
        val channels: ChannelRepository = mock()
        val providers: ProviderRepository = mock()
        val favorites: FavoriteRepository = mock()
        val combined: CombinedM3uRepository = mock()
        val ordering: ObserveLiveChannelOrdering = mock()
        val provider = LegacyProvider(id = 7, name = "Provider", type = ProviderType.M3U, serverUrl = "https://example.test", createdAt = 1)
        whenever(providers.getProvider(7)).thenReturn(provider)
        val scope = liveFavoritesOrderScope(liveProviderOrderIdentity(provider))
        whenever(ordering.observeSettings(eq(scope), eq(listOf(7L)), eq(emptyMap()))).thenReturn(flowOf(
            LiveChannelOrderSnapshot(LiveChannelOrder(LiveChannelOrderMode.MANUAL, listOf("stream:2", "stream:1")), LiveChannelOrderMode.PROVIDER)
        ))
        val rows = listOf(Channel(id = 1, providerId = 7, streamId = 1, name = "One"), Channel(id = 2, providerId = 7, streamId = 2, name = "Two"))
        val coordinator = PlayerChannelCoordinator(channels, ordering, providers, favorites, combined)
        assertThat(coordinator.orderVirtual(7, null, VirtualCategoryIds.FAVORITES, flowOf(rows)).first().map { it.id })
            .containsExactly(2L, 1L).inOrder()
    }
}
