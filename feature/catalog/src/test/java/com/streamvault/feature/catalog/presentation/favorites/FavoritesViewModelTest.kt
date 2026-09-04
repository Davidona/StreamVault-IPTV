package com.streamvault.feature.catalog.presentation.favorites

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.feature.catalog.presentation.CatalogMainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class FavoritesViewModelTest {

    @get:Rule
    val mainDispatcherRule = CatalogMainDispatcherRule()

    private val favoriteRepository: FavoriteRepository = mock()
    private val channelRepository: ChannelRepository = mock()
    private val movieRepository: MovieRepository = mock()
    private val seriesRepository: SeriesRepository = mock()
    private val playbackHistoryRepository: PlaybackHistoryRepository = mock()
    private val providerRepository: ProviderRepository = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val getContinueWatching: com.streamvault.domain.usecase.GetContinueWatching = mock()
    private val providers = MutableStateFlow<List<LegacyProvider>>(emptyList())
    private val activeProvider = MutableStateFlow<LegacyProvider?>(null)
    private val favorites = MutableStateFlow<List<Favorite>>(emptyList())
    private val appContext: Context = mock()

    private lateinit var viewModel: FavoritesViewModel

    @Before
    fun setUp() {
        whenever(providerRepository.getProviders()).thenReturn(providers)
        whenever(providerRepository.getActiveProvider()).thenReturn(activeProvider)
        whenever(favoriteRepository.getFavorites(any<List<Long>>(), anyOrNull())).thenReturn(favorites)
        whenever(favoriteRepository.getGroups(any<List<Long>>(), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getGroupFavoriteCounts(any<List<Long>>(), any()))
            .thenReturn(flowOf(emptyMap()))
        whenever(preferencesRepository.promotedLiveGroupIds).thenReturn(flowOf(emptySet()))
        whenever(playbackHistoryRepository.getRecentlyWatched(any())).thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(any(), any())).thenReturn(flowOf(emptyList()))
        whenever(getContinueWatching.invoke(any<Set<Long>>(), any(), any(), any()))
            .thenReturn(flowOf(com.streamvault.domain.usecase.ContinueWatchingResult.Items(emptyList())))
        whenever(appContext.getString(any())).thenAnswer { "resource-${it.arguments[0]}" }
        whenever(appContext.getString(any(), any())).thenAnswer { "resource-${it.arguments[0]}" }

        viewModel = FavoritesViewModel(
            appContext = appContext,
            favoriteRepository = favoriteRepository,
            channelRepository = channelRepository,
            movieRepository = movieRepository,
            seriesRepository = seriesRepository,
            playbackHistoryRepository = playbackHistoryRepository,
            providerRepository = providerRepository,
            preferencesRepository = preferencesRepository,
            getContinueWatching = getContinueWatching,
        )
    }

    @Test
    fun `preset selection applies the matching content filter`() {
        viewModel.selectPreset(SavedLibraryPreset.MOVIES)

        assertThat(viewModel.uiState.value.selectedPreset).isEqualTo(SavedLibraryPreset.MOVIES)
        assertThat(viewModel.uiState.value.selectedFilter).isEqualTo(SavedLibraryFilter.MOVIE)
    }

    @Test
    fun `provider scope and sort selections remain independent`() {
        viewModel.selectProviderScope(SavedLibraryProviderScope.ALL_PROVIDERS)
        viewModel.selectSort(SavedLibrarySort.TITLE)

        assertThat(viewModel.uiState.value.selectedProviderScope)
            .isEqualTo(SavedLibraryProviderScope.ALL_PROVIDERS)
        assertThat(viewModel.uiState.value.selectedSort).isEqualTo(SavedLibrarySort.TITLE)
    }

    @Test
    fun `canceling reorder restores the original order without persisting the preview`() = runTest {
        val provider = LegacyProvider(
            id = 7L,
            name = "Fixture",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "http://fixture.test"
        )
        val firstFavorite = Favorite(
            id = 101L,
            providerId = provider.id,
            contentId = 1001L,
            contentType = ContentType.MOVIE,
            position = 0
        )
        val secondFavorite = firstFavorite.copy(
            id = 102L,
            contentId = 1002L,
            position = 1
        )
        whenever(movieRepository.getMoviesByIds(any())).thenReturn(
            flowOf(
                listOf(
                    Movie(id = firstFavorite.contentId, name = "First", providerId = provider.id),
                    Movie(id = secondFavorite.contentId, name = "Second", providerId = provider.id)
                )
            )
        )
        whenever(favoriteRepository.reorderFavorites(any())).thenReturn(Result.success(Unit))

        providers.value = listOf(provider)
        activeProvider.value = provider
        favorites.value = listOf(firstFavorite, secondFavorite)
        viewModel = FavoritesViewModel(
            appContext = appContext,
            favoriteRepository = favoriteRepository,
            channelRepository = channelRepository,
            movieRepository = movieRepository,
            seriesRepository = seriesRepository,
            playbackHistoryRepository = playbackHistoryRepository,
            providerRepository = providerRepository,
            preferencesRepository = preferencesRepository,
            getContinueWatching = getContinueWatching,
        )
        advanceUntilIdle()

        val originalOrder = viewModel.uiState.value.sections.single().items.map { it.favorite.id }
        val firstItem = viewModel.uiState.value.sections.single().items.first()
        viewModel.enterReorderMode("global", firstItem)
        viewModel.moveItem(1)
        assertThat(viewModel.uiState.value.sections.single().items.map { it.favorite.id })
            .containsExactly(102L, 101L)
            .inOrder()

        viewModel.cancelReorderMode()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.sections.single().items.map { it.favorite.id })
            .containsExactlyElementsIn(originalOrder)
            .inOrder()
        assertThat(viewModel.uiState.value.isReorderMode).isFalse()
        verify(favoriteRepository, never()).reorderFavorites(any())
    }

    @Test
    fun `saving reorder sends the preview order and exits edit mode`() = runTest {
        val provider = LegacyProvider(
            id = 7L,
            name = "Fixture",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "http://fixture.test"
        )
        val firstFavorite = Favorite(
            id = 101L,
            providerId = provider.id,
            contentId = 1001L,
            contentType = ContentType.MOVIE,
            position = 0
        )
        val secondFavorite = firstFavorite.copy(
            id = 102L,
            contentId = 1002L,
            position = 1
        )
        whenever(movieRepository.getMoviesByIds(any())).thenReturn(
            flowOf(
                listOf(
                    Movie(id = firstFavorite.contentId, name = "First", providerId = provider.id),
                    Movie(id = secondFavorite.contentId, name = "Second", providerId = provider.id)
                )
            )
        )
        whenever(favoriteRepository.reorderFavorites(any())).thenReturn(Result.success(Unit))

        providers.value = listOf(provider)
        activeProvider.value = provider
        favorites.value = listOf(firstFavorite, secondFavorite)
        viewModel = FavoritesViewModel(
            appContext = appContext,
            favoriteRepository = favoriteRepository,
            channelRepository = channelRepository,
            movieRepository = movieRepository,
            seriesRepository = seriesRepository,
            playbackHistoryRepository = playbackHistoryRepository,
            providerRepository = providerRepository,
            preferencesRepository = preferencesRepository,
            getContinueWatching = getContinueWatching,
        )
        advanceUntilIdle()

        val firstItem = viewModel.uiState.value.sections.single().items.first()
        viewModel.enterReorderMode("global", firstItem)
        viewModel.moveItem(1)
        viewModel.saveReorder()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isReorderMode).isFalse()
        verify(favoriteRepository).reorderFavorites(listOf(secondFavorite, firstFavorite))
    }
}
