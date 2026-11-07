package com.streamvault.feature.live.phone

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import com.streamvault.domain.policy.*
import com.streamvault.domain.repository.*
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.usecase.GetCustomCategories
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.*

class OrderingReviewProbeTest {
    private val providers: ProviderRepository = mock()
    private val categories: CategoryRepository = mock()
    private val channels: ChannelRepository = mock()
    private val orders: LiveChannelOrderRepository = mock()
    private val preferences: SettingsPreferences = mock()
    private val custom: GetCustomCategories = mock()
    private val usage: LiveWatchUsageRepository = mock()
    private val favorites: FavoriteRepository = mock()
    private val combined: CombinedM3uRepository = mock()
    private val backend = PhoneOrderingBackend(providers, categories, channels, orders, preferences, custom, usage, favorites, combined)

    @Test fun `opening filtered favorites must preserve protected saved slots`() = runTest {
        val provider = LegacyProvider(id = 7, name = "Provider", type = ProviderType.M3U, serverUrl = "https://example.test", createdAt = 123)
        whenever(providers.getProvider(7)).thenReturn(provider)
        whenever(custom(7, ContentType.LIVE)).thenReturn(flowOf(listOf(Category(VirtualCategoryIds.FAVORITES, name = "Favorites", isVirtual = true))))
        whenever(favorites.getFavorites(7, ContentType.LIVE)).thenReturn(flowOf((1L..3L).map {
            Favorite(id = it, providerId = 7, contentId = it, contentType = ContentType.LIVE, position = it.toInt())
        }))
        // getChannelsByIds applies parental-level-3 visibility and excludes protected row 3.
        whenever(channels.getChannelsByIds(listOf(1L, 2L, 3L))).thenReturn(flowOf((1L..2L).map { Channel(id = it, providerId = 7, streamId = it, name = "Channel $it") }))
        whenever(orders.hasSavedOrder(any())).thenReturn(true)
        whenever(orders.observe(any())).thenReturn(flowOf(LiveChannelOrder(LiveChannelOrderMode.MANUAL, listOf("stream:3", "stream:1", "stream:2"), revision = 1)))
        whenever(orders.save(any(), any(), any())).thenAnswer { LiveChannelOrderSaveResult.Saved(it.getArgument(2)) }
        whenever(preferences.defaultLiveChannelOrder).thenReturn(flowOf(LiveChannelOrderMode.PROVIDER))
        whenever(preferences.isIncognitoMode).thenReturn(flowOf(true))
        backend.channels(7, VirtualCategoryIds.FAVORITES, "All")
        verify(orders, never()).save(any(), any(), any())
    }

    @Test fun `first combined favorites visit must seed legacy order`() = runTest {
        val provider = LegacyProvider(id = 7, name = "Provider", type = ProviderType.M3U, serverUrl = "https://example.test", createdAt = 123)
        val profile = CombinedM3uProfile(id = 31, identityKey = "profile-uuid", name = "Family", members = listOf(CombinedM3uProfileMember(profileId = 31, providerId = 7, priority = 0)))
        val rows = listOf(Channel(id = 1, providerId = 7, streamId = 1, name = "One"), Channel(id = 2, providerId = 7, streamId = 2, name = "Two"))
        whenever(combined.getProfile(31)).thenReturn(profile)
        whenever(providers.getProvider(7)).thenReturn(provider)
        whenever(favorites.getFavorites(listOf(7L), ContentType.LIVE)).thenReturn(flowOf(listOf(
            Favorite(id = 1, providerId = 7, contentId = 1, contentType = ContentType.LIVE, position = 0),
            Favorite(id = 2, providerId = 7, contentId = 2, contentType = ContentType.LIVE, position = 1)
        )))
        whenever(channels.getChannelsByIds(listOf(1L, 2L))).thenReturn(flowOf(rows))
        whenever(orders.observe(any())).thenReturn(flowOf(LiveChannelOrder()))
        whenever(orders.hasSavedOrder(any())).thenReturn(false)
        whenever(orders.initializeIfAbsent(any(), any())).thenAnswer { it.getArgument<LiveChannelOrder>(1) }
        whenever(preferences.defaultLiveChannelOrder).thenReturn(flowOf(LiveChannelOrderMode.PROVIDER))
        whenever(preferences.isIncognitoMode).thenReturn(flowOf(true))
        val catalog = backend.combinedChannels(31, VirtualCategoryIds.FAVORITES, PhoneLabels("Favorites", "Recent", "All"))!!
        assertThat(catalog.mode).isEqualTo(LiveChannelOrderMode.MANUAL)
        assertThat(catalog.hasManualOrder).isTrue()
    }

    @Test fun `protected combined category must not reveal metadata to restricted session`() = runTest {
        val provider = LegacyProvider(id = 7, name = "Provider", type = ProviderType.M3U, serverUrl = "https://example.test", createdAt = 123)
        val profile = CombinedM3uProfile(id = 31, identityKey = "profile-uuid", name = "Family", members = listOf(CombinedM3uProfileMember(profileId = 31, providerId = 7, priority = 0)))
        val category = Category(id = CombinedCategoryIdentity.idForName("Locked"), name = "Locked", isUserProtected = true)
        val pair = CombinedCategory(category, listOf(CombinedCategoryBinding(7, "Provider", 12)))
        whenever(combined.getProfile(31)).thenReturn(profile)
        whenever(providers.getProvider(7)).thenReturn(provider)
        whenever(combined.getCombinedCategories(31)).thenReturn(flowOf(listOf(pair)))
        whenever(combined.getCombinedChannels(31, pair)).thenReturn(flowOf(emptyList()))
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(Category(id = 12, name = "Locked", isUserProtected = true))))
        whenever(orders.observe(any())).thenReturn(flowOf(LiveChannelOrder()))
        whenever(preferences.defaultLiveChannelOrder).thenReturn(flowOf(LiveChannelOrderMode.PROVIDER))
        whenever(preferences.isIncognitoMode).thenReturn(flowOf(true))
        assertThat(backend.combinedChannels(31, category.id, PhoneLabels("Favorites", "Recent", "All"), allowProtected = false)).isNull()
    }
}

