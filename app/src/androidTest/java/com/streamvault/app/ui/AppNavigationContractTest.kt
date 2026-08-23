package com.streamvault.app.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.navigation.NavType
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.app.navigation.AppRouteCodec
import com.streamvault.app.navigation.NavigationCommandEffect
import com.streamvault.app.navigation.NavControllerNavigator
import com.streamvault.app.navigation.PendingNavigationCommand
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationCommand
import com.streamvault.core.navigation.PlayerNavigationRequest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationContractTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun externalSearchCommandNavigatesOnceAndAcknowledges() {
        lateinit var navController: TestNavHostController
        var acknowledgementCount = 0
        var previousRouteAfterNavigation: String? = null

        composeRule.setContent {
            navController = rememberTestNavController()
            val currentEntry by navController.currentBackStackEntryAsState()
            var pending by remember {
                mutableStateOf<PendingNavigationCommand?>(
                    PendingNavigationCommand(
                        id = 1L,
                        command = NavigationCommand.Navigate(AppDestination.Search("news"))
                    )
                )
            }
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {}
                composable(
                    route = "search?query={query}",
                    arguments = listOf(navArgument("query") { type = NavType.StringType })
                ) {}
            }
            NavigationCommandEffect(
                pending = pending,
                currentBackStackEntry = currentEntry,
                navigator = remember(navController) { NavControllerNavigator(navController) },
                acknowledge = {
                    acknowledgementCount++
                    pending = null
                }
            )
        }

        composeRule.waitUntil(5_000) {
            navController.currentBackStackEntry?.arguments?.getString("query") == "news"
        }
        composeRule.runOnIdle {
            previousRouteAfterNavigation = navController.previousBackStackEntry?.destination?.route
        }
        composeRule.waitForIdle()

        assertThat(acknowledgementCount).isEqualTo(1)
        assertThat(previousRouteAfterNavigation).isEqualTo("home")
        assertThat(navController.currentBackStackEntry?.arguments?.getString("query"))
            .isEqualTo("news")
    }

    @Test
    fun playerReturnCommandUsesTypedGuideDestination() {
        lateinit var navController: TestNavHostController
        var completedPhase = 0

        composeRule.setContent {
            navController = rememberTestNavController()
            val currentEntry by navController.currentBackStackEntryAsState()
            var pending by remember { mutableStateOf<PendingNavigationCommand?>(null) }
            LaunchedEffect(Unit) {
                navController.navigate(AppRouteCodec.encode(AppDestination.Guide()))
                pending = PendingNavigationCommand(
                    id = 1L,
                    command = NavigationCommand.OpenPlayer(
                        PlayerNavigationRequest(
                            streamUrl = "https://example.com/live.m3u8",
                            title = "Guide channel",
                            internalId = 42L,
                            returnDestination = AppDestination.Guide()
                        )
                    )
                )
            }
            NavHost(navController = navController, startDestination = "home") {
                composable("home") {}
                composable(
                    route = "epg?categoryId={categoryId}&anchorTime={anchorTime}&favoritesOnly={favoritesOnly}",
                    arguments = listOf(
                        navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("anchorTime") { type = NavType.LongType; defaultValue = -1L },
                        navArgument("favoritesOnly") { type = NavType.BoolType; defaultValue = false }
                    )
                ) {}
                composable("player") {}
            }
            NavigationCommandEffect(
                pending = pending,
                currentBackStackEntry = currentEntry,
                navigator = remember(navController) { NavControllerNavigator(navController) },
                acknowledge = { id ->
                    if (id == 1L) {
                        pending = PendingNavigationCommand(
                            id = 2L,
                            command = NavigationCommand.ReturnTo(AppDestination.Guide())
                        )
                    } else {
                        completedPhase = 2
                        pending = null
                    }
                }
            )
        }

        composeRule.waitUntil(5_000) { completedPhase == 2 }
        composeRule.runOnIdle {
            assertThat(navController.currentBackStackEntry?.destination?.route)
                .contains("epg")
            assertThat(navController.currentBackStackEntry?.arguments?.getLong("categoryId"))
                .isEqualTo(-1L)
        }
    }

    @androidx.compose.runtime.Composable
    private fun rememberTestNavController(): TestNavHostController {
        val context = LocalContext.current
        return remember(context) {
            TestNavHostController(context).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
        }
    }

}
