package com.streamvault.app.settings

import android.content.Context
import com.streamvault.app.diagnostics.CrashReportStore
import com.streamvault.feature.settings.api.SettingsCrashReport
import com.streamvault.feature.settings.api.SettingsDiagnosticsPort
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppSettingsDiagnosticsAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsDiagnosticsPort {
    override fun latestReport(): SettingsCrashReport? =
        CrashReportStore.latestReport(context)?.let { report ->
            SettingsCrashReport(
                timestamp = report.timestamp,
                exception = report.exception,
                fileName = report.fileName,
                content = report.content,
            )
        }

    override fun deleteLatestReport(): Boolean =
        CrashReportStore.deleteLatestReport(context)
}
