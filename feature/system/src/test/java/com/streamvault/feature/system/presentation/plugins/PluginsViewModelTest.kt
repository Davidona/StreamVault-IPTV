package com.streamvault.feature.system.presentation.plugins

import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.system.api.InstalledStreamVaultPlugin
import com.streamvault.feature.system.api.StreamVaultPluginContract
import com.streamvault.feature.system.api.StreamVaultPluginManifest
import com.streamvault.feature.system.api.SystemPluginManagementPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.wheneverBlocking

@OptIn(ExperimentalCoroutinesApi::class)
class PluginsViewModelTest {
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
    fun refreshPublishesDiscoveredPluginsAndProviderSources() = runTest {
        val plugin = pluginFixture()
        val port = mock<SystemPluginManagementPort>()
        wheneverBlocking { port.discoverPlugins() }.thenReturn(listOf(plugin))
        wheneverBlocking { port.providerSources() }.thenReturn(emptyList())

        val viewModel = PluginsViewModel(port)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.plugins).containsExactly(plugin)
        assertThat(viewModel.uiState.value.providerSources).isEmpty()
    }

    @Test
    fun blankInstallUrlPublishesExistingValidationMessage() = runTest {
        val port = mock<SystemPluginManagementPort>()
        wheneverBlocking { port.discoverPlugins() }.thenReturn(emptyList())
        wheneverBlocking { port.providerSources() }.thenReturn(emptyList())

        val viewModel = PluginsViewModel(port)
        advanceUntilIdle()

        viewModel.updateInstallUrl("  ")
        viewModel.installFromUrl()

        assertThat(viewModel.uiState.value.userMessage)
            .isEqualTo("Enter a plugin APK URL first")
    }

    @Test
    fun localInstallDelegatesUriAndRefreshesState() = runTest {
        val uri = mock<android.net.Uri>()
        val port = mock<SystemPluginManagementPort>()
        wheneverBlocking { port.discoverPlugins() }.thenReturn(emptyList())
        wheneverBlocking { port.providerSources() }.thenReturn(emptyList())
        wheneverBlocking { port.installApkFromUri(uri) }
            .thenReturn(com.streamvault.domain.model.Result.Success(Unit))

        val viewModel = PluginsViewModel(port)
        advanceUntilIdle()
        viewModel.installFromLocalUri(uri)
        advanceUntilIdle()

        verifyBlocking(port) { installApkFromUri(uri) }
        assertThat(viewModel.uiState.value.isLoading).isFalse()
    }

    @Test
    fun enablePassesProgressAndPublishesUnchangedResultMessage() = runTest {
        val plugin = pluginFixture()
        val enabledPlugin = plugin.copy(enabled = true)
        val port = mock<SystemPluginManagementPort>()
        wheneverBlocking { port.discoverPlugins() }.thenReturn(listOf(plugin), listOf(enabledPlugin))
        wheneverBlocking { port.providerSources() }.thenReturn(emptyList())
        wheneverBlocking { port.setPluginEnabled(eq(plugin), eq(true), any()) }
            .thenAnswer { invocation ->
                invocation.getArgument<(String) -> Unit>(2)("Syncing plugin provider...")
                com.streamvault.feature.system.api.PluginActionResult(true, "Plugin enabled")
            }
        val viewModel = PluginsViewModel(port)
        advanceUntilIdle()

        viewModel.setPluginEnabled(plugin, true)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.plugins).containsExactly(enabledPlugin)
        assertThat(viewModel.uiState.value.userMessage).isEqualTo("Plugin enabled")
        assertThat(viewModel.uiState.value.syncProgress).isNull()
    }

    private fun pluginFixture() = InstalledStreamVaultPlugin(
        packageName = "com.example.plugin",
        serviceClassName = "com.example.PluginService",
        appLabel = "Example plugin",
        manifest = StreamVaultPluginManifest(
            id = "example",
            name = "Example plugin",
            capabilities = listOf(StreamVaultPluginContract.CAPABILITY_CONFIGURATION_SCHEMA)
        ),
        enabled = false
    )
}
