package com.streamvault.app.settings

import android.content.Context
import android.net.Uri
import com.streamvault.app.BuildConfig
import com.streamvault.app.device.removableAppStorageDirs
import com.streamvault.app.util.OfficialBuildStatus
import com.streamvault.app.util.OfficialBuildVerifier
import com.streamvault.domain.model.Result
import com.streamvault.feature.settings.api.SettingsBackupFileHost
import com.streamvault.feature.settings.api.SettingsBuildInfo
import com.streamvault.feature.settings.api.SettingsOfficialBuildStatus
import com.streamvault.feature.settings.api.SettingsPlatformHost
import com.streamvault.feature.settings.api.SettingsRecordingPlaybackRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File

/**
 * App-owned bridge for Activity and Android framework operations required by
 * the settings feature. The root navigation layer supplies the callbacks for
 * operations that need an Activity or an Activity Result launcher.
 */
class AppSettingsPlatformHost(
    @ApplicationContext private val context: Context,
    override val backupFiles: SettingsBackupFileHost,
    private val playRecordingOperation: (SettingsRecordingPlaybackRequest) -> Unit = {},
    private val shareBackupOperation: (Uri) -> Result<Unit> = {
        Result.error("Backup sharing is unavailable")
    },
    private val shareCrashReportOperation: () -> Result<Unit> = {
        Result.error("Crash-report sharing is unavailable")
    },
) : SettingsPlatformHost {
    override val buildInfo: SettingsBuildInfo = SettingsBuildInfo(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE,
        applicationId = BuildConfig.APPLICATION_ID,
        buildType = BuildConfig.BUILD_TYPE,
        buildTimestampUtc = BuildConfig.BUILD_TIMESTAMP_UTC,
        updateChannel = BuildConfig.APP_UPDATE_CHANNEL,
    )

    override fun officialBuildStatus(): SettingsOfficialBuildStatus =
        when (OfficialBuildVerifier.verify(context).status) {
            OfficialBuildStatus.OFFICIAL -> SettingsOfficialBuildStatus.OFFICIAL
            OfficialBuildStatus.UNOFFICIAL -> SettingsOfficialBuildStatus.UNOFFICIAL
            OfficialBuildStatus.VERIFICATION_UNAVAILABLE ->
                SettingsOfficialBuildStatus.VERIFICATION_UNAVAILABLE
        }

    override fun playRecording(request: SettingsRecordingPlaybackRequest) {
        playRecordingOperation(request)
    }

    override fun shareBackup(uri: Uri): Result<Unit> =
        shareBackupOperation(uri)

    override fun shareCrashReport(): Result<Unit> =
        shareCrashReportOperation()

    override fun removableBackupDirectory(): File? =
        context.removableAppStorageDirs().firstOrNull()
}
