package com.streamvault.feature.settings.api

data class SettingsCrashReport(
    val timestamp: String,
    val exception: String,
    val fileName: String,
    val content: String,
)

interface SettingsDiagnosticsPort {
    fun latestReport(): SettingsCrashReport?
    fun deleteLatestReport(): Boolean
}
