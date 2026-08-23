package com.streamvault.app.ui.screens.player.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.app.ui.test.TestFixtures
import com.streamvault.app.ui.test.assertAgainstGolden
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.player.PlayerError
import com.streamvault.player.TrackType
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
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("golden")
                ) {
                    PlayerControlsOverlay(
                        visible = true,
                        title = TestFixtures.vodTitle,
                        contentType = "MOVIE",
                        isPlaying = true,
                        currentProgram = null,
                        currentChannel = null,
                        currentChannelName = null,
                        displayChannelNumber = TestFixtures.displayChannelNumber,
                        currentPosition = TestFixtures.currentPositionMs,
                        duration = TestFixtures.durationMs,
                        aspectRatioLabel = TestFixtures.aspectRatioLabel,
                        subtitleTrackCount = 2,
                        audioTrackCount = 2,
                        videoQualityCount = 2,
                        currentRecordingStatus = null,
                        isMuted = false,
                        mediaTitle = null,
                        playButtonFocusRequester = FocusRequester(),
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
                        clockLabelOverride = TestFixtures.fixedClock
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("player_controls_overlay_vod")
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
                        notice = TestFixtures.notice,
                        onDismiss = {},
                        onAction = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("player_notice_banner")
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

        composeRule.onNodeWithTag("golden").assertAgainstGolden("player_error_overlay")
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
                        audioTracks = TestFixtures.audioTracks,
                        subtitleTracks = TestFixtures.subtitleTracks,
                        videoTracks = emptyList(),
                        onDismiss = {},
                        onSelectAudio = {},
                        onSelectVideo = {},
                        onSelectSubtitle = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("player_track_selection_dialog")
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
                        title = TestFixtures.vodTitle,
                        onStartOver = {},
                        onResume = {}
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("player_resume_prompt")
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
                        state = TestFixtures.invalidNumericInputState,
                        visible = true
                    )
                }
            }
        }

        composeRule.onNodeWithTag("golden").assertAgainstGolden("player_numeric_input_overlay")
    }
}
