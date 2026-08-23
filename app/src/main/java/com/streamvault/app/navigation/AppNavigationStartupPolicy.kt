package com.streamvault.app.navigation

import com.streamvault.app.MainActivity
import com.streamvault.core.navigation.PlayerNavigationRequest
import com.streamvault.domain.model.AppLandingDestination

/** Temporary compatibility bridge for the pre-coordinator root wiring. */
internal suspend fun resolveStartupPlayerRequest(
    mainActivity: MainActivity,
    landingDestination: AppLandingDestination
): PlayerNavigationRequest? = mainActivity.startupNavigationResolver
    .resolve(landingDestination)
    .playerRequest
