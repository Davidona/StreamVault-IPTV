package com.streamvault.domain.provider

import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series

data class ProviderContentReference(
    val providerId: Long,
    val localId: Long? = null,
    val streamId: Long? = null,
    val remoteId: String? = null,
    val seriesCatalogOrigin: com.streamvault.domain.model.SeriesCatalogOrigin? = null,
    val episodePlaybackTemplateUrl: String? = null
) {
    fun numericRemoteId(): Long? = remoteId?.toLongOrNull() ?: streamId
}

data class GuideRequest(
    val streamId: Long,
    val epgChannelId: String? = null,
    val limit: Int = 4
)

data class PlaybackRequest(
    val sourceUrl: String,
    val content: ProviderContentReference,
    val contentType: com.streamvault.domain.model.ContentType,
    val containerExtension: String? = null,
    val preferStableUrl: Boolean = false
)

data class ResolvedPlayback(
    val url: String,
    val expirationTime: Long? = null,
    val containerExtension: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val playbackTransportPolicy: com.streamvault.domain.model.PlaybackTransportPolicy? = null,
    val allowInvalidSsl: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int? = null,
    val observations: List<PlaybackObservation> = emptyList()
)

/** State learned while resolving, returned as data so the resolver itself remains side-effect free. */
sealed interface PlaybackObservation {
    val providerId: Long
    val configurationGeneration: Long
}

data class StalkerPlaybackObservation(
    override val providerId: Long,
    override val configurationGeneration: Long,
    val playbackMode: String,
    val endpointPreference: com.streamvault.domain.model.StalkerEndpointPreference,
    val cookieMode: com.streamvault.domain.model.StalkerCookieMode,
    val backendHint: com.streamvault.domain.model.StalkerPlaybackBackendHint
) : PlaybackObservation

data class CatchUpRequest(
    val streamId: Long,
    val start: Long,
    val end: Long,
    val sourceStreamUrl: String? = null,
    val sourceCatchUpTemplate: String? = null,
    val sourceCatchUpType: String? = null,
    val catchUpCorrectionSeconds: Int = 0,
    val fallbackCatchUpTemplate: String = DEFAULT_M3U_CATCH_UP_QUERY_FORMAT
)

const val DEFAULT_M3U_CATCH_UP_QUERY_FORMAT = "?utc={utc}&lutc={lutc}"

enum class ProviderCapability {
    AUTHENTICATION,
    LIVE_CATALOG,
    VOD_CATALOG,
    SERIES_CATALOG,
    GUIDE,
    PLAYBACK,
    CATCH_UP
}

sealed interface CapabilityResolution<out T> {
    data class Available<T>(val capability: T) : CapabilityResolution<T>
    data class Unsupported(val reason: String) : CapabilityResolution<Nothing>
    data class Restricted(val reason: String) : CapabilityResolution<Nothing>
    data class ConfigurationError(val reason: String) : CapabilityResolution<Nothing>
}

interface ProviderAuthenticator {
    suspend fun authenticate(): Result<Provider>
}

interface LiveCatalogSource {
    suspend fun getLiveCategories(): Result<List<Category>>
    suspend fun getLiveStreams(categoryId: Long? = null): Result<List<Channel>>
}

interface VodCatalogSource {
    suspend fun getVodCategories(): Result<List<Category>>
    suspend fun getVodStreams(categoryId: Long? = null): Result<List<Movie>>
    suspend fun getVodInfo(vodId: Long): Result<Movie>
    suspend fun getVodInfo(reference: ProviderContentReference): Result<Movie> {
        val id = reference.numericRemoteId()
            ?: return Result.error("Provider does not expose a numeric VOD identifier")
        return getVodInfo(id)
    }
    suspend fun hydrateVod(reference: ProviderContentReference, current: Movie): Result<Movie> =
        getVodInfo(reference)
}

interface SeriesCatalogSource {
    suspend fun getSeriesCategories(): Result<List<Category>>
    suspend fun getSeriesList(categoryId: Long? = null): Result<List<Series>>
    suspend fun getSeriesInfo(seriesId: Long): Result<Series>
    suspend fun getSeriesInfo(reference: ProviderContentReference): Result<Series> {
        val id = reference.numericRemoteId()
            ?: return Result.error("Provider does not expose a numeric series identifier")
        return getSeriesInfo(id)
    }
    suspend fun hydrateSeries(reference: ProviderContentReference, current: Series): Result<Series> =
        getSeriesInfo(reference)
}

interface GuideSource {
    suspend fun getEpg(channelId: String): Result<List<Program>>
    suspend fun getShortEpg(channelId: String, limit: Int = 4): Result<List<Program>>
    suspend fun getEpg(request: GuideRequest): Result<List<Program>> =
        getEpg(request.epgChannelId?.takeIf(String::isNotBlank) ?: request.streamId.toString())
    suspend fun getShortEpg(request: GuideRequest): Result<List<Program>> =
        getShortEpg(request.epgChannelId?.takeIf(String::isNotBlank) ?: request.streamId.toString(), request.limit)
}

interface PlaybackResolver {
    suspend fun buildStreamUrl(streamId: Long, containerExtension: String? = null): String
    suspend fun resolve(request: PlaybackRequest): Result<ResolvedPlayback> {
        if (request.sourceUrl.isNotBlank()) {
            return Result.success(
                ResolvedPlayback(
                    url = request.sourceUrl,
                    containerExtension = request.containerExtension
                )
            )
        }
        val streamId = request.content.numericRemoteId()
            ?: return Result.error("Provider does not expose a numeric playback identifier")
        return runCatching { buildStreamUrl(streamId, request.containerExtension) }
            .fold(
                { Result.success(ResolvedPlayback(it, containerExtension = request.containerExtension)) },
                { Result.error(it.message ?: "Playback resolution failed", it) }
            )
    }
}

interface CatchUpSource {
    suspend fun buildCatchUpUrl(streamId: Long, start: Long, end: Long): String?
    suspend fun buildCatchUpUrls(streamId: Long, start: Long, end: Long): List<String> =
        listOfNotNull(buildCatchUpUrl(streamId, start, end))
    suspend fun buildCatchUpUrls(request: CatchUpRequest): List<String> =
        buildCatchUpUrls(request.streamId, request.start, request.end)
}

interface ProviderCapabilityFactory {
    val providerType: ProviderType
    fun create(snapshot: ProviderSnapshot): ProviderCapabilitySet
}

/** The typed execution surface for one immutable provider snapshot. */
interface ProviderCapabilitySet {
    val snapshot: ProviderSnapshot
    fun authentication(): CapabilityResolution<ProviderAuthenticator>
    fun liveCatalog(): CapabilityResolution<LiveCatalogSource>
    fun vodCatalog(): CapabilityResolution<VodCatalogSource>
    fun seriesCatalog(): CapabilityResolution<SeriesCatalogSource>
    fun guide(): CapabilityResolution<GuideSource>
    fun playback(): CapabilityResolution<PlaybackResolver>
    fun catchUp(): CapabilityResolution<CatchUpSource>
}

interface ProviderCapabilityRegistry {
    fun resolve(snapshot: ProviderSnapshot): CapabilityResolution<ProviderCapabilitySet>
}

/** Canonical potential-capability matrix. Runtime restrictions are applied by factories. */
object ProviderCapabilityMatrix {
    private val matrix = mapOf(
        ProviderType.XTREAM_CODES to ProviderCapability.entries.toSet(),
        ProviderType.STALKER_PORTAL to ProviderCapability.entries.toSet(),
        ProviderType.M3U to setOf(
            ProviderCapability.LIVE_CATALOG,
            ProviderCapability.VOD_CATALOG,
            ProviderCapability.GUIDE,
            ProviderCapability.PLAYBACK,
            ProviderCapability.CATCH_UP
        ),
        ProviderType.JELLYFIN to setOf(
            ProviderCapability.AUTHENTICATION,
            ProviderCapability.VOD_CATALOG,
            ProviderCapability.SERIES_CATALOG,
            ProviderCapability.PLAYBACK
        )
    )

    fun potentialCapabilities(type: ProviderType): Set<ProviderCapability> = matrix.getValue(type)
    fun supports(type: ProviderType, capability: ProviderCapability): Boolean =
        capability in potentialCapabilities(type)
}
