package com.streamvault.feature.settings.api

import android.net.Uri
import java.io.File

data class SettingsBackupFileCandidate(
    val displayName: String,
    val uri: Uri,
    val lastModifiedMs: Long,
    val sizeBytes: Long = 0L,
)

interface SettingsBackupFileHost {
    val jsonMimeType: String

    fun rememberManagedExport(uri: Uri)
    fun listManagedBackups(): List<SettingsBackupFileCandidate>
    fun listPickerFreeBackups(): List<SettingsBackupFileCandidate>
    fun listBackups(directory: File): List<SettingsBackupFileCandidate>
    fun createShareExportFile(): File
    fun createPickerFreeExportUri(): Uri?
    fun finishPickerFreeExport(uri: Uri, success: Boolean): Boolean
    fun createExportFile(directory: File): File
    fun delete(candidate: SettingsBackupFileCandidate): Boolean
    fun providerUri(file: File): Uri
}
