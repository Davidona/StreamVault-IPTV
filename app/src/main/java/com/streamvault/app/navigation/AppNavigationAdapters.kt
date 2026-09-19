package com.streamvault.app.navigation

import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTopLevelDestination

internal fun AppLandingDestination.toAppRoute(): String = when (this) {
    AppLandingDestination.HOME -> Routes.HOME
    AppLandingDestination.LIVE_TV -> Routes.LIVE_TV
    AppLandingDestination.FIRST_FAVORITE_LIVE -> Routes.LIVE_TV
    AppLandingDestination.LAST_WATCHED_LIVE -> Routes.LIVE_TV
    AppLandingDestination.MOVIES -> Routes.MOVIES
    AppLandingDestination.SERIES -> Routes.SERIES
    AppLandingDestination.GUIDE -> Routes.EPG
    AppLandingDestination.DOWNLOADS -> Routes.DOWNLOADS
    AppLandingDestination.PLUGINS -> Routes.PLUGINS
    AppLandingDestination.SETTINGS -> Routes.SETTINGS
}

internal fun AppTopLevelDestination.toAppRoute(): String = when (this) {
    AppTopLevelDestination.HOME -> Routes.HOME
    AppTopLevelDestination.LIVE_TV -> Routes.LIVE_TV
    AppTopLevelDestination.MOVIES -> Routes.MOVIES
    AppTopLevelDestination.SERIES -> Routes.SERIES
    AppTopLevelDestination.DOWNLOADS -> Routes.DOWNLOADS
    AppTopLevelDestination.GUIDE -> Routes.EPG
    AppTopLevelDestination.SEARCH -> Routes.SEARCH
    AppTopLevelDestination.PLUGINS -> Routes.PLUGINS
    AppTopLevelDestination.SETTINGS -> Routes.SETTINGS
}
