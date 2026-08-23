package com.streamvault.app.navigation

import androidx.lifecycle.ViewModel
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.navigation.ExternalNavigationRequest
import com.streamvault.core.navigation.NavigationCommand
import com.streamvault.core.navigation.NavigationOptions
import com.streamvault.core.navigation.PlayerNavigationRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PendingNavigationCommand(
    val id: Long,
    val command: NavigationCommand
)

@HiltViewModel
class AppNavigationCoordinator @Inject constructor(
    private val commandIds: NavigationCommandIdSource
) : ViewModel() {
    private val commandQueue = ArrayDeque<PendingNavigationCommand>()
    private val _pendingCommand = MutableStateFlow<PendingNavigationCommand?>(null)
    val pendingCommand: StateFlow<PendingNavigationCommand?> = _pendingCommand.asStateFlow()

    fun submitExternalRequest(request: ExternalNavigationRequest) {
        submit(request.toNavigationCommand())
    }

    fun submit(command: NavigationCommand) {
        commandQueue += PendingNavigationCommand(commandIds.next(), command)
        if (_pendingCommand.value == null) {
            _pendingCommand.value = commandQueue.first()
        }
    }

    fun acknowledge(id: Long) {
        val pending = commandQueue.firstOrNull() ?: return
        if (pending.id != id) return
        commandQueue.removeFirst()
        _pendingCommand.value = commandQueue.firstOrNull()
    }
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
