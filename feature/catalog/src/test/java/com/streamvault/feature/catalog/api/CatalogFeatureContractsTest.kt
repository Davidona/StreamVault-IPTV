package com.streamvault.feature.catalog.api

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Test

class CatalogFeatureContractsTest {
    @Test
    fun `cast request preserves feature owned presentation inputs`() {
        val streamInfo = StreamInfo(url = "https://example.test/movie.m3u8")
        val request = CatalogCastRequest(
            streamInfo = streamInfo,
            title = "Title",
            subtitle = "Subtitle",
            artworkUrl = "art",
            startPositionMs = 42_000L,
        )

        assertThat(request.streamInfo).isEqualTo(streamInfo)
        assertThat(request.title).isEqualTo("Title")
        assertThat(request.subtitle).isEqualTo("Subtitle")
        assertThat(request.artworkUrl).isEqualTo("art")
        assertThat(request.startPositionMs).isEqualTo(42_000L)
    }

    @Test
    fun `catalog ports accept app independent fakes`() {
        val platformHost = FakePlatformHost()
        val streamPreparer = FakeStreamPreparer()
        val downloadStarter = FakeDownloadStarter()
        val castPort = FakeCastPort()
        val updatePort = FakeUpdatePort()

        platformHost.openCastRouteChooser()
        downloadStarter.startDownload("download-1")

        assertThat(platformHost.openedChooser).isTrue()
        assertThat(downloadStarter.startedIds).containsExactly("download-1")
        assertThat(streamPreparer).isInstanceOf(CatalogStreamPreparer::class.java)
        assertThat(castPort).isInstanceOf(CatalogCastPort::class.java)
        assertThat(updatePort).isInstanceOf(CatalogAppUpdatePort::class.java)
    }

    @Test
    fun `catalog result and event models preserve exhaustive states`() {
        val started: CatalogCastStartResult = CatalogCastStartResult.Started
        val routeSelection: CatalogCastStartResult = CatalogCastStartResult.RouteSelectionRequired
        val unavailable: CatalogCastStartResult = CatalogCastStartResult.Unavailable
        val unsupported: CatalogCastStartResult =
            CatalogCastStartResult.Unsupported(CatalogMessage.CastUnsupported)
        val finished: CatalogCastPlaybackEvent =
            CatalogCastPlaybackEvent.Finished(true, CatalogMessage.CastStarted)
        val cancelled: CatalogCastPlaybackEvent = CatalogCastPlaybackEvent.RouteSelectionCancelled

        assertThat(started).isEqualTo(CatalogCastStartResult.Started)
        assertThat(routeSelection).isEqualTo(CatalogCastStartResult.RouteSelectionRequired)
        assertThat(unavailable).isEqualTo(CatalogCastStartResult.Unavailable)
        assertThat(unsupported).isEqualTo(
            CatalogCastStartResult.Unsupported(CatalogMessage.CastUnsupported)
        )
        assertThat(finished).isEqualTo(
            CatalogCastPlaybackEvent.Finished(true, CatalogMessage.CastStarted)
        )
        assertThat(cancelled).isEqualTo(CatalogCastPlaybackEvent.RouteSelectionCancelled)
        assertThat(CatalogUiEvent.OpenCastRouteChooser)
            .isEqualTo(CatalogUiEvent.OpenCastRouteChooser)
        assertThat(CatalogUiEvent.ShowMessage(CatalogMessage.DownloadStarted).message)
            .isEqualTo(CatalogMessage.DownloadStarted)
    }

    @Test
    fun `channel playback context carries typed return destination`() {
        val context = CatalogChannelPlaybackContext(
            categoryId = 7L,
            providerId = 11L,
            isVirtual = true,
            combinedProfileId = 13L,
            returnDestination = AppDestination.Home,
        )

        assertThat(context.categoryId).isEqualTo(7L)
        assertThat(context.providerId).isEqualTo(11L)
        assertThat(context.isVirtual).isTrue()
        assertThat(context.combinedProfileId).isEqualTo(13L)
        assertThat(context.returnDestination).isEqualTo(AppDestination.Home)
    }

    private class FakePlatformHost : CatalogPlatformHost {
        var openedChooser = false

        override fun openCastRouteChooser() {
            openedChooser = true
        }
    }

    private class FakeStreamPreparer : CatalogStreamPreparer {
        override suspend fun prepare(streamInfo: StreamInfo): Result<StreamInfo> =
            Result.Success(streamInfo)
    }

    private class FakeDownloadStarter : CatalogDownloadStarter {
        val startedIds = mutableListOf<String>()

        override fun startDownload(downloadId: String) {
            startedIds += downloadId
        }
    }

    private class FakeCastPort : CatalogCastPort {
        override val playbackEvents: Flow<CatalogCastPlaybackEvent> = MutableSharedFlow()

        override suspend fun startCasting(request: CatalogCastRequest): CatalogCastStartResult =
            CatalogCastStartResult.Started
    }

    private class FakeUpdatePort : CatalogAppUpdatePort {
        override val notice: Flow<CatalogUpdateNotice?> = MutableStateFlow(null)

        override suspend fun installDownloadedUpdate(expectedSha256: String?): Result<Unit> =
            Result.Success(Unit)
    }
}
