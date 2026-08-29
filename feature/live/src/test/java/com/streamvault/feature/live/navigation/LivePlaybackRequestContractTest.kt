package com.streamvault.feature.live.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.feature.live.api.LiveArchivePlaybackRequest
import com.streamvault.feature.live.api.LiveChannelPlaybackRequest
import org.junit.Test

class LivePlaybackRequestContractTest {

    @Test
    fun channelRequest_preservesEveryCompositionField() {
        val channel = Channel(
            id = 42L,
            name = "News",
            streamUrl = "https://example.test/news.m3u8",
            providerId = 7L,
        )

        val request = LiveChannelPlaybackRequest(
            channel = channel,
            categoryId = 12L,
            providerId = 7L,
            isVirtual = true,
            combinedProfileId = 34L,
            combinedSourceFilterProviderId = 56L,
            returnRoute = "epg?categoryId=12&anchorTime=1700000000000&favoritesOnly=true",
        )

        assertThat(request.channel).isSameInstanceAs(channel)
        assertThat(request.categoryId).isEqualTo(12L)
        assertThat(request.providerId).isEqualTo(7L)
        assertThat(request.isVirtual).isTrue()
        assertThat(request.combinedProfileId).isEqualTo(34L)
        assertThat(request.combinedSourceFilterProviderId).isEqualTo(56L)
        assertThat(request.returnRoute)
            .isEqualTo("epg?categoryId=12&anchorTime=1700000000000&favoritesOnly=true")
    }

    @Test
    fun archiveRequest_preservesChannelProgramAndReturnRoute() {
        val channel = Channel(
            id = 42L,
            name = "News",
            streamUrl = "https://example.test/news.m3u8",
            providerId = 7L,
        )
        val program = Program(
            id = 99L,
            channelId = "news",
            title = "The Briefing",
            startTime = 1_700_000_000_000L,
            endTime = 1_700_000_600_000L,
            providerId = 7L,
        )

        val request = LiveArchivePlaybackRequest(
            channel = channel,
            program = program,
            categoryId = 12L,
            isVirtual = true,
            combinedProfileId = 34L,
            returnRoute = "epg?categoryId=12&anchorTime=1700000000000&favoritesOnly=true",
        )

        assertThat(request.channel).isSameInstanceAs(channel)
        assertThat(request.program).isSameInstanceAs(program)
        assertThat(request.categoryId).isEqualTo(12L)
        assertThat(request.isVirtual).isTrue()
        assertThat(request.combinedProfileId).isEqualTo(34L)
        assertThat(request.returnRoute)
            .isEqualTo("epg?categoryId=12&anchorTime=1700000000000&favoritesOnly=true")
    }
}
