package com.streamvault.app.navigation

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavHostController
import androidx.navigation.NavOptionsBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.streamvault.app.ui.model.isArchivePlayable
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.app.ui.screens.dashboard.DashboardScreen
import com.streamvault.app.ui.screens.multiview.MultiViewScreen
import com.streamvault.app.ui.screens.home.HomeScreen
import com.streamvault.app.ui.screens.movies.MoviesScreen
import com.streamvault.app.ui.screens.player.PlayerScreen
import com.streamvault.app.ui.screens.plugins.PluginsScreen
import com.streamvault.app.ui.screens.provider.ProviderSetupScreen
import com.streamvault.app.ui.screens.series.SeriesScreen
import com.streamvault.app.ui.screens.vod.VodScreen
import com.streamvault.app.ui.screens.settings.SettingsScreen
import com.streamvault.app.ui.screens.welcome.WelcomeScreen
import com.streamvault.app.ui.screens.downloads.DownloadsScreen
import com.streamvault.app.MainActivity
import com.streamvault.domain.model.AppLandingDestination
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.MovieDetailPresentationHint
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SeriesDetailPresentationHint
import com.streamvault.domain.model.VirtualCategoryIds
import java.io.Serializable
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine


internal const val PLAYER_REQUEST_KEY = "player_request"
internal const val MOVIE_DETAIL_PRESENTATION_HINT_KEY = "movie_detail_presentation_hint"
internal const val SERIES_DETAIL_PRESENTATION_HINT_KEY = "series_detail_presentation_hint"
internal const val APP_NAVIGATION_TAG = "AppNavigation"

data class PlayerNavigationRequest(
    val streamUrl: String,
    val title: String,
    val channelId: String? = null,
    val internalId: Long = -1L,
    val categoryId: Long? = null,
    val providerId: Long? = null,
    val isVirtual: Boolean = false,
    val combinedProfileId: Long? = null,
    val combinedSourceFilterProviderId: Long? = null,
    val contentType: String = "LIVE",
    val artworkUrl: String? = null,
    val archiveStartMs: Long? = null,
    val archiveEndMs: Long? = null,
    val archiveTitle: String? = null,
    val returnRoute: String? = null,
    val seriesId: Long? = null,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val episodeId: Long? = null
) : Serializable

object Routes {
    const val PROVIDER_SETUP = "provider_setup?providerId={providerId}&importUri={importUri}"
    const val HOME = "home"
    const val LIVE_TV = "live_tv"
    const val LIVE_TV_DESTINATION = "live_tv?categoryId={categoryId}"
    const val MOVIES = "movies"
    const val SERIES = "series"
    const val VOD = "vod"
    const val DOWNLOADS = "downloads"
    const val EPG = "epg"
    const val EPG_DESTINATION = "epg?categoryId={categoryId}&anchorTime={anchorTime}&favoritesOnly={favoritesOnly}"
    const val SETTINGS = "settings"
    const val SETTINGS_DESTINATION = "settings?backupUri={backupUri}"
    const val PLUGINS = "plugins"
    const val PLAYER = "player"
    const val SEARCH = "search"
    const val SEARCH_DESTINATION = "search?query={query}"
    const val MOVIE_DETAIL = "movie_detail/{movieId}?returnRoute={returnRoute}"
    const val SERIES_DETAIL = "series_detail/{seriesId}?returnRoute={returnRoute}"
    const val WELCOME = "welcome"
    const val PARENTAL_CONTROL_GROUPS = "parental_control_groups/{providerId}"
    const val MULTI_VIEW = "multi_view"


    fun providerSetup(providerId: Long? = null, importUri: String? = null): String {
        val encodedImportUri = Uri.encode(importUri ?: "")
        return "provider_setup?providerId=${providerId ?: -1L}&importUri=$encodedImportUri"
    }
    fun liveTv(categoryId: Long? = null) = if (categoryId == null) LIVE_TV else "$LIVE_TV?categoryId=$categoryId"
    fun epg(categoryId: Long? = null, anchorTime: Long? = null, favoritesOnly: Boolean? = null): String {
        val resolvedCategoryId = categoryId ?: -1L
        val resolvedAnchorTime = anchorTime ?: -1L
        val resolvedFavoritesOnly = favoritesOnly ?: false
        return "$EPG?categoryId=$resolvedCategoryId&anchorTime=$resolvedAnchorTime&favoritesOnly=$resolvedFavoritesOnly"
    }

    fun livePlayer(
        channel: Channel,
        categoryId: Long? = channel.categoryId,
        providerId: Long? = channel.providerId,
        isVirtual: Boolean = false,
        combinedProfileId: Long? = null,
        combinedSourceFilterProviderId: Long? = null,
        returnRoute: String? = null
    ): PlayerNavigationRequest {
        val effectiveCategoryId = categoryId ?: ChannelRepository.ALL_CHANNELS_ID
        return player(
            streamUrl = channel.streamUrl,
            title = channel.name,
            channelId = channel.epgChannelId,
            internalId = channel.id,
            categoryId = effectiveCategoryId,
            providerId = providerId,
            isVirtual = isVirtual,
            combinedProfileId = combinedProfileId,
            combinedSourceFilterProviderId = combinedSourceFilterProviderId,
            contentType = "LIVE",
            returnRoute = returnRoute
        )
    }

    fun moviePlayer(movie: Movie): PlayerNavigationRequest {
        return player(
            streamUrl = movie.streamUrl,
            title = movie.name,
            internalId = movie.id,
            categoryId = movie.categoryId,
            providerId = movie.providerId,
            contentType = "MOVIE",
            artworkUrl = movie.posterUrl ?: movie.backdropUrl
        )
    }

    fun episodePlayer(episode: Episode): PlayerNavigationRequest {
        return player(
            streamUrl = episode.streamUrl,
            title = "${episode.title} - S${episode.seasonNumber}E${episode.episodeNumber}",
            internalId = episode.id,
            providerId = episode.providerId,
            contentType = "SERIES_EPISODE",
            artworkUrl = episode.coverUrl,
            seriesId = episode.seriesId.takeIf { it > 0L },
            seasonNumber = episode.seasonNumber,
            episodeNumber = episode.episodeNumber,
            episodeId = episode.episodeId.takeIf { it > 0L }
        )
    }

    fun search(query: String? = null): String =
        if (query.isNullOrBlank()) SEARCH else "$SEARCH?query=${Uri.encode(query)}"

    fun settings(backupUri: String? = null): String =
        if (backupUri.isNullOrBlank()) SETTINGS else "$SETTINGS?backupUri=${Uri.encode(backupUri)}"

    fun player(
        streamUrl: String,
        title: String,
        channelId: String? = null,
        internalId: Long = -1L,
        categoryId: Long? = null,
        providerId: Long? = null,
        isVirtual: Boolean = false,
        combinedProfileId: Long? = null,
        combinedSourceFilterProviderId: Long? = null,
        contentType: String = "LIVE",
        artworkUrl: String? = null,
        archiveStartMs: Long? = null,
        archiveEndMs: Long? = null,
        archiveTitle: String? = null,
        returnRoute: String? = null,
        seriesId: Long? = null,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
        episodeId: Long? = null
    ): PlayerNavigationRequest {
        return PlayerNavigationRequest(
            streamUrl = streamUrl,
            title = title,
            channelId = channelId,
            internalId = internalId,
            categoryId = categoryId,
            providerId = providerId,
            isVirtual = isVirtual,
            combinedProfileId = combinedProfileId,
            combinedSourceFilterProviderId = combinedSourceFilterProviderId,
            contentType = contentType,
            artworkUrl = artworkUrl,
            archiveStartMs = archiveStartMs,
            archiveEndMs = archiveEndMs,
            archiveTitle = archiveTitle,
            returnRoute = returnRoute,
            seriesId = seriesId,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            episodeId = episodeId
        )
    }

    fun movieDetail(movieId: Long, returnRoute: String? = null) =
        "movie_detail/$movieId?returnRoute=${Uri.encode(returnRoute ?: "")}"
    fun seriesDetail(seriesId: Long, returnRoute: String? = null) =
        "series_detail/$seriesId?returnRoute=${Uri.encode(returnRoute ?: "")}"
    fun parentalControlGroups(providerId: Long) = "parental_control_groups/$providerId"
}

/** Accepts app-supported media schemes while still rejecting obviously unsafe ones. */
private fun isStreamUrlSafe(url: String?): Boolean {
    if (url.isNullOrBlank()) return false
    val scheme = url.substringBefore("://").lowercase()
    return scheme in setOf("http", "https", "rtsp", "rtmp", "rtsps", "mms", "xtream", "stalker", "content", "file")
}

internal fun safePlayerNavigationRequest(request: PlayerNavigationRequest?): PlayerNavigationRequest? =
    request?.takeIf { isStreamUrlSafe(it.streamUrl) }

