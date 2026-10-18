package com.streamvault.core.ui.components.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.tv.material3.MaterialTheme
import org.junit.Rule
import org.junit.Test
import com.google.common.truth.Truth.assertThat
import android.content.res.Configuration
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.streamvault.core.ui.theme.StreamVaultTheme

class CoreAppScreenScaffoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exposesDestinationSemanticsAndForwardsSelection() {
        var selectedDestination: String? = null
        composeRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides Configuration().apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 960
                screenHeightDp = 540
            }) {
            MaterialTheme {
                CoreAppScreenScaffold(
                    currentDestinationId = "home",
                    destinations = listOf(
                        UiDestination(id = "home", label = "Home", icon = Icons.Default.Home),
                        UiDestination(id = "settings", label = "Settings", icon = Icons.Default.Home)
                    ),
                    onDestinationSelected = { selectedDestination = it },
                    title = "Home"
                ) {}
            }
            }
        }

        composeRule.onNodeWithContentDescription("streamvault.destination:home").assertExists()
        composeRule
            .onNode(hasText("Settings") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertThat(selectedDestination).isEqualTo("settings")
        }
    }

    @Test
    fun portraitMenuReachesEveryDestinationAndRotationRestoresTopNavigation() {
        val configuration = mutableStateOf(Configuration().apply {
            orientation = Configuration.ORIENTATION_PORTRAIT
            screenWidthDp = 390
            screenHeightDp = 840
        })
        val currentDestination = mutableStateOf("tab0")
        val destinations = (0..9).map { UiDestination("tab$it", "Destination $it", Icons.Default.Home) }
        composeRule.setContent {
            CompositionLocalProvider(LocalConfiguration provides configuration.value) {
                StreamVaultTheme {
                    CoreAppScreenScaffold(
                        currentDestinationId = currentDestination.value,
                        destinations = destinations,
                        onDestinationSelected = { currentDestination.value = it },
                        navigationChrome = NavigationChrome.TopBar,
                        title = "Screen title",
                        topBarVisible = configuration.value.orientation == Configuration.ORIENTATION_LANDSCAPE
                    ) {}
                }
            }
        }
        composeRule.onNodeWithContentDescription("Destination 0").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Navigation menu").performClick()
        composeRule.onNodeWithText("Destination 9").performScrollTo().performClick()
        composeRule.runOnIdle { assertThat(currentDestination.value).isEqualTo("tab9") }
        composeRule.onNodeWithText("Destination 9").assertDoesNotExist()
        composeRule.runOnIdle {
            configuration.value = Configuration(configuration.value).apply {
                orientation = Configuration.ORIENTATION_LANDSCAPE
                screenWidthDp = 960
                screenHeightDp = 540
            }
        }
        composeRule.onNodeWithContentDescription("Navigation menu").assertDoesNotExist()
        composeRule.onNodeWithContentDescription("streamvault.destination:tab9").assertExists()
        composeRule.onNodeWithText("Destination 9").assertExists()
    }
}
