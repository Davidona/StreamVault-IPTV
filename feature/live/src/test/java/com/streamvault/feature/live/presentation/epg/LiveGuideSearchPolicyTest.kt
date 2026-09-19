package com.streamvault.feature.live.presentation.epg

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import org.junit.Test

class LiveGuideSearchPolicyTest {
    @Test
    fun `guide metadata search matches channel name or category case insensitively`() {
        val channel = Channel(id = 9L, name = "News One", categoryName = "World")

        assertThat(matchesLiveGuideMetadataSearch(channel, "news")).isTrue()
        assertThat(matchesLiveGuideMetadataSearch(channel, "WORLD")).isTrue()
        assertThat(matchesLiveGuideMetadataSearch(channel, "sports")).isFalse()
    }

    @Test
    fun `blank guide metadata search matches every channel`() {
        val channel = Channel(id = 9L, name = "News One")

        assertThat(matchesLiveGuideMetadataSearch(channel, "")).isTrue()
    }
}
