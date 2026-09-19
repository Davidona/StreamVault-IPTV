package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppHomeDashboardShelfTest {

    @Test
    fun `defaultOrder matches the existing home layout`() {
        assertThat(AppHomeDashboardShelf.defaultOrder).containsExactly(
            AppHomeDashboardShelf.FAVORITE_CHANNELS,
            AppHomeDashboardShelf.RECENT_CHANNELS,
            AppHomeDashboardShelf.LIVE_SHORTCUTS,
            AppHomeDashboardShelf.CONTINUE_WATCHING,
            AppHomeDashboardShelf.PINNED_CATEGORIES,
            AppHomeDashboardShelf.RECENT_MOVIES,
            AppHomeDashboardShelf.RECENT_SERIES
        ).inOrder()
    }

    @Test
    fun `normalizeForStorage removes duplicates and preserves order`() {
        val normalized = AppHomeDashboardShelf.normalizeForStorage(
            listOf(
                AppHomeDashboardShelf.RECENT_MOVIES,
                AppHomeDashboardShelf.FAVORITE_CHANNELS,
                AppHomeDashboardShelf.RECENT_MOVIES,
                AppHomeDashboardShelf.TOP_RATED_MOVIES
            )
        )

        assertThat(normalized).containsExactly(
            AppHomeDashboardShelf.RECENT_MOVIES,
            AppHomeDashboardShelf.FAVORITE_CHANNELS,
            AppHomeDashboardShelf.TOP_RATED_MOVIES
        ).inOrder()
    }

    @Test
    fun `displayOrder keeps enabled shelves first and appends hidden shelves`() {
        val ordered = AppHomeDashboardShelf.displayOrder(
            listOf(
                AppHomeDashboardShelf.RECOMMENDED_MOVIES,
                AppHomeDashboardShelf.FAVORITE_CHANNELS
            )
        )

        assertThat(ordered.take(2)).containsExactly(
            AppHomeDashboardShelf.RECOMMENDED_MOVIES,
            AppHomeDashboardShelf.FAVORITE_CHANNELS
        ).inOrder()
        assertThat(ordered).containsAtLeastElementsIn(AppHomeDashboardShelf.catalogOrder)
        assertThat(ordered).hasSize(AppHomeDashboardShelf.catalogOrder.size)
    }
}
