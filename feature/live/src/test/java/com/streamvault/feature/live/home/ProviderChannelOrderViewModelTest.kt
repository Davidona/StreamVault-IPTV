package com.streamvault.feature.live.home

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.model.*
import com.streamvault.domain.policy.liveFavoritesOrderScope
import com.streamvault.domain.policy.liveProviderOrderIdentity
import com.streamvault.domain.repository.*
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.usecase.UnlockParentalCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProviderChannelOrderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val providers: ProviderRepository = mock()
    private val categories: CategoryRepository = mock()
    private val channels: ChannelRepository = mock()
    private val favorites: FavoriteRepository = mock()
    private val orders: LiveChannelOrderRepository = mock()
    private val preferences: SettingsPreferences = mock()
    private val parental: ParentalControlManager = mock()
    private val unlock: UnlockParentalCategory = mock()
    private val stored = MutableStateFlow(LiveChannelOrder())
    private val unlocked = MutableStateFlow(emptySet<Long>())
    private val provider = LegacyProvider(id = 7, name = "Inactive provider", type = ProviderType.M3U, serverUrl = "https://example.test", createdAt = 123)
    private val category = Category(id = 12, name = "Sports")
    private val rows = (1..2000).map { Channel(id = it.toLong(), providerId = 7, streamId = it.toLong(), name = "Channel $it") }
    private lateinit var model: ProviderChannelOrderViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        whenever(preferences.isIncognitoMode).thenReturn(flowOf(false))
        whenever(preferences.defaultLiveChannelOrder).thenReturn(flowOf(com.streamvault.domain.model.LiveChannelOrderMode.PROVIDER))
        whenever(preferences.parentalControlLevel).thenReturn(flowOf(0))
        whenever(parental.unlockedCategoriesForProvider(any())).thenReturn(unlocked)
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(category)))
        whenever(channels.getChannelsByCategory(7, 12)).thenReturn(flowOf(rows))
        whenever(orders.observe(any())).thenReturn(stored)
        model = ProviderChannelOrderViewModel(providers, categories, channels, favorites, orders, preferences, parental, unlock)
    }

    @After fun cleanup() { model.reset(); Dispatchers.resetMain() }

    private suspend fun load() {
        whenever(providers.getProvider(7)).thenReturn(provider)
        model.open(7, category)
        dispatcher.scheduler.advanceUntilIdle()
    }

    @Test fun `manages inactive provider without switching playback source`() = runTest {
        load()
        assertThat(model.uiState.value.channels).hasSize(2000)
        verify(providers, never()).setActiveProvider(any())
        verify(providers, never()).getActiveProvider()
    }

    @Test fun `direct move persists remote identities and survives reopening`() = runTest {
        whenever(orders.save(any(), any(), any())).thenAnswer { invocation ->
            stored.value = invocation.getArgument<LiveChannelOrder>(2).copy(revision = 1)
            LiveChannelOrderSaveResult.Saved(stored.value)
        }
        load()
        model.move(rows.last(), 1)
        model.save()
        advanceUntilIdle()
        assertThat(model.uiState.value.closed).isTrue()
        assertThat(stored.value.manualKeys.first()).isEqualTo("stream:2000")
        model.reset()
        load()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(2000L)
    }

    @Test fun `unchanged draft does not create override`() = runTest {
        load()
        model.save()
        advanceUntilIdle()
        verify(orders, never()).save(any(), any(), any())
        assertThat(model.uiState.value.closed).isTrue()
    }

    @Test fun `favorites migrate legacy positions and save explicit modes through the shared scope`() = runTest {
        whenever(providers.getProvider(7)).thenReturn(provider)
        val favoriteCategory = Category(VirtualCategoryIds.FAVORITES, name = "Favorites", isVirtual = true)
        val snapshot = listOf(
            Favorite(id = 1, providerId = 7, contentId = 101, contentType = ContentType.LIVE, position = 0, addedAt = 1),
            Favorite(id = 2, providerId = 7, contentId = 102, contentType = ContentType.LIVE, position = 1024, addedAt = 2)
        )
        val favoriteRows = listOf(
            Channel(id = 101, providerId = 7, streamId = 101, name = "Zulu"),
            Channel(id = 102, providerId = 7, streamId = 102, name = "Alpha")
        )
        val scope = liveFavoritesOrderScope(liveProviderOrderIdentity(provider))
        whenever(favorites.getFavorites(7, ContentType.LIVE)).thenReturn(flowOf(snapshot))
        whenever(channels.getChannelsByIds(listOf(101, 102))).thenReturn(flowOf(favoriteRows))
        whenever(orders.hasSavedOrder(eq(scope))).thenReturn(false)
        whenever(orders.initializeIfAbsent(eq(scope), any())).thenAnswer { it.getArgument<LiveChannelOrder>(1) }
        whenever(orders.save(eq(scope), any(), any())).thenAnswer {
            LiveChannelOrderSaveResult.Saved(it.getArgument(2))
        }

        model.open(7, favoriteCategory)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(model.uiState.value.mode).isEqualTo(LiveChannelOrderMode.MANUAL)
        assertThat(model.uiState.value.channels.map { it.name }).containsExactly("Zulu", "Alpha").inOrder()
        val seeded = argumentCaptor<LiveChannelOrder>()
        verify(orders).initializeIfAbsent(eq(scope), seeded.capture())
        assertThat(seeded.firstValue.manualKeys).containsExactly("stream:101", "stream:102").inOrder()

        model.selectMode(LiveChannelOrderMode.TITLE_ASC)
        assertThat(model.uiState.value.channels.map { it.name }).containsExactly("Alpha", "Zulu").inOrder()
        model.save()
        dispatcher.scheduler.advanceUntilIdle()
        val requested = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(eq(scope), eq(0L), requested.capture())
        assertThat(requested.firstValue.mode).isEqualTo(LiveChannelOrderMode.TITLE_ASC)
        assertThat(requested.firstValue.manualKeys).containsExactly("stream:101", "stream:102").inOrder()
    }

    @Test fun `save failure keeps complete editable draft`() = runTest {
        whenever(orders.save(any(), any(), any())).thenThrow(IllegalStateException("disk"))
        load()
        model.move(rows.last(), 1)
        model.save()
        advanceUntilIdle()
        assertThat(model.uiState.value.closed).isFalse()
        assertThat(model.uiState.value.saving).isFalse()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(2000L)
    }

    @Test fun `conflict keeps draft and requires reload`() = runTest {
        whenever(orders.save(any(), any(), any())).thenReturn(LiveChannelOrderSaveResult.Conflict(LiveChannelOrder(revision = 2)))
        load()
        model.move(rows.last(), 1)
        model.save()
        advanceUntilIdle()
        assertThat(model.uiState.value.closed).isFalse()
        assertThat(model.uiState.value.message).isNotNull()
    }

    @Test fun `locked category cannot load channels until correct PIN`() = runTest {
        whenever(preferences.parentalControlLevel).thenReturn(flowOf(1))
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(category.copy(isAdult = true))))
        load()
        assertThat(model.uiState.value.locked).isTrue()
        verify(channels, never()).getChannelsByCategory(any(), any())
        whenever(unlock(any())).thenAnswer { unlocked.value = setOf(12); Result.success(Unit) }
        model.unlock("1234")
        advanceUntilIdle()
        assertThat(model.uiState.value.locked).isFalse()
        assertThat(model.uiState.value.channels).hasSize(2000)
    }

    @Test fun `default restores inheritance and retains saved arrangement`() = runTest {
        stored.value = LiveChannelOrder(LiveChannelOrderMode.MANUAL, listOf("stream:2000", "stream:1"), 4)
        whenever(orders.save(any(), any(), any())).thenAnswer { LiveChannelOrderSaveResult.Saved(it.getArgument(2)) }
        load()
        model.save(useDefault = true)
        advanceUntilIdle()
        val capture = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(any(), eq(4), capture.capture())
        assertThat(capture.firstValue.mode).isEqualTo(LiveChannelOrderMode.USE_DEFAULT)
        assertThat(capture.firstValue.manualKeys).isEqualTo(stored.value.manualKeys)
    }

    @Test fun `opening manual editor starts from effective default across complete category`() = runTest {
        whenever(preferences.defaultLiveChannelOrder).thenReturn(flowOf(LiveChannelOrderMode.TITLE_DESC))
        load()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(999L)
        model.move(model.uiState.value.channels.last(), 1)
        model.save()
        advanceUntilIdle()
        val capture = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(any(), any(), capture.capture())
        assertThat(capture.firstValue.mode).isEqualTo(LiveChannelOrderMode.MANUAL)
        assertThat(capture.firstValue.manualKeys).hasSize(2000)
    }

    @Test fun `incognito editor ignores watch based ordering`() = runTest {
        val usage: LiveWatchUsageRepository = mock()
        whenever(preferences.isIncognitoMode).thenReturn(flowOf(true))
        whenever(preferences.defaultLiveChannelOrder).thenReturn(flowOf(LiveChannelOrderMode.RECENTLY_WATCHED))
        whenever(usage.observe(7)).thenReturn(flowOf(mapOf("stream:2000" to LiveWatchUsage(lastWatchedAtMs = 100))))
        model.reset()
        model = ProviderChannelOrderViewModel(providers, categories, channels, favorites, orders, preferences, parental, unlock, usage)
        load()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(1L)
        verify(usage, never()).observe(any())
    }

    @Test fun `predefined preview can cancel without writes and save retains manual arrangement`() = runTest {
        val manual = listOf("stream:2000", "stream:1")
        stored.value = LiveChannelOrder(LiveChannelOrderMode.MANUAL, manual, 4)
        whenever(orders.save(any(), any(), any())).thenAnswer { LiveChannelOrderSaveResult.Saved(it.getArgument(2)) }
        load()
        model.selectMode(LiveChannelOrderMode.TITLE_DESC)
        assertThat(model.uiState.value.channels.first().id).isEqualTo(999L)
        verify(orders, never()).save(any(), any(), any())
        model.reset()
        load()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(2000L)
        model.selectMode(LiveChannelOrderMode.TITLE_DESC)
        model.save()
        advanceUntilIdle()
        val capture = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(any(), eq(4), capture.capture())
        assertThat(capture.firstValue.mode).isEqualTo(LiveChannelOrderMode.TITLE_DESC)
        assertThat(capture.firstValue.manualKeys).isEqualTo(manual)
    }

    @Test fun `manual reset previews provider order supports undo and preserves hidden slots`() = runTest {
        stored.value = LiveChannelOrder(
            LiveChannelOrderMode.MANUAL,
            listOf("stream:2000", "stream:1", "stream:9999"),
            6
        )
        whenever(orders.save(any(), any(), any())).thenAnswer { LiveChannelOrderSaveResult.Saved(it.getArgument(2)) }
        load()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(2000L)

        model.resetManualArrangement()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(1L)
        assertThat(model.hasChanges()).isTrue()
        model.undo()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(2000L)
        assertThat(model.hasChanges()).isFalse()
        model.redo()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(1L)
        model.save()
        advanceUntilIdle()

        val capture = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(any(), eq(6), capture.capture())
        assertThat(capture.firstValue.mode).isEqualTo(LiveChannelOrderMode.MANUAL)
        assertThat(capture.firstValue.manualKeys.first()).isEqualTo("stream:1")
        assertThat(capture.firstValue.manualKeys[2]).isEqualTo("stream:9999")
    }

    @Test fun `manual reset keeps an inactive saved sequence inactive`() = runTest {
        stored.value = LiveChannelOrder(LiveChannelOrderMode.TITLE_DESC, listOf("stream:2000", "stream:1"), 5)
        whenever(orders.save(any(), any(), any())).thenAnswer { LiveChannelOrderSaveResult.Saved(it.getArgument(2)) }
        load()
        model.resetManualArrangement()
        assertThat(model.uiState.value.mode).isEqualTo(LiveChannelOrderMode.TITLE_DESC)
        model.save()
        advanceUntilIdle()

        val capture = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(any(), eq(5), capture.capture())
        assertThat(capture.firstValue.mode).isEqualTo(LiveChannelOrderMode.TITLE_DESC)
        assertThat(capture.firstValue.manualKeys.first()).isEqualTo("stream:1")
    }

    @Test fun `manual mode restores retained arrangement and provider choice overrides default`() = runTest {
        stored.value = LiveChannelOrder(LiveChannelOrderMode.TITLE_ASC, listOf("stream:2000", "stream:1"), 4)
        load()
        model.selectMode(LiveChannelOrderMode.MANUAL)
        assertThat(model.uiState.value.channels.first().id).isEqualTo(2000L)
        model.selectMode(LiveChannelOrderMode.PROVIDER)
        assertThat(model.uiState.value.channels.first().id).isEqualTo(1L)
        assertThat(model.uiState.value.mode).isEqualTo(LiveChannelOrderMode.PROVIDER)
    }

    @Test fun `bulk move keeps draft order validates block bounds and undoes as one operation`() = runTest {
        load()
        model.moveMany(listOf(rows[1999], rows[1499]), 2000)
        assertThat(model.uiState.value.draft?.hasChanges).isFalse()
        model.moveMany(listOf(rows[1999], rows[1499]), 1)
        assertThat(model.uiState.value.channels.take(2).map { it.id }).containsExactly(1500L, 2000L).inOrder()
        assertThat(model.uiState.value.channels).hasSize(2000)
        model.undo()
        assertThat(model.uiState.value.channels).isEqualTo(rows)
    }

    @Test fun `explicit provider mode is a change even when the inherited sequence is identical`() = runTest {
        load()
        assertThat(model.hasChanges()).isFalse()
        model.selectMode(LiveChannelOrderMode.PROVIDER)
        assertThat(model.uiState.value.draft?.hasChanges).isFalse()
        assertThat(model.hasChanges()).isTrue()
        verify(orders, never()).save(any(), any(), any())
        model.save()
        advanceUntilIdle()
        val capture = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(any(), any(), capture.capture())
        assertThat(capture.firstValue.mode).isEqualTo(LiveChannelOrderMode.PROVIDER)
        assertThat(capture.firstValue.manualKeys).isEmpty()
    }

    @Test fun `dynamic mode previews use an entry snapshot and can become a manual refinement`() = runTest {
        val usage: LiveWatchUsageRepository = mock()
        val stats = MutableStateFlow(mapOf("stream:2000" to LiveWatchUsage(100, 1000)))
        whenever(usage.observe(7)).thenReturn(stats)
        model.reset()
        model = ProviderChannelOrderViewModel(providers, categories, channels, favorites, orders, preferences, parental, unlock, usage)
        load()
        model.selectMode(LiveChannelOrderMode.MOST_WATCHED)
        assertThat(model.uiState.value.channels.first().id).isEqualTo(2000L)
        stats.value = mapOf("stream:2" to LiveWatchUsage(200, 2000))
        advanceUntilIdle()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(2000L)
        model.move(rows.first(), 1)
        assertThat(model.uiState.value.mode).isEqualTo(LiveChannelOrderMode.MANUAL)
        model.save()
        advanceUntilIdle()
        val capture = argumentCaptor<LiveChannelOrder>()
        verify(orders).save(any(), any(), capture.capture())
        assertThat(capture.firstValue.mode).isEqualTo(LiveChannelOrderMode.MANUAL)
        assertThat(capture.firstValue.manualKeys.take(2)).containsExactly("stream:1", "stream:2000").inOrder()
    }

    @Test fun `out of range moves are ignored and pickup cancellation restores prior draft`() = runTest {
        load()
        model.move(rows.last(), 0)
        assertThat(model.uiState.value.channels.first().id).isEqualTo(1L)
        model.move(rows.last(), 1)
        model.beginMove()
        model.move(rows.last(), 2)
        model.cancelMove()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(2000L)
        model.undo()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(1L)
        model.redo()
        assertThat(model.uiState.value.channels.first().id).isEqualTo(2000L)
    }
}
