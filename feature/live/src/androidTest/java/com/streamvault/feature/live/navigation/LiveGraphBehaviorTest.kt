package com.streamvault.feature.live.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.feature.live.api.LiveArchivePlaybackRequest
import com.streamvault.feature.live.api.LiveChannelPlaybackRequest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveGraphBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun liveRoute_passesCategoryArgumentToFeatureContent() {
        lateinit var navController: TestNavHostController
        var receivedCategoryId: Long? = null

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {
                    LaunchedEffect(Unit) {
                        navController.navigate("live_tv?categoryId=42")
                    }
                }
                registerLiveGraph(
                    liveTvContent = { initialCategoryId, _, _ ->
                        receivedCategoryId = initialCategoryId
                    },
                    epgContent = { _, _, _, _, _, _ -> },
                    onPlaybackRequested = {},
                    onArchivePlaybackRequested = {},
                    onNavigate = {},
                )
            }
        }

        composeRule.waitUntil(5_000) { receivedCategoryId == 42L }
        assertThat(navController.currentBackStackEntry?.arguments?.getLong("categoryId"))
            .isEqualTo(42L)
        assertThat(receivedCategoryId).isEqualTo(42L)
    }

    @Test
    fun epgRoute_preservesOptionalArgumentsAndTypedPlaybackCallbacks() {
        lateinit var navController: TestNavHostController
        var receivedCategoryId: Long? = null
        var receivedAnchorTime: Long? = null
        var receivedFavoritesOnly = false
        var channelRequest: LiveChannelPlaybackRequest? = null
        var archiveRequest: LiveArchivePlaybackRequest? = null
        val channel = Channel(
            id = 7L,
            name = "Guide News",
            streamUrl = "https://example.test/live.m3u8",
            providerId = 3L,
        )
        val program = Program(
            id = 9L,
            channelId = "guide-news",
            title = "Headlines",
            startTime = 1_700_000_000_000L,
            endTime = 1_700_000_600_000L,
            providerId = 3L,
        )

        composeRule.setContent {
            navController = rememberTestNavController()
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {
                    LaunchedEffect(Unit) {
                        navController.navigate(
                            "epg?categoryId=12&anchorTime=1700000000000&favoritesOnly=true"
                        )
                    }
                }
                registerLiveGraph(
                    liveTvContent = { _, _, _ -> },
                    epgContent = { categoryId, anchorTime, favoritesOnly, onPlayChannel, onPlayArchive, _ ->
                        receivedCategoryId = categoryId
                        receivedAnchorTime = anchorTime
                        receivedFavoritesOnly = favoritesOnly
                        LaunchedEffect(Unit) {
                            onPlayChannel(
                                LiveChannelPlaybackRequest(
                                    channel = channel,
                                    categoryId = categoryId,
                                    providerId = channel.providerId,
                                    isVirtual = false,
                                    combinedProfileId = null,
                                    combinedSourceFilterProviderId = null,
                                    returnRoute = LiveRoutePatterns.epg(
                                        categoryId,
                                        anchorTime,
                                        favoritesOnly,
                                    ),
                                )
                            )
                            onPlayArchive(
                                LiveArchivePlaybackRequest(
                                    channel = channel,
                                    program = program,
                                    categoryId = categoryId,
                                    isVirtual = false,
                                    combinedProfileId = null,
                                    returnRoute = LiveRoutePatterns.epg(
                                        categoryId,
                                        anchorTime,
                                        favoritesOnly,
                                    ),
                                )
                            )
                        }
                    },
                    onPlaybackRequested = { channelRequest = it },
                    onArchivePlaybackRequested = { archiveRequest = it },
                    onNavigate = {},
                )
            }
        }

        composeRule.waitUntil(5_000) {
            receivedCategoryId == 12L &&
                receivedAnchorTime == 1_700_000_000_000L &&
                receivedFavoritesOnly &&
                channelRequest != null &&
                archiveRequest != null
        }

        assertThat(navController.currentBackStackEntry?.arguments?.getLong("categoryId"))
            .isEqualTo(12L)
        assertThat(navController.currentBackStackEntry?.arguments?.getLong("anchorTime"))
            .isEqualTo(1_700_000_000_000L)
        assertThat(navController.currentBackStackEntry?.arguments?.getBoolean("favoritesOnly"))
            .isTrue()
        assertThat(channelRequest?.channel).isSameInstanceAs(channel)
        assertThat(channelRequest?.returnRoute).isEqualTo(
            "epg?categoryId=12&anchorTime=1700000000000&favoritesOnly=true"
        )
        assertThat(archiveRequest?.channel).isSameInstanceAs(channel)
        assertThat(archiveRequest?.program).isSameInstanceAs(program)
        assertThat(archiveRequest?.returnRoute).isEqualTo(
            "epg?categoryId=12&anchorTime=1700000000000&favoritesOnly=true"
        )
    }

    @Composable
    private fun rememberTestNavController(): TestNavHostController {
        val context = LocalContext.current
        return remember(context) {
            TestNavHostController(context).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
        }
    }
}
