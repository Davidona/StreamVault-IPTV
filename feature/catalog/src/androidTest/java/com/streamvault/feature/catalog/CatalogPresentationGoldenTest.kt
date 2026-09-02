package com.streamvault.feature.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.core.ui.components.shell.AppHeroHeader
import com.streamvault.core.ui.components.shell.AppSectionHeader
import com.streamvault.core.ui.components.shell.ContentMetadataStrip
import com.streamvault.core.ui.components.shell.StatusPill
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.feature.catalog.presentation.components.ChannelCard
import com.streamvault.feature.catalog.presentation.components.MovieCard
import com.streamvault.feature.catalog.presentation.components.SeriesCard
import com.streamvault.feature.catalog.test.assertAgainstGolden
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogPresentationGoldenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dashboard_route_matchesGolden() = captureGolden("route_dashboard_default") {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            AppHeroHeader(
                title = "Your Library, Ready",
                subtitle = "One place for live shortcuts, recent progress, and provider health.",
                eyebrow = "Dashboard",
                footer = { ContentMetadataStrip(values = listOf("126 live", "18 to resume", "2 alerts")) }
            )
            AppSectionHeader(title = "Continue Watching", subtitle = "Recent items kept within reach.")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MovieCard(movie = movie(), onClick = {})
                SeriesCard(series = series(), onClick = {})
                ChannelCard(channel = channel(), nowMs = 0L, onClick = {})
            }
        }
    }

    @Test
    fun movies_route_matchesGolden() = captureGolden("route_movies_landing") {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            AppHeroHeader(title = "Movies", subtitle = "Curated shelves and category jumps.")
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                MovieCard(movie = movie(), onClick = {})
                MovieCard(movie = movie().copy(id = 2L, name = "Coastline"), onClick = {})
            }
        }
    }

    @Test
    fun series_detail_route_matchesGolden() = captureGolden("route_series_detail") {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            AppHeroHeader(title = "Series Detail", subtitle = "Season browsing and progress-aware episodes.")
            AppSectionHeader(title = "Season 1", subtitle = "8 episodes available")
            SeriesCard(series = series(), onClick = {})
        }
    }

    @Test
    fun search_route_matchesGolden() = captureGolden("route_search_results") {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AppHeroHeader(title = "Search", subtitle = "Unified results across live, movies, and series.")
            SearchInput(value = "night", onValueChange = {}, placeholder = "Search everything")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(label = "Live 12")
                StatusPill(label = "Movies 8")
                StatusPill(label = "Series 4")
            }
        }
    }

    private fun captureGolden(name: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        composeRule.setContent {
            StreamVaultTheme {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().padding(24.dp).testTag("golden")) {
                        content()
                    }
                }
            }
        }
        composeRule.onNodeWithTag("golden").assertAgainstGolden(name)
    }

    private fun movie() = Movie(id = 1L, name = "The Night Shift", year = "2026", rating = 8.8f)
    private fun series() = Series(id = 1L, name = "The Long Road", releaseDate = "2026", rating = 8.5f)
    private fun channel() = Channel(id = 1L, name = "Headline News", streamUrl = "https://example.test/live.m3u8")
}
