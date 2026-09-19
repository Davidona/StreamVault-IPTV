package com.streamvault.feature.system.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.createGraph
import androidx.navigation.testing.TestNavHostController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.feature.system.api.SystemScaffoldContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemRouteGraphBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun systemGraphRegistersWelcomeDownloadsAndPlugins() {
        lateinit var navController: TestNavHostController

        composeRule.setContent {
            val context = LocalContext.current
            navController = remember(context) {
                TestNavHostController(context).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
                graph = createGraph(startDestination = SystemRoutePatterns.WELCOME) {
                    registerSystemGraph(
                        actions = recordingActions(),
                        startupReady = true,
                        onStartupNavigationRequested = {},
                        scaffold = testScaffold,
                    )
                }
            }
            }
        }

        composeRule.runOnIdle {
            navController.navigate(SystemRoutePatterns.DOWNLOADS)
            check(navController.currentBackStackEntry?.destination?.route == SystemRoutePatterns.DOWNLOADS)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            navController.navigate(SystemRoutePatterns.PLUGINS)
            check(navController.currentBackStackEntry?.destination?.route == SystemRoutePatterns.PLUGINS)
        }
        composeRule.waitForIdle()

        check(navController.currentBackStackEntry?.destination?.route == SystemRoutePatterns.PLUGINS)
    }

    private val testScaffold: SystemScaffoldContent = { _, _, _, _, _, content ->
        Column { content() }
    }

    private fun recordingActions() = object : NavigationActions {
        override fun navigate(destination: AppDestination, options: NavigationOptions) = Unit

        override fun openPlayer(request: PlayerNavigationRequest) = Unit

        override fun back(): Boolean = true

        override fun returnTo(destination: AppDestination?): Boolean = true
    }
}
