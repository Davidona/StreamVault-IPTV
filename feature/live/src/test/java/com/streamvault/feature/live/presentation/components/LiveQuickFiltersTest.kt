package com.streamvault.feature.live.presentation.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveQuickFiltersTest {
    @Test
    fun `chip keys keep all filter first and preserve saved filter order`() {
        assertThat(liveQuickFilterKeys(listOf("Sports", "News")))
            .containsExactly(LIVE_ALL_CATEGORY_FILTER_KEY, "Sports", "News")
            .inOrder()
    }

    @Test
    fun `selection uses all filter only when search is blank`() {
        assertThat(liveQuickFilterSelection(activeFilter = null, categorySearchQuery = ""))
            .isEqualTo(LIVE_ALL_CATEGORY_FILTER_KEY)
        assertThat(liveQuickFilterSelection(activeFilter = null, categorySearchQuery = "sports"))
            .isNull()
        assertThat(liveQuickFilterSelection(activeFilter = "Sports", categorySearchQuery = "sports"))
            .isEqualTo("Sports")
    }
}
