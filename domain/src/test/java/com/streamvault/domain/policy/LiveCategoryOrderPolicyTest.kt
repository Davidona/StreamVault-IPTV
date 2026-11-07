package com.streamvault.domain.policy

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import com.streamvault.domain.repository.ChannelRepository
import org.junit.Test

class LiveCategoryOrderPolicyTest {
    private val provider = listOf(Category(1, name = "News"), Category(2, name = "Sports"), Category(3, name = "Kids"))
    private val personal = listOf(Category(VirtualCategoryIds.FAVORITES, name = "Favorites", isVirtual = true),
        Category(VirtualCategoryIds.RECENT, name = "Recent", isVirtual = true), Category(-20, name = "Mine", isVirtual = true),
        Category(ChannelRepository.ALL_CHANNELS_ID, name = "All"))

    @Test fun `pinning preserves normal position for unpinning`() {
        val order = LiveCategoryOrder(CategorySortMode.MANUAL, normalIds = listOf(3, 1, 2))
        assertThat(applyLiveCategorySections(provider, setOf(1), order).map { it.id }).containsExactly(1L, 3L, 2L).inOrder()
        assertThat(applyLiveCategorySections(provider, emptySet(), order).map { it.id }).containsExactly(3L, 1L, 2L).inOrder()
    }

    @Test fun `personal and pinned sequences are independent of provider sequence`() {
        val order = LiveCategoryOrder(CategorySortMode.MANUAL, normalIds = listOf(3, 2, 1), pinnedIds = listOf(1, 2),
            personalIds = listOf(ChannelRepository.ALL_CHANNELS_ID, -20, VirtualCategoryIds.FAVORITES))
        assertThat(applyLiveCategorySections(personal + provider, setOf(1, 2), order).map { it.id }).containsExactly(
            ChannelRepository.ALL_CHANNELS_ID, -20L, VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT, 1L, 2L, 3L).inOrder()
    }

    @Test fun `hidden entries retain rank when restored`() {
        val order = LiveCategoryOrder(CategorySortMode.MANUAL, normalIds = listOf(3, 1, 2))
        val hidden = applyProviderCategoryDisplayPreferences(provider, setOf(1), order.sortMode, order.normalIds)
        assertThat(applyLiveCategorySections(hidden, emptySet(), order).map { it.id }).containsExactly(3L, 2L).inOrder()
        assertThat(applyLiveCategorySections(provider, emptySet(), order).map { it.id }).containsExactly(3L, 1L, 2L).inOrder()
    }

    @Test fun `new provider entries append in source order after saved members`() {
        val items = provider + Category(4, name = "New") + Category(5, name = "Another")
        val order = LiveCategoryOrder(CategorySortMode.MANUAL, normalIds = listOf(2, 999, 1))
        assertThat(applyLiveCategorySections(items, emptySet(), order).map { it.id }).containsExactly(2L, 1L, 3L, 4L, 5L).inOrder()
    }

    @Test fun `predefined mode takes precedence and saved manual order remains available`() {
        val manual = LiveCategoryOrder(CategorySortMode.MANUAL, normalIds = listOf(2, 1, 3))
        assertThat(applyLiveCategorySections(provider, emptySet(), manual.copy(sortMode = CategorySortMode.TITLE_ASC)).map { it.id })
            .containsExactly(3L, 1L, 2L).inOrder()
        assertThat(applyLiveCategorySections(provider, emptySet(), manual).map { it.id }).containsExactly(2L, 1L, 3L).inOrder()
    }

    @Test fun `editing normal section preserves pinned and hidden slots`() {
        assertThat(mergeLiveCategoryPositions(listOf(1, 2, 3, 4), listOf(4, 1, 3)))
            .containsExactly(4L, 2L, 1L, 3L).inOrder()
    }

    @Test fun `sorting does not change protection or other category metadata`() {
        val protected = provider[0].copy(isAdult = true, isUserProtected = true)
        assertThat(applyLiveCategorySections(listOf(protected, provider[1]), emptySet(), LiveCategoryOrder(normalIds = listOf(2, 1), sortMode = CategorySortMode.MANUAL)).last())
            .isEqualTo(protected)
    }
}
