package com.streamvault.feature.playback.multiview

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import org.junit.Test

class MultiViewManagerTest {

    @Test
    fun setChannel_replacesSlotAndRemovesTheChannelFromItsPreviousSlot() {
        val manager = MultiViewManager()
        val news = channel(1, "News")
        val sport = channel(2, "Sport")

        manager.setChannel(0, news)
        manager.setChannel(1, sport)
        manager.setChannel(3, news)

        assertThat(manager.slots.value).containsExactly(null, sport, null, news).inOrder()
    }

    @Test
    fun slots_alwaysExposeTheFixedMaximumIncludingEmptySlots() {
        val manager = MultiViewManager()

        assertThat(manager.slots.value).hasSize(MultiViewManager.MAX_SLOTS)
        assertThat(manager.slots.value).containsExactly(null, null, null, null).inOrder()

        manager.setChannel(MultiViewManager.MAX_SLOTS, channel(9, "Ignored"))
        manager.clearSlot(-1)

        assertThat(manager.slots.value).containsExactly(null, null, null, null).inOrder()
    }

    @Test
    fun setSlots_requiresTheMaximumAndKeepsEmptySlotPositions() {
        val manager = MultiViewManager()
        val news = channel(1, "News")

        manager.setSlots(listOf(news, null, null, null))

        assertThat(manager.isQueued(news.id)).isTrue()
        assertThat(manager.slots.value).containsExactly(news, null, null, null).inOrder()
    }

    @Test
    fun setChannel_usesChannelIdentityRatherThanItsDisplayName() {
        val manager = MultiViewManager()
        val original = channel(3, "News")
        val renamed = channel(3, "News HD")

        manager.setChannel(0, original)
        manager.setChannel(2, renamed)

        assertThat(manager.slots.value).containsExactly(null, null, renamed, null).inOrder()
    }

    private fun channel(id: Long, name: String) = Channel(
        id = id,
        name = name,
        streamUrl = "https://example.test/$id.m3u8"
    )
}
