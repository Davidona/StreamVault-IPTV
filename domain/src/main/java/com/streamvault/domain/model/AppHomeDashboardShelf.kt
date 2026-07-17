package com.streamvault.domain.model

enum class AppHomeDashboardShelf(
    val storageValue: String,
    val defaultEnabled: Boolean
) {
    FAVORITE_CHANNELS("favorite_channels", defaultEnabled = true),
    RECENT_CHANNELS("recent_channels", defaultEnabled = true),
    LIVE_SHORTCUTS("live_shortcuts", defaultEnabled = true),
    CONTINUE_WATCHING("continue_watching", defaultEnabled = true),
    PINNED_CATEGORIES("pinned_categories", defaultEnabled = true),
    RECENT_MOVIES("recent_movies", defaultEnabled = true),
    RECENT_SERIES("recent_series", defaultEnabled = true),
    FAVORITE_MOVIES("favorite_movies", defaultEnabled = false),
    FAVORITE_SERIES("favorite_series", defaultEnabled = false),
    CONTINUE_WATCHING_MOVIES("continue_watching_movies", defaultEnabled = false),
    CONTINUE_WATCHING_SERIES("continue_watching_series", defaultEnabled = false),
    TOP_RATED_MOVIES("top_rated_movies", defaultEnabled = false),
    RECOMMENDED_MOVIES("recommended_movies", defaultEnabled = false);

    companion object {
        val catalogOrder: List<AppHomeDashboardShelf> = listOf(
            FAVORITE_CHANNELS,
            RECENT_CHANNELS,
            LIVE_SHORTCUTS,
            CONTINUE_WATCHING,
            PINNED_CATEGORIES,
            RECENT_MOVIES,
            RECENT_SERIES,
            FAVORITE_MOVIES,
            FAVORITE_SERIES,
            CONTINUE_WATCHING_MOVIES,
            CONTINUE_WATCHING_SERIES,
            TOP_RATED_MOVIES,
            RECOMMENDED_MOVIES
        )

        val defaultOrder: List<AppHomeDashboardShelf> = catalogOrder.filter { it.defaultEnabled }

        fun fromStorage(value: String?): AppHomeDashboardShelf? =
            catalogOrder.firstOrNull { it.storageValue.equals(value, ignoreCase = true) }

        fun normalizeForStorage(shelves: List<AppHomeDashboardShelf>): List<AppHomeDashboardShelf> {
            val unique = linkedSetOf<AppHomeDashboardShelf>()
            shelves.forEach { shelf ->
                if (shelf in catalogOrder) {
                    unique += shelf
                }
            }
            return unique.toList()
        }

        fun displayOrder(enabledShelves: List<AppHomeDashboardShelf>): List<AppHomeDashboardShelf> {
            val normalized = normalizeForStorage(enabledShelves)
            val hidden = catalogOrder.filterNot { it in normalized }
            return normalized + hidden
        }
    }
}
