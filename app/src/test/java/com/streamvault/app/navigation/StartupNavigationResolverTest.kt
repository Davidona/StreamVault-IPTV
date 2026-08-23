package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.CombinedM3uProfile
import com.streamvault.domain.model.CombinedM3uProfileMember
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.CombinedM3uRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.model.VirtualCategoryIds
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class StartupNavigationResolverTest {
    private val preferencesRepository = mock<PreferencesRepository>()
    private val combinedM3uRepository = mock<CombinedM3uRepository>()
    private val favoriteRepository = mock<FavoriteRepository>()
    private val playbackHistoryRepository = mock<PlaybackHistoryRepository>()
    private val channelRepository = mock<ChannelRepository>()
    private val providerRepository = mock<ProviderRepository>()
    private val resolver = StartupNavigationResolver(
        preferencesRepository = preferencesRepository,
        combinedM3uRepository = combinedM3uRepository,
        favoriteRepository = favoriteRepository,
        playbackHistoryRepository = playbackHistoryRepository,
        channelRepository = channelRepository,
        providerRepository = providerRepository
    )

    @Test
    fun homeReturnsTypedLandingWithoutPlayer() = runTest {
        val result = resolver.resolve(AppLandingDestination.HOME)

        assertThat(result.destination).isEqualTo(com.streamvault.core.navigation.AppDestination.Home)
        assertThat(result.playerRequest).isNull()
    }

    @Test
    fun firstFavoriteProducesVirtualLiveRequestReturningToLive() = runTest {
        whenever(preferencesRepository.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.ProviderSource(7L))
        )
        whenever(favoriteRepository.getFavorites(7L, ContentType.LIVE)).thenReturn(
            flowOf(listOf(Favorite(providerId = 7L, contentId = 42L, contentType = ContentType.LIVE)))
        )
        whenever(preferencesRepository.getHiddenChannelIds(7L)).thenReturn(flowOf(emptySet()))
        whenever(channelRepository.getChannel(42L)).thenReturn(
            Channel(
                id = 42L,
                name = "News",
                streamUrl = "https://example.com/live.m3u8",
                providerId = 7L
            )
        )

        val result = resolver.resolve(AppLandingDestination.FIRST_FAVORITE_LIVE)

        assertThat(result.destination).isEqualTo(com.streamvault.core.navigation.AppDestination.LiveTv())
        assertThat(result.playerRequest?.internalId).isEqualTo(42L)
        assertThat(result.playerRequest?.categoryId).isEqualTo(VirtualCategoryIds.FAVORITES)
        assertThat(result.playerRequest?.returnDestination)
            .isEqualTo(com.streamvault.core.navigation.AppDestination.LiveTv())
    }

    @Test
    fun lastWatchedSelectsMostRecentLiveChannel() = runTest {
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.ProviderSource(7L))
        )
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(7L, 24)).thenReturn(
            flowOf(
                listOf(
                    history(41L, 100L),
                    history(42L, 200L)
                )
            )
        )
        whenever(preferencesRepository.getHiddenChannelIds(7L)).thenReturn(flowOf(emptySet()))
        whenever(channelRepository.getChannel(42L)).thenReturn(channel(42L, 7L))

        val result = resolver.resolve(AppLandingDestination.LAST_WATCHED_LIVE)

        assertThat(result.playerRequest?.internalId).isEqualTo(42L)
    }

    @Test
    fun hiddenFavoriteIsSkippedWhenNoVisibleCandidateRemains() = runTest {
        whenever(preferencesRepository.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.ProviderSource(7L))
        )
        whenever(favoriteRepository.getFavorites(7L, ContentType.LIVE)).thenReturn(
            flowOf(listOf(Favorite(providerId = 7L, contentId = 42L, contentType = ContentType.LIVE)))
        )
        whenever(preferencesRepository.getHiddenChannelIds(7L)).thenReturn(flowOf(setOf(42L)))
        whenever(channelRepository.getChannel(42L)).thenReturn(channel(42L, 7L))

        assertThat(resolver.resolve(AppLandingDestination.FIRST_FAVORITE_LIVE).playerRequest).isNull()
    }

    @Test
    fun combinedSourceAcceptsOnlyEnabledMembersAndCarriesProfileId() = runTest {
        whenever(preferencesRepository.showFavoritesCategory).thenReturn(flowOf(true))
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.CombinedM3uSource(99L))
        )
        whenever(combinedM3uRepository.getProfile(99L)).thenReturn(
            CombinedM3uProfile(
                id = 99L,
                name = "Combined",
                members = listOf(
                    CombinedM3uProfileMember(profileId = 99L, providerId = 7L, priority = 0, enabled = true),
                    CombinedM3uProfileMember(profileId = 99L, providerId = 8L, priority = 1, enabled = true),
                    CombinedM3uProfileMember(profileId = 99L, providerId = 9L, priority = 2, enabled = false)
                )
            )
        )
        whenever(favoriteRepository.getFavorites(listOf(7L, 8L), ContentType.LIVE)).thenReturn(
            flowOf(
                listOf(
                    Favorite(providerId = 9L, contentId = 90L, contentType = ContentType.LIVE),
                    Favorite(providerId = 8L, contentId = 80L, contentType = ContentType.LIVE)
                )
            )
        )
        whenever(preferencesRepository.getHiddenChannelIds(7L)).thenReturn(flowOf(emptySet()))
        whenever(preferencesRepository.getHiddenChannelIds(8L)).thenReturn(flowOf(emptySet()))
        whenever(channelRepository.getChannel(80L)).thenReturn(channel(80L, 8L))

        val result = resolver.resolve(AppLandingDestination.FIRST_FAVORITE_LIVE)

        assertThat(result.playerRequest?.internalId).isEqualTo(80L)
        assertThat(result.playerRequest?.providerId).isEqualTo(8L)
        assertThat(result.playerRequest?.combinedProfileId).isEqualTo(99L)
    }

    @Test
    fun noActiveSourceOrProviderReturnsTypedLandingWithoutPlayer() = runTest {
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(flowOf(null))
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(null))

        val result = resolver.resolve(AppLandingDestination.LAST_WATCHED_LIVE)

        assertThat(result.destination).isEqualTo(com.streamvault.core.navigation.AppDestination.LiveTv())
        assertThat(result.playerRequest).isNull()
    }

    @Test
    fun missingChannelCandidateProducesNoPlayerRequest() = runTest {
        whenever(combinedM3uRepository.getActiveLiveSource()).thenReturn(
            flowOf(ActiveLiveSource.ProviderSource(7L))
        )
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(7L, 24)).thenReturn(
            flowOf(listOf(history(42L, 200L)))
        )
        whenever(preferencesRepository.getHiddenChannelIds(7L)).thenReturn(flowOf(emptySet()))
        whenever(channelRepository.getChannel(42L)).thenReturn(null)

        assertThat(resolver.resolve(AppLandingDestination.LAST_WATCHED_LIVE).playerRequest).isNull()
    }

    private fun channel(id: Long, providerId: Long) = Channel(
        id = id,
        name = "Channel $id",
        streamUrl = "https://example.com/$id.m3u8",
        providerId = providerId
    )

    private fun history(contentId: Long, lastWatchedAt: Long) = PlaybackHistory(
        contentId = contentId,
        contentType = ContentType.LIVE,
        providerId = 7L,
        title = "Channel $contentId",
        streamUrl = "https://example.com/$contentId.m3u8",
        lastWatchedAt = lastWatchedAt
    )
}
