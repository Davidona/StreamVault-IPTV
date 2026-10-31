package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupImportResult
import com.streamvault.domain.manager.BackupRestoreOutcome
import com.streamvault.domain.usecase.ExportBackup
import com.streamvault.domain.usecase.ImportBackup
import com.streamvault.domain.usecase.ImportBackupCommand
import com.streamvault.domain.usecase.ImportBackupResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.kotlin.*

/** All 128 section selections, both conflict policies, and every durable restore outcome. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class SettingsBackupPlanMatrixTest(
    private val mask: Int,
    private val strategy: BackupConflictStrategy,
    private val outcome: BackupRestoreOutcome
) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "sections={0}, conflict={1}, outcome={2}")
        fun cases(): List<Array<Any>> = (0..127).flatMap { mask ->
            BackupConflictStrategy.entries.flatMap { strategy ->
                BackupRestoreOutcome.entries.map { arrayOf<Any>(mask, strategy, it) }
            }
        }
    }

    @Test
    fun selectionIsForwardedOnceAndOutcomeControlsRetry() = runTest(StandardTestDispatcher()) {
        val importer: ImportBackup = mock()
        val exporter: ExportBackup = mock()
        val plan = BackupImportPlan(
            importPreferences = mask and 1 != 0,
            importProviders = mask and 2 != 0,
            importSavedLibrary = mask and 4 != 0,
            importPlaybackHistory = mask and 8 != 0,
            importMultiViewPresets = mask and 16 != 0,
            importRecordingSchedules = mask and 32 != 0,
            importStremioAddons = mask and 64 != 0,
            conflictStrategy = strategy
        )
        val ui = MutableStateFlow(SettingsUiState(pendingBackupUri = "content://backup", backupImportPlan = plan))
        whenever(importer.confirm(any())).thenReturn(ImportBackupResult.Success(
            BackupImportResult(outcome = outcome, restoreJobId = "job")
        ))
        val actions = SettingsBackupActions(exporter, importer, ui)
        var followUps = 0
        actions.confirmBackupImport(this) { followUps++ }
        actions.confirmBackupImport(this) { followUps++ } // Double tap before coroutine dispatch.
        advanceUntilIdle()

        if (mask == 0) {
            verifyNoInteractions(importer)
            assertThat(ui.value.pendingBackupUri).isEqualTo("content://backup")
            assertThat(ui.value.backupImportPlan).isEqualTo(plan)
            assertThat(followUps).isEqualTo(0)
        } else {
            verify(importer, times(1)).confirm(ImportBackupCommand("content://backup", plan))
            val completed = outcome == BackupRestoreOutcome.COMPLETE || outcome == BackupRestoreOutcome.WAITING_FOR_SYNC
            assertThat(ui.value.pendingBackupUri).isEqualTo(if (completed) null else "content://backup")
            assertThat(ui.value.backupImportPlan).isEqualTo(if (completed) BackupImportPlan() else plan)
            assertThat(ui.value.pendingRestoreJobId).isEqualTo("job")
            assertThat(followUps).isEqualTo(if (completed) 1 else 0)
        }
        assertThat(ui.value.isImportingBackup).isFalse()
    }
}
