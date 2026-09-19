package com.streamvault.feature.system.presentation.plugins

import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.system.api.InstalledStreamVaultPlugin
import com.streamvault.feature.system.api.PluginActionResult
import com.streamvault.feature.system.api.PluginConfigurationField
import com.streamvault.feature.system.api.PluginConfigurationSchema
import com.streamvault.feature.system.api.PluginConfigurationSection
import com.streamvault.feature.system.api.PluginConfigurationSnapshot
import com.streamvault.feature.system.api.StreamVaultPluginContract
import com.streamvault.feature.system.api.StreamVaultPluginManifest
import com.streamvault.feature.system.api.SystemPluginManagementPort
import com.streamvault.domain.model.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.wheneverBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class PluginConfigurationDraftTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun requiredBlankValueIsRejectedBeforePluginSave() = runTest {
        val plugin = pluginFixture()
        val port = mock<SystemPluginManagementPort>()
        val snapshot = configurationSnapshot(plugin)
        wheneverBlocking { port.discoverPlugins() }.thenReturn(listOf(plugin))
        wheneverBlocking { port.providerSources() }.thenReturn(emptyList())
        wheneverBlocking { port.loadPluginConfiguration(plugin) }.thenReturn(Result.Success(snapshot))

        val viewModel = PluginsViewModel(port)
        advanceUntilIdle()
        viewModel.openPluginConfiguration(plugin)
        advanceUntilIdle()
        viewModel.updateConfigurationValue("server", "")
        viewModel.savePluginConfiguration()

        assertThat(viewModel.uiState.value.configuration?.validationErrors)
            .containsEntry("server", "Server is required")
        assertThat(viewModel.uiState.value.userMessage)
            .isEqualTo("Review the highlighted plugin settings")
    }

    @Test
    fun validDraftSerializesTypedFieldsAndRefreshesSavedValues() = runTest {
        val plugin = pluginFixture()
        val port = mock<SystemPluginManagementPort>()
        val snapshot = configurationSnapshot(plugin)
        val updatedValues = buildJsonObject {
            put("server", "https://updated.example")
            put("port", 9090)
            put("enabled", true)
        }
        wheneverBlocking { port.discoverPlugins() }.thenReturn(listOf(plugin))
        wheneverBlocking { port.providerSources() }.thenReturn(emptyList())
        wheneverBlocking { port.loadPluginConfiguration(plugin) }.thenReturn(Result.Success(snapshot))
        wheneverBlocking { port.savePluginConfiguration(eq(plugin), any()) }
            .thenReturn(PluginActionResult(true, "Plugin settings saved"))
        wheneverBlocking { port.loadPluginConfigurationValues(plugin) }
            .thenReturn(Result.Success(updatedValues))

        val viewModel = PluginsViewModel(port)
        advanceUntilIdle()
        viewModel.openPluginConfiguration(plugin)
        advanceUntilIdle()
        viewModel.updateConfigurationValue("server", "https://updated.example")
        viewModel.updateConfigurationValue("port", "9090")
        viewModel.updateConfigurationValue("enabled", "true")
        viewModel.savePluginConfiguration()
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.configuration?.draftValues)
            .containsAtLeast(
                "server", "https://updated.example",
                "port", "9090",
                "enabled", "true"
            )
        assertThat(viewModel.uiState.value.userMessage).isEqualTo("Plugin settings saved")
    }

    private fun configurationSnapshot(plugin: InstalledStreamVaultPlugin) =
        PluginConfigurationSnapshot(
            plugin = plugin,
            schema = PluginConfigurationSchema(
                sections = listOf(
                    PluginConfigurationSection(
                        id = "connection",
                        title = "Connection",
                        fields = listOf(
                            PluginConfigurationField(
                                key = "server",
                                label = "Server",
                                type = PluginConfigurationField.TYPE_URL,
                                required = true
                            ),
                            PluginConfigurationField(
                                key = "port",
                                label = "Port",
                                type = PluginConfigurationField.TYPE_NUMBER,
                                required = true
                            ),
                            PluginConfigurationField(
                                key = "enabled",
                                label = "Enabled",
                                type = PluginConfigurationField.TYPE_BOOLEAN
                            )
                        )
                    )
                )
            ),
            values = buildJsonObject {
                put("server", "https://initial.example")
                put("port", 8080)
                put("enabled", false)
            }
        )

    private fun pluginFixture() = InstalledStreamVaultPlugin(
        packageName = "com.example.plugin",
        serviceClassName = "com.example.PluginService",
        appLabel = "Example plugin",
        manifest = StreamVaultPluginManifest(
            id = "example",
            name = "Example plugin",
            capabilities = listOf(StreamVaultPluginContract.CAPABILITY_CONFIGURATION_SCHEMA),
            configurationMode = StreamVaultPluginContract.CONFIGURATION_MODE_HOST_SCHEMA
        ),
        enabled = false
    )
}
