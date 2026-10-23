package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupImportResult
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.model.Result
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ExportBackupTest {
    @Test
    fun forwards_selected_sections_unchanged() = runTest {
        val manager = FakeExportBackupManager()
        val selection = com.streamvault.domain.manager.BackupExportPlan(exportProviders = false, exportPlaybackHistory = false)
        assertThat(ExportBackup(manager)(ExportBackupCommand("content://backup", selection))).isEqualTo(ExportBackupResult.Success)
        assertThat(manager.lastExportPlan).isEqualTo(selection)
    }

    @Test
    fun rejects_blank_destination() = runTest {
        val useCase = ExportBackup(FakeExportBackupManager())

        val result = useCase(ExportBackupCommand(uriString = ""))

        assertThat(result).isInstanceOf(ExportBackupResult.Error::class.java)
        assertThat((result as ExportBackupResult.Error).message).isEqualTo("Backup destination is unavailable.")
    }

    @Test
    fun delegates_export_to_backup_manager() = runTest {
        val manager = FakeExportBackupManager(exportResult = Result.success(Unit))
        val useCase = ExportBackup(manager)

        val result = useCase(ExportBackupCommand(uriString = "content://backup.json"))

        assertThat(result).isEqualTo(ExportBackupResult.Success)
        assertThat(manager.lastExportUri).isEqualTo("content://backup.json")
    }
}

private class FakeExportBackupManager(
    private val exportResult: Result<Unit> = Result.success(Unit),
    private val inspectResult: Result<BackupPreview> = Result.success(
        BackupPreview(
            version = 4,
            providerCount = 1,
            favoriteCount = 2,
            groupCount = 3,
            playbackHistoryCount = 4,
            multiViewPresetCount = 1,
            preferenceCount = 5,
            protectedCategoryCount = 0,
            scheduledRecordingCount = 0,
            providerConflicts = 0,
            favoriteConflicts = 0,
            groupConflicts = 0,
            historyConflicts = 0,
            protectedCategoryConflicts = 0,
            recordingConflicts = 0
        )
    ),
    private val importResult: Result<BackupImportResult> = Result.success(BackupImportResult())
) : BackupManager {
    var lastExportUri: String? = null
    var lastExportPlan: com.streamvault.domain.manager.BackupExportPlan? = null
    var lastInspectUri: String? = null
    var lastImportCall: Pair<String, BackupImportPlan>? = null

    override suspend fun exportConfig(uriString: String, plan: com.streamvault.domain.manager.BackupExportPlan): Result<Unit> {
        lastExportUri = uriString
        lastExportPlan = plan
        return exportResult
    }

    override suspend fun inspectBackup(uriString: String): Result<BackupPreview> {
        lastInspectUri = uriString
        return inspectResult
    }

    override suspend fun importConfig(
        uriString: String,
        plan: BackupImportPlan
    ): Result<BackupImportResult> {
        lastImportCall = uriString to plan
        return importResult
    }
}
