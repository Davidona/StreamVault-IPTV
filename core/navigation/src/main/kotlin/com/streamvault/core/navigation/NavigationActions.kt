package com.streamvault.core.navigation

interface NavigationActions {
    fun navigate(
        destination: AppDestination,
        options: NavigationOptions = NavigationOptions()
    )

    fun openPlayer(request: PlayerNavigationRequest)

    fun back(): Boolean

    fun returnTo(destination: AppDestination?): Boolean
}
