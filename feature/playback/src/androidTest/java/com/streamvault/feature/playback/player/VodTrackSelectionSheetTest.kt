package com.streamvault.feature.playback.player

import android.graphics.Bitmap
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class VodTrackSelectionSheetTest {
    @get:Rule val rule = createComposeRule()
    private val tracks = (1..30).map { PlayerTrack("$it", "Subtitle language $it", null, TrackType.TEXT, it == 1) }

    @Test fun sourcesRemainVisibleWithManyTracks_andSettingsApplyWithoutClosing() {
        var selected: String? = null
        var searches = 0
        var imports = 0
        var dismissals = 0
        rule.setContent {
            var scale by remember { mutableStateOf(1f) }
            var background by remember { mutableStateOf(0x80000000.toInt()) }
            var offset by remember { mutableStateOf(0) }
            StreamVaultTheme {
                VodTrackSelectionSheet(
                    trackType = TrackType.TEXT,
                    tracks = tracks,
                    offsetMs = offset,
                    textScale = scale,
                    backgroundColor = background,
                    onlineAvailable = true,
                    onDismiss = { dismissals++ },
                    onSelectTrack = { selected = it },
                    onSearchOnline = { searches++ },
                    onLoadLocal = { imports++ },
                    onAdjustOffset = { offset += it },
                    onResetOffset = { offset = 0 },
                    onSetTextScale = { scale = it },
                    onSetOpacity = { background = (it * 255 / 100) shl 24 },
                )
            }
        }
        rule.onNodeWithText("Provider subtitles").assertIsDisplayed()
        rule.onNodeWithText("Provider subtitles").assertIsFocused()
        capture("vod-subtitle-sources")
        rule.onNodeWithText("OpenSubtitles").assertIsDisplayed()
        rule.onNodeWithText("Provider subtitles").performKeyInput {
            pressKey(Key.DirectionDown)
        }
        rule.onNodeWithText("OpenSubtitles").assertIsFocused().performKeyInput {
            pressKey(Key.Enter)
        }
        rule.onNodeWithText("Load SRT from device").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        capture("vod-subtitle-sources")
        rule.onNodeWithText("Settings").performSemanticsAction(SemanticsActions.OnClick) { it() }
        capture("vod-subtitle-settings")
        rule.onNodeWithContentDescription("Increase Subtitle delay").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("100 ms").assertExists()
        rule.onNodeWithContentDescription("Reset delay").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("0 ms").assertExists()
        rule.onNodeWithContentDescription("Increase Font size").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("125%").assertExists()
        rule.onNodeWithContentDescription("Increase Background opacity").assertIsDisplayed().performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("60%").assertExists()
        capture("vod-subtitle-appearance")
        rule.onNodeWithText("Sources").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNodeWithText("Provider subtitles").performSemanticsAction(SemanticsActions.OnClick) { it() }
        rule.onNode(hasScrollToIndexAction()).performScrollToNode(hasText("Subtitle language 30"))
        rule.onNodeWithText("Subtitle language 30").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals("30", selected)
        assertEquals(1, searches)
        assertEquals(1, imports)
        assertEquals(1, dismissals)
    }

    @Test fun audioSelection_showsSelectedTrack_andSelectsAnother() {
        var selected: String? = null
        val audio = listOf(PlayerTrack("en", "English · Stereo", "en", TrackType.AUDIO, true),
            PlayerTrack("he", "Hebrew · Stereo", "he", TrackType.AUDIO, false))
        rule.setContent {
            StreamVaultTheme {
                VodTrackSelectionSheet(
                    trackType = TrackType.AUDIO,
                    tracks = audio,
                    offsetMs = 0,
                    textScale = 1f,
                    backgroundColor = 0,
                    onlineAvailable = false,
                    onDismiss = {},
                    onSelectTrack = { selected = it },
                    onSearchOnline = {},
                    onLoadLocal = {},
                    onAdjustOffset = {},
                    onResetOffset = {},
                    onSetTextScale = {},
                    onSetOpacity = {},
                )
            }
        }
        rule.onNodeWithText("English · Stereo").assertIsDisplayed()
        capture("vod-audio-tracks")
        rule.onNodeWithText("Hebrew · Stereo").performSemanticsAction(SemanticsActions.OnClick) { it() }
        assertEquals("he", selected)
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.filesDir, "vod-track-review").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
