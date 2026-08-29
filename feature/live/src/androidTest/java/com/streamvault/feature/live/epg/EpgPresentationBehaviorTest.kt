package com.streamvault.feature.live.epg

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.feature.live.presentation.epg.LiveGuideHeroBadge
import com.streamvault.feature.live.presentation.epg.LiveGuideMessageState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgPresentationBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun guideHeroBadge_rendersLiveStateText() {
        composeRule.setContent {
            StreamVaultTheme {
                LiveGuideHeroBadge(text = "LIVE NOW", highlight = true)
            }
        }

        composeRule.onNodeWithText("LIVE NOW").assertExists().assertIsDisplayed()
    }

    @Test
    fun guideMessageState_rendersActionableEmptyStateCopy() {
        composeRule.setContent {
            StreamVaultTheme {
                LiveGuideMessageState(
                    title = "Guide unavailable",
                    subtitle = "Try refreshing the schedule.",
                    actionLabel = "Refresh",
                    onAction = {},
                )
            }
        }

        composeRule.onNodeWithText("Guide unavailable").assertExists().assertIsDisplayed()
        composeRule.onNodeWithText("Try refreshing the schedule.").assertExists().assertIsDisplayed()
        composeRule.onNodeWithText("Refresh").assertExists().assertIsDisplayed()
    }
}
