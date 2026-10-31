package com.streamvault.feature.settings.presentation

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.domain.manager.BackupExportPlan
import com.streamvault.feature.settings.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsBackupExportDialogTest {
    @get:Rule val composeRule = createComposeRule()
    private fun label(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)
    private fun waitForDialog() {
        // PremiumDialog blocks the opening gesture for its first 500 ms.
        composeRule.mainClock.advanceTimeBy(650)
        composeRule.waitForIdle()
    }

    @Test
    fun defaultsSelectAllAndProviderToggleIsPassedToConfirmation() {
        var exported: BackupExportPlan? = null
        composeRule.setContent { BackupExportDialog(BackupExportPlan(), {}, { exported = it }) }
        waitForDialog()
        composeRule.onNodeWithContentDescription(label(R.string.settings_backup_section_preferences)).assertIsOn()
        composeRule.onNodeWithContentDescription(label(R.string.settings_backup_section_providers)).assertIsOn().performClick()
        composeRule.onNode(hasText(label(R.string.settings_backup_data)) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(BackupExportPlan(exportProviders = false), exported) }
    }

    @Test
    fun emptySelectionCannotBeExported() {
        composeRule.setContent {
            BackupExportDialog(BackupExportPlan(false, false, false, false, false, false, false), {}, {})
        }
        waitForDialog()
        composeRule.onNode(hasText(label(R.string.settings_backup_data)) and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun cancelDoesNotStartAnExport() {
        var dismissed = false
        var exported = false
        composeRule.setContent { BackupExportDialog(BackupExportPlan(), { dismissed = true }, { exported = true }) }
        waitForDialog()
        composeRule.onNode(hasText(label(R.string.settings_cancel)) and hasClickAction())
            .performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals(true, dismissed); assertEquals(false, exported) }
    }

    @Test
    fun selectionSurvivesSavedStateRestoration() {
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent { BackupExportDialog(BackupExportPlan(), {}, {}) }
        waitForDialog()
        val providers = label(R.string.settings_backup_section_providers)
        composeRule.onNodeWithContentDescription(providers).performClick()
        restoration.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithContentDescription(providers).assertIsOff()
    }
}
