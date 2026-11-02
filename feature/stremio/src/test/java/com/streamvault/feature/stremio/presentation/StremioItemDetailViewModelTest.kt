package com.streamvault.feature.stremio.presentation

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioAddon
import com.streamvault.domain.model.stremio.StremioAddonSource
import com.streamvault.domain.model.stremio.StremioAttributedResource
import com.streamvault.domain.model.stremio.StremioCatalogPage
import com.streamvault.domain.model.stremio.StremioCatalogQuery
import com.streamvault.domain.model.stremio.StremioCatalogRef
import com.streamvault.domain.model.stremio.StremioManifest
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaDetail
import com.streamvault.domain.model.stremio.StremioMetaResolution
import com.streamvault.domain.model.stremio.StremioResourceResolution
import com.streamvault.domain.model.stremio.StremioStream
import com.streamvault.domain.model.stremio.StremioSubtitle
import com.streamvault.domain.model.stremio.StremioVideo
import com.streamvault.domain.model.stremio.installFingerprint
import com.streamvault.domain.repository.DownloadManager
import com.streamvault.domain.repository.StremioAddonRepository
import com.streamvault.feature.stremio.R
import com.streamvault.feature.stremio.api.StremioDownloadStarter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.times

@OptIn(ExperimentalCoroutinesApi::class)
class StremioItemDetailViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private data class StreamCall(val exactId: String, val addonIds: Set<Long>?)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun seasonWatchedSavesOneBatchAndExcludesFutureEpisodes() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L)).apply {
            meta = StremioMetaDetail("series", StremioMediaType.SERIES, "Series", videos = listOf(
                StremioVideo("one", season=1, episode=1),
                StremioVideo("two", season=1, episode=2, released="2999-01-01T00:00:00Z"),
                StremioVideo("other", season=2, episode=1)))
        }
        val histories = mock<com.streamvault.domain.repository.StremioPlaybackHistoryRepository>()
        whenever(histories.observeHistory()).thenReturn(MutableStateFlow(emptyList()))
        whenever(histories.saveAll(any())).thenReturn(Result.success(Unit))
        val vm = viewModel(repository,"series","series",playbackHistoryRepository=histories)
        advanceUntilIdle()
        vm.setSeasonWatched(1,true); advanceUntilIdle()
        val saved = argumentCaptor<List<com.streamvault.domain.model.stremio.StremioPlaybackHistory>>()
        verify(histories).saveAll(saved.capture())
        assertThat(saved.firstValue.map { it.exactVideoId }).containsExactly("one")
        assertThat(saved.firstValue.single().watched).isTrue()
        vm.setSeasonWatched(1,false); advanceUntilIdle()
        verify(histories,times(2)).saveAll(saved.capture())
        assertThat(saved.lastValue.map { it.exactVideoId }).containsExactly("one","two")
        assertThat(saved.lastValue.all { !it.watched && it.lastWatchedAt == 0L }).isTrue()
    }

    @Test fun unplayedItemCanBeMarkedWatchedAndAutomaticHistoryUpdatesTheButton() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L))
        val historyRepository = mock<com.streamvault.domain.repository.StremioPlaybackHistoryRepository>()
        val records = MutableStateFlow<List<com.streamvault.domain.model.stremio.StremioPlaybackHistory>>(emptyList())
        whenever(historyRepository.observeHistory()).thenReturn(records)
        whenever(historyRepository.save(any())).thenReturn(Result.success(Unit))
        val vm = viewModel(repository,"movie","tt123",playbackHistoryRepository=historyRepository)
        advanceUntilIdle()
        vm.toggleWatched();advanceUntilIdle()
        val saved=argumentCaptor<com.streamvault.domain.model.stremio.StremioPlaybackHistory>()
        verify(historyRepository).save(saved.capture())
        assertThat(saved.firstValue.watched).isTrue()
        assertThat(saved.firstValue.resumePositionMs).isEqualTo(0L)
        records.value=listOf(saved.firstValue.copy(watched=false));advanceUntilIdle()
        assertThat(vm.state.value.watchedKeys).doesNotContain(com.streamvault.domain.model.stremio.stremioWatchedKey(StremioMediaType.MOVIE,"tt123"))
        records.value=listOf(saved.firstValue);advanceUntilIdle()
        assertThat(vm.state.value.watchedKeys).contains(com.streamvault.domain.model.stremio.stremioWatchedKey(StremioMediaType.MOVIE,"tt123"))
    }

    @Test
    fun explicitWatchedActionsUpdateResumeHistoryAndUnwatchResetsProgress() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L))
        val historyRepository = mock<com.streamvault.domain.repository.StremioPlaybackHistoryRepository>()
        val history = com.streamvault.domain.model.stremio.StremioPlaybackHistory(
            contentKey = "stremio:global:movie:tt123", mediaType = "movie", exactVideoId = "tt123",
            title = "Movie", resumePositionMs = 5000, totalDurationMs = 10000,
            catalogOriginKey = addon(11L).installFingerprint()
        )
        whenever(historyRepository.observeHistory()).thenReturn(kotlinx.coroutines.flow.flowOf(emptyList()))
        whenever(historyRepository.get(any())).thenReturn(history)
        whenever(historyRepository.save(any())).thenReturn(Result.success(Unit))
        val viewModel = viewModel(repository, "movie", "tt123", playbackHistoryRepository = historyRepository)
        advanceUntilIdle()
        viewModel.toggleWatched()
        advanceUntilIdle()
        viewModel.toggleWatched()
        advanceUntilIdle()
        val saved = argumentCaptor<com.streamvault.domain.model.stremio.StremioPlaybackHistory>()
        verify(historyRepository, times(2)).save(saved.capture())
        assertThat(saved.allValues[0].watched).isTrue()
        assertThat(saved.allValues[1].watched).isFalse()
        assertThat(saved.allValues[1].resumePositionMs).isEqualTo(0)
    }

    @Test
    fun channelFeedsSelectRealVideoIdsAndKeepInlineStreamProvenance() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L)).apply {
            meta = StremioMetaDetail("channel:1", StremioMediaType.CHANNEL, "Channel",
                videos = listOf(StremioVideo("upload:one", title = "First upload",
                    streams = listOf(StremioStream(url = "https://media.example/upload.mp4")))))
        }
        val viewModel = viewModel(repository, "channel", "channel:1")
        advanceUntilIdle()
        viewModel.loadStreams("upload:one")
        advanceUntilIdle()
        assertThat(viewModel.state.value.selectedVideo?.id).isEqualTo("upload:one")
        assertThat(viewModel.state.value.streams.single().source.addonId).isEqualTo(31L)
        assertThat(repository.streamCalls).isEmpty()
        viewModel.loadStreams("invented")
        assertThat(viewModel.state.value.requestedVideoId).isEqualTo("upload:one")
    }

    @Test
    fun libraryAndWatchedActionsPersistIndependently() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L))
        val viewModel = viewModel(repository, "movie", "tt123")
        advanceUntilIdle()
        viewModel.toggleLibrary()
        advanceUntilIdle()
        assertThat(viewModel.state.value.inLibrary).isTrue()
        viewModel.toggleWatched()
        advanceUntilIdle()
        assertThat(viewModel.state.value.watchedKeys).contains(
            com.streamvault.domain.model.stremio.stremioWatchedKey(StremioMediaType.MOVIE, "tt123"))
        viewModel.toggleLibrary()
        advanceUntilIdle()
        assertThat(viewModel.state.value.inLibrary).isFalse()
        assertThat(viewModel.state.value.watchedKeys).isNotEmpty()
        assertThat(viewModel.state.value.libraryBusy).isFalse()
    }

    @Test
    fun `default video loads embedded streams with metadata provenance without querying stream addons`() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L)).apply {
            meta = StremioMetaDetail(
                id = "movie:1", type = StremioMediaType.MOVIE, name = "Movie",
                defaultVideoId = "video:1",
                videos = listOf(StremioVideo("video:1", streams = listOf(
                    StremioStream(url = "https://media.example/embedded.mp4")
                )))
            )
        }
        val viewModel = viewModel(repository, "movie", "movie:1")
        advanceUntilIdle()
        assertThat(repository.streamCalls).isEmpty()
        assertThat(viewModel.state.value.requestedVideoId).isEqualTo("video:1")
        assertThat(viewModel.state.value.streams.single().source.addonId).isEqualTo(31L)
        assertThat(viewModel.state.value.streamsLoading).isFalse()
    }

    @Test
    fun `explicit empty embedded streams do not fall back to unrelated stream addons`() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L)).apply {
            meta = StremioMetaDetail(
                id = "series:1", type = StremioMediaType.SERIES, name = "Series",
                videos = listOf(StremioVideo("episode:1", streams = emptyList()))
            )
        }
        val viewModel = viewModel(repository, "series", "series:1")
        advanceUntilIdle()
        viewModel.loadStreams("episode:1")
        advanceUntilIdle()
        assertThat(repository.streamCalls).isEmpty()
        assertThat(viewModel.state.value.streams).isEmpty()
        assertThat(viewModel.state.value.streamCandidateCount).isEqualTo(1)
        assertThat(viewModel.state.value.streamsLoading).isFalse()
    }

    @Test
    fun `episode stream lookup uses exact video id and preserves catalog provenance`() = runTest(dispatcher) {
        val exactVideoId = "series:custom-id:season 02/episode 04"
        val addon = addon(11L)
        val repository = ItemRepositoryFake(addon).apply {
            meta = StremioMetaDetail(
                id = "series:custom-id",
                type = StremioMediaType.SERIES,
                name = "Series",
                videos = listOf(StremioVideo(exactVideoId, title = "Episode 4", season = 2, episode = 4))
            )
        }
        val viewModel = viewModel(repository, "series", "series:custom-id")
        advanceUntilIdle()
        val episode = viewModel.state.value.videos.single()

        viewModel.loadStreams(episode.id)
        advanceUntilIdle()

        assertThat(repository.streamCalls.single().exactId).isEqualTo(exactVideoId)
        assertThat(viewModel.state.value.requestedVideoId).isEqualTo(exactVideoId)
        assertThat(viewModel.state.value.selectedVideo?.id).isEqualTo(exactVideoId)
        assertThat(viewModel.state.value.catalogOriginKey).isEqualTo(addon.installFingerprint())
        assertThat(viewModel.state.value.streams.single().source.addonId).isEqualTo(21L)
    }

    @Test
    fun `torrent sources load without checking for an external playback plugin`() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L)).apply {
            streamResponder = { _, _ ->
                StremioResourceResolution(
                    results = listOf(
                        StremioAttributedResource(
                            source(21L),
                            StremioStream(title = "Torrent", infoHash = "a".repeat(40))
                        )
                    )
                )
            }
        }
        val viewModel = viewModel(
            repository = repository,
            type = "movie",
            exactId = "tt123"
        )
        advanceUntilIdle()

        viewModel.loadStreams("tt123")
        advanceUntilIdle()

        assertThat(viewModel.state.value.streams.single().resource.kind)
            .isEqualTo(com.streamvault.domain.model.stremio.StremioStreamKind.TORRENT)
    }

    @Test
    fun `history replay resolves fresh streams for the saved exact episode id`() = runTest(dispatcher) {
        val exactVideoId = "tt12345:1:2"
        val addon = addon(11L)
        val repository = ItemRepositoryFake(addon).apply {
            meta = StremioMetaDetail(
                id = "tt12345",
                type = StremioMediaType.SERIES,
                name = "Series",
                videos = listOf(StremioVideo(exactVideoId, title = "Episode 2"))
            )
        }
        val replayOriginKey = addon.installFingerprint()
        val viewModel = StremioItemDetailViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    StremioItemDetailViewModel.ARG_ORIGIN_ADDON_ID to addon.id,
                    StremioItemDetailViewModel.ARG_TYPE to "series",
                    StremioItemDetailViewModel.ARG_EXACT_ID to "tt12345",
                    StremioItemDetailViewModel.ARG_NAME to "Series",
                    StremioItemDetailViewModel.ARG_REPLAY_VIDEO_ID to exactVideoId,
                    StremioItemDetailViewModel.ARG_REPLAY_ORIGIN_KEY to replayOriginKey,
                    StremioItemDetailViewModel.ARG_REPLAY_REQUIRES_ORIGIN to true
                )
            ),
            repository = repository,
            contentAccess = FakeStremioContentAccess(),
            downloadManager = mock<DownloadManager>(),
            downloadStarter = StremioDownloadStarter { },
            context = mock<Context>()
        )
        advanceUntilIdle()

        assertThat(repository.metaCalls).containsExactly(addon.id to "tt12345")
        assertThat(repository.streamCalls.single().exactId).isEqualTo(exactVideoId)
        assertThat(viewModel.state.value.catalogOriginKey).isEqualTo(replayOriginKey)
        assertThat(viewModel.state.value.requestedVideoId).isEqualTo(exactVideoId)
    }

    @Test
    fun `custom history replay stays unavailable when its origin addon is missing`() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L))
        val viewModel = StremioItemDetailViewModel(
            savedStateHandle = SavedStateHandle(
                mapOf(
                    StremioItemDetailViewModel.ARG_ORIGIN_ADDON_ID to 0L,
                    StremioItemDetailViewModel.ARG_TYPE to "movie",
                    StremioItemDetailViewModel.ARG_EXACT_ID to "vendor:item:7",
                    StremioItemDetailViewModel.ARG_NAME to "Custom item",
                    StremioItemDetailViewModel.ARG_REPLAY_VIDEO_ID to "vendor:item:7",
                    StremioItemDetailViewModel.ARG_REPLAY_ORIGIN_KEY to "a".repeat(64),
                    StremioItemDetailViewModel.ARG_REPLAY_REQUIRES_ORIGIN to true
                )
            ),
            repository = repository,
            contentAccess = FakeStremioContentAccess(),
            downloadManager = mock<DownloadManager>(),
            downloadStarter = StremioDownloadStarter { },
            context = mock<Context>()
        )
        advanceUntilIdle()

        assertThat(viewModel.state.value.replayOriginUnavailable).isTrue()
        assertThat(repository.metaCalls).isEmpty()
        viewModel.loadStreams("vendor:item:7")
        advanceUntilIdle()
        assertThat(repository.streamCalls).isEmpty()
    }

    @Test
    fun `retry requests only failed addon and keeps other addon rows`() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L)).apply {
            streamResponder = { _, addonIds ->
                if (addonIds == null) {
                    StremioResourceResolution(
                        results = listOf(attributedStream(21L, "old-21")),
                        failures = listOf(failure(22L)),
                        candidateCount = 2
                    )
                } else {
                    StremioResourceResolution(
                        results = listOf(attributedStream(22L, "new-22")),
                        candidateCount = 1
                    )
                }
            }
        }
        val viewModel = viewModel(repository, "movie", "tt123")
        advanceUntilIdle()

        viewModel.loadStreams("tt123")
        advanceUntilIdle()
        viewModel.retryStreamsForAddon(22L)
        advanceUntilIdle()

        assertThat(repository.streamCalls.map { it.addonIds }).containsExactly(null, setOf(22L)).inOrder()
        assertThat(viewModel.state.value.streams.map { it.resource.title }).containsExactly("old-21", "new-22")
            .inOrder()
        assertThat(viewModel.state.value.streamFailures).isEmpty()
    }

    @Test
    fun `changing episode cancels prior fanout and only publishes current id`() = runTest(dispatcher) {
        val firstStarted = CompletableDeferred<Unit>()
        val repository = ItemRepositoryFake(addon(11L)).apply {
            meta = StremioMetaDetail(
                id = "series-id",
                type = StremioMediaType.SERIES,
                name = "Series",
                videos = listOf(StremioVideo("episode-one"), StremioVideo("episode-two"))
            )
            streamResponder = { exactId, _ ->
                if (exactId == "episode-one") {
                    firstStarted.complete(Unit)
                    awaitCancellation()
                } else {
                    StremioResourceResolution(results = listOf(attributedStream(21L, exactId)))
                }
            }
        }
        val viewModel = viewModel(repository, "series", "series-id")
        advanceUntilIdle()

        viewModel.loadStreams("episode-one")
        runCurrent()
        firstStarted.await()
        viewModel.loadStreams("episode-two")
        advanceUntilIdle()

        assertThat(viewModel.state.value.requestedVideoId).isEqualTo("episode-two")
        assertThat(viewModel.state.value.streams.single().resource.title).isEqualTo("episode-two")
    }

    @Test
    fun `catalog preview remains usable when no metadata addon succeeds`() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L)).apply {
            metaResolution = StremioMetaResolution(candidateCount = 0)
        }
        val viewModel = viewModel(repository, "movie", "tt123", name = "Catalog Preview")
        advanceUntilIdle()

        assertThat(viewModel.state.value.metadata).isNull()
        assertThat(viewModel.state.value.preview.name).isEqualTo("Catalog Preview")
        assertThat(viewModel.state.value.metadataLoading).isFalse()
        assertThat(repository.metaCalls.single()).isEqualTo(11L to "tt123")
    }

    @Test
    fun `series without episode ids can check sources using the exact catalog id`() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L)).apply {
            meta = StremioMetaDetail(
                id = "series-custom",
                type = StremioMediaType.SERIES,
                name = "Series without videos"
            )
        }
        val viewModel = viewModel(repository, "series", "series-custom")
        advanceUntilIdle()

        viewModel.loadStreams("series-custom")
        advanceUntilIdle()

        assertThat(viewModel.state.value.videos).isEmpty()
        assertThat(viewModel.state.value.requestedVideoId).isEqualTo("series-custom")
        assertThat(viewModel.state.value.selectedVideo).isNull()
        assertThat(repository.streamCalls.single().exactId).isEqualTo("series-custom")
        assertThat(viewModel.state.value.streams).hasSize(1)
    }

    @Test
    fun `catalog source lookup completes while metadata hangs and survives its 504 failure`() = runTest(dispatcher) {
        val metadataResult = CompletableDeferred<StremioMetaResolution>()
        val repository = ItemRepositoryFake(addon(11L)).apply {
            metaResponder = { metadataResult.await() }
            streamResponder = { _, _ ->
                StremioResourceResolution(results = listOf(
                    StremioAttributedResource(source(21L), StremioStream(infoHash = "a".repeat(40)))
                ))
            }
        }
        val viewModel = viewModel(repository, "series", "catalog:item:7")
        runCurrent()

        viewModel.loadStreams("catalog:item:7")
        runCurrent()

        assertThat(viewModel.state.value.metadataLoading).isTrue()
        assertThat(viewModel.state.value.streamsLoading).isFalse()
        assertThat(viewModel.state.value.streams).hasSize(1)
        assertThat(repository.streamCalls.single().exactId).isEqualTo("catalog:item:7")

        metadataResult.complete(StremioMetaResolution(
            failures = listOf(com.streamvault.domain.model.stremio.StremioAddonResourceFailure(
                source(31L), "Addon request failed with HTTP 504"
            )), candidateCount = 1
        ))
        advanceUntilIdle()

        assertThat(viewModel.state.value.metadataLoading).isFalse()
        assertThat(viewModel.state.value.metadata).isNull()
        assertThat(viewModel.state.value.metadataFailures.single().message).contains("504")
        assertThat(viewModel.state.value.streams).hasSize(1)
    }

    @Test
    fun `movie sources remain available after metadata throws`() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L)).apply {
            metaResponder = { throw java.io.IOException("metadata unavailable") }
        }
        val viewModel = viewModel(repository, "movie", "tt123")
        advanceUntilIdle()

        viewModel.loadStreams("tt123")
        advanceUntilIdle()

        assertThat(viewModel.state.value.metadataError).isEqualTo(R.string.stremio_error_generic.toString())
        assertThat(viewModel.state.value.catalogOriginKey).isEqualTo(addon(11L).installFingerprint())
        assertThat(viewModel.state.value.streams).hasSize(1)
    }

    @Test
    fun `series with known episodes rejects invented ids and parent lookups`() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L)).apply {
            meta = StremioMetaDetail("series-id", StremioMediaType.SERIES, "Series",
                videos = listOf(StremioVideo("episode-one")))
        }
        val viewModel = viewModel(repository, "series", "series-id")
        advanceUntilIdle()

        viewModel.loadStreams("series-id")
        viewModel.loadStreams("invented-episode")
        advanceUntilIdle()

        assertThat(repository.streamCalls).isEmpty()
        viewModel.loadStreams("episode-one")
        advanceUntilIdle()
        assertThat(repository.streamCalls.single().exactId).isEqualTo("episode-one")
    }

    @Test
    fun `failed metadata retry retains known episode ids and available streams`() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L)).apply {
            meta = StremioMetaDetail("series-id", StremioMediaType.SERIES, "Series",
                videos = listOf(StremioVideo("episode-one")))
        }
        val viewModel = viewModel(repository, "series", "series-id")
        advanceUntilIdle()
        viewModel.loadStreams("episode-one")
        advanceUntilIdle()

        repository.metaResolution = StremioMetaResolution(failures = listOf(failure(31L)), candidateCount = 1)
        viewModel.retryMetadata()
        advanceUntilIdle()

        assertThat(viewModel.state.value.videos.single().id).isEqualTo("episode-one")
        assertThat(viewModel.state.value.streams).hasSize(1)
        viewModel.loadStreams("episode-one")
        advanceUntilIdle()
        assertThat(repository.streamCalls).hasSize(2)
    }

    @Test
    fun `history starts exact episode sources before metadata completes and survives its exception`() = runTest(dispatcher) {
        val metadataResult = CompletableDeferred<StremioMetaResolution>()
        val origin = addon(11L)
        val repository = ItemRepositoryFake(origin).apply { metaResponder = { metadataResult.await() } }
        val viewModel = StremioItemDetailViewModel(
            SavedStateHandle(mapOf(
                StremioItemDetailViewModel.ARG_ORIGIN_ADDON_ID to origin.id,
                StremioItemDetailViewModel.ARG_TYPE to "series",
                StremioItemDetailViewModel.ARG_EXACT_ID to "series-id",
                StremioItemDetailViewModel.ARG_REPLAY_VIDEO_ID to "exact:saved:episode",
                StremioItemDetailViewModel.ARG_REPLAY_ORIGIN_KEY to origin.installFingerprint(),
                StremioItemDetailViewModel.ARG_REPLAY_REQUIRES_ORIGIN to true
            )), repository, FakeStremioContentAccess(), mock<DownloadManager>(), StremioDownloadStarter { }, contextWithResourceIds()
        )
        runCurrent()

        assertThat(viewModel.state.value.metadataLoading).isTrue()
        assertThat(repository.streamCalls.single().exactId).isEqualTo("exact:saved:episode")
        assertThat(viewModel.state.value.streams).hasSize(1)

        metadataResult.completeExceptionally(java.io.IOException("metadata unavailable"))
        advanceUntilIdle()

        assertThat(viewModel.state.value.metadataError).isEqualTo(R.string.stremio_error_generic.toString())
        assertThat(viewModel.state.value.streams).hasSize(1)
        assertThat(repository.streamCalls).hasSize(1)
    }

    @Test
    fun `hidden Stremio blocks metadata and stream resolution`() = runTest(dispatcher) {
        val repository = ItemRepositoryFake(addon(11L))
        val access = FakeStremioContentAccess(
            StremioVisibilityState(hidden = true)
        )
        val viewModel = viewModel(repository, "movie", "tt123", contentAccess = access)
        advanceUntilIdle()

        viewModel.loadStreams("tt123")
        advanceUntilIdle()

        assertThat(repository.metaCalls).isEmpty()
        assertThat(repository.streamCalls).isEmpty()
        assertThat(viewModel.state.value.metadataLoading).isFalse()
    }

    private fun viewModel(
        repository: ItemRepositoryFake,
        type: String,
        exactId: String,
        name: String = "Preview",
        contentAccess: StremioContentAccess = FakeStremioContentAccess(),
        playbackHistoryRepository: com.streamvault.domain.repository.StremioPlaybackHistoryRepository? = null
    ) = StremioItemDetailViewModel(
        savedStateHandle = SavedStateHandle(
            mapOf(
                StremioItemDetailViewModel.ARG_ORIGIN_ADDON_ID to 11L,
                StremioItemDetailViewModel.ARG_TYPE to type,
                StremioItemDetailViewModel.ARG_EXACT_ID to exactId,
                StremioItemDetailViewModel.ARG_NAME to name,
                StremioItemDetailViewModel.ARG_POSTER to "https://images.example/poster.jpg"
            )
        ),
        repository = repository,
        contentAccess = contentAccess,
        downloadManager = mock<DownloadManager>(),
        downloadStarter = StremioDownloadStarter { },
            context = contextWithResourceIds(),
        playbackHistoryRepository = playbackHistoryRepository
    )

    private fun contextWithResourceIds(): Context = mock<Context>().also { context ->
        whenever(context.getString(any<Int>())).thenAnswer { invocation ->
            invocation.getArgument<Int>(0).toString()
        }
    }

    private fun addon(id: Long) = StremioAddon(
        id = id,
        installUrl = "https://example.test/$id/manifest.json",
        manifestId = "addon.$id",
        name = "Addon $id",
        version = "1"
    )

    private fun source(id: Long) = StremioAddonSource(
        addonId = id,
        addonName = "Addon $id",
        manifestId = "addon.$id",
        installFingerprint = "fingerprint-$id"
    )

    private fun attributedStream(id: Long, title: String) = StremioAttributedResource(
        source = source(id),
        resource = StremioStream(title = title, url = "https://media.example/$title.mp4")
    )

    private fun failure(id: Long) = com.streamvault.domain.model.stremio.StremioAddonResourceFailure(
        source(id),
        "offline"
    )

    private inner class ItemRepositoryFake(private val addon: StremioAddon) : StremioAddonRepository {
        val addons = MutableStateFlow(listOf(addon))
        val metaCalls = mutableListOf<Pair<Long, String>>()
        val streamCalls = mutableListOf<StreamCall>()
        var meta: StremioMetaDetail? = StremioMetaDetail(
            id = "tt123",
            type = StremioMediaType.MOVIE,
            name = "Resolved"
        )
        var metaResolution: StremioMetaResolution? = null
        var metaResponder: (suspend () -> StremioMetaResolution)? = null
        var streamResponder: suspend (String, Set<Long>?) -> StremioResourceResolution<StremioStream> =
            { exactId, _ -> StremioResourceResolution(results = listOf(attributedStream(21L, exactId))) }

        override fun observeAddons(): Flow<List<StremioAddon>> = addons
        override suspend fun getAddon(id: Long): StremioAddon? = addon.takeIf { it.id == id }
        override suspend fun getManifest(addonId: Long): StremioManifest? = null
        override suspend fun fetchCatalog(
            addonId: Long,
            catalog: StremioCatalogRef,
            query: StremioCatalogQuery
        ): Result<StremioCatalogPage> = Result.success(StremioCatalogPage(emptyList()))
        override suspend fun resolveMeta(
            catalogOriginAddonId: Long,
            type: StremioMediaType,
            exactId: String
        ): StremioMetaResolution {
            metaCalls += catalogOriginAddonId to exactId
            metaResponder?.let { return it() }
            return metaResolution ?: meta?.let {
                StremioMetaResolution(
                    result = StremioAttributedResource(source(31L), it),
                    candidateCount = 1
                )
            } ?: StremioMetaResolution()
        }
        override suspend fun resolveStreams(
            type: StremioMediaType,
            exactId: String,
            addonIds: Set<Long>?
        ): StremioResourceResolution<StremioStream> {
            streamCalls += StreamCall(exactId, addonIds)
            return streamResponder(exactId, addonIds)
        }
        override suspend fun resolveSubtitles(
            type: StremioMediaType,
            exactVideoId: String,
            videoHash: String?,
            videoSize: Long?,
            filename: String?,
            addonIds: Set<Long>?
        ): StremioResourceResolution<StremioSubtitle> = StremioResourceResolution()
        override suspend fun installAddon(installUrl: String): Result<StremioAddon> = Result.error("unused")
        override suspend fun removeAddon(id: Long): Result<Unit> = Result.success(Unit)
        override suspend fun setAddonEnabled(id: Long, enabled: Boolean): Result<Unit> = Result.success(Unit)
        override suspend fun moveAddon(id: Long, offset: Int): Result<Unit> = Result.success(Unit)
        override suspend fun setCatalogEnabled(addonId: Long, catalogKey: String, enabled: Boolean): Result<Unit> =
            Result.success(Unit)
    }
}
