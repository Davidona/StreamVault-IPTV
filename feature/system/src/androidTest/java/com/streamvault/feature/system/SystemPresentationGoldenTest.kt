package com.streamvault.feature.system

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.tv.material3.MaterialTheme
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.ui.components.shell.CoreAppScreenScaffold
import com.streamvault.core.ui.components.shell.NavigationChrome
import com.streamvault.core.ui.components.shell.UiDestination
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.DownloadContentType
import com.streamvault.domain.model.DownloadItem
import com.streamvault.domain.model.DownloadStatus
import com.streamvault.domain.model.DownloadStorageConfig
import com.streamvault.domain.sync.Section
import com.streamvault.feature.system.api.InstalledStreamVaultPlugin
import com.streamvault.feature.system.api.PluginConfigurationAction
import com.streamvault.feature.system.api.PluginConfigurationField
import com.streamvault.feature.system.api.PluginConfigurationOption
import com.streamvault.feature.system.api.PluginConfigurationSchema
import com.streamvault.feature.system.api.PluginConfigurationSection
import com.streamvault.feature.system.api.StreamVaultPluginContract
import com.streamvault.feature.system.api.StreamVaultPluginManifest
import com.streamvault.feature.system.api.SystemScaffoldContent
import com.streamvault.feature.system.api.WelcomeSyncProgress
import com.streamvault.feature.system.navigation.SystemRoutePatterns
import com.streamvault.feature.system.presentation.downloads.DownloadsContent
import com.streamvault.feature.system.presentation.downloads.DownloadsUiState
import com.streamvault.feature.system.presentation.plugins.ActivePluginConfiguration
import com.streamvault.feature.system.presentation.plugins.PluginsActions
import com.streamvault.feature.system.presentation.plugins.PluginsContent
import com.streamvault.feature.system.presentation.plugins.PluginsUiState
import com.streamvault.feature.system.presentation.welcome.WelcomeContent
import com.streamvault.feature.system.test.assertAgainstGolden
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemPresentationGoldenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun welcome_no_provider_matchesGolden() = captureGolden("welcome_no_provider") {
        WelcomeContent(
            hasProviders = false,
            syncProgress = null,
            onNavigateToHome = {},
            onNavigateToSetup = {},
        )
    }

    @Test
    fun welcome_syncing_matchesGolden() = captureGolden("welcome_syncing") {
        WelcomeContent(
            hasProviders = true,
            syncProgress = WelcomeSyncProgress(
                section = Section.LIVE,
                current = 42,
                total = 100,
                currentLabel = "Indexing live channels...",
                itemsIndexed = 420,
            ),
            onNavigateToHome = {},
            onNavigateToSetup = {},
        )
    }

    @Test
    fun downloads_empty_matchesGolden() = captureGolden("downloads_empty") {
        DownloadsContent(
            uiState = DownloadsUiState(
                isLoading = false,
                storageConfig = DownloadStorageConfig(displayName = "StreamVault Downloads"),
            ),
            scaffold = goldenScaffold,
            onChangeFolder = {},
            onOpen = {},
            onResume = {},
            onDelete = {},
            onConfirmDelete = {},
            onDismissDelete = {},
        )
    }

    @Test
    fun downloads_completed_matchesGolden() = captureGolden("downloads_completed") {
        DownloadsContent(
            uiState = DownloadsUiState(
                isLoading = false,
                storageConfig = DownloadStorageConfig(displayName = "StreamVault Downloads"),
                downloads = listOf(completedDownload()),
            ),
            scaffold = goldenScaffold,
            onChangeFolder = {},
            onOpen = {},
            onResume = {},
            onDelete = {},
            onConfirmDelete = {},
            onDismissDelete = {},
        )
    }

    @Test
    fun plugins_empty_matchesGolden() = captureGolden("plugins_empty") {
        PluginsContent(
            uiState = PluginsUiState(isLoading = false),
            scaffold = goldenScaffold,
            showInstallUrlDialog = false,
            onShowInstallUrlDialog = {},
            onDismissInstallUrlDialog = {},
            onInstallFromFile = {},
            actions = noOpPluginActions(),
        )
    }

    @Test
    fun plugins_configuration_matchesGolden() = captureGolden("plugins_configuration") {
        val plugin = pluginFixture()
        PluginsContent(
            uiState = PluginsUiState(
                configuration = ActivePluginConfiguration(
                    plugin = plugin,
                    schema = PluginConfigurationSchema(
                        title = "Example plugin settings",
                        description = "Configure the companion provider connection.",
                        sections = listOf(
                            PluginConfigurationSection(
                                id = "connection",
                                title = "Connection",
                                fields = listOf(
                                    PluginConfigurationField(
                                        key = "server",
                                        type = PluginConfigurationField.TYPE_URL,
                                        label = "Server URL",
                                        placeholder = "https://example.test",
                                        required = true,
                                    ),
                                    PluginConfigurationField(
                                        key = "apiKey",
                                        type = PluginConfigurationField.TYPE_PASSWORD,
                                        label = "API key",
                                        required = true,
                                        secret = true,
                                    ),
                                    PluginConfigurationField(
                                        key = "mode",
                                        type = PluginConfigurationField.TYPE_SELECT,
                                        label = "Sync mode",
                                        options = listOf(
                                            PluginConfigurationOption("balanced", "Balanced"),
                                            PluginConfigurationOption("fast", "Fast"),
                                        ),
                                    ),
                                    PluginConfigurationField(
                                        key = "enabled",
                                        type = PluginConfigurationField.TYPE_BOOLEAN,
                                        label = "Enabled",
                                    ),
                                    PluginConfigurationField(
                                        key = "interval",
                                        type = PluginConfigurationField.TYPE_NUMBER,
                                        label = "Refresh interval",
                                    ),
                                ),
                            ),
                        ),
                        actions = listOf(
                            PluginConfigurationAction(
                                id = "reload",
                                label = "Reload catalog",
                                description = "Ask the plugin to refresh its provider data.",
                            ),
                        ),
                    ),
                    values = buildJsonObject {
                        put("server", "")
                        put("apiKey", "")
                        put("mode", "balanced")
                        put("enabled", false)
                        put("interval", 15)
                    },
                    draftValues = mapOf(
                        "server" to "",
                        "apiKey" to "demo-token",
                        "mode" to "balanced",
                        "enabled" to "true",
                        "interval" to "30",
                    ),
                    validationErrors = mapOf("server" to "Server URL is required"),
                ),
            ),
            scaffold = goldenScaffold,
            showInstallUrlDialog = false,
            onShowInstallUrlDialog = {},
            onDismissInstallUrlDialog = {},
            onInstallFromFile = {},
            actions = noOpPluginActions(),
        )
    }

    private fun captureGolden(
        name: String,
        content: @androidx.compose.runtime.Composable () -> Unit,
    ) {
        composeRule.setContent {
            StreamVaultTheme {
                MaterialTheme {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(AppColors.Canvas)
                            .testTag("golden"),
                    ) {
                        content()
                    }
                }
            }
        }
        composeRule.onNodeWithTag("golden").assertAgainstGolden(name)
    }

    private val goldenScaffold: SystemScaffoldContent = { destination, title, subtitle, compactHeader, showScreenHeader, content ->
        CoreAppScreenScaffold(
            currentDestinationId = when (destination) {
                AppDestination.Downloads -> SystemRoutePatterns.DOWNLOADS
                AppDestination.Plugins -> SystemRoutePatterns.PLUGINS
                else -> "system"
            },
            destinations = listOf(
                UiDestination("home", "Home", Icons.Default.Home),
                UiDestination(SystemRoutePatterns.DOWNLOADS, "Downloads", Icons.Default.Download),
                UiDestination(SystemRoutePatterns.PLUGINS, "Plugins", Icons.Default.Info),
                UiDestination("settings", "Settings", Icons.Default.Settings),
            ),
            onDestinationSelected = {},
            title = title,
            subtitle = subtitle,
            navigationChrome = NavigationChrome.TopBar,
            topBarVisible = true,
            compactHeader = compactHeader,
            showScreenHeader = showScreenHeader,
            content = content,
        )
    }

    private fun completedDownload() = DownloadItem(
        id = "completed",
        providerId = 7L,
        contentType = DownloadContentType.MOVIE,
        contentId = 42L,
        contentName = "The Night Shift",
        streamUrl = "https://example.test/movie.mp4",
        outputDisplayPath = "/storage/emulated/0/Download/The Night Shift.mp4",
        status = DownloadStatus.COMPLETED,
        bytesWritten = 123_456_789L,
        totalBytes = 123_456_789L,
        completedAt = 1L,
    )

    private fun noOpPluginActions() = PluginsActions(
        onUpdateInstallUrl = {},
        onInstallFromLocalUri = { _: Uri -> },
        onInstallFromUrl = {},
        onRefreshPlugins = {},
        onSetPluginEnabled = { _, _ -> },
        onOpenPluginConfiguration = {},
        onClosePluginConfiguration = {},
        onRefreshPluginConfiguration = {},
        onSavePluginConfiguration = {},
        onUpdateConfigurationValue = { _, _ -> },
        onRunConfigurationAction = { _: PluginConfigurationAction -> },
        onClearMessage = {},
    )

    private fun pluginFixture() = InstalledStreamVaultPlugin(
        packageName = "com.example.streamvault.plugin",
        serviceClassName = "com.example.streamvault.PluginService",
        appLabel = "Example plugin",
        manifest = StreamVaultPluginManifest(
            id = "example",
            name = "Example plugin",
            versionName = "2.4.1",
            description = "Companion provider synchronization",
            capabilities = listOf(StreamVaultPluginContract.CAPABILITY_CONFIGURATION_SCHEMA),
            configurationMode = StreamVaultPluginContract.CONFIGURATION_MODE_HOST_SCHEMA,
        ),
        enabled = true,
    )
}
