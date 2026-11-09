package com.streamvault.feature.live.phone

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import com.streamvault.domain.policy.combinedLiveChannelOrderScope
import com.streamvault.domain.policy.liveFavoritesOrderScope
import com.streamvault.domain.policy.liveProviderOrderIdentity
import com.streamvault.domain.repository.*
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.usecase.GetCustomCategories
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

class PhoneOrderingBackendTest {
    private val providers: ProviderRepository = mock()
    private val categories: CategoryRepository = mock()
    private val channels: ChannelRepository = mock()
    private val orders: LiveChannelOrderRepository = mock()
    private val preferences: SettingsPreferences = mock()
    private val custom: GetCustomCategories = mock()
    private val usage: LiveWatchUsageRepository = mock()
    private val favorites: FavoriteRepository = mock()
    private val combined: CombinedM3uRepository = mock()
    private val storedOrderScopes = mutableSetOf<LiveChannelOrderScope>()
    private val saved = MutableStateFlow(LiveChannelOrder())
    private val members = MutableStateFlow((1..3).map { Channel(id = it.toLong(), providerId = 7, streamId = it.toLong(), name = "Channel $it") })
    private val backend = PhoneOrderingBackend(providers, categories, channels, orders, preferences, custom, usage, favorites, combined)

    @Test fun `combined provider preview starts from provider order rather than saved manual order`() = runTest {
        val provider = LegacyProvider(id = 7L, name = "Provider", type = ProviderType.M3U, serverUrl = "https://example.test", createdAt = 123)
        val profile = CombinedM3uProfile(id = 31L, identityKey = "preview-profile", name = "Combined",
            members = listOf(CombinedM3uProfileMember(profileId = 31L, providerId = 7L, priority = 0)))
        val category = Category(id = CombinedCategoryIdentity.idForName("News"), name = "News")
        val pair = CombinedCategory(category, listOf(CombinedCategoryBinding(7L, "Provider", 12L)))
        val rows = listOf(Channel(id = 1, providerId = 7, streamId = 1, name = "One"), Channel(id = 2, providerId = 7, streamId = 2, name = "Two"))
        val identity = liveProviderOrderIdentity(provider)
        whenever(combined.getProfile(31)).thenReturn(profile)
        whenever(providers.getProvider(7)).thenReturn(provider)
        whenever(combined.getCombinedCategories(31)).thenReturn(flowOf(listOf(pair)))
        whenever(channels.getChannelsByCategory(7, 12)).thenReturn(flowOf(rows))
        whenever(orders.observe(any())).thenReturn(flowOf(LiveChannelOrder(LiveChannelOrderMode.MANUAL,
            listOf("$identity|stream:2", "$identity|stream:1"), revision = 4)))
        val labels = PhoneLabels("Favorites", "Recent", "All")
        val initial = backend.combinedChannels(31, category.id, labels)!!
        assertThat(initial.rows.map { it.name }).containsExactly("Two", "One").inOrder()
        val (result, preview) = backend.previewCombinedChannels(31, category.id, labels, initial.revision,
            initial.fingerprint, LiveChannelOrderMode.PROVIDER)
        assertThat(result).isEqualTo(PhoneSaveResult.Saved)
        assertThat(preview.map { it.name }).containsExactly("One", "Two").inOrder()
    }

    @Before fun setup() {
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(Category(id = 12, name = "Sports"))))
        whenever(channels.getChannelsByCategory(7, 12)).thenReturn(members)
        whenever(orders.observe(any())).thenReturn(saved)
        whenever(preferences.defaultLiveChannelOrder).thenReturn(flowOf(LiveChannelOrderMode.PROVIDER))
        whenever(preferences.isIncognitoMode).thenReturn(flowOf(false))
        whenever(usage.observe(7)).thenReturn(flowOf(emptyMap()))
    }

    private suspend fun prepare() {
        whenever(providers.getProvider(7)).thenReturn(LegacyProvider(id = 7, name = "Provider", type = ProviderType.M3U, serverUrl = "https://example.test", createdAt = 123))
        whenever(orders.hasSavedOrder(any())).thenAnswer { storedOrderScopes.contains(it.getArgument(0)) }
        whenever(orders.initializeIfAbsent(any(), any())).thenAnswer {
            val scope = it.getArgument<LiveChannelOrderScope>(0)
            storedOrderScopes += scope
            it.getArgument<LiveChannelOrder>(1).also { order -> saved.value = order }
        }
        whenever(orders.save(any(), any(), any())).thenAnswer {
            it.getArgument<LiveChannelOrder>(2).let { order ->
                val result = order.copy(revision = order.revision + 1)
                saved.value = result
                LiveChannelOrderSaveResult.Saved(result)
            }
        }
    }

    @Test fun `manual save rejects duplicate members and preserves remote identities`() = runTest {
        prepare()
        val initial = backend.channels(7, 12, "All")!!
        assertThat(backend.saveChannels(7, 12, "All", initial.revision, initial.fingerprint,
            LiveChannelOrderMode.MANUAL, true, listOf("7:1", "7:1", "7:3"))).isEqualTo(PhoneSaveResult.Invalid)
        whenever(orders.save(any(), any(), any())).thenAnswer { LiveChannelOrderSaveResult.Saved(it.getArgument(2)) }
        assertThat(backend.saveChannels(7, 12, "All", initial.revision, initial.fingerprint,
            LiveChannelOrderMode.MANUAL, true, listOf("7:3", "7:1", "7:2"))).isEqualTo(PhoneSaveResult.Saved)
        val order = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(any(), eq(0), order.capture())
        assertThat(order.firstValue.manualKeys).containsExactly("stream:3", "stream:1", "stream:2").inOrder()
    }

    @Test fun `changed membership and stale revision reject a draft`() = runTest {
        prepare()
        val initial = backend.channels(7, 12, "All")!!
        members.value = members.value.dropLast(1)
        assertThat(backend.saveChannels(7, 12, "All", initial.revision, initial.fingerprint,
            LiveChannelOrderMode.MANUAL, true, initial.rows.map { it.key })).isEqualTo(PhoneSaveResult.Conflict)
        members.value = members.value + Channel(id = 3, providerId = 7, streamId = 3, name = "Channel 3")
        saved.value = LiveChannelOrder(revision = 1)
        assertThat(backend.saveChannels(7, 12, "All", initial.revision, initial.fingerprint,
            LiveChannelOrderMode.MANUAL, true, initial.rows.map { it.key })).isEqualTo(PhoneSaveResult.Conflict)
        verify(orders, never()).save(any(), any(), any())
    }

    @Test fun `incognito does not expose watch derived ordering`() = runTest {
        prepare()
        whenever(preferences.isIncognitoMode).thenReturn(flowOf(true))
        whenever(preferences.defaultLiveChannelOrder).thenReturn(flowOf(LiveChannelOrderMode.RECENTLY_WATCHED))
        whenever(usage.observe(7)).thenReturn(flowOf(mapOf("stream:3" to LiveWatchUsage(lastWatchedAtMs = 100))))
        assertThat(backend.channels(7, 12, "All")!!.rows.map { it.key }).containsExactly("7:1", "7:2", "7:3").inOrder()
        verify(usage, never()).observe(any())
    }

    @Test fun `category reorder preserves sections and rejects a stale visibility snapshot`() = runTest {
        prepare()
        val hidden = MutableStateFlow(emptySet<Long>())
        val categoryOrder = LiveCategoryOrder(pinnedCategoryIds = setOf(12), revision = 3)
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(Category(id = 12, name = "Sports"), Category(id = 13, name = "News"))))
        whenever(preferences.getLiveCategoryOrder(7)).thenReturn(flowOf(categoryOrder))
        whenever(preferences.getHiddenCategoryIds(7, ContentType.LIVE)).thenReturn(hidden)
        whenever(preferences.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(preferences.showRecentChannelsCategory).thenReturn(flowOf(true))
        whenever(preferences.showAllChannelsCategory).thenReturn(flowOf(true))
        whenever(custom(7, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        val labels = PhoneLabels("Favorites", "Recent", "All")
        val initial = backend.categories(7, labels)
        val proposed = initial.sections.mapValues { (_, rows) -> rows.map { it.key } }.toMutableMap()
        proposed[PhoneSection.PINNED] = listOf("13")
        assertThat(backend.saveCategories(7, labels, initial.revision, initial.fingerprint, proposed)).isEqualTo(PhoneSaveResult.Invalid)
        proposed[PhoneSection.PINNED] = listOf("12")
        hidden.value = setOf(13)
        assertThat(backend.saveCategories(7, labels, initial.revision, initial.fingerprint, proposed)).isEqualTo(PhoneSaveResult.Conflict)
        verify(preferences, never()).saveLiveCategoryOrderWithPins(any(), any(), any())
    }

    @Test fun `combined category pinning saves to profile identity and leaves personal order fixed`() = runTest {
        val profile = CombinedM3uProfile(
            id = 31L, identityKey = "combined-phone-profile", name = "Family",
            members = listOf(CombinedM3uProfileMember(profileId = 31L, providerId = 7L, priority = 0))
        )
        val news = Category(id = CombinedCategoryIdentity.idForName("News"), name = "News")
        val sports = Category(id = CombinedCategoryIdentity.idForName("Sports"), name = "Sports")
        val binding = CombinedCategoryBinding(providerId = 7L, providerName = "Provider", categoryId = 12L)
        val savedOrder = LiveCategoryOrder(
            sortMode = CategorySortMode.TITLE_ASC,
            normalIds = listOf(news.id, sports.id),
            pinnedIds = listOf(sports.id),
            pinnedCategoryIds = setOf(sports.id),
            personalIds = listOf(VirtualCategoryIds.FAVORITES),
            revision = 4L
        )
        whenever(combined.getProfile(31L)).thenReturn(profile)
        whenever(combined.getCombinedCategories(31L)).thenReturn(flowOf(listOf(
            CombinedCategory(news, listOf(binding.copy(categoryId = 12L))),
            CombinedCategory(sports, listOf(binding.copy(categoryId = 13L)))
        )))
        whenever(custom(listOf(7L), ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferences.getCombinedLiveCategoryOrder(profile.identityKey)).thenReturn(flowOf(savedOrder))
        whenever(preferences.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(preferences.showRecentChannelsCategory).thenReturn(flowOf(true))
        whenever(preferences.showAllChannelsCategory).thenReturn(flowOf(true))
        whenever(preferences.saveCombinedLiveCategoryOrder(any(), any(), any())).thenAnswer {
            SaveLiveCategoryOrderResult.Saved(it.getArgument(2))
        }

        val labels = PhoneLabels("Favorites", "Recent", "All")
        val current = backend.combinedCategories(31L, labels)
        assertThat(current.editableSections).containsExactly(PhoneSection.PINNED, PhoneSection.PROVIDER)
        assertThat(current.sections.getValue(PhoneSection.PERSONAL).map { it.key })
            .containsExactly(VirtualCategoryIds.FAVORITES.toString(), VirtualCategoryIds.RECENT.toString(), ChannelRepository.ALL_CHANNELS_ID.toString()).inOrder()
        val proposed = current.sections.mapValues { (_, rows) -> rows.map { it.key } }.toMutableMap()
        proposed[PhoneSection.PINNED] = proposed.getValue(PhoneSection.PINNED) + news.id.toString()
        proposed[PhoneSection.PROVIDER] = emptyList()

        assertThat(backend.saveCombinedCategories(31L, labels, current.revision, current.fingerprint, proposed))
            .isEqualTo(PhoneSaveResult.Saved)
        val saved = argumentCaptor<LiveCategoryOrder>()
        verify(preferences).saveCombinedLiveCategoryOrder(eq(profile.identityKey), eq(4L), saved.capture())
        assertThat(saved.firstValue.sortMode).isEqualTo(CategorySortMode.TITLE_ASC)
        assertThat(saved.firstValue.pinnedCategoryIds).containsExactly(news.id, sports.id)
        assertThat(saved.firstValue.personalIds).isEqualTo(savedOrder.personalIds)
        verify(preferences, never()).saveLiveCategoryOrderWithPins(any(), any(), any())
    }

    @Test fun `combined channel reorder uses profile and provider qualified remote identities`() = runTest {
        val provider = LegacyProvider(id = 7L, name = "Provider", type = ProviderType.M3U,
            serverUrl = "https://example.test", createdAt = 123)
        val profile = CombinedM3uProfile(
            id = 31L, identityKey = "combined-channel-phone-profile", name = "Family",
            members = listOf(CombinedM3uProfileMember(profileId = 31L, providerId = 7L, priority = 0))
        )
        val category = Category(id = CombinedCategoryIdentity.idForName("News"), name = "News")
        val combinedCategory = CombinedCategory(category, listOf(
            CombinedCategoryBinding(providerId = 7L, providerName = "Provider", categoryId = 12L)
        ))
        val rows = listOf(
            Channel(id = 501L, providerId = 7L, streamId = 61L, name = "One"),
            Channel(id = 502L, providerId = 7L, streamId = 62L, name = "Two")
        )
        val providerIdentity = liveProviderOrderIdentity(provider)
        val scope = combinedLiveChannelOrderScope(profile, category)
        whenever(combined.getProfile(31L)).thenReturn(profile)
        whenever(providers.getProvider(7L)).thenReturn(provider)
        whenever(combined.getCombinedCategories(31L)).thenReturn(flowOf(listOf(combinedCategory)))
        whenever(combined.getCombinedChannels(31L, combinedCategory)).thenReturn(flowOf(rows))
        whenever(channels.getChannelsByCategory(7L, 12L)).thenReturn(flowOf(rows))
        whenever(orders.observe(scope)).thenReturn(flowOf(LiveChannelOrder(
            mode = LiveChannelOrderMode.MANUAL,
            manualKeys = listOf("$providerIdentity|stream:61", "$providerIdentity|stream:62"),
            revision = 9L
        )))
        whenever(preferences.defaultLiveChannelOrder).thenReturn(flowOf(LiveChannelOrderMode.PROVIDER))
        whenever(preferences.isIncognitoMode).thenReturn(flowOf(false))
        whenever(orders.save(any(), any(), any())).thenAnswer {
            LiveChannelOrderSaveResult.Saved(it.getArgument<LiveChannelOrder>(2))
        }

        val initial = backend.combinedChannels(31L, category.id, PhoneLabels("Favorites", "Recent", "All"))!!
        assertThat(initial.rows.map { it.key }).containsExactly("7:501", "7:502").inOrder()
        val moved = initial.rows.map { it.key }.reversed()
        assertThat(backend.saveCombinedChannels(31L, category.id, PhoneLabels("Favorites", "Recent", "All"),
            initial.revision, initial.fingerprint, LiveChannelOrderMode.MANUAL, true, moved))
            .isEqualTo(PhoneSaveResult.Saved)
        val capture = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(eq(scope), eq(9L), capture.capture())
        assertThat(capture.firstValue.manualKeys)
            .containsExactly("$providerIdentity|stream:62", "$providerIdentity|stream:61").inOrder()
    }

    @Test fun `pinning saves membership without activating manual provider sort`() = runTest {
        prepare()
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(Category(id = 12, name = "Sports"), Category(id = 13, name = "News"))))
        whenever(preferences.getLiveCategoryOrder(7)).thenReturn(flowOf(LiveCategoryOrder(sortMode = CategorySortMode.TITLE_ASC, normalIds = listOf(12, 13), revision = 3)))
        whenever(preferences.getHiddenCategoryIds(7, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferences.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(preferences.showRecentChannelsCategory).thenReturn(flowOf(true))
        whenever(preferences.showAllChannelsCategory).thenReturn(flowOf(true))
        whenever(custom(7, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferences.saveLiveCategoryOrderWithPins(any(), any(), any())).thenAnswer { SaveLiveCategoryOrderResult.Saved(it.getArgument(2)) }
        val labels = PhoneLabels("Favorites", "Recent", "All")
        val initial = backend.categories(7, labels)
        val proposed = initial.sections.mapValues { (_, rows) -> rows.map { it.key } }.toMutableMap()
        proposed[PhoneSection.PROVIDER] = proposed.getValue(PhoneSection.PROVIDER).filterNot { it == "12" }
        proposed[PhoneSection.PINNED] = listOf("12")
        assertThat(backend.saveCategories(7, labels, initial.revision, initial.fingerprint, proposed)).isEqualTo(PhoneSaveResult.Saved)
        val saved = argumentCaptor<LiveCategoryOrder>()
        verify(preferences).saveLiveCategoryOrderWithPins(eq(7), eq(3), saved.capture())
        assertThat(saved.firstValue.pinnedCategoryIds).containsExactly(12L)
        assertThat(saved.firstValue.sortMode).isEqualTo(CategorySortMode.TITLE_ASC)
        assertThat(saved.firstValue.normalIds).containsExactly(12L, 13L).inOrder()
    }

    @Test fun `unpinning restores the effective normal position without changing sort`() = runTest {
        prepare()
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(Category(id = 12, name = "Sports"), Category(id = 13, name = "News"))))
        whenever(preferences.getLiveCategoryOrder(7)).thenReturn(flowOf(LiveCategoryOrder(sortMode = CategorySortMode.TITLE_ASC, normalIds = listOf(12, 13), pinnedCategoryIds = setOf(12), revision = 3)))
        whenever(preferences.getHiddenCategoryIds(7, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferences.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(preferences.showRecentChannelsCategory).thenReturn(flowOf(true))
        whenever(preferences.showAllChannelsCategory).thenReturn(flowOf(true))
        whenever(custom(7, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferences.saveLiveCategoryOrderWithPins(any(), any(), any())).thenAnswer { SaveLiveCategoryOrderResult.Saved(it.getArgument(2)) }
        val labels = PhoneLabels("Favorites", "Recent", "All")
        val initial = backend.categories(7, labels)
        assertThat(initial.normalReference).containsExactly("13", "12").inOrder()
        val proposed = initial.sections.mapValues { (_, rows) -> rows.map { it.key } }.toMutableMap()
        proposed[PhoneSection.PINNED] = emptyList()
        proposed[PhoneSection.PROVIDER] = listOf("13", "12")
        assertThat(backend.saveCategories(7, labels, initial.revision, initial.fingerprint, proposed)).isEqualTo(PhoneSaveResult.Saved)
        val saved = argumentCaptor<LiveCategoryOrder>()
        verify(preferences).saveLiveCategoryOrderWithPins(eq(7), eq(3), saved.capture())
        assertThat(saved.firstValue.pinnedCategoryIds).isEmpty()
        assertThat(saved.firstValue.sortMode).isEqualTo(CategorySortMode.TITLE_ASC)
        assertThat(saved.firstValue.normalIds).containsExactly(12L, 13L).inOrder()
    }

    @Test fun `moving an unpinned category away from effective order activates manual sort`() = runTest {
        prepare()
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(Category(id = 12, name = "Sports"), Category(id = 13, name = "News"))))
        whenever(preferences.getLiveCategoryOrder(7)).thenReturn(flowOf(LiveCategoryOrder(sortMode = CategorySortMode.TITLE_ASC, revision = 3)))
        whenever(preferences.getHiddenCategoryIds(7, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferences.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(preferences.showRecentChannelsCategory).thenReturn(flowOf(true))
        whenever(preferences.showAllChannelsCategory).thenReturn(flowOf(true))
        whenever(custom(7, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferences.saveLiveCategoryOrderWithPins(any(), any(), any())).thenAnswer { SaveLiveCategoryOrderResult.Saved(it.getArgument(2)) }
        val labels = PhoneLabels("Favorites", "Recent", "All")
        val initial = backend.categories(7, labels)
        val proposed = initial.sections.mapValues { (_, rows) -> rows.map { it.key } }.toMutableMap()
        proposed[PhoneSection.PROVIDER] = listOf("12", "13")
        assertThat(backend.saveCategories(7, labels, initial.revision, initial.fingerprint, proposed)).isEqualTo(PhoneSaveResult.Saved)
        val saved = argumentCaptor<LiveCategoryOrder>()
        verify(preferences).saveLiveCategoryOrderWithPins(eq(7), eq(3), saved.capture())
        assertThat(saved.firstValue.sortMode).isEqualTo(CategorySortMode.MANUAL)
        assertThat(saved.firstValue.normalIds).containsExactly(12L, 13L).inOrder()
    }

    @Test fun `favorite order migrates legacy positions and saves through shared order scope`() = runTest {
        prepare()
        val category = Category(VirtualCategoryIds.FAVORITES, name = "Favorites", isVirtual = true)
        val snapshot = (1L..3L).map { Favorite(id = it, providerId = 7, contentId = 100 + it,
            contentType = ContentType.LIVE, position = (it * 1024).toInt(), addedAt = it) }
        whenever(custom(7, ContentType.LIVE)).thenReturn(flowOf(listOf(category)))
        whenever(favorites.getFavorites(7, ContentType.LIVE)).thenReturn(flowOf(snapshot))
        whenever(channels.getChannelsByIds(listOf(101, 102, 103))).thenReturn(flowOf(listOf(
            Channel(id = 101, providerId = 7, streamId = 101, name = "One"),
            Channel(id = 103, providerId = 7, streamId = 103, name = "Three"))))
        val initial = backend.channels(7, VirtualCategoryIds.FAVORITES, "All")!!
        assertThat(initial.fixedOrder).isFalse()
        assertThat(initial.mode).isEqualTo(LiveChannelOrderMode.MANUAL)
        assertThat(initial.rows.map { it.key }).containsExactly("7:101", "7:103").inOrder()
        assertThat(backend.saveChannels(7, VirtualCategoryIds.FAVORITES, "All", initial.revision, initial.fingerprint,
            LiveChannelOrderMode.MANUAL, true, listOf("7:103", "7:101"))).isEqualTo(PhoneSaveResult.Saved)
        val order = argumentCaptor<LiveChannelOrder>()
        verify(orders).initializeIfAbsent(any(), order.capture())
        assertThat(order.firstValue.manualKeys).containsExactly("stream:101", "stream:103").inOrder()
        verify(orders).save(any(), eq(initial.revision), order.capture())
        assertThat(order.secondValue.manualKeys).containsExactly("stream:103", "stream:101").inOrder()
    }

    @Test fun `favorites support explicit sort overrides in the same shared scope`() = runTest {
        prepare()
        val provider = LegacyProvider(id = 7, name = "Provider", type = ProviderType.M3U,
            serverUrl = "https://example.test", createdAt = 123)
        whenever(providers.getProvider(7)).thenReturn(provider)
        val category = Category(VirtualCategoryIds.FAVORITES, name = "Favorites", isVirtual = true)
        val legacyFavorites = listOf(
            Favorite(id = 1, providerId = 7, contentId = 101, contentType = ContentType.LIVE, position = 0, addedAt = 1),
            Favorite(id = 2, providerId = 7, contentId = 102, contentType = ContentType.LIVE, position = 1024, addedAt = 2)
        )
        whenever(custom(7, ContentType.LIVE)).thenReturn(flowOf(listOf(category)))
        whenever(favorites.getFavorites(7, ContentType.LIVE)).thenReturn(flowOf(legacyFavorites))
        whenever(channels.getChannelsByIds(listOf(101, 102))).thenReturn(flowOf(listOf(
            Channel(id = 101, providerId = 7, streamId = 101, name = "Zulu"),
            Channel(id = 102, providerId = 7, streamId = 102, name = "Alpha")
        )))

        val initial = backend.channels(7, VirtualCategoryIds.FAVORITES, "All")!!
        assertThat(initial.mode).isEqualTo(LiveChannelOrderMode.MANUAL)
        assertThat(initial.rows.map { it.name }).containsExactly("Zulu", "Alpha").inOrder()
        val (previewResult, preview) = backend.previewChannels(
            7, VirtualCategoryIds.FAVORITES, "All", initial.revision, initial.fingerprint,
            LiveChannelOrderMode.TITLE_ASC
        )
        assertThat(previewResult).isEqualTo(PhoneSaveResult.Saved)
        assertThat(preview.map { it.name }).containsExactly("Alpha", "Zulu").inOrder()
        verify(orders, never()).save(any(), any(), any())

        assertThat(backend.saveChannels(
            7, VirtualCategoryIds.FAVORITES, "All", initial.revision, initial.fingerprint,
            LiveChannelOrderMode.TITLE_ASC, false, preview.map { it.key }
        )).isEqualTo(PhoneSaveResult.Saved)
        val requested = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(
            eq(liveFavoritesOrderScope(liveProviderOrderIdentity(provider))),
            eq(initial.revision),
            requested.capture()
        )
        assertThat(requested.firstValue.mode).isEqualTo(LiveChannelOrderMode.TITLE_ASC)
        assertThat(requested.firstValue.manualKeys).containsExactly("stream:101", "stream:102").inOrder()
    }

    @Test fun `custom group phone draft conflicts after membership changes`() = runTest {
        prepare()
        val group = Category(-44, name = "Family", isVirtual = true)
        whenever(favorites.getGroups(7, ContentType.LIVE)).thenReturn(flowOf(listOf(
            VirtualGroup(id = 44, providerId = 7, name = "Family", identityKey = "stable-family"))))
        val entries = MutableStateFlow(listOf(Favorite(id = 1, providerId = 7, contentId = 101,
            contentType = ContentType.LIVE, groupId = 44, position = 0, addedAt = 1)))
        whenever(custom(7, ContentType.LIVE)).thenReturn(flowOf(listOf(group)))
        whenever(favorites.getFavoritesByGroup(44)).thenReturn(entries)
        whenever(channels.getChannelsByIds(listOf(101))).thenReturn(flowOf(listOf(Channel(id = 101, providerId = 7, streamId = 101, name = "One"))))
        val initial = backend.channels(7, -44, "All")!!
        entries.value = entries.value + Favorite(id = 2, providerId = 7, contentId = 102,
            contentType = ContentType.LIVE, groupId = 44, position = 1024, addedAt = 2)
        whenever(channels.getChannelsByIds(listOf(101, 102))).thenReturn(flowOf(listOf(
            Channel(id = 101, providerId = 7, streamId = 101, name = "One"),
            Channel(id = 102, providerId = 7, streamId = 102, name = "Two"))))
        assertThat(backend.saveChannels(7, -44, "All", initial.revision, initial.fingerprint,
            LiveChannelOrderMode.MANUAL, true, initial.rows.map { it.key })).isEqualTo(PhoneSaveResult.Conflict)
        val reconciled = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(any(), eq(initial.revision), reconciled.capture())
        assertThat(reconciled.firstValue.manualKeys).containsExactly("stream:101", "stream:102").inOrder()
    }

    @Test fun `favorite reorder keeps quality variants together`() = runTest {
        prepare()
        val category = Category(VirtualCategoryIds.FAVORITES, name = "Favorites", isVirtual = true)
        val snapshot = (1L..3L).map { Favorite(id = it, providerId = 7, contentId = 100 + it,
            contentType = ContentType.LIVE, position = (it * 1024).toInt(), addedAt = it) }
        val grouped = Channel(id = 101, providerId = 7, name = "Grouped", logicalGroupId = "grouped", variants = listOf(
            LiveChannelVariant(rawChannelId = 101, providerId = 7, streamId = 101, logicalGroupId = "grouped", originalName = "SD", canonicalName = "Grouped", streamUrl = "https://example.test/sd"),
            LiveChannelVariant(rawChannelId = 102, providerId = 7, streamId = 102, logicalGroupId = "grouped", originalName = "HD", canonicalName = "Grouped", streamUrl = "https://example.test/hd")))
        val other = Channel(id = 103, providerId = 7, streamId = 103, name = "Other")
        whenever(custom(7, ContentType.LIVE)).thenReturn(flowOf(listOf(category)))
        whenever(favorites.getFavorites(7, ContentType.LIVE)).thenReturn(flowOf(snapshot))
        whenever(channels.getChannelsByIds(listOf(101, 102, 103))).thenReturn(flowOf(listOf(grouped, other)))
        val initial = backend.channels(7, VirtualCategoryIds.FAVORITES, "All")!!
        assertThat(initial.rows.map { it.key }).containsExactly("7:101", "7:103").inOrder()
        assertThat(backend.saveChannels(7, VirtualCategoryIds.FAVORITES, "All", initial.revision, initial.fingerprint,
            LiveChannelOrderMode.MANUAL, true, listOf("7:103", "7:101"))).isEqualTo(PhoneSaveResult.Saved)
        val order = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(any(), eq(initial.revision), order.capture())
        assertThat(order.firstValue.manualKeys).containsExactly("stream:103", "stream:101", "stream:102").inOrder()
    }

    @Test fun `unprivileged category session omits protected rows and preserves their pins`() = runTest {
        prepare()
        val visible = Category(id = 12, name = "News")
        val locked = Category(id = 13, name = "Protected", isUserProtected = true)
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(visible, locked)))
        whenever(preferences.getLiveCategoryOrder(7)).thenReturn(flowOf(LiveCategoryOrder(
            sortMode = CategorySortMode.MANUAL, normalIds = listOf(12, 13), pinnedCategoryIds = setOf(13), revision = 3)))
        whenever(preferences.getHiddenCategoryIds(7, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferences.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(preferences.showRecentChannelsCategory).thenReturn(flowOf(true))
        whenever(preferences.showAllChannelsCategory).thenReturn(flowOf(true))
        whenever(custom(7, ContentType.LIVE)).thenReturn(flowOf(emptyList()))
        whenever(preferences.saveLiveCategoryOrderWithPins(any(), any(), any())).thenAnswer { SaveLiveCategoryOrderResult.Saved(it.getArgument(2)) }
        val labels = PhoneLabels("Favorites", "Recent", "All")
        val initial = backend.categories(7, labels, allowProtected = false)
        assertThat(initial.sections.values.flatten().map { it.name }).doesNotContain("Protected")
        assertThat(backend.channels(7, 13, "All", allowProtected = false)).isNull()
        val proposed = initial.sections.mapValues { (_, rows) -> rows.map { it.key } }.toMutableMap()
        proposed[PhoneSection.PROVIDER] = emptyList()
        proposed[PhoneSection.PINNED] = listOf("12")
        assertThat(backend.saveCategories(7, labels, initial.revision, initial.fingerprint, proposed, allowProtected = false)).isEqualTo(PhoneSaveResult.Saved)
        val saved = argumentCaptor<LiveCategoryOrder>()
        verify(preferences).saveLiveCategoryOrderWithPins(eq(7), eq(3), saved.capture())
        assertThat(saved.firstValue.pinnedCategoryIds).containsExactly(12L, 13L)
        assertThat(saved.firstValue.normalIds).containsExactly(12L, 13L).inOrder()
    }

    @Test fun `unprivileged channel reorder keeps protected slots in their original positions`() = runTest {
        prepare()
        val raw = listOf(Channel(id = 1, providerId = 7, streamId = 1, name = "One"),
            Channel(id = 2, providerId = 7, streamId = 2, name = "Protected", isUserProtected = true),
            Channel(id = 3, providerId = 7, streamId = 3, name = "Three"))
        whenever(channels.getChannelsByCategory(7, 12)).thenReturn(flowOf(raw))
        whenever(orders.save(any(), any(), any())).thenAnswer { LiveChannelOrderSaveResult.Saved(it.getArgument(2)) }
        val initial = backend.channels(7, 12, "All", allowProtected = false)!!
        assertThat(initial.rows.map { it.name }).containsExactly("One", "Three").inOrder()
        assertThat(backend.saveChannels(7, 12, "All", initial.revision, initial.fingerprint,
            LiveChannelOrderMode.MANUAL, true, listOf("7:3", "7:1"), allowProtected = false)).isEqualTo(PhoneSaveResult.Saved)
        val requested = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(any(), eq(0), requested.capture())
        assertThat(requested.firstValue.manualKeys).containsExactly("stream:3", "stream:2", "stream:1").inOrder()
    }

    @Test fun `unprivileged all channels excludes unlocked protected category and retains its slot`() = runTest {
        prepare()
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(
            Category(id = 12, name = "Public"), Category(id = 13, name = "Locked", isUserProtected = true))))
        val raw = listOf(
            Channel(id = 1, providerId = 7, streamId = 1, name = "One", categoryId = 12),
            // The TV presentation layer clears protection flags for unlocked categories.
            Channel(id = 2, providerId = 7, streamId = 2, name = "Private", categoryId = 13),
            Channel(id = 3, providerId = 7, streamId = 3, name = "Three", categoryId = 12))
        whenever(channels.getChannelsByCategory(7, ChannelRepository.ALL_CHANNELS_ID)).thenReturn(flowOf(raw))
        whenever(orders.save(any(), any(), any())).thenAnswer { LiveChannelOrderSaveResult.Saved(it.getArgument(2)) }
        val initial = backend.channels(7, ChannelRepository.ALL_CHANNELS_ID, "All", allowProtected = false)!!
        assertThat(initial.rows.map { it.name }).containsExactly("One", "Three").inOrder()
        assertThat(backend.saveChannels(7, ChannelRepository.ALL_CHANNELS_ID, "All", initial.revision,
            initial.fingerprint, LiveChannelOrderMode.MANUAL, true, listOf("7:3", "7:1"), false))
            .isEqualTo(PhoneSaveResult.Saved)
        val order = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(any(), eq(0), order.capture())
        assertThat(order.firstValue.manualKeys).containsExactly("stream:3", "stream:2", "stream:1").inOrder()
    }

    @Test fun `unprivileged favorites excludes channels from unlocked protected categories`() = runTest {
        prepare()
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(
            Category(id = 12, name = "Public"), Category(id = 13, name = "Adult", isAdult = true))))
        whenever(custom(7, ContentType.LIVE)).thenReturn(flowOf(listOf(
            Category(VirtualCategoryIds.FAVORITES, name = "Favorites", isVirtual = true))))
        val snapshot = (1L..2L).map { Favorite(id = it, providerId = 7, contentId = it,
            contentType = ContentType.LIVE, position = it.toInt(), addedAt = it) }
        whenever(favorites.getFavorites(7, ContentType.LIVE)).thenReturn(flowOf(snapshot))
        whenever(channels.getChannelsByIds(listOf(1, 2))).thenReturn(flowOf(listOf(
            Channel(id = 1, providerId = 7, name = "Public", categoryId = 12),
            Channel(id = 2, providerId = 7, name = "Private", categoryId = 13))))
        assertThat(backend.channels(7, VirtualCategoryIds.FAVORITES, "All", false)!!.rows.map { it.name })
            .containsExactly("Public")
        assertThat(backend.channels(7, VirtualCategoryIds.FAVORITES, "All", true)!!.rows.map { it.name })
            .containsExactly("Public", "Private").inOrder()
    }

    @Test fun `unprivileged session excludes grouped rows containing protected variants`() = runTest {
        prepare()
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(
            Category(id = 12, name = "Public"), Category(id = 13, name = "Locked", isUserProtected = true))))
        fun variant(id: Long, category: Long, protected: Boolean = false) = LiveChannelVariant(
            rawChannelId = id, logicalGroupId = "group", providerId = 7, originalName = "Variant",
            canonicalName = "Group", streamUrl = "", streamId = id, categoryId = category,
            isUserProtected = protected)
        val rows = listOf(
            Channel(id = 1, providerId = 7, streamId = 1, name = "Public", categoryId = 12),
            Channel(id = 2, providerId = 7, streamId = 2, name = "Mixed category", categoryId = 12,
                variants = listOf(variant(2, 12), variant(3, 13))),
            Channel(id = 4, providerId = 7, streamId = 4, name = "Protected variant", categoryId = 12,
                variants = listOf(variant(4, 12), variant(5, 12, protected = true))))
        whenever(channels.getChannelsByCategory(7, ChannelRepository.ALL_CHANNELS_ID)).thenReturn(flowOf(rows))
        assertThat(backend.channels(7, ChannelRepository.ALL_CHANNELS_ID, "All", false)!!.rows.map { it.name })
            .containsExactly("Public")
        assertThat(backend.channels(7, ChannelRepository.ALL_CHANNELS_ID, "All", true)!!.rows).hasSize(3)
    }

    @Test fun `unprivileged session hides protected personal groups`() = runTest {
        prepare()
        val locked = Category(id = -44, name = "Private group", isVirtual = true, isUserProtected = true)
        whenever(custom(7, ContentType.LIVE)).thenReturn(flowOf(listOf(locked)))
        whenever(preferences.getLiveCategoryOrder(7)).thenReturn(flowOf(LiveCategoryOrder()))
        whenever(preferences.getHiddenCategoryIds(7, ContentType.LIVE)).thenReturn(flowOf(emptySet()))
        whenever(preferences.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(preferences.showRecentChannelsCategory).thenReturn(flowOf(true))
        whenever(preferences.showAllChannelsCategory).thenReturn(flowOf(true))
        val labels = PhoneLabels("Favorites", "Recent", "All")
        assertThat(backend.categories(7, labels, allowProtected = false).sections.values.flatten().map { it.name }).doesNotContain("Private group")
        assertThat(backend.channels(7, -44, "All", allowProtected = false)).isNull()
    }
}
