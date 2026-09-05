package com.streamvault.feature.settings.presentation

import android.app.Activity
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.feature.settings.databinding.SettingsBackupImportPreviewViewsBinding
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupImportPreviewViewControllerTest {

    private lateinit var binding: SettingsBackupImportPreviewViewsBinding
    private lateinit var controller: BackupImportPreviewViewController
    private var selectedStrategy: BackupConflictStrategy? = null

    @Before
    fun setUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        binding = SettingsBackupImportPreviewViewsBinding.inflate(activity.layoutInflater)
        activity.setContentView(binding.root)
        controller = BackupImportPreviewViewController(
            binding = binding,
            callbacks = BackupImportPreviewViewController.Callbacks(
                onDismiss = {},
                onStrategySelected = { selectedStrategy = it },
                onImportPreferencesChanged = {},
                onImportProvidersChanged = {},
                onImportSavedLibraryChanged = {},
                onImportPlaybackHistoryChanged = {},
                onImportMultiViewChanged = {},
                onImportRecordingSchedulesChanged = {},
                onConfirm = {},
            ),
        )
    }

    @Test
    fun renderDisablesImportWhenNoSectionIsSelected() {
        controller.render(
            preview = preview(),
            plan = BackupImportPlan(
                importPreferences = false,
                importProviders = false,
                importSavedLibrary = false,
                importPlaybackHistory = false,
                importMultiViewPresets = false,
                importRecordingSchedules = false,
            ),
            isImporting = false,
        )

        assertThat(binding.confirmButton.isEnabled).isFalse()
    }

    @Test
    fun strategySelectionEmitsReplaceExisting() {
        controller.render(
            preview = preview(),
            plan = BackupImportPlan(importPreferences = true),
            isImporting = false,
        )

        binding.replaceExistingButton.performClick()

        assertThat(selectedStrategy).isEqualTo(BackupConflictStrategy.REPLACE_EXISTING)
    }

    private fun preview() = BackupPreview(
        version = 14,
        providerCount = 3,
        favoriteCount = 12,
        groupCount = 4,
        playbackHistoryCount = 8,
        multiViewPresetCount = 2,
        preferenceCount = 10,
        protectedCategoryCount = 1,
        scheduledRecordingCount = 3,
        providerConflicts = 1,
        favoriteConflicts = 2,
        groupConflicts = 0,
        historyConflicts = 1,
        protectedCategoryConflicts = 0,
        recordingConflicts = 1,
    )
}
