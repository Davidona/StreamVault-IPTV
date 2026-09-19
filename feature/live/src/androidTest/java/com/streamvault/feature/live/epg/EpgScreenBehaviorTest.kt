package com.streamvault.feature.live.epg

import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.AppTimeFormat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.feature.live.presentation.epg.GuideDensity
import com.streamvault.feature.live.presentation.epg.LiveGuideGridLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideGridRow
import com.streamvault.feature.live.presentation.epg.LiveGuideMessageState
import com.streamvault.feature.live.presentation.epg.LiveGuidePreviewLabels
import com.streamvault.feature.live.presentation.epg.LiveGuidePreviewPane
import com.streamvault.feature.live.presentation.epg.LiveGuideToolbarLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideToolbarRow
import com.streamvault.feature.live.presentation.time.LocalLiveTimeFormat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EpgScreenBehaviorTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun guideToolbar_dispatchesCategoryTimeSearchAndOptionsCallbacks() {
        val events = mutableListOf<String>()

        composeRule.setContent {
            StreamVaultTheme {
                LiveGuideToolbarRow(
                    selectedCategoryName = "Sports",
                    labels = LiveGuideToolbarLabels(
                        jumpNow = "Now",
                        search = "Search",
                        options = "Options"
                    ),
                    onOpenCategoryPicker = { events += "category" },
                    onJumpToNow = { events += "now" },
                    onOpenSearch = { events += "search" },
                    onOpenOptions = { events += "options" },
                    onGuideInteract = {}
                )
            }
        }

        composeRule.onNodeWithText("Sports").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Now").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Search").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Options").performSemanticsAction(SemanticsActions.OnClick)

        assertThat(events).containsExactly("category", "now", "search", "options").inOrder()
    }

    @Test
    fun guideMessageState_dispatchesRetryAction() {
        var actionCount = 0

        composeRule.setContent {
            StreamVaultTheme {
                LiveGuideMessageState(
                    title = "Guide unavailable",
                    subtitle = "Try again later",
                    actionLabel = "Retry",
                    onAction = { actionCount++ }
                )
            }
        }

        composeRule.onNodeWithText("Guide unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("Retry").performSemanticsAction(SemanticsActions.OnClick)

        assertThat(actionCount).isEqualTo(1)
    }

    @Test
    fun guidePreviewPane_rendersPlaceholderState() {
        composeRule.setContent {
            StreamVaultTheme {
                LiveGuidePreviewPane(
                    previewPlayerEngine = null,
                    isPreviewLoading = false,
                    focusedChannel = null,
                    focusedProgram = null,
                    labels = LiveGuidePreviewLabels(
                        title = "Preview",
                        placeholderTitle = "Select a channel",
                        noSchedule = "No schedule"
                    )
                )
            }
        }
        composeRule.onNodeWithText("Select a channel").assertIsDisplayed()
    }

    @Test
    fun guidePreviewPane_rendersFocusedChannelWithoutSchedule() {
        composeRule.setContent {
            StreamVaultTheme {
                LiveGuidePreviewPane(
                    previewPlayerEngine = null,
                    isPreviewLoading = false,
                    focusedChannel = testChannel(21L, "World News"),
                    focusedProgram = null,
                    labels = LiveGuidePreviewLabels(
                        title = "Preview",
                        placeholderTitle = "Select a channel",
                        noSchedule = "No schedule"
                    )
                )
            }
        }
        composeRule.onNodeWithText("21. World News").assertIsDisplayed()
        composeRule.onNodeWithText("No schedule").assertIsDisplayed()
    }

    @Test
    fun guidePreviewPane_rendersProgramTimeRangeWithEnDash() {
        val now = System.currentTimeMillis()
        val program = Program(
            channelId = "world-news",
            title = "Evening Bulletin",
            startTime = now - 60_000L,
            endTime = now + 60_000L
        )

        composeRule.setContent {
            StreamVaultTheme {
                CompositionLocalProvider(LocalLiveTimeFormat provides AppTimeFormat.TWENTY_FOUR_HOUR) {
                    LiveGuidePreviewPane(
                        previewPlayerEngine = null,
                        isPreviewLoading = false,
                        focusedChannel = testChannel(21L, "World News"),
                        focusedProgram = program,
                        labels = LiveGuidePreviewLabels(
                            title = "Preview",
                            placeholderTitle = "Select a channel",
                            noSchedule = "No schedule"
                        )
                    )
                }
            }
        }

        composeRule.onNodeWithText("Evening Bulletin").assertIsDisplayed()
        composeRule.onNodeWithText(" – ", substring = true).assertIsDisplayed()
    }

    @Test
    fun guideGridRow_preservesChannelProgramIdentityAndCallbacks() {
        val now = System.currentTimeMillis()
        val channel = testChannel(31L, "World News")
        val program = Program(
            channelId = "world-news",
            title = "Evening Bulletin",
            startTime = now + 5 * 60 * 1000L,
            endTime = now + 35 * 60 * 1000L
        )
        val events = mutableListOf<String>()

        composeRule.setContent {
            StreamVaultTheme {
                LiveGuideGridRow(
                    channel = channel,
                    isFavorite = true,
                    programs = listOf(program),
                    windowStart = now - 60 * 60 * 1000L,
                    windowEnd = now + 3 * 60 * 60 * 1000L,
                    channelRailWidth = 180.dp,
                    timelineGap = 4.dp,
                    timelineViewportWidth = 640.dp,
                    totalTimelineWidth = 640.dp,
                    density = GuideDensity.COMPACT,
                    transparentOverlay = false,
                    rowHeight = 40.dp,
                    markerStepMs = 30 * 60 * 1000L,
                    scrollState = rememberScrollState(),
                    labels = LiveGuideGridLabels(
                        noSchedule = "No schedule",
                        archiveBadge = "Archive",
                        favoriteBadge = "Favorite"
                    ),
                    onChannelClick = { events += "channel:${channel.id}" },
                    onChannelFocused = {},
                    onProgramClick = { events += "program:${it.title}" },
                    onProgramFocused = {}
                )
            }
        }

        composeRule.onNodeWithText("31. World News")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithText("Evening Bulletin")
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)

        assertThat(events).containsExactly("channel:31", "program:Evening Bulletin").inOrder()
    }
}

private fun testChannel(id: Long, name: String) = Channel(
    id = id,
    name = name,
    number = id.toInt(),
    streamUrl = "https://example.test/$id.m3u8",
    epgChannelId = name.lowercase().replace(' ', '-')
)
