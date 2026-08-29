package com.streamvault.app.live

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.core.navigation.AppDestination
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.feature.live.api.LiveArchivePlaybackRequest
import com.streamvault.feature.live.api.LiveChannelPlaybackRequest
import org.junit.Test

class AppLivePlaybackRequestMapperTest {

    @Test
    fun channelRequest_mapsToLivePlayerPayloadWithoutDroppingMetadata() {
        val channel = Channel(
            id = 42L,
            name = "News",
            streamUrl = "https://example.test/news.m3u8",
            epgChannelId = "news-epg",
            providerId = 7L,
        )
        val returnDestination = AppDestination.Guide(12L, 1_700_000_000_000L, true)

        val request = LiveChannelPlaybackRequest(
            channel = channel,
            categoryId = null,
            providerId = 7L,
            isVirtual = true,
            combinedProfileId = 34L,
            combinedSourceFilterProviderId = 56L,
            returnRoute = AppRouteCodec.encode(returnDestination),
        ).toAppPlayerNavigationRequest()

        assertThat(request.streamUrl).isEqualTo(channel.streamUrl)
        assertThat(request.title).isEqualTo(channel.name)
        assertThat(request.channelId).isEqualTo(channel.epgChannelId)
        assertThat(request.internalId).isEqualTo(channel.id)
        assertThat(request.categoryId).isEqualTo(ChannelRepository.ALL_CHANNELS_ID)
        assertThat(request.providerId).isEqualTo(channel.providerId)
        assertThat(request.isVirtual).isTrue()
        assertThat(request.combinedProfileId).isEqualTo(34L)
        assertThat(request.combinedSourceFilterProviderId).isEqualTo(56L)
        assertThat(request.contentType).isEqualTo("LIVE")
        assertThat(request.returnDestination).isEqualTo(returnDestination)
    }

    @Test
    fun archiveRequest_mapsArchiveWindowAndGuideReturnDestination() {
        val channel = Channel(
            id = 42L,
            name = "News",
            streamUrl = "https://example.test/news.m3u8",
            epgChannelId = "news-epg",
            providerId = 7L,
            catchUpSupported = true,
            catchUpDays = 2,
            catchUpSource = "https://example.test/archive/{start}/{end}",
        )
        val program = Program(
            id = 99L,
            channelId = "news-epg",
            title = "The Briefing",
            startTime = 1_700_000_000_000L,
            endTime = 1_700_000_600_000L,
            hasArchive = true,
            providerId = 7L,
        )
        val returnDestination = AppDestination.Guide(12L, 1_700_000_000_000L, true)

        val request = LiveArchivePlaybackRequest(
            channel = channel,
            program = program,
            categoryId = 12L,
            isVirtual = false,
            combinedProfileId = 34L,
            returnRoute = AppRouteCodec.encode(returnDestination),
        ).toAppArchivePlayerNavigationRequest(now = program.endTime + 1L)

        assertThat(request).isNotNull()
        assertThat(request!!.streamUrl).isEqualTo(channel.streamUrl)
        assertThat(request.title).isEqualTo(channel.name)
        assertThat(request.channelId).isEqualTo(channel.epgChannelId)
        assertThat(request.internalId).isEqualTo(channel.id)
        assertThat(request.categoryId).isEqualTo(12L)
        assertThat(request.providerId).isEqualTo(channel.providerId)
        assertThat(request.isVirtual).isFalse()
        assertThat(request.combinedProfileId).isEqualTo(34L)
        assertThat(request.contentType).isEqualTo("LIVE")
        assertThat(request.archiveStartMs).isEqualTo(program.startTime)
        assertThat(request.archiveEndMs).isEqualTo(program.endTime)
        assertThat(request.archiveTitle).isEqualTo("${channel.name}: ${program.title}")
        assertThat(request.returnDestination).isEqualTo(returnDestination)
    }

    @Test
    fun archiveRequest_rejectsUnplayableProgramAtMapperBoundary() {
        val channel = Channel(
            id = 42L,
            name = "News",
            streamUrl = "https://example.test/news.m3u8",
            providerId = 7L,
        )
        val program = Program(
            id = 99L,
            channelId = "news-epg",
            title = "The Briefing",
            startTime = 1_700_000_000_000L,
            endTime = 1_700_000_600_000L,
            providerId = 7L,
        )

        val request = LiveArchivePlaybackRequest(
            channel = channel,
            program = program,
            categoryId = 12L,
            isVirtual = false,
            combinedProfileId = null,
            returnRoute = null,
        ).toAppArchivePlayerNavigationRequest(now = program.endTime + 1L)

        assertThat(request).isNull()
    }
}
