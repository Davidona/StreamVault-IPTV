package com.streamvault.feature.stremio.presentation

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
import com.streamvault.domain.repository.StremioAddonRepository
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
            contentAccess = FakeStremioContentAccess()
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
            contentAccess = FakeStremioContentAccess()
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
    fun `series without exact episode ids cannot request parent streams`() = runTest(dispatcher) {
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
        assertThat(viewModel.state.value.requestedVideoId).isNull()
        assertThat(repository.streamCalls).isEmpty()
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
        contentAccess: StremioContentAccess = FakeStremioContentAccess()
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
        contentAccess = contentAccess
    )

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
