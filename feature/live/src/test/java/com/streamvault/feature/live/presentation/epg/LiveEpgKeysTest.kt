package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import org.junit.Test

class LiveEpgKeysTest {
    @Test
    fun `channel key includes stable channel and guide identity`() {
        val channel = Channel(
            id = 42L,
            name = "  News  ",
            epgChannelId = "xmltv.news",
            streamId = 7L
        )

        assertThat(liveEpgChannelKey(channel, index = 3))
            .isEqualTo("channel:42:7:xmltv.news:News:3")
    }

    @Test
    fun `channel key falls back to stream id when guide id is absent`() {
        val channel = Channel(id = 9L, name = "Sports", streamId = 11L)

        assertThat(liveEpgChannelKey(channel, index = 0))
            .isEqualTo("channel:9:11:11:Sports:0")
    }
}
