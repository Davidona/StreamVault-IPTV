package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import org.junit.Test

class PlayerPlaylistContextTest {
    private val unprepared = context(-1, virtual = false, provider = -1)

    @Test fun `first custom group loads on player entry despite category minus one`() {
        assertThat(shouldReloadPlayerPlaylist(unprepared, context(-1, virtual = true))).isTrue()
    }

    @Test fun `entering first custom group replaces provider category playlist`() {
        assertThat(shouldReloadPlayerPlaylist(context(42), context(-1, virtual = true))).isTrue()
    }

    @Test fun `same category number with different virtual identity reloads playlist`() {
        assertThat(shouldReloadPlayerPlaylist(context(-1), context(-1, virtual = true))).isTrue()
    }

    @Test fun `same context does not reload when tuning another member channel`() {
        val group = context(-1, virtual = true)
        assertThat(shouldReloadPlayerPlaylist(group, group)).isFalse()
    }

    @Test fun `switching custom groups reloads membership`() {
        assertThat(shouldReloadPlayerPlaylist(context(-1, virtual = true), context(-2, virtual = true))).isTrue()
    }

    @Test fun `absent category is still ignored for non virtual playback`() {
        assertThat(shouldReloadPlayerPlaylist(context(42), context(-1))).isFalse()
    }

    @Test fun `provider favorites recent all channels and combined filters still reload`() {
        listOf(context(42), context(VirtualCategoryIds.FAVORITES, true),
            context(VirtualCategoryIds.RECENT, true), context(ChannelRepository.ALL_CHANNELS_ID)).forEach {
            assertThat(shouldReloadPlayerPlaylist(unprepared, it)).isTrue()
        }
        val group = context(-1, virtual = true)
        assertThat(shouldReloadPlayerPlaylist(group, group.copy(providerId = 2))).isTrue()
        assertThat(shouldReloadPlayerPlaylist(group, group.copy(combinedProfileId = 1))).isTrue()
        assertThat(shouldReloadPlayerPlaylist(group, group.copy(combinedSourceFilterProviderId = 2))).isTrue()
    }

    private fun context(id: Long, virtual: Boolean = false, provider: Long = 1) =
        PlayerPlaylistContext(id, provider, virtual, null, null)
}
