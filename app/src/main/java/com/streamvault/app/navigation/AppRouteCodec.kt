package com.streamvault.app.navigation

import com.streamvault.feature.provider.navigation.ProviderRoutePatterns

import android.net.Uri
import com.streamvault.core.navigation.AppDestination
import com.streamvault.feature.playback.navigation.PlaybackRoutePatterns
import java.net.URLDecoder

internal object AppRoutePatterns {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val LIVE_TV = "live_tv"
    const val LIVE_TV_DESTINATION = "live_tv?categoryId={categoryId}"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val VOD = "vod"
    const val DOWNLOADS = "downloads"
    const val EPG = "epg"
    const val EPG_DESTINATION =
        "epg?categoryId={categoryId}&anchorTime={anchorTime}&favoritesOnly={favoritesOnly}"
    const val SETTINGS = "settings"
    const val SETTINGS_DESTINATION = "settings?backupUri={backupUri}"
    const val PLUGINS = "plugins"
    const val PLAYER = PlaybackRoutePatterns.PLAYER
    const val SEARCH = "search"
    const val SEARCH_DESTINATION = "search?query={query}"
    const val PROVIDER_SETUP = ProviderRoutePatterns.PROVIDER_SETUP
    const val MOVIE_DETAIL = "movie_detail/{movieId}?returnRoute={returnRoute}"
    const val SERIES_DETAIL = "series_detail/{seriesId}?returnRoute={returnRoute}"
    const val PARENTAL_CONTROL_GROUPS = "parental_control_groups/{providerId}"
    const val MULTI_VIEW = PlaybackRoutePatterns.MULTI_VIEW
}

internal object AppRouteCodec {
    fun encode(destination: AppDestination): String = when (destination) {
        AppDestination.Welcome -> AppRoutePatterns.WELCOME
        AppDestination.Home -> AppRoutePatterns.HOME
        is AppDestination.LiveTv -> destination.categoryId?.let { categoryId ->
            "${AppRoutePatterns.LIVE_TV}?categoryId=$categoryId"
        } ?: AppRoutePatterns.LIVE_TV
        AppDestination.Movies -> AppRoutePatterns.MOVIES
        AppDestination.Series -> AppRoutePatterns.SERIES
        AppDestination.Vod -> AppRoutePatterns.VOD
        AppDestination.Downloads -> AppRoutePatterns.DOWNLOADS
        is AppDestination.Guide -> {
            val categoryId = destination.categoryId ?: -1L
            val anchorTime = destination.anchorTimeMs ?: -1L
            "${AppRoutePatterns.EPG}?categoryId=$categoryId" +
                "&anchorTime=$anchorTime&favoritesOnly=${destination.favoritesOnly}"
        }
        is AppDestination.Settings -> destination.backupUri?.takeIf(String::isNotBlank)?.let { uri ->
            "${AppRoutePatterns.SETTINGS}?backupUri=${Uri.encode(uri)}"
        } ?: AppRoutePatterns.SETTINGS
        AppDestination.Plugins -> AppRoutePatterns.PLUGINS
        is AppDestination.Search -> destination.query?.takeIf(String::isNotBlank)?.let { query ->
            "${AppRoutePatterns.SEARCH}?query=${Uri.encode(query)}"
        } ?: AppRoutePatterns.SEARCH
        is AppDestination.ProviderSetup -> {
            val providerId = destination.providerId ?: -1L
            val importUri = Uri.encode(destination.importUri ?: "")
            "${AppRoutePatterns.PROVIDER_SETUP.substringBefore('?')}?providerId=$providerId&importUri=$importUri"
        }
        is AppDestination.MovieDetail -> {
            val returnRoute = destination.returnDestination?.let(::encode).orEmpty()
            "movie_detail/${destination.movieId}?returnRoute=${Uri.encode(returnRoute)}"
        }
        is AppDestination.SeriesDetail -> {
            val returnRoute = destination.returnDestination?.let(::encode).orEmpty()
            "series_detail/${destination.seriesId}?returnRoute=${Uri.encode(returnRoute)}"
        }
        is AppDestination.ParentalControlGroups ->
            "${AppRoutePatterns.PARENTAL_CONTROL_GROUPS.substringBefore("/{")}/${destination.providerId}"
        AppDestination.Player -> AppRoutePatterns.PLAYER
        AppDestination.MultiView -> AppRoutePatterns.MULTI_VIEW
    }

    fun decode(route: String): AppDestination? {
        val normalizedRoute = route.trim()
        if (normalizedRoute.isEmpty()) return null

        val path = normalizedRoute.substringBefore('?')
        val query = normalizedRoute.queryParameters()
        return when {
            path == AppRoutePatterns.WELCOME -> AppDestination.Welcome
            path == AppRoutePatterns.HOME -> AppDestination.Home
            path == AppRoutePatterns.LIVE_TV -> {
                when {
                    !query.containsKey("categoryId") -> AppDestination.LiveTv()
                    else -> query["categoryId"]?.toLongOrNull()?.let { categoryId ->
                        AppDestination.LiveTv(normalizeSentinel(categoryId))
                    }
                }
            }
            path == AppRoutePatterns.MOVIES -> AppDestination.Movies
            path == AppRoutePatterns.SERIES -> AppDestination.Series
            path == AppRoutePatterns.VOD -> AppDestination.Vod
            path == AppRoutePatterns.DOWNLOADS -> AppDestination.Downloads
            path == AppRoutePatterns.EPG -> decodeGuide(query)
            path == AppRoutePatterns.SETTINGS -> AppDestination.Settings(
                backupUri = query["backupUri"]?.takeIf(String::isNotBlank)
            )
            path == AppRoutePatterns.PLUGINS -> AppDestination.Plugins
            path == AppRoutePatterns.PLAYER -> AppDestination.Player
            path == AppRoutePatterns.MULTI_VIEW -> AppDestination.MultiView
            path == AppRoutePatterns.SEARCH -> AppDestination.Search(
                query["query"]?.takeIf(String::isNotBlank)
            )
            path == AppRoutePatterns.PROVIDER_SETUP.substringBefore('?') -> AppDestination.ProviderSetup(
                providerId = query.longOrNull("providerId")?.let(::normalizeSentinel),
                importUri = query["importUri"]?.takeIf(String::isNotBlank)
            )
            path.startsWith("movie_detail/") -> decodeMovieDetail(path, query)
            path.startsWith("series_detail/") -> decodeSeriesDetail(path, query)
            path.startsWith("parental_control_groups/") -> path
                .substringAfter("parental_control_groups/")
                .toLongOrNull()
                ?.let { providerId -> runCatching { AppDestination.ParentalControlGroups(providerId) }.getOrNull() }
            else -> null
        }
    }

    fun decodeLegacyExternalRoute(route: String): AppDestination? = decode(route)?.let { destination ->
        when (destination) {
            AppDestination.Home,
            AppDestination.Plugins,
            is AppDestination.ProviderSetup,
            is AppDestination.MovieDetail,
            is AppDestination.SeriesDetail -> destination
            else -> null
        }
    }

    private fun decodeGuide(query: Map<String, String>): AppDestination? {
        if (query.isEmpty()) return AppDestination.Guide()
        val categoryId = if (query.containsKey("categoryId")) {
            val rawCategoryId = query["categoryId"]?.toLongOrNull() ?: return null
            normalizeSentinel(rawCategoryId)
        } else {
            null
        }
        val anchorTime = if (query.containsKey("anchorTime")) {
            val rawAnchorTime = query["anchorTime"]?.toLongOrNull() ?: return null
            normalizeSentinel(rawAnchorTime)
        } else {
            null
        }
        val favoritesOnly = query["favoritesOnly"]?.toBooleanStrictOrNull()
            ?: if (query.containsKey("favoritesOnly")) return null else false
        return AppDestination.Guide(categoryId, anchorTime, favoritesOnly)
    }

    private fun decodeMovieDetail(path: String, query: Map<String, String>): AppDestination? {
        val movieId = path.substringAfter("movie_detail/").toLongOrNull() ?: return null
        return runCatching {
            AppDestination.MovieDetail(movieId, decodeReturnDestination(query["returnRoute"]))
        }.getOrNull()
    }

    private fun decodeSeriesDetail(path: String, query: Map<String, String>): AppDestination? {
        val seriesId = path.substringAfter("series_detail/").toLongOrNull() ?: return null
        return runCatching {
            AppDestination.SeriesDetail(seriesId, decodeReturnDestination(query["returnRoute"]))
        }.getOrNull()
    }

    private fun decodeReturnDestination(value: String?): AppDestination? =
        value?.takeIf(String::isNotBlank)?.let(::decode)

    private fun normalizeSentinel(value: Long): Long? = value.takeUnless { it == -1L }

    private fun Map<String, String>.longOrNull(key: String): Long? = this[key]?.toLongOrNull()
}

/** Temporary compatibility facade for route-based consumers during navigation migration. */
internal object Routes {
    const val PROVIDER_SETUP = AppRoutePatterns.PROVIDER_SETUP
    const val HOME = AppRoutePatterns.HOME
    const val LIVE_TV = AppRoutePatterns.LIVE_TV
    const val LIVE_TV_DESTINATION = AppRoutePatterns.LIVE_TV_DESTINATION
    const val MOVIES = AppRoutePatterns.MOVIES
    const val SERIES = AppRoutePatterns.SERIES
    const val VOD = AppRoutePatterns.VOD
    const val DOWNLOADS = AppRoutePatterns.DOWNLOADS
    const val EPG = AppRoutePatterns.EPG
    const val EPG_DESTINATION = AppRoutePatterns.EPG_DESTINATION
    const val SETTINGS = AppRoutePatterns.SETTINGS
    const val SETTINGS_DESTINATION = AppRoutePatterns.SETTINGS_DESTINATION
    const val PLUGINS = AppRoutePatterns.PLUGINS
    const val PLAYER = AppRoutePatterns.PLAYER
    const val SEARCH = AppRoutePatterns.SEARCH
    const val SEARCH_DESTINATION = AppRoutePatterns.SEARCH_DESTINATION
    const val MOVIE_DETAIL = AppRoutePatterns.MOVIE_DETAIL
    const val SERIES_DETAIL = AppRoutePatterns.SERIES_DETAIL
    const val WELCOME = AppRoutePatterns.WELCOME
    const val PARENTAL_CONTROL_GROUPS = AppRoutePatterns.PARENTAL_CONTROL_GROUPS
    const val MULTI_VIEW = AppRoutePatterns.MULTI_VIEW

    fun providerSetup(providerId: Long? = null, importUri: String? = null): String =
        AppRouteCodec.encode(AppDestination.ProviderSetup(providerId, importUri))

    fun liveTv(categoryId: Long? = null): String =
        AppRouteCodec.encode(AppDestination.LiveTv(categoryId))

    fun epg(categoryId: Long? = null, anchorTime: Long? = null, favoritesOnly: Boolean? = null): String =
        AppRouteCodec.encode(AppDestination.Guide(categoryId, anchorTime, favoritesOnly ?: false))

    fun search(query: String? = null): String =
        AppRouteCodec.encode(AppDestination.Search(query))

    fun settings(backupUri: String? = null): String =
        AppRouteCodec.encode(AppDestination.Settings(backupUri))

    fun movieDetail(movieId: Long, returnRoute: String? = null): String =
        AppRouteCodec.encode(
            AppDestination.MovieDetail(movieId, returnRoute?.let(AppRouteCodec::decode))
        )

    fun seriesDetail(seriesId: Long, returnRoute: String? = null): String =
        AppRouteCodec.encode(
            AppDestination.SeriesDetail(seriesId, returnRoute?.let(AppRouteCodec::decode))
        )

    fun parentalControlGroups(providerId: Long): String =
        AppRouteCodec.encode(AppDestination.ParentalControlGroups(providerId))
}

private fun String.queryParameters(): Map<String, String> {
    val query = substringAfter('?', missingDelimiterValue = "")
    if (query.isBlank()) return emptyMap()
    return query.split('&')
        .mapNotNull { entry ->
            val key = entry.substringBefore('=', missingDelimiterValue = "")
                .takeIf(String::isNotBlank)
                ?: return@mapNotNull null
            val rawValue = entry.substringAfter('=', missingDelimiterValue = "")
            val value = runCatching { URLDecoder.decode(rawValue, Charsets.UTF_8.name()) }
                .getOrNull()
                ?: return@mapNotNull null
            key to value
        }
        .toMap()
}
