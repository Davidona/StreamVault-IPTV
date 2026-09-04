package com.streamvault.feature.system.presentation.plugins

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.feature.system.api.InstalledStreamVaultPlugin
import com.streamvault.feature.system.api.PluginConfigurationAction
import com.streamvault.feature.system.api.PluginConfigurationField
import com.streamvault.feature.system.api.PluginConfigurationSchema
import com.streamvault.feature.system.api.PluginConfigurationSection
import com.streamvault.feature.system.api.StreamVaultPluginContract
import com.streamvault.feature.system.api.StreamVaultPluginManifest
import com.streamvault.feature.system.api.SystemScaffoldContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginsPresentationTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyStateRetainsInstallAndRefreshActions() {
        var installUrlClicks = 0
        var installFileClicks = 0
        var refreshClicks = 0

        composeRule.setContent {
            StreamVaultTheme {
                PluginsContent(
                    uiState = PluginsUiState(isLoading = false),
                    scaffold = testScaffold,
                    showInstallUrlDialog = false,
                    onShowInstallUrlDialog = { installUrlClicks++ },
                    onDismissInstallUrlDialog = {},
                    onInstallFromFile = { installFileClicks++ },
                    actions = noOpActions(onRefreshPlugins = { refreshClicks++ }),
                )
            }
        }

        composeRule.onNodeWithText("No compatible StreamVault plugins are installed.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Install URL").performClick()
        composeRule.onNodeWithText("Install file").performClick()
        composeRule.onNodeWithText("Refresh").performClick()

        assertThat(installUrlClicks).isEqualTo(1)
        assertThat(installFileClicks).isEqualTo(1)
        assertThat(refreshClicks).isEqualTo(1)
    }

    @Test
    fun discoveredPluginRetainsConfigureAndEnableCallbacks() {
        var configured = false
        var enabled: Boolean? = null
        val plugin = pluginFixture()

        composeRule.setContent {
            StreamVaultTheme {
                PluginsContent(
                    uiState = PluginsUiState(isLoading = false, plugins = listOf(plugin)),
                    scaffold = testScaffold,
                    showInstallUrlDialog = false,
                    onShowInstallUrlDialog = {},
                    onDismissInstallUrlDialog = {},
                    onInstallFromFile = {},
                    actions = noOpActions(
                        onSetPluginEnabled = { _, value -> enabled = value },
                        onOpenPluginConfiguration = { configured = true },
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Example plugin").assertIsDisplayed()
        composeRule.onNodeWithText("Disabled").assertIsDisplayed()
        composeRule.onNodeWithText("Configure").performClick()

        assertThat(configured).isTrue()
        assertThat(enabled).isNull()
    }

    @Test
    fun hostConfigurationRetainsRequiredFieldIndicationAndSaveAction() {
        var saves = 0
        val plugin = pluginFixture()
        val configuration = ActivePluginConfiguration(
            plugin = plugin,
            schema = PluginConfigurationSchema(
                title = "Plugin settings",
                sections = listOf(
                    PluginConfigurationSection(
                        id = "connection",
                        title = "Connection",
                        fields = listOf(
                            PluginConfigurationField(
                                key = "server",
                                label = "Server",
                                type = PluginConfigurationField.TYPE_URL,
                                required = true,
                            ),
                            PluginConfigurationField(
                                key = "enabled",
                                label = "Enabled",
                                type = PluginConfigurationField.TYPE_BOOLEAN,
                            ),
                        ),
                    )
                ),
                actions = listOf(PluginConfigurationAction("reload", "Reload")),
            ),
            values = buildJsonObject {
                put("server", "")
                put("enabled", false)
            },
            draftValues = mapOf("server" to "", "enabled" to "false"),
            validationErrors = mapOf("server" to "Server is required"),
        )

        composeRule.setContent {
            StreamVaultTheme {
                PluginsContent(
                    uiState = PluginsUiState(configuration = configuration),
                    scaffold = testScaffold,
                    showInstallUrlDialog = false,
                    onShowInstallUrlDialog = {},
                    onDismissInstallUrlDialog = {},
                    onInstallFromFile = {},
                    actions = noOpActions(onSavePluginConfiguration = { saves++ }),
                )
            }
        }

        composeRule.onNodeWithText("Plugin settings").assertIsDisplayed()
        composeRule.onNodeWithText("Some plugin settings need attention before saving.")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Server is required").assertIsDisplayed()
        composeRule.onNodeWithText("Enabled").assertIsDisplayed()
        composeRule.onNodeWithText("Save").performClick()

        assertThat(saves).isEqualTo(1)
    }

    private val testScaffold: SystemScaffoldContent = { _, _, _, _, _, content ->
        Column(modifier = androidx.compose.ui.Modifier.fillMaxSize()) { content() }
    }

    private fun noOpActions(
        onRefreshPlugins: () -> Unit = {},
        onSetPluginEnabled: (InstalledStreamVaultPlugin, Boolean) -> Unit = { _, _ -> },
        onOpenPluginConfiguration: (InstalledStreamVaultPlugin) -> Unit = {},
        onSavePluginConfiguration: () -> Unit = {},
    ) = PluginsActions(
        onUpdateInstallUrl = {},
        onInstallFromLocalUri = {},
        onInstallFromUrl = {},
        onRefreshPlugins = onRefreshPlugins,
        onSetPluginEnabled = onSetPluginEnabled,
        onOpenPluginConfiguration = onOpenPluginConfiguration,
        onClosePluginConfiguration = {},
        onRefreshPluginConfiguration = {},
        onSavePluginConfiguration = onSavePluginConfiguration,
        onUpdateConfigurationValue = { _, _ -> },
        onRunConfigurationAction = {},
        onClearMessage = {},
    )

    private fun pluginFixture() = InstalledStreamVaultPlugin(
        packageName = "com.example.plugin",
        serviceClassName = "com.example.PluginService",
        appLabel = "Example plugin",
        manifest = StreamVaultPluginManifest(
            id = "example",
            name = "Example plugin",
            capabilities = listOf(StreamVaultPluginContract.CAPABILITY_CONFIGURATION_SCHEMA),
            configurationMode = StreamVaultPluginContract.CONFIGURATION_MODE_HOST_SCHEMA,
        ),
        enabled = false,
    )
}
