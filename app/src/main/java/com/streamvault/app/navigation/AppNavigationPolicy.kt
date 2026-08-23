package com.streamvault.app.navigation

import com.streamvault.core.navigation.AppDestination
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType

internal fun requiresResolvedStartupTarget(landingDestination: AppLandingDestination): Boolean =
    landingDestination == AppLandingDestination.FIRST_FAVORITE_LIVE ||
        landingDestination == AppLandingDestination.LAST_WATCHED_LIVE

internal fun resolveCatalogDestination(
    layout: CatalogLayout?,
    requested: AppDestination,
    lastSplitCatalogType: ContentType,
    splitPreferenceReady: Boolean
): AppDestination = when {
    layout != CatalogLayout.SPLIT && requested in setOf(AppDestination.Movies, AppDestination.Series) ->
        AppDestination.Vod
    layout == CatalogLayout.SPLIT && requested == AppDestination.Vod && splitPreferenceReady ->
        if (lastSplitCatalogType == ContentType.SERIES) AppDestination.Series else AppDestination.Movies
    else -> requested
}

/** Temporary route adapter for the pre-coordinator root wiring. */
internal fun resolveCatalogRoute(
    layout: CatalogLayout?,
    requestedRoute: String,
    lastSplitCatalogType: ContentType,
    splitPreferenceReady: Boolean
): String = AppRouteCodec.decode(requestedRoute)?.let { decoded ->
    AppRouteCodec.encode(
        resolveCatalogDestination(layout, decoded, lastSplitCatalogType, splitPreferenceReady)
    )
} ?: requestedRoute
