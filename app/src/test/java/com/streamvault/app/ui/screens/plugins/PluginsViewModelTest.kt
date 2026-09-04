package com.streamvault.app.ui.screens.plugins

import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.system.api.InstalledStreamVaultPlugin
import com.streamvault.feature.system.api.StreamVaultPluginContract
import com.streamvault.app.plugins.StreamVaultPluginManager
import com.streamvault.feature.system.api.StreamVaultPluginManifest
import com.streamvault.domain.provider.ProviderSourceRegistry
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
import org.mockito.kotlin.mock
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
        val manager = mock<StreamVaultPluginManager>()
        val registry = mock<ProviderSourceRegistry>()
        wheneverBlocking { manager.discoverPlugins() }.thenReturn(listOf(plugin))
        wheneverBlocking { registry.sources() }.thenReturn(emptyList())

        val viewModel = PluginsViewModel(manager, registry)
        advanceUntilIdle()

        assertThat(viewModel.uiState.value.isLoading).isFalse()
        assertThat(viewModel.uiState.value.plugins).containsExactly(plugin)
        assertThat(viewModel.uiState.value.providerSources).isEmpty()
    }

    @Test
    fun blankInstallUrlPublishesExistingValidationMessage() = runTest {
        val manager = mock<StreamVaultPluginManager>()
        val registry = mock<ProviderSourceRegistry>()
        wheneverBlocking { manager.discoverPlugins() }.thenReturn(emptyList())
        wheneverBlocking { registry.sources() }.thenReturn(emptyList())

        val viewModel = PluginsViewModel(manager, registry)
        advanceUntilIdle()

        viewModel.updateInstallUrl("  ")
        viewModel.installFromUrl()

        assertThat(viewModel.uiState.value.userMessage)
            .isEqualTo("Enter a plugin APK URL first")
    }

    @Test
    fun localInstallDelegatesUriAndRefreshesState() = runTest {
        val uri = mock<android.net.Uri>()
        val manager = mock<StreamVaultPluginManager>()
        val registry = mock<ProviderSourceRegistry>()
        wheneverBlocking { manager.discoverPlugins() }.thenReturn(emptyList())
        wheneverBlocking { registry.sources() }.thenReturn(emptyList())
        wheneverBlocking { manager.installApkFromUri(uri) }
            .thenReturn(com.streamvault.domain.model.Result.Success(Unit))

        val viewModel = PluginsViewModel(manager, registry)
        advanceUntilIdle()
        viewModel.installFromLocalUri(uri)
        advanceUntilIdle()

        verifyBlocking(manager) { installApkFromUri(uri) }
        assertThat(viewModel.uiState.value.isLoading).isFalse()
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
