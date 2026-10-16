package com.streamvault.feature.stremio.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioAddon
import com.streamvault.domain.model.stremio.StremioCatalogExtraDef
import com.streamvault.domain.model.stremio.StremioCatalogPage
import com.streamvault.domain.model.stremio.StremioCatalogQuery
import com.streamvault.domain.model.stremio.StremioCatalogRef
import com.streamvault.domain.model.stremio.StremioManifest
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaPreview
import com.streamvault.domain.model.stremio.StremioMetaResolution
import com.streamvault.domain.model.stremio.StremioResourceResolution
import com.streamvault.domain.model.stremio.StremioStream
import com.streamvault.domain.model.stremio.StremioSubtitle
import com.streamvault.domain.repository.StremioAddonRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StremioCatalogBrowseViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `full page without hasMore loads next page using raw offset`() = runTest(dispatcher) {
        val repository = CatalogRepositoryFake(catalogWithSkip())
        repository.pages += StremioCatalogPage((0 until 100).map { preview("tt$it") }, rawItemCount = 100)
        repository.pages += StremioCatalogPage(
            metas = listOf(preview("tt0"), preview("tt100")),
            rawItemCount = 7
        )
        val viewModel = StremioCatalogBrowseViewModel(repository, FakeStremioContentAccess())
        advanceUntilIdle()

        val rowKey = viewModel.state.value.rows.single().key
        assertThat(viewModel.state.value.rows.single().nextSkip).isEqualTo(100)
        assertThat(viewModel.state.value.rows.single().canLoadMore).isTrue()

        viewModel.loadMore(rowKey)
        advanceUntilIdle()

        val row = viewModel.state.value.rows.single()
        assertThat(repository.calls.map { it.query.skip }).containsExactly(0, 100).inOrder()
        assertThat(row.nextSkip).isEqualTo(107)
        assertThat(row.metas).hasSize(101)
        assertThat(row.canLoadMore).isFalse()
    }

    @Test
    fun `short page without hasMore stops pagination`() = runTest(dispatcher) {
        val repository = CatalogRepositoryFake(catalogWithSkip())
        repository.pages += StremioCatalogPage(listOf(preview("tt1")), rawItemCount = 99)
        val viewModel = StremioCatalogBrowseViewModel(repository, FakeStremioContentAccess())
        advanceUntilIdle()

        val row = viewModel.state.value.rows.single()
        assertThat(row.canLoadMore).isFalse()
        assertThat(row.nextSkip).isEqualTo(99)
    }

    @Test
    fun `required search catalog waits until query exists`() = runTest(dispatcher) {
        val catalog = catalogWithSkip(
            extras = listOf(
                StremioCatalogExtraDef("search", isRequired = true),
                StremioCatalogExtraDef("skip")
            )
        )
        val repository = CatalogRepositoryFake(catalog)
        val viewModel = StremioCatalogBrowseViewModel(repository, FakeStremioContentAccess())
        advanceUntilIdle()

        val row = viewModel.state.value.rows.single()
        assertThat(repository.calls).isEmpty()
        assertThat(row.waitingForInput).isNotNull()

        viewModel.setSearch(row.key, "  galaxy  ")
        advanceTimeBy(350)
        advanceUntilIdle()

        assertThat(repository.calls).hasSize(1)
        assertThat(repository.calls.single().query.search).isEqualTo("galaxy")
        assertThat(repository.calls.single().query.skip).isEqualTo(0)
    }

    @Test
    fun `repeated page response stops further pagination`() = runTest(dispatcher) {
        val repository = CatalogRepositoryFake(catalogWithSkip())
        val samePage = StremioCatalogPage(listOf(preview("tt1")), hasMore = true, rawItemCount = 1)
        repository.pages += samePage
        repository.pages += samePage
        val viewModel = StremioCatalogBrowseViewModel(repository, FakeStremioContentAccess())
        advanceUntilIdle()
        val key = viewModel.state.value.rows.single().key

        viewModel.loadMore(key)
        advanceUntilIdle()

        val row = viewModel.state.value.rows.single()
        assertThat(row.canLoadMore).isFalse()
        assertThat(row.error).contains("repeated a page")
    }

    @Test
    fun `manifest refresh discards cached rows and reloads the catalog`() = runTest(dispatcher) {
        val repository = CatalogRepositoryFake(catalogWithSkip())
        repository.pages += StremioCatalogPage(listOf(preview("before-refresh")), rawItemCount = 1)
        repository.pages += StremioCatalogPage(listOf(preview("after-refresh")), rawItemCount = 1)
        val viewModel = StremioCatalogBrowseViewModel(repository, FakeStremioContentAccess())
        advanceUntilIdle()

        repository.addons.value = repository.addons.value.map { it.copy(manifestJson = "{\"version\":2}") }
        advanceUntilIdle()

        assertThat(repository.calls).hasSize(2)
        assertThat(viewModel.state.value.rows.single().metas.map { it.id })
            .containsExactly("after-refresh")
    }

    @Test
    fun `changing search cancels the stale catalog request`() = runTest(dispatcher) {
        val catalog = catalogWithSkip(
            extras = listOf(StremioCatalogExtraDef("search"), StremioCatalogExtraDef("skip"))
        )
        val repository = CatalogRepositoryFake(catalog)
        val staleRequestCancelled = CompletableDeferred<Unit>()
        repository.fetchResponder = { call ->
            if (call.query.search == null) {
                try {
                    awaitCancellation()
                } finally {
                    staleRequestCancelled.complete(Unit)
                }
            } else {
                Result.success(StremioCatalogPage(listOf(preview("fresh-result")), rawItemCount = 1))
            }
        }
        val viewModel = StremioCatalogBrowseViewModel(repository, FakeStremioContentAccess())
        runCurrent()
        val rowKey = viewModel.state.value.rows.single().key

        viewModel.setSearch(rowKey, "Matrix")
        advanceTimeBy(350)
        runCurrent()

        assertThat(staleRequestCancelled.isCompleted).isTrue()
        assertThat(repository.calls.map { it.query.search }).containsExactly(null, "Matrix").inOrder()
        assertThat(viewModel.state.value.rows.single().metas.map { it.id }).containsExactly("fresh-result")
    }

    @Test
    fun `disabled addons and catalogs are excluded from browse rows`() = runTest(dispatcher) {
        val repository = CatalogRepositoryFake(catalogWithSkip())
        repository.addons.value = listOf(
            addon(id = 1L, enabled = true),
            addon(id = 2L, enabled = false),
            addon(id = 3L, enabled = true, catalogEnabled = false)
        )
        repository.manifests[1L] = manifest(catalogWithSkip())
        repository.manifests[2L] = manifest(catalogWithSkip())
        repository.manifests[3L] = manifest(catalogWithSkip())
        val viewModel = StremioCatalogBrowseViewModel(repository, FakeStremioContentAccess())
        advanceUntilIdle()

        assertThat(viewModel.state.value.rows.map { it.key.addonId }).containsExactly(1L)
        assertThat(repository.calls.map { it.addonId }).containsExactly(1L)
    }

    @Test
    fun `hidden Stremio does not fetch addon catalog pages`() = runTest(dispatcher) {
        val repository = CatalogRepositoryFake(catalogWithSkip())
        val access = FakeStremioContentAccess(
            StremioVisibilityState(hidden = true)
        )
        val viewModel = StremioCatalogBrowseViewModel(repository, access)
        advanceUntilIdle()

        assertThat(viewModel.state.value.rows).hasSize(1)
        assertThat(repository.calls).isEmpty()
    }

    private fun catalogWithSkip(
        extras: List<StremioCatalogExtraDef> = listOf(StremioCatalogExtraDef("skip"))
    ) = StremioCatalogRef(
        type = StremioMediaType.MOVIE,
        id = "top",
        name = "Popular",
        extras = extras
    )

    private fun manifest(catalog: StremioCatalogRef) = StremioManifest(
        id = "test.addon",
        name = "Test",
        version = "1",
        types = listOf(StremioMediaType.MOVIE),
        catalogs = listOf(catalog)
    )

    private fun addon(
        id: Long,
        enabled: Boolean,
        catalogEnabled: Boolean = true
    ) = StremioAddon(
        id = id,
        installUrl = "https://example.test/$id/manifest.json",
        manifestId = "test.$id",
        name = "Addon $id",
        version = "1",
        enabled = enabled,
        catalogSettings = mapOf("movie:top" to catalogEnabled)
    )

    private fun preview(id: String) = StremioMetaPreview(
        id = id,
        type = StremioMediaType.MOVIE,
        name = id
    )
}

private class CatalogRepositoryFake(catalog: StremioCatalogRef) : StremioAddonRepository {
    data class FetchCall(val addonId: Long, val query: StremioCatalogQuery)

    val addons = MutableStateFlow(listOf(addon(id = 1L, enabled = true)))
    val manifests = mutableMapOf(1L to manifest(catalog))
    val calls = mutableListOf<FetchCall>()
    val pages = ArrayDeque<StremioCatalogPage>()
    var fetchResponder: (suspend (FetchCall) -> Result<StremioCatalogPage>)? = null

    override fun observeAddons(): Flow<List<StremioAddon>> = addons
    override suspend fun getAddon(id: Long): StremioAddon? = addons.value.firstOrNull { it.id == id }
    override suspend fun getManifest(addonId: Long): StremioManifest? = manifests[addonId]
    override suspend fun fetchCatalog(
        addonId: Long,
        catalog: StremioCatalogRef,
        query: StremioCatalogQuery
    ): Result<StremioCatalogPage> {
        val call = FetchCall(addonId, query)
        calls += call
        fetchResponder?.let { responder -> return responder(call) }
        return Result.success(pages.removeFirstOrNull() ?: StremioCatalogPage(emptyList()))
    }
    override suspend fun resolveMeta(
        catalogOriginAddonId: Long,
        type: StremioMediaType,
        exactId: String
    ): StremioMetaResolution = StremioMetaResolution()
    override suspend fun resolveStreams(
        type: StremioMediaType,
        exactId: String,
        addonIds: Set<Long>?
    ): StremioResourceResolution<StremioStream> = StremioResourceResolution()
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

private fun addon(
    id: Long,
    enabled: Boolean,
    catalogEnabled: Boolean = true
) = StremioAddon(
    id = id,
    installUrl = "https://example.test/$id/manifest.json",
    manifestId = "test.$id",
    name = "Addon $id",
    version = "1",
    enabled = enabled,
    catalogSettings = mapOf("movie:top" to catalogEnabled)
)

private fun manifest(catalog: StremioCatalogRef) = StremioManifest(
    id = "test.addon",
    name = "Test",
    version = "1",
    types = listOf(StremioMediaType.MOVIE),
    catalogs = listOf(catalog)
)
