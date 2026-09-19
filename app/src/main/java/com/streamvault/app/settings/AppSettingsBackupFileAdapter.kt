package com.streamvault.app.settings

import android.content.Context
import android.net.Uri
import com.streamvault.app.backup.BackupFileBridge
import com.streamvault.feature.settings.api.SettingsBackupFileCandidate
import com.streamvault.feature.settings.api.SettingsBackupFileHost
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettingsBackupFileAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsBackupFileHost {
    override val jsonMimeType: String = BackupFileBridge.MIME_TYPE_JSON

    override fun rememberManagedExport(uri: Uri) {
        BackupFileBridge.rememberManagedExport(context, uri)
    }

    override fun listManagedBackups(): List<SettingsBackupFileCandidate> =
        BackupFileBridge.listManagedBackups(context).map(::mapCandidate)

    override fun listPickerFreeBackups(): List<SettingsBackupFileCandidate> =
        BackupFileBridge.listPickerFreeBackups(context).map(::mapCandidate)

    override fun listBackups(directory: File): List<SettingsBackupFileCandidate> =
        BackupFileBridge.listBackupFiles(directory).map { file ->
            mapCandidate(BackupFileBridge.candidateForFile(file))
        }

    override fun createShareExportFile(): File =
        BackupFileBridge.createExportFile(context)

    override fun createPickerFreeExportUri(): Uri? =
        BackupFileBridge.createPickerFreeExportUri(context)

    override fun finishPickerFreeExport(uri: Uri, success: Boolean): Boolean =
        BackupFileBridge.finishPickerFreeExport(context, uri, success)

    override fun createExportFile(directory: File): File =
        BackupFileBridge.createExportFile(directory)

    override fun delete(candidate: SettingsBackupFileCandidate): Boolean =
        BackupFileBridge.deleteManagedBackup(
            context,
            BackupFileBridge.BackupFileCandidate(
                displayName = candidate.displayName,
                uri = candidate.uri,
                lastModifiedMs = candidate.lastModifiedMs,
            )
        )

    override fun providerUri(file: File): Uri =
        BackupFileBridge.providerUriForFile(context, file)

    private fun mapCandidate(candidate: BackupFileBridge.BackupFileCandidate): SettingsBackupFileCandidate =
        SettingsBackupFileCandidate(
            displayName = candidate.displayName,
            uri = candidate.uri,
            lastModifiedMs = candidate.lastModifiedMs,
            sizeBytes = candidateSizeBytes(candidate.uri),
        )

    private fun candidateSizeBytes(uri: Uri): Long =
        when (uri.scheme) {
            "file" -> uri.path?.let(::File)?.length() ?: 0L
            "content" -> runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.length.takeIf { it >= 0L } ?: 0L
                } ?: 0L
            }.getOrDefault(0L)
            else -> 0L
        }
}
