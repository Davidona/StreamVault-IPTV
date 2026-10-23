package com.streamvault.domain.usecase

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.manager.BackupConflictStrategy
import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupImportResult
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.RecordingScheduleImportDisposition
import com.streamvault.domain.manager.RecordingScheduleImportOutcome
import com.streamvault.domain.manager.RecordingScheduleImportSummary
import com.streamvault.domain.model.Result
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ImportBackupTest {

    @Test
    fun returns_preview_with_default_plan() = runTest {
        val manager = FakeImportBackupManager(
            inspectResult = Result.success(
                BackupPreview(
                    version = 4,
                    providerCount = 1,
                    favoriteCount = 1,
                    groupCount = 1,
                    playbackHistoryCount = 1,
                    multiViewPresetCount = 1,
                    preferenceCount = 1,
                    protectedCategoryCount = 0,
                    scheduledRecordingCount = 0,
                    providerConflicts = 0,
                    favoriteConflicts = 0,
                    groupConflicts = 0,
                    historyConflicts = 0,
                    protectedCategoryConflicts = 0,
                    recordingConflicts = 0
                )
            )
        )
        val useCase = ImportBackup(manager)

        val result = useCase.inspect(InspectBackupCommand(uriString = "content://backup.json"))

        assertThat(result).isInstanceOf(InspectBackupResult.Success::class.java)
        val success = result as InspectBackupResult.Success
        assertThat(success.uriString).isEqualTo("content://backup.json")
        assertThat(success.defaultPlan).isEqualTo(BackupImportPlan())
        assertThat(manager.lastInspectUri).isEqualTo("content://backup.json")
    }

    @Test
    fun summarizes_imported_sections() = runTest {
        val manager = FakeImportBackupManager(
            importResult = Result.success(
                BackupImportResult(importedSections = listOf("Preferences", "Providers"))
            )
        )
        val useCase = ImportBackup(manager)
        val plan = BackupImportPlan(conflictStrategy = BackupConflictStrategy.REPLACE_EXISTING)

        val result = useCase.confirm(
            ImportBackupCommand(
                uriString = "content://backup.json",
                plan = plan
            )
        )

        assertThat(result).isInstanceOf(ImportBackupResult.Success::class.java)
        val success = result as ImportBackupResult.Success
        assertThat(success.importedSummary).isEqualTo("Preferences, Providers")
        assertThat(manager.lastImportCall).isEqualTo("content://backup.json" to plan)
    }

    @Test
    fun rejects_blank_source_for_import() = runTest {
        val useCase = ImportBackup(FakeImportBackupManager())

        val result = useCase.confirm(ImportBackupCommand(uriString = ""))

        assertThat(result).isInstanceOf(ImportBackupResult.Error::class.java)
        assertThat((result as ImportBackupResult.Error).message).isEqualTo("Backup source is unavailable.")
    }

    @Test
    fun import_summary_includes_recording_schedule_failures() = runTest {
        val manager = FakeImportBackupManager(
            importResult = Result.success(
                BackupImportResult(
                    importedSections = listOf("Providers", "Recording Schedules"),
                    recordingScheduleImport = RecordingScheduleImportSummary(
                        outcomes = listOf(
                            RecordingScheduleImportOutcome(
                                channelName = "BBC One",
                                programTitle = "World News",
                                scheduledStartMs = 1_000L,
                                scheduledEndMs = 2_000L,
                                disposition = RecordingScheduleImportDisposition.REPLACED_EXISTING
                            ),
                            RecordingScheduleImportOutcome(
                                channelName = "BBC Two",
                                programTitle = "Documentary",
                                scheduledStartMs = 3_000L,
                                scheduledEndMs = 4_000L,
                                disposition = RecordingScheduleImportDisposition.FAILED,
                                reason = "Provider connection limit reached."
                            )
                        )
                    )
                )
            )
        )
        val useCase = ImportBackup(manager)

        val result = useCase.confirm(ImportBackupCommand(uriString = "content://backup.json"))

        assertThat(result).isInstanceOf(ImportBackupResult.Success::class.java)
        val success = result as ImportBackupResult.Success
        assertThat(success.importedSummary)
            .isEqualTo("Providers, Recording Schedules. Recording schedules: 1 imported, 0 skipped, 1 failed")
    }
}

private class FakeImportBackupManager(
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
) : com.streamvault.domain.manager.BackupManager {
    var lastInspectUri: String? = null
    var lastImportCall: Pair<String, BackupImportPlan>? = null

    override suspend fun exportConfig(uriString: String, plan: com.streamvault.domain.manager.BackupExportPlan): Result<Unit> = error("Not used in test")

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