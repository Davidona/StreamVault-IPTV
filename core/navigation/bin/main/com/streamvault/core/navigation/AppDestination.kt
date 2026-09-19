package com.streamvault.core.navigation

import java.io.Serializable

sealed interface AppDestination : Serializable {
    data object Welcome : AppDestination
    data object Home : AppDestination
    data class LiveTv(val categoryId: Long? = null) : AppDestination
    data object Movies : AppDestination
    data object Series : AppDestination
    data object Vod : AppDestination
    data object Downloads : AppDestination
    data class Guide(
        val categoryId: Long? = null,
        val anchorTimeMs: Long? = null,
        val favoritesOnly: Boolean = false
    ) : AppDestination
    data class Settings(val backupUri: String? = null) : AppDestination
    data object Plugins : AppDestination
    data class Search(val query: String? = null) : AppDestination
    data class ProviderSetup(
        val providerId: Long? = null,
        val importUri: String? = null
    ) : AppDestination
    data class MovieDetail(
        val movieId: Long,
        val returnDestination: AppDestination? = null
    ) : AppDestination {
        init {
            require(movieId > 0L) { "movieId must be positive" }
        }
    }
    data class SeriesDetail(
        val seriesId: Long,
        val returnDestination: AppDestination? = null
    ) : AppDestination {
        init {
            require(seriesId > 0L) { "seriesId must be positive" }
        }
    }
    data class ParentalControlGroups(val providerId: Long) : AppDestination {
        init {
            require(providerId > 0L) { "providerId must be positive" }
        }
    }
    data object Player : AppDestination
    data object MultiView : AppDestination
}
