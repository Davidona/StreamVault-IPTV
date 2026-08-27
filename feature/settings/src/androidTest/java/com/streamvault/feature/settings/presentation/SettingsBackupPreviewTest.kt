package com.streamvault.feature.settings.presentation

import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsBackupPreviewTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun previewDialogRendersFeatureOwnedTitle() {
        composeRule.setContent {
            BackupImportPreviewDialog(
                preview = BackupPreview(
                    version = 14,
                    providerCount = 1,
                    favoriteCount = 2,
                    groupCount = 0,
                    playbackHistoryCount = 3,
                    multiViewPresetCount = 0,
                    preferenceCount = 4,
                    protectedCategoryCount = 0,
                    scheduledRecordingCount = 0,
                    providerConflicts = 0,
                    favoriteConflicts = 0,
                    groupConflicts = 0,
                    historyConflicts = 0,
                    protectedCategoryConflicts = 0,
                    recordingConflicts = 0,
                ),
                plan = BackupImportPlan(),
                onDismiss = {},
                onStrategySelected = {},
                onImportPreferencesChanged = {},
                onImportProvidersChanged = {},
                onImportSavedLibraryChanged = {},
                onImportPlaybackHistoryChanged = {},
                onImportMultiViewChanged = {},
                onImportRecordingSchedulesChanged = {},
                onConfirm = {},
            )
        }

        composeRule.onNodeWithText("Review Backup Import").assertExists()
    }
}
