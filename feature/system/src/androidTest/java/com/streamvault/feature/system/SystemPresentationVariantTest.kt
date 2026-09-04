package com.streamvault.feature.system

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.tv.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.feature.system.api.InstalledStreamVaultPlugin
import com.streamvault.feature.system.api.SystemScaffoldContent
import com.streamvault.feature.system.presentation.downloads.DownloadsContent
import com.streamvault.feature.system.presentation.downloads.DownloadsUiState
import com.streamvault.feature.system.presentation.plugins.PluginsActions
import com.streamvault.feature.system.presentation.plugins.PluginsContent
import com.streamvault.feature.system.presentation.welcome.WelcomeContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemPresentationVariantTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun welcomeRetainsAccessibleActionsInRtlAndLargeFontWithAnimationsPaused() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density = 1f, fontScale = 1.3f),
            ) {
                StreamVaultTheme {
                    MaterialTheme {
                        WelcomeContent(
                            hasProviders = false,
                            syncProgress = null,
                            onNavigateToHome = {},
                            onNavigateToSetup = {},
                        )
                    }
                }
            }
        }

        composeRule.onNode(hasText("Setup Provider") and hasClickAction())
            .assertIsDisplayed()
            .assertHasClickAction()
        composeRule.onNode(hasText("Set up later") and hasClickAction())
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.OnClick)
    }

    @Test
    fun downloadsRetainsAccessibleActionsInRtlAndLargeFontWithAnimationsPaused() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density = 1f, fontScale = 1.3f),
            ) {
                StreamVaultTheme {
                    MaterialTheme {
                        Column(modifier = Modifier.fillMaxSize()) {
                            DownloadsContent(
                                uiState = DownloadsUiState(isLoading = false),
                                scaffold = testScaffold,
                                onChangeFolder = {},
                                onOpen = {},
                                onResume = {},
                                onDelete = {},
                                onConfirmDelete = {},
                                onDismissDelete = {},
                            )
                        }
                    }
                }
            }
        }

        composeRule.onNodeWithText("No downloads yet").assertIsDisplayed()
        composeRule.onNode(hasText("Change download folder") and hasClickAction())
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    @Test
    fun pluginsRetainAccessibleActionsInRtlAndLargeFontWithAnimationsPaused() {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density = 1f, fontScale = 1.3f),
            ) {
                StreamVaultTheme {
                    MaterialTheme {
                        PluginsContent(
                            uiState = com.streamvault.feature.system.presentation.plugins.PluginsUiState(
                                isLoading = false,
                            ),
                            scaffold = testScaffold,
                            showInstallUrlDialog = false,
                            onShowInstallUrlDialog = {},
                            onDismissInstallUrlDialog = {},
                            onInstallFromFile = {},
                            actions = noOpPluginActions,
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("No compatible StreamVault plugins are installed.")
            .assertIsDisplayed()
        composeRule.onNode(hasText("Refresh") and hasClickAction())
            .assertIsDisplayed()
            .assertHasClickAction()
    }

    private val testScaffold: SystemScaffoldContent = { _, _, _, _, _, content ->
        Column(modifier = Modifier.fillMaxSize()) { content() }
    }

    private val noOpPluginActions = PluginsActions(
        onUpdateInstallUrl = {},
        onInstallFromLocalUri = {},
        onInstallFromUrl = {},
        onRefreshPlugins = {},
        onSetPluginEnabled = { _: InstalledStreamVaultPlugin, _: Boolean -> },
        onOpenPluginConfiguration = {},
        onClosePluginConfiguration = {},
        onRefreshPluginConfiguration = {},
        onSavePluginConfiguration = {},
        onUpdateConfigurationValue = { _, _ -> },
        onRunConfigurationAction = {},
        onClearMessage = {},
    )
}
