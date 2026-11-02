package com.streamvault.feature.stremio.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.StremioAddonRepository
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Test
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class StremioGuideTest {
    @Test fun localDaysRequestAllOverlappingUtcDaysIncludingDstBoundaries() {
        assertThat(guideUtcDates(LocalDate.parse("2026-03-08"), ZoneId.of("America/New_York")))
            .containsExactly(LocalDate.parse("2026-03-08"), LocalDate.parse("2026-03-09")).inOrder()
        assertThat(guideUtcDates(LocalDate.parse("2026-11-01"), ZoneId.of("Asia/Jerusalem")))
            .containsExactly(LocalDate.parse("2026-10-31"), LocalDate.parse("2026-11-01")).inOrder()
        assertThat(guideUtcDates(LocalDate.parse("2026-11-01"), ZoneId.of("UTC")))
            .containsExactly(LocalDate.parse("2026-11-01"))
    }

    @Test fun guideMergeDeduplicatesProgrammesAndDropsInvalidOrOutsideSchedules() {
        val valid = StremioVideo("programme", startTime = "2026-11-01T10:00:00Z", endTime = "2026-11-01T11:00:00Z")
        val channel = StremioMetaDetail("channel", StremioMediaType.TV, "News", videos = listOf(valid))
        val second = channel.copy(videos = listOf(valid,
            valid.copy(id = "invalid", endTime = "2026-11-01T09:00:00Z"),
            valid.copy(id = "old", startTime = "2026-10-30T10:00:00Z", endTime = "2026-10-30T11:00:00Z")))
        val result = mergeGuideChannels(listOf(channel, second), LocalDate.parse("2026-11-01"), ZoneId.of("UTC"))
        assertThat(result).hasSize(1)
        assertThat(result.single().videos.map { it.id }).containsExactly("programme")
    }

    @Test fun guidePaginationContinuesAfterShortPagesUntilEmptyResponse() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = mock<StremioAddonRepository>()
            val addon = StremioAddon(id = 1, installUrl = "https://guide.example/manifest.json", manifestId = "guide", name = "Guide", version = "1")
            val catalog = StremioCatalogRef(StremioMediaType.TV, "guide", extras = listOf(StremioCatalogExtraDef("date"), StremioCatalogExtraDef("skip")))
            whenever(repository.observeAddons()).thenReturn(MutableStateFlow(listOf(addon)))
            whenever(repository.getManifest(1)).thenReturn(StremioManifest("guide", "Guide", "1", catalogs = listOf(catalog), behaviorHints = StremioManifestBehaviorHints(epgProvider = true)))
            val calls = mutableListOf<StremioCatalogQuery>()
            whenever(repository.fetchCatalog(eq(1L), eq(catalog), any())).thenAnswer { invocation ->
                val query = invocation.getArgument<StremioCatalogQuery>(2)
                calls += query
                Result.success(if (query.skip == 0) StremioCatalogPage(emptyList(), rawItemCount = 1,
                    detailedMetas = listOf(StremioMetaDetail("news", StremioMediaType.TV, "News")))
                    else StremioCatalogPage(emptyList()))
            }
            val viewModel = StremioGuideViewModel(repository, FakeStremioContentAccess(), NoopStremioCatalogParentalAccess)
            advanceUntilIdle()
            assertThat(calls.filter { it.skip == 1 }).isNotEmpty()
            assertThat(calls.all { it.extraValues["date"] != null }).isTrue()
            assertThat(viewModel.state.value.channels.single().id).isEqualTo("news")
            assertThat(viewModel.state.value.failed).isFalse()
        } finally { Dispatchers.resetMain() }
    }
}
