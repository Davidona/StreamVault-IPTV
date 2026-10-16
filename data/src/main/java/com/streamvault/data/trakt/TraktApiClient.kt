package com.streamvault.data.trakt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.streamvault.domain.trakt.TraktMediaIdentity
import com.streamvault.domain.trakt.TraktScrobbleEvent
import com.streamvault.domain.trakt.TraktHistoryEntry
import com.streamvault.domain.trakt.TraktHistoryPage
import com.streamvault.domain.trakt.TraktHistoryUpload
import com.streamvault.domain.trakt.TraktHistoryUploadResult
import com.streamvault.domain.trakt.TraktWatchlistIdentity
import com.streamvault.domain.trakt.TraktWatchlistPage
import com.streamvault.domain.trakt.TraktWatchlistType
import com.streamvault.domain.trakt.TraktFavoriteIdentity
import com.streamvault.domain.trakt.TraktFavoritePage
import com.streamvault.domain.trakt.TraktFavoriteType
import com.streamvault.domain.trakt.TraktUserProfile
import com.streamvault.domain.trakt.TraktRatingIdentity
import com.streamvault.domain.trakt.TraktRatingPage
import com.streamvault.domain.trakt.TraktRatingType
import com.streamvault.domain.trakt.TraktCustomList
import com.streamvault.domain.trakt.TraktCustomListItem
import com.streamvault.domain.trakt.TraktCustomListItemIdentity
import com.streamvault.domain.trakt.TraktCustomListItemType
import com.streamvault.domain.trakt.TraktCustomListItemsPage
import com.streamvault.domain.trakt.TraktDiscoveryCategory
import com.streamvault.domain.trakt.TraktDiscoveryItem
import com.streamvault.domain.trakt.TraktDiscoveryPage
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Dedicated client without provider interceptors, disk cache, or body logging. */
class TraktApiClient(
    private val config: TraktConfig,
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.trakt.tv/".toHttpUrl(),
    private val userAgent: String = "StreamVault",
) : TraktApi, TraktPlaybackApi, TraktHistoryApi, TraktWatchlistApi, TraktFavoritesApi, TraktRatingsApi,
    TraktCustomListsApi, TraktDiscoveryApi {
    private val gson = Gson()
    private val requestMutex = Mutex()
    private var nextRequestAt = 0L
    private var lastWriteAt = 0L
    private var rateLimitFailures = 0
    private val episodeIds = object : LinkedHashMap<TraktMediaIdentity.EpisodeId, Long>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TraktMediaIdentity.EpisodeId, Long>?) = size > 128
    }

    override suspend fun history(accessToken: String, page: Int): TraktHistoryPage {
        require(page in 1..10000)
        var pageCount: Int? = null
        val response = request("sync/history?page=$page&limit=100", accessToken,
            observeHeaders = { pageCount = it["X-Pagination-Page-Count"]?.toIntOrNull()?.takeIf { count -> count >= 0 } })
        val rows = gson.fromJson(response, JsonArray::class.java)
        var skipped = 0
        val entries = rows.mapNotNull { row ->
            runCatching {
                val item = row.asJsonObject
                val id = item["id"].asString.toLong()
                val watchedAt = Instant.parse(item["watched_at"].asString).toEpochMilli()
                val identity = when (item["type"].asString) {
                    "movie" -> TraktMediaIdentity.MovieId(item.getAsJsonObject("movie").getAsJsonObject("ids")["tmdb"].asString.toLong())
                    "episode" -> TraktMediaIdentity.EpisodeId(
                        item.getAsJsonObject("show").getAsJsonObject("ids")["tmdb"].asString.toLong(),
                        item.getAsJsonObject("episode")["season"].asString.toInt(),
                        item.getAsJsonObject("episode")["number"].asString.toInt())
                    else -> error("Unsupported history media")
                }
                TraktHistoryEntry(id, identity, watchedAt)
            }.getOrElse { skipped++; null }
        }
        return TraktHistoryPage(entries, page, pageCount?.let { page < it } ?: (rows.size() >= 100), skipped)
    }

    override suspend fun watchlist(accessToken: String, type: TraktWatchlistType, page: Int): TraktWatchlistPage {
        require(page in 1..10000)
        var pageCount: Int? = null
        val response = request("sync/watchlist/${type.apiPath}?page=$page&limit=100", accessToken,
            observeHeaders = { pageCount = it["X-Pagination-Page-Count"]?.toIntOrNull()?.takeIf { count -> count >= 0 } })
        val rows = gson.fromJson(response, JsonArray::class.java)
        var skipped = 0
        val items = rows.mapNotNull { row ->
            runCatching {
                val wrapper = row.asJsonObject
                val media = wrapper.getAsJsonObject(if (type == TraktWatchlistType.MOVIE) "movie" else "show")
                    ?: error("Missing watchlist item")
                val tmdbId = media.getAsJsonObject("ids")?.get("tmdb")?.takeUnless { it.isJsonNull }
                    ?.asString?.toLongOrNull()
                    ?.takeIf { it > 0 } ?: error("Missing TMDB id")
                TraktWatchlistIdentity(type, tmdbId)
            }.getOrElse { skipped++; null }
        }
        return TraktWatchlistPage(items, page, pageCount?.let { page < it } ?: (rows.size() >= 100), skipped)
    }

    override suspend fun discover(category: TraktDiscoveryCategory, page: Int): TraktDiscoveryPage {
        require(page in 1..10000)
        val response = request("${category.media.apiPath}/${category.kind.apiPath}?page=$page&limit=$DISCOVERY_PAGE_SIZE")
        val rows = gson.fromJson(response, JsonArray::class.java)
        var skipped = 0
        val entries = rows.mapNotNull { row ->
            runCatching {
                val entry = row.asJsonObject
                // Trending wraps media with watcher metadata; popular returns the media object directly.
                val mediaKey = if (category.media == com.streamvault.domain.trakt.TraktDiscoveryMedia.MOVIE) "movie" else "show"
                val media = entry.getAsJsonObject(mediaKey) ?: entry
                val tmdbId = media.getAsJsonObject("ids")?.get("tmdb")?.takeUnless { it.isJsonNull }
                    ?.asString?.toLongOrNull()?.takeIf { it > 0L }
                val title = media.get("title")?.takeUnless { it.isJsonNull }?.asString.orEmpty()
                val year = media.get("year")?.takeUnless { it.isJsonNull }?.asInt
                if (tmdbId == null && title.isBlank()) error("Missing discovery identity")
                TraktDiscoveryItem(tmdbId = tmdbId, title = title, year = year)
            }.getOrElse { skipped++; null }
        }.distinctBy { item ->
            item.tmdbId?.let { "tmdb:$it" } ?: "title:${item.title.lowercase(Locale.ROOT)}:${item.year}"
        }.take(DISCOVERY_PAGE_SIZE)
        return TraktDiscoveryPage(entries.mapNotNull(TraktDiscoveryItem::tmdbId), skipped, entries)
    }

    override suspend fun setWatchlisted(
        accessToken: String,
        identity: TraktWatchlistIdentity,
        watchlisted: Boolean,
    ) {
        val mediaKey = if (identity.type == TraktWatchlistType.MOVIE) "movies" else "shows"
        val payload = mapOf(mediaKey to listOf(mapOf("ids" to mapOf("tmdb" to identity.tmdbId))))
        val path = if (watchlisted) "sync/watchlist" else "sync/watchlist/remove"
        request(path, accessToken, payload)
    }

    override suspend fun setFavorited(
        accessToken: String,
        identity: TraktFavoriteIdentity,
        favorited: Boolean,
    ) {
        val payload = mapOf(identity.type.payloadKey to listOf(mapOf("ids" to mapOf("tmdb" to identity.tmdbId))))
        val path = if (favorited) "sync/favorites" else "sync/favorites/remove"
        request(path, accessToken, payload)
    }

    override suspend fun favorites(
        accessToken: String,
        type: TraktFavoriteType,
        page: Int,
    ): TraktFavoritePage {
        require(page in 1..MAX_SYNC_PAGES)
        var pageCount: Int? = null
        val rows = gson.fromJson(
            request("sync/favorites/${type.apiPath}?page=$page&limit=$SYNC_PAGE_SIZE", accessToken,
                observeHeaders = { pageCount = it["X-Pagination-Page-Count"]?.toIntOrNull()?.takeIf { count -> count >= 0 } }),
            JsonArray::class.java,
        )
        var skipped = 0
        val items = rows.mapNotNull { row ->
            runCatching {
                val wrapper = row.asJsonObject
                val media = wrapper.getAsJsonObject(type.responseKey) ?: error("Missing favorite item")
                val tmdbId = media.getAsJsonObject("ids")?.get("tmdb")?.takeUnless { it.isJsonNull }
                    ?.asString?.toLongOrNull()?.takeIf { it > 0L } ?: error("Missing TMDB id")
                TraktFavoriteIdentity(type, tmdbId)
            }.getOrElse { skipped++; null }
        }.distinct()
        return TraktFavoritePage(
            identities = items,
            page = page,
            hasMore = pageCount?.let { page < it } ?: (rows.size() >= SYNC_PAGE_SIZE),
            skippedItems = skipped,
        )
    }

    override suspend fun ratings(accessToken: String, type: TraktRatingType, page: Int): TraktRatingPage {
        require(page in 1..10000)
        var pageCount: Int? = null
        val response = request("users/me/ratings/${type.apiPath}?page=$page&limit=100", accessToken,
            observeHeaders = { pageCount = it["X-Pagination-Page-Count"]?.toIntOrNull()?.takeIf { count -> count >= 0 } })
        val rows = gson.fromJson(response, JsonArray::class.java)
        var skipped = 0
        val items = rows.mapNotNull { row ->
            runCatching {
                val item = row.asJsonObject
                require(item["type"]?.asString == type.responseKey)
                val rating = item["rating"]?.asInt?.takeIf { it in 1..10 } ?: error("Invalid rating")
                val media = item.getAsJsonObject(type.responseKey) ?: error("Missing rated media")
                val tmdbId = media.getAsJsonObject("ids")?.get("tmdb")?.takeUnless { it.isJsonNull }
                    ?.asString?.toLongOrNull()?.takeIf { it > 0L } ?: error("Missing TMDB id")
                TraktRatingIdentity(type, tmdbId) to rating
            }.getOrElse { skipped++; null }
        }
        return TraktRatingPage(items, page, pageCount?.let { page < it } ?: (rows.size() >= 100), skipped)
    }

    override suspend fun setRating(
        accessToken: String,
        identity: TraktRatingIdentity,
        rating: Int,
    ) {
        require(rating in 1..10)
        val ratedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        val item = mapOf("rating" to rating, "rated_at" to ratedAt,
            "ids" to mapOf("tmdb" to identity.tmdbId))
        request("sync/ratings", accessToken, mapOf(identity.type.payloadKey to listOf(item)))
    }

    override suspend fun lists(accessToken: String): List<TraktCustomList> {
        val rows = gson.fromJson(request("users/me/lists", accessToken), JsonArray::class.java)
        return rows.mapNotNull { row ->
            runCatching {
                val item = row.asJsonObject
                val ids = item.getAsJsonObject("ids") ?: error("Missing list ids")
                val id = ids.get("trakt")?.takeUnless { it.isJsonNull }?.asLong?.takeIf { it > 0L }
                    ?: error("Missing list id")
                val name = item.get("name")?.takeUnless { it.isJsonNull }?.asString?.trim()
                    ?.takeIf { it.isNotEmpty() } ?: error("Missing list name")
                TraktCustomList(
                    id = id,
                    name = name,
                    description = item.get("description")?.takeUnless { it.isJsonNull }?.asString
                        ?.trim()?.takeIf { it.isNotEmpty() },
                    privacy = item.get("privacy")?.takeUnless { it.isJsonNull }?.asString ?: "private",
                    itemCount = item.get("item_count")?.takeUnless { it.isJsonNull }?.asInt
                        ?.coerceAtLeast(0) ?: 0,
                )
            }.getOrNull()
        }.distinctBy { it.id }
    }

    override suspend fun items(
        accessToken: String,
        listId: Long,
        type: TraktCustomListItemType,
        page: Int,
    ): TraktCustomListItemsPage {
        require(listId > 0L && page in 1..10000)
        var pageCount: Int? = null
        val response = request("users/me/lists/$listId/items/${type.apiPath}?page=$page&limit=$CUSTOM_LIST_PAGE_SIZE&extended=full",
            accessToken, observeHeaders = {
                pageCount = it["X-Pagination-Page-Count"]?.toIntOrNull()?.takeIf { count -> count >= 0 }
            })
        val rows = gson.fromJson(response, JsonArray::class.java)
        var skipped = 0
        val parsed = rows.mapNotNull { row ->
            runCatching {
                val wrapper = row.asJsonObject
                val media = wrapper.getAsJsonObject(type.responseKey) ?: error("Missing list media")
                fun positiveId(obj: JsonObject, key: String): Long? = obj.getAsJsonObject("ids")
                    ?.get(key)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asLong }.getOrNull() }
                    ?.takeIf { it > 0L }
                val (identity, title) = when (type) {
                    TraktCustomListItemType.MOVIE, TraktCustomListItemType.SHOW -> {
                        val tmdbId = positiveId(media, "tmdb") ?: error("Missing TMDB id")
                        val title = media.get("title")?.takeUnless { it.isJsonNull }?.asString
                            ?.trim()?.takeIf { it.isNotEmpty() } ?: error("Missing title")
                        TraktCustomListItemIdentity(type, tmdbId) to title
                    }
                    TraktCustomListItemType.SEASON, TraktCustomListItemType.EPISODE -> {
                        val show = wrapper.getAsJsonObject("show") ?: media.getAsJsonObject("show")
                            ?: error("Missing parent show")
                        val showTmdbId = positiveId(show, "tmdb") ?: error("Missing parent show TMDB id")
                        val showTitle = show.get("title")?.takeUnless { it.isJsonNull }?.asString
                            ?.trim()?.takeIf { it.isNotEmpty() } ?: error("Missing parent show title")
                        if (type == TraktCustomListItemType.SEASON) {
                            val season = media.get("number")?.takeUnless { it.isJsonNull }?.asInt
                                ?.takeIf { it >= 0 } ?: error("Missing season number")
                            TraktCustomListItemIdentity(type, showTmdbId, season) to "$showTitle · Season $season"
                        } else {
                            val season = media.get("season")?.takeUnless { it.isJsonNull }?.asInt
                                ?.takeIf { it >= 0 } ?: error("Missing episode season")
                            val number = media.get("number")?.takeUnless { it.isJsonNull }?.asInt
                                ?.takeIf { it > 0 } ?: error("Missing episode number")
                            val episodeTitle = media.get("title")?.takeUnless { it.isJsonNull }?.asString
                                ?.trim()?.takeIf { it.isNotEmpty() } ?: "Episode $number"
                            TraktCustomListItemIdentity(type, showTmdbId, season, number) to
                                "$showTitle · S${season}E${number} · $episodeTitle"
                        }
                    }
                }
                TraktCustomListItem(
                    identity = identity,
                    title = title,
                    year = media.get("year")?.takeUnless { it.isJsonNull }?.asInt?.takeIf { it in 1800..3000 },
                    rank = wrapper.get("rank")?.takeUnless { it.isJsonNull }?.asInt?.takeIf { it > 0 },
                    listItemId = wrapper.get("id")?.takeUnless { it.isJsonNull }
                        ?.let { runCatching { it.asLong }.getOrNull() }?.takeIf { it > 0L },
                )
            }.getOrElse { skipped++; null }
        }
        return TraktCustomListItemsPage(
            items = parsed,
            page = page,
            hasMore = pageCount?.let { page < it } ?: (rows.size() >= CUSTOM_LIST_PAGE_SIZE),
            skippedItems = skipped,
        )
    }

    override suspend fun createList(
        accessToken: String,
        name: String,
        description: String?,
    ): TraktCustomList {
        require(name.isNotBlank())
        val payload = buildMap<String, Any> {
            put("name", name.trim())
            put("privacy", "private")
            description?.trim()?.takeIf { it.isNotEmpty() }?.let { put("description", it) }
        }
        val result = gson.fromJson(request("users/me/lists", accessToken, payload), JsonObject::class.java)
        val ids = result.getAsJsonObject("ids") ?: error("Missing created list ids")
        val id = ids.get("trakt")?.takeUnless { it.isJsonNull }?.asLong?.takeIf { it > 0L }
            ?: error("Missing created list id")
        return TraktCustomList(
            id = id,
            name = result.get("name")?.takeUnless { it.isJsonNull }?.asString?.trim()
                ?.takeIf { it.isNotEmpty() } ?: name.trim(),
            description = result.get("description")?.takeUnless { it.isJsonNull }?.asString
                ?.trim()?.takeIf { it.isNotEmpty() } ?: description?.trim()?.takeIf { it.isNotEmpty() },
            privacy = result.get("privacy")?.takeUnless { it.isJsonNull }?.asString ?: "private",
            itemCount = result.get("item_count")?.takeUnless { it.isJsonNull }?.asInt?.coerceAtLeast(0) ?: 0,
        )
    }

    override suspend fun deleteList(accessToken: String, listId: Long) {
        require(listId > 0L)
        request("users/me/lists/$listId", accessToken, httpMethod = "DELETE")
    }

    override suspend fun reorderLists(accessToken: String, listIds: List<Long>) {
        require(listIds.isNotEmpty() && listIds.all { it > 0L } && listIds.distinct().size == listIds.size)
        request("users/me/lists/reorder", accessToken, mapOf("rank" to listIds))
    }

    override suspend fun reorderItems(accessToken: String, listId: Long, listItemIds: List<Long>) {
        require(listId > 0L && listItemIds.isNotEmpty() && listItemIds.all { it > 0L } &&
            listItemIds.distinct().size == listItemIds.size)
        request("users/me/lists/$listId/items/reorder", accessToken, mapOf("rank" to listItemIds))
    }

    override suspend fun setItem(
        accessToken: String,
        listId: Long,
        identity: TraktCustomListItemIdentity,
        included: Boolean,
    ) {
        require(listId > 0L)
        val ids = when (identity.type) {
            TraktCustomListItemType.MOVIE, TraktCustomListItemType.SHOW -> mapOf("tmdb" to identity.tmdbId)
            TraktCustomListItemType.SEASON -> {
                val showTraktId = resolveShowTraktId(accessToken, identity.tmdbId)
                    ?: error("Could not match the show to Trakt")
                val seasonNumber = requireNotNull(identity.seasonNumber)
                val season = gson.fromJson(
                    request("shows/$showTraktId/seasons/$seasonNumber", accessToken), JsonObject::class.java,
                )
                require(season.get("number")?.asInt == seasonNumber) { "Trakt returned a different season" }
                val seasonTraktId = season.getAsJsonObject("ids")?.get("trakt")?.takeUnless { it.isJsonNull }
                    ?.asLong?.takeIf { it > 0L } ?: error("Trakt season id is missing")
                mapOf("trakt" to seasonTraktId)
            }
            TraktCustomListItemType.EPISODE -> {
                val episodeTraktId = resolveEpisodeTraktId(accessToken,
                    TraktMediaIdentity.EpisodeId(identity.tmdbId, requireNotNull(identity.seasonNumber),
                        requireNotNull(identity.episodeNumber))) ?: error("Could not match the episode to Trakt")
                mapOf("trakt" to episodeTraktId)
            }
        }
        val payload = mapOf(identity.type.payloadKey to listOf(mapOf("ids" to ids)))
        val path = "users/me/lists/$listId/items" + if (included) "" else "/remove"
        request(path, accessToken, payload)
    }

    override suspend fun scrobble(accessToken: String, event: TraktScrobbleEvent): Boolean {
        val media = when (val identity = event.media) {
            is TraktMediaIdentity.MovieId -> "movie" to mapOf("ids" to mapOf("tmdb" to identity.tmdbId))
            is TraktMediaIdentity.EpisodeId -> {
                val episodeId = resolveEpisodeTraktId(accessToken, identity) ?: return false
                "episode" to mapOf("ids" to mapOf("trakt" to episodeId))
            }
        }
        request("scrobble/${event.action.name.lowercase(java.util.Locale.ROOT)}", accessToken,
            mapOf(media, "progress" to event.progress))
        return true
    }

    override suspend fun upload(accessToken: String, events: List<TraktHistoryUpload>): TraktHistoryUploadResult {
        if (events.isEmpty()) return TraktHistoryUploadResult.Success(uploaded = 0)
        val movies = mutableListOf<Map<String, Any>>()
        val episodes = mutableListOf<Map<String, Any>>()
        var unmatched = 0
        for (event in events.distinctBy { it.media to (it.watchedAtMs / 60_000) }) {
            val watchedAt = Instant.ofEpochMilli(event.watchedAtMs).truncatedTo(ChronoUnit.MINUTES).toString()
            when (val media = event.media) {
                is TraktMediaIdentity.MovieId -> movies += mapOf(
                    "watched_at" to watchedAt,
                    "ids" to mapOf("tmdb" to media.tmdbId),
                )
                is TraktMediaIdentity.EpisodeId -> {
                    val episodeId = resolveEpisodeTraktId(accessToken, media)
                    if (episodeId == null) unmatched++ else episodes += mapOf(
                        "watched_at" to watchedAt,
                        "ids" to mapOf("trakt" to episodeId),
                    )
                }
            }
        }
        if (movies.isEmpty() && episodes.isEmpty()) return TraktHistoryUploadResult.Success(0, unmatched)
        val payload = buildMap<String, Any> {
            if (movies.isNotEmpty()) put("movies", movies)
            if (episodes.isNotEmpty()) put("episodes", episodes)
        }
        val response = gson.fromJson(request("sync/history", accessToken, payload), JsonObject::class.java)
        val added = response.getAsJsonObject("added")
        val updated = response.getAsJsonObject("updated")
        val notFound = response.getAsJsonObject("not_found")
        val accepted = count(added, "movies") + count(added, "episodes") +
            count(updated, "movies") + count(updated, "episodes")
        val rejected = notFound?.let { countArray(it, "movies") + countArray(it, "episodes") } ?: 0
        return TraktHistoryUploadResult.Success(accepted, unmatched + rejected)
    }

    private suspend fun resolveEpisodeTraktId(accessToken: String, identity: TraktMediaIdentity.EpisodeId): Long? {
        synchronized(episodeIds) { episodeIds[identity] }?.let { return it }
        val showId = resolveShowTraktId(accessToken, identity.showTmdbId) ?: return null
        val episode = gson.fromJson(request("shows/$showId/seasons/${identity.season}/episodes/${identity.number}", accessToken), JsonObject::class.java)
        if (episode["season"]?.asInt != identity.season || episode["number"]?.asInt != identity.number) return null
        val id = episode.getAsJsonObject("ids")?.get("trakt")?.takeUnless { it.isJsonNull }?.asLong?.takeIf { it > 0 } ?: return null
        synchronized(episodeIds) { episodeIds[identity] = id }
        return id
    }

    private suspend fun resolveShowTraktId(accessToken: String, showTmdbId: Long): Long? {
        val results = gson.fromJson(request("search/tmdb/$showTmdbId?type=show", accessToken), JsonArray::class.java)
        val matches = results.mapNotNull { element ->
            val result = element.asJsonObject
            val show = result.getAsJsonObject("show") ?: return@mapNotNull null
            val ids = show.getAsJsonObject("ids") ?: return@mapNotNull null
            if (result["type"]?.asString != "show" || ids["tmdb"]?.takeUnless { it.isJsonNull }?.asLong != showTmdbId) null
            else ids["trakt"]?.takeUnless { it.isJsonNull }?.asLong?.takeIf { it > 0 }
        }.distinct()
        return matches.singleOrNull()
    }

    private fun count(parent: JsonObject?, key: String): Int =
        parent?.get(key)?.takeUnless { it.isJsonNull }?.asInt?.coerceAtLeast(0) ?: 0

    private fun countArray(parent: JsonObject, key: String): Int =
        parent.getAsJsonArray(key)?.size() ?: 0

    private companion object {
        const val CUSTOM_LIST_PAGE_SIZE = 250
        const val DISCOVERY_PAGE_SIZE = 100
        const val SYNC_PAGE_SIZE = 100
        const val MAX_SYNC_PAGES = 50
    }

    override suspend fun exchange(code: String, verifier: String): TraktTokens = tokens(mapOf(
        "code" to code, "code_verifier" to verifier, "grant_type" to "authorization_code",
        "client_id" to config.clientId, "redirect_uri" to config.redirectUri,
    ))

    override suspend fun refresh(refreshToken: String): TraktTokens = tokens(mapOf(
        "refresh_token" to refreshToken, "grant_type" to "refresh_token",
        "client_id" to config.clientId, "redirect_uri" to config.redirectUri,
    ))

    private suspend fun tokens(payload: Map<String, String>): TraktTokens {
        val result = gson.fromJson(request("oauth/token", payload = payload), TraktTokens::class.java)
        require(!result.accessToken.isNullOrBlank() && !result.refreshToken.isNullOrBlank() &&
            result.expiresIn > 0 && result.createdAt > 0) { "Invalid Trakt token response" }
        return result
    }

    override suspend fun username(accessToken: String): String =
        gson.fromJson(request("users/settings", accessToken), JsonObject::class.java)
            .getAsJsonObject("user").get("username").asString

    override suspend fun profile(accessToken: String): TraktUserProfile {
        val user = gson.fromJson(request("users/settings", accessToken), JsonObject::class.java)
            .getAsJsonObject("user") ?: error("Missing Trakt user")
        fun string(name: String): String? = user.get(name)?.takeUnless { it.isJsonNull }
            ?.asString?.trim()?.takeIf { it.isNotEmpty() }
        return TraktUserProfile(
            username = string("username") ?: error("Missing Trakt username"),
            name = string("name"),
            location = string("location"),
            about = string("about"),
            gender = string("gender"),
            age = user.get("age")?.takeUnless { it.isJsonNull }?.let { runCatching { it.asInt }.getOrNull() }
                ?.takeIf { it in 1..120 },
            joinedAt = string("joined_at"),
            vip = user.get("vip")?.takeUnless { it.isJsonNull }?.let { runCatching { it.asBoolean }.getOrNull() } ?: false,
        )
    }

    private suspend fun request(path: String, token: String? = null, payload: Map<String, Any>? = null,
        observeHeaders: ((okhttp3.Headers) -> Unit)? = null,
        httpMethod: String = if (payload == null) "GET" else "POST"): String =
        withContext(Dispatchers.IO) {
          requestMutex.withLock {
            val now = System.currentTimeMillis()
            if (now < nextRequestAt) throw TraktApiException(429, retryAfterSeconds = (nextRequestAt - now + 999) / 1000)
            require(httpMethod in setOf("GET", "POST", "DELETE"))
            if (httpMethod != "GET") {
                delay((lastWriteAt + 1000 - now).coerceAtLeast(0))
                lastWriteAt = System.currentTimeMillis()
            }
            val builder = Request.Builder().url(baseUrl.resolve(path)!!)
                .header("trakt-api-key", config.clientId).header("trakt-api-version", "2")
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", userAgent)
            token?.let { builder.header("Authorization", "Bearer $it") }
            when (httpMethod) {
                "GET" -> Unit // Request.Builder defaults to GET.
                "POST" -> builder.post(gson.toJson(payload.orEmpty()).toRequestBody("application/json".toMediaType()))
                "DELETE" -> builder.delete()
            }
            client.newCall(builder.build()).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // Only retain the OAuth error identifier. Bodies can contain sensitive data.
                    val error = runCatching { gson.fromJson(body, JsonObject::class.java)?.get("error")?.asString }.getOrNull()
                    val retry = retryAfter(response.header("Retry-After"))
                    if (response.code == 429) {
                        rateLimitFailures = (rateLimitFailures + 1).coerceAtMost(6)
                        val waitSeconds = retry ?: ((1L shl rateLimitFailures) + kotlin.random.Random.nextLong(1, 4))
                        nextRequestAt = System.currentTimeMillis() + waitSeconds.coerceAtMost(31536000) * 1000
                    }
                    throw TraktApiException(response.code, error, retry)
                }
                rateLimitFailures = 0
                observeHeaders?.invoke(response.headers)
                body
            }
          }
        }

    private fun retryAfter(value: String?): Long? = value?.toLongOrNull()?.coerceAtLeast(0)
        ?: runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toEpochSecond() -
                System.currentTimeMillis() / 1000).coerceAtLeast(0)
        }.getOrNull()

}
