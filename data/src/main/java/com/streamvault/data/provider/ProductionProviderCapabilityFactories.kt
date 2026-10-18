package com.streamvault.data.provider

import com.streamvault.data.mapper.toDomain
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.local.dao.MovieDao
import com.streamvault.data.local.dao.ProgramDao
import com.streamvault.data.remote.http.buildGenericProviderRequestProfile
import com.streamvault.data.remote.jellyfin.JellyfinPage
import com.streamvault.data.remote.jellyfin.buildJellyfinAuthorizationHeader
import com.streamvault.data.remote.xtream.XtreamStreamKind
import com.streamvault.data.remote.xtream.XtreamUrlFactory
import com.streamvault.data.remote.xtream.extractStreamExpirationTime
import com.streamvault.data.repository.buildM3uCatchUpUrls
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.CapabilityState
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.provider.CatchUpRequest
import com.streamvault.domain.provider.CatchUpSource
import com.streamvault.domain.provider.CapabilityResolution
import com.streamvault.domain.provider.GuideSource
import com.streamvault.domain.provider.GuideRequest
import com.streamvault.domain.provider.LiveCatalogSource
import com.streamvault.domain.provider.PlaybackResolver
import com.streamvault.domain.provider.PlaybackRequest
import com.streamvault.domain.provider.ProviderAuthenticator
import com.streamvault.domain.provider.ProviderCapabilityFactory
import com.streamvault.domain.provider.ProviderCapabilitySet
import com.streamvault.domain.provider.ProviderContentReference
import com.streamvault.domain.provider.SeriesCatalogSource
import com.streamvault.domain.provider.VodCatalogSource
import com.streamvault.domain.provider.ResolvedPlayback
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

class ProviderCapabilityTimeoutException(message: String) : RuntimeException(message)

@Singleton
class XtreamCapabilityFactory @Inject constructor(
    private val clients: TypedProviderClientFactory
) : ProviderCapabilityFactory {
    override val providerType = ProviderType.XTREAM_CODES
    override fun create(snapshot: ProviderSnapshot): ProviderCapabilitySet {
        require(snapshot.configuration.type == providerType) { "Xtream factory requires XtreamConfig" }
        val adapter = XtreamCapabilityAdapter(snapshot, clients)
        return FixedProviderCapabilitySet(
            snapshot = snapshot,
            authentication = available(adapter),
            liveCatalog = available(adapter),
            vodCatalog = available(adapter),
            seriesCatalog = available(adapter),
            guide = available(adapter),
            playback = available(adapter),
            catchUp = available(adapter)
        )
    }
}

@Singleton
class StalkerCapabilityFactory @Inject constructor(
    private val clients: TypedProviderClientFactory,
    private val playbackCache: StalkerPlaybackCapabilityCache
) : ProviderCapabilityFactory {
    override val providerType = ProviderType.STALKER_PORTAL
    override fun create(snapshot: ProviderSnapshot): ProviderCapabilitySet {
        val client = when (val result = clients.stalker(snapshot)) {
            is CapabilityResolution.Available -> result.capability
            is CapabilityResolution.ConfigurationError -> throw IllegalArgumentException(result.reason)
            is CapabilityResolution.Restricted -> throw IllegalArgumentException(result.reason)
            is CapabilityResolution.Unsupported -> throw IllegalArgumentException(result.reason)
        }
        fun <T> learned(key: String, capability: T): CapabilityResolution<T> =
            when (snapshot.stalkerLearning?.capabilities?.get(key)?.value) {
                CapabilityState.UNSUPPORTED -> CapabilityResolution.Unsupported("Portal reports $key as unsupported")
                CapabilityState.RESTRICTED -> CapabilityResolution.Restricted("Portal restricts $key")
                else -> available(capability)
            }
        return FixedProviderCapabilitySet(
            snapshot = snapshot,
            authentication = available(client),
            liveCatalog = learned("live", client),
            vodCatalog = learned("vod", client),
            seriesCatalog = learned("series", client),
            guide = learned("epg", client),
            playback = when (val playback = playbackCache.resolve(snapshot)) {
                is CapabilityResolution.Available -> learned("playback", playback.capability)
                is CapabilityResolution.ConfigurationError -> playback
                is CapabilityResolution.Restricted -> playback
                is CapabilityResolution.Unsupported -> playback
            },
            catchUp = learned("catch_up", client)
        )
    }
}

@Singleton
class M3uCapabilityFactory @Inject constructor(
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val programDao: ProgramDao
) : ProviderCapabilityFactory {
    override val providerType = ProviderType.M3U
    override fun create(snapshot: ProviderSnapshot): ProviderCapabilitySet {
        val config = snapshot.configuration as? M3uConfig
            ?: throw IllegalArgumentException("M3U factory requires M3uConfig")
        val adapter = M3uCapabilityAdapter(
            providerId = snapshot.provider.id,
            config = config,
            categoryDao = categoryDao,
            channelDao = channelDao,
            movieDao = movieDao,
            programDao = programDao
        )
        val guide = if (config.epgUrl.isBlank()) {
            CapabilityResolution.Unsupported("M3U guide requires a configured XMLTV source")
        } else {
            available(adapter)
        }
        return FixedProviderCapabilitySet(
            snapshot = snapshot,
            authentication = CapabilityResolution.Unsupported("M3U does not authenticate"),
            liveCatalog = available(adapter),
            vodCatalog = available(adapter),
            seriesCatalog = CapabilityResolution.Unsupported("M3U has no native series catalog"),
            guide = guide,
            playback = available(adapter),
            catchUp = available(adapter)
        )
    }
}

@Singleton
class JellyfinCapabilityFactory @Inject constructor(
    private val clients: TypedProviderClientFactory
) : ProviderCapabilityFactory {
    override val providerType = ProviderType.JELLYFIN
    override fun create(snapshot: ProviderSnapshot): ProviderCapabilitySet {
        val context = when (val result = clients.jellyfin(snapshot)) {
            is CapabilityResolution.Available -> result.capability
            is CapabilityResolution.ConfigurationError -> throw IllegalArgumentException(result.reason)
            is CapabilityResolution.Restricted -> throw IllegalArgumentException(result.reason)
            is CapabilityResolution.Unsupported -> throw IllegalArgumentException(result.reason)
        }
        val adapter = JellyfinCapabilityAdapter(context)
        return FixedProviderCapabilitySet(
            snapshot = snapshot,
            authentication = available(adapter),
            liveCatalog = CapabilityResolution.Unsupported("Jellyfin has no live catalog"),
            vodCatalog = available(adapter),
            seriesCatalog = available(adapter),
            guide = CapabilityResolution.Unsupported("Jellyfin has no IPTV guide"),
            playback = available(adapter),
            catchUp = CapabilityResolution.Unsupported("Jellyfin has no IPTV catch-up")
        )
    }
}

private fun <T> available(value: T): CapabilityResolution<T> = CapabilityResolution.Available(value)

private class FixedProviderCapabilitySet(
    override val snapshot: ProviderSnapshot,
    private val authentication: CapabilityResolution<ProviderAuthenticator>,
    private val liveCatalog: CapabilityResolution<LiveCatalogSource>,
    private val vodCatalog: CapabilityResolution<VodCatalogSource>,
    private val seriesCatalog: CapabilityResolution<SeriesCatalogSource>,
    private val guide: CapabilityResolution<GuideSource>,
    private val playback: CapabilityResolution<PlaybackResolver>,
    private val catchUp: CapabilityResolution<CatchUpSource>
) : ProviderCapabilitySet {
    override fun authentication() = authentication
    override fun liveCatalog() = liveCatalog
    override fun vodCatalog() = vodCatalog
    override fun seriesCatalog() = seriesCatalog
    override fun guide() = guide
    override fun playback() = playback
    override fun catchUp() = catchUp
}

private class XtreamCapabilityAdapter(
    private val snapshot: ProviderSnapshot,
    private val clients: TypedProviderClientFactory
) : ProviderAuthenticator, LiveCatalogSource, VodCatalogSource, SeriesCatalogSource, GuideSource,
    PlaybackResolver, CatchUpSource {
    private suspend fun <T> execute(block: suspend (com.streamvault.data.remote.xtream.XtreamProvider) -> Result<T>): Result<T> =
        when (val resolved = clients.xtream(snapshot)) {
            is CapabilityResolution.Available -> block(resolved.capability)
            is CapabilityResolution.ConfigurationError -> Result.error(resolved.reason)
            is CapabilityResolution.Restricted -> Result.error(resolved.reason)
            is CapabilityResolution.Unsupported -> Result.error(resolved.reason)
        }

    override suspend fun authenticate(): Result<Provider> = execute { it.authenticate() }
    override suspend fun getLiveCategories(): Result<List<Category>> = execute { it.getLiveCategories() }
    override suspend fun getLiveStreams(categoryId: Long?): Result<List<Channel>> = execute { it.getLiveStreams(categoryId) }
    override suspend fun getVodCategories(): Result<List<Category>> = execute { it.getVodCategories() }
    override suspend fun getVodStreams(categoryId: Long?): Result<List<Movie>> = execute { it.getVodStreams(categoryId) }
    override suspend fun getVodInfo(vodId: Long): Result<Movie> = execute { it.getVodInfo(vodId) }
    override suspend fun getSeriesCategories(): Result<List<Category>> = execute { it.getSeriesCategories() }
    override suspend fun getSeriesList(categoryId: Long?): Result<List<Series>> = execute { it.getSeriesList(categoryId) }
    override suspend fun getSeriesInfo(seriesId: Long): Result<Series> = execute { it.getSeriesInfo(seriesId) }
    override suspend fun hydrateSeries(reference: ProviderContentReference, current: Series): Result<Series> =
        withTimeoutOrNull(8_000L) { execute { it.hydrateSeries(reference, current) } }
            ?: Result.error(
                "Xtream series detail hydration timed out",
                ProviderCapabilityTimeoutException("Xtream series detail hydration timed out")
            )
    override suspend fun getEpg(channelId: String): Result<List<Program>> = execute { it.getEpg(channelId) }
    override suspend fun getShortEpg(channelId: String, limit: Int): Result<List<Program>> = execute { it.getShortEpg(channelId, limit) }
    override suspend fun getEpg(request: GuideRequest): Result<List<Program>> =
        execute { it.getEpg(request.streamId.toString()) }
    override suspend fun getShortEpg(request: GuideRequest): Result<List<Program>> =
        execute { it.getShortEpg(request.streamId.toString(), request.limit) }
    override suspend fun buildStreamUrl(streamId: Long, containerExtension: String?): String =
        when (val resolved = clients.xtream(snapshot)) {
            is CapabilityResolution.Available -> resolved.capability.buildStreamUrl(streamId, containerExtension)
            else -> ""
        }
    override suspend fun resolve(request: PlaybackRequest): Result<ResolvedPlayback> {
        val config = snapshot.configuration as XtreamConfig
        val token = XtreamUrlFactory.parseInternalStreamUrl(request.sourceUrl)
        val directToken = token ?: XtreamUrlFactory.parseCredentialedStreamUrl(
            request.sourceUrl,
            snapshot.provider.id
        )
        val kind = directToken?.kind ?: XtreamUrlFactory.kindForContentType(request.contentType)
            ?: return Result.error("Xtream content type is not playable")
        val streamId = directToken?.streamId ?: request.content.numericRemoteId()
            ?: return Result.error("Xtream playback stream ID is missing")
        val resolvedExtension = clients.resolveLiveContainerExtension(
            snapshot,
            kind,
            directToken?.containerExtension ?: request.containerExtension
        )
        val directSource = token?.directSource
            ?.takeIf { kind != XtreamStreamKind.LIVE }
            ?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
        val stableUrl = XtreamUrlFactory.buildPlaybackUrl(
            serverUrl = config.serverUrl,
            username = config.username,
            password = config.password,
            kind = kind,
            streamId = streamId,
            containerExtension = resolvedExtension
        )
        val sourceIsInternal = token != null
        val resolvedUrl = when {
            kind == XtreamStreamKind.LIVE && XtreamUrlFactory.isGeneratedCatchUpUrl(
                request.sourceUrl, config.serverUrl, config.username, config.password, streamId
            ) -> request.sourceUrl
            request.preferStableUrl -> stableUrl
            directSource != null -> directSource
            sourceIsInternal -> stableUrl
            kind == XtreamStreamKind.LIVE -> stableUrl
            request.sourceUrl.isNotBlank() -> request.sourceUrl
            else -> stableUrl
        }
        val profile = buildGenericProviderRequestProfile(
            ownerTag = "provider:${snapshot.provider.id}/playback",
            httpUserAgent = config.httpUserAgent,
            httpHeaders = config.httpHeaders
        )
        return Result.success(
            ResolvedPlayback(
                url = resolvedUrl,
                expirationTime = extractStreamExpirationTime(resolvedUrl),
                containerExtension = resolvedExtension,
                headers = profile.headers,
                userAgent = profile.userAgent
            )
        )
    }
    override suspend fun buildCatchUpUrl(streamId: Long, start: Long, end: Long): String? =
        buildCatchUpUrls(streamId, start, end).firstOrNull()
    override suspend fun buildCatchUpUrls(streamId: Long, start: Long, end: Long): List<String> =
        when (val resolved = clients.xtream(snapshot)) {
            is CapabilityResolution.Available -> resolved.capability.buildCatchUpUrls(streamId, start, end)
            else -> emptyList()
        }
}

private class M3uCapabilityAdapter(
    private val providerId: Long,
    private val config: M3uConfig,
    private val categoryDao: CategoryDao,
    private val channelDao: ChannelDao,
    private val movieDao: MovieDao,
    private val programDao: ProgramDao
) : LiveCatalogSource, VodCatalogSource, GuideSource, PlaybackResolver, CatchUpSource {
    override suspend fun getLiveCategories(): Result<List<Category>> = Result.success(
        categoryDao.getByProviderAndTypeSync(providerId, ContentType.LIVE.name).map { it.toDomain() }
    )
    override suspend fun getLiveStreams(categoryId: Long?): Result<List<Channel>> = Result.success(
        channelDao.getByProviderSync(providerId)
            .asSequence()
            .filter { categoryId == null || it.categoryId == categoryId }
            .map { it.toDomain() }
            .toList()
    )
    override suspend fun getVodCategories(): Result<List<Category>> = Result.success(
        categoryDao.getByProviderAndTypeSync(providerId, ContentType.MOVIE.name).map { it.toDomain() }
    )
    override suspend fun getVodStreams(categoryId: Long?): Result<List<Movie>> = Result.success(
        movieDao.getByProviderSync(providerId)
            .asSequence()
            .filter { categoryId == null || it.categoryId == categoryId }
            .map { it.toDomain() }
            .toList()
    )
    override suspend fun getVodInfo(vodId: Long): Result<Movie> =
        movieDao.getByStreamId(providerId, vodId)?.toDomain()?.let(Result.Companion::success)
            ?: Result.error("M3U movie was not found in the imported catalog")
    override suspend fun hydrateVod(reference: ProviderContentReference, current: Movie): Result<Movie> = Result.success(current)
    override suspend fun getEpg(channelId: String): Result<List<Program>> {
        val now = System.currentTimeMillis()
        return Result.success(
            programDao.getForChannel(
                providerId,
                channelId,
                now - GUIDE_LOOKBACK_MILLIS,
                now + GUIDE_LOOKAHEAD_MILLIS
            ).first().map { it.toDomain() }
        )
    }
    override suspend fun getShortEpg(channelId: String, limit: Int): Result<List<Program>> =
        getEpg(channelId).map { it.take(limit.coerceAtLeast(0)) }
    override suspend fun buildStreamUrl(streamId: Long, containerExtension: String?): String = ""
    override suspend fun resolve(request: PlaybackRequest): Result<ResolvedPlayback> {
        if (request.sourceUrl.isBlank()) return Result.error("M3U playback URL is missing")
        val profile = buildGenericProviderRequestProfile(
            ownerTag = "provider:${request.content.providerId}/playback",
            httpUserAgent = config.httpUserAgent,
            httpHeaders = config.httpHeaders
        )
        return Result.success(
            ResolvedPlayback(
                url = request.sourceUrl,
                expirationTime = extractStreamExpirationTime(request.sourceUrl),
                containerExtension = request.containerExtension,
                headers = profile.headers,
                userAgent = profile.userAgent
            )
        )
    }
    override suspend fun buildCatchUpUrl(streamId: Long, start: Long, end: Long): String? = null
    override suspend fun buildCatchUpUrls(request: CatchUpRequest): List<String> =
        request.sourceCatchUpTemplate?.let { buildM3uCatchUpUrls(it, request.start, request.end) }.orEmpty()

    private companion object {
        const val GUIDE_LOOKBACK_MILLIS = 12L * 60L * 60L * 1000L
        const val GUIDE_LOOKAHEAD_MILLIS = 7L * 24L * 60L * 60L * 1000L
    }
}

private class JellyfinCapabilityAdapter(
    private val context: JellyfinClientContext
) : ProviderAuthenticator, VodCatalogSource, SeriesCatalogSource, PlaybackResolver {
    override suspend fun authenticate(): Result<Provider> = Result.success(context.provider)
    override suspend fun getVodCategories(): Result<List<Category>> = Result.success(
        listOf(Category(id = 1L, name = "Movies", type = ContentType.MOVIE))
    )
    override suspend fun getVodStreams(categoryId: Long?): Result<List<Movie>> =
        collectPages { context.client.fetchMoviesPage(context.provider, it) }.map { rows -> rows.map { it.toDomain() } }
    override suspend fun getVodInfo(vodId: Long): Result<Movie> =
        getVodStreams().map { movies -> movies.firstOrNull { it.id == vodId } }
            .let { result ->
                when (result) {
                    is Result.Success -> result.data?.let(Result.Companion::success)
                        ?: Result.error("Jellyfin movie not found")
                    is Result.Error -> result
                    is Result.Loading -> Result.Loading
                }
            }
    override suspend fun hydrateVod(reference: ProviderContentReference, current: Movie): Result<Movie> = Result.success(current)

    override suspend fun getSeriesCategories(): Result<List<Category>> = Result.success(
        listOf(Category(id = 2L, name = "Series", type = ContentType.SERIES))
    )
    override suspend fun getSeriesList(categoryId: Long?): Result<List<Series>> =
        collectPages { context.client.fetchSeriesPage(context.provider, it) }.map { rows -> rows.map { it.toDomain() } }
    override suspend fun getSeriesInfo(seriesId: Long): Result<Series> = Result.error("Jellyfin series requires its opaque remote ID")
    override suspend fun hydrateSeries(reference: ProviderContentReference, current: Series): Result<Series> {
        val remoteId = reference.remoteId?.takeIf(String::isNotBlank)
            ?: return Result.error("Jellyfin series remote ID is missing")
        val localId = reference.localId ?: current.id
        return context.client.fetchEpisodes(context.provider, remoteId, localId).map { rows ->
            val episodes = rows.map { it.toDomain() }
            current.copy(
                seasons = episodes.groupBy { it.seasonNumber }
                    .toSortedMap()
                    .map { (number, seasonEpisodes) ->
                        Season(
                            seasonNumber = number,
                            episodes = seasonEpisodes.sortedBy { it.episodeNumber },
                            episodeCount = seasonEpisodes.size
                        )
                    }
            )
        }
    }

    override suspend fun buildStreamUrl(streamId: Long, containerExtension: String?): String = ""
    override suspend fun resolve(request: PlaybackRequest): Result<ResolvedPlayback> {
        if (request.sourceUrl.isBlank()) return Result.error("Jellyfin playback URL is missing")
        val config = context.provider
        return Result.success(
            ResolvedPlayback(
                url = request.sourceUrl,
                expirationTime = extractStreamExpirationTime(request.sourceUrl),
                containerExtension = request.containerExtension,
                headers = mapOf(
                    "Authorization" to buildJellyfinAuthorizationHeader(
                        config.serverUrl,
                        config.username,
                        config.password
                    )
                )
            )
        )
    }
}

private suspend fun <T> collectPages(
    fetch: suspend (Int) -> Result<JellyfinPage<T>>
): Result<List<T>> {
    val rows = ArrayList<T>()
    var offset = 0
    do {
        when (val pageResult = fetch(offset)) {
            is Result.Success -> {
                val page = pageResult.data
                rows += page.items
                if (page.items.isEmpty() || page.nextStartIndex >= page.totalRecordCount) {
                    return Result.success(rows)
                }
                offset = page.nextStartIndex
            }
            is Result.Error -> return pageResult
            is Result.Loading -> return Result.error("Unexpected loading state")
        }
    } while (true)
}
