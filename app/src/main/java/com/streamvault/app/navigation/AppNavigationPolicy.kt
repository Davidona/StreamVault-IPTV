package com.streamvault.app.navigation

import com.streamvault.core.navigation.AppDestination
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType

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
