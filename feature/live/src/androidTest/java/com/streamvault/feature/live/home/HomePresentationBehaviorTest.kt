package com.streamvault.feature.live.home

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.feature.live.presentation.components.LiveCategoryRow
import com.streamvault.feature.live.presentation.components.LiveChannelRowSurface
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomePresentationBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun channelRow_exposesStableProgramFavoriteAndCatchUpSemantics() {
        val channel = Channel(
            id = 7L,
            name = "World Sports HD",
            number = 7,
            providerId = 3L,
            streamUrl = "https://example.test/live.m3u8",
            isFavorite = true,
            catchUpSupported = true,
            catchUpDays = 2,
            catchUpSource = "https://example.test/archive/{start}/{end}",
            currentProgram = Program(
                channelId = "world-sports",
                title = "Morning Sports",
                startTime = 0L,
                endTime = 60_000L,
            ),
        )

        composeRule.setContent {
            StreamVaultTheme {
                LiveChannelRowSurface(
                    channel = channel,
                    nowMs = 30_000L,
                    rowHeight = 68.dp,
                    onClick = {},
                )
            }
        }

        composeRule
            .onNodeWithContentDescription(
                "Channel 7, World Sports HD. Now playing Morning Sports. Favorite. Catch-up available"
            )
            .assertExists()
            .assertIsDisplayed()
    }

    @Test
    fun lockedChannelRow_exposesLockStateWithoutChangingIdentity() {
        val channel = Channel(
            id = 8L,
            name = "Protected News",
            number = 8,
            providerId = 3L,
            streamUrl = "https://example.test/protected.m3u8",
        )

        composeRule.setContent {
            StreamVaultTheme {
                LiveChannelRowSurface(
                    channel = channel,
                    nowMs = 0L,
                    onClick = {},
                    isLocked = true,
                    lockedLabel = "Locked",
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Channel 8, Protected News")
            .assertExists()
        composeRule.onNodeWithText("Locked").assertExists().assertIsDisplayed()
    }

    @Test
    fun categoryRow_retainsCategoryContent() {
        composeRule.setContent {
            StreamVaultTheme {
                LiveCategoryRow(
                    title = "News",
                    items = listOf(Category(id = 10L, name = "World")),
                    onPinToggle = {},
                    isPinned = false,
                    keySelector = Category::id,
                ) { category ->
                    Text(category.name)
                }
            }
        }

        composeRule.onNodeWithText("News").assertExists()
        composeRule.onNodeWithText("World").assertExists()
    }
}
