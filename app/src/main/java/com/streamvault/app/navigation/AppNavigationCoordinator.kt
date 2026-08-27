package com.streamvault.app.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.ExternalNavigationRequest
import com.streamvault.core.navigation.NavigationCommand
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.repository.ProviderRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PendingNavigationCommand(
    val id: Long,
    val command: NavigationCommand
)

@HiltViewModel
class AppNavigationCoordinator @Inject constructor(
    private val commandIds: NavigationCommandIdSource,
    private val startupResolver: StartupNavigationResolver,
    private val preferencesRepository: PreferencesRepository,
    private val providerRepository: ProviderRepository
) : ViewModel() {
    private val commandQueue = ArrayDeque<PendingNavigationCommand>()
    private val _pendingCommand = MutableStateFlow<PendingNavigationCommand?>(null)
    val pendingCommand: StateFlow<PendingNavigationCommand?> = _pendingCommand.asStateFlow()
    private val _state = MutableStateFlow(AppNavigationState())
    val state: StateFlow<AppNavigationState> = _state.asStateFlow()

    private var activeProviderId: Long? = null
    private var startupNavigationRequested = false
    private var startupNavigationAcknowledged = false
    private var startupNavigationCommandId: Long? = null
    private var startupPlayerRequest: PlayerNavigationRequest? = null
    private var startupPlayerEnqueued = false
    private var startupLiveDestinationResumed = false
    private var resolvedStartupKey: StartupResolutionKey? = null
    private var startupResolutionKey: StartupResolutionKey? = null

    init {
        observeStartupState()
        observeCatalogState()
    }

    private fun observeStartupState() {
        viewModelScope.launch {
            combine(
                preferencesRepository.appLandingDestination,
                preferencesRepository.appTopLevelDestinations,
                providerRepository.getActiveProvider()
            ) { preferredLanding, topLevelDestinations, provider ->
                StartupInputs(
                    preferredLanding = preferredLanding,
                    topLevelDestinations = topLevelDestinations,
                    providerId = provider?.id,
                    catalogLayout = provider?.catalogLayout
                )
            }
                .distinctUntilChanged()
                .collectLatest { inputs ->
                    activeProviderId = inputs.providerId
                    val landingDestination = AppTopLevelDestination.resolveLandingDestination(
                        preferred = inputs.preferredLanding,
                        destinations = inputs.topLevelDestinations
                    )
                    val resolutionKey = StartupResolutionKey(landingDestination)
                    val landingRoute = startupResolver.destinationFor(landingDestination)
                    if (!startupNavigationRequested) {
                        resolvedStartupKey = resolutionKey
                    }
                    _state.update {
                        if (!startupNavigationRequested) {
                            it.copy(
                                startupTarget = StartupNavigationTarget(landingRoute),
                                topLevelDestinations = inputs.topLevelDestinations,
                                catalogLayout = inputs.catalogLayout
                            )
                        } else {
                            it.copy(
                                topLevelDestinations = inputs.topLevelDestinations,
                                catalogLayout = inputs.catalogLayout
                            )
                        }
                    }
                    val playerRequest = startupResolver.resolvePlayerRequest(landingDestination)
                    if (!startupNavigationRequested || startupResolutionKey == resolutionKey) {
                        startupPlayerRequest = playerRequest
                        _state.update {
                            it.copy(
                                startupTarget = it.startupTarget?.copy(playerRequest = playerRequest)
                                    ?: StartupNavigationTarget(landingRoute, playerRequest)
                            )
                        }
                        enqueueStartupPlayerIfReady()
                    }
                }
        }
    }

    private fun observeCatalogState() {
        viewModelScope.launch {
            providerRepository.getActiveProvider()
                .flatMapLatest { provider ->
                    if (provider == null) {
                        flowOf(CatalogState(null, null, ContentType.MOVIE, ready = false))
                    } else {
                        preferencesRepository.getLastSplitCatalogType(provider.id).map { lastSplitType ->
                            CatalogState(
                                providerId = provider.id,
                                layout = provider.catalogLayout,
                                lastSplitType = lastSplitType,
                                ready = true
                            )
                        }
                    }
                }
                .collectLatest { catalogState ->
                    activeProviderId = catalogState.providerId
                    _state.update {
                        it.copy(
                            catalogLayout = catalogState.layout,
                            lastSplitCatalogType = catalogState.lastSplitType,
                            splitPreferenceReady = catalogState.ready
                        )
                    }
                }
        }
    }

    fun submitExternalRequest(request: ExternalNavigationRequest) {
        submit(request.toNavigationCommand())
    }

    fun submit(command: NavigationCommand) {
        enqueue(command)
    }

    fun requestStartupNavigation(popUpTo: AppDestination) {
        if (startupNavigationRequested) return
        val target = state.value.startupTarget ?: return
        startupNavigationRequested = true
        startupResolutionKey = resolvedStartupKey
        startupPlayerRequest = target.playerRequest
        startupLiveDestinationResumed = false
        startupNavigationCommandId = enqueue(
            NavigationCommand.Navigate(
                destination = target.destination,
                options = NavigationOptions(popUpTo = popUpTo, inclusive = true)
            )
        ).id
    }

    fun onDestinationResumed(destination: AppDestination) {
        if (destination !is AppDestination.LiveTv) return
        startupLiveDestinationResumed = true
        enqueueStartupPlayerIfReady()
    }

    private fun enqueueStartupPlayerIfReady() {
        if (!startupNavigationAcknowledged || !startupLiveDestinationResumed || startupPlayerEnqueued) {
            return
        }
        val request = startupPlayerRequest ?: return
        startupPlayerEnqueued = true
        submit(NavigationCommand.OpenPlayer(request))
    }

    fun requestTopLevelNavigation(requested: AppDestination) {
        val currentState = state.value
        val destination = resolveCatalogDestination(
            layout = currentState.catalogLayout,
            requested = requested,
            lastSplitCatalogType = currentState.lastSplitCatalogType,
            splitPreferenceReady = currentState.splitPreferenceReady
        )
        val splitType = when (requested) {
            AppDestination.Movies -> ContentType.MOVIE
            AppDestination.Series -> ContentType.SERIES
            else -> null
        }
        if (currentState.catalogLayout == CatalogLayout.SPLIT && splitType != null) {
            _state.update { it.copy(lastSplitCatalogType = splitType, splitPreferenceReady = true) }
            activeProviderId?.let { providerId ->
                viewModelScope.launch {
                    preferencesRepository.setLastSplitCatalogType(providerId, splitType)
                }
            }
        }
        submit(
            NavigationCommand.Navigate(
                destination = destination,
                options = NavigationOptions(
                    launchSingleTop = true,
                    restoreState = true,
                    saveState = true,
                    popUpTo = AppDestination.Welcome
                )
            )
        )
    }

    private fun enqueue(command: NavigationCommand): PendingNavigationCommand {
        val pending = PendingNavigationCommand(commandIds.next(), command)
        commandQueue += pending
        if (_pendingCommand.value == null) {
            _pendingCommand.value = pending
        }
        return pending
    }

    fun acknowledge(id: Long) {
        val pending = commandQueue.firstOrNull() ?: return
        if (pending.id != id) return
        commandQueue.removeFirst()
        if (startupNavigationCommandId == id) {
            startupNavigationAcknowledged = true
            startupNavigationCommandId = null
            enqueueStartupPlayerIfReady()
        }
        _pendingCommand.value = commandQueue.firstOrNull()
    }

    private data class StartupInputs(
        val preferredLanding: AppLandingDestination,
        val topLevelDestinations: List<AppTopLevelDestination>,
        val providerId: Long?,
        val catalogLayout: CatalogLayout?
    )

    private data class StartupResolutionKey(
        val landingDestination: AppLandingDestination
    )

    private data class CatalogState(
        val providerId: Long?,
        val layout: CatalogLayout?,
        val lastSplitType: ContentType,
        val ready: Boolean
    )
}

internal fun ExternalNavigationRequest.toNavigationCommand(): NavigationCommand = when (this) {
    is ExternalNavigationRequest.Search -> NavigationCommand.Navigate(
        destination = AppDestination.Search(query),
        options = NavigationOptions(launchSingleTop = true)
    )
    is ExternalNavigationRequest.Player -> NavigationCommand.OpenPlayer(request)
    is ExternalNavigationRequest.Destination -> NavigationCommand.Navigate(
        destination = destination,
        options = NavigationOptions(launchSingleTop = true)
    )
    is ExternalNavigationRequest.ImportM3u -> NavigationCommand.Navigate(
        destination = AppDestination.ProviderSetup(importUri = uri),
        options = NavigationOptions(launchSingleTop = true)
    )
    is ExternalNavigationRequest.ImportBackup -> NavigationCommand.Navigate(
        destination = AppDestination.Settings(backupUri = uri),
        options = NavigationOptions(launchSingleTop = true)
    )
}

fun interface NavigationCommandIdSource {
    fun next(): Long
}

internal class AtomicNavigationCommandIdSource @Inject constructor() : NavigationCommandIdSource {
    private val nextId = AtomicLong(0L)
    override fun next(): Long = nextId.incrementAndGet()
}
