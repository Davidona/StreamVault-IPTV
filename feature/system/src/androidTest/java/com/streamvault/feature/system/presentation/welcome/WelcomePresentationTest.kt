package com.streamvault.feature.system.presentation.welcome

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.sync.Section
import com.streamvault.feature.system.api.WelcomeSyncProgress
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WelcomePresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun noProviderStateRetainsSetupAndLaterActions() {
        var homeClicks = 0
        var setupClicks = 0

        composeRule.setContent {
            StreamVaultTheme {
                WelcomeContent(
                    hasProviders = false,
                    syncProgress = null,
                    onNavigateToHome = { homeClicks++ },
                    onNavigateToSetup = { setupClicks++ },
                )
            }
        }

        composeRule.onNode(hasText("Setup Provider") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle {
            assertThat(setupClicks).isEqualTo(1)
        }
        composeRule.onNode(hasText("Set up later") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)

        composeRule.runOnIdle {
            assertThat(homeClicks).isEqualTo(1)
        }
    }

    @Test
    fun loadingStateRetainsTitleAndSubtitle() {
        composeRule.setContent {
            StreamVaultTheme {
                WelcomeContent(
                    hasProviders = null,
                    syncProgress = null,
                    onNavigateToHome = {},
                    onNavigateToSetup = {},
                )
            }
        }

        composeRule.onNodeWithText("Preparing your library").assertIsDisplayed()
        composeRule
            .onNodeWithText("Opening the right screen for this device.")
            .assertIsDisplayed()
    }

    @Test
    fun activeProgressRetainsSectionLabelAndIndexedCount() {
        composeRule.setContent {
            StreamVaultTheme {
                WelcomeContent(
                    hasProviders = true,
                    syncProgress = WelcomeSyncProgress(
                        section = Section.VOD,
                        current = 2,
                        total = 5,
                        currentLabel = "Movies",
                        itemsIndexed = 44,
                    ),
                    onNavigateToHome = {},
                    onNavigateToSetup = {},
                )
            }
        }

        composeRule.onNodeWithText("VOD").assertIsDisplayed()
        composeRule.onNodeWithText("Movies").assertIsDisplayed()
        composeRule.onNodeWithText("44 items indexed").assertIsDisplayed()
    }
}
