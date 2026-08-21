package com.streamvault.domain.usecase

import com.streamvault.domain.manager.BackupImportPlan
import com.streamvault.domain.manager.BackupImportResult
import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.BackupPreview
import com.streamvault.domain.manager.BackupRestoreOutcome
import com.streamvault.domain.model.Result
import javax.inject.Inject

data class InspectBackupCommand(
    val uriString: String
)

data class ImportBackupCommand(
    val uriString: String,
    val plan: BackupImportPlan = BackupImportPlan()
)

sealed class InspectBackupResult {
    data class Success(
        val uriString: String,
        val preview: BackupPreview,
        val defaultPlan: BackupImportPlan = BackupImportPlan()
    ) : InspectBackupResult()

    data class Error(val message: String, val exception: Throwable? = null) : InspectBackupResult()
}

sealed class ImportBackupResult {
    data class Success(val result: BackupImportResult) : ImportBackupResult() {
        val importedSummary: String
            get() {
                val baseSummary = when (result.outcome) {
                    BackupRestoreOutcome.COMPLETE ->
                        result.importedSections.joinToString().ifBlank { "Nothing imported" }
                    BackupRestoreOutcome.WAITING_FOR_SYNC ->
                        "Imported; ${result.pendingCount + result.unresolvedCount} item(s) are waiting for provider sync"
                    BackupRestoreOutcome.PARTIAL ->
                        "Partially imported: ${result.importedSections.joinToString().ifBlank { "nothing" }}"
                    BackupRestoreOutcome.FAILED_BEFORE_COMMIT -> "Nothing was committed"
                }
                val failureSummary = result.failedSections
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = "Failed: ")
                val unresolvedSummary = result.unresolvedReferences
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = "Unresolved: ")
                val recordingSummary = result.recordingScheduleImport
                    ?.takeIf { it.failedCount > 0 || it.skippedCount > 0 }
                    ?.let { summary ->
                        "Recording schedules: ${summary.importedCount} imported, ${summary.skippedCount} skipped, ${summary.failedCount} failed"
                    }
                return listOf(baseSummary, failureSummary, unresolvedSummary, recordingSummary)
                    .filterNotNull()
                    .joinToString(separator = ". ")
            }
    }

    data class Error(val message: String, val exception: Throwable? = null) : ImportBackupResult()
}

class ImportBackup @Inject constructor(
    private val backupManager: BackupManager
) {
    suspend fun inspect(command: InspectBackupCommand): InspectBackupResult {
        if (command.uriString.isBlank()) {
            return InspectBackupResult.Error("Backup source is unavailable.")
        }

        return when (val result = backupManager.inspectBackup(command.uriString)) {
            is Result.Success -> InspectBackupResult.Success(
                uriString = command.uriString,
                preview = result.data
            )

            is Result.Error -> InspectBackupResult.Error(result.message, result.exception)
            is Result.Loading -> InspectBackupResult.Error("Unexpected loading state")
        }
    }

    suspend fun confirm(command: ImportBackupCommand): ImportBackupResult {
        if (command.uriString.isBlank()) {
            return ImportBackupResult.Error("Backup source is unavailable.")
        }

        return when (val result = backupManager.importConfig(command.uriString, command.plan)) {
            is Result.Success -> ImportBackupResult.Success(result.data)
            is Result.Error -> ImportBackupResult.Error(result.message, result.exception)
            is Result.Loading -> ImportBackupResult.Error("Unexpected loading state")
        }
    }
}
