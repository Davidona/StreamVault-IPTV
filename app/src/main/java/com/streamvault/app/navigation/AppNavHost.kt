package com.streamvault.app.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import com.streamvault.app.navigation.graph.registerLiveGraph
import com.streamvault.app.navigation.graph.registerSystemGraph
import com.streamvault.app.navigation.graph.registerWelcomeGraph
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
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
import com.streamvault.app.navigation.toLivePlayerRequest
import com.streamvault.app.navigation.toPlayerNavigationRequest
import com.streamvault.feature.catalog.api.CatalogChannelPlaybackContext
import com.streamvault.feature.catalog.api.CatalogDashboardShelfCustomizationContent
import com.streamvault.feature.catalog.api.CatalogNavigationChrome
import com.streamvault.feature.catalog.api.CatalogPlatformHost
import com.streamvault.feature.catalog.api.CatalogScaffoldContent
import com.streamvault.feature.catalog.navigation.registerCatalogGraph
import com.streamvault.feature.settings.presentation.DashboardShelfCustomizationDialog
import com.streamvault.domain.model.ContentType

@Composable
internal fun AppNavHost(
    navController: NavHostController,
    actions: NavigationActions,
    catalogDetailActions: CatalogDetailNavigationActions,
    payloads: AppNavigationPayloads,
    catalogPlatformHost: CatalogPlatformHost?,
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
        registerLiveGraph(actions, onTopLevelDestinationRequested)
        registerCatalogGraph(
            actions = actions,
            platformHost = catalogPlatformHost,
            scaffold = appCatalogScaffold(onTopLevelDestinationRequested),
            dashboardShelfCustomizationContent = appDashboardShelfCustomizationContent(),
            onTopLevelDestinationRequested = onTopLevelDestinationRequested,
            onOpenMovieDetail = catalogDetailActions::openMovieDetail,
            onOpenSeriesDetail = catalogDetailActions::openSeriesDetail,
            onPlayChannel = { channel, playbackContext ->
                actions.openPlayer(
                    channel.toLivePlayerRequest(
                        categoryId = playbackContext.categoryId,
                        providerId = playbackContext.providerId,
                        isVirtual = playbackContext.isVirtual,
                        combinedProfileId = playbackContext.combinedProfileId,
                        returnDestination = playbackContext.returnDestination
                    )
                )
            },
            onPlayMovie = { movie, returnDestination ->
                actions.openPlayer(movie.toPlayerNavigationRequest(returnDestination))
            },
            onPlayEpisode = { episode, returnDestination ->
                actions.openPlayer(episode.toPlayerNavigationRequest(returnDestination))
            },
            onPlayHistory = { history, returnDestination ->
                when (history.contentType) {
                    ContentType.SERIES -> actions.navigate(
                        AppDestination.SeriesDetail(history.contentId, returnDestination),
                        NavigationOptions(launchSingleTop = true)
                    )
                    ContentType.LIVE,
                    ContentType.MOVIE,
                    ContentType.VOD,
                    ContentType.SERIES_EPISODE -> actions.openPlayer(
                        history.toPlayerNavigationRequest(returnDestination)
                    )
                }
            },
            consumeMoviePresentationHint = payloads::consumeMoviePresentationHint,
            consumeSeriesPresentationHint = payloads::consumeSeriesPresentationHint,
            decodeDestination = AppRouteCodec::decode,
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
            parentalControlContent = { onBack, destinations ->
                ParentalControlGroupScreen(
                    currentRoute = AppRoutePatterns.SETTINGS,
                    onNavigate = { route ->
                        AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested)
                    },
                    onBack = onBack,
                    navigationDestinations = destinations,
                )
            }
        )
        registerSystemGraph(actions, onTopLevelDestinationRequested)
    }
}

private fun appCatalogScaffold(
    onTopLevelDestinationRequested: (AppDestination) -> Unit
): CatalogScaffoldContent = { currentDestination, title, subtitle, chrome, topBarVisible, compactHeader, showScreenHeader, content ->
    AppScreenScaffold(
        currentRoute = AppRouteCodec.encode(currentDestination),
        onNavigate = { route -> AppRouteCodec.decode(route)?.let(onTopLevelDestinationRequested) },
        title = title,
        subtitle = subtitle,
        navigationChrome = when (chrome) {
            CatalogNavigationChrome.Rail -> AppNavigationChrome.Rail
            CatalogNavigationChrome.TopBar -> AppNavigationChrome.TopBar
        },
        topBarVisible = topBarVisible,
        compactHeader = compactHeader,
        showScreenHeader = showScreenHeader,
        content = content
    )
}

private fun appDashboardShelfCustomizationContent(): CatalogDashboardShelfCustomizationContent =
    { currentShelves, onDismiss, onSave ->
        DashboardShelfCustomizationDialog(
            currentShelves = currentShelves,
            onDismiss = onDismiss,
            onSave = onSave
        )
    }
