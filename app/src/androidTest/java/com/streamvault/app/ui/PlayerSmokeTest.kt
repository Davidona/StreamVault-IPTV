package com.streamvault.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.app.ui.components.shell.CategoryRailPanel
import com.streamvault.app.ui.screens.player.overlay.PlayerControlsOverlay
import com.streamvault.app.ui.screens.player.overlay.PlayerTrackSelectionDialog
import com.streamvault.app.ui.test.TestFixtures
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.player.TrackType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlayerSmokeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun categoryRailPanel_searchField_acceptsInitialFocusAndInput() {
        val searchFocusRequester = FocusRequester()

        composeRule.setContent {
            val query = remember { mutableStateOf("") }
            StreamVaultTheme {
                CategoryRailPanel(
                    title = "Guide",
                    searchValue = query.value,
                    onSearchValueChange = { query.value = it },
                    searchPlaceholder = "Search categories",
                    modifier = Modifier.fillMaxSize(),
                    searchFocusRequester = searchFocusRequester
                ) {
                }
                LaunchedEffect(Unit) {
                    searchFocusRequester.requestFocus()
                }
            }
        }

        composeRule.onNode(hasSetTextAction()).assertIsFocused()
        composeRule.onNode(hasSetTextAction()).performTextInput("Sports")
        composeRule.onNodeWithText("Sports").assertExists()
    }

    @Test
    fun playerControlsOverlay_playButton_canReceiveFocus() {
        val playButtonFocusRequester = FocusRequester()

        composeRule.setContent {
            StreamVaultTheme {
                PlayerControlsOverlay(
                    visible = true,
                    title = TestFixtures.vodTitle,
                    contentType = "MOVIE",
                    isPlaying = false,
                    currentProgram = null,
                    currentChannel = null,
                    currentChannelName = null,
                    displayChannelNumber = TestFixtures.displayChannelNumber,
                    currentPosition = TestFixtures.currentPositionMs,
                    duration = TestFixtures.durationMs,
                    aspectRatioLabel = TestFixtures.aspectRatioLabel,
                    subtitleTrackCount = 1,
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
                    clockLabelOverride = TestFixtures.fixedClock
                )
                LaunchedEffect(Unit) {
                    playButtonFocusRequester.requestFocus()
                }
            }
        }

        composeRule.onNodeWithText(">").assertIsFocused()
    }

    @Test
    fun playerControlsOverlay_showsMuteActionWhenMuted() {
        composeRule.setContent {
            StreamVaultTheme {
                PlayerControlsOverlay(
                    visible = true,
                    title = TestFixtures.liveTitle,
                    contentType = "LIVE",
                    isPlaying = true,
                    currentProgram = null,
                    currentChannel = null,
                    currentChannelName = TestFixtures.liveTitle,
                    displayChannelNumber = TestFixtures.displayChannelNumber,
                    currentPosition = 0L,
                    duration = 0L,
                    aspectRatioLabel = TestFixtures.aspectRatioLabel,
                    subtitleTrackCount = 1,
                    audioTrackCount = 2,
                    videoQualityCount = 2,
                    currentRecordingStatus = null,
                    isMuted = true,
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

        composeRule.onNodeWithText("Muted").assertExists()
        composeRule.onNodeWithText("Unmute").assertExists()
    }

    @Test
    fun playerTrackSelectionDialog_selectsAudioTrack() {
        var selectedTrackId: String? = null

        composeRule.setContent {
            StreamVaultTheme {
                PlayerTrackSelectionDialog(
                    trackType = TrackType.AUDIO,
                    audioTracks = TestFixtures.audioTracks,
                    subtitleTracks = TestFixtures.subtitleTracks,
                    videoTracks = emptyList(),
                    onDismiss = {},
                    onSelectAudio = { selectedTrackId = it },
                    onSelectVideo = {},
                    onSelectSubtitle = {}
                )
            }
        }

        composeRule.onNodeWithText("Spanish Stereo").performClick()
        composeRule.runOnIdle {
            check(selectedTrackId == "audio-es")
        }
    }
}
