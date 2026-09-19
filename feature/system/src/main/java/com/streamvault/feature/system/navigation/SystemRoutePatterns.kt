package com.streamvault.feature.system.navigation

object SystemRoutePatterns {
    const val WELCOME = "welcome"
    const val DOWNLOADS = "downloads"
    const val PLUGINS = "plugins"
}

internal fun systemGraphRoutes(): List<String> = listOf(
    SystemRoutePatterns.WELCOME,
    SystemRoutePatterns.DOWNLOADS,
    SystemRoutePatterns.PLUGINS,
)
