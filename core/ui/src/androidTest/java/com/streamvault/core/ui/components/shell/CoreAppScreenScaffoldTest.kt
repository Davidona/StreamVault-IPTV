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

class CoreAppScreenScaffoldTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun exposesDestinationSemanticsAndForwardsSelection() {
        var selectedDestination: String? = null
        composeRule.setContent {
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

        composeRule.onNodeWithContentDescription("streamvault.destination:home").assertExists()
        composeRule
            .onNode(hasText("Settings") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertThat(selectedDestination).isEqualTo("settings")
        }
    }
}
