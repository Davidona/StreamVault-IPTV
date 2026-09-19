package com.streamvault.app.navigation

import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.ContentType

data class AppNavigationState(
    val startupTarget: StartupNavigationTarget? = null,
    val topLevelDestinations: List<AppTopLevelDestination> = AppTopLevelDestination.defaultOrder,
    val catalogLayout: CatalogLayout? = null,
    val lastSplitCatalogType: ContentType = ContentType.MOVIE,
    val splitPreferenceReady: Boolean = false
)
