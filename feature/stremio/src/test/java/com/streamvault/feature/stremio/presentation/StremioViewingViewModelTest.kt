package com.streamvault.feature.stremio.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.*
import com.streamvault.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.mockito.kotlin.*

@OptIn(ExperimentalCoroutinesApi::class)
class StremioViewingViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() { Dispatchers.setMain(dispatcher) }
    @After fun after() { Dispatchers.resetMain() }
    private val addon=StremioAddon(1,"https://fixture.test/manifest.json","fixture","Fixture","1")
    private fun history(id:String,watched:Boolean=false,at:Long=1) = StremioPlaybackHistory(
        stremioPlaybackContentKey("series",id,"series",addon.installFingerprint())!!,"series",id,"series","Series",watched=watched,
        resumePositionMs=if(watched) 100 else 30,totalDurationMs=100,lastWatchedAt=at,catalogOriginKey=addon.installFingerprint())
    private val meta=StremioMetaDetail("series",StremioMediaType.SERIES,"Series",videos=listOf(
        StremioVideo("one",season=1,episode=1),StremioVideo("two",season=1,episode=2),
        StremioVideo("future",season=1,episode=3,released="2999-01-01T00:00:00Z")))
    private suspend fun setup(records:List<StremioPlaybackHistory>, access:FakeStremioContentAccess=FakeStremioContentAccess(), followed:Boolean=false, metadata:StremioMetaDetail=meta): Triple<StremioViewingViewModel,MutableStateFlow<List<StremioPlaybackHistory>>,StremioPlaybackHistoryRepository> {
        val addons=mock<StremioAddonRepository>()
        val histories=mock<StremioPlaybackHistoryRepository>()
        val flow=MutableStateFlow(records)
        whenever(addons.observeAddons()).thenReturn(MutableStateFlow(listOf(if (followed) addon.copy(catalogSettings=mapOf(stremioLibraryKey(StremioMediaType.SERIES,"series") to true)) else addon)))
        whenever(histories.observeHistory()).thenReturn(flow)
        whenever(histories.dismiss(any())).thenReturn(Result.success(Unit))
        whenever(addons.resolveMeta(any(),any(),any())).thenReturn(StremioMetaResolution(
            StremioAttributedResource(StremioAddonSource(1,"Fixture","fixture",addon.installFingerprint()),metadata)))
        return Triple(StremioViewingViewModel(addons,histories,access,NoopStremioCatalogParentalAccess),flow,histories)
    }
    @Test fun unplayedUnwatchedMarkersDoNotReplaceTheLastPlayedEpisode() = runTest(dispatcher) {
        val (vm,_,_) = setup(listOf(history("future",false,0).copy(resumePositionMs=0),history("one",true,1)))
        advanceUntilIdle()
        assertThat(vm.state.value.history.map { it.history.exactVideoId }).containsExactly("one")
        assertThat(vm.state.value.watching.single().history.exactVideoId).isEqualTo("two")
    }

    @Test fun followedSeriesCountsReleasedEpisodesAndListsUpcomingReleases() = runTest(dispatcher) {
        val metadata = meta.copy(videos=meta.videos.map { video ->
            video.copy(released=if(video.id=="future") "2999-01-01T00:00:00Z" else "2020-01-01T00:00:00+00:00")
        }.plus(StremioVideo("soon",season=1,episode=4,released=java.time.Instant.now().plusSeconds(86400).toString())))
        val (vm,_,_) = setup(listOf(history("one",true)),followed=true,metadata=metadata)
        advanceUntilIdle()
        assertThat(vm.state.value.watching.single().newEpisodes).isEqualTo(1)
        assertThat(vm.state.value.releases.map { it.video.id }).containsExactly("soon")
    }

    @Test fun completedEpisodeProvidesNextEpisodeAndRetainsCompletedHistory() = runTest(dispatcher) {
        val (vm,_,_)=setup(listOf(history("one",true)))
        advanceUntilIdle()
        assertThat(vm.state.value.watching.single().history.exactVideoId).isEqualTo("two")
        assertThat(vm.state.value.watching.single().upNext).isTrue()
        assertThat(vm.state.value.history.single().history.watched).isTrue()
    }
    @Test fun dismissTargetsCompletedCursorAndDoesNotDeleteHistory() = runTest(dispatcher) {
        val (vm,flow,repository)=setup(listOf(history("one",true)))
        advanceUntilIdle()
        vm.dismiss(vm.state.value.watching.single());advanceUntilIdle()
        verify(repository).dismiss(history("one").contentKey)
        verify(repository,never()).delete(any())
        flow.value=listOf(history("one",true).copy(dismissedAtMs=10))
        advanceUntilIdle()
        assertThat(vm.state.value.watching).isEmpty()
        assertThat(vm.state.value.history).hasSize(1)
    }
    @Test fun noUpcomingEpisodeIsPresentedAndHidingStremioClearsShelf() = runTest(dispatcher) {
        val access=FakeStremioContentAccess()
        val (vm,_,_)=setup(listOf(history("two",true,2),history("one",true)),access)
        advanceUntilIdle()
        assertThat(vm.state.value.watching).isEmpty()
        assertThat(vm.state.value.history).hasSize(2)
        access.setState(StremioVisibilityState(hidden=true));advanceUntilIdle()
        assertThat(vm.state.value.history).isEmpty()
    }
}
