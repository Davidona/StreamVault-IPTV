package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import org.junit.Test

class LiveGuideLockPolicyTest {
    @Test
    fun `guide category is locked only when parental controls are active`() {
        val protectedCategory = Category(id = 4L, name = "Protected", isAdult = true)

        assertThat(isLiveGuideCategoryLocked(protectedCategory, parentalControlLevel = 1)).isTrue()
        assertThat(isLiveGuideCategoryLocked(protectedCategory, parentalControlLevel = 0)).isFalse()
    }

    @Test
    fun `guide channel is locked by its own protection or source category`() {
        val protectedCategory = Category(id = 4L, name = "Protected", isAdult = true)
        val channel = Channel(id = 9L, name = "Channel", categoryId = 4L)

        assertThat(
            isLiveGuideChannelLocked(
                channel = channel,
                categoriesById = mapOf(protectedCategory.id to protectedCategory),
                parentalControlLevel = 1
            )
        ).isTrue()
    }

    @Test
    fun `guide channel is unlocked when parental controls are inactive`() {
        val protectedChannel = Channel(id = 9L, name = "Channel", isUserProtected = true)

        assertThat(
            isLiveGuideChannelLocked(
                channel = protectedChannel,
                categoriesById = emptyMap(),
                parentalControlLevel = 0
            )
        ).isFalse()
    }

    @Test
    fun `guide channel is unlocked when it has no protection`() {
        val channel = Channel(id = 9L, name = "Channel")

        assertThat(
            isLiveGuideChannelLocked(
                channel = channel,
                categoriesById = emptyMap(),
                parentalControlLevel = 2
            )
        ).isFalse()
    }
}
