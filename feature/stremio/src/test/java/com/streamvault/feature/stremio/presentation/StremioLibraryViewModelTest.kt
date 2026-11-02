package com.streamvault.feature.stremio.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class StremioLibraryViewModelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }

    @Test fun globalWatchedHistorySortsSavedCopiesAcrossAddonsAndVisibilityClearsRows() = runTest(dispatcher) {
        val addon=StremioAddon(1,"https://fixture.test/manifest.json","fixture","Fixture","1",
            catalogSettings=mapOf(stremioLibraryKey(StremioMediaType.MOVIE,"tt123") to true))
        val addons=mock<StremioAddonRepository>()
        val histories=mock<StremioPlaybackHistoryRepository>()
        whenever(addons.observeAddons()).thenReturn(MutableStateFlow(listOf(addon)))
        whenever(addons.resolveMeta(any(),any(),any())).thenReturn(StremioMetaResolution())
        whenever(histories.observeHistory()).thenReturn(MutableStateFlow(listOf(StremioPlaybackHistory(
            "stremio:global:movie:tt123","movie","tt123",title="Movie",watched=true,lastWatchedAt=123,
            catalogOriginKey="a".repeat(64)))))
        val access=FakeStremioContentAccess()
        val vm=StremioLibraryViewModel(addons,histories,access,NoopStremioCatalogParentalAccess)
        advanceUntilIdle()
        assertThat(vm.state.value.single().watched).isTrue()
        assertThat(vm.state.value.single().lastWatchedAt).isEqualTo(123)
        access.setState(StremioVisibilityState(hidden=true));advanceUntilIdle()
        assertThat(vm.state.value).isEmpty()
    }

    @Test fun oneWatchedEpisodeDoesNotMarkAWholeReleasedSeriesWatched() = runTest(dispatcher) {
        val addon=StremioAddon(1,"https://fixture.test/manifest.json","fixture","Fixture","1",
            catalogSettings=mapOf(stremioLibraryKey(StremioMediaType.SERIES,"tt123") to true))
        val addons=mock<StremioAddonRepository>()
        val histories=mock<StremioPlaybackHistoryRepository>()
        whenever(addons.observeAddons()).thenReturn(MutableStateFlow(listOf(addon)))
        val meta=StremioMetaDetail("tt123",StremioMediaType.SERIES,"Series",videos=listOf(
            StremioVideo("tt123:1:1",season=1,episode=1),StremioVideo("tt123:1:2",season=1,episode=2)))
        whenever(addons.resolveMeta(any(),any(),any())).thenReturn(StremioMetaResolution(
            StremioAttributedResource(StremioAddonSource(1,"Fixture","fixture",addon.installFingerprint()),meta)))
        val record=StremioPlaybackHistory("stremio:global:series-episode:tt123:1:1","series","tt123:1:1","tt123","Series",watched=true,catalogOriginKey=addon.installFingerprint())
        val records=MutableStateFlow(listOf(record))
        whenever(histories.observeHistory()).thenReturn(records)
        val vm=StremioLibraryViewModel(addons,histories,FakeStremioContentAccess(),NoopStremioCatalogParentalAccess)
        advanceUntilIdle();assertThat(vm.state.value.single().watched).isFalse()
        records.value=listOf(record,record.copy(contentKey="stremio:global:series-episode:tt123:1:2",exactVideoId="tt123:1:2"))
        advanceUntilIdle();assertThat(vm.state.value.single().watched).isTrue()
    }
}
