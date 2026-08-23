package com.streamvault.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.app.navigation.Routes
import com.streamvault.app.ui.components.SearchInput
import com.streamvault.core.ui.components.shell.AppHeroHeader
import com.streamvault.core.ui.components.shell.AppMessageState
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.core.ui.components.shell.AppSectionHeader
import com.streamvault.app.ui.components.shell.BrowseHeroPanel
import com.streamvault.app.ui.components.shell.BrowseSearchLaunchCard
import com.streamvault.app.ui.components.shell.CategoryRailPanel
import com.streamvault.core.ui.components.shell.ContentMetadataStrip
import com.streamvault.app.ui.components.shell.EpisodeRowCard
import com.streamvault.app.ui.components.shell.LibraryBrowseScaffold
import com.streamvault.app.ui.components.shell.LiveChannelRowSurface
import com.streamvault.core.ui.components.shell.LoadMoreCard
import com.streamvault.app.ui.components.shell.MoviePosterCard
import com.streamvault.app.ui.components.shell.SeriesPosterCard
import com.streamvault.core.ui.components.shell.StatusPill
import com.streamvault.core.ui.design.AppColors
import com.streamvault.app.ui.test.TestFixtures
import com.streamvault.app.ui.test.assertAgainstGolden
import com.streamvault.core.ui.theme.StreamVaultTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PremiumRouteGoldenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun dashboard_route_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    AppScreenScaffold(
                        currentRoute = Routes.HOME,
                        onNavigate = {},
                        title = "Your Library, Ready",
                        subtitle = "Pulse IPTV is active. Jump back into live, continue watching, or browse fresh additions."
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                            AppHeroHeader(
                                title = "Tonight's Premium Picks",
                                subtitle = "One place for live shortcuts, recent progress, and provider health.",
                                eyebrow = "Dashboard",
                                actions = {
                                    StatusPill(label = "4K", containerColor = AppColors.Brand)
                                    StatusPill(label = "Saved", containerColor = AppColors.Warning, contentColor = AppColors.Canvas)
                                },
                                footer = {
                                    ContentMetadataStrip(values = listOf("126 live", "18 to resume", "2 alerts"))
                                }
                            )
                            AppSectionHeader(
                                title = "Continue Watching",
                                subtitle = "Recent live channels, movies, and series kept within reach."
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                MoviePosterCard(movie = TestFixtures.movie, modifier = Modifier.width(160.dp).height(240.dp))
                                SeriesPosterCard(series = TestFixtures.series, modifier = Modifier.width(160.dp).height(240.dp))
                                LiveChannelRowSurface(
                                    channel = TestFixtures.liveChannel,
                                    nowMs = TestFixtures.currentProgram.startTime,
                                    onClick = {},
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("route_dashboard_default")
    }

    @Test
    fun live_route_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    LibraryBrowseScaffold(
                        currentRoute = Routes.LIVE_TV,
                        onNavigate = {},
                        title = "Live TV",
                        subtitle = "Browse categories, scan now playing, and jump into multiview-ready channels.",
                        header = {
                            AppHeroHeader(
                                title = TestFixtures.liveChannelName,
                                subtitle = "World Cup Qualifiers is live now. Scan dense rows optimized for large playlists.",
                                eyebrow = "Guide-ready",
                                footer = {
                                    ContentMetadataStrip(values = listOf("Sports", "Catch-up", "105"))
                                }
                            )
                        },
                        railContent = {
                            CategoryRailPanel(
                                title = "Live Groups",
                                searchValue = "",
                                onSearchValueChange = {},
                                searchPlaceholder = "Search categories"
                            ) {
                                TestFixtures.liveCategories.forEach { category ->
                                    item {
                                    StatusPill(
                                        label = "${category.name} ${category.count}",
                                        modifier = Modifier.padding(bottom = 8.dp)
                                    )
                                }
                                }
                            }
                        },
                        content = {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                BrowseSearchLaunchCard(
                                    title = "Search Live TV",
                                    subtitle = "Find channels, categories, or program titles without leaving the live surface.",
                                    onClick = {}
                                )
                                LiveChannelRowSurface(
                                    channel = TestFixtures.liveChannel,
                                    nowMs = TestFixtures.currentProgram.startTime,
                                    onClick = {}
                                )
                                LiveChannelRowSurface(
                                    channel = TestFixtures.liveChannel.copy(
                                        id = 8L,
                                        name = "Headline News HD",
                                        isFavorite = false,
                                        catchUpSupported = false,
                                        currentProgram = TestFixtures.currentProgram.copy(title = "Global Briefing")
                                    ),
                                    nowMs = TestFixtures.currentProgram.startTime,
                                    onClick = {}
                                )
                            }
                        }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("route_live_browse")
    }

    @Test
    fun movies_route_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    LibraryBrowseScaffold(
                        currentRoute = Routes.MOVIES,
                        onNavigate = {},
                        title = "Movies",
                        subtitle = "Curated shelves, fast category jumps, and large-library-safe browsing.",
                        railContent = {
                            CategoryRailPanel(
                                title = "Movie Categories",
                                searchValue = "",
                                onSearchValueChange = {},
                                searchPlaceholder = "Search movie categories"
                            ) {
                                item { StatusPill(label = "All Library 240", modifier = Modifier.padding(bottom = 8.dp)) }
                                item { StatusPill(label = "Top Picks 48", modifier = Modifier.padding(bottom = 8.dp)) }
                                item { StatusPill(label = "New Releases 32") }
                            }
                        },
                        content = {
                            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                BrowseHeroPanel(
                                    title = TestFixtures.vodTitle,
                                    subtitle = "Prestige movie curation with shared focus styling and premium hierarchy.",
                                    eyebrow = "Top Picks",
                                    metadata = listOf("2026", "Thriller", "RTG 8.8"),
                                    actionLabel = "Play",
                                    onClick = {}
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    MoviePosterCard(movie = TestFixtures.movie, modifier = Modifier.width(160.dp).height(240.dp))
                                    MoviePosterCard(
                                        movie = TestFixtures.movie.copy(id = 101L, name = "Coastline", year = "2025", rating = 8.1f),
                                        modifier = Modifier.width(160.dp).height(240.dp)
                                    )
                                    MoviePosterCard(
                                        movie = TestFixtures.movie.copy(id = 102L, name = "Northern Lights", year = "2024", rating = 7.9f),
                                        modifier = Modifier.width(160.dp).height(240.dp)
                                    )
                                }
                                LoadMoreCard(label = "Load more titles", onClick = {})
                            }
                        }
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("route_movies_landing")
    }

    @Test
    fun series_detail_route_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    AppScreenScaffold(
                        currentRoute = Routes.SERIES,
                        onNavigate = {},
                        title = "Series Detail",
                        subtitle = "Backdrop hero, metadata, and progress-aware episode rows."
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                            BrowseHeroPanel(
                                title = TestFixtures.series.name,
                                subtitle = "A premium detail surface with season browsing and editorial context.",
                                eyebrow = "Trending Series",
                                metadata = listOf("Drama", "2026", "RTG 8.5"),
                                actionLabel = "Resume",
                                onClick = {}
                            )
                            AppSectionHeader(
                                title = "Season 1",
                                subtitle = "8 episodes available"
                            )
                            EpisodeRowCard(episode = TestFixtures.episode)
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("route_series_detail")
    }

    @Test
    fun saved_guide_and_settings_routes_matchGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(AppColors.Canvas)
                        .testTag("golden")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            AppScreenScaffold(
                                // Saved Library is no longer a top-level navigation destination.
                                // Keep this visual fixture unselected instead of highlighting an
                                // unrelated production route.
                                currentRoute = "saved_library_golden_fixture",
                                onNavigate = {},
                                title = "Saved",
                                subtitle = "Manage favorite channels, movies, and series from one premium hub."
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    AppHeroHeader(
                                        title = "Saved Library",
                                        subtitle = "Live recall, movies, and series shelves stay aligned with the new shell."
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        StatusPill(label = "Live Recall")
                                        StatusPill(label = "Movies")
                                        StatusPill(label = "Series")
                                    }
                                    SeriesPosterCard(series = TestFixtures.series, modifier = Modifier.width(160.dp).height(240.dp))
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            AppScreenScaffold(
                                currentRoute = Routes.EPG,
                                onNavigate = {},
                                title = "Guide",
                                subtitle = "Sticky summary header and focused program details."
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        StatusPill(label = "All Channels")
                                        StatusPill(label = "Archive Ready", containerColor = AppColors.Brand)
                                    }
                                    AppMessageState(
                                        title = TestFixtures.currentProgram.title,
                                        subtitle = "21:00 - 22:00 ֲ· Focused details stay visible while the timeline scrolls."
                                    )
                                }
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            AppScreenScaffold(
                                currentRoute = Routes.SETTINGS,
                                onNavigate = {},
                                title = "Settings",
                                subtitle = "Provider health, parental controls, and backup tools in one refined hub."
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    AppHeroHeader(
                                        title = "Pulse IPTV",
                                        subtitle = "1 active provider ֲ· Locked groups enabled ֲ· System language"
                                    )
                                    AppMessageState(
                                        title = "Provider Sync Healthy",
                                        subtitle = "Last sync completed successfully. Diagnostics are available if playback degrades."
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("route_saved_guide_settings")
    }

    @Test
    fun search_route_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    AppScreenScaffold(
                        currentRoute = Routes.SEARCH,
                        onNavigate = {},
                        title = "Search",
                        subtitle = "Unified results across live, movies, and series."
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            SearchInput(
                                value = "night",
                                onValueChange = {},
                                placeholder = "Search everything"
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StatusPill(label = "Live 12")
                                StatusPill(label = "Movies 8")
                                StatusPill(label = "Series 4")
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                MoviePosterCard(movie = TestFixtures.movie, modifier = Modifier.width(160.dp).height(240.dp))
                                SeriesPosterCard(series = TestFixtures.series, modifier = Modifier.width(160.dp).height(240.dp))
                            }
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("route_search_results")
    }
}
