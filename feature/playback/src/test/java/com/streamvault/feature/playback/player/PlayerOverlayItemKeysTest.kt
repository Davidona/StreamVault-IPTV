package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import org.junit.Test

class PlayerOverlayItemKeysTest {

    @Test
    fun `channel and category keys stay stable when display fields change`() {
        val channel = Channel(id = 42L, name = "News", streamId = 7L)
        val updatedChannel = channel.copy(name = "News HD")
        val category = Category(id = 3L, roomId = 9L, name = "Favorites")
        val updatedCategory = category.copy(name = "Favorites renamed")

        assertThat(playerChannelOverlayItemKey(channel))
            .isEqualTo(playerChannelOverlayItemKey(updatedChannel))
        assertThat(playerCategoryOverlayItemKey(category))
            .isEqualTo(playerCategoryOverlayItemKey(updatedCategory))
    }

    @Test
    fun `program keys distinguish adjacent programs on the same channel`() {
        val first = Program(
            id = 0L,
            channelId = "news",
            title = "First",
            startTime = 1_000L,
            endTime = 2_000L
        )
        val second = first.copy(
            title = "Second",
            startTime = 2_000L,
            endTime = 3_000L
        )

        assertThat(playerProgramOverlayItemKey(first))
            .isNotEqualTo(playerProgramOverlayItemKey(second))
    }
}
