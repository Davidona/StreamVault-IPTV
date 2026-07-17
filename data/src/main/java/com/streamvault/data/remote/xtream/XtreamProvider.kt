package com.streamvault.data.remote.xtream

import android.util.Log
import com.streamvault.data.remote.http.HttpRequestProfile
import com.streamvault.data.remote.dto.*
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.domain.model.*
import com.streamvault.domain.provider.IptvProvider
import com.streamvault.domain.util.ChannelNormalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.util.Base64
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Xtream Codes provider implementation.
 * Converts Xtream API responses to domain models.
 */
class XtreamProvider(
    override val providerId: Long,
    private val api: XtreamApiService,
    private val serverUrl: String,
    private val username: String,
    private val password: String,
    private val allowedOutputFormats: List<String> = emptyList(),
    private val useTextClassification: Boolean = true,
    private val enableBase64TextCompatibility: Boolean = false,
    private val requestProfile: HttpRequestProfile = HttpRequestProfile(ownerTag = "provider:$providerId/xtream")
) : IptvProvider {
    companion object {
        private const val TAG = "XtreamProvider"
        private const val STREAM_SUMMARY_BATCH_SIZE = 500

        /**
         * Offset-aware formatters for Xtream EPG textual timestamps.
         * Only [OffsetDateTime] is attempted with these; we never fall back to
         * [LocalDateTime] on these formatters, which would silently drop the
         * timezone and produce a time that is wrong by several hours.
         */
        private val xtreamEpgOffsetFormats: List<DateTimeFormatter> = listOf(
            // ISO 8601 with colon offset: "2025-01-01T12:00:00+03:00"
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            // Space-separated with colon offset: "2025-01-01 12:00:00+03:00"
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("yyyy-MM-dd HH:mm:ssXXX")
                .toFormatter(Locale.US),
            // Space-separated with numeric offset: "2025-01-01 12:00:00+0300"
            DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern("yyyy-MM-dd HH:mm:ssxx")
                .toFormatter(Locale.US)
        )

        /**
         * Local (no timezone) formatters for Xtream EPG textual timestamps.
         * Values parsed with these are assumed to be UTC, which is the
         * convention used by Xtream Codes providers.
         */
        private val xtreamEpgLocalFormats: List<DateTimeFormatter> = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US),
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        )
    }

    private enum class XtreamTextDecodeMode {
        RAW,
        EPG,
        METADATA
    }

    private data class ResolvedXtreamCategory(
        val id: Long?,
        val name: String?
    )

    private var serverInfo: XtreamServerInfo? = null
    private var liveOutputFormats: List<String> = normalizeAllowedOutputFormats(allowedOutputFormats)
    private val adultCategoryCache = mutableMapOf<ContentType, Set<Long>>()
    private val adultCategoryCacheMutex = Mutex()
    override suspend fun authenticate(): Result<Provider> = try {
        val response = api.authenticate(
            XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password),
            requestProfile
        )
        serverInfo = response.serverInfo
        liveOutputFormats = normalizeAllowedOutputFormats(response.userInfo.allowedOutputFormats)
            .ifEmpty { liveOutputFormats }

        if (response.userInfo.auth != 1) {
            Result.error("Authentication failed: ${response.userInfo.message}")
        } else {
            // Parse expiration date
            val expDateStr = response.userInfo.expDate
            val expDate = parseXtreamExpirationDate(expDateStr)

            Result.success(
                Provider(
                    id = providerId,
                    name = "$username@${serverUrl.substringAfter("://").substringBefore("/")}",
                    type = ProviderType.XTREAM_CODES,
                    serverUrl = serverUrl,
                    username = username,
                    password = password,
                    maxConnections = parseXtreamMaxConnections(response.userInfo.maxConnections),
                    expirationDate = expDate,
                    apiVersion = response.serverInfo.apiVersion?.takeIf { it.isNotBlank() }
                        ?: response.serverInfo.version?.takeIf { it.isNotBlank() },
                    allowedOutputFormats = liveOutputFormats,
                    status = when (response.userInfo.status) {
                        "Active" -> ProviderStatus.ACTIVE
                        "Expired" -> ProviderStatus.EXPIRED
                        "Disabled" -> ProviderStatus.DISABLED
                        else -> {
                            Log.w(TAG, "Unknown account status: ${response.userInfo.status}")
                            ProviderStatus.UNKNOWN
                        }
                    }
                )
            )
        }
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Authentication failed", e), e)
    }

    // ── Live TV ────────────────────────────────────────────────────

    override suspend fun getLiveCategories(): Result<List<Category>> = try {
        val categories = api.getLiveCategories(
            XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password, action = "get_live_categories"),
            requestProfile
        )
        cacheAdultCategoryIds(ContentType.LIVE, categories)
        Result.success(categories.map { it.toDomain(ContentType.LIVE) })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load live categories", e), e)
    }

    override suspend fun getLiveStreams(categoryId: Long?): Result<List<Channel>> = try {
        val adultCategoryIds = loadAdultCategoryIds(ContentType.LIVE)
        val streams = api.getLiveStreams(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_live_streams",
                extraQueryParams = mapOf("category_id" to categoryId?.toString())
            ),
            requestProfile
        )
        Result.success(
            streams.mapNotNull { stream ->
                runCatching { stream.toChannel(adultCategoryIds, includePlaybackVariants = true) }
                    .onFailure {
                        Log.w(
                            TAG,
                            "Skipping malformed live item ${stream.streamId}: " +
                                XtreamUrlFactory.sanitizeLogMessage(it.message ?: "mapping failed")
                        )
                    }
                    .getOrNull()
            }
        )
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load live streams", e), e)
    }

    // ── VOD ────────────────────────────────────────────────────────

    override suspend fun getVodCategories(): Result<List<Category>> = try {
        val categories = api.getVodCategories(
            XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password, action = "get_vod_categories"),
            requestProfile
        )
        cacheAdultCategoryIds(ContentType.MOVIE, categories)
        Result.success(categories.map { it.toDomain(ContentType.MOVIE) })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load VOD categories", e), e)
    }

    override suspend fun getVodStreams(categoryId: Long?): Result<List<Movie>> = try {
        val adultCategoryIds = loadAdultCategoryIds(ContentType.MOVIE)
        val streams = api.getVodStreams(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_vod_streams",
                extraQueryParams = mapOf("category_id" to categoryId?.toString())
            ),
            requestProfile
        )
        Result.success(
            streams.mapNotNull { stream ->
                runCatching { stream.toMovie(adultCategoryIds) }
                    .onFailure {
                        Log.w(
                            TAG,
                            "Skipping malformed VOD item ${stream.streamId}: " +
                                XtreamUrlFactory.sanitizeLogMessage(it.message ?: "mapping failed")
                        )
                    }
                    .getOrNull()
            }
        )
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load VOD", e), e)
    }

    suspend fun streamVodSummaries(
        categoryId: Long? = null,
        batchSize: Int = STREAM_SUMMARY_BATCH_SIZE,
        adultCategoryIds: Set<Long>? = null,
        onBatch: suspend (List<Movie>) -> Unit
    ): Result<Int> = try {
        val resolvedAdultCategoryIds = adultCategoryIds ?: loadAdultCategoryIds(ContentType.MOVIE)
        val buffer = mutableListOf<Movie>()
        var acceptedCount = 0
        api.streamVodStreams(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_vod_streams",
                extraQueryParams = mapOf("category_id" to categoryId?.toString())
            ),
            requestProfile
        ) { stream ->
            mapVodStream(stream, resolvedAdultCategoryIds)?.let { movie ->
                buffer += movie
                acceptedCount++
                if (buffer.size >= batchSize) {
                    onBatch(buffer.toList())
                    buffer.clear()
                }
            }
        }
        if (buffer.isNotEmpty()) {
            onBatch(buffer.toList())
        }
        Result.success(acceptedCount)
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to stream VOD index", e), e)
    }

    override suspend fun getVodInfo(vodId: Long): Result<Movie> = try {
        val response = api.getVodInfo(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_vod_info",
                extraQueryParams = mapOf("vod_id" to vodId.toString())
            ),
            requestProfile
        )
        val movieData = response.movieData
        val info = response.info

        if (movieData == null) {
            Result.error("Movie not found")
        } else if (movieData.streamId <= 0) {
            Result.error("Movie stream is invalid")
        } else {
            val adultCategoryIds = loadAdultCategoryIds(ContentType.MOVIE)
            val category = resolveXtreamCategory(ContentType.MOVIE, movieData.categoryId, null)
            val normalizedContainerExtension = normalizeContainerExtension(movieData.containerExtension)
            val sanitizedDirectSource = sanitizeAssetValue(movieData.directSource)
            Result.success(
                Movie(
                    id = movieData.streamId,
                    name = decodeXtreamNullableText(movieData.name, XtreamTextDecodeMode.RAW)?.ifBlank { null } ?: "Movie ${movieData.streamId}",
                    posterUrl = sanitizeAssetValue(info?.movieImage),
                    backdropUrl = firstUsableAsset(info?.backdropPath),
                    categoryId = category.id,
                    categoryName = category.name,
                    containerExtension = normalizedContainerExtension,
                    plot = decodeXtreamNullableText(info?.plot, XtreamTextDecodeMode.METADATA),
                    cast = decodeXtreamNullableText(info?.cast, XtreamTextDecodeMode.METADATA),
                    director = decodeXtreamNullableText(info?.director, XtreamTextDecodeMode.METADATA),
                    genre = decodeXtreamNullableText(info?.genre, XtreamTextDecodeMode.METADATA),
                    releaseDate = info?.releaseDate,
                    duration = info?.duration,
                    durationSeconds = info?.durationSecs ?: 0,
                    rating = normalizeXtreamRatingTenPoint(info?.rating, info?.rating5based),
                    tmdbId = info?.tmdbId ?: movieData.tmdb?.trim()?.toLongOrNull(),
                    youtubeTrailer = info?.youtubeTrailer ?: movieData.youtubeTrailer ?: movieData.trailer,
                    providerId = providerId,
                    streamUrl = XtreamUrlFactory.buildInternalStreamUrl(
                        providerId = providerId,
                        kind = XtreamStreamKind.MOVIE,
                        streamId = movieData.streamId,
                        containerExtension = normalizedContainerExtension,
                        directSource = sanitizedDirectSource
                    ),
                    streamId = movieData.streamId,
                    isAdult = resolveAdultFlag(
                        explicitAdult = movieData.isAdult,
                        categoryId = category.id,
                        categoryName = category.name,
                        adultCategoryIds = adultCategoryIds
                    )
                )
            )
        }
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load movie details", e), e)
    }

    // ── Series ─────────────────────────────────────────────────────

    override suspend fun getSeriesCategories(): Result<List<Category>> = try {
        val categories = api.getSeriesCategories(
            XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password, action = "get_series_categories"),
            requestProfile
        )
        cacheAdultCategoryIds(ContentType.SERIES, categories)
        Result.success(categories.map { it.toDomain(ContentType.SERIES) })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load series categories", e), e)
    }

    override suspend fun getSeriesList(categoryId: Long?): Result<List<Series>> = try {
        val adultCategoryIds = loadAdultCategoryIds(ContentType.SERIES)
        val items = api.getSeriesList(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_series",
                extraQueryParams = mapOf("category_id" to categoryId?.toString())
            ),
            requestProfile
        )
        Result.success(
            items.mapNotNull { item ->
                runCatching { item.toDomain(adultCategoryIds) }
                    .onFailure {
                        Log.w(
                            TAG,
                            "Skipping malformed series item ${item.seriesId}: " +
                                XtreamUrlFactory.sanitizeLogMessage(it.message ?: "mapping failed")
                        )
                    }
                    .getOrNull()
            }
        )
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load series", e), e)
    }

    suspend fun streamSeriesSummaries(
        categoryId: Long? = null,
        batchSize: Int = STREAM_SUMMARY_BATCH_SIZE,
        adultCategoryIds: Set<Long>? = null,
        onBatch: suspend (List<Series>) -> Unit
    ): Result<Int> = try {
        val resolvedAdultCategoryIds = adultCategoryIds ?: loadAdultCategoryIds(ContentType.SERIES)
        val buffer = mutableListOf<Series>()
        var acceptedCount = 0
        api.streamSeriesList(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_series",
                extraQueryParams = mapOf("category_id" to categoryId?.toString())
            ),
            requestProfile
        ) { item ->
            mapSeriesItem(item, resolvedAdultCategoryIds)?.let { series ->
                buffer += series
                acceptedCount++
                if (buffer.size >= batchSize) {
                    onBatch(buffer.toList())
                    buffer.clear()
                }
            }
        }
        if (buffer.isNotEmpty()) {
            onBatch(buffer.toList())
        }
        Result.success(acceptedCount)
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to stream series index", e), e)
    }

    override suspend fun getSeriesInfo(seriesId: Long): Result<Series> = try {
        val response = requestSeriesInfoWithCompatibilityFallback(seriesId)
        val info = response.info
        val adultCategoryIds = loadAdultCategoryIds(ContentType.SERIES)
        val baseSeries = info?.toDomain(
            adultCategoryIds = adultCategoryIds,
            fallbackSeriesId = seriesId
        ) ?: buildFallbackSeriesFromDetails(response, seriesId)
            ?: return Result.error("Series details are unavailable")
        val isAdult = resolveAdultFlag(
            explicitAdult = info?.isAdult,
            categoryId = info?.categoryId?.toLongOrNull(),
            categoryName = info?.categoryName,
            adultCategoryIds = adultCategoryIds
        )
        val seasonMetadataByNumber = response.seasons.associateBy { it.seasonNumber }
        val seasons = when {
            response.episodes.isNotEmpty() -> response.episodes.map { (seasonNum, episodes) ->
                val resolvedSeasonNumber = seasonNum.toIntOrNull() ?: episodes.firstNotNullOfOrNull { episode ->
                    episode.season.takeIf { it > 0 }
                } ?: 0
                val mappedEpisodes = episodes.mapIndexedNotNull { index, ep ->
                    val episodeId = ep.id.toLongOrNull()?.takeIf { it > 0 }
                        ?: ep.episodeNum.takeIf { it > 0 }?.let { resolvedSeasonNumber * 10000L + it }
                        ?: ((index + 1).let { resolvedSeasonNumber * 10000L + it })
                    val normalizedEpisodeNum = if (ep.episodeNum > 0) ep.episodeNum else index + 1
                    val normalizedExtension = normalizeContainerExtension(ep.containerExtension)
                    Episode(
                        id = episodeId,
                        title = decodeXtreamText(
                            ep.title.ifBlank { decodeXtreamNullableText(ep.info?.name, XtreamTextDecodeMode.RAW) ?: "Episode $normalizedEpisodeNum" },
                            XtreamTextDecodeMode.RAW
                        ),
                        episodeNumber = normalizedEpisodeNum,
                        seasonNumber = ep.season.takeIf { it > 0 } ?: resolvedSeasonNumber,
                        containerExtension = normalizedExtension,
                        coverUrl = sanitizeAssetValue(ep.info?.movieImage),
                        plot = decodeXtreamNullableText(ep.info?.plot, XtreamTextDecodeMode.METADATA),
                        duration = ep.info?.duration,
                        durationSeconds = ep.info?.durationSecs ?: 0,
                        rating = ep.info?.rating?.toFloatOrNull() ?: 0f,
                        releaseDate = ep.info?.releaseDate,
                        seriesId = seriesId,
                        providerId = providerId,
                        streamUrl = XtreamUrlFactory.buildInternalStreamUrl(
                            providerId = providerId,
                            kind = XtreamStreamKind.SERIES,
                            streamId = episodeId,
                            containerExtension = normalizedExtension,
                            directSource = sanitizeAssetValue(ep.directSource)
                        ),
                        isAdult = isAdult,
                        isUserProtected = false,
                        episodeId = episodeId
                    )
                }
                val seasonMetadata = seasonMetadataByNumber[resolvedSeasonNumber]
                Season(
                    seasonNumber = resolvedSeasonNumber,
                    name = seasonMetadata?.name?.takeIf { it.isNotBlank() } ?: "Season $resolvedSeasonNumber",
                    coverUrl = seasonMetadata?.cover,
                    airDate = seasonMetadata?.airDate,
                    episodes = mappedEpisodes,
                    episodeCount = seasonMetadata?.episodeCount?.takeIf { it > 0 } ?: mappedEpisodes.size
                )
            }.sortedBy { it.seasonNumber }

            response.seasons.isNotEmpty() -> response.seasons
                .sortedBy { it.seasonNumber }
                .map { season ->
                    Season(
                        seasonNumber = season.seasonNumber,
                        name = season.name.takeIf { it.isNotBlank() } ?: "Season ${season.seasonNumber}",
                        coverUrl = season.cover,
                        airDate = season.airDate,
                        episodes = emptyList(),
                        episodeCount = season.episodeCount
                    )
                }

            else -> emptyList()
        }

        Result.success(
            baseSeries.copy(
                seasons = seasons,
                providerId = providerId,
                isAdult = isAdult
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load series details", e), e)
    }

    // ── EPG ────────────────────────────────────────────────────────

    override suspend fun getEpg(channelId: String): Result<List<Program>> = try {
        val streamId = channelId.toLongOrNull() ?: 0
        val response = api.getFullEpg(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_simple_data_table",
                extraQueryParams = mapOf("stream_id" to streamId.toString())
            ),
            requestProfile
        )
        Result.success(response.epgListings.mapNotNull { it.toDomainOrNull() })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load EPG", e), e)
    }

    override suspend fun getShortEpg(channelId: String, limit: Int): Result<List<Program>> = try {
        val streamId = channelId.toLongOrNull() ?: 0
        val response = api.getShortEpg(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_short_epg",
                extraQueryParams = mapOf(
                    "stream_id" to streamId.toString(),
                    "limit" to limit.toString()
                )
            ),
            requestProfile
        )
        Result.success(response.epgListings.mapNotNull { it.toDomainOrNull() })
    } catch (e: Exception) {
        Result.error(XtreamErrorFormatter.message("Failed to load EPG", e), e)
    }

    // ── Stream URLs ────────────────────────────────────────────────

    override suspend fun buildStreamUrl(streamId: Long, containerExtension: String?): String {
        return XtreamUrlFactory.buildPlaybackUrl(
            serverUrl = serverUrl,
            username = username,
            password = password,
            kind = XtreamStreamKind.LIVE,
            streamId = streamId,
            containerExtension = preferredLiveContainerExtension(containerExtension)
        )
    }

    private fun buildMovieStreamUrl(streamId: Long, containerExtension: String?): String {
        return XtreamUrlFactory.buildPlaybackUrl(
            serverUrl = serverUrl,
            username = username,
            password = password,
            kind = XtreamStreamKind.MOVIE,
            streamId = streamId,
            containerExtension = containerExtension
        )
    }

    private fun buildSeriesStreamUrl(streamId: Long, containerExtension: String?): String {
        return XtreamUrlFactory.buildPlaybackUrl(
            serverUrl = serverUrl,
            username = username,
            password = password,
            kind = XtreamStreamKind.SERIES,
            streamId = streamId,
            containerExtension = containerExtension
        )
    }

    override suspend fun buildCatchUpUrl(streamId: Long, start: Long, end: Long): String? {
        return buildCatchUpUrls(streamId, start, end).firstOrNull()
    }

    override suspend fun buildCatchUpUrls(streamId: Long, start: Long, end: Long): List<String> {
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.ROOT)
        dateFormat.timeZone = java.util.TimeZone.getTimeZone("UTC") // Xtream servers typically use UTC for EPG timeshifts
        val formattedStart = dateFormat.format(java.util.Date(start * 1000L))
        val durationMinutes = ((end - start) / 60).coerceAtLeast(1L)
        return XtreamUrlFactory.buildCatchUpUrls(
            serverUrl = serverUrl,
            username = username,
            password = password,
            durationMinutes = durationMinutes,
            formattedStart = formattedStart,
            streamId = streamId,
            containerExtensions = candidateCatchUpExtensions()
        )
    }

    suspend fun mapCategories(type: ContentType, categories: List<XtreamCategory>): List<Category> {
        if (type != ContentType.SERIES_EPISODE) {
            cacheAdultCategoryIds(type, categories)
        }
        return categories.map { it.toDomain(type) }
    }

    suspend fun mapVodStreamsResponse(streams: List<XtreamStream>): List<Movie> =
        mapVodStreamsSequence(streams.asSequence()).toList().also { mapped ->
            if (streams.isNotEmpty() && mapped.isEmpty()) {
                Log.w(TAG, "Xtream VOD mapping rejected all ${streams.size} raw items for provider $providerId.")
            }
        }

    suspend fun mapVodStreamsSequence(streams: Sequence<XtreamStream>): Sequence<Movie> {
        val adultCategoryIds = loadAdultCategoryIds(ContentType.MOVIE)
        return streams.mapNotNull { stream -> mapVodStream(stream, adultCategoryIds) }
    }

    suspend fun mapLiveStreamsResponse(streams: List<XtreamStream>): List<Channel> {
        val adultCategoryIds = loadAdultCategoryIds(ContentType.LIVE)
        return streams.mapNotNull { stream ->
            runCatching { stream.toChannel(adultCategoryIds, includePlaybackVariants = false) }
                .onFailure {
                    Log.w(
                        TAG,
                        "Skipping malformed live item ${stream.streamId}: " +
                            XtreamUrlFactory.sanitizeLogMessage(it.message ?: "mapping failed")
                    )
                }
                .getOrNull()
        }
    }

    suspend fun mapLiveStreamsSequence(streams: Sequence<XtreamStream>): Sequence<Channel> {
        val adultCategoryIds = loadAdultCategoryIds(ContentType.LIVE)
        return streams.mapNotNull { stream ->
            runCatching { stream.toChannel(adultCategoryIds, includePlaybackVariants = false) }
                .onFailure {
                    Log.w(
                        TAG,
                        "Skipping malformed live item ${stream.streamId}: " +
                            XtreamUrlFactory.sanitizeLogMessage(it.message ?: "mapping failed")
                    )
                }
                .getOrNull()
        }
    }

    suspend fun mapLiveStreamRowsSequence(rows: Sequence<XtreamLiveStreamRow>): Sequence<Channel> {
        val adultCategoryIds = loadAdultCategoryIds(ContentType.LIVE)
        return rows.mapNotNull { row ->
            runCatching { row.toChannel(adultCategoryIds) }
                .onFailure {
                    Log.w(
                        TAG,
                        "Skipping malformed live row ${row.streamId}: " +
                            XtreamUrlFactory.sanitizeLogMessage(it.message ?: "mapping failed")
                    )
                }
                .getOrNull()
        }
    }

    suspend fun mapSeriesListResponse(items: List<XtreamSeriesItem>): List<Series> =
        mapSeriesListSequence(items.asSequence()).toList().also { mapped ->
            if (items.isNotEmpty() && mapped.isEmpty()) {
                Log.w(TAG, "Xtream series mapping rejected all ${items.size} raw items for provider $providerId.")
            }
        }

    suspend fun mapSeriesListSequence(items: Sequence<XtreamSeriesItem>): Sequence<Series> {
        val adultCategoryIds = loadAdultCategoryIds(ContentType.SERIES)
        return items.mapNotNull { item -> mapSeriesItem(item, adultCategoryIds) }
    }

    // ── Mappers ────────────────────────────────────────────────────
    
    private suspend fun loadAdultCategoryIds(type: ContentType): Set<Long> {
        adultCategoryCacheMutex.withLock {
            adultCategoryCache[type]?.let { return it }
            val categories = runCatching {
                when (type) {
                    ContentType.LIVE -> api.getLiveCategories(
                        XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password, action = "get_live_categories"),
                        requestProfile
                    )
                    ContentType.MOVIE -> api.getVodCategories(
                        XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password, action = "get_vod_categories"),
                        requestProfile
                    )
                    ContentType.SERIES -> api.getSeriesCategories(
                        XtreamUrlFactory.buildPlayerApiUrl(serverUrl, username, password, action = "get_series_categories"),
                        requestProfile
                    )
                    ContentType.SERIES_EPISODE -> emptyList()
                }
            }.getOrElse {
                Log.w(
                    TAG,
                    "Failed to prefetch $type categories for adult tagging: " +
                        XtreamUrlFactory.sanitizeLogMessage(it.message ?: "unknown error")
                )
                emptyList()
            }
            return categories
                .filter { it.isAdult == true || (useTextClassification && AdultContentClassifier.isAdultCategoryName(it.categoryName)) }
                .mapNotNull { resolveXtreamCategory(type, it.categoryId, it.categoryName).id }
                .toSet()
                .also { adultCategoryCache[type] = it }
        }
    }

    private suspend fun cacheAdultCategoryIds(type: ContentType, categories: List<XtreamCategory>) {
        val ids = categories
            .filter { it.isAdult == true || (useTextClassification && AdultContentClassifier.isAdultCategoryName(it.categoryName)) }
            .mapNotNull { resolveXtreamCategory(type, it.categoryId, it.categoryName).id }
            .toSet()
        adultCategoryCacheMutex.withLock {
            adultCategoryCache[type] = ids
        }
    }

    private fun resolveAdultFlag(
        explicitAdult: Boolean?,
        categoryId: Long?,
        categoryName: String?,
        adultCategoryIds: Set<Long>
    ): Boolean {
        return explicitAdult == true ||
            (categoryId != null && categoryId in adultCategoryIds) ||
            (useTextClassification && AdultContentClassifier.isAdultCategoryName(categoryName))
    }

    private fun normalizeAllowedOutputFormats(formats: List<String>): List<String> {
        return formats
            .mapNotNull { format ->
                normalizeContainerExtension(format)
                    ?.takeIf(::isRecognizedLiveFormat)
            }
            .distinct()
    }

    private fun preferredLiveContainerExtension(containerExtension: String?): String? {
        normalizeContainerExtension(containerExtension)?.let { return it }
        return when {
            "m3u8" in liveOutputFormats -> "m3u8"
            "ts" in liveOutputFormats -> "ts"
            else -> null
        }
    }

    private fun candidateCatchUpExtensions(): List<String> {
        return liveOutputFormats
            .filter(::isRecognizedLiveFormat)
            .ifEmpty { listOf("ts", "m3u8") }
            .distinct()
    }

    private fun buildInternalLiveStreamUrl(
        streamId: Long,
        containerExtension: String?,
        directSource: String?
    ): String {
        return XtreamUrlFactory.buildInternalStreamUrl(
            providerId = providerId,
            kind = XtreamStreamKind.LIVE,
            streamId = streamId,
            containerExtension = containerExtension,
            directSource = directSource
        )
    }

    private fun buildLiveQualityOptions(
        streamId: Long,
        primaryContainerExtension: String?,
        directSource: String?
    ): List<ChannelQualityOption> {
        val extensions = buildList {
            primaryContainerExtension?.let(::add)
            if (directSource == null) {
                liveOutputFormats
                    .filter(::isRecognizedLiveFormat)
                    .filter { it != primaryContainerExtension }
                    .forEach(::add)
            }
        }.distinct()

        return extensions.map { extension ->
            ChannelQualityOption(
                label = liveFormatLabel(extension),
                url = buildInternalLiveStreamUrl(
                    streamId = streamId,
                    containerExtension = extension,
                    directSource = if (extension == primaryContainerExtension) directSource else null
                )
            )
        }
    }

    private fun liveFormatLabel(extension: String): String = when (extension.lowercase(Locale.ROOT)) {
        "m3u8" -> "HLS"
        "ts" -> "MPEG-TS"
        else -> extension.uppercase(Locale.ROOT)
    }

    private fun isRecognizedLiveFormat(extension: String): Boolean =
        extension == "m3u8" || extension == "ts"

    private fun normalizeContainerExtension(containerExtension: String?): String? {
        return containerExtension
            ?.trim()
            ?.removePrefix(".")
            ?.lowercase(Locale.ROOT)
            ?.takeIf { it.isNotEmpty() }
    }

    private fun XtreamCategory.toDomain(type: ContentType): Category {
        val category = resolveXtreamCategory(type, categoryId, categoryName)
        return Category(
            id = category.id ?: 0,
            name = category.name ?: "Uncategorized",
            parentId = if (parentId > 0) parentId.toLong() else null,
            type = type,
            isAdult = isAdult == true || (useTextClassification && AdultContentClassifier.isAdultCategoryName(category.name))
        )
    }

    private fun XtreamStream.toChannel(
        adultCategoryIds: Set<Long>,
        includePlaybackVariants: Boolean
    ): Channel? = buildLiveChannel(
        streamId = streamId,
        num = num,
        name = name,
        streamIcon = streamIcon,
        epgChannelId = epgChannelId,
        categoryId = primaryCategoryId(),
        categoryName = categoryName,
        tvArchive = tvArchive,
        tvArchiveDuration = tvArchiveDuration,
        containerExtension = containerExtension,
        explicitAdult = isAdult,
        directSource = directSource,
        includePlaybackVariants = includePlaybackVariants,
        adultCategoryIds = adultCategoryIds
    )

    private fun XtreamLiveStreamRow.toChannel(adultCategoryIds: Set<Long>): Channel? = buildLiveChannel(
        streamId = streamId,
        num = num,
        name = name,
        streamIcon = streamIcon,
        epgChannelId = epgChannelId,
        categoryId = primaryCategoryId(),
        categoryName = categoryName,
        tvArchive = tvArchive,
        tvArchiveDuration = tvArchiveDuration,
        containerExtension = containerExtension,
        explicitAdult = isAdult,
        directSource = null,
        includePlaybackVariants = false,
        adultCategoryIds = adultCategoryIds
    )

    private fun buildLiveChannel(
        streamId: Long,
        num: Int,
        name: String?,
        streamIcon: String?,
        epgChannelId: String?,
        categoryId: String?,
        categoryName: String?,
        tvArchive: Int,
        tvArchiveDuration: Int?,
        containerExtension: String?,
        explicitAdult: Boolean?,
        directSource: String?,
        includePlaybackVariants: Boolean,
        adultCategoryIds: Set<Long>
    ): Channel? {
        if (streamId <= 0) return null
        val category = resolveXtreamCategory(ContentType.LIVE, categoryId, categoryName)
        val primaryContainerExtension = preferredLiveContainerExtension(containerExtension)
        val resolvedName = decodeXtreamNullableText(name, XtreamTextDecodeMode.RAW)?.ifBlank { null } ?: "Channel $streamId"
        val sanitizedLogoUrl = sanitizeAssetValue(streamIcon)
        val sanitizedEpgChannelId = decodeXtreamNullableText(epgChannelId, XtreamTextDecodeMode.RAW)
        val sanitizedDirectSource = if (includePlaybackVariants) sanitizeAssetValue(directSource) else null
        val streamUrl = buildInternalLiveStreamUrl(
            streamId = streamId,
            containerExtension = primaryContainerExtension,
            directSource = sanitizedDirectSource
        )
        val qualityOptions = if (includePlaybackVariants) {
            buildLiveQualityOptions(
                streamId = streamId,
                primaryContainerExtension = primaryContainerExtension,
                directSource = sanitizedDirectSource
            )
        } else {
            emptyList()
        }
        return Channel(
            id = 0,
            name = resolvedName,
            logoUrl = sanitizedLogoUrl,
            categoryId = category.id,
            categoryName = category.name,
            epgChannelId = sanitizedEpgChannelId,
            number = num,
            catchUpSupported = tvArchive == 1,
            catchUpDays = tvArchiveDuration ?: 0,
            providerId = providerId,
            streamUrl = streamUrl,
            isAdult = resolveAdultFlag(
                explicitAdult = explicitAdult,
                categoryId = category.id,
                categoryName = category.name,
                adultCategoryIds = adultCategoryIds
            ),
            isUserProtected = false,
            logicalGroupId = ChannelNormalizer.getLogicalGroupId(resolvedName, providerId),
            qualityOptions = qualityOptions,
            alternativeStreams = if (includePlaybackVariants) {
                qualityOptions.mapNotNull { it.url }.filter { it != streamUrl }
            } else {
                emptyList()
            },
            streamId = streamId
        )
    }

    private fun XtreamStream.toMovie(adultCategoryIds: Set<Long>): Movie? {
        if (streamId <= 0) return null
        val category = resolveXtreamCategory(ContentType.MOVIE, primaryCategoryId(), categoryName)
        val resolvedName = decodeXtreamNullableText(name, XtreamTextDecodeMode.RAW)?.ifBlank { null } ?: "Movie $streamId"
        val normalizedContainerExtension = normalizeContainerExtension(containerExtension)
        val sanitizedDirectSource = sanitizeAssetValue(directSource)
        return Movie(
            id = 0,
            name = resolvedName,
            posterUrl = sanitizeAssetValue(coverBig) ?: sanitizeAssetValue(streamIcon),
            categoryId = category.id,
            categoryName = category.name,
            containerExtension = normalizedContainerExtension,
            rating = normalizeXtreamRatingTenPoint(rating, rating5based),
            tmdbId = tmdb?.trim()?.toLongOrNull(),
            youtubeTrailer = youtubeTrailer ?: trailer,
            providerId = providerId,
            streamUrl = XtreamUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = XtreamStreamKind.MOVIE,
                streamId = streamId,
                containerExtension = normalizedContainerExtension,
                directSource = sanitizedDirectSource
            ),
            isAdult = resolveAdultFlag(
                explicitAdult = isAdult,
                categoryId = category.id,
                categoryName = category.name,
                adultCategoryIds = adultCategoryIds
            ),
            isUserProtected = false,
            streamId = streamId,
            addedAt = added?.trim()?.toLongOrNull() ?: 0L,
            year = extractYearFromName(resolvedName)
        )
    }

    /**
     * Extracts a 4-digit year from a movie name as fallback for the DB year column.
     * Priority: parenthetical year at end first, then any standalone year.
     */
    private fun extractYearFromName(name: String): String? {
        val parenYear = Regex("""\((\d{4})\)\s*$""").find(name)
        return parenYear?.groupValues?.get(1)
    }

    private fun mapVodStream(
        stream: XtreamStream,
        adultCategoryIds: Set<Long>
    ): Movie? {
        return runCatching { stream.toMovie(adultCategoryIds) }
            .onFailure {
                Log.w(
                    TAG,
                    "Skipping malformed VOD item ${stream.streamId}: " +
                        XtreamUrlFactory.sanitizeLogMessage(it.message ?: "mapping failed")
                )
            }
            .getOrNull()
    }

    private fun XtreamSeriesItem.toDomain(
        adultCategoryIds: Set<Long>,
        fallbackSeriesId: Long? = null
    ): Series? {
        val resolvedSeriesId = seriesId.takeIf { it > 0 } ?: fallbackSeriesId ?: return null
        val category = resolveXtreamCategory(ContentType.SERIES, categoryId, categoryName)
        val resolvedName = decodeXtreamNullableText(name, XtreamTextDecodeMode.RAW)?.ifBlank { null } ?: "Series $resolvedSeriesId"
        return Series(
            id = 0,
            name = resolvedName,
            posterUrl = sanitizeAssetValue(coverBig) ?: sanitizeAssetValue(movieImage) ?: sanitizeAssetValue(cover),
            backdropUrl = firstUsableAsset(backdropPath),
            categoryId = category.id,
            categoryName = category.name,
            plot = decodeXtreamNullableText(plot, XtreamTextDecodeMode.METADATA)
                ?: decodeXtreamNullableText(description, XtreamTextDecodeMode.METADATA),
            cast = decodeXtreamNullableText(cast, XtreamTextDecodeMode.METADATA),
            director = decodeXtreamNullableText(director, XtreamTextDecodeMode.METADATA),
            genre = decodeXtreamNullableText(genre, XtreamTextDecodeMode.METADATA),
            releaseDate = releaseDate ?: releaseDateAlt,
            rating = normalizeXtreamRatingTenPoint(rating, rating5based),
            tmdbId = tmdb?.trim()?.toLongOrNull() ?: tmdbId?.trim()?.toLongOrNull(),
            youtubeTrailer = youtubeTrailer ?: trailer,
            episodeRunTime = episodeRunTime,
            lastModified = lastModified?.toLongOrNull() ?: 0L,
            providerId = providerId,
            isAdult = resolveAdultFlag(
                explicitAdult = isAdult,
                categoryId = category.id,
                categoryName = category.name,
                adultCategoryIds = adultCategoryIds
            ),
            isUserProtected = false,
            seriesId = resolvedSeriesId
        )
    }

    private fun mapSeriesItem(
        item: XtreamSeriesItem,
        adultCategoryIds: Set<Long>
    ): Series? {
        return runCatching { item.toDomain(adultCategoryIds) }
            .onFailure {
                Log.w(
                    TAG,
                    "Skipping malformed series item ${item.seriesId}: " +
                        XtreamUrlFactory.sanitizeLogMessage(it.message ?: "mapping failed")
                )
            }
            .getOrNull()
    }

    private suspend fun requestSeriesInfoWithCompatibilityFallback(seriesId: Long): XtreamSeriesInfoResponse {
        val primaryAttempt = runCatching { requestSeriesInfo(seriesId, "series_id") }
        val primaryResponse = primaryAttempt.getOrNull()
        if (primaryResponse.hasUsableSeriesDetailPayload()) {
            return requireNotNull(primaryResponse)
        }

        val primaryFailure = primaryAttempt.exceptionOrNull()
        val shouldTryLegacyParam = primaryFailure == null ||
            primaryFailure is XtreamRequestException ||
            primaryFailure is XtreamParsingException
        if (!shouldTryLegacyParam) {
            primaryResponse?.let { return it }
            primaryFailure?.let { throw it }
        }
        val legacyAttempt = runCatching { requestSeriesInfo(seriesId, "series") }
        val legacyResponse = legacyAttempt.getOrNull()
        if (legacyResponse.hasUsableSeriesDetailPayload()) {
            return requireNotNull(legacyResponse)
        }

        primaryResponse?.let { return it }
        primaryFailure?.let { throw it }
        legacyAttempt.exceptionOrNull()?.let { throw it }
        return legacyResponse ?: XtreamSeriesInfoResponse()
    }

    private suspend fun requestSeriesInfo(seriesId: Long, queryParamName: String): XtreamSeriesInfoResponse {
        return api.getSeriesInfo(
            XtreamUrlFactory.buildPlayerApiUrl(
                serverUrl = serverUrl,
                username = username,
                password = password,
                action = "get_series_info",
                extraQueryParams = mapOf(queryParamName to seriesId.toString())
            ),
            requestProfile
        )
    }

    private fun XtreamSeriesInfoResponse?.hasUsableSeriesDetailPayload(): Boolean =
        this?.info != null || !this?.episodes.isNullOrEmpty() || !this?.seasons.isNullOrEmpty()

    private fun buildFallbackSeriesFromDetails(
        response: XtreamSeriesInfoResponse,
        seriesId: Long
    ): Series? {
        if (response.episodes.isEmpty() && response.seasons.isEmpty()) {
            return null
        }
        val fallbackPoster = response.seasons.firstNotNullOfOrNull { season ->
            sanitizeAssetValue(season.cover)
        }
        return Series(
            id = 0,
            name = "Series $seriesId",
            posterUrl = fallbackPoster,
            providerId = providerId,
            isAdult = false,
            isUserProtected = false,
            seriesId = seriesId
        )
    }

    private fun XtreamEpgListing.toDomainOrNull(): Program? {
        // Resolve start and end independently: use the numeric timestamp when present
        // (> 0), otherwise fall back to the textual field. Using per-field resolution
        // prevents a valid numeric startTimestamp from being discarded just because
        // stopTimestamp is absent (some providers omit one but not the other).
        val resolvedStart = if (startTimestamp > 0L) startTimestamp * 1000L
                            else parseXtreamEpgTimestamp(start)
        val resolvedEnd   = if (stopTimestamp  > 0L) stopTimestamp  * 1000L
                            else parseXtreamEpgTimestamp(end)
        // Skip silently rather than emitting an epoch-zero programme that corrupts the guide.
        if (resolvedStart <= 0L || resolvedEnd <= resolvedStart) return null

        // Xtream sometimes base64-encodes title and description
        val decodedTitle = decodeXtreamText(title, XtreamTextDecodeMode.EPG)
        val decodedDescription = decodeXtreamText(description, XtreamTextDecodeMode.EPG)

        return Program(
            id = id.toLongOrNull() ?: 0,
            channelId = channelId,
            title = decodedTitle,
            description = decodedDescription,
            startTime = resolvedStart,
            endTime = resolvedEnd,
            lang = lang,
            category = null,
            hasArchive = hasArchive == 1,
            isNowPlaying = nowPlaying == 1,
            providerId = providerId
        )
    }

    /**
     * Parses an Xtream EPG time value which may be:
     * - A numeric epoch-seconds string (e.g. `"1735726800"`)
     * - An ISO-style date-time with timezone offset (e.g. `"2025-01-01T12:00:00+03:00"`)
     * - An ISO-style local date-time, assumed UTC (e.g. `"2025-01-01 12:00:00"`)
     *
     * Offset-aware formats are tried first so that a valid `+HH:mm` suffix is never
     * silently dropped. Local formats are only tried after all offset paths fail.
     *
     * Returns epoch-milliseconds, or 0 if the value cannot be parsed.
     */
    private fun parseXtreamEpgTimestamp(value: String): Long {
        if (value.isBlank()) return 0L
        val trimmed = value.trim()
        // Numeric string: epoch seconds (must be > 0 to exclude placeholder zeroes)
        trimmed.toLongOrNull()?.takeIf { it > 0L }?.let { return it * 1000L }
        // Offset-aware text timestamps: use OffsetDateTime only — never LocalDateTime
        // on these formatters, because LocalDateTime.from(OffsetDateTime) strips the
        // offset and would return a time that is wrong by the provider's UTC offset.
        xtreamEpgOffsetFormats.forEach { formatter ->
            runCatching {
                OffsetDateTime.parse(trimmed, formatter).toInstant().toEpochMilli()
            }.getOrNull()?.let { return it }
        }
        // Local text timestamps: UTC is assumed (Xtream convention)
        xtreamEpgLocalFormats.forEach { formatter ->
            runCatching {
                LocalDateTime.parse(trimmed, formatter).toInstant(ZoneOffset.UTC).toEpochMilli()
            }.getOrNull()?.let { return it }
        }
        return 0L
    }

    private fun decodeXtreamNullableText(
        value: String?,
        mode: XtreamTextDecodeMode = XtreamTextDecodeMode.RAW
    ): String? {
        return value?.let { decodeXtreamText(it, mode) }?.takeIf { it.isNotBlank() }
    }

    private fun sanitizeAssetValue(value: String?): String? {
        return value
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
    }

    private fun firstUsableAsset(values: List<String>?): String? {
        return values.orEmpty().firstNotNullOfOrNull(::sanitizeAssetValue)
    }

    private fun normalizeXtreamRatingTenPoint(
        rawTenPoint: String?,
        rawFivePoint: String?
    ): Float {
        val tenPoint = rawTenPoint?.trim()?.toFloatOrNull()?.takeIf { it in 0f..10f }
        if (tenPoint != null) {
            return tenPoint
        }
        return rawFivePoint
            ?.trim()
            ?.toFloatOrNull()
            ?.takeIf { it in 0f..5f }
            ?.times(2f)
            ?: 0f
    }

    private fun parseXtreamMaxConnections(rawValue: String?): Int {
        return rawValue
            ?.trim()
            ?.toIntOrNull()
            ?.takeIf { it > 0 }
            ?: 1
    }

    private fun decodeXtreamText(
        value: String,
        mode: XtreamTextDecodeMode = XtreamTextDecodeMode.RAW
    ): String = tryBase64Decode(value, mode).trim()

    private fun resolveXtreamCategory(
        type: ContentType,
        rawCategoryId: String?,
        rawCategoryName: String?
    ): ResolvedXtreamCategory {
        val decodedName = decodeXtreamNullableText(rawCategoryName, XtreamTextDecodeMode.RAW)
        val parsedId = rawCategoryId?.trim()?.toLongOrNull()
        if (parsedId != null) {
            return ResolvedXtreamCategory(
                id = parsedId,
                name = decodedName ?: "Category $parsedId"
            )
        }

        val fallbackSeed = decodedName ?: rawCategoryId?.trim()?.takeIf { it.isNotBlank() }
        return if (fallbackSeed != null) {
            ResolvedXtreamCategory(
                id = syntheticCategoryId(type, fallbackSeed),
                name = decodedName ?: "Category ${rawCategoryId?.trim()}"
            )
        } else {
            ResolvedXtreamCategory(id = null, name = null)
        }
    }

    private fun XtreamStream.primaryCategoryId(): String? {
        return primaryCategoryId(categoryId, categoryIds)
    }

    private fun XtreamLiveStreamRow.primaryCategoryId(): String? {
        return primaryCategoryId(categoryId, categoryIds)
    }

    private fun primaryCategoryId(categoryId: String?, categoryIds: List<String>?): String? {
        val normalizedCategoryId = categoryId.normalizedCategoryToken()
        val firstAlternateCategoryId = categoryIds.orEmpty()
            .mapNotNull { it.normalizedCategoryToken() }
            .firstOrNull { it != "0" }
            ?: categoryIds.orEmpty().firstNotNullOfOrNull { it.normalizedCategoryToken() }
        return when {
            normalizedCategoryId == null -> firstAlternateCategoryId
            normalizedCategoryId == "0" && firstAlternateCategoryId != null && firstAlternateCategoryId != "0" -> firstAlternateCategoryId
            else -> normalizedCategoryId
        }
    }

    private fun String?.normalizedCategoryToken(): String? = this
        ?.trim()
        ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    private fun syntheticCategoryId(type: ContentType, seed: String): Long {
        val normalized = "$providerId/${type.name}/${seed.trim().lowercase(Locale.ROOT)}"
        return (normalized.hashCode().toLong() and 0x7fff_ffffL).coerceAtLeast(1L)
    }

    private fun tryBase64Decode(value: String, mode: XtreamTextDecodeMode): String = try {
        val normalized = value.trim()
        if (
            (mode == XtreamTextDecodeMode.RAW && !enableBase64TextCompatibility) ||
            normalized.isBlank() ||
            normalized.length % 4 != 0 ||
            !XTREAM_BASE64_REGEX.matches(normalized)
        ) {
            value
        } else {
            val decoded = String(Base64.getDecoder().decode(normalized), Charsets.UTF_8)
            if (isPlausibleXtreamDecodedText(decoded)) decoded else value
        }
    } catch (_: Exception) {
        value
    }

    private fun isPlausibleXtreamDecodedText(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return false
        if (trimmed.any { it.isISOControl() && !it.isWhitespace() }) return false

        val readableChars = trimmed.count(::isReadableXtreamTextChar)
        val readableRatio = readableChars.toDouble() / trimmed.length.toDouble()
        val letterOrDigitCount = trimmed.count(Char::isLetterOrDigit)

        return readableRatio >= XTREAM_DECODED_TEXT_MIN_READABLE_RATIO &&
            letterOrDigitCount >= XTREAM_DECODED_TEXT_MIN_ALNUM_COUNT
    }

    private fun isReadableXtreamTextChar(char: Char): Boolean {
        return char.isLetterOrDigit() || char.isWhitespace() || char in XTREAM_READABLE_TEXT_PUNCTUATION
    }
}

internal fun parseXtreamExpirationDate(rawValue: String?): Long? {
    val value = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (value.equals("Unlimited", ignoreCase = true)) return Long.MAX_VALUE
    if (value.equals("null", ignoreCase = true) || value.equals("none", ignoreCase = true)) return null

    value.toLongOrNull()?.let { numeric ->
        return if (numeric >= 1_000_000_000_000L) numeric else numeric * 1000L
    }

    runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
    runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()?.let { return it }

    XTREAM_LOCAL_DATE_TIME_FORMATTERS.forEach { formatter ->
        runCatching {
            LocalDateTime.parse(value, formatter).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()?.let { return it }
    }

    XTREAM_LOCAL_DATE_FORMATTERS.forEach { formatter ->
        runCatching {
            LocalDate.parse(value, formatter).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()?.let { return it }
    }

    return null
}

private val XTREAM_LOCAL_DATE_TIME_FORMATTERS: List<DateTimeFormatter> = listOf(
    "yyyy-MM-dd HH:mm:ss",
    "yyyy/MM/dd HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm:ss",
    "yyyy/MM/dd'T'HH:mm:ss",
    "dd-MM-yyyy HH:mm:ss",
    "dd/MM/yyyy HH:mm:ss"
).map { pattern ->
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern(pattern)
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.SMART)
}

private val XTREAM_LOCAL_DATE_FORMATTERS: List<DateTimeFormatter> = listOf(
    DateTimeFormatter.ISO_LOCAL_DATE,
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("yyyy/MM/dd")
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.SMART),
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("dd-MM-yyyy")
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.SMART),
    DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("dd/MM/yyyy")
        .toFormatter(Locale.ROOT)
        .withResolverStyle(ResolverStyle.SMART)
)

private val XTREAM_BASE64_REGEX = Regex("^[A-Za-z0-9+/]+={0,2}$")
private const val XTREAM_DECODED_TEXT_MIN_READABLE_RATIO = 0.85
private const val XTREAM_DECODED_TEXT_MIN_ALNUM_COUNT = 3
private val XTREAM_READABLE_TEXT_PUNCTUATION = setOf('\'', '"', '-', '_', '.', '?', '!', '&', ':', '’', ',', ';', '(', ')', '[', ']', '/', '\\')
