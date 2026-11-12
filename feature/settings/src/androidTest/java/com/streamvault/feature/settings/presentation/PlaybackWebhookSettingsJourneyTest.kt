package com.streamvault.feature.settings.presentation

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.input.key.Key
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.playback.events.*
import com.streamvault.feature.settings.R
import java.io.File
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse

/** Production page with a deterministic port; validates layout/focus, not HTTP delivery. */
class PlaybackWebhookSettingsJourneyTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private class Webhooks : PlaybackWebhooks {
        override val configuration = MutableStateFlow(PlaybackWebhookConfiguration())
        override val diagnostics = MutableStateFlow<Map<String, PlaybackWebhookDiagnostic>>(emptyMap())
        override suspend fun save(configuration: PlaybackWebhookConfiguration) {
            require(configuration.destinations.all { it.name.isNotBlank() && it.url.startsWith("http") })
            this.configuration.value = configuration
        }
        override fun report(owner: String, observation: PlaybackObservation) = Unit
        override fun finish(owner: String) = Unit
        override suspend fun test(destinationId: String) = Unit
        override fun preview(destination: PlaybackWebhookDestination) = "{\"schema_version\":1,\"event\":\"stop\",\"type\":\"movie\",\"title\":\"Example\",\"watched\":true}"
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun remoteActivationOpensEditorAndBackReturnsToList() {
        val model = PlaybackWebhookSettingsViewModel(Webhooks())
        rule.setContent {
            StreamVaultTheme {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    PlaybackWebhookSettingsPage(viewModel = model)
                }
            }
        }
        rule.onNodeWithText(rule.activity.getString(R.string.webhook_add))
            .performScrollTo().performSemanticsAction(SemanticsActions.RequestFocus)
            .performKeyInput { pressKey(Key.Enter) }
        rule.onNodeWithText(rule.activity.getString(R.string.webhook_name)).assertExists()
        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithText(rule.activity.getString(R.string.webhook_add)).assertExists()
    }

    @Test fun longestHelpTranslationsFitAPhoneWidthWithoutTruncation() {
        var language by mutableStateOf("fil")
        var translatedContext = rule.activity as android.content.Context
        val model = PlaybackWebhookSettingsViewModel(Webhooks())
        rule.setContent {
            val base = LocalContext.current
            val context = remember(language) { base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
            }) }
            translatedContext = context
            CompositionLocalProvider(LocalContext provides context, LocalResources provides context.resources,
                LocalConfiguration provides context.resources.configuration) {
                StreamVaultTheme {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        PlaybackWebhookSettingsPage(viewModel = model)
                    }
                }
            }
        }
        for (locale in listOf("fil", "el")) {
            rule.runOnIdle { language = locale }
            rule.waitForIdle()
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(translatedContext.getString(R.string.webhook_help)).performScrollTo()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertFalse("$locale help was truncated", results.single().hasVisualOverflow)
        }
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test fun captureThemesFocusErrorsPreviewAndRtl() {
        var theme by mutableStateOf("classic_blue")
        var language by mutableStateOf("en")
        var compositionKey by mutableIntStateOf(0)
        val restingFocus = FocusRequester()
        val service = Webhooks()
        val model = PlaybackWebhookSettingsViewModel(service)
        val directory = File(rule.activity.getExternalFilesDir(null), "webhook-visuals").apply { mkdirs() }
        var localContext = rule.activity as android.content.Context
        var focusManager: androidx.compose.ui.focus.FocusManager? = null
        rule.setContent {
            focusManager = LocalFocusManager.current
            val base = LocalContext.current
            val context = remember(language) { base.createConfigurationContext(Configuration(base.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
            }) }
            localContext = context
            CompositionLocalProvider(LocalContext provides context,
                LocalResources provides context.resources,
                LocalConfiguration provides context.resources.configuration,
                LocalLayoutDirection provides if (language in listOf("ar", "fa", "he")) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                StreamVaultTheme(theme) {
                    InterceptPlatformTextInput(interceptor = { _, _ -> kotlinx.coroutines.awaitCancellation() }) {
                    key(compositionKey) {
                        Column(Modifier.fillMaxSize().background(AppColors.Canvas).padding(LocalAppSpacing.current.lg)) {
                            WebhookAction(context.getString(R.string.webhook_back), modifier = Modifier.testTag("resting_focus").focusRequester(restingFocus)) {}
                            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                                PlaybackWebhookSettingsPage(viewModel = model)
                            }
                        }
                    }
                    }
                }
            }
        }
        fun text(id: Int) = localContext.getString(id)
        fun capture(name: String, focusId: Int) {
            rule.mainClock.advanceTimeBy(600)
            rule.runOnIdle {
                focusManager?.clearFocus(force = true)
                restingFocus.requestFocus()
                (rule.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0)
            }
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
            android.os.SystemClock.sleep(300)
            assertAppWindow()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                File(directory, "${theme}_${language}_${name}_default.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            // Freeze the clock for focused input captures: a blinking cursor is deliberately
            // non-idle. Advance enough frames to settle focus and label transitions explicitly.
            rule.mainClock.autoAdvance = false
            rule.onAllNodes(hasText(text(focusId)) and !hasTestTag("resting_focus")).onLast()
                .performSemanticsAction(SemanticsActions.RequestFocus)
            rule.mainClock.advanceTimeBy(600)
            rule.waitForIdle()
            rule.runOnIdle {
                (rule.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .hideSoftInputFromWindow(rule.activity.window.decorView.windowToken, 0)
            }
            android.os.SystemClock.sleep(300)
            assertAppWindow()
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                File(directory, "${theme}_${language}_${name}_focused.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            rule.runOnIdle { focusManager?.clearFocus(force = true); restingFocus.requestFocus() }
            rule.mainClock.advanceTimeBy(600)
            rule.mainClock.autoAdvance = true
        }
        for (palette in listOf("classic_blue", "light", "amoled_black")) {
            rule.runOnIdle { theme = palette; language = "en"; compositionKey++; model.failed.value = false; service.configuration.value = PlaybackWebhookConfiguration() }
            rule.waitForIdle()
            capture("list", R.string.webhook_enabled)
            rule.onNodeWithText(text(R.string.webhook_add)).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            rule.waitForIdle()
            capture("editor", R.string.webhook_name)
            rule.onNodeWithText(text(R.string.webhook_save)).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            rule.waitForIdle()
            rule.onNodeWithText(text(R.string.webhook_error)).performScrollTo().assertIsDisplayed()
            capture("error", R.string.webhook_enabled)
            rule.onNodeWithText(text(R.string.webhook_interval)).performScrollTo()
            capture("options", R.string.webhook_interval)
            rule.onNodeWithText(text(R.string.webhook_watched)).performScrollTo()
            capture("events", R.string.webhook_watched)
            rule.onNodeWithText(text(R.string.webhook_preview)).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            rule.waitForIdle()
            capture("preview", R.string.webhook_back)
        }
        for (locale in listOf("ar", "fa", "he", "de")) {
            rule.runOnIdle { language = locale; compositionKey++; model.failed.value = false }
            rule.waitForIdle()
            capture("layout", R.string.webhook_enabled)
            rule.onNodeWithText(text(R.string.webhook_add)).performScrollTo().performSemanticsAction(SemanticsActions.OnClick)
            rule.waitForIdle()
            capture("editor", R.string.webhook_name)
        }
    }

    private fun assertAppWindow() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        repeat(20) {
            val activePackage = automation.rootInActiveWindow?.packageName?.toString()
            // Compose can temporarily suppress its accessibility root during semantics work.
            // Screenshots are also inspected visually; null does not identify a system window.
            if (activePackage == null || activePackage == rule.activity.packageName) return
            android.os.SystemClock.sleep(100)
        }
        val directory = File(rule.activity.getExternalFilesDir(null), "webhook-visuals")
        automation.takeScreenshot().let { bitmap -> File(directory, "obscured.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        } }
        error("Active window ${automation.rootInActiveWindow?.packageName}; expected ${rule.activity.packageName}")
    }
}
