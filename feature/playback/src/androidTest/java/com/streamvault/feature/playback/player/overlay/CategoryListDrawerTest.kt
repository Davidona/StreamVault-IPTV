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
import com.streamvault.domain.model.Category
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
class CategoryListDrawerTest(private val theme: String, private val language: String) {
    @get:Rule val compose = createComposeRule()

    @Test
    fun selectionFocusBackAndScreenshots() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val config = Configuration(instrumentation.targetContext.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(language))
        }
        val context = instrumentation.targetContext.createConfigurationContext(config)
        val categories = listOf(
            Category(id = -999, name = "Favorites", count = 0),
            Category(id = -1, name = "dass", count = 3, isVirtual = true),
            Category(id = -1_000_000, name = "All Channels", count = 27637),
            Category(id = 20, name = "INTERNATIONAL SPORTS AND CHAMPIONSHIP LIVE EVENTS", count = 124),
            Category(id = 21, name = "Protected category", count = 14)
        )
        var dismissed = 0
        var selected: Category? = null
        val close = context.getString(R.string.player_close)
        compose.setContent {
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides config,
                LocalLayoutDirection provides if (language == "en") LayoutDirection.Ltr else LayoutDirection.Rtl
            ) {
                StreamVaultTheme(themeId = theme) {
                    Box(Modifier.fillMaxSize().background(AppColors.Surface)) {
                        CategoryListOverlay(
                            categories = categories, currentCategoryId = -1,
                            isCategoryLocked = { it.id == 21L },
                            onSelectCategory = { selected = it }, onDismiss = { dismissed++ }
                        )
                    }
                }
            }
        }
        compose.onNodeWithText("dass").assertIsFocused().assertIsSelected()
        capture("entry")
        categories.forEachIndexed { index, category ->
            compose.onNodeWithText(category.name).performScrollTo()
                .performSemanticsAction(SemanticsActions.RequestFocus)
            compose.onNodeWithText(category.name).assertIsFocused()
            capture("focus_$index")
        }
        compose.onNodeWithText("dass").assertIsSelected()
        compose.onNodeWithContentDescription(context.getString(R.string.home_locked_short)).assertExists()
        compose.onNodeWithContentDescription(close).performSemanticsAction(SemanticsActions.RequestFocus)
        capture("focus_close")
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(1, dismissed)
        compose.onNodeWithText("dass").performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitForIdle()
        assertEquals(categories[1], selected)
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
        assertEquals(2, dismissed)
    }

    private fun capture(state: String) {
        compose.waitForIdle()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val output = "/sdcard/Download/streamvault-category-drawer"
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
