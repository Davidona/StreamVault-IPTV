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
import com.streamvault.core.ui.components.SearchInput
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
                                        subtitle = "21:00 - 22:00 · Focused details stay visible while the timeline scrolls."
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
                                        subtitle = "1 active provider · Locked groups enabled · System language"
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

}
