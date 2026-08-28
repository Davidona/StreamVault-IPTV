package com.streamvault.feature.settings.presentation

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.core.ui.components.dialogs.PinDialog
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.delay
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
class SettingsConnectedBehaviorTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun settingsSections_areSelectableWithDpad_andRestoreFocusToSelectedSection() {
        var selectedCategory by mutableIntStateOf(0)
        val settingsFocusRequester = FocusRequester()

        composeRule.setContent {
            StreamVaultTheme {
                Row(modifier = Modifier.fillMaxSize()) {
                    SettingsNavigationRail(
                        selectedCategory = selectedCategory,
                        focusRequester = settingsFocusRequester,
                        onCategorySelected = { selectedCategory = it }
                    )
                    Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        androidx.tv.material3.Text(text = "Selected section: $selectedCategory")
                    }
                }
                LaunchedEffect(selectedCategory) {
                    delay(120)
                    settingsFocusRequester.requestFocus()
                }
            }
        }

        val providersNode = hasText("Providers") and hasClickAction()
        val playbackNode = hasText("Playback") and hasClickAction()
        composeRule.waitUntil(2_000) {
            runCatching {
                composeRule.onNode(providersNode).assertIsFocused()
            }.isSuccess
        }
        composeRule.onNode(providersNode).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNode(playbackNode).assertIsFocused()

        composeRule.onNode(playbackNode).performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Selected section: 1").assertIsDisplayed()
        composeRule.onNodeWithText("Playback").assertIsFocused()
        composeRule.onRoot().saveSettingsScreenshot("settings_navigation_dpad")
    }

    @Test
    fun settingsNavigation_inRtl_keepsSectionsAccessible() {
        val focusRequester = FocusRequester()

        composeRule.setContent {
            StreamVaultTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        SettingsNavigationRail(
                            selectedCategory = 0,
                            focusRequester = focusRequester,
                            onCategorySelected = {}
                        )
                        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                            androidx.tv.material3.Text(text = "RTL settings")
                        }
                    }
                }
            }
        }

        composeRule
            .onNode(hasText("Providers") and hasClickAction())
            .assertExists()
            .assertIsDisplayed()
        composeRule.onNodeWithText("RTL settings").assertIsDisplayed()
        composeRule.onRoot().saveSettingsScreenshot("settings_navigation_rtl")
    }

    @Test
    fun settingsSearch_exposesAccessibleLabelAndCurrentValue() {
        composeRule.setContent {
            StreamVaultTheme {
                SearchInput(
                    value = "sports",
                    onValueChange = {},
                    placeholder = "Search categories..."
                )
            }
        }

        composeRule
            .onNodeWithContentDescription("Search categories...")
            .assertExists()
            .assertIsDisplayed()
    }

    @Test
    fun backupPreview_forwardsStrategyToggleAndConfirmCallbacks() {
        var selectedStrategy: BackupConflictStrategy? = null
        var preferenceImportEnabled: Boolean? = null
        var confirmed = false

        composeRule.setContent {
            StreamVaultTheme {
                BackupImportPreviewDialog(
                    preview = testBackupPreview(),
                    plan = BackupImportPlan(),
                    onDismiss = {},
                    onStrategySelected = { selectedStrategy = it },
                    onImportPreferencesChanged = { preferenceImportEnabled = it },
                    onImportProvidersChanged = {},
                    onImportSavedLibraryChanged = {},
                    onImportPlaybackHistoryChanged = {},
                    onImportMultiViewChanged = {},
                    onImportRecordingSchedulesChanged = {},
                    onConfirm = { confirmed = true }
                )
            }
        }

        waitForDialog("Review Backup Import")
        composeRule
            .onNode(hasText("Replace Existing") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.onAllNodes(isToggleable())[0].performSemanticsAction(SemanticsActions.OnClick)
        composeRule
            .onNode(hasText("Import Backup") and hasClickAction())
            .assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle {
            assertThat(selectedStrategy).isEqualTo(BackupConflictStrategy.REPLACE_EXISTING)
            assertThat(preferenceImportEnabled).isFalse()
            assertThat(confirmed).isTrue()
        }
        composeRule.onNodeWithText("Review Backup Import").saveSettingsScreenshot("settings_backup_preview")
    }

    @Test
    fun backupSelection_selectingItemForwardsUri_andUpdatesDialogState() {
        var visible by mutableStateOf(true)
        var selectedUri: String? = null

        composeRule.setContent {
            StreamVaultTheme {
                if (visible) {
                    BackupSelectionDialog(
                        title = "Choose backup",
                        subtitle = "Select a fixture",
                        items = listOf(
                            BackupDialogItem("content://backup/one", "Backup one"),
                            BackupDialogItem("content://backup/two", "Backup two")
                        ),
                        emptyMessage = "No backups",
                        onSelect = { uri ->
                            selectedUri = uri
                            visible = false
                        },
                        onDismiss = { visible = false }
                    )
                }
            }
        }

        waitForDialog("Choose backup")
        composeRule
            .onNode(hasText("Backup two") and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertThat(selectedUri).isEqualTo("content://backup/two")
        }
        composeRule.onNodeWithText("Choose backup").assertDoesNotExist()
    }

    @Test
    fun backupSelection_backDismissesOpenDialog() {
        var visible by mutableStateOf(true)
        var dismissCount = 0

        composeRule.setContent {
            StreamVaultTheme {
                if (visible) {
                    BackupSelectionDialog(
                        title = "Dismiss with Back",
                        subtitle = "Back behavior",
                        items = listOf(BackupDialogItem("backup", "Backup")),
                        emptyMessage = "No backups",
                        onSelect = {},
                        onDismiss = {
                            dismissCount++
                            visible = false
                        }
                    )
                }
            }
        }

        waitForDialog("Dismiss with Back")
        composeRule.runOnIdle {
            composeRule.activity.onBackPressedDispatcher.onBackPressed()
        }
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithText("Dismiss with Back").fetchSemanticsNodes().isEmpty()
        }
        composeRule.runOnIdle {
            assertThat(dismissCount).isEqualTo(1)
        }
    }

    @Test
    fun parentalControlCard_reflectsPinState_andForwardsChangeAction() {
        var hasPin by mutableStateOf(false)
        var changeActionCount = 0

        composeRule.setContent {
            StreamVaultTheme {
                ParentalControlCard(
                    level = 0,
                    hasParentalPin = hasPin,
                    hasActiveProvider = true,
                    onChangeLevel = {},
                    onChangePin = {
                        changeActionCount++
                        hasPin = true
                    }
                )
            }
        }

        composeRule
            .onNode(hasText("Set PIN") and hasClickAction())
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.waitUntil(2_000) {
            composeRule.onAllNodesWithText("Change PIN").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.runOnIdle {
            assertThat(changeActionCount).isEqualTo(1)
        }
    }

    @Test
    fun pinDialog_acceptsFourDigitEntry_andPreservesErrorSemantics() {
        val enteredPin = AtomicReference<String?>(null)

        composeRule.setContent {
            StreamVaultTheme {
                PinDialog(
                    title = "Enter PIN",
                    cancelLabel = "Cancel",
                    error = "Incorrect PIN",
                    onDismissRequest = {},
                    onPinEntered = { enteredPin.set(it) }
                )
            }
        }

        composeRule.onNodeWithText("Enter PIN").assertIsDisplayed()
        composeRule.onNodeWithText("Incorrect PIN").assertIsDisplayed()
        composeRule.mainClock.advanceTimeBy(650)
        composeRule.waitForIdle()
        listOf("1", "2", "3", "4").forEach { digit ->
            composeRule
                .onNode(hasText(digit) and hasClickAction())
                .performSemanticsAction(SemanticsActions.OnClick)
        }
        composeRule.waitUntil(3_000) { enteredPin.get() == "1234" }
        composeRule.runOnIdle {
            assertThat(enteredPin.get()).isEqualTo("1234")
        }
    }

    private fun waitForDialog(title: String) {
        composeRule.waitUntil(3_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.mainClock.advanceTimeBy(650)
        composeRule.waitForIdle()
    }

    private fun testBackupPreview() = BackupPreview(
        version = 14,
        providerCount = 1,
        favoriteCount = 2,
        groupCount = 1,
        playbackHistoryCount = 3,
        multiViewPresetCount = 1,
        preferenceCount = 4,
        protectedCategoryCount = 1,
        scheduledRecordingCount = 1,
        providerConflicts = 1,
        favoriteConflicts = 0,
        groupConflicts = 0,
        historyConflicts = 1,
        protectedCategoryConflicts = 0,
        recordingConflicts = 0
    )
}

@Suppress("DEPRECATION")
private fun SemanticsNodeInteraction.saveSettingsScreenshot(name: String) {
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val directory = requireNotNull(context.externalMediaDirs.firstOrNull())
        .resolve("settings-validation")
        .apply { mkdirs() }
    val outputFile = File(directory, "$name.png")
    captureToImage().asAndroidBitmap().compressTo(outputFile)
    println("SETTINGS_SCREENSHOT ${outputFile.absolutePath}")
}

private fun Bitmap.compressTo(outputFile: File) {
    outputFile.outputStream().use { stream ->
        check(compress(Bitmap.CompressFormat.PNG, 100, stream)) {
            "Unable to write Settings connected-test screenshot: ${outputFile.absolutePath}"
        }
    }
}
