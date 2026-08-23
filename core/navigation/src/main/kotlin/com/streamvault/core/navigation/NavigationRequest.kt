package com.streamvault.core.navigation

import java.io.Serializable

sealed interface ExternalNavigationRequest : Serializable {
    data class Search(val query: String) : ExternalNavigationRequest
    data class Player(val request: PlayerNavigationRequest) : ExternalNavigationRequest
    data class Destination(val destination: AppDestination) : ExternalNavigationRequest
    data class ImportM3u(val uri: String) : ExternalNavigationRequest
    data class ImportBackup(val uri: String) : ExternalNavigationRequest
}

data class NavigationOptions(
    val launchSingleTop: Boolean = false,
    val restoreState: Boolean = false,
    val saveState: Boolean = false,
    val popUpTo: AppDestination? = null,
    val inclusive: Boolean = false
) : Serializable

sealed interface NavigationCommand : Serializable {
    data class Navigate(
        val destination: AppDestination,
        val options: NavigationOptions = NavigationOptions()
    ) : NavigationCommand

    data class OpenPlayer(val request: PlayerNavigationRequest) : NavigationCommand

    data object Back : NavigationCommand

    data class ReturnTo(val destination: AppDestination?) : NavigationCommand
}
