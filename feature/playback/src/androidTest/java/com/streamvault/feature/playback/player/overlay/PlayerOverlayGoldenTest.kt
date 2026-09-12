package com.streamvault.feature.playback.player.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.assertLeftPositionInRootIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.Channel
import com.streamvault.player.PlayerError
import com.streamvault.player.TrackType
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerOverlayGoldenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun playerControlsOverlay_vod_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                val playButtonFocusRequester = remember { FocusRequester() }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    PlayerControlsOverlay(
                        visible = true,
                        title = PlayerOverlayGoldenFixtures.vodTitle,
                        contentType = "MOVIE",
                        isPlaying = true,
                        currentProgram = null,
                        currentChannel = null,
                        currentChannelName = null,
                        displayChannelNumber = PlayerOverlayGoldenFixtures.displayChannelNumber,
                        currentPosition = PlayerOverlayGoldenFixtures.currentPositionMs,
                        duration = PlayerOverlayGoldenFixtures.durationMs,
                        aspectRatioLabel = PlayerOverlayGoldenFixtures.aspectRatioLabel,
                        subtitleTrackCount = 2,
                        audioTrackCount = 2,
                        videoQualityCount = 2,
                        currentRecordingStatus = null,
                        isMuted = false,
                        mediaTitle = null,
                        playButtonFocusRequester = playButtonFocusRequester,
                        onClose = {},
                        onTogglePlayPause = {},
                        onSeekBackward = {},
                        onSeekForward = {},
                        onRestartProgram = {},
                        onOpenArchive = {},
                        onStartRecording = {},
                        onStopRecording = {},
                        onScheduleRecording = {},
                        onToggleAspectRatio = {},
                        onOpenSubtitleTracks = {},
                        onOpenAudioTracks = {},
                        onOpenVideoTracks = {},
                        onOpenSplitScreen = {},
                        onToggleMute = {},
                        clockLabelOverride = PlayerOverlayGoldenFixtures.fixedClock
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertPlayerOverlayGolden("player_controls_overlay_vod")
    }

    @Test
    fun channelInfoOverlay_placesBackButtonAtTopLeft() {
        var backButtonClicks = 0
        composeRule.setContent {
            StreamVaultTheme {
                ChannelInfoOverlay(
                    currentChannel = Channel(id = 4L, name = "Nickelodeon", number = 4),
                    displayChannelNumber = 4,
                    currentProgram = null,
                    nextProgram = null,
                    focusRequester = remember { FocusRequester() },
                    lastVisitedCategoryName = null,
                    onDismiss = {},
                    onOverlayInteracted = {},
                    onOpenFullEpg = {},
                    onOpenLastGroup = {},
                    currentRecordingStatus = null,
                    onStartRecording = {},
                    onStopRecording = {},
                    onScheduleRecording = {},
                    onScheduleDailyRecording = {},
                    onScheduleWeeklyRecording = {},
                    onRestartProgram = {},
                    onOpenArchive = {},
                    onToggleAspectRatio = {},
                    onToggleDiagnostics = {},
                    onTogglePlayPause = {},
                    onSeekBackward = {},
                    onSeekForward = {},
                    onSeekToLiveEdge = {},
                    isPlaying = true,
                    currentAspectRatio = "Original",
                    isDiagnosticsEnabled = false,
                    showBackButton = true,
                    onBackToMenu = { backButtonClicks++ }
                )
            }
        }

        composeRule.onNodeWithContentDescription("Back to menu")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertTopPositionInRootIsEqualTo(24.dp)
            .assertLeftPositionInRootIsEqualTo(24.dp)
            .performSemanticsAction(SemanticsActions.OnClick)
        assertThat(backButtonClicks).isEqualTo(1)
    }

    @Test
    fun playerNoticeBanner_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    PlayerNoticeBanner(
                        notice = PlayerOverlayGoldenFixtures.notice,
                        onDismiss = {},
                        onAction = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertPlayerOverlayGolden("player_notice_banner")
    }

    @Test
    fun playerErrorOverlay_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    PlayerErrorOverlay(
                        playerError = PlayerError.NetworkError("Timeout"),
                        contentType = "LIVE",
                        hasAlternateStream = true,
                        hasLastChannel = true,
                        onAction = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertPlayerOverlayGolden("player_error_overlay")
    }

    @Test
    fun playerTrackSelectionDialog_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    PlayerTrackSelectionDialog(
                        trackType = TrackType.AUDIO,
                        audioTracks = PlayerOverlayGoldenFixtures.audioTracks,
                        subtitleTracks = PlayerOverlayGoldenFixtures.subtitleTracks,
                        videoTracks = emptyList(),
                        onDismiss = {},
                        onSelectAudio = {},
                        onSelectVideo = {},
                        onSelectSubtitle = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertPlayerOverlayGolden("player_track_selection_dialog")
    }

    @Test
    fun playerResumePrompt_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    PlayerResumePrompt(
                        title = PlayerOverlayGoldenFixtures.vodTitle,
                        onStartOver = {},
                        onResume = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertPlayerOverlayGolden("player_resume_prompt")
    }

    @Test
    fun playerNumericInputOverlay_matchesGolden() {
        composeRule.setContent {
            StreamVaultTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    PlayerNumericInputOverlay(
                        state = PlayerOverlayGoldenFixtures.invalidNumericInputState,
                        visible = true
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertPlayerOverlayGolden("player_numeric_input_overlay")
    }
}
