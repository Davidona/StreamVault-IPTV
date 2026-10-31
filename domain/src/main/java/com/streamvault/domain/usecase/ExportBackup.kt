package com.streamvault.domain.usecase

import com.streamvault.domain.manager.BackupManager
import com.streamvault.domain.manager.BackupExportPlan
import com.streamvault.domain.model.Result
import javax.inject.Inject

data class ExportBackupCommand(
    val uriString: String,
    val plan: BackupExportPlan = BackupExportPlan()
)

sealed class ExportBackupResult {
    data object Success : ExportBackupResult()
    data class Error(val message: String, val exception: Throwable? = null) : ExportBackupResult()
}

class ExportBackup @Inject constructor(
    private val backupManager: BackupManager
) {
    suspend operator fun invoke(command: ExportBackupCommand): ExportBackupResult {
        if (command.uriString.isBlank()) {
            return ExportBackupResult.Error("Backup destination is unavailable.")
        }

        return when (val result = backupManager.exportConfig(command.uriString, command.plan)) {
            is Result.Success -> ExportBackupResult.Success
            is Result.Error -> ExportBackupResult.Error(result.message, result.exception)
            is Result.Loading -> ExportBackupResult.Error("Unexpected loading state")
        }
    }
}
