package com.streamvault.app.playback

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StreamInfo
import com.streamvault.feature.playback.api.CastMediaRequest
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AppPlaybackServiceAdaptersTest {

    @Test
    fun `preparer forwards stream info and returns plugin success unchanged`() = runTest {
        val streamInfo = StreamInfo(url = "https://example.test/input.m3u8")
        val prepared = StreamInfo(url = "https://example.test/prepared.m3u8")
        val operations = FakeStreamVaultPluginPlaybackOperations(Result.Success(prepared), "unused")
        val adapter = StreamVaultPluginPlaybackServiceAdapter(operations)

        val result = adapter.prepare(streamInfo)

        assertThat(operations.preparedStreamInfos).containsExactly(streamInfo)
        assertThat(result).isEqualTo(Result.Success(prepared))
    }

    @Test
    fun `preparer returns plugin error unchanged`() = runTest {
        val streamInfo = StreamInfo(url = "https://example.test/input.m3u8")
        val error = Result.Error("Plugin rejected playback")
        val operations = FakeStreamVaultPluginPlaybackOperations(error, "unused")
        val adapter = StreamVaultPluginPlaybackServiceAdapter(operations)

        val result = adapter.prepare(streamInfo)

        assertThat(operations.preparedStreamInfos).containsExactly(streamInfo)
        assertThat(result).isSameInstanceAs(error)
    }

    @Test
    fun `rewriter forwards cast request and returns plugin URL unchanged`() = runTest {
        val request = CastMediaRequest(
            url = "https://example.test/input.m3u8",
            title = "Input",
            headers = mapOf("Authorization" to "Bearer token")
        )
        val operations = FakeStreamVaultPluginPlaybackOperations(
            Result.Success(StreamInfo(url = "https://example.test/unused.m3u8")),
            "https://example.test/rewritten.m3u8"
        )
        val adapter = StreamVaultPluginPlaybackServiceAdapter(operations)

        val result = adapter.rewrite(request)

        assertThat(operations.castRequests).containsExactly(request)
        assertThat(result).isEqualTo("https://example.test/rewritten.m3u8")
    }

    @Test
    fun `surface refresh port forwards progress and both refreshes once`() = runTest {
        val history = PlaybackHistory(
            contentId = 7L,
            contentType = ContentType.MOVIE,
            providerId = 11L,
            title = "Movie",
            streamUrl = "https://example.test/movie.m3u8"
        )
        val watchNext = FakeWatchNextOperations()
        val launcher = FakeLauncherRecommendationsOperations()
        val adapter = AppPlaybackSurfaceRefreshAdapter(watchNext, launcher)

        adapter.updateWatchNextProgress(history)
        adapter.refreshWatchNext()
        adapter.refreshRecommendations()

        assertThat(watchNext.progressUpdates).containsExactly(history)
        assertThat(watchNext.refreshCalls).isEqualTo(1)
        assertThat(launcher.refreshCalls).isEqualTo(1)
    }

    private class FakeStreamVaultPluginPlaybackOperations(
        private val prepareResult: Result<StreamInfo>,
        private val rewriteResult: String?
    ) : StreamVaultPluginPlaybackOperations {
        val preparedStreamInfos = mutableListOf<StreamInfo>()
        val castRequests = mutableListOf<CastMediaRequest>()

        override suspend fun preparePlaybackStreamInfo(streamInfo: StreamInfo): Result<StreamInfo> {
            preparedStreamInfos += streamInfo
            return prepareResult
        }

        override suspend fun rewriteCastUrl(request: CastMediaRequest): String? {
            castRequests += request
            return rewriteResult
        }
    }

    private class FakeWatchNextOperations : WatchNextOperations {
        val progressUpdates = mutableListOf<PlaybackHistory>()
        var refreshCalls = 0

        override suspend fun updateWatchNextProgress(history: PlaybackHistory) {
            progressUpdates += history
        }

        override suspend fun refreshWatchNext() {
            refreshCalls++
        }
    }

    private class FakeLauncherRecommendationsOperations : LauncherRecommendationsOperations {
        var refreshCalls = 0

        override suspend fun refreshRecommendations() {
            refreshCalls++
        }
    }
}
