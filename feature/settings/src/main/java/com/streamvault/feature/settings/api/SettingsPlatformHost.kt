package com.streamvault.feature.settings.api

import android.net.Uri
import com.streamvault.domain.model.Result
import java.io.File

data class SettingsBuildInfo(
    val versionName: String,
    val versionCode: Int,
    val applicationId: String,
    val buildType: String,
    val buildTimestampUtc: Long = 0L,
    val updateChannel: String = "stable",
)

enum class SettingsOfficialBuildStatus {
    OFFICIAL,
    UNOFFICIAL,
    VERIFICATION_UNAVAILABLE,
}

data class SettingsRecordingPlaybackRequest(
    val streamUrl: String,
    val title: String,
    val internalId: Long? = null,
    val providerId: Long? = null,
    val contentType: String? = null,
)

interface SettingsPlatformHost {
    val buildInfo: SettingsBuildInfo
    val backupFiles: SettingsBackupFileHost

    fun officialBuildStatus(): SettingsOfficialBuildStatus
    fun playRecording(request: SettingsRecordingPlaybackRequest)
    fun shareBackup(uri: Uri): Result<Unit>
    fun shareCrashReport(): Result<Unit>
    fun removableBackupDirectory(): File?
}
