package com.streamvault.app.catalog

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.update.AppUpdateActionState
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.feature.catalog.api.CatalogCastPlaybackEvent
import com.streamvault.feature.catalog.api.CatalogCastRequest
import com.streamvault.feature.catalog.api.CatalogCastStartResult
import com.streamvault.feature.catalog.api.CatalogMessage
import com.streamvault.feature.catalog.api.CatalogUpdateAction
import com.streamvault.feature.playback.api.CastMediaRequest
import com.streamvault.feature.playback.cast.CastMediaRequestBuildResult
import com.streamvault.feature.playback.cast.CastPlaybackEvent
import com.streamvault.feature.playback.cast.CastStartResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

class AppCatalogAdaptersTest {
    @Test
    fun `stream preparer delegates without rewriting result`() = runTest {
        val input = StreamInfo(url = "https://example.test/input.m3u8")
        val output = StreamInfo(url = "https://example.test/prepared.m3u8")
        val calls = mutableListOf<StreamInfo>()
        val adapter = AppCatalogStreamPreparer { streamInfo ->
            calls += streamInfo
            Result.Success(output)
        }

        assertThat(adapter.prepare(input)).isEqualTo(Result.Success(output))
        assertThat(calls).containsExactly(input)
    }

    @Test
    fun `download starter forwards string id to injected service launcher`() {
        val calls = mutableListOf<String>()
        val adapter = AppCatalogDownloadStarter { downloadId ->
            calls += downloadId
        }

        adapter.startDownload("download-42")

        assertThat(calls).containsExactly("download-42")
    }

    @Test
    fun `cast adapter preserves request fields and maps start results`() = runTest {
        val input = CatalogCastRequest(
            streamInfo = StreamInfo(url = "https://example.test/movie.m3u8"),
            title = "Movie",
            subtitle = "2026",
            artworkUrl = "https://example.test/poster.jpg",
            startPositionMs = 42_000L,
        )
        val operations = FakeCastOperations(CastStartResult.STARTED)
        val adapter = AppCatalogCastPort(operations)

        assertThat(adapter.startCasting(input)).isEqualTo(CatalogCastStartResult.Started)
        assertThat(operations.request).isEqualTo(
            CastMediaRequest(
                url = input.streamInfo.url,
                title = input.title,
                subtitle = input.subtitle,
                artworkUrl = input.artworkUrl,
                startPositionMs = input.startPositionMs,
            )
        )
    }

    @Test
    fun `cast adapter maps every start result`() = runTest {
        val input = CatalogCastRequest(StreamInfo("https://example.test/movie.m3u8"), "Movie", null, null, 0L)
        val expected = listOf(
            CastStartResult.STARTED to CatalogCastStartResult.Started,
            CastStartResult.ROUTE_SELECTION_REQUIRED to CatalogCastStartResult.RouteSelectionRequired,
            CastStartResult.UNAVAILABLE to CatalogCastStartResult.Unavailable,
            CastStartResult.UNSUPPORTED to CatalogCastStartResult.Unsupported(CatalogMessage.CastUnsupported),
        )

        expected.forEach { (playbackResult, catalogResult) ->
            assertThat(AppCatalogCastPort(FakeCastOperations(playbackResult)).startCasting(input))
                .isEqualTo(catalogResult)
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `cast adapter maps ordered lifecycle events without playback types`() = runTest {
        val playbackEvents = MutableSharedFlow<CastPlaybackEvent>(extraBufferCapacity = 8)
        val adapter = AppCatalogCastPort(FakeCastOperations(CastStartResult.STARTED, playbackEvents))
        val collected = mutableListOf<CatalogCastPlaybackEvent>()
        val job = backgroundScope.launch {
            adapter.playbackEvents.collect { collected += it }
        }
        runCurrent()

        playbackEvents.emit(CastPlaybackEvent.RouteSelectionCancelled)
        playbackEvents.emit(CastPlaybackEvent.MediaLoadSucceeded("Movie"))
        playbackEvents.emit(CastPlaybackEvent.MediaLoadFailed("Movie"))
        playbackEvents.emit(CastPlaybackEvent.SessionStartFailed(7))
        playbackEvents.emit(CastPlaybackEvent.ReceiverUnavailable("Movie"))
        runCurrent()

        assertThat(collected).containsExactly(
            CatalogCastPlaybackEvent.RouteSelectionCancelled,
            CatalogCastPlaybackEvent.Finished(true, CatalogMessage.CastStarted),
            CatalogCastPlaybackEvent.Finished(false, CatalogMessage.CastLoadFailed),
            CatalogCastPlaybackEvent.Finished(false, CatalogMessage.CastSessionFailed),
            CatalogCastPlaybackEvent.Finished(false, CatalogMessage.CastUnavailable),
        ).inOrder()
        job.cancel()
    }

    @Test
    fun `update action mapping preserves all existing states`() {
        assertThat(AppCatalogUpdatePort.mapAction(AppUpdateActionState.None))
            .isEqualTo(CatalogUpdateAction.None)
        assertThat(AppCatalogUpdatePort.mapAction(AppUpdateActionState.DownloadLatest))
            .isEqualTo(CatalogUpdateAction.DownloadLatest)
        assertThat(AppCatalogUpdatePort.mapAction(AppUpdateActionState.Downloading))
            .isEqualTo(CatalogUpdateAction.Downloading)
        assertThat(AppCatalogUpdatePort.mapAction(AppUpdateActionState.InstallLatest))
            .isEqualTo(CatalogUpdateAction.InstallLatest)
        assertThat(AppCatalogUpdatePort.mapAction(AppUpdateActionState.InstallPermissionRequired))
            .isEqualTo(CatalogUpdateAction.InstallPermissionRequired)
    }

    @Test
    fun `update adapter forwards install sha unchanged`() = runTest {
        var receivedSha: String? = null
        val adapter = AppCatalogUpdatePort(
            notice = MutableStateFlow(null),
            install = { sha ->
                receivedSha = sha
                Result.Success(Unit)
            },
        )

        assertThat(adapter.installDownloadedUpdate("sha-256")).isEqualTo(Result.Success(Unit))
        assertThat(receivedSha).isEqualTo("sha-256")
    }

    private class FakeCastOperations(
        private val result: CastStartResult,
        override val playbackEvents: Flow<CastPlaybackEvent> = MutableSharedFlow(extraBufferCapacity = 8),
    ) : AppCatalogCastOperations {
        var request: CastMediaRequest? = null

        override fun buildRequest(
            streamInfo: StreamInfo,
            title: String,
            subtitle: String?,
            artworkUrl: String?,
            startPositionMs: Long,
        ): CastMediaRequestBuildResult {
            request = CastMediaRequest(
                url = streamInfo.url,
                title = title,
                subtitle = subtitle,
                artworkUrl = artworkUrl,
                startPositionMs = startPositionMs,
            )
            return CastMediaRequestBuildResult.Success(request!!)
        }

        override suspend fun startCasting(request: CastMediaRequest): CastStartResult = result
    }
}
