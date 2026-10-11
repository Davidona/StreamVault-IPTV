package com.streamvault.feature.stremio.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.streamvault.feature.stremio.api.StremioScaffoldContent
import com.streamvault.feature.stremio.presentation.AddonsScreen

fun NavGraphBuilder.registerStremioGraph(scaffold: StremioScaffoldContent) {
    composable(StremioRoutePatterns.ADDONS) {
        AddonsScreen(scaffold = scaffold)
    }
}
