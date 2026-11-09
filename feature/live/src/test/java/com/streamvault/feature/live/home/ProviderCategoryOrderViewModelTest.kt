package com.streamvault.feature.live.home

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.usecase.GetCustomCategories
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProviderCategoryOrderViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val categories: CategoryRepository = mock()
    private val combined: CombinedM3uRepository = mock()
    private val preferences: SettingsPreferences = mock()
    private val custom: GetCustomCategories = mock()
    private val stored = MutableStateFlow(LiveCategoryOrder(sortMode = CategorySortMode.TITLE_ASC,
        normalIds = listOf(3, 1, 2), pinnedCategoryIds = setOf(2)))
    private lateinit var model: ProviderCategoryOrderViewModel

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        whenever(categories.getCategories(7)).thenReturn(flowOf(listOf(Category(1, name = "News", providerOrder = 1),
            Category(2, name = "Sports", providerOrder = 2), Category(3, name = "Kids", providerOrder = 3))))
        whenever(custom.invoke(eq(7L), eq(ContentType.LIVE))).thenReturn(flowOf(listOf(Category(-10, name = "Mine", isVirtual = true))))
        whenever(preferences.getLiveCategoryOrder(7)).thenReturn(stored)
        whenever(preferences.getHiddenCategoryIds(7, ContentType.LIVE)).thenReturn(flowOf(setOf(3)))
        whenever(preferences.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(preferences.showRecentChannelsCategory).thenReturn(flowOf(true))
        whenever(preferences.showAllChannelsCategory).thenReturn(flowOf(true))
        model = ProviderCategoryOrderViewModel(categories, combined, preferences, custom)
    }
    @After fun cleanup() { model.reset(); Dispatchers.resetMain() }
    private fun open() { model.open(7, "Favorites", "Recent", "All"); dispatcher.scheduler.advanceUntilIdle() }
    private suspend fun successfulSave() {
        whenever(preferences.saveLiveCategoryOrder(any(), any(), any())).thenAnswer { call ->
            stored.value = call.getArgument<LiveCategoryOrder>(2).copy(revision = stored.value.revision + 1)
            SaveLiveCategoryOrderResult.Saved(stored.value)
        }
    }

    @Test fun `opening and unchanged saving create no override`() = runTest {
        open(); model.save(); advanceUntilIdle()
        assertThat(model.uiState.value.closed).isTrue()
        verify(preferences, never()).saveLiveCategoryOrder(any(), any(), any())
    }

    @Test fun `editor includes hidden categories and all personal entries`() = runTest {
        open()
        assertThat(model.uiState.value.hiddenIds).contains(3L)
        assertThat(model.uiState.value.categories.keys).containsAtLeast(1L, 2L, 3L, -10L,
            VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT, ChannelRepository.ALL_CHANNELS_ID)
        assertThat(model.uiState.value.drafts.getValue(CategoryOrderSection.PINNED).keys).containsExactly("2")
    }

    @Test fun `personal edit preserves inactive manual provider arrangement and preset`() = runTest {
        successfulSave(); open()
        model.move(CategoryOrderSection.PERSONAL, ChannelRepository.ALL_CHANNELS_ID, 1)
        model.save(); advanceUntilIdle()
        assertThat(stored.value.sortMode).isEqualTo(CategorySortMode.TITLE_ASC)
        assertThat(stored.value.normalIds).containsExactly(3L, 1L, 2L).inOrder()
        assertThat(stored.value.personalIds.first()).isEqualTo(ChannelRepository.ALL_CHANNELS_ID)
    }

    @Test fun `normal edit activates manual and keeps pinned membership`() = runTest {
        successfulSave(); open()
        model.move(CategoryOrderSection.PROVIDER, 1, 1)
        model.save(); advanceUntilIdle()
        assertThat(stored.value.sortMode).isEqualTo(CategorySortMode.MANUAL)
        assertThat(stored.value.normalIds).containsExactly(1L, 3L, 2L).inOrder()
        assertThat(stored.value.pinnedCategoryIds).containsExactly(2L)
    }

    @Test fun `pin section edit does not activate manual provider sorting`() = runTest {
        stored.value = stored.value.copy(pinnedCategoryIds = setOf(1, 2))
        successfulSave(); open()
        model.move(CategoryOrderSection.PINNED, 2, 1)
        model.save(); advanceUntilIdle()
        assertThat(stored.value.pinnedIds).containsExactly(2L, 1L).inOrder()
        assertThat(stored.value.sortMode).isEqualTo(CategorySortMode.TITLE_ASC)
    }

    @Test fun `provider order resets active mode and retains manual positions`() = runTest {
        stored.value = stored.value.copy(sortMode = CategorySortMode.MANUAL)
        successfulSave(); open(); model.save(providerOrder = true); advanceUntilIdle()
        assertThat(stored.value.sortMode).isEqualTo(CategorySortMode.DEFAULT)
        assertThat(stored.value.normalIds).containsExactly(3L, 1L, 2L).inOrder()
    }

    @Test fun `undo and canceled pickup restore original without persisting`() = runTest {
        open()
        model.beginMove(CategoryOrderSection.PROVIDER)
        model.move(CategoryOrderSection.PROVIDER, 1, 1)
        model.cancelMove()
        assertThat(model.uiState.value.drafts.getValue(CategoryOrderSection.PROVIDER).hasChanges).isFalse()
        model.move(CategoryOrderSection.PROVIDER, 1, 1)
        model.undo(CategoryOrderSection.PROVIDER)
        model.save(); advanceUntilIdle()
        verify(preferences, never()).saveLiveCategoryOrder(any(), any(), any())
    }

    @Test fun `conflict keeps all drafts editable`() = runTest {
        whenever(preferences.saveLiveCategoryOrder(any(), any(), any())).thenReturn(SaveLiveCategoryOrderResult.Conflict(stored.value.copy(revision = 8)))
        open(); model.move(CategoryOrderSection.PROVIDER, 1, 1); model.save(); advanceUntilIdle()
        assertThat(model.uiState.value.closed).isFalse()
        assertThat(model.uiState.value.saving).isFalse()
        assertThat(model.uiState.value.drafts.getValue(CategoryOrderSection.PROVIDER).hasChanges).isTrue()
    }

    @Test fun `combined source category order and pins save by portable profile identity`() = runTest {
        val profile = CombinedM3uProfile(
            id = 31,
            identityKey = "portable-profile-31",
            name = "Combined",
            members = listOf(CombinedM3uProfileMember(profileId = 31, providerId = 7, priority = 0))
        )
        val news = Category(id = 101, name = "News", providerOrder = 0)
        val sports = Category(id = 102, name = "Sports", providerOrder = 1)
        val kids = Category(id = 103, name = "Kids", providerOrder = 2)
        val memberBinding = listOf(CombinedCategoryBinding(providerId = 7, providerName = "Provider", categoryId = 101))
        whenever(combined.getProfile(31)).thenReturn(profile)
        whenever(combined.getCombinedCategories(31)).thenReturn(flowOf(listOf(
            CombinedCategory(news, memberBinding),
            CombinedCategory(sports, listOf(memberBinding.single().copy(categoryId = 102))),
            CombinedCategory(kids, listOf(memberBinding.single().copy(categoryId = 103)))
        )))
        whenever(custom.invoke(eq(listOf(7L)), eq(ContentType.LIVE))).thenReturn(flowOf(listOf(
            Category(VirtualCategoryIds.FAVORITES, name = "Favorites", isVirtual = true)
        )))
        val combinedSaved = MutableStateFlow(LiveCategoryOrder(pinnedCategoryIds = setOf(102)))
        whenever(preferences.getCombinedLiveCategoryOrder("portable-profile-31")).thenReturn(combinedSaved)
        whenever(preferences.saveCombinedLiveCategoryOrder(any(), any(), any())).thenAnswer { call ->
            combinedSaved.value = call.getArgument<LiveCategoryOrder>(2).copy(revision = combinedSaved.value.revision + 1)
            SaveLiveCategoryOrderResult.Saved(combinedSaved.value)
        }

        model.openCombined(31, "Favorites", "Recent", "All")
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(model.uiState.value.showPersonalSection).isFalse()
        assertThat(model.uiState.value.drafts.getValue(CategoryOrderSection.PINNED).keys).containsExactly("102")
        model.move(CategoryOrderSection.PROVIDER, 101, 2)
        model.save(); dispatcher.scheduler.advanceUntilIdle()

        assertThat(combinedSaved.value.sortMode).isEqualTo(CategorySortMode.MANUAL)
        assertThat(combinedSaved.value.normalIds).containsExactly(103L, 102L, 101L).inOrder()
        assertThat(combinedSaved.value.pinnedIds).containsExactly(102L)
        assertThat(combinedSaved.value.pinnedCategoryIds).containsExactly(102L)
        assertThat(combinedSaved.value.personalIds).isEmpty()
        verify(preferences).saveCombinedLiveCategoryOrder(eq("portable-profile-31"), eq(0L), any())
        verify(preferences, never()).saveLiveCategoryOrder(any(), any(), any())
    }
}
