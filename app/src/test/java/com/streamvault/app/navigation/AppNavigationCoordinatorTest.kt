package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.ExternalNavigationRequest
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
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

    private fun coordinator(
        ids: NavigationCommandIdSource,
        startupLanding: AppLandingDestination = AppLandingDestination.HOME
    ): AppNavigationCoordinator {
        val preferencesRepository = mock<PreferencesRepository>()
        whenever(preferencesRepository.appLandingDestination)
            .thenReturn(flowOf(startupLanding))
        whenever(preferencesRepository.appTopLevelDestinations)
            .thenReturn(flowOf(AppTopLevelDestination.defaultOrder))
        whenever(preferencesRepository.showFavoritesCategory).thenReturn(flowOf(true))
        val providerRepository = mock<ProviderRepository>()
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(null))
        val combinedM3uRepository = mock<CombinedM3uRepository>()
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.ProviderSource(7L))
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
