package com.streamvault.feature.stremio.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioAddon
import com.streamvault.domain.model.stremio.StremioCatalogPage
import com.streamvault.domain.model.stremio.StremioCatalogQuery
import com.streamvault.domain.model.stremio.StremioCatalogRef
import com.streamvault.domain.model.stremio.StremioManifest
import com.streamvault.domain.model.stremio.StremioManifestResource
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaResolution
import com.streamvault.domain.model.stremio.StremioPlaybackHistory
import com.streamvault.domain.model.stremio.StremioResourceResolution
import com.streamvault.domain.model.stremio.StremioResourceKind
import com.streamvault.domain.model.stremio.StremioStream
import com.streamvault.domain.model.stremio.StremioSubtitle
import com.streamvault.domain.repository.StremioAddonRepository
import com.streamvault.domain.repository.StremioPlaybackHistoryRepository
import com.streamvault.feature.stremio.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

@OptIn(ExperimentalCoroutinesApi::class)
class AddonsViewModelTest {

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
    fun `addons load with their catalog snapshots`() = runTest(dispatcher) {
        val repository = FakeStremioAddonRepository()
        repository.addons.value = listOf(addon(id = 1L, name = "Cinemeta"))
        repository.manifests[1L] = manifestWithOneCatalog()

        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.loading).isFalse()
        assertThat(state.addons).hasSize(1)
        assertThat(state.addons.first().addon.name).isEqualTo("Cinemeta")
        assertThat(state.addons.first().catalogs.single().id).isEqualTo("top")
    }

    @Test
    fun `install success closes the dialog and reports status`() = runTest(dispatcher) {
        val repository = FakeStremioAddonRepository()
        repository.installResult = Result.success(addon(id = 5L, name = "New Addon"))
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.showInstallDialog()
        viewModel.updateInstallUrl("https://example.test/manifest.json")
        viewModel.install()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.installDialogVisible).isFalse()
        assertThat(state.installUrl).isEmpty()
        assertThat(state.installing).isFalse()
        assertThat(state.statusMessageRes).isEqualTo(R.string.stremio_status_installed)
        assertThat(state.errorMessage).isNull()
    }

    @Test
    fun `hidden Stremio addons cannot be installed`() = runTest(dispatcher) {
        val repository = FakeStremioAddonRepository()
        val access = FakeStremioContentAccess(
            StremioVisibilityState(hidden = true)
        )
        val viewModel = createViewModel(repository, access)
        advanceUntilIdle()

        viewModel.showInstallDialog()
        viewModel.updateInstallUrl("https://example.test/manifest.json")
        viewModel.install()
        advanceUntilIdle()

        assertThat(viewModel.state.value.installDialogVisible).isFalse()
        assertThat(repository.installCalls).isEmpty()
    }

    @Test
    fun `install failure keeps the dialog and shows the error`() = runTest(dispatcher) {
        val repository = FakeStremioAddonRepository()
        repository.installResult = Result.error("Addon request failed with HTTP 500")
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.showInstallDialog()
        viewModel.updateInstallUrl("https://example.test/manifest.json")
        viewModel.install()
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(state.installDialogVisible).isTrue()
        assertThat(state.installing).isFalse()
        assertThat(state.errorMessage).contains("500")
    }

    @Test
    fun `blank install urls are ignored`() = runTest(dispatcher) {
        val repository = FakeStremioAddonRepository()
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.showInstallDialog()
        viewModel.updateInstallUrl("   ")
        viewModel.install()
        advanceUntilIdle()

        assertThat(repository.installCalls).isEmpty()
        assertThat(viewModel.state.value.installDialogVisible).isTrue()
    }

    @Test
    fun `remove collapses the expanded card and reports status`() = runTest(dispatcher) {
        val repository = FakeStremioAddonRepository()
        repository.addons.value = listOf(addon(id = 3L, name = "Cinemeta"))
        val viewModel = createViewModel(repository)
        advanceUntilIdle()

        viewModel.toggleExpanded(3L)
        viewModel.removeAddon(viewModel.state.value.addons.single())
        advanceUntilIdle()

        val state = viewModel.state.value
        assertThat(repository.removedIds).containsExactly(3L)
        assertThat(state.expandedAddonId).isNull()
        assertThat(state.statusMessageRes).isEqualTo(R.string.stremio_status_removed)
    }

    @Test
    fun `enabled move and catalog toggles reach the repository`() = runTest(dispatcher) {
        val repository = FakeStremioAddonRepository()
        repository.addons.value = listOf(addon(id = 7L, name = "Cinemeta"))
        val viewModel = createViewModel(repository)
        advanceUntilIdle()
        val entry = viewModel.state.value.addons.single()

        viewModel.setEnabled(entry, enabled = false)
        advanceUntilIdle()
        viewModel.moveAddon(entry, offset = -1)
        advanceUntilIdle()
        viewModel.setCatalogEnabled(entry, "movie:top", enabled = false)
        advanceUntilIdle()

        assertThat(repository.lastEnabled).isEqualTo(7L to false)
        assertThat(repository.lastMove).isEqualTo(7L to -1)
        assertThat(repository.lastCatalogToggle).isEqualTo(Triple(7L, "movie:top", false))
    }

    private fun addon(id: Long, name: String): StremioAddon = StremioAddon(
        id = id,
        installUrl = "https://example.test/$id/manifest.json",
        manifestId = "test.$id",
        name = name,
        version = "1.0.0"
    )

    private fun manifestWithOneCatalog(): StremioManifest = StremioManifest(
        id = "test",
        name = "Test",
        version = "1.0.0",
        types = listOf(StremioMediaType.MOVIE),
        resources = listOf(StremioManifestResource(StremioResourceKind.CATALOG)),
        catalogs = listOf(
            StremioCatalogRef(type = StremioMediaType.MOVIE, id = "top", name = "Popular")
        )
    )
}

private fun createViewModel(
    repository: StremioAddonRepository,
    contentAccess: StremioContentAccess = FakeStremioContentAccess()
) = AddonsViewModel(repository, FakeStremioPlaybackHistoryRepository(), contentAccess, mock())

private class FakeStremioPlaybackHistoryRepository : StremioPlaybackHistoryRepository {
    override fun observeContinueWatching(limit: Int): Flow<List<StremioPlaybackHistory>> =
        MutableStateFlow(emptyList())

    override suspend fun get(contentKey: String): StremioPlaybackHistory? = null

    override suspend fun save(history: StremioPlaybackHistory): Result<Unit> = Result.success(Unit)

    override suspend fun delete(contentKey: String): Result<Unit> = Result.success(Unit)
}

private class FakeStremioAddonRepository : StremioAddonRepository {
    val addons = MutableStateFlow<List<StremioAddon>>(emptyList())
    val manifests = mutableMapOf<Long, StremioManifest>()
    val installCalls = mutableListOf<String>()
    val removedIds = mutableListOf<Long>()
    var installResult: Result<StremioAddon> = Result.error("not configured")
    var lastEnabled: Pair<Long, Boolean>? = null
    var lastMove: Pair<Long, Int>? = null
    var lastCatalogToggle: Triple<Long, String, Boolean>? = null

    override fun observeAddons(): Flow<List<StremioAddon>> = addons

    override suspend fun getAddon(id: Long): StremioAddon? =
        addons.value.firstOrNull { it.id == id }

    override suspend fun getManifest(addonId: Long): StremioManifest? = manifests[addonId]

    override suspend fun fetchCatalog(
        addonId: Long,
        catalog: StremioCatalogRef,
        query: StremioCatalogQuery
    ): Result<StremioCatalogPage> = Result.success(StremioCatalogPage(emptyList()))

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

    override suspend fun installAddon(installUrl: String): Result<StremioAddon> {
        installCalls += installUrl
        return installResult
    }

    override suspend fun removeAddon(id: Long): Result<Unit> {
        removedIds += id
        addons.value = addons.value.filterNot { it.id == id }
        return Result.success(Unit)
    }

    override suspend fun setAddonEnabled(id: Long, enabled: Boolean): Result<Unit> {
        lastEnabled = id to enabled
        return Result.success(Unit)
    }

    override suspend fun moveAddon(id: Long, offset: Int): Result<Unit> {
        lastMove = id to offset
        return Result.success(Unit)
    }

    override suspend fun setCatalogEnabled(
        addonId: Long,
        catalogKey: String,
        enabled: Boolean
    ): Result<Unit> {
        lastCatalogToggle = Triple(addonId, catalogKey, enabled)
        return Result.success(Unit)
    }
}
