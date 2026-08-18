package com.streamvault.app.navigation

import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType

internal fun requiresResolvedStartupTarget(landingDestination: AppLandingDestination): Boolean =
    landingDestination == AppLandingDestination.FIRST_FAVORITE_LIVE ||
        landingDestination == AppLandingDestination.LAST_WATCHED_LIVE

internal fun resolveCatalogRoute(
    layout: CatalogLayout?,
    requestedRoute: String,
    lastSplitCatalogType: ContentType,
    splitPreferenceReady: Boolean
): String = when {
    layout != CatalogLayout.SPLIT && requestedRoute in setOf(Routes.MOVIES, Routes.SERIES) -> Routes.VOD
    layout == CatalogLayout.SPLIT && requestedRoute == Routes.VOD && splitPreferenceReady ->
        if (lastSplitCatalogType == ContentType.SERIES) Routes.SERIES else Routes.MOVIES
    else -> requestedRoute
}
