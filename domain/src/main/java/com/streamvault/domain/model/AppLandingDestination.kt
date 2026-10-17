package com.streamvault.domain.model

enum class AppLandingDestination(val storageValue: String) {
    HOME("home"),
    LIVE_TV("live_tv"),
    SPORTS("sports"),
    FIRST_FAVORITE_LIVE("first_favorite_live"),
    LAST_WATCHED_LIVE("last_watched_live"),
    MOVIES("movies"),
    SERIES("series"),
    GUIDE("guide"),
    DOWNLOADS("downloads"),
    PLUGINS("plugins"),
    SETTINGS("settings");

    companion object {
        fun fromStorage(value: String?): AppLandingDestination =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: HOME
    }
}
