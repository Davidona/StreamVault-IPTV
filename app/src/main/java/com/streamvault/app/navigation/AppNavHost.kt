package com.streamvault.app.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.streamvault.app.navigation.graph.registerCatalogGraph
import com.streamvault.app.navigation.graph.registerHomeGraph
import com.streamvault.app.navigation.graph.registerLiveGraph
import com.streamvault.app.navigation.graph.registerSystemGraph
import com.streamvault.app.navigation.graph.registerWelcomeGraph
import com.streamvault.feature.settings.presentation.BackupImportPreviewDialog
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.app.ui.components.shell.rememberAppDestinationItems
import com.streamvault.feature.settings.navigation.registerSettingsGraph
import com.streamvault.feature.settings.parental.ParentalControlGroupScreen
import com.streamvault.feature.settings.api.SettingsPlatformHost
import com.streamvault.feature.settings.presentation.SettingsScreen
import com.streamvault.feature.playback.api.PlaybackPlatformHost
import com.streamvault.feature.playback.navigation.registerPlaybackGraph
import com.streamvault.feature.provider.api.ProviderBackupPreviewRequest
import com.streamvault.feature.provider.navigation.registerProviderGraph

@Composable
internal fun AppNavHost(
    navController: NavHostController,
    actions: NavigationActions,
    catalogDetailActions: CatalogDetailNavigationActions,
    payloads: AppNavigationPayloads,
    playbackPlatformHost: PlaybackPlatformHost?,
    settingsPlatformHost: SettingsPlatformHost,
    startupReady: Boolean,
    onStartupNavigationRequested: (popUpTo: AppDestination) -> Unit,
    onTopLevelDestinationRequested: (AppDestination) -> Unit,
    onCloseApp: () -> Unit
) {
    val navigationDestinations = rememberAppDestinationItems()

    NavHost(
        navController = navController,
        startDestination = AppRoutePatterns.WELCOME
    ) {
        registerWelcomeGraph(actions, startupReady, onStartupNavigationRequested)
        registerProviderGraph(
            actions = actions,
            startupReady = startupReady,
            onStartupNavigationRequested = onStartupNavigationRequested,
            backupPreviewContent = { request: ProviderBackupPreviewRequest ->
                BackupImportPreviewDialog(
                    preview = request.preview,
                    plan = request.plan,
                    onDismiss = request.onDismiss,
                    onStrategySelected = request.onStrategySelected,
                    onImportPreferencesChanged = request.onImportPreferencesChanged,
                    onImportProvidersChanged = request.onImportProvidersChanged,
                    onImportSavedLibraryChanged = request.onImportSavedLibraryChanged,
                    onImportPlaybackHistoryChanged = request.onImportPlaybackHistoryChanged,
                    onImportMultiViewChanged = request.onImportMultiViewChanged,
                    onImportRecordingSchedulesChanged = request.onImportRecordingSchedulesChanged,
                    isImporting = request.isImporting,
                    onConfirm = request.onConfirm
                )
            }
        )
        registerHomeGraph(actions, catalogDetailActions, onTopLevelDestinationRequested)
        registerLiveGraph(actions, onTopLevelDestinationRequested)
        registerCatalogGraph(
            actions,
            catalogDetailActions,
            payloads,
            onTopLevelDestinationRequested
        )
        registerPlaybackGraph(actions, playbackPlatformHost, payloads::consumePlayerRequest)
        registerSettingsGraph(
            actions = actions,
            platformHost = settingsPlatformHost,
            navigationDestinations = navigationDestinations,
            settingsContent = { backupUri, platformHost, destinations ->
                SettingsScreen(
                    onNavigate = { route ->
                        AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
                    },
                    currentRoute = AppRoutePatterns.SETTINGS,
                    platformHost = platformHost,
                    navigationDestinations = destinations,
                    onAddProvider = dropUnlessResumed {
                        actions.navigate(AppDestination.ProviderSetup())
                    },
                    onEditProvider = { provider ->
                        actions.navigate(
                            AppDestination.ProviderSetup(providerId = provider.id),
                            NavigationOptions(launchSingleTop = true)
                        )
                    },
                    onNavigateToParentalControl = { providerId ->
                        actions.navigate(
                            AppDestination.ParentalControlGroups(providerId),
                            NavigationOptions(launchSingleTop = true)
                        )
                    },
                    initialBackupImportUri = backupUri,
                    onCloseApp = onCloseApp
                )
            },
            parentalControlContent = { onBack ->
                ParentalControlGroupScreen(
                    currentRoute = AppRoutePatterns.SETTINGS,
                    onNavigate = { route ->
                        AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
                    },
                    onBack = onBack
                )
            }
        )
        registerSystemGraph(actions, onTopLevelDestinationRequested)
    }
}
