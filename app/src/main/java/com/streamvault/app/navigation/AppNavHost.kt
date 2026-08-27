package com.streamvault.app.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.streamvault.app.navigation.graph.registerCatalogGraph
import com.streamvault.app.navigation.graph.registerHomeGraph
import com.streamvault.app.navigation.graph.registerLiveGraph
import com.streamvault.app.navigation.graph.registerSystemGraph
import com.streamvault.app.navigation.graph.registerWelcomeGraph
import com.streamvault.app.ui.screens.settings.BackupImportPreviewDialog
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.NavigationActions
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
    startupReady: Boolean,
    onStartupNavigationRequested: (popUpTo: AppDestination) -> Unit,
    onTopLevelDestinationRequested: (AppDestination) -> Unit
) {
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
        registerSystemGraph(actions, onTopLevelDestinationRequested)
    }
}
