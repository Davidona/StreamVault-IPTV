package com.streamvault.app.system

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.provider.NativeProviderSource
import com.streamvault.domain.provider.ProviderSource
import com.streamvault.feature.system.api.InstalledStreamVaultPlugin
import com.streamvault.feature.system.api.PluginActionResult
import com.streamvault.feature.system.api.PluginConfigurationSchema
import com.streamvault.feature.system.api.PluginConfigurationSnapshot
import com.streamvault.feature.system.api.StreamVaultPluginContract
import com.streamvault.feature.system.api.StreamVaultPluginManifest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.mockito.kotlin.wheneverBlocking

class AppSystemPluginManagementAdapterTest {
    @Test
    fun delegatesDiscoveryAndProviderSourcesUnchanged() = runTest {
        val plugin = pluginFixture()
        val plugins = listOf(plugin)
        val sources: List<ProviderSource> = listOf(
            NativeProviderSource(
                providerId = 7L,
                enabled = true,
                capabilities = emptySet(),
                providerType = ProviderType.M3U,
            ),
        )
        val manager = mock<StreamVaultPluginManager>()
        val registry = mock<com.streamvault.domain.provider.ProviderSourceRegistry>()
        wheneverBlocking { manager.discoverPlugins() }.thenReturn(plugins)
        wheneverBlocking { registry.sources() }.thenReturn(sources)
        val adapter = AppSystemPluginManagementAdapter(manager, registry)

        assertThat(adapter.discoverPlugins()).isSameInstanceAs(plugins)
        assertThat(adapter.providerSources()).isSameInstanceAs(sources)
        verifyBlocking(manager) { discoverPlugins() }
        verifyBlocking(registry) { sources() }
    }

    @Test
    fun delegatesAllPluginOperationsWithOriginalArgumentsAndResults() = runTest {
        val plugin = pluginFixture()
        val uri = mock<Uri>()
        val callback: (String) -> Unit = mock()
        val manager = mock<StreamVaultPluginManager>()
        val registry = mock<com.streamvault.domain.provider.ProviderSourceRegistry>()
        val installUriResult = Result.success(Unit)
        val installUrlResult = Result.success(Unit)
        val enableResult = PluginActionResult(true, "Enabled")
        val configurationResult = PluginActionResult(true, "Opened")
        val snapshot = PluginConfigurationSnapshot(
            plugin = plugin,
            schema = PluginConfigurationSchema(),
            values = JsonObject(emptyMap()),
        )
        val configurationResultData = Result.success(snapshot)
        val values = JsonObject(emptyMap())
        val valuesResult = Result.success(values)
        val saveResult = PluginActionResult(true, "Saved")
        val actionResult = PluginActionResult(true, "Ran")
        wheneverBlocking { manager.installApkFromUri(uri) }.thenReturn(installUriResult)
        wheneverBlocking { manager.installApkFromUrl("https://example.test/plugin.apk") }
            .thenReturn(installUrlResult)
        wheneverBlocking { manager.setPluginEnabled(plugin, true, callback) }.thenReturn(enableResult)
        whenever(manager.openPluginConfiguration(plugin)).thenReturn(configurationResult)
        wheneverBlocking { manager.loadPluginConfiguration(plugin) }.thenReturn(configurationResultData)
        wheneverBlocking { manager.loadPluginConfigurationValues(plugin) }.thenReturn(valuesResult)
        wheneverBlocking { manager.savePluginConfiguration(plugin, "{\"enabled\":true}") }
            .thenReturn(saveResult)
        wheneverBlocking { manager.runPluginConfigurationAction(plugin, "refresh") }
            .thenReturn(actionResult)
        val adapter = AppSystemPluginManagementAdapter(manager, registry)

        assertThat(adapter.installApkFromUri(uri)).isSameInstanceAs(installUriResult)
        assertThat(adapter.installApkFromUrl("https://example.test/plugin.apk"))
            .isSameInstanceAs(installUrlResult)
        assertThat(adapter.setPluginEnabled(plugin, true, callback)).isSameInstanceAs(enableResult)
        assertThat(adapter.openPluginConfiguration(plugin)).isSameInstanceAs(configurationResult)
        assertThat(adapter.loadPluginConfiguration(plugin)).isSameInstanceAs(configurationResultData)
        assertThat(adapter.loadPluginConfigurationValues(plugin)).isSameInstanceAs(valuesResult)
        assertThat(adapter.savePluginConfiguration(plugin, "{\"enabled\":true}"))
            .isSameInstanceAs(saveResult)
        assertThat(adapter.runPluginConfigurationAction(plugin, "refresh")).isSameInstanceAs(actionResult)

        verifyBlocking(manager) { installApkFromUri(uri) }
        verifyBlocking(manager) { installApkFromUrl("https://example.test/plugin.apk") }
        verifyBlocking(manager) { setPluginEnabled(plugin, true, callback) }
        verify(manager).openPluginConfiguration(plugin)
        verifyBlocking(manager) { loadPluginConfiguration(plugin) }
        verifyBlocking(manager) { loadPluginConfigurationValues(plugin) }
        verifyBlocking(manager) { savePluginConfiguration(plugin, "{\"enabled\":true}") }
        verifyBlocking(manager) { runPluginConfigurationAction(plugin, "refresh") }
    }

    private fun pluginFixture() = InstalledStreamVaultPlugin(
        packageName = "com.example.plugin",
        serviceClassName = "com.example.PluginService",
        appLabel = "Example plugin",
        manifest = StreamVaultPluginManifest(
            id = "example",
            name = "Example plugin",
            capabilities = listOf(StreamVaultPluginContract.CAPABILITY_CONFIGURATION_SCHEMA),
        ),
        enabled = false,
    )
}
