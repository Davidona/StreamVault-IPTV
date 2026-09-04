package com.streamvault.feature.system.api

import android.net.Uri
import com.streamvault.domain.model.Result
import com.streamvault.domain.provider.ProviderSource
import kotlinx.serialization.json.JsonObject

interface SystemPluginManagementPort {
    suspend fun discoverPlugins(): List<InstalledStreamVaultPlugin>

    suspend fun providerSources(): List<ProviderSource>

    suspend fun installApkFromUri(uri: Uri): Result<Unit>

    suspend fun installApkFromUrl(url: String): Result<Unit>

    suspend fun setPluginEnabled(
        plugin: InstalledStreamVaultPlugin,
        enabled: Boolean,
        onProgress: (String) -> Unit,
    ): PluginActionResult

    fun openPluginConfiguration(plugin: InstalledStreamVaultPlugin): PluginActionResult

    suspend fun loadPluginConfiguration(
        plugin: InstalledStreamVaultPlugin,
    ): Result<PluginConfigurationSnapshot>

    suspend fun loadPluginConfigurationValues(plugin: InstalledStreamVaultPlugin): Result<JsonObject>

    suspend fun savePluginConfiguration(
        plugin: InstalledStreamVaultPlugin,
        valuesJson: String,
    ): PluginActionResult

    suspend fun runPluginConfigurationAction(
        plugin: InstalledStreamVaultPlugin,
        actionId: String,
    ): PluginActionResult
}
