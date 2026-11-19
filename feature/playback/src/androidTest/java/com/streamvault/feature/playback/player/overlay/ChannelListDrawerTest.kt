package com.streamvault.feature.playback.player.overlay

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.Channel
import com.streamvault.feature.playback.R
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ChannelListDrawerTest(private val theme: String, private val language: String) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun focusNavigationSelectionAndScreenshots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val context = instrumentation.targetContext.createConfigurationContext(config)
        val channels = listOf(
            Channel(id = 17, name = "FR - BFM GRAND LILLE FHD", number = 17, catchUpSupported = true, catchUpDays = 7,
                providerId = 1, streamId = 17, streamUrl = "xtream://test/17"),
            Channel(id = 18, name = "FR - BFM MARSEILLE FHD", number = 18),
            Channel(id = 19, name = "LA CHAINE METEO F", number = 19, catchUpSupported = true, catchUpDays = 7,
                providerId = 1, streamId = 19, streamUrl = "xtream://test/19"),
            Channel(id = 20, name = "FR - EURONEWS FRENCH INTERNATIONAL NEWS AND CURRENT AFFAIRS", number = 20)
        )
        var dismissed = 0
        var selected: Long? = null
        var openedCategories = 0
        var openedLastGroup = 0
        val categories = context.getString(R.string.label_categories)
        val lastGroup = context.getString(R.string.player_last_group_label, "France / International")
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides config,
                LocalLayoutDirection provides if (language == "en") LayoutDirection.Ltr else LayoutDirection.Rtl
            ) {
                StreamVaultTheme(themeId = theme) {
                    Box(Modifier.fillMaxSize().background(AppColors.Surface)) {
                        ChannelListOverlay(
                            channels = channels,
                            recentChannels = listOf(Channel(id = 99, name = "Recent channel with a long descriptive title")),
                            currentChannelId = 19,
                            lastVisitedCategoryName = "France / International",
                            onOpenCategories = { openedCategories++ },
                            onOpenLastGroup = { openedLastGroup++ },
                            onSelectChannel = { selected = it },
                            onDismiss = { dismissed++ }
                        )
                    }
                }
            }
        }
        compose.onNodeWithText(channels[2].name).assertIsDisplayed().assertIsFocused()
        capture("entry")
        val labels = listOf(categories, lastGroup) + channels.map { it.name }
        labels.forEachIndexed { index, label ->
            if (index >= 2) compose.onNodeWithText(label).performScrollTo()
            val node = if (index == 0) compose.onNodeWithContentDescription(label) else compose.onNodeWithText(label)
            node.performSemanticsAction(SemanticsActions.RequestFocus)
            node.assertIsFocused()
            capture("focus_$index")
        }
        compose.onAllNodes(hasScrollAction()).onFirst().performScrollToIndex(0)
        compose.onNodeWithText("Recent channel with a long descriptive title")
            .performSemanticsAction(SemanticsActions.RequestFocus)
        capture("focus_recent")
        compose.onNodeWithText(channels[2].name).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(19L, selected)
        assertEquals(1, dismissed)
        compose.onNodeWithContentDescription(categories).performSemanticsAction(SemanticsActions.RequestFocus)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.onNodeWithText(lastGroup).performSemanticsAction(SemanticsActions.RequestFocus)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(1, openedCategories)
        assertEquals(1, openedLastGroup)
        val towardStart = if (language == "en") android.view.KeyEvent.KEYCODE_DPAD_LEFT
            else android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        instrumentation.sendKeyDownUpSync(towardStart)
        compose.onNodeWithContentDescription(categories).assertIsFocused()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertEquals(2, dismissed)
        compose.onNodeWithText(channels[2].name).performScrollTo()
            .performSemanticsAction(SemanticsActions.RequestFocus)
        instrumentation.sendKeyDownUpSync(towardStart)
        compose.waitForIdle()
        assertEquals(2, openedCategories)
        assertEquals(2, dismissed)
    }

    private fun capture(state: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val output = "/sdcard/Download/streamvault-channel-drawer"
        automation.executeShellCommand("mkdir -p $output").use {
            FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes() }
        }
        compose.onRoot().captureToImage().asAndroidBitmap().let { bitmap ->
            val path = "$output/${theme}_${language}_$state.png"
            val pipes = automation.executeShellCommandRw("dd of=$path")
            ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            FileInputStream(pipes[0].fileDescriptor).use { it.readBytes() }
            pipes[0].close()
            val size = automation.executeShellCommand("stat -c %s $path").use {
                FileInputStream(it.fileDescriptor).use { stream -> stream.readBytes().toString(Charsets.UTF_8).trim() }
            }
            assertTrue("Screenshot was not saved: $path ($size)", (size.toLongOrNull() ?: 0) > 0)
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "{0}_{1}")
        fun cases(): List<Array<String>> = listOf("classic_blue", "light", "amoled_black").flatMap { theme ->
            listOf("en", "ar", "fa", "he").map { language -> arrayOf(theme, language) }
        }
    }
}
