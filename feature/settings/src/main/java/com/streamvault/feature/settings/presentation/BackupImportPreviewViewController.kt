package com.streamvault.feature.settings.presentation

import android.widget.CompoundButton
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.feature.settings.R
import com.streamvault.feature.settings.databinding.SettingsBackupImportPreviewViewsBinding

class BackupImportPreviewViewController(
    private val binding: SettingsBackupImportPreviewViewsBinding,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val onDismiss: () -> Unit,
        val onStrategySelected: (BackupConflictStrategy) -> Unit,
        val onImportPreferencesChanged: (Boolean) -> Unit,
        val onImportProvidersChanged: (Boolean) -> Unit,
        val onImportSavedLibraryChanged: (Boolean) -> Unit,
        val onImportPlaybackHistoryChanged: (Boolean) -> Unit,
        val onImportMultiViewChanged: (Boolean) -> Unit,
        val onImportRecordingSchedulesChanged: (Boolean) -> Unit,
        val onConfirm: () -> Unit,
    )

    init {
        binding.cancelButton.setOnClickListener { callbacks.onDismiss() }
        binding.confirmButton.setOnClickListener { callbacks.onConfirm() }
        binding.strategyGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                binding.keepExistingButton.id -> callbacks.onStrategySelected(
                    BackupConflictStrategy.KEEP_EXISTING
                )
                binding.replaceExistingButton.id -> callbacks.onStrategySelected(
                    BackupConflictStrategy.REPLACE_EXISTING
                )
            }
        }
        bindSwitch(binding.importPreferencesSwitch, callbacks.onImportPreferencesChanged)
        bindSwitch(binding.importProvidersSwitch, callbacks.onImportProvidersChanged)
        bindSwitch(binding.importSavedSwitch, callbacks.onImportSavedLibraryChanged)
        bindSwitch(binding.importHistorySwitch, callbacks.onImportPlaybackHistoryChanged)
        bindSwitch(binding.importMultiViewSwitch, callbacks.onImportMultiViewChanged)
        bindSwitch(binding.importRecordingsSwitch, callbacks.onImportRecordingSchedulesChanged)
    }

    fun render(preview: BackupPreview, plan: BackupImportPlan, isImporting: Boolean) {
        val context = binding.root.context
        binding.previewTitle.text = context.getString(R.string.settings_backup_preview_title)
        binding.previewSubtitle.text = context.getString(
            R.string.settings_backup_preview_subtitle,
            preview.version,
        )
        binding.conflictStrategyLabel.setText(R.string.settings_backup_conflict_strategy)
        binding.importSectionsLabel.setText(R.string.settings_backup_import_sections)
        binding.scrollHint.setText(R.string.settings_backup_preview_scroll_hint)

        renderSummary(
            title = binding.preferencesTitle,
            conflict = binding.preferencesConflict,
            count = binding.preferencesCount,
            titleRes = R.string.settings_backup_section_preferences,
            itemCount = preview.preferenceCount,
            conflictCount = 0,
        )
        renderSummary(
            title = binding.providersTitle,
            conflict = binding.providersConflict,
            count = binding.providersCount,
            titleRes = R.string.settings_backup_section_providers,
            itemCount = preview.providerCount,
            conflictCount = preview.providerConflicts,
        )
        renderSummary(
            title = binding.savedTitle,
            conflict = binding.savedConflict,
            count = binding.savedCount,
            titleRes = R.string.settings_backup_section_saved,
            itemCount = preview.favoriteCount + preview.groupCount + preview.protectedCategoryCount,
            conflictCount = preview.favoriteConflicts + preview.groupConflicts +
                preview.protectedCategoryConflicts,
        )
        renderSummary(
            title = binding.historyTitle,
            conflict = binding.historyConflict,
            count = binding.historyCount,
            titleRes = R.string.settings_backup_section_history,
            itemCount = preview.playbackHistoryCount,
            conflictCount = preview.historyConflicts,
        )
        renderSummary(
            title = binding.multiViewTitle,
            conflict = binding.multiViewConflict,
            count = binding.multiViewCount,
            titleRes = R.string.settings_backup_section_multiview,
            itemCount = preview.multiViewPresetCount,
            conflictCount = 0,
        )
        renderSummary(
            title = binding.recordingsTitle,
            conflict = binding.recordingsConflict,
            count = binding.recordingsCount,
            titleRes = R.string.settings_backup_section_recordings,
            itemCount = preview.scheduledRecordingCount,
            conflictCount = preview.recordingConflicts,
        )

        binding.strategyGroup.setOnCheckedChangeListener(null)
        binding.strategyGroup.check(
            when (plan.conflictStrategy) {
                BackupConflictStrategy.REPLACE_EXISTING -> binding.replaceExistingButton.id
                BackupConflictStrategy.KEEP_EXISTING -> binding.keepExistingButton.id
            }
        )
        binding.strategyGroup.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                binding.keepExistingButton.id -> callbacks.onStrategySelected(
                    BackupConflictStrategy.KEEP_EXISTING
                )
                binding.replaceExistingButton.id -> callbacks.onStrategySelected(
                    BackupConflictStrategy.REPLACE_EXISTING
                )
            }
        }

        setSwitchChecked(binding.importPreferencesSwitch, plan.importPreferences)
        setSwitchChecked(binding.importProvidersSwitch, plan.importProviders)
        setSwitchChecked(binding.importSavedSwitch, plan.importSavedLibrary)
        setSwitchChecked(binding.importHistorySwitch, plan.importPlaybackHistory)
        setSwitchChecked(binding.importMultiViewSwitch, plan.importMultiViewPresets)
        setSwitchChecked(binding.importRecordingsSwitch, plan.importRecordingSchedules)

        val anyEnabled = plan.importPreferences || plan.importProviders || plan.importSavedLibrary ||
            plan.importPlaybackHistory || plan.importMultiViewPresets ||
            plan.importRecordingSchedules
        binding.confirmButton.isEnabled = anyEnabled && !isImporting
        binding.cancelButton.isEnabled = !isImporting
        binding.strategyGroup.isEnabled = !isImporting
        binding.importPreferencesSwitch.isEnabled = !isImporting
        binding.importProvidersSwitch.isEnabled = !isImporting
        binding.importSavedSwitch.isEnabled = !isImporting
        binding.importHistorySwitch.isEnabled = !isImporting
        binding.importMultiViewSwitch.isEnabled = !isImporting
        binding.importRecordingsSwitch.isEnabled = !isImporting
    }

    fun requestInitialFocus(): Boolean = binding.keepExistingButton.requestFocus()

    private fun renderSummary(
        title: android.widget.TextView,
        conflict: android.widget.TextView,
        count: android.widget.TextView,
        titleRes: Int,
        itemCount: Int,
        conflictCount: Int,
    ) {
        val context = binding.root.context
        title.setText(titleRes)
        conflict.text = if (conflictCount > 0) {
            context.getString(R.string.settings_backup_conflict_count, conflictCount)
        } else {
            context.getString(R.string.settings_backup_no_conflicts)
        }
        count.text = context.getString(R.string.settings_backup_item_count, itemCount)
    }

    private fun bindSwitch(
        button: CompoundButton,
        onChanged: (Boolean) -> Unit,
    ) {
        button.setOnCheckedChangeListener { _, checked -> onChanged(checked) }
    }

    private fun setSwitchChecked(button: CompoundButton, checked: Boolean) {
        button.setOnCheckedChangeListener(null)
        button.isChecked = checked
        button.setOnCheckedChangeListener { _, value ->
            when (button.id) {
                binding.importPreferencesSwitch.id -> callbacks.onImportPreferencesChanged(value)
                binding.importProvidersSwitch.id -> callbacks.onImportProvidersChanged(value)
                binding.importSavedSwitch.id -> callbacks.onImportSavedLibraryChanged(value)
                binding.importHistorySwitch.id -> callbacks.onImportPlaybackHistoryChanged(value)
                binding.importMultiViewSwitch.id -> callbacks.onImportMultiViewChanged(value)
                binding.importRecordingsSwitch.id -> callbacks.onImportRecordingSchedulesChanged(value)
            }
        }
    }
}
