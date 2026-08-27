package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.ExternalNavigationRequest
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class AppNavigationCoordinatorTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun commandIsClearedOnlyAfterMatchingAcknowledgement() = runTest {
        val ids = sequenceOf(10L, 11L).iterator()
        val coordinator = coordinator(NavigationCommandIdSource { ids.next() })
        coordinator.submitExternalRequest(ExternalNavigationRequest.Search("news"))

        val pending = coordinator.pendingCommand.value
        assertThat(pending?.id).isEqualTo(10L)
        coordinator.acknowledge(11L)
        assertThat(coordinator.pendingCommand.value).isEqualTo(pending)
        coordinator.acknowledge(10L)
        assertThat(coordinator.pendingCommand.value).isNull()
    }

    @Test
    fun queuedCommandsArePublishedInSubmissionOrder() = runTest {
        val ids = sequenceOf(10L, 11L).iterator()
        val coordinator = coordinator(NavigationCommandIdSource { ids.next() })
        coordinator.submitExternalRequest(ExternalNavigationRequest.Search("news"))
        coordinator.submitExternalRequest(ExternalNavigationRequest.Destination(AppDestination.Home))

        val first = coordinator.pendingCommand.value
        assertThat(first?.id).isEqualTo(10L)
        coordinator.acknowledge(10L)
        assertThat(coordinator.pendingCommand.value?.id).isEqualTo(11L)
        assertThat(coordinator.pendingCommand.value?.command)
            .isEqualTo(
                ExternalNavigationRequest.Destination(AppDestination.Home).toNavigationCommand()
            )
    }

    @Test
    fun repeatedStartupRequestsSubmitOnlyOneCommandAndOpenPlayerAfterLiveResumes() = runTest {
        val ids = sequenceOf(10L, 11L, 12L).iterator()
        val coordinator = coordinator(
            NavigationCommandIdSource { ids.next() },
            AppLandingDestination.FIRST_FAVORITE_LIVE
        )
        coordinator.requestStartupNavigation(AppDestination.Welcome)
        coordinator.requestStartupNavigation(AppDestination.Welcome)

        assertThat(coordinator.pendingCommand.value?.id).isEqualTo(10L)
        coordinator.acknowledge(10L)
        coordinator.onDestinationResumed(AppDestination.Home)
        assertThat(coordinator.pendingCommand.value).isNull()
        coordinator.onDestinationResumed(AppDestination.LiveTv())
        assertThat(coordinator.pendingCommand.value?.id).isEqualTo(11L)
        coordinator.onDestinationResumed(AppDestination.LiveTv())
        assertThat(coordinator.pendingCommand.value?.id).isEqualTo(11L)
    }

    @Test
    fun liveLandingResumedBeforeStartupAcknowledgementStillOpensPlayer() = runTest {
        val ids = sequenceOf(10L, 11L).iterator()
        val coordinator = coordinator(
            ids = NavigationCommandIdSource { ids.next() },
            startupLanding = AppLandingDestination.FIRST_FAVORITE_LIVE
        )
        runCurrent()

        coordinator.requestStartupNavigation(AppDestination.Welcome)
        coordinator.onDestinationResumed(AppDestination.LiveTv())
        coordinator.acknowledge(10L)

        assertThat(coordinator.pendingCommand.value?.id).isEqualTo(11L)
        assertThat(coordinator.pendingCommand.value?.command)
            .isInstanceOf(com.streamvault.core.navigation.NavigationCommand.OpenPlayer::class.java)
    }

    @Test
    fun liveLandingBecomesNavigableBeforeOptionalPlayerLookupCompletes() = runTest {
        val activeLiveSource = CompletableDeferred<ActiveLiveSource?>()
        val coordinator = coordinator(
            ids = NavigationCommandIdSource { 10L },
            startupLanding = AppLandingDestination.FIRST_FAVORITE_LIVE,
            activeLiveSource = kotlinx.coroutines.flow.flow {
                emit(activeLiveSource.await())
            }
        )

        runCurrent()

        assertThat(coordinator.state.value.startupTarget?.destination)
            .isEqualTo(AppDestination.LiveTv())
        activeLiveSource.complete(null)
    }

    @Test
    fun deferredPlayerLookupCompletesAfterLiveResumesAndOpensPlayer() = runTest {
        val ids = sequenceOf(10L, 11L).iterator()
        val activeLiveSource = CompletableDeferred<ActiveLiveSource?>()
        val coordinator = coordinator(
            ids = NavigationCommandIdSource { ids.next() },
            startupLanding = AppLandingDestination.FIRST_FAVORITE_LIVE,
            activeLiveSource = kotlinx.coroutines.flow.flow {
                emit(activeLiveSource.await())
            }
        )
        runCurrent()

        coordinator.requestStartupNavigation(AppDestination.Welcome)
        coordinator.acknowledge(10L)
        coordinator.onDestinationResumed(AppDestination.LiveTv())
        assertThat(coordinator.pendingCommand.value).isNull()

        activeLiveSource.complete(ActiveLiveSource.ProviderSource(7L))
        runCurrent()

        assertThat(coordinator.pendingCommand.value?.id).isEqualTo(11L)
        assertThat(coordinator.pendingCommand.value?.command)
            .isInstanceOf(com.streamvault.core.navigation.NavigationCommand.OpenPlayer::class.java)
    }

    @Test
    fun unifiedCatalogNavigationRewritesMoviesRequestToVod() = runTest {
        val coordinator = coordinator(
            ids = NavigationCommandIdSource { 10L },
            activeProvider = kotlinx.coroutines.flow.flowOf(
                LegacyProvider(
                    id = 7L,
                    name = "Provider",
                    type = ProviderType.M3U,
                    serverUrl = "https://example.com",
                    catalogLayout = CatalogLayout.UNIFIED_VOD
                )
            )
        )
        runCurrent()

        coordinator.requestTopLevelNavigation(AppDestination.Movies)

        val command = coordinator.pendingCommand.value?.command
        assertThat(command).isInstanceOf(com.streamvault.core.navigation.NavigationCommand.Navigate::class.java)
        assertThat((command as com.streamvault.core.navigation.NavigationCommand.Navigate).destination)
            .isEqualTo(AppDestination.Vod)
    }

    private fun coordinator(
        ids: NavigationCommandIdSource,
        startupLanding: AppLandingDestination = AppLandingDestination.HOME,
        activeLiveSource: kotlinx.coroutines.flow.Flow<ActiveLiveSource?> =
            flowOf(ActiveLiveSource.ProviderSource(7L)),
        activeProvider: kotlinx.coroutines.flow.Flow<LegacyProvider?> =
            flowOf(null)
    ): AppNavigationCoordinator {
        val preferencesRepository = mock<PreferencesRepository>()
        whenever(preferencesRepository.appLandingDestination)
            .thenReturn(flowOf(startupLanding))
        whenever(preferencesRepository.appTopLevelDestinations)
            .thenReturn(flowOf(AppTopLevelDestination.defaultOrder))
        whenever(preferencesRepository.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(preferencesRepository.getLastSplitCatalogType(7L)).thenReturn(flowOf(ContentType.MOVIE))
        val providerRepository = mock<ProviderRepository>()
        whenever(providerRepository.getActiveProvider()).thenReturn(activeProvider)
        val combinedM3uRepository = mock<CombinedM3uRepository>()
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            activeLiveSource
        )
        val favoriteRepository = mock<FavoriteRepository>()
        whenever(favoriteRepository.getFavorites(7L, ContentType.LIVE)).thenReturn(
            flowOf(listOf(Favorite(providerId = 7L, contentId = 42L, contentType = ContentType.LIVE)))
        )
        whenever(preferencesRepository.getHiddenChannelIds(7L)).thenReturn(flowOf(emptySet()))
        val channelRepository = mock<ChannelRepository>()
        runBlocking {
            whenever(channelRepository.getChannel(42L)).thenReturn(
                Channel(
                    id = 42L,
                    name = "News",
                    streamUrl = "https://example.com/live.m3u8",
                    providerId = 7L
                )
            )
        }
        val startupResolver = StartupNavigationResolver(
            preferencesRepository = preferencesRepository,
            combinedM3uRepository = combinedM3uRepository,
            favoriteRepository = favoriteRepository,
            playbackHistoryRepository = mock<PlaybackHistoryRepository>(),
            channelRepository = channelRepository,
            providerRepository = providerRepository
        )
        return AppNavigationCoordinator(
            commandIds = ids,
            startupResolver = startupResolver,
            preferencesRepository = preferencesRepository,
            providerRepository = providerRepository
        )
    }
}
