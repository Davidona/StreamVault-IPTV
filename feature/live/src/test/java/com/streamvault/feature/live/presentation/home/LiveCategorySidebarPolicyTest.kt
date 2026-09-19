package com.streamvault.feature.live.presentation.home

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LiveCategorySidebarPolicyTest {
    @Test
    fun `auto-hide is disabled by default`() {
        assertThat(shouldCollapseLiveCategorySidebar()).isFalse()
    }

    @Test
    fun `enabled auto-hide collapses for normal category selection`() {
        assertThat(
            shouldCollapseLiveCategorySidebar(
                autoHideCategories = true
            )
        ).isTrue()
    }

    @Test
    fun `locked and reorder selections do not collapse the sidebar`() {
        assertThat(
            shouldCollapseLiveCategorySidebar(
                autoHideCategories = true,
                isLocked = true
            )
        ).isFalse()
        assertThat(
            shouldCollapseLiveCategorySidebar(
                autoHideCategories = true,
                isReorderMode = true
            )
        ).isFalse()
    }
}
