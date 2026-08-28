package com.streamvault.feature.live.navigation

/** Stable route contracts owned by the Live feature and consumed by the app codec. */
object LiveRoutePatterns {
    const val LIVE_TV = "live_tv"
    const val LIVE_TV_DESTINATION = "live_tv?categoryId={categoryId}"
    const val EPG = "epg"
    const val EPG_DESTINATION =
        "epg?categoryId={categoryId}&anchorTime={anchorTime}&favoritesOnly={favoritesOnly}"
}
