package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.stalker.StalkerAdvancedOptions
import com.streamvault.data.remote.stalker.StalkerAdvancedOptionsCodec
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.StalkerCatalogLoadOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class StalkerCatalogExecutionTest {
    private val sections = listOf(ContentType.MOVIE, ContentType.SERIES)

    @Test
    fun `default and old saved options are parallel and new choices round trip`() {
        assertThat(StalkerAdvancedOptionsCodec.decode("").catalogLoadOrder).isEqualTo(StalkerCatalogLoadOrder.PARALLEL)
        assertThat(StalkerAdvancedOptionsCodec.decode("""{"hwVersion":"custom"}""").catalogLoadOrder)
            .isEqualTo(StalkerCatalogLoadOrder.PARALLEL)
        for (order in StalkerCatalogLoadOrder.entries) {
            val original = StalkerAdvancedOptions(hwVersion = "custom", catalogLoadOrder = order)
            assertThat(StalkerAdvancedOptionsCodec.decodeStrict(StalkerAdvancedOptionsCodec.encode(original))).isEqualTo(original)
        }
        val future = StalkerAdvancedOptionsCodec.decode("""{"catalogLoadOrder":"FUTURE","hwVersion":"custom"}""")
        assertThat(future.catalogLoadOrder).isEqualTo(StalkerCatalogLoadOrder.PARALLEL)
        assertThat(future.hwVersion).isEqualTo("custom")
    }

    @Test
    fun `parallel mode and category heads start both sections without waiting for the other`() = runTest {
        for ((order, heads) in listOf(StalkerCatalogLoadOrder.PARALLEL to false,
            StalkerCatalogLoadOrder.MOVIES_FIRST to true, StalkerCatalogLoadOrder.SERIES_FIRST to true)) {
            val entered = mutableSetOf<ContentType>()
            val bothEntered = CompletableDeferred<Unit>()
            val work = async {
                executeStalkerCatalogSections(sections, order, heads, isPending = { true }) { section ->
                    entered += section
                    if (entered.size == 2) bothEntered.complete(Unit)
                    bothEntered.await()
                    section
                }
            }
            bothEntered.await()
            assertThat(work.await()).containsExactlyElementsIn(sections)
        }
    }

    @Test
    fun `serial choices keep the other section queued until the preferred section finishes`() = runTest {
        for (order in listOf(StalkerCatalogLoadOrder.MOVIES_FIRST, StalkerCatalogLoadOrder.SERIES_FIRST)) {
            val first = if (order == StalkerCatalogLoadOrder.MOVIES_FIRST) ContentType.MOVIE else ContentType.SERIES
            val visited = mutableListOf<ContentType>()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val work = async {
                executeStalkerCatalogSections(sections, order, false, isPending = { false }) { section ->
                    visited += section
                    if (section == first) { entered.complete(Unit); release.await() }
                    section
                }
            }
            entered.await()
            assertThat(visited).containsExactly(first)
            release.complete(Unit)
            work.await()
            assertThat(visited.first()).isEqualTo(first)
            assertThat(visited).hasSize(2)
        }
    }

    @Test
    fun `retry waits keep the second section queued without an immediate successor loop`() = runTest {
        for (order in listOf(StalkerCatalogLoadOrder.MOVIES_FIRST, StalkerCatalogLoadOrder.SERIES_FIRST)) {
            val first = if (order == StalkerCatalogLoadOrder.MOVIES_FIRST) ContentType.MOVIE else ContentType.SERIES
            val visited = mutableListOf<ContentType>()
            executeStalkerCatalogSections(sections, order, false, isPending = { true }) { section -> visited += section; section }
            assertThat(visited).containsExactly(first)
            fun state(type: ContentType) = StalkerCatalogSectionState(type, type != first,
                if (type == first) 120 else 0, true, "QUEUED", 0)
            val decision = StalkerIndexPolicy.chooseNextSection(CatalogLayout.SPLIT, null,
                state(ContentType.MOVIE), state(ContentType.SERIES), order)
            assertThat(decision.contentType).isNull()
            assertThat(decision.retryDelaySeconds).isEqualTo(120L)
        }
    }

    @Test
    fun `a terminal truncated first section releases the queued second section`() = runTest {
        val dao = mock<com.streamvault.data.local.dao.StalkerIndexJobDao>()
        whenever(dao.get(1L, ContentType.MOVIE.name)).thenReturn(
            com.streamvault.data.local.entity.StalkerIndexJobEntity(1L, ContentType.MOVIE,
                com.streamvault.domain.model.StalkerIndexState.PARTIAL))
        val provider = com.streamvault.domain.model.LegacyProvider(1L, "Portal",
            com.streamvault.domain.model.ProviderType.STALKER_PORTAL, serverUrl = "https://portal.invalid",
            stalkerAdvancedOptionsJson = StalkerAdvancedOptionsCodec.encode(
                StalkerAdvancedOptions(catalogLoadOrder = StalkerCatalogLoadOrder.MOVIES_FIRST)))
        val truncated = StalkerHydrationSnapshot(1, 14, "TRUNCATED", null,
            1, 1, 1, 100, 1400, 100, false, 14, 0, 0, 3, null)
        val coordinator = StalkerIndexContinuationCoordinator(
            stalkerIndexJobStore = StalkerIndexJobStore(dao),
            loadCategories = { _, type -> listOf(com.streamvault.data.local.entity.CategoryEntity(
                categoryId = if (type == ContentType.MOVIE) 10 else 20, name = "Category", type = type, providerId = 1)) },
            loadHydration = { _, type, _ -> if (type == ContentType.MOVIE) truncated else null },
            visibleCategories = { _, _, categories, _ -> categories },
            loadProvider = { provider }, loadStalkerProviders = { listOf(1) },
            deleteLegacyJobs = {}, cancelIndex = {}, scheduleIndex = { _, _, _ -> }, scheduleBackgroundEpg = {})
        val decision = coordinator.chooseNextSection(provider, mock(), null, false, 10)
        assertThat(decision.contentType).isEqualTo(ContentType.SERIES)
    }

    @Test
    fun `unified VOD and disabled sections never wait for an absent section`() = runTest {
        val visited = mutableListOf<ContentType>()
        executeStalkerCatalogSections(listOf(ContentType.MOVIE), StalkerCatalogLoadOrder.SERIES_FIRST, false,
            isPending = { true }) { visited += it; it }
        assertThat(visited).containsExactly(ContentType.MOVIE)
    }
}
