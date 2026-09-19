package com.streamvault.feature.live.presentation.home

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import org.junit.Test

class LiveHomeLockPolicyTest {
    @Test
    fun `adult category is locked until its normalized id is unlocked`() {
        val category = Category(id = -7L, name = "Adult", isAdult = true)

        assertThat(isLiveHomeCategoryLocked(category, 1, emptySet())).isTrue()
        assertThat(isLiveHomeCategoryLocked(category, 1, setOf(7L))).isFalse()
        assertThat(isLiveHomeCategoryLocked(category, 0, emptySet())).isFalse()
    }

    @Test
    fun `channel uses selected or source category protection`() {
        val sourceCategory = Category(id = 4L, name = "Source", isUserProtected = true)
        val selectedCategory = Category(id = 5L, name = "Selected")
        val channel = Channel(id = 9L, name = "Channel", categoryId = 4L)

        assertThat(
            isLiveHomeChannelLocked(
                channel = channel,
                categories = listOf(sourceCategory, selectedCategory),
                selectedCategory = selectedCategory,
                parentalControlLevel = 2,
                unlockedCategoryIds = emptySet()
            )
        ).isTrue()
        assertThat(
            isLiveHomeChannelLocked(
                channel = channel,
                categories = listOf(sourceCategory, selectedCategory),
                selectedCategory = selectedCategory,
                parentalControlLevel = 2,
                unlockedCategoryIds = setOf(4L)
            )
        ).isFalse()
    }
}
