package com.streamvault.domain.model

enum class AppTopLevelDestination(
    val storageValue: String,
    val landingDestination: AppLandingDestination? = null,
    val isRequired: Boolean = false
) {
    HOME("home", AppLandingDestination.HOME),
    LIVE_TV("live_tv", AppLandingDestination.LIVE_TV),
    SPORTS("sports", AppLandingDestination.SPORTS),
    MOVIES("movies", AppLandingDestination.MOVIES),
    SERIES("series", AppLandingDestination.SERIES),
    DOWNLOADS("downloads", AppLandingDestination.DOWNLOADS),
    GUIDE("guide", AppLandingDestination.GUIDE),
    SEARCH("search"),
    PLUGINS("plugins", AppLandingDestination.PLUGINS),
    SETTINGS("settings", AppLandingDestination.SETTINGS, isRequired = true);

    companion object {
        val defaultOrder: List<AppTopLevelDestination> = listOf(
            HOME,
            LIVE_TV,
            SPORTS,
            MOVIES,
            SERIES,
            DOWNLOADS,
            GUIDE,
            SEARCH,
            PLUGINS,
            SETTINGS
        )

        fun fromStorage(value: String?): AppTopLevelDestination? =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) }

        fun normalizeForStorage(destinations: List<AppTopLevelDestination>): List<AppTopLevelDestination> {
            val unique = linkedSetOf<AppTopLevelDestination>()
            destinations.forEach(unique::add)
            unique += SETTINGS
            return unique.toList()
        }

        fun availableLandingDestinations(
            destinations: List<AppTopLevelDestination>
        ): List<AppLandingDestination> {
            val normalized = normalizeForStorage(destinations)
            val available = normalized
                .mapNotNull { it.landingDestination }
                .distinct()
                .toMutableList()
            if (LIVE_TV in normalized) {
                val liveTvIndex = available.indexOf(AppLandingDestination.LIVE_TV)
                if (liveTvIndex >= 0) {
                    available.add(liveTvIndex + 1, AppLandingDestination.FIRST_FAVORITE_LIVE)
                    available.add(liveTvIndex + 2, AppLandingDestination.LAST_WATCHED_LIVE)
                } else {
                    available += listOf(
                        AppLandingDestination.FIRST_FAVORITE_LIVE,
                        AppLandingDestination.LAST_WATCHED_LIVE
                    )
                }
            }
            return available
        }

        fun resolveLandingDestination(
            preferred: AppLandingDestination,
            destinations: List<AppTopLevelDestination>
        ): AppLandingDestination {
            val available = availableLandingDestinations(destinations)
            return if (preferred in available) {
                preferred
            } else {
                available.firstOrNull() ?: AppLandingDestination.SETTINGS
            }
        }
    }
}
