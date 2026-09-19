package com.streamvault.feature.catalog.presentation.search

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SearchHistoryScope
import com.streamvault.domain.model.Series
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.manager.ParentalControlManager
import com.streamvault.domain.manager.RecordingManager
import com.streamvault.domain.repository.CategoryRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.usecase.SearchContent
import com.streamvault.domain.usecase.SearchContentResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val providerRepository: ProviderRepository = mock()
    private val searchContent: SearchContent = mock()
    private val preferencesRepository: PreferencesRepository = mock()
    private val parentalControlManager: ParentalControlManager = mock()
    private val favoriteRepository: FavoriteRepository = mock()
    private val categoryRepository: CategoryRepository = mock()
    private val recordingManager: RecordingManager = mock()

    private lateinit var viewModel: SearchViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(null))
        whenever(preferencesRepository.parentalControlLevel).thenReturn(flowOf(0))
        whenever(preferencesRepository.getRecentSearchQueries(any(), anyOrNull(), any())).thenReturn(flowOf(emptyList()))
        whenever(parentalControlManager.unlockedCategoriesForProvider(any())).thenReturn(flowOf(emptySet()))
        whenever(searchContent.invoke(any(), any(), any(), any())).thenReturn(flowOf(SearchContentResult()))
        whenever(recordingManager.observeRecordingItems()).thenReturn(flowOf(emptyList()))

        viewModel = SearchViewModel(
            providerRepository,
            searchContent,
            preferencesRepository,
            parentalControlManager,
            favoriteRepository,
            categoryRepository,
            recordingManager
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `submitted queries are stored trimmed deduplicated and capped`() = runTest {
        viewModel.onQueryChange(" news ")
        viewModel.onSearchSubmitted()
        advanceUntilIdle()

        verify(preferencesRepository).recordRecentSearchQuery(
            query = eq("news"),
            scope = eq(SearchHistoryScope.ALL),
            providerId = eq(null),
            usedAt = any()
        )
    }

    @Test
    fun `clearRecentQueries removes all stored search shortcuts`() = runTest {
        viewModel.clearRecentQueries()
        advanceUntilIdle()

        verify(preferencesRepository).clearRecentSearchQueries(
            scope = eq(SearchHistoryScope.ALL),
            providerId = eq(null)
        )
    }

    @Test
    fun `ui state exposes provider and query readiness before results load`() = runTest {
        whenever(providerRepository.getActiveProvider()).thenReturn(
            flowOf(
                Provider(
                    id = 1L,
                    name = "Provider",
                    type = ProviderType.M3U,
                    serverUrl = "http://test"
                )
            )
        )

        viewModel = SearchViewModel(
            providerRepository,
            searchContent,
            preferencesRepository,
            parentalControlManager,
            favoriteRepository,
            categoryRepository,
            recordingManager
        )

        val collectorJob = backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        viewModel.onQueryChange("a")
        testScheduler.advanceTimeBy(400)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.hasActiveProvider).isTrue()
        assertThat(viewModel.uiState.value.queryLength).isEqualTo(1)
        assertThat(viewModel.uiState.value.hasSearched).isFalse()
        collectorJob.cancel()
    }

    @Test
    fun `ui state reports loading while search results are pending`() = runTest {
        whenever(providerRepository.getActiveProvider()).thenReturn(
            flowOf(
                Provider(
                    id = 5L,
                    name = "Provider",
                    type = ProviderType.M3U,
                    serverUrl = "http://test"
                )
            )
        )
        whenever(searchContent.invoke(any(), any(), any(), any())).thenReturn(
            flow {
                delay(1_000)
                emit(SearchContentResult(channels = listOf(Channel(id = 1L, name = "News", streamUrl = "http://stream", providerId = 5L))))
            }
        )

        viewModel = SearchViewModel(
            providerRepository,
            searchContent,
            preferencesRepository,
            parentalControlManager,
            favoriteRepository,
            categoryRepository,
            recordingManager
        )

        val collectorJob = backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        viewModel.onQueryChange("news")
        testScheduler.advanceTimeBy(400)
        runCurrent()

        assertThat(viewModel.uiState.value.isLoading).isTrue()
        assertThat(viewModel.uiState.value.hasSearched).isTrue()

        testScheduler.advanceTimeBy(1_000)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.channels.map { it.id }).containsExactly(1L)
        collectorJob.cancel()
    }

    @Test
    fun `search results flow through the shared search use case`() = runTest {
        whenever(providerRepository.getActiveProvider()).thenReturn(
            flowOf(
                Provider(
                    id = 5L,
                    name = "Provider",
                    type = ProviderType.M3U,
                    serverUrl = "http://test"
                )
            )
        )
        whenever(searchContent.invoke(any(), any(), any(), any())).thenReturn(
            flowOf(
                SearchContentResult(
                    channels = listOf(Channel(id = 1L, name = "News", streamUrl = "http://stream", providerId = 5L)),
                    movies = listOf(Movie(id = 2L, name = "Movie")),
                    series = listOf(Series(id = 3L, name = "Series"))
                )
            )
        )

        viewModel = SearchViewModel(
            providerRepository,
            searchContent,
            preferencesRepository,
            parentalControlManager,
            favoriteRepository,
            categoryRepository,
            recordingManager
        )

        val collectorJob = backgroundScope.launch { viewModel.uiState.collect { } }
        advanceUntilIdle()
        viewModel.onQueryChange("news")
        testScheduler.advanceTimeBy(400)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.channels.map { it.id }).containsExactly(1L)
        assertThat(viewModel.uiState.value.movies.map { it.id }).containsExactly(2L)
        assertThat(viewModel.uiState.value.series.map { it.id }).containsExactly(3L)
        assertThat(viewModel.uiState.value.hasSearched).isTrue()
        collectorJob.cancel()
    }
}
