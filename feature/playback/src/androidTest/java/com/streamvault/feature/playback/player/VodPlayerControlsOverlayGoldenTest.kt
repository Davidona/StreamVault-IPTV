package com.streamvault.feature.playback.player

import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.player.PlayerChapter
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VodPlayerControlsOverlayGoldenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chapterAwareOverlay_exposesVideoFirstActions() {
        var backButtonClicks = 0
        composeRule.setContent {
            StreamVaultTheme {
                val focusRequester = remember { FocusRequester() }
                VodPlayerControlsOverlay(
                    visible = true,
                    title = "The Long Night",
                    overlayState = buildVodOverlayState(
                        contentType = "MOVIE",
                        isCatchUpPlayback = false,
                        chapters = listOf(
                            PlayerChapter(1, "Opening", 0L, 60_000L),
                            PlayerChapter(2, "The flooded station", 60_000L, null)
                        ),
                        currentPositionMs = 72_000L,
                        showEpisodesAction = false,
                        subtitleTrackCount = 1,
                        audioTrackCount = 1,
                        videoQualityCount = 1,
                        showExternalPlayerAction = false,
                        isCastConnected = false
                    ),
                    isPlaying = true,
                    currentPositionMs = 72_000L,
                    durationMs = 150_000L,
                    seekPreview = SeekPreviewState(),
                    playButtonFocusRequester = focusRequester,
                    showBackButton = true,
                    onBackToMenu = { backButtonClicks++ },
                    onClose = {},
                    onTogglePlayPause = {},
                    onSeekBackward = {},
                    onSeekForward = {},
                    onSeekPreviousChapter = {},
                    onSeekNextChapter = {},
                    onOpenChapters = {},
                    onOpenEpisodes = {},
                    onOpenSubtitleTracks = {},
                    onOpenAudioTracks = {},
                    onOpenSettings = {},
                    onSeekToPosition = {},
                    onSetScrubbingMode = {},
                    onSeekPreviewPositionChanged = {},
                    onUserInteraction = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("VOD playback controls").assertExists()
        composeRule.onNodeWithContentDescription("Back to menu").assertIsDisplayed()
        composeRule.onNodeWithTag("player_back_button")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onNodeWithContentDescription("Chapters").assertExists()
        composeRule.onNodeWithContentDescription("Next chapter").assertExists()
        assertThat(backButtonClicks).isEqualTo(1)
    }

    @Test
    fun noChapters_hidesChapterAction() {
        composeRule.setContent {
            StreamVaultTheme {
                val focusRequester = remember { FocusRequester() }
                VodPlayerControlsOverlay(
                    visible = true,
                    title = "A movie",
                    overlayState = buildVodOverlayState(
                        contentType = "MOVIE",
                        isCatchUpPlayback = false,
                        chapters = emptyList(),
                        currentPositionMs = 0L,
                        showEpisodesAction = false,
                        subtitleTrackCount = 0,
                        audioTrackCount = 0,
                        videoQualityCount = 0,
                        showExternalPlayerAction = false,
                        isCastConnected = false
                    ),
                    isPlaying = false,
                    currentPositionMs = 0L,
                    durationMs = 150_000L,
                    seekPreview = SeekPreviewState(),
                    playButtonFocusRequester = focusRequester,
                    onClose = {},
                    onTogglePlayPause = {},
                    onSeekBackward = {},
                    onSeekForward = {},
                    onSeekPreviousChapter = {},
                    onSeekNextChapter = {},
                    onOpenChapters = {},
                    onOpenEpisodes = {},
                    onOpenSubtitleTracks = {},
                    onOpenAudioTracks = {},
                    onOpenSettings = {},
                    onSeekToPosition = {},
                    onSetScrubbingMode = {},
                    onSeekPreviewPositionChanged = {},
                    onUserInteraction = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("VOD playback controls").assertExists()
        composeRule.onNodeWithContentDescription("Chapters").assertDoesNotExist()
    }
}
