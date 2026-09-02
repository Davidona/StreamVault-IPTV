package com.streamvault.feature.catalog.presentation.favorites

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.feature.catalog.presentation.CatalogMainDispatcherRule
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

    private lateinit var viewModel: FavoritesViewModel

    @Before
    fun setUp() {
        whenever(providerRepository.getProviders()).thenReturn(flowOf(emptyList()))
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(null))
        whenever(favoriteRepository.getFavorites(any<List<Long>>(), anyOrNull())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getGroups(any<List<Long>>(), any())).thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getGroupFavoriteCounts(any<List<Long>>(), any()))
            .thenReturn(flowOf(emptyMap()))
        whenever(preferencesRepository.promotedLiveGroupIds).thenReturn(flowOf(emptySet()))
        whenever(playbackHistoryRepository.getRecentlyWatched(any())).thenReturn(flowOf(emptyList()))

        viewModel = FavoritesViewModel(
            appContext = RuntimeEnvironment.getApplication() as Context,
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
}
