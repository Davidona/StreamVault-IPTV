package com.streamvault.feature.catalog.presentation.favorites

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.AppDestination
import com.streamvault.data.local.dao.ChannelPreferenceDao
import com.streamvault.data.local.dao.SearchHistoryDao
import com.streamvault.data.preferences.PreferencesCorruptionRecovery
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Favorite
import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.repository.FavoriteRepository
import com.streamvault.domain.repository.MovieRepository
import com.streamvault.domain.repository.PlaybackHistoryRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.SeriesRepository
import com.streamvault.domain.usecase.GetContinueWatching
import com.streamvault.feature.catalog.api.CatalogScaffoldContent
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@RunWith(AndroidJUnit4::class)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class FavoritesScreenHostTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun directHostRendersFavoritesAndBackCancelsReorder() {
        val provider = LegacyProvider(
            id = 7L,
            name = "Fixture provider",
            type = ProviderType.XTREAM_CODES,
            serverUrl = "http://fixture.test"
        )
        val first = Favorite(
            id = 101L,
            providerId = provider.id,
            contentId = 1001L,
            contentType = ContentType.MOVIE,
            position = 0
        )
        val second = first.copy(
            id = 102L,
            contentId = 1002L,
            position = 1
        )
        val favoriteRepository: FavoriteRepository = mock()
        val channelRepository: ChannelRepository = mock()
        val movieRepository: MovieRepository = mock()
        val seriesRepository: SeriesRepository = mock()
        val playbackHistoryRepository: PlaybackHistoryRepository = mock()
        val providerRepository: ProviderRepository = mock()
        val channelPreferenceDao: ChannelPreferenceDao = mock()
        val searchHistoryDao: SearchHistoryDao = mock()
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val preferencesRepository = PreferencesRepository(
            context = appContext,
            channelPreferenceDao = channelPreferenceDao,
            searchHistoryDao = searchHistoryDao,
            corruptionRecovery = PreferencesCorruptionRecovery(appContext)
        )
        whenever(providerRepository.getProviders()).thenReturn(flowOf(listOf(provider)))
        whenever(providerRepository.getActiveProvider()).thenReturn(flowOf(provider))
        whenever(favoriteRepository.getFavorites(any<List<Long>>(), anyOrNull()))
            .thenReturn(flowOf(listOf(first, second)))
        whenever(favoriteRepository.getGroups(any<List<Long>>(), any()))
            .thenReturn(flowOf(emptyList()))
        whenever(favoriteRepository.getGroupFavoriteCounts(any<List<Long>>(), any()))
            .thenReturn(flowOf(emptyMap()))
        whenever(movieRepository.getMoviesByIds(any<List<Long>>())).thenReturn(
            flowOf(
                listOf(
                    Movie(id = 1001L, name = "First fixture movie", providerId = provider.id),
                    Movie(id = 1002L, name = "Second fixture movie", providerId = provider.id)
                )
            )
        )
        whenever(playbackHistoryRepository.getRecentlyWatched(any()))
            .thenReturn(flowOf(emptyList()))
        whenever(playbackHistoryRepository.getRecentlyWatchedByProvider(any(), any()))
            .thenReturn(flowOf(emptyList()))
        val viewModel = FavoritesViewModel(
            appContext = appContext,
            favoriteRepository = favoriteRepository,
            channelRepository = channelRepository,
            movieRepository = movieRepository,
            seriesRepository = seriesRepository,
            playbackHistoryRepository = playbackHistoryRepository,
            providerRepository = providerRepository,
            preferencesRepository = preferencesRepository,
            getContinueWatching = GetContinueWatching(playbackHistoryRepository)
        )

        composeRule.setContent {
            MaterialTheme {
                FavoritesScreen(
                    onItemClick = {},
                    onHistoryClick = {},
                    currentDestination = AppDestination.Home,
                    onDestinationRequested = {},
                    scaffold = testScaffold,
                    viewModel = viewModel
                )
            }
        }

        composeRule.waitUntil(timeoutMillis = 5_000) {
            viewModel.uiState.value.sections.singleOrNull()?.items?.size == 2
        }
        composeRule.onNodeWithText("Saved").assertIsDisplayed()

        val firstUiModel = viewModel.uiState.value.sections.single().items.first()
        viewModel.enterReorderMode("global", firstUiModel)
        composeRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.isReorderMode }
        composeRule.onNodeWithText("Reorder mode").assertIsDisplayed()
        composeRule.activity.onBackPressedDispatcher.onBackPressed()

        composeRule.waitUntil(timeoutMillis = 5_000) { !viewModel.uiState.value.isReorderMode }
        assertThat(viewModel.uiState.value.isReorderMode).isFalse()
    }

    private companion object {
        val testScaffold: CatalogScaffoldContent = { _, title, _, _, _, _, _, content ->
            Column(modifier = Modifier.fillMaxSize()) {
                androidx.tv.material3.Text(title)
                content()
            }
        }
    }
}
