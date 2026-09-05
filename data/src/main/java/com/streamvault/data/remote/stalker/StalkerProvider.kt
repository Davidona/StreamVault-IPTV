package com.streamvault.data.remote.stalker

import android.util.Log
import com.streamvault.data.util.AdultContentClassifier
import com.streamvault.data.util.UrlSecurityPolicy
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.util.KeyedMutexRegistry
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.PlaybackTransportMode
import com.streamvault.domain.model.PlaybackTransportPolicy
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Season
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.SeriesCatalogOrigin
import com.streamvault.domain.model.VodCatalogItem
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.StalkerPortalProfile
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.model.StalkerTransportChallenge
import com.streamvault.domain.model.StalkerTransportChallengeReason
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.StalkerTransportOrigin
import com.streamvault.domain.provider.*
import com.streamvault.domain.util.ChannelNormalizer
import com.streamvault.data.remote.xtream.extractStreamExpirationTime
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class StalkerPlaybackInfo(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val transportPolicy: PlaybackTransportPolicy? = null,
    val allowInvalidSsl: Boolean = false,
    val proxyHost: String = "",
    val proxyPort: Int? = null,
    val playbackMode: StalkerPlaybackMode = StalkerPlaybackMode.DIRECT_URL,
    val endpointPreference: StalkerEndpointPreference = StalkerEndpointPreference.AUTO,
    val cookieMode: StalkerCookieMode = StalkerCookieMode.NONE,
    val backendHint: StalkerPlaybackBackendHint = StalkerPlaybackBackendHint.AUTO
)

data class StalkerPagedResult<T>(
    val items: List<T>,
    val page: Int,
    val totalPages: Int,
    val pageSize: Int,
    val advertisedTotalItems: Int? = null,
    val advertisedTotalPages: Int? = null,
    val hasAdvertisedTotal: Boolean = true,
    val isTruncated: Boolean = false,
    val terminationReason: String? = null
) {
    val isComplete: Boolean
        get() = !isTruncated && if (hasAdvertisedTotal) page >= totalPages else items.isEmpty()
}

data class StalkerVodCatalogItem(
    val rawItemId: String,
    val item: VodCatalogItem
)

class StalkerProvider(
    val providerId: Long,
    private val api: StalkerApiService,
    private val portalUrl: String,
    private val macAddress: String,
    private val authMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    private val username: String = "",
    private val password: String = "",
    private val httpUserAgent: String = "",
    private val httpHeaders: String = "",
    private val portalFingerprintHint: StalkerPortalFingerprint = StalkerPortalFingerprint.BASIC_MAC,
    private val magPresetHint: StalkerMagPreset = StalkerMagPreset.GENERIC_SAFE,
    private val bootstrapRecipeHint: StalkerBootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
    private val endpointPreferenceHint: StalkerEndpointPreference = StalkerEndpointPreference.AUTO,
    private val cookieModeHint: StalkerCookieMode = StalkerCookieMode.NONE,
    private val playbackBackendHint: StalkerPlaybackBackendHint = StalkerPlaybackBackendHint.AUTO,
    private val portalProfileHint: StalkerPortalProfile = StalkerPortalProfile.MAG_BASIC,
    private val preferredPlaybackMode: StalkerPlaybackMode? = null,
    private val deviceProfile: String,
    private val timezone: String,
    private val locale: String,
    private val serialNumber: String = "",
    private val deviceId: String = "",
    private val deviceId2: String = "",
    private val signature: String = "",
    private val stalkerAdvancedOptionsJson: String = "",
    private val protocolPreference: StalkerProtocolPreference = StalkerProtocolPreference.AUTO,
    private val transportGrant: StalkerTransportGrant? = null,
    private val requestedProfileId: String = StalkerCompatibilityProfileIds.AUTO,
    private val learnedProfileId: String = "",
    private val configurationGeneration: Long = 0L,
    private val requireCatalogValidation: Boolean = true,
    private val catalogLayoutHint: CatalogLayout = CatalogLayout.UNKNOWN,
    private val catalogLayoutDetectionVersionHint: Int = 0,
    private val identityResolver: StalkerRemoteIdentityResolver? = null,
    private val portalStateStore: StalkerPortalStateStore? = null,
    private val discoveryCoordinator: StalkerDiscoveryCoordinator =
        StalkerDiscoveryCoordinator(api),
    private val onProgress: ((String) -> Unit)? = null
) : ProviderAuthenticator,
    LiveCatalogSource,
    VodCatalogSource,
    SeriesCatalogSource,
    GuideSource,
    PlaybackResolver,
    CatchUpSource {
internal companion object {
        private const val TAG = "StalkerProvider"
        private const val DEFAULT_PLAYER_USER_AGENT = "Lavf53.32.100"
        private const val LIVE_IDENTITY_BATCH_SIZE = 500
        private const val RESOLVED_URL_CACHE_SOFT_TTL_MILLIS = 60_000L
        private const val RESOLVED_URL_CACHE_MIN_TTL_MILLIS = 5_000L
        private const val RESOLVED_URL_CACHE_MAX_TTL_MILLIS = 300_000L
        private const val AUTH_FAILURE_COOLDOWN_MILLIS = 2_000L
        private const val FALLBACK_SURROGATE_FLOOR = 4_000_000_000L
        const val CATALOG_LAYOUT_DETECTION_VERSION = 1
        private val sharedAuthCache = ConcurrentHashMap<String, CachedAuth>()
        private val sharedPortalAuthCache = ConcurrentHashMap<String, CachedAuth>()
        private val sharedAuthFailureCache = ConcurrentHashMap<String, CachedAuthFailure>()
        private val sharedAuthMutexes = KeyedMutexRegistry<String>()
        private val resolvedStreamUrlCache = ConcurrentHashMap<String, CachedResolvedUrl>()
        private val missingVodClassificationLogged = ConcurrentHashMap.newKeySet<Long>()

        fun clearSharedAuthCacheForTests() {
            sharedAuthCache.clear()
            sharedPortalAuthCache.clear()
            sharedAuthFailureCache.clear()
        }

        fun clearResolvedStreamUrlCacheForTests() {
            resolvedStreamUrlCache.clear()
        }

        /** Provider deletion hook for process-lifetime authentication and URL caches. */
        fun clearCachesForProvider(providerId: Long) {
            if (providerId <= 0L) return
            val authPrefix = "provider:$providerId|"
            sharedAuthCache.keys.filter { it.startsWith(authPrefix) }.forEach(sharedAuthCache::remove)
            val portalPrefix = "portal:$providerId|"
            sharedPortalAuthCache.keys.filter { it.startsWith(portalPrefix) }.forEach(sharedPortalAuthCache::remove)
            sharedAuthFailureCache.keys.filter { it.startsWith(authPrefix) }.forEach(sharedAuthFailureCache::remove)
            resolvedStreamUrlCache.keys
                .filter { it.startsWith("$providerId|") }
                .forEach(resolvedStreamUrlCache::remove)
            missingVodClassificationLogged.remove(providerId)
        }

        private fun trimSharedCaches() {
            trimMap(sharedAuthCache, MAX_AUTH_CACHE_ENTRIES)
            trimMap(sharedPortalAuthCache, MAX_AUTH_CACHE_ENTRIES)
            trimMap(sharedAuthFailureCache, MAX_AUTH_CACHE_ENTRIES)
            trimMap(resolvedStreamUrlCache, MAX_RESOLVED_URL_CACHE_ENTRIES)
            while (missingVodClassificationLogged.size > MAX_MISSING_CLASSIFICATION_ENTRIES) {
                missingVodClassificationLogged.firstOrNull()?.let(missingVodClassificationLogged::remove)
                    ?: break
            }
        }

        private fun <K, V> trimMap(map: ConcurrentHashMap<K, V>, maxEntries: Int) {
            if (map.size <= maxEntries) return
            map.keys.take(map.size - maxEntries).forEach(map::remove)
        }

        private const val MAX_AUTH_CACHE_ENTRIES = 256
        private const val MAX_RESOLVED_URL_CACHE_ENTRIES = 2_048
        private const val MAX_MISSING_CLASSIFICATION_ENTRIES = 512
        private const val MAX_REMOTE_IDENTITY_CACHE_ENTRIES = 4_096
    }

    private data class CachedAuth(
        val session: StalkerSession,
        val profile: StalkerProviderProfile
)

    private data class CachedAuthFailure(
        val expiresAt: Long,
        val message: String,
        val exception: Throwable?
    )

    private data class CachedResolvedUrl(
        val url: String,
        val expiresAt: Long
    )

    private data class CategorySeed(
        val id: Long,
        val rawId: String,
        val name: String
    )

    // Authentication lock identity must remain stable for the provider lifetime. Evicting a
    // configuration-scoped lock while an older provider instance still holds it permits a second
    // mutex to be created and defeats same-provider authentication serialization.
    private var sessionCache: StalkerSession? = null
    private var accountProfileCache: StalkerProviderProfile? = null
    private var authFailureCache: CachedAuthFailure? = null
    private val categoryCache = mutableMapOf<ContentType, List<CategorySeed>>()
    private val remoteIdentityCache = ConcurrentHashMap<Pair<ContentType, String>, Long>()

    suspend fun invalidateAuthentication() {
        sharedAuthMutexes.withLock(authMutexKey()) {
            sessionCache = null
            accountProfileCache = null
            authFailureCache = null
            categoryCache.clear()
            sharedAuthCache.remove(authCacheKey())
            if (providerId > 0L) {
                sharedPortalAuthCache.remove(portalIdentityKey())
            }
            sharedAuthFailureCache.remove(authCacheKey())
            portalStateStore?.clearResumableAuth(providerId)
            clearResolvedStreamUrlCache()
            api.invalidateSessionScopes(providerId)
        }
    }

    private fun resolvedStreamUrlCacheKey(kind: StalkerStreamKind, cmd: String): String =
        "$providerId|${kind.name}|$cmd"

    private fun consultResolvedStreamUrlCache(kind: StalkerStreamKind, cmd: String): String? {
        val key = resolvedStreamUrlCacheKey(kind, cmd)
        val entry = resolvedStreamUrlCache[key] ?: return null
        if (System.currentTimeMillis() >= entry.expiresAt) {
            resolvedStreamUrlCache.remove(key, entry)
            return null
        }
        return entry.url
    }

    private fun storeResolvedStreamUrl(kind: StalkerStreamKind, cmd: String, url: String) {
        val now = System.currentTimeMillis()
        val expiry = extractStreamExpirationTime(url)
            ?.takeIf { it > now }
            ?.coerceIn(now + RESOLVED_URL_CACHE_MIN_TTL_MILLIS, now + RESOLVED_URL_CACHE_MAX_TTL_MILLIS)
            ?: (now + RESOLVED_URL_CACHE_SOFT_TTL_MILLIS)
        resolvedStreamUrlCache[resolvedStreamUrlCacheKey(kind, cmd)] =
            CachedResolvedUrl(url = url, expiresAt = expiry)
        trimSharedCaches()
    }

    private fun clearResolvedStreamUrlCache() {
        val prefix = "$providerId|"
        resolvedStreamUrlCache.keys.removeAll { it.startsWith(prefix) }
    }

    override suspend fun authenticate(): Result<Provider> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val profile = authResult.data.second
                val catalogDetection = detectCatalogLayout(authResult.data.first, profile)
                val catalogLayout = catalogDetection.layout
                val learnedDeviceProfile = buildLearnedDeviceProfile(profile)
                val hostLabel = portalUrl.substringAfter("://").substringBefore('/').ifBlank { "portal" }
                val providerName = profile.accountName?.takeUnless { it.isBlank() || it == "0" }
                    ?: normalizedUsername().takeIf { it.isNotBlank() }
                    ?: "${normalizedMacAddress().takeLast(8)}@$hostLabel"
                Result.success(
                    Provider(
                        id = providerId,
                        name = providerName,
                        type = ProviderType.STALKER_PORTAL,
                        serverUrl = StalkerUrlFactory.normalizePortalUrl(portalUrl),
                        username = normalizedUsername(),
                        password = normalizedPassword(),
                        stalkerMacAddress = normalizedMacAddress(),
                        stalkerDeviceProfile = learnedDeviceProfile.deviceProfile,
                        stalkerDeviceTimezone = learnedDeviceProfile.timezone,
                        stalkerDeviceLocale = learnedDeviceProfile.locale,
                        stalkerSerialNumber = learnedDeviceProfile.serialNumber,
                        stalkerDeviceId = learnedDeviceProfile.deviceId,
                        stalkerDeviceId2 = learnedDeviceProfile.deviceId2,
                        stalkerSignature = learnedDeviceProfile.signature,
                        stalkerAdvancedOptionsJson = stalkerAdvancedOptionsJson,
                        stalkerAuthMode = profile.effectiveAuthMode,
                        stalkerPortalProfile = profile.portalProfile,
                        stalkerPortalFingerprint = profile.portalFingerprint,
                        stalkerMagPreset = profile.magPreset,
                        stalkerProtocolPreference = protocolPreference,
                        stalkerRequestedProfileId = requestedProfileId,
                        stalkerLearnedProfileId = learnedCompatibilityProfileId(profile),
                        stalkerProfileRevision = profile.profileRevision,
                        stalkerProfileVerification = profile.profileVerification,
                        stalkerProtocolFamily = profile.protocolFamily,
                        stalkerLastBootstrapRecipe = profile.bootstrapRecipe,
                        stalkerEndpointPreference = profile.fingerprintEvidence.endpointPreference,
                        stalkerCookieMode = profile.fingerprintEvidence.cookieMode,
                        stalkerPlaybackBackendHint = profile.fingerprintEvidence.playbackBackendHint,
                        stalkerLastPlaybackMode = null,
                        stalkerCredentialsRequired = profile.credentialRequired,
                        stalkerMacRequired = profile.macRequired,
                        stalkerUsesTemporaryLinks = profile.portalCapabilities.usesTemporaryLinks,
                        stalkerModuleRestricted = profile.portalCapabilities.moduleRestricted,
                        stalkerStrictFingerprintRequired = profile.strictFingerprintRequired,
                        stalkerRecipeFallbackUsed = profile.fallbackRecipeUsed,
                        stalkerRecipeRediscoveryAttempts = if (profile.rediscoveryAttempted) 1 else 0,
                        maxConnections = profile.maxConnections ?: 1,
                        expirationDate = profile.expirationDate,
                        apiVersion = "Stalker/MAG Portal",
                        catalogLayout = catalogLayout,
                        catalogLayoutDetectionVersion = if (catalogDetection.conclusive) {
                            CATALOG_LAYOUT_DETECTION_VERSION
                        } else {
                            catalogLayoutDetectionVersionHint
                        },
                        status = resolveProviderStatus(profile)
                    )
                )
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private data class CatalogLayoutDetection(
        val layout: CatalogLayout,
        val conclusive: Boolean
    )

    private suspend fun detectCatalogLayout(
        session: StalkerSession,
        profile: StalkerProviderProfile
    ): CatalogLayoutDetection {
        if (
            catalogLayoutDetectionVersionHint >= CATALOG_LAYOUT_DETECTION_VERSION &&
            catalogLayoutHint != CatalogLayout.UNKNOWN
        ) {
            return CatalogLayoutDetection(catalogLayoutHint, conclusive = false)
        }

        val device = buildLearnedDeviceProfile(profile)
        val seriesResult = api.getSeriesCategories(session, device)
        if (seriesResult is Result.Success && seriesResult.data.isNotEmpty()) {
            StalkerTelemetry.catalogLayoutDetected(
                providerId,
                CatalogLayout.SPLIT.name,
                seriesResult.data.size,
                0,
                "native_series_categories"
            )
            return CatalogLayoutDetection(CatalogLayout.SPLIT, conclusive = true)
        }
        val seriesEndpointIsDefinitivelyEmpty = seriesResult is Result.Success ||
            (seriesResult as? Result.Error)?.isEmptyHttpBodyResponse() == true
        if (!seriesEndpointIsDefinitivelyEmpty) {
            return CatalogLayoutDetection(catalogLayoutHint, conclusive = false)
        }

        return when (val vodResult = api.getVodCategories(session, device)) {
            is Result.Success -> if (vodResult.data.isNotEmpty()) {
                StalkerTelemetry.catalogLayoutDetected(
                    providerId,
                    CatalogLayout.UNIFIED_VOD.name,
                    0,
                    vodResult.data.size,
                    "series_empty_vod_present"
                )
                CatalogLayoutDetection(CatalogLayout.UNIFIED_VOD, conclusive = true)
            } else {
                CatalogLayoutDetection(catalogLayoutHint, conclusive = false)
            }
            is Result.Error,
            is Result.Loading -> CatalogLayoutDetection(catalogLayoutHint, conclusive = false)
        }
    }

    private fun Result.Error.isEmptyHttpBodyResponse(): Boolean {
        return generateSequence(exception) { it.cause }
            .any { it is StalkerApiError.EmptyBody }
    }

    suspend fun getAccountProfile(): Result<StalkerProviderProfile> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> Result.success(authResult.data.second)
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getLiveCategories(): Result<List<Category>> =
        mapCategories(ContentType.LIVE) { session, profile ->
            api.getLiveCategories(session, profile)
        }

    override suspend fun getLiveStreams(categoryId: Long?): Result<List<Channel>> {
        val result = mapItems(ContentType.LIVE, categoryId) { session, profile, rawCategoryId ->
            api.getLiveStreams(session, profile, rawCategoryId)
        }
        return mapResolvedItems(ContentType.LIVE, result, ::toChannel)
    }

    internal fun validatedAuthenticationSnapshot(): Pair<StalkerSession, StalkerProviderProfile>? {
        val session = sessionCache ?: return null
        val profile = accountProfileCache ?: return null
        return session to profile
    }

    suspend fun streamLiveStreams(onChannel: suspend (Channel) -> Unit): Result<Int> {
        return runWithAuthorizedSession { session, _ ->
            val pendingItems = ArrayList<StalkerItemRecord>(LIVE_IDENTITY_BATCH_SIZE)

            suspend fun flushPendingItems() {
                if (pendingItems.isEmpty()) return
                bindRemoteIds(ContentType.LIVE, pendingItems.map(StalkerItemRecord::id))
                pendingItems.forEach { item ->
                    toChannel(item)?.let { channel -> onChannel(channel) }
                }
                pendingItems.clear()
            }

            when (val result = api.streamLiveStreams(session, currentDeviceProfile()) { item ->
                pendingItems += item
                if (pendingItems.size >= LIVE_IDENTITY_BATCH_SIZE) {
                    flushPendingItems()
                }
            }) {
                is Result.Success -> {
                    flushPendingItems()
                    Result.success(result.data)
                }
                is Result.Error -> Result.error(result.message, result.exception)
                is Result.Loading -> Result.error("Unexpected loading state")
            }
        }
    }

    override suspend fun getVodCategories(): Result<List<Category>> =
        mapCategories(ContentType.MOVIE) { session, profile ->
            api.getVodCategories(session, profile)
        }

    suspend fun getUnifiedVodCategories(): Result<List<Category>> =
        mapCategories(ContentType.VOD) { session, profile ->
            api.getVodCategories(session, profile)
        }

    override suspend fun getVodStreams(categoryId: Long?): Result<List<Movie>> {
        val result = mapItems(ContentType.MOVIE, categoryId) { session, profile, rawCategoryId ->
            api.getVodStreams(session, profile, rawCategoryId)
        }
        return mapResolvedItems(ContentType.MOVIE, result) { item ->
            toMovie(item, requestedCategoryId = categoryId)
        }
    }

    suspend fun getVodStreamsPage(categoryId: Long?, page: Int): Result<StalkerPagedResult<Movie>> =
        mapPagedItems(ContentType.MOVIE, categoryId) { session, profile, rawCategoryId ->
            api.getVodStreamsPage(session, profile, rawCategoryId, page)
        }.let { result -> mapResolvedPage(ContentType.MOVIE, result) { item ->
            toMovie(item, requestedCategoryId = categoryId)
        } }

    suspend fun getVodStreamsPageUsingItemCategories(categoryId: Long?, page: Int): Result<StalkerPagedResult<Movie>> =
        mapPagedItems(ContentType.MOVIE, categoryId) { session, profile, rawCategoryId ->
            api.getVodStreamsPage(session, profile, rawCategoryId, page)
        }.let { result -> mapResolvedPage(ContentType.MOVIE, result) { item ->
            toMovie(item, requestedCategoryId = null)
        } }

    /**
     * Portal-backed VOD search (the same `get_ordered_list` + `search` query the portal's own
     * STB UI uses). Searches the entire VOD catalog server-side, regardless of category, and
     * pages through results with the portal's native page size.
     */
    suspend fun searchVodPage(query: String, page: Int): Result<StalkerPagedResult<Movie>> {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isEmpty()) {
            return Result.success(
                StalkerPagedResult(items = emptyList(), page = page, totalPages = 0, pageSize = 0)
            )
        }
        return mapPagedItems(ContentType.MOVIE, null) { session, profile, _ ->
            api.getVodStreamsPage(session, profile, null, page, searchQuery = trimmedQuery)
        }.let { result -> mapResolvedPage(ContentType.MOVIE, result) { item ->
            toMovie(item, requestedCategoryId = null)
        } }
    }

    suspend fun getUnifiedVodPage(categoryId: Long, page: Int): Result<StalkerPagedResult<StalkerVodCatalogItem>> =
        getClassifiedVodPage(ContentType.VOD, categoryId, page)

    suspend fun getSplitVodPage(
        categoryId: Long,
        seriesCategoryId: Long,
        page: Int
    ): Result<StalkerPagedResult<StalkerVodCatalogItem>> =
        getClassifiedVodPage(ContentType.MOVIE, categoryId, page, seriesCategoryId)

    suspend fun projectVodCategoryToSeries(categoryId: Long): Long? {
        val rawId = resolveRawCategoryId(ContentType.MOVIE, categoryId) ?: return null
        val name = categoryCache[ContentType.MOVIE]
            ?.firstOrNull { it.id == categoryId || it.rawId == rawId }
            ?.name
            ?: "Category $categoryId"
        val projectedId = identityResolver?.resolveCategories(
            providerId,
            ContentType.SERIES,
            listOf(rawId to name)
        )?.get(rawId) ?: fallbackStableId(ContentType.SERIES, rawId)
        categoryCache[ContentType.SERIES] = categoryCache[ContentType.SERIES]
            .orEmpty()
            .filterNot { it.rawId == rawId } + CategorySeed(projectedId, rawId, name)
        putRemoteIdentity(ContentType.SERIES to rawId, projectedId)
        return projectedId
    }

    suspend fun projectSeriesCategoryToVod(categoryId: Long): Long? {
        val rawId = resolveRawCategoryId(ContentType.SERIES, categoryId) ?: return null
        val name = categoryCache[ContentType.SERIES]
            ?.firstOrNull { it.id == categoryId || it.rawId == rawId }
            ?.name
            ?: "Category $categoryId"
        val projectedId = identityResolver?.resolveCategories(
            providerId,
            ContentType.MOVIE,
            listOf(rawId to name)
        )?.get(rawId) ?: fallbackStableId(ContentType.MOVIE, rawId)
        categoryCache[ContentType.MOVIE] = categoryCache[ContentType.MOVIE]
            .orEmpty()
            .filterNot { it.rawId == rawId } + CategorySeed(projectedId, rawId, name)
        putRemoteIdentity(ContentType.MOVIE to rawId, projectedId)
        return projectedId
    }

    private suspend fun getClassifiedVodPage(
        categoryType: ContentType,
        categoryId: Long,
        page: Int,
        seriesCategoryId: Long = categoryId
    ): Result<StalkerPagedResult<StalkerVodCatalogItem>> {
        val rawResult = mapPagedItems(categoryType, categoryId) { session, profile, rawCategoryId ->
            api.getVodStreamsPage(session, profile, rawCategoryId, page)
        }
        return when (rawResult) {
            is Result.Success -> {
                val raw = rawResult.data
                val movieRecords = raw.items.filterNot(StalkerItemRecord::isSeries)
                val seriesRecords = raw.items.filter(StalkerItemRecord::isSeries)
                bindRemoteIds(ContentType.MOVIE, movieRecords.map(StalkerItemRecord::id))
                bindRemoteIds(ContentType.SERIES, seriesRecords.map(StalkerItemRecord::id))
                val items = raw.items.mapNotNull { record ->
                    if (record.isSeries) {
                        toSeries(
                            item = record,
                            requestedCategoryId = seriesCategoryId,
                            categoryType = if (categoryType == ContentType.VOD) ContentType.VOD else ContentType.SERIES,
                            origin = SeriesCatalogOrigin.VOD_DERIVED
                        )?.let { StalkerVodCatalogItem(record.id, VodCatalogItem.SeriesItem(it)) }
                    } else {
                        toMovie(
                            item = record,
                            requestedCategoryId = categoryId,
                            categoryType = categoryType
                        )?.let { StalkerVodCatalogItem(record.id, VodCatalogItem.MovieItem(it)) }
                    }
                }
                Result.success(
                    StalkerPagedResult(
                        items = items,
                        page = raw.page,
                        totalPages = raw.totalPages,
                        pageSize = raw.pageSize,
                        advertisedTotalItems = raw.advertisedTotalItems,
                        advertisedTotalPages = raw.advertisedTotalPages,
                        hasAdvertisedTotal = raw.hasAdvertisedTotal,
                        isTruncated = raw.isTruncated,
                        terminationReason = raw.terminationReason
                    )
                )
            }
            is Result.Error -> Result.error(rawResult.message, rawResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getVodInfo(vodId: Long): Result<Movie> {
        return when (val moviesResult = getVodStreamsPage(categoryId = null, page = 1)) {
            is Result.Success -> moviesResult.data.items
                .firstOrNull { movie ->
                    movie.streamId == vodId || movie.id == vodId
                }?.let { movie -> Result.success(movie) }
                ?: Result.error("Movie not found")
            is Result.Error -> Result.error(moviesResult.message, moviesResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    override suspend fun getSeriesCategories(): Result<List<Category>> {
        val primary = mapCategories(ContentType.SERIES) { session, profile ->
            api.getSeriesCategories(session, profile)
        }
        // Many Ministra portals serve series inside VOD (is_series=1) with the separate
        // type=series endpoint returning false/empty. Fall back to VOD categories so the
        // Series tab is populated — adds no overhead for portals that do use type=series.
        if (primary is Result.Success && primary.data.isEmpty()) {
            return mapCategories(ContentType.SERIES) { session, profile ->
                api.getVodCategories(session, profile)
            }
        }
        return primary
    }

    override suspend fun getSeriesList(categoryId: Long?): Result<List<Series>> {
        val primaryResult = mapItems(ContentType.SERIES, categoryId) { session, profile, rawCategoryId ->
            api.getSeries(session, profile, rawCategoryId)
        }
        val mapped = mapResolvedItems(ContentType.SERIES, primaryResult) { item ->
            toSeries(item, requestedCategoryId = categoryId)
        }
        // Fall back to VOD items with is_series=1 when the type=series endpoint is empty.
        if (mapped is Result.Success && mapped.data.isEmpty()) {
            val vodResult = mapItems(ContentType.SERIES, categoryId) { session, profile, rawCategoryId ->
                api.getVodStreams(session, profile, rawCategoryId)
            }
            return mapResolvedItems(ContentType.SERIES, vodResult) { item ->
                if (item.isSeries) toSeries(item, requestedCategoryId = categoryId) else null
            }
        }
        return mapped
    }

    suspend fun getSeriesListPage(categoryId: Long?, page: Int): Result<StalkerPagedResult<Series>> {
        val primary = getNativeSeriesListPage(categoryId, page)
        // Fall back to VOD items with is_series=1 when the type=series endpoint returns
        // empty (providers that serve series inside VOD). Only triggers when the primary
        // path produces zero items, so portals using type=series are unaffected.
        if (primary is Result.Success && primary.data.items.isEmpty()) {
            val vodPage = mapPagedItems(ContentType.SERIES, categoryId) { session, profile, rawCategoryId ->
                api.getVodStreamsPage(session, profile, rawCategoryId, page)
            }.let { result -> mapResolvedPage(ContentType.SERIES, result) { item ->
                if (item.isSeries) toSeries(item, requestedCategoryId = categoryId) else null
            } }
            return vodPage
        }
        return primary
    }

    suspend fun getNativeSeriesListPage(categoryId: Long?, page: Int): Result<StalkerPagedResult<Series>> =
        mapPagedItems(ContentType.SERIES, categoryId) { session, profile, rawCategoryId ->
            api.getSeriesPage(session, profile, rawCategoryId, page)
        }.let { result -> mapResolvedPage(ContentType.SERIES, result) { item ->
            toSeries(item, requestedCategoryId = categoryId)
        } }

    suspend fun isWildcardCategory(type: ContentType, categoryId: Long): Boolean {
        val normalizedType = when (type) {
            ContentType.SERIES_EPISODE -> ContentType.SERIES
            else -> type
        }
        return resolveRawCategoryId(normalizedType, categoryId)?.trim() == "*" ||
            categoryId == syntheticCategoryId(normalizedType, "*")
    }

    override suspend fun getSeriesInfo(seriesId: Long): Result<Series> =
        getSeriesInfo(identityResolver?.reverse(providerId, ContentType.SERIES, seriesId) ?: seriesId.toString())

    override suspend fun hydrateSeries(
        reference: com.streamvault.domain.provider.ProviderContentReference,
        current: Series
    ): Result<Series> = getSeriesInfo(
        providerSeriesId = reference.remoteId?.takeIf(String::isNotBlank)
            ?: identityResolver?.reverse(providerId, ContentType.SERIES, reference.streamId ?: current.seriesId)
            ?: (reference.streamId ?: current.seriesId).toString(),
        catalogOrigin = reference.seriesCatalogOrigin ?: current.catalogOrigin,
        episodePlaybackTemplateUrl = reference.episodePlaybackTemplateUrl
            ?: current.episodePlaybackTemplateUrl
    )

    suspend fun getSeriesInfo(
        providerSeriesId: String,
        catalogOrigin: SeriesCatalogOrigin = SeriesCatalogOrigin.NATIVE,
        episodePlaybackTemplateUrl: String? = null,
        learnedDialect: StalkerSeriesDetailDialect = StalkerSeriesDetailDialect.UNKNOWN
    ): Result<Series> {
        return runWithAuthorizedSession { session, _ ->
            val profile = currentDeviceProfile()
            var usedVodDialect = if (
                catalogOrigin == SeriesCatalogOrigin.VOD_DERIVED ||
                learnedDialect == StalkerSeriesDetailDialect.VOD_SEASON_SHELLS
            ) true else false
            val primary = if (usedVodDialect) {
                api.getVodSeriesDetails(session, profile, providerSeriesId)
            } else {
                api.getSeriesDetails(session, profile, providerSeriesId)
            }
            val detailsResult = if (
                primary is Result.Success &&
                primary.data.seasons.isEmpty() &&
                catalogOrigin == SeriesCatalogOrigin.NATIVE &&
                learnedDialect == StalkerSeriesDetailDialect.UNKNOWN
            ) {
                usedVodDialect = true
                api.getVodSeriesDetails(session, profile, providerSeriesId)
            } else {
                primary
            }
            when (detailsResult) {
                is Result.Success -> {
                    val details = detailsResult.data
                    bindRemoteIds(ContentType.SERIES, listOf(details.series.id))
                    bindRemoteIds(
                        ContentType.SERIES_EPISODE,
                        details.seasons.flatMap { season -> season.episodes.map(StalkerEpisodeRecord::id) }
                    )
                    Result.success(
                        details.toSeries(
                            // VOD_DERIVED is also an internal signal to the repository when
                            // a native row needed the bounded semantic VOD fallback. The
                            // repository's native-wins merge keeps the persisted origin native.
                            catalogOrigin = if (usedVodDialect) {
                                SeriesCatalogOrigin.VOD_DERIVED
                            } else {
                                catalogOrigin
                            },
                            episodePlaybackTemplateUrl = episodePlaybackTemplateUrl
                        )
                    )
                }
                is Result.Error -> Result.error(detailsResult.message, detailsResult.exception)
                is Result.Loading -> Result.error("Unexpected loading state")
            }
        }
    }

    override suspend fun getEpg(channelId: String): Result<List<Program>> {
        return runWithAuthorizedSession { session, _ ->
            when (val epgResult = api.getEpg(session, currentDeviceProfile(), channelId)) {
                is Result.Success -> Result.success(epgResult.data.map { it.toProgram() })
                is Result.Error -> Result.error(epgResult.message, epgResult.exception)
                is Result.Loading -> Result.error("Unexpected loading state")
            }
        }
    }

    suspend fun getBulkEpg(periodHours: Int = 6): Result<List<Program>> {
        return runWithAuthorizedSession { session, _ ->
            when (val epgResult = api.getBulkEpg(session, currentDeviceProfile(), periodHours)) {
                is Result.Success -> Result.success(epgResult.data.map { it.toProgram() })
                is Result.Error -> Result.error(epgResult.message, epgResult.exception)
                is Result.Loading -> Result.error("Unexpected loading state")
            }
        }
    }

    /**
     * Streams the bulk EPG payload one program at a time. Use this in place of [getBulkEpg]
     * when the caller can flush programs incrementally; it avoids materialising the full
     * portal response (which can exceed 30 MB on some Stalker servers).
     */
    suspend fun streamBulkEpg(
        periodHours: Int = 6,
        onProgram: suspend (Program) -> Unit
    ): Result<Int> {
        return runWithAuthorizedSession { session, _ ->
            api.streamBulkEpg(session, currentDeviceProfile(), periodHours) { record ->
                    onProgram(record.toProgram())
                }
        }
    }

    /**
     * Streams a per-channel EPG payload. Mirrors [getEpg] but does not buffer the result.
     */
    suspend fun streamEpg(
        channelId: String,
        periodHours: Int = 6,
        onProgram: suspend (Program) -> Unit
    ): Result<Int> {
        return runWithAuthorizedSession { session, _ ->
            api.streamEpg(session, currentDeviceProfile(), channelId, periodHours) { record ->
                    onProgram(record.toProgram())
                }
        }
    }

    override suspend fun getShortEpg(channelId: String, limit: Int): Result<List<Program>> {
        return runWithAuthorizedSession { session, _ ->
            when (val epgResult = api.getShortEpg(session, currentDeviceProfile(), channelId, limit)) {
                is Result.Success -> Result.success(epgResult.data.map { it.toProgram() })
                is Result.Error -> Result.error(epgResult.message, epgResult.exception)
                is Result.Loading -> Result.error("Unexpected loading state")
            }
        }
    }

    /**
     * The portal `ch_id` parameter only understands the numeric portal channel id.
     * The XMLTV-style guide key some channels carry is never valid there, so it is
     * only tried as a fallback when the numeric id returns nothing.
     */
    override suspend fun getShortEpg(request: com.streamvault.domain.provider.GuideRequest): Result<List<Program>> {
        val numericKey = request.streamId.takeIf { it > 0L }?.toString()
        val numericResult = numericKey?.let { getShortEpg(it, request.limit) }
        if (numericResult is Result.Success && numericResult.data.isNotEmpty()) return numericResult
        val xmlKey = request.epgChannelId?.takeIf { it.isNotBlank() }?.takeUnless { it == numericKey }
        if (xmlKey != null) {
            val xmlResult = getShortEpg(xmlKey, request.limit)
            if (xmlResult is Result.Success && xmlResult.data.isNotEmpty()) return xmlResult
        }
        return numericResult ?: Result.error("Short EPG lookup returned no programs")
    }

    override suspend fun getEpg(request: com.streamvault.domain.provider.GuideRequest): Result<List<Program>> {
        // get_epg_info only understands the numeric portal channel id; unlike short EPG
        // there is no cheap fallback key, so a non-numeric request fails fast instead of
        // burning a slow portal call that cannot succeed.
        val numericKey = request.streamId.takeIf { it > 0L }?.toString()
            ?: return Result.error("EPG lookup needs a numeric portal channel id")
        return getEpg(numericKey)
    }

    suspend fun resolvePlaybackInfo(
        kind: StalkerStreamKind,
        cmd: String,
        seriesNumber: Int? = null,
        archiveStartSeconds: Long? = null,
        archiveEndSeconds: Long? = null
    ): Result<StalkerPlaybackInfo> = resolvePlaybackInfo(
        kind = kind,
        descriptor = buildStalkerPlaybackDescriptor(cmd),
        seriesNumber = seriesNumber,
        archiveStartSeconds = archiveStartSeconds,
        archiveEndSeconds = archiveEndSeconds
    )

    suspend fun resolvePlaybackInfo(
        kind: StalkerStreamKind,
        descriptor: StalkerPlaybackDescriptor?,
        seriesNumber: Int? = null,
        archiveStartSeconds: Long? = null,
        archiveEndSeconds: Long? = null
    ): Result<StalkerPlaybackInfo> {
        validateArchiveWindow(kind, archiveStartSeconds, archiveEndSeconds)?.let { message ->
            return Result.error(message)
        }
        val resolvedDescriptor = descriptor ?: return Result.error("This portal requires a different playback path than the default command.")
        return resolvePlaybackInfoInternal(
            kind = kind,
            descriptor = resolvedDescriptor,
            seriesNumber = seriesNumber,
            archiveStartSeconds = archiveStartSeconds,
            archiveEndSeconds = archiveEndSeconds,
            allowRebootstrap = true
        )
    }

    private suspend fun resolvePlaybackInfoInternal(
        kind: StalkerStreamKind,
        descriptor: StalkerPlaybackDescriptor,
        seriesNumber: Int?,
        archiveStartSeconds: Long?,
        archiveEndSeconds: Long?,
        allowRebootstrap: Boolean
    ): Result<StalkerPlaybackInfo> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, accountProfile) = authResult.data
                val profile = currentDeviceProfile()
                var lastError: Result.Error? = null
                val orderedCandidates = orderStalkerCommandVariants(descriptor.candidates)
                    .sortedBy { variant ->
                        if (preferredPlaybackMode != null && variant.playbackMode == preferredPlaybackMode) 0 else 1
                    }
                orderedCandidates.forEach { variant ->
                    val adapter = resolveStalkerPlaybackAdapter(
                        descriptor = descriptor,
                        variant = variant,
                        portalProfileHint = accountProfile.portalProfile.takeUnless {
                            it == StalkerPortalProfile.MAG_BASIC
                        } ?: portalProfileHint,
                        preferredMode = preferredPlaybackMode,
                        backendHint = accountProfile.fingerprintEvidence.playbackBackendHint
                            .takeUnless { it == StalkerPlaybackBackendHint.AUTO }
                            ?: playbackBackendHint,
                        cookieModeHint = accountProfile.fingerprintEvidence.cookieMode
                            .takeUnless { it == StalkerCookieMode.NONE }
                            ?: cookieModeHint
                    )
                    val directUrl = extractDirectPlaybackUrl(variant.cmd)
                    val directCandidates = when (kind) {
                        StalkerStreamKind.ARCHIVE -> buildArchiveDirectCandidates(
                            sourceUrl = directUrl,
                            startSeconds = archiveStartSeconds,
                            endSeconds = archiveEndSeconds
                        )
                        else -> listOfNotNull(directUrl)
                    }
                    directCandidates
                        .firstOrNull { candidate ->
                            adapter.allowsDirectBypass(variant) &&
                                shouldBypassCreateLink(kind, candidate)
                        }
?.let { candidate ->
                            Log.d(
                                TAG,
                                "Resolved direct Stalker playback provider=$providerId kind=${kind.name} mode=${adapter.adapterMode.name} " +
                                    "candidateMode=${variant.playbackMode.name} endpoint=${effectiveArchiveEndpointPreference(kind, session).name}"
                            )
                            return Result.success(
                                buildResolvedPlaybackInfo(
                                    session = session,
                                    profile = profile,
                                    adapter = adapter,
                                    resolvedUrl = candidate,
                                    kind = kind,
                                    descriptor = descriptor,
                                    userAgent = resolveDirectPlaybackUserAgent(profile)
                                )
                            )
                        }

                    if (!adapter.requiresCreateLink(variant)) {
                        lastError = Result.error("This portal requires a different playback path than the default command.")
                        return@forEach
                    }

                    consultResolvedStreamUrlCache(kind, variant.cmd)?.let { cachedResolvedUrl ->
                        Log.d(
                            TAG,
                            "Resolved create_link playback provider=$providerId kind=${kind.name} mode=${adapter.adapterMode.name} " +
                                "cache=hit endpoint=${effectiveArchiveEndpointPreference(kind, session).name}"
                        )
                        return Result.success(
                            buildResolvedPlaybackInfo(
                                session = session,
                                profile = profile,
                                adapter = adapter,
                                resolvedUrl = cachedResolvedUrl,
                                kind = kind,
                                descriptor = descriptor,
                                userAgent = resolvePlaybackUserAgent(profile)
                            )
                        )
                    }

                    when (
                        val linkResult = api.createLink(
                            session = session,
                            profile = profile,
                            kind = kind,
                            cmd = variant.cmd,
                            seriesNumber = seriesNumber,
                            archiveStartSeconds = archiveStartSeconds,
                            archiveEndSeconds = archiveEndSeconds
                        )
                    ) {
is Result.Success -> {
                            val resolvedUrl = repairCreateLinkUrl(
                                kind = kind,
                                resolvedUrl = linkResult.data,
                                sourceDirectUrl = directUrl,
                                archiveStartSeconds = archiveStartSeconds,
                                archiveEndSeconds = archiveEndSeconds
                            )
                            storeResolvedStreamUrl(kind, variant.cmd, resolvedUrl)
                            Log.d(
                                TAG,
                                "Resolved create_link playback provider=$providerId kind=${kind.name} mode=${adapter.adapterMode.name} " +
                                    "candidateMode=${variant.playbackMode.name} endpoint=${effectiveArchiveEndpointPreference(kind, session).name} " +
                                    "cookie=${effectiveArchiveCookieMode(kind, session, resolvedUrl).name} " +
                                    "liveTarget=${livePlaybackTargetSummary(directUrl, resolvedUrl)}"
                            )
                            return Result.success(
                                buildResolvedPlaybackInfo(
                                    session = session,
                                    profile = profile,
                                    adapter = adapter,
                                    resolvedUrl = resolvedUrl,
                                    kind = kind,
                                    descriptor = descriptor,
                                    userAgent = resolvePlaybackUserAgent(profile)
                                )
                            )
                        }
                        is Result.Error -> {
                            lastError = linkResult
                            if (generateSequence(linkResult.exception) { it.cause }
                                    .any { it is StalkerApiError.ContentUnavailable }
                            ) {
                                val message = linkResult.message.takeIf(String::isNotBlank)
                                    ?: "The provider reported that this item is currently unavailable."
                                return Result.error(
                                    message,
                                    StalkerPlaybackResolutionException(
                                        message = message,
                                        cause = linkResult.exception,
                                        streamKind = kind,
                                        portalFingerprint = accountProfile.portalFingerprint,
                                        magPreset = accountProfile.magPreset,
                                        bootstrapRecipe = accountProfile.bootstrapRecipe,
                                        endpointPreference = accountProfile.fingerprintEvidence.endpointPreference,
                                        cookieMode = accountProfile.fingerprintEvidence.cookieMode,
                                        playbackBackendHint = accountProfile.fingerprintEvidence.playbackBackendHint,
                                        fallbackRecipeUsed = accountProfile.fallbackRecipeUsed,
                                        rediscoveryAttempted = accountProfile.rediscoveryAttempted
                                    )
                                )
                            }
                        }
                        is Result.Loading -> {
                            lastError = Result.error("Unexpected loading state")
                        }
                    }
                }

                val needsRebootstrap = allowRebootstrap &&
                    orderedCandidates.any { variant ->
                        resolveStalkerPlaybackAdapter(
                            descriptor = descriptor,
                            variant = variant,
                            portalProfileHint = accountProfile.portalProfile.takeUnless {
                                it == StalkerPortalProfile.MAG_BASIC
                            } ?: portalProfileHint,
                            preferredMode = preferredPlaybackMode,
                            backendHint = accountProfile.fingerprintEvidence.playbackBackendHint
                                .takeUnless { it == StalkerPlaybackBackendHint.AUTO }
                                ?: playbackBackendHint,
                            cookieModeHint = accountProfile.fingerprintEvidence.cookieMode
                                .takeUnless { it == StalkerCookieMode.NONE }
                                ?: cookieModeHint
                        ).allowsRebootstrap(descriptor, accountProfile)
                    } &&
                    isAuthorizationFailure(lastError?.message.orEmpty(), lastError?.exception)
                if (needsRebootstrap) {
                    invalidateAuthentication()
                    return resolvePlaybackInfoInternal(
                        kind = kind,
                        descriptor = descriptor,
                        seriesNumber = seriesNumber,
                        archiveStartSeconds = archiveStartSeconds,
                        archiveEndSeconds = archiveEndSeconds,
                        allowRebootstrap = false
                    )
                }

                val message = when {
                    generateSequence(lastError?.exception) { it.cause }
                        .any { it is StalkerApiError.ContentUnavailable } ->
                        "The provider reported that this item is currently unavailable."

                    generateSequence(lastError?.exception) { it.cause }
                        .any { it is StalkerApiError } ->
                        lastError?.message?.takeIf { it.isNotBlank() }
                            ?: "The provider could not resolve this item for playback."

                    accountProfile.strictFingerprintRequired && lastError?.message.isNullOrBlank() ->
                        "Portal requires stricter MAG emulation."

                    accountProfile.fallbackRecipeUsed && descriptor.capabilities.usesTemporaryLinks ->
                        "Portal matched a legacy MAG recipe and was retried automatically, but playback still failed."

                    accountProfile.rediscoveryAttempted ->
                        "Stored portal recipe failed; rediscovery attempted."

                    descriptor.capabilities.ambiguousAccountState || accountProfile.ambiguousState ->
                        "Portal profile is ambiguous; playback/session validation failed."

                    descriptor.primaryMode == StalkerPlaybackMode.MULTI_CMD || descriptor.candidates.size > 1 ->
                        "This portal requires a different playback path than the default command."

                    descriptor.capabilities.usesTemporaryLinks ->
                        lastError?.message?.takeIf { it.isNotBlank() }
                            ?: "Portal could not issue a valid temporary playback link for this stream."

                    else -> lastError?.message?.takeIf { it.isNotBlank() }
                        ?: "Portal family detected, but no working recipe succeeded."
                }
                Log.w(
                    TAG,
                    "Stalker playback failed provider=$providerId kind=${kind.name} " +
                        "fingerprint=${accountProfile.portalFingerprint.name} preset=${accountProfile.magPreset.name} " +
                        "recipe=${accountProfile.bootstrapRecipe.name} endpoint=${accountProfile.fingerprintEvidence.endpointPreference.name} " +
                        "cookie=${accountProfile.fingerprintEvidence.cookieMode.name} backend=${accountProfile.fingerprintEvidence.playbackBackendHint.name} " +
                        "fallback=${accountProfile.fallbackRecipeUsed} rediscovery=${accountProfile.rediscoveryAttempted} " +
                        "errorType=${lastError?.exception?.javaClass?.simpleName ?: "none"} " +
                        "reason=$message"
                )
                Result.error(
                    message,
                    StalkerPlaybackResolutionException(
                        message = message,
                        cause = lastError?.exception,
                        streamKind = kind,
                        portalFingerprint = accountProfile.portalFingerprint,
                        magPreset = accountProfile.magPreset,
                        bootstrapRecipe = accountProfile.bootstrapRecipe,
                        endpointPreference = accountProfile.fingerprintEvidence.endpointPreference,
                        cookieMode = accountProfile.fingerprintEvidence.cookieMode,
                        playbackBackendHint = accountProfile.fingerprintEvidence.playbackBackendHint,
                        fallbackRecipeUsed = accountProfile.fallbackRecipeUsed,
                        rediscoveryAttempted = accountProfile.rediscoveryAttempted
                    )
                )
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    suspend fun resolvePlaybackUrl(
        kind: StalkerStreamKind,
        cmd: String,
        seriesNumber: Int? = null,
        archiveStartSeconds: Long? = null,
        archiveEndSeconds: Long? = null
    ): Result<String> =
        resolvePlaybackInfo(
            kind = kind,
            cmd = cmd,
            seriesNumber = seriesNumber,
            archiveStartSeconds = archiveStartSeconds,
            archiveEndSeconds = archiveEndSeconds
        ).mapData(StalkerPlaybackInfo::url)

    override suspend fun buildStreamUrl(streamId: Long, containerExtension: String?): String {
        throw UnsupportedOperationException("Stalker stream URLs require a command token context.")
    }

    override suspend fun resolve(
        request: com.streamvault.domain.provider.PlaybackRequest
    ): Result<com.streamvault.domain.provider.ResolvedPlayback> {
        val token = StalkerUrlFactory.parseInternalStreamUrl(request.sourceUrl)
        val directKind = when (request.contentType) {
            ContentType.LIVE -> StalkerStreamKind.LIVE
            ContentType.VOD,
            ContentType.MOVIE -> StalkerStreamKind.MOVIE
            ContentType.SERIES_EPISODE,
            ContentType.SERIES -> StalkerStreamKind.EPISODE
        }
        val directUrl = token?.let { request.sourceUrl } ?: repairDirectPlaybackUrl(
            request.sourceUrl,
            directKind,
            request.content.streamId
        )
        if (token == null && !UrlSecurityPolicy.isAllowedStreamEntryUrl(directUrl)) {
            return Result.error("Stalker playback URL is not allowed")
        }
        val descriptor = token?.playbackDescriptor
            ?: buildStalkerPlaybackDescriptor(
                primaryCmd = directUrl,
                capabilities = StalkerPortalCapabilities()
            )
        return when (val result = resolvePlaybackInfo(
            kind = token?.kind ?: directKind,
            descriptor = descriptor,
            seriesNumber = token?.seriesNumber,
            archiveStartSeconds = token?.archiveStartSeconds,
            archiveEndSeconds = token?.archiveEndSeconds
        )) {
            is Result.Success -> {
                val info = result.data
                Result.success(
                    com.streamvault.domain.provider.ResolvedPlayback(
                        url = info.url,
                        containerExtension = token?.containerExtension ?: request.containerExtension,
                        headers = info.headers,
                        userAgent = info.userAgent,
                        playbackTransportPolicy = info.transportPolicy,
                        allowInvalidSsl = info.allowInvalidSsl,
                        proxyHost = info.proxyHost,
                        proxyPort = info.proxyPort,
                        observations = listOf(
                            com.streamvault.domain.provider.StalkerPlaybackObservation(
                                providerId = providerId,
                                configurationGeneration = configurationGeneration,
                                playbackMode = info.playbackMode.name,
                                endpointPreference = info.endpointPreference,
                                cookieMode = info.cookieMode,
                                backendHint = info.backendHint
                            )
                        )
                    )
                )
            }
            is Result.Error -> Result.error(result.message, result.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private fun repairDirectPlaybackUrl(
        url: String,
        kind: StalkerStreamKind,
        fallbackStreamId: Long?
    ): String {
        if (kind != StalkerStreamKind.LIVE || fallbackStreamId == null || fallbackStreamId <= 0L) return url
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        if (!uri.path.orEmpty().lowercase().endsWith("/play/live.php")) return url
        val rawQuery = uri.rawQuery ?: return url
        val parts = rawQuery.split('&').filter(String::isNotBlank)
        if (parts.isEmpty()) return url
        var hasStream = false
        var changed = false
        val repaired = parts.map { part ->
            if (part.substringBefore('=', "").lowercase() != "stream") return@map part
            hasStream = true
            if (part.substringAfter('=', "").isNotBlank()) return@map part
            changed = true
            "stream=$fallbackStreamId"
        }.toMutableList()
        if (!hasStream && rawQuery.contains("play_token=")) {
            repaired += "stream=$fallbackStreamId"
            changed = true
        }
        if (!changed) return url
        return URI(uri.scheme, uri.authority, uri.path, repaired.joinToString("&"), uri.fragment).toString()
    }

    override suspend fun buildCatchUpUrl(streamId: Long, start: Long, end: Long): String? =
        buildCatchUpUrls(streamId, start, end).firstOrNull()

    override suspend fun buildCatchUpUrls(streamId: Long, start: Long, end: Long): List<String> =
        buildCatchUpUrls(streamId, start, end, sourceStreamUrl = null, sourceCatchUpSource = null)

    override suspend fun buildCatchUpUrls(
        request: com.streamvault.domain.provider.CatchUpRequest
    ): List<String> = buildCatchUpUrls(
        streamId = request.streamId,
        start = request.start,
        end = request.end,
        sourceStreamUrl = request.sourceStreamUrl,
        sourceCatchUpSource = request.sourceCatchUpTemplate
    )

    suspend fun buildCatchUpUrls(
        streamId: Long,
        start: Long,
        end: Long,
        sourceStreamUrl: String?,
        sourceCatchUpSource: String?
    ): List<String> {
        val safeStart = start.takeIf { it > 0L } ?: return emptyList()
        val safeEnd = end.takeIf { it > safeStart } ?: return emptyList()
        val seedToken = sequenceOf(sourceCatchUpSource, sourceStreamUrl)
            .mapNotNull(StalkerUrlFactory::parseInternalStreamUrl)
            .firstOrNull()
            ?: return emptyList()
        if (seedToken.providerId != providerId) {
            return emptyList()
        }
        if (seedToken.kind != StalkerStreamKind.LIVE && seedToken.kind != StalkerStreamKind.ARCHIVE) {
            return emptyList()
        }
        val seedDescriptor = seedToken.playbackDescriptor
            ?: buildStalkerPlaybackDescriptor(seedToken.cmd)
            ?: return emptyList()
        val orderedCandidates = seedDescriptor.candidates.sortedBy { variant ->
            if (preferredPlaybackMode != null && variant.playbackMode == preferredPlaybackMode) 0 else 1
        }
        return orderedCandidates.mapIndexed { index, variant ->
            StalkerUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = StalkerStreamKind.ARCHIVE,
                itemId = streamId.takeIf { it > 0L } ?: seedToken.itemId,
                cmd = variant.cmd,
                containerExtension = seedToken.containerExtension,
                archiveStartSeconds = safeStart,
                archiveEndSeconds = safeEnd,
                playbackDescriptor = StalkerPlaybackDescriptor(
                    primaryMode = variant.playbackMode,
                    candidates = listOf(variant.copy(priority = index)),
                    capabilities = seedDescriptor.capabilities
                )
            )
        }.distinct()
    }

    private suspend fun mapCategories(
        type: ContentType,
        loader: suspend (StalkerSession, StalkerDeviceProfile) -> Result<List<StalkerCategoryRecord>>
    ): Result<List<Category>> {
        return runWithAuthorizedSession { session, _ ->
            when (val result = loader(session, currentDeviceProfile())) {
                is Result.Success -> {
                    val categoryRecords = result.data.ifEmpty {
                        when (type) {
                            ContentType.MOVIE -> listOf(StalkerCategoryRecord(id = "*", name = "All Movies"))
                            ContentType.SERIES -> listOf(StalkerCategoryRecord(id = "*", name = "All Series"))
                            else -> emptyList()
                        }
                    }
                    bindCategoryRemoteIds(type, categoryRecords)
                    val categories = categoryRecords.map { record ->
                        val id = syntheticCategoryId(type, record.id.ifBlank { record.name })
                        CategorySeed(
                            id = id,
                            rawId = record.id,
                            name = record.name
                        )
                    }
                    categoryCache[type] = categories
                    Result.success(
                        categories.map { seed ->
                            Category(
                                id = seed.id,
                                name = seed.name,
                                type = type,
                                isAdult = AdultContentClassifier.isAdultCategoryName(seed.name)
                            )
                        }
                    )
                }
                is Result.Error -> Result.error(result.message, result.exception)
                is Result.Loading -> Result.error("Unexpected loading state")
            }
        }
    }

    private suspend fun mapItems(
        type: ContentType,
        categoryId: Long?,
        loader: suspend (StalkerSession, StalkerDeviceProfile, String?) -> Result<List<StalkerItemRecord>>
    ): Result<List<StalkerItemRecord>> {
        return runWithAuthorizedSession { session, _ ->
            val rawCategoryId = resolveRawCategoryId(type, categoryId)
            loader(session, currentDeviceProfile(), rawCategoryId)
        }
    }

    private suspend fun mapPagedItems(
        type: ContentType,
        categoryId: Long?,
        loader: suspend (StalkerSession, StalkerDeviceProfile, String?) -> Result<StalkerPagedItems>
    ): Result<StalkerPagedItems> {
        return runWithAuthorizedSession { session, _ ->
            val rawCategoryId = resolveRawCategoryId(type, categoryId)
            loader(session, currentDeviceProfile(), rawCategoryId)
        }
    }

    private suspend fun <T> mapResolvedItems(
        type: ContentType,
        result: Result<List<StalkerItemRecord>>?,
        mapper: (StalkerItemRecord) -> T?
    ): Result<List<T>> = when (result) {
        is Result.Success -> {
            bindRemoteIds(type, result.data.map(StalkerItemRecord::id))
            Result.success(result.data.mapNotNull(mapper))
        }
        is Result.Error -> Result.error(result.message, result.exception)
        is Result.Loading -> Result.error("Unexpected loading state")
        null -> Result.error("Portal returned no item response")
    }

    private suspend fun <T> mapResolvedPage(
        type: ContentType,
        result: Result<StalkerPagedItems>?,
        mapper: (StalkerItemRecord) -> T?
    ): Result<StalkerPagedResult<T>> = when (result) {
        is Result.Success -> {
            val paged = result.data
            bindRemoteIds(type, paged.items.map(StalkerItemRecord::id))
            Result.success(
                StalkerPagedResult(
                    items = paged.items.mapNotNull(mapper),
                    page = paged.page,
                    totalPages = paged.totalPages,
                    pageSize = paged.pageSize,
                    advertisedTotalItems = paged.advertisedTotalItems,
                    advertisedTotalPages = paged.advertisedTotalPages,
                    hasAdvertisedTotal = paged.hasAdvertisedTotal,
                    isTruncated = paged.isTruncated,
                    terminationReason = paged.terminationReason
                )
            )
        }
        is Result.Error -> Result.error(result.message, result.exception)
        is Result.Loading -> Result.error("Unexpected loading state")
        null -> Result.error("Portal returned no page response")
    }

    private suspend fun bindRemoteIds(type: ContentType, rawIds: Iterable<String>) {
        val ids = rawIds.map(String::trim).filter(String::isNotEmpty).distinct()
        if (ids.isEmpty()) return
        val resolved = identityResolver?.resolveAll(providerId, type, ids)
            ?: ids.associateWith { rawId ->
                rawId.toLongOrNull()?.takeIf { it > 0L } ?: fallbackStableId(type, rawId)
            }
        resolved.forEach { (rawId, surrogateId) ->
            putRemoteIdentity(type to rawId, surrogateId)
        }
    }

    private suspend fun bindCategoryRemoteIds(type: ContentType, records: List<StalkerCategoryRecord>) {
        val resolved = identityResolver?.resolveCategories(
            providerId = providerId,
            contentType = type,
            categories = records.map { record -> (record.id.ifBlank { record.name }) to record.name }
        )
        if (resolved == null) {
            records.forEach { record ->
                val rawId = record.id.ifBlank { record.name }.trim()
                putRemoteIdentity(type to rawId, fallbackStableId(type, rawId))
            }
        } else {
            resolved.forEach { (rawId, surrogateId) -> putRemoteIdentity(type to rawId, surrogateId) }
        }
    }

    private suspend fun <T> runWithAuthorizedSession(
        retryOnAuthorizationFailure: Boolean = true,
        block: suspend (StalkerSession, StalkerProviderProfile) -> Result<T>
    ): Result<T> {
        return when (val authResult = ensureAuthenticated()) {
            is Result.Success -> {
                val (session, profile) = authResult.data
                when (val result = block(session, profile)) {
                    is Result.Error ->
                        if (retryOnAuthorizationFailure && isAuthorizationFailure(result.message, result.exception)) {
                            invalidateAuthentication()
                            runWithAuthorizedSession(false, block)
                        } else {
                            Result.error(result.message, result.exception)
                        }
                    else -> result
                }
            }
            is Result.Error -> Result.error(authResult.message, authResult.exception)
            is Result.Loading -> Result.error("Unexpected loading state")
        }
    }

    private suspend fun ensureAuthenticated(): Result<Pair<StalkerSession, StalkerProviderProfile>> =
        sharedAuthMutexes.withLock(authMutexKey()) {
            val cachedSession = sessionCache
            val cachedProfile = accountProfileCache
            if (cachedSession != null && cachedProfile != null &&
                !cachedSession.isExpired() &&
                cachedProfile.expirationDate?.let { it > System.currentTimeMillis() } != false
            ) {
                return@withLock Result.success(cachedSession to cachedProfile)
            }
            if (cachedSession != null || cachedProfile != null) {
                sessionCache = null
                accountProfileCache = null
                sharedAuthCache.remove(authCacheKey())
                if (providerId > 0L) {
                    sharedPortalAuthCache.remove(portalIdentityKey())
                }
                api.invalidateSessionScopes(providerId)
            }
            (authFailureCache ?: sharedAuthFailureCache[authCacheKey()])?.let { failure ->
                if (failure.expiresAt > System.currentTimeMillis()) {
                    return@withLock Result.error(failure.message, failure.exception)
                }
                authFailureCache = null
                sharedAuthFailureCache.remove(authCacheKey(), failure)
            }
            sharedAuthCache[authCacheKey()]?.let { cachedAuth ->
                if (!cachedAuth.session.isExpired() &&
                    cachedAuth.profile.expirationDate?.let { it > System.currentTimeMillis() } != false
                ) {
                    sessionCache = cachedAuth.session
                    accountProfileCache = cachedAuth.profile
                    return@withLock Result.success(cachedAuth.session to cachedAuth.profile)
                }
                sharedAuthCache.remove(authCacheKey(), cachedAuth)
                api.invalidateSessionScopes(providerId)
            }
            if (providerId > 0L) {
                sharedPortalAuthCache[portalIdentityKey()]?.let { cachedAuth ->
                    if (!cachedAuth.session.isExpired() &&
                        cachedAuth.profile.expirationDate?.let { it > System.currentTimeMillis() } != false
                    ) {
                        sessionCache = cachedAuth.session
                        accountProfileCache = cachedAuth.profile
                        sharedAuthCache[authCacheKey()] = cachedAuth
                        return@withLock Result.success(cachedAuth.session to cachedAuth.profile)
                    }
                    sharedPortalAuthCache.remove(portalIdentityKey(), cachedAuth)
                    api.invalidateSessionScopes(providerId)
                }
            }
            if (providerId > 0L) {
                portalStateStore?.resumableAuth(providerId, configurationGeneration)?.let { resumed ->
                    sessionCache = resumed.session
                    accountProfileCache = resumed.profile
                    val cachedAuth = CachedAuth(session = resumed.session, profile = resumed.profile)
                    sharedAuthCache[authCacheKey()] = cachedAuth
                    sharedPortalAuthCache[portalIdentityKey()] = cachedAuth
                    StalkerTelemetry.strategySelected(providerId, "RESUMED_SESSION", "PERSISTED_TOKEN")
                    return@withLock Result.success(resumed.session to resumed.profile)
                }
            }

            val persistedState = portalStateStore?.getValidated(providerId)
            val hasPersistedAuthentication = persistedState?.let { state ->
                !state.workingEndpoint.isNullOrBlank() || !state.bootstrapRecipe.isNullOrBlank()
            } == true
            val rawPersistedRecipe = persistedState?.bootstrapRecipe
                ?.let { value -> runCatching { StalkerBootstrapRecipe.valueOf(value) }.getOrNull() }
            val persistedRecipe = rawPersistedRecipe?.takeIf { recipe ->
                portalStateStore?.isRecipeHealthy(persistedState, recipe.name) != false
            }
            val persistedEndpointUrl = persistedState?.workingEndpoint?.takeIf { endpoint ->
                portalStateStore?.isEndpointHealthy(persistedState, endpoint) != false
            }
            val persistedEndpoint = persistedEndpointUrl?.let { endpoint ->
                if (endpoint.contains("server/load.php", ignoreCase = true)) {
                    StalkerEndpointPreference.SERVER_LOAD
                } else {
                    StalkerEndpointPreference.PORTAL
                }
            }
            if (persistedState?.workingEndpoint != null && persistedEndpointUrl == null) {
                StalkerTelemetry.strategySelected(providerId, "AUTH_ENDPOINT_AUTO", "ENDPOINT_COOLDOWN")
            } else if (persistedEndpoint != null) {
                StalkerTelemetry.strategySelected(providerId, persistedEndpoint.name, "VALIDATED_CACHE")
            }
            if (rawPersistedRecipe != null && persistedRecipe == null) {
                StalkerTelemetry.strategySelected(providerId, "AUTH_RECIPE_DISCOVERY", "RECIPE_COOLDOWN")
            }
            val profile = buildStalkerDeviceProfile(
                // Preserve the complete validated endpoint (scheme, custom path, and script), not
                // just its SERVER_LOAD/PORTAL family. loadUrlCandidates accepts a direct script URL.
                portalUrl = persistedEndpointUrl ?: portalUrl,
                macAddress = normalizedMacAddress(),
                authMode = authMode,
                magPresetHint = magPresetHint,
                portalFingerprintHint = portalFingerprintHint,
                bootstrapRecipeHint = persistedRecipe
                    ?: bootstrapRecipeHint.takeIf { rawPersistedRecipe == null }
                    ?: StalkerBootstrapRecipe.GENERIC_SAFE,
                endpointPreferenceHint = persistedEndpoint ?: endpointPreferenceHint,
                cookieModeHint = cookieModeHint,
                playbackBackendHint = playbackBackendHint,
                username = normalizedUsername(),
                password = normalizedPassword(),
                httpUserAgentOverride = httpUserAgent.trim(),
                httpHeadersOverride = httpHeaders,
                deviceProfile = normalizedDeviceProfile(),
                timezone = normalizedTimezone(),
                locale = normalizedLocale(),
                serialNumberOverride = normalizedSerialNumber(),
                deviceIdOverride = normalizedDeviceId(),
                deviceId2Override = normalizedDeviceId2(),
                signatureOverride = normalizedSignature(),
                stalkerAdvancedOptionsJson = stalkerAdvancedOptionsJson,
                protocolPreference = protocolPreference,
                transportGrant = transportGrant,
                requestedProfileId = requestedProfileId,
                learnedProfileId = learnedProfileId,
                requireCatalogValidation = requireCatalogValidation,
                allowCompatibilityDiscovery = providerId <= 0L,
                onProgress = onProgress
            ).copy(providerId = providerId)
            val initialAuthResult = discoveryCoordinator.authenticate(profile)
            val initialAuthThrottled = (initialAuthResult as? Result.Error)?.exception?.isPortalThrottle() == true
            val finalAuthResult = when {
                initialAuthResult !is Result.Error -> initialAuthResult
                // A throttled portal must not get an instant repair handshake: the retry
                // fires milliseconds later and converts a soft throttle into a hard 429.
                // Surface the throttle so callers back off; the persisted session and
                // failure cooldown already cover the retry.
                initialAuthThrottled -> {
                    StalkerTelemetry.strategySelected(providerId, "AUTH_THROTTLE_BACKOFF", "THROTTLED_AUTH_RETRY_SKIPPED")
                    initialAuthResult
                }
                persistedEndpointUrl != null -> {
                    portalStateStore?.markEndpointUnhealthy(
                        providerId,
                        persistedEndpointUrl,
                        configurationGeneration = configurationGeneration
                    )
                    StalkerTelemetry.strategySelected(providerId, "AUTH_ENDPOINT_AUTO", "CACHED_ENDPOINT_FAILED")
                    discoveryCoordinator.authenticate(
                        profile.copy(
                            portalUrl = portalUrl,
                            endpointPreference = StalkerEndpointPreference.AUTO,
                            // Endpoint repair retains the learned identity. Broad profile rotation
                            // remains reserved for an explicit foreground Repair connection action.
                            allowCompatibilityDiscovery = false
                        )
                    )
                }
                hasPersistedAuthentication -> {
                    // If the persisted endpoint/recipe is already cooling down, the old path
                    // rebuilt the same generic profile with discovery disabled. That made a
                    // recoverable provider fail every catalog request until the cooldown expired.
                    // Give one bounded recovery attempt the original portal base and the normal
                    // compatibility candidate set, then persist whichever recipe succeeds.
                    StalkerTelemetry.strategySelected(providerId, "AUTH_RECIPE_DISCOVERY", "CACHED_AUTH_RECOVERY")
                    discoveryCoordinator.authenticate(
                        profile.copy(
                            portalUrl = portalUrl,
                            bootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
                            endpointPreference = StalkerEndpointPreference.AUTO,
                            allowCompatibilityDiscovery = true
                        )
                    )
                }
                else -> initialAuthResult
            }
            when (val authResult = finalAuthResult) {
                is Result.Success -> {
                    sessionCache = authResult.data.first
                    accountProfileCache = authResult.data.second
                    authFailureCache = null
                    sharedAuthFailureCache.remove(authCacheKey())
                    sharedAuthCache[authCacheKey()] = CachedAuth(
                        session = authResult.data.first,
                        profile = authResult.data.second
                    )
                    if (providerId > 0L) {
                        sharedPortalAuthCache[portalIdentityKey()] = CachedAuth(
                            session = authResult.data.first,
                            profile = authResult.data.second
                        )
                    }
                    trimSharedCaches()
                    portalStateStore?.recordAuthentication(
                        providerId = providerId,
                        session = authResult.data.first,
                        profile = authResult.data.second,
                        configurationGeneration = configurationGeneration
                    )
                    Result.success(authResult.data)
                }
                is Result.Error -> {
                    rawPersistedRecipe?.let { failedRecipe ->
                        portalStateStore?.markRecipeUnhealthy(
                            providerId,
                            failedRecipe.name,
                            configurationGeneration = configurationGeneration
                        )
                    }
                    authFailureCache = CachedAuthFailure(
                        expiresAt = System.currentTimeMillis() + AUTH_FAILURE_COOLDOWN_MILLIS,
                        message = authResult.message,
                        exception = authResult.exception
                    )
                    sharedAuthFailureCache[authCacheKey()] = authFailureCache!!
                    trimSharedCaches()
                    Result.error(authResult.message, authResult.exception)
                }
                is Result.Loading -> Result.error("Unexpected loading state")
            }
        }

    suspend fun validatedPortalState(): com.streamvault.data.local.entity.StalkerPortalStateEntity? =
        portalStateStore?.getValidated(providerId)

    suspend fun recordBulkLiveCapability(supported: Boolean, categoryFidelity: Boolean? = null) {
        portalStateStore?.recordBulkLive(
            providerId,
            supported,
            categoryFidelity,
            configurationGeneration = configurationGeneration
        )
    }

    suspend fun recordWildcardCapability(contentType: ContentType, supported: Boolean) {
        portalStateStore?.recordWildcard(
            providerId,
            contentType,
            supported,
            configurationGeneration = configurationGeneration
        )
    }

    suspend fun recordEpgCapability(supported: Boolean) {
        portalStateStore?.recordEpg(
            providerId,
            supported,
            configurationGeneration = configurationGeneration
        )
    }

    private fun currentDeviceProfile(): StalkerDeviceProfile {
        accountProfileCache?.let { learned ->
            return buildLearnedDeviceProfile(learned).copy(authEpoch = sessionCache?.authEpoch ?: 0L)
        }
        return buildStalkerDeviceProfile(
            portalUrl = portalUrl,
            macAddress = normalizedMacAddress(),
            authMode = authMode,
            magPresetHint = magPresetHint,
            portalFingerprintHint = portalFingerprintHint,
            bootstrapRecipeHint = bootstrapRecipeHint,
            endpointPreferenceHint = endpointPreferenceHint,
            cookieModeHint = cookieModeHint,
            playbackBackendHint = playbackBackendHint,
            username = normalizedUsername(),
            password = normalizedPassword(),
            httpUserAgentOverride = httpUserAgent.trim(),
            httpHeadersOverride = httpHeaders,
            deviceProfile = normalizedDeviceProfile(),
            timezone = normalizedTimezone(),
            locale = normalizedLocale(),
            serialNumberOverride = normalizedSerialNumber(),
            deviceIdOverride = normalizedDeviceId(),
            deviceId2Override = normalizedDeviceId2(),
            signatureOverride = normalizedSignature(),
            stalkerAdvancedOptionsJson = stalkerAdvancedOptionsJson,
            protocolPreference = protocolPreference,
            transportGrant = transportGrant,
            requestedProfileId = requestedProfileId,
            learnedProfileId = learnedProfileId,
            requireCatalogValidation = requireCatalogValidation,
            allowCompatibilityDiscovery = providerId <= 0L,
            onProgress = onProgress
        ).copy(
            providerId = providerId,
            authEpoch = sessionCache?.authEpoch ?: 0L
        )
    }

    private fun buildLearnedDeviceProfile(profile: StalkerProviderProfile): StalkerDeviceProfile {
        val learnedCompatibilityProfileId = learnedCompatibilityProfileId(profile)
        return buildStalkerDeviceProfile(
            portalUrl = portalUrl,
            macAddress = normalizedMacAddress(),
            authMode = profile.effectiveAuthMode,
            magPresetHint = profile.magPreset,
            portalFingerprintHint = profile.portalFingerprint,
            bootstrapRecipeHint = profile.bootstrapRecipe,
            endpointPreferenceHint = profile.fingerprintEvidence.endpointPreference,
            cookieModeHint = profile.fingerprintEvidence.cookieMode,
            playbackBackendHint = profile.fingerprintEvidence.playbackBackendHint,
            username = normalizedUsername(),
            password = normalizedPassword(),
            httpUserAgentOverride = httpUserAgent.trim(),
            httpHeadersOverride = httpHeaders,
            deviceProfile = normalizedDeviceProfile(),
            timezone = normalizedTimezone(),
            locale = normalizedLocale(),
            serialNumberOverride = normalizedSerialNumber(),
            deviceIdOverride = normalizedDeviceId(),
            deviceId2Override = normalizedDeviceId2(),
            signatureOverride = normalizedSignature(),
            stalkerAdvancedOptionsJson = stalkerAdvancedOptionsJson,
            protocolPreference = protocolPreference,
            transportGrant = transportGrant,
            requestedProfileId = learnedCompatibilityProfileId,
            learnedProfileId = learnedCompatibilityProfileId,
            requireCatalogValidation = requireCatalogValidation,
            allowCompatibilityDiscovery = providerId <= 0L,
            onProgress = onProgress
        ).copy(
            providerId = providerId,
            authEpoch = sessionCache?.authEpoch ?: 0L
        )
    }

    private fun learnedCompatibilityProfileId(profile: StalkerProviderProfile): String =
        if (profile.compatibilityProfileId == StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC &&
            profile.magPreset != StalkerMagPreset.GENERIC_SAFE
        ) {
            StalkerCompatibilityRegistry.idForLegacyPreset(profile.magPreset)
        } else {
            profile.compatibilityProfileId
        }

    private fun buildPlaybackHeaders(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        url: String
    ): Map<String, String> = buildMap {
        val playerHeaderOverrides = parseStalkerHeaderOverrides(profile.advancedOptions.playerHeaders)
        val omitAuthorization = shouldOmitPlaybackAuthorization(url)
        val serverCookieHeader = api.currentCookieHeader(session)
            .ifBlank { session.serverCookieHeader }
        put("Referer", session.portalReferer)
        put("Accept", "*/*")
        put("Connection", "keep-alive")
        buildPlaybackHostHeader(url)?.let { host ->
            put("Host", host)
        }
        put("Cookie", buildPlaybackCookieHeader(serverCookieHeader, profile))
        put("X-User-Agent", profile.xUserAgent)
        session.token.takeIf { it.isNotBlank() && !omitAuthorization }?.let { token ->
            put("Authorization", "Bearer $token")
        }
        profile.headerOverrides.forEach { (name, value) ->
            if (value == null) {
                remove(name)
            } else if (name.equals("User-Agent", ignoreCase = true)) {
                // Playback user agent is surfaced separately on StalkerPlaybackInfo.
            } else {
                put(name, value)
            }
        }
        playerHeaderOverrides.forEach { (name, value) ->
            if (value == null) {
                remove(name)
            } else if (name.equals("User-Agent", ignoreCase = true)) {
                // Playback user agent is surfaced separately on StalkerPlaybackInfo.
            } else {
                put(name, value)
            }
        }
    }

    private fun playbackTransportPolicyFor(url: String): PlaybackTransportPolicy? {
        val grant = transportGrant ?: return null
        val normalizedUrl = url.trim().substringAfter(' ', url.trim())
        val uri = runCatching { URI(normalizedUrl) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val port = when {
            uri.port != -1 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> return null
        }
        if (!grant.origin.scheme.equals(scheme, ignoreCase = true) ||
            !grant.origin.host.equals(host, ignoreCase = true) ||
            grant.origin.port != port
        ) {
            return null
        }
        return when (grant.mode) {
            StalkerTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS ->
                PlaybackTransportPolicy(
                    mode = PlaybackTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS,
                    origin = grant.origin,
                    spkiSha256 = grant.spkiSha256
                )
            StalkerTransportMode.USER_ACCEPTED_HTTP ->
                PlaybackTransportPolicy(
                    mode = PlaybackTransportMode.USER_ACCEPTED_HTTP,
                    origin = grant.origin,
                    allowCrossOriginHttpRedirects = true
                )
            StalkerTransportMode.AUTO_STRICT,
            StalkerTransportMode.VERIFIED_HTTPS -> null
        }
    }

private fun playbackTransportChallengeFor(url: String): StalkerTransportChallenge? {
        // IPTV streams are commonly served over plain HTTP from a different origin than the
        // (often HTTPS) portal — Flussonic / raw-IP CDNs / high ports are the norm, not the
        // exception. The portal already authenticated and vouched for the resolved URL via
        // create_link, so we trust cleartext playback origins instead of gating them behind a
        // per-origin consent that can never be granted through the UI. The portal-origin
        // transport consent (StalkerTransportFactory) is unchanged.
        return null
    }

    private fun buildResolvedPlaybackInfo(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        adapter: StalkerPlaybackAdapter,
        resolvedUrl: String,
        kind: StalkerStreamKind,
        descriptor: StalkerPlaybackDescriptor,
        userAgent: String?
    ): StalkerPlaybackInfo = StalkerPlaybackInfo(
        url = resolvedUrl,
        headers = buildPlaybackHeaders(session, profile, resolvedUrl),
        userAgent = userAgent,
        transportPolicy = playbackTransportPolicyFor(resolvedUrl),
        allowInvalidSsl = false,
        proxyHost = profile.advancedOptions.proxy?.host.orEmpty(),
        proxyPort = profile.advancedOptions.proxy?.port,
        playbackMode = adapter.adapterMode,
        endpointPreference = effectiveArchiveEndpointPreference(kind, session),
        cookieMode = derivePlaybackCookieMode(
            current = effectiveArchiveCookieMode(kind, session, resolvedUrl),
            url = resolvedUrl
        ),
        backendHint = detectPlaybackBackendHint(resolvedUrl, descriptor.capabilities, adapter)
    )

    private fun resolvePlaybackUserAgent(profile: StalkerDeviceProfile): String? {
        profile.advancedOptions.playerUserAgent.trim().takeIf { it.isNotBlank() }?.let { return it }
        parseStalkerHeaderOverrides(profile.advancedOptions.playerHeaders).entries.firstOrNull { (name, _) ->
            name.equals("User-Agent", ignoreCase = true)
        }?.let { (_, value) -> return value }
        profile.headerOverrides.entries.firstOrNull { (name, _) ->
            name.equals("User-Agent", ignoreCase = true)
        }?.let { (_, value) -> return value }
        return profile.playerUserAgent.ifBlank { DEFAULT_PLAYER_USER_AGENT }
    }

    private fun resolveDirectPlaybackUserAgent(profile: StalkerDeviceProfile): String? {
        profile.advancedOptions.playerUserAgent.trim().takeIf { it.isNotBlank() }?.let { return it }
        parseStalkerHeaderOverrides(profile.advancedOptions.playerHeaders).entries.firstOrNull { (name, _) ->
            name.equals("User-Agent", ignoreCase = true)
        }?.let { (_, value) -> return value }
        profile.headerOverrides.entries.firstOrNull { (name, _) ->
            name.equals("User-Agent", ignoreCase = true)
        }?.let { (_, value) -> return value }
        profile.playerUserAgent.takeIf { it.isNotBlank() }?.let { return it }
        return profile.userAgent.ifBlank { DEFAULT_PLAYER_USER_AGENT }
    }

    private fun buildPlaybackHostHeader(url: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val isDefaultPort = when {
            uri.port == -1 -> true
            uri.scheme.equals("http", ignoreCase = true) && uri.port == 80 -> true
            uri.scheme.equals("https", ignoreCase = true) && uri.port == 443 -> true
            else -> false
        }
        return if (isDefaultPort) host else "$host:${uri.port}"
    }

    private fun shouldOmitPlaybackAuthorization(url: String): Boolean {
        val path = runCatching { URI(url).path?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        return path.endsWith("/play/live.php") || path.endsWith("/play/movie.php")
    }

    private fun buildPlaybackCookieHeader(
        serverCookieHeader: String,
        profile: StalkerDeviceProfile
    ): String {
        val cookies = linkedMapOf(
            "mac" to encodeCookieValue(profile.macAddress),
            "stb_lang" to encodeCookieValue(profile.locale),
            "timezone" to encodeCookieValue(profile.timezone)
        )
        serverCookieHeader.split(';')
            .mapNotNull { part ->
                val key = part.substringBefore('=', missingDelimiterValue = "").trim()
                val value = part.substringAfter('=', missingDelimiterValue = "").trim()
                key.takeIf { it.isNotBlank() && value.isNotBlank() }?.let { it to value }
            }.forEach { (key, value) ->
                cookies.putIfAbsent(key, value)
        }
        return cookies.entries.joinToString("; ") { (key, value) -> "$key=$value" }
    }

    private fun encodeCookieValue(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun derivePlaybackCookieMode(
        current: StalkerCookieMode,
        url: String
    ): StalkerCookieMode {
        val path = runCatching { URI(url).path?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        val playbackNeedsCookies = path.endsWith("/play/live.php") || path.endsWith("/play/movie.php")
        return when {
            playbackNeedsCookies && current == StalkerCookieMode.CREATE_LINK -> StalkerCookieMode.BOTH
            playbackNeedsCookies -> StalkerCookieMode.PLAYBACK
            else -> current
        }
    }

    private fun effectiveArchiveCookieMode(
        kind: StalkerStreamKind,
        session: StalkerSession,
        url: String
    ): StalkerCookieMode {
        val base = session.fingerprintEvidence.cookieMode
        if (kind != StalkerStreamKind.ARCHIVE) {
            return base
        }
        return when (base) {
            StalkerCookieMode.NONE -> StalkerCookieMode.PLAYBACK
            StalkerCookieMode.CREATE_LINK -> StalkerCookieMode.BOTH
            else -> derivePlaybackCookieMode(base, url)
        }
    }

    private fun effectiveArchiveEndpointPreference(
        kind: StalkerStreamKind,
        session: StalkerSession
    ): StalkerEndpointPreference =
        if (kind == StalkerStreamKind.ARCHIVE) {
            session.fingerprintEvidence.archiveEndpointPreference.takeUnless {
                it == StalkerEndpointPreference.AUTO
            } ?: session.fingerprintEvidence.endpointPreference
        } else {
            session.fingerprintEvidence.endpointPreference
        }

    private fun buildArchiveDirectCandidates(
        sourceUrl: String?,
        startSeconds: Long?,
        endSeconds: Long?
    ): List<String> {
        val normalizedSource = sourceUrl?.trim()?.takeIf { it.isNotBlank() } ?: return emptyList()
        val safeStart = startSeconds?.takeIf { it > 0L } ?: return listOf(normalizedSource)
        val safeEnd = endSeconds?.takeIf { it > safeStart } ?: return listOf(normalizedSource)
        val liveNow = maxOf(safeEnd, System.currentTimeMillis() / 1000L)
        val withUtc = appendArchiveQueryParameter(normalizedSource, "utc", safeStart.toString())
        val withLutc = appendArchiveQueryParameter(withUtc ?: normalizedSource, "lutc", liveNow.toString())
        return listOfNotNull(
            normalizedSource.takeIf { hasArchiveQueryHints(it) },
            withLutc
        ).distinct()
    }

    private fun hasArchiveQueryHints(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val query = uri.rawQuery?.lowercase(Locale.ROOT).orEmpty()
        return query.contains("utc=") ||
            query.contains("lutc=") ||
            query.contains("timeshift=") ||
            uri.path?.lowercase(Locale.ROOT)?.contains("timeshift") == true
    }

    private fun appendArchiveQueryParameter(url: String, name: String, value: String): String? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val rawQuery = uri.rawQuery
        val existingParts = rawQuery
            ?.split('&')
            ?.filter { it.isNotBlank() }
            .orEmpty()
            .toMutableList()
        val replaced = existingParts.map { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "")
            if (key.equals(name, ignoreCase = true)) {
                "$key=$value"
            } else {
                part
            }
        }.toMutableList()
        if (replaced.none { part ->
                part.substringBefore('=', missingDelimiterValue = "").equals(name, ignoreCase = true)
            }
        ) {
            replaced += "$name=$value"
        }
        val query = replaced.joinToString("&")
        return URI(uri.scheme, uri.authority, uri.path, query, uri.fragment).toString()
    }

    private fun detectPlaybackBackendHint(
        url: String,
        capabilities: StalkerPortalCapabilities,
        adapter: StalkerPlaybackAdapter
    ): StalkerPlaybackBackendHint {
        val path = runCatching { URI(url).path?.lowercase(Locale.ROOT).orEmpty() }.getOrDefault("")
        return when {
            path.endsWith("/play/live.php") -> StalkerPlaybackBackendHint.PLAY_LIVE
            path.endsWith("/play/movie.php") -> StalkerPlaybackBackendHint.PLAY_MOVIE
            adapter.adapterMode == StalkerPlaybackMode.TEMP_LINK_FLUSSONIC ||
                adapter.adapterMode == StalkerPlaybackMode.TEMP_LINK_WOWZA ||
                adapter.adapterMode == StalkerPlaybackMode.TEMP_LINK_NGINX ||
                adapter.adapterMode == StalkerPlaybackMode.PLAY_LIVE_PORTAL ||
                adapter.adapterMode == StalkerPlaybackMode.PLAY_MOVIE_PORTAL ->
                if (capabilities.nginxSecureLink || capabilities.useHttpTemporaryLink) {
                    StalkerPlaybackBackendHint.TEMP_LINK_STRICT
                } else {
                    StalkerPlaybackBackendHint.TEMP_LINK
                }

            else -> StalkerPlaybackBackendHint.DIRECT
        }
    }

    private fun extractDirectPlaybackUrl(cmd: String): String? {
        return cmd
            .substringAfter(' ', missingDelimiterValue = cmd)
            .trim()
            .takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
    }

    private fun shouldBypassCreateLink(kind: StalkerStreamKind, directUrl: String): Boolean {
        val parsed = runCatching { URI(directUrl) }.getOrNull() ?: return false
        val host = parsed.host?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (host.isBlank()) return false
        if (host == "localhost" || host == "127.0.0.1" || host == "0.0.0.0") return false
        if ((kind == StalkerStreamKind.LIVE || kind == StalkerStreamKind.ARCHIVE) &&
            parsed.isStalkerChannelCommandPath()
        ) {
            return false
        }
        if ((kind == StalkerStreamKind.LIVE || kind == StalkerStreamKind.ARCHIVE) && !hasUsableLiveStreamTarget(parsed)) return false

        return true
    }

    private fun repairCreateLinkUrl(
        kind: StalkerStreamKind,
        resolvedUrl: String,
        sourceDirectUrl: String?,
        archiveStartSeconds: Long? = null,
        archiveEndSeconds: Long? = null
    ): String {
        val repairedArchive = if (kind == StalkerStreamKind.ARCHIVE) {
            buildArchiveDirectCandidates(resolvedUrl, archiveStartSeconds, archiveEndSeconds).firstOrNull()
                ?: resolvedUrl
        } else {
            resolvedUrl
        }
        if (kind != StalkerStreamKind.LIVE || sourceDirectUrl.isNullOrBlank()) {
            return repairedArchive
        }

        val resolvedUri = runCatching { URI(repairedArchive) }.getOrNull() ?: return repairedArchive
        if (!isLivePlayPath(resolvedUri)) {
            return repairedArchive
        }
        val resolvedStreamId = resolvedUri.queryParameter("stream")?.takeIf { it.isUsableStreamId() }
        if (resolvedStreamId != null) {
            return repairedArchive
        }

        val sourceUri = runCatching { URI(sourceDirectUrl) }.getOrNull()
        val sourceStreamId = sourceUri?.liveStreamTargetId() ?: return repairedArchive
        return upsertQueryParameter(resolvedUri, "stream", sourceStreamId) ?: repairedArchive
    }

    private fun hasUsableLiveStreamTarget(uri: URI): Boolean {
        if (!isLivePlayPath(uri)) {
            return true
        }
        return uri.queryParameter("stream")?.isUsableStreamId() == true
    }

    private fun isLivePlayPath(uri: URI): Boolean =
        uri.path?.trim()?.lowercase(Locale.ROOT).orEmpty().endsWith("/play/live.php")

    private fun URI.liveStreamTargetId(): String? {
        queryParameter("stream")?.takeIf { it.isUsableStreamId() }?.let { return it }
        val path = path?.trim('/') ?: return null
        val segments = path.split('/').filter { it.isNotBlank() }
        val channelSegment = segments
            .dropLast(1)
            .zip(segments.drop(1))
            .firstOrNull { (previous, _) -> previous.equals("ch", ignoreCase = true) }
            ?.second
            ?: return null
        return channelSegment.trimEnd('_').takeIf { it.isUsableStreamId() }
    }

    private fun String.isUsableStreamId(): Boolean {
        val value = trim()
        return value.isNotBlank() &&
            value != "0" &&
            !value.equals("null", ignoreCase = true)
    }

    private fun livePlaybackTargetSummary(sourceDirectUrl: String?, resolvedUrl: String): String {
        val sourceUri = sourceDirectUrl?.let { runCatching { URI(it) }.getOrNull() }
        val resolvedUri = runCatching { URI(resolvedUrl) }.getOrNull()
        if (sourceUri == null && resolvedUri == null) {
            return "none"
        }
        val sourceTarget = sourceUri?.liveStreamTargetId().orEmpty()
        val resolvedTarget = resolvedUri?.takeIf(::isLivePlayPath)?.queryParameter("stream").orEmpty()
        return "source=${sourceTarget.ifBlank { "none" }} resolved=${resolvedTarget.ifBlank { "none" }}"
    }

    private fun URI.queryParameter(name: String): String? {
        val rawQuery = rawQuery ?: return null
        return rawQuery.split('&')
            .asSequence()
            .map { part ->
                val key = part.substringBefore('=', missingDelimiterValue = "")
                val value = part.substringAfter('=', missingDelimiterValue = "")
                key to value
            }
            .firstOrNull { (key, _) -> key.equals(name, ignoreCase = true) }
            ?.second
    }

    private fun upsertQueryParameter(uri: URI, name: String, value: String): String? {
        val rawQuery = uri.rawQuery.orEmpty()
        val parts = rawQuery.split('&')
            .filter { it.isNotBlank() }
        var replaced = false
        val updatedParts = parts.map { part ->
            val key = part.substringBefore('=', missingDelimiterValue = "")
            if (key.equals(name, ignoreCase = true)) {
                replaced = true
                "$key=$value"
            } else {
                part
            }
        }
        val updated = if (replaced) {
            updatedParts
        } else {
            updatedParts + "$name=$value"
        }.joinToString("&")
        return URI(uri.scheme, uri.authority, uri.path, updated, uri.fragment).toString()
    }

    private suspend fun resolveRawCategoryId(type: ContentType, categoryId: Long?): String? {
        val normalizedType = when (type) {
            ContentType.SERIES_EPISODE -> ContentType.SERIES
            else -> type
        }
        val targetId = categoryId ?: return null
        // A populated cache that misses must not short-circuit: fall through to the persisted
        // identity map and the live category fetch before giving up.
        categoryCache[normalizedType]?.firstOrNull { it.id == targetId }?.rawId?.let { return it }
        identityResolver?.reverse(providerId, normalizedType, targetId)?.let { return it }
        when (val categoriesResult = when (normalizedType) {
            ContentType.LIVE -> getLiveCategories()
            ContentType.VOD -> getVodCategories()
            ContentType.MOVIE -> getVodCategories()
            ContentType.SERIES -> getSeriesCategories()
            ContentType.SERIES_EPISODE -> Result.success(emptyList())
        }) {
            is Result.Success -> {
                categoryCache[normalizedType]?.firstOrNull { it.id == targetId }?.rawId?.let { return it }
            }
            else -> Unit
        }
        // Last resort for portals whose stored category ids are the portal's own numeric ids
        // while the identity map is keyed to another epoch's surrogates: numeric ids pass
        // through to the portal unchanged. Synthetic surrogate ids (>= the high floor) are
        // never sent because the portal cannot resolve them.
        return targetId.takeIf { it in 1L until FALLBACK_SURROGATE_FLOOR }?.toString()
    }

    /**
     * Resolves a portal-relative URL (e.g. `/stalker_portal/screenshots/.../X.png`)
     * into an absolute URL using the portal origin. Ministra portals return
     * `screenshot_uri` as a site-relative path; without this, image loaders cannot
     * resolve the poster/backdrop/cover.
     */
    private fun resolvePortalUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url
        if (!url.startsWith("/")) return url
        return StalkerLogoUrlResolver.resolvePortalUrl(portalUrl, url)
    }

    /**
     * Resolves a channel logo URL into an absolute URL. Some Ministra portals return the
     * channel `logo` field as a bare filename (`536.png`) that lives under the portal's
     * `misc/logos/{size}/` directory — the convention STBEmu uses when it requests
     * `/stalker_portal/misc/logos/120/536.png`. See [StalkerLogoUrlResolver].
     */
    private fun resolveChannelLogoUrl(url: String?): String? =
        StalkerLogoUrlResolver.resolveChannelLogoUrl(portalUrl, url)

    private fun toChannel(item: StalkerItemRecord): Channel? {
        val numericId = stableItemId(ContentType.LIVE, item.id)
        val category = resolveCategory(ContentType.LIVE, item.categoryId, item.categoryName)
        val directStreamUrl = item.streamUrl
            ?.substringAfter(' ', missingDelimiterValue = item.streamUrl)
            ?.trim()
            ?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
        val streamUrl = item.cmd?.takeIf { it.isNotBlank() }?.let { cmd ->
            StalkerUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = StalkerStreamKind.LIVE,
                itemId = numericId,
                cmd = cmd,
                containerExtension = item.containerExtension,
                playbackDescriptor = item.playbackDescriptor
            )
        } ?: directStreamUrl
            ?: return null
        val resolvedName = item.name.ifBlank { "Channel $numericId" }
        val catchUpSupported = item.archiveAvailable == true ||
            item.portalCapabilities.archiveAvailable ||
            item.allowLocalTimeshift == true ||
            item.allowLocalPvr == true ||
            item.allowRemotePvr == true
        return Channel(
            id = 0L,
            name = resolvedName,
            logoUrl = resolveChannelLogoUrl(item.logoUrl),
            categoryId = category.id,
            categoryName = category.name,
            streamUrl = streamUrl,
            epgChannelId = item.epgChannelId ?: item.id,
            number = item.number.coerceAtLeast(0),
            catchUpSupported = catchUpSupported,
            catchUpDays = 0,
            catchUpSource = streamUrl.takeIf { catchUpSupported },
            providerId = providerId,
            isAdult = item.isAdult || AdultContentClassifier.isAdultCategoryName(category.name),
            isUserProtected = false,
            logicalGroupId = ChannelNormalizer.getLogicalGroupId(resolvedName, providerId),
            streamId = numericId
        )
    }

    private fun toMovie(
        item: StalkerItemRecord,
        requestedCategoryId: Long? = null,
        categoryType: ContentType = ContentType.MOVIE
    ): Movie? {
        if (item.isSeries) return null
        if (!item.hasSeriesMarker && missingVodClassificationLogged.add(providerId)) {
            trimSharedCaches()
            StalkerTelemetry.missingVodClassification(providerId)
        }
        val numericId = stableItemId(ContentType.MOVIE, item.id)
        val category = requestedCategorySeed(
            type = categoryType,
            categoryId = requestedCategoryId,
            fallbackName = item.categoryName
        )
            ?: resolveCategory(categoryType, item.categoryId, item.categoryName)
        val directStreamUrl = item.streamUrl
            ?.substringAfter(' ', missingDelimiterValue = item.streamUrl)
            ?.trim()
            ?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
        val streamUrl = item.cmd?.takeIf { it.isNotBlank() }?.let { cmd ->
            StalkerUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = StalkerStreamKind.MOVIE,
                itemId = numericId,
                cmd = cmd,
                containerExtension = item.containerExtension,
                playbackDescriptor = item.playbackDescriptor
            )
        } ?: directStreamUrl
            ?: return null
        return Movie(
            id = 0L,
            name = item.name.ifBlank { "Movie $numericId" },
            posterUrl = resolvePortalUrl(item.logoUrl),
            backdropUrl = resolvePortalUrl(item.backdropUrl),
            categoryId = category.id,
            categoryName = category.name,
            streamUrl = streamUrl,
            containerExtension = item.containerExtension,
            plot = item.plot,
            cast = item.cast,
            director = item.director,
            genre = item.genre,
            releaseDate = item.releaseDate,
            rating = item.rating.coerceIn(0f, 10f),
            tmdbId = item.tmdbId,
            youtubeTrailer = item.youtubeTrailer,
            providerId = providerId,
            isAdult = item.isAdult || AdultContentClassifier.isAdultCategoryName(category.name),
            isUserProtected = false,
            streamId = numericId,
            addedAt = item.addedAt
        )
    }

    private fun requestedCategorySeed(
        type: ContentType,
        categoryId: Long?,
        fallbackName: String? = null
    ): CategorySeed? {
        val targetId = categoryId ?: return null
        val normalizedType = when (type) {
            ContentType.SERIES_EPISODE -> ContentType.SERIES
            else -> type
        }
        return categoryCache[normalizedType]?.firstOrNull { category ->
            category.id == targetId || category.rawId.toLongOrNull() == targetId
        } ?: CategorySeed(
            id = targetId,
            rawId = targetId.toString(),
            name = fallbackName?.trim().takeUnless { it.isNullOrBlank() } ?: "Category $targetId"
        )
    }

    private fun toSeries(
        item: StalkerItemRecord,
        requestedCategoryId: Long? = null,
        categoryType: ContentType = ContentType.SERIES,
        origin: SeriesCatalogOrigin = SeriesCatalogOrigin.NATIVE
    ): Series? {
        val numericId = stableItemId(ContentType.SERIES, item.id)
        val category = requestedCategorySeed(
            type = categoryType,
            categoryId = requestedCategoryId,
            fallbackName = item.categoryName
        ) ?: resolveCategory(categoryType, item.categoryId, item.categoryName)
        return Series(
            id = 0L,
            name = item.name.ifBlank { "Series $numericId" },
            posterUrl = resolvePortalUrl(item.logoUrl),
            backdropUrl = resolvePortalUrl(item.backdropUrl),
            categoryId = category.id,
            categoryName = category.name,
            plot = item.plot,
            cast = item.cast,
            director = item.director,
            genre = item.genre,
            releaseDate = item.releaseDate,
            rating = item.rating.coerceIn(0f, 10f),
            tmdbId = item.tmdbId,
            youtubeTrailer = item.youtubeTrailer,
            providerId = providerId,
            isAdult = item.isAdult || AdultContentClassifier.isAdultCategoryName(category.name),
            isUserProtected = false,
            lastModified = item.addedAt,
            seriesId = item.id.toLongOrNull() ?: numericId,
            providerSeriesId = item.id,
            catalogOrigin = origin,
            episodePlaybackTemplateUrl = buildEpisodePlaybackTemplate(item, numericId)
        )
    }

    private fun buildEpisodePlaybackTemplate(item: StalkerItemRecord, numericId: Long): String? {
        val command = item.cmd?.takeIf(String::isNotBlank)
            ?: item.streamUrl?.takeIf(String::isNotBlank)
            ?: return null
        return StalkerUrlFactory.buildInternalStreamUrl(
            providerId = providerId,
            kind = StalkerStreamKind.EPISODE,
            itemId = numericId,
            cmd = command,
            containerExtension = item.containerExtension,
            playbackDescriptor = item.playbackDescriptor
                ?: buildStalkerPlaybackDescriptor(
                    primaryCmd = command,
                    alternateCommands = item.commandVariants.map { it.sourceKey to it.cmd },
                    capabilities = item.portalCapabilities
                )
        )
    }

    private fun StalkerSeriesDetails.toSeries(
        catalogOrigin: SeriesCatalogOrigin,
        episodePlaybackTemplateUrl: String?
    ): Series {
        val mappedSeries = toSeries(series, origin = catalogOrigin)
        val baseSeries = if (mappedSeries != null) {
            mappedSeries.copy(
                name = series.name,
                episodePlaybackTemplateUrl = mappedSeries.episodePlaybackTemplateUrl
                    ?: episodePlaybackTemplateUrl
            )
        } else {
            Series(
                id = 0L,
                name = series.name,
                providerId = providerId,
                seriesId = series.id.toLongOrNull() ?: stableItemId(ContentType.SERIES, series.id),
                providerSeriesId = series.id,
                catalogOrigin = catalogOrigin,
                episodePlaybackTemplateUrl = episodePlaybackTemplateUrl
            )
        }
        val mappedSeasons = seasons.map { season ->
                val episodes = season.episodes.mapIndexed { index, episode ->
                    episode.toEpisode(
                        fallbackSeriesId = baseSeries.seriesId,
                        fallbackSeasonNumber = season.seasonNumber,
                        fallbackEpisodeNumber = index + 1,
                        seasonCmd = season.cmd,
                        parentTemplateUrl = baseSeries.episodePlaybackTemplateUrl
                    )
                }
                Season(
                    seasonNumber = season.seasonNumber.coerceAtLeast(0),
                    name = season.name.ifBlank { "Season ${season.seasonNumber}" },
                    coverUrl = season.coverUrl,
                    episodes = episodes,
                    episodeCount = episodes.size
                )
            }
        return baseSeries.copy(seasons = mappedSeasons)
    }

    private fun StalkerEpisodeRecord.toEpisode(
        fallbackSeriesId: Long,
        fallbackSeasonNumber: Int,
        fallbackEpisodeNumber: Int,
        seasonCmd: String?,
        parentTemplateUrl: String?
    ): Episode {
        val numericId = stableItemId(ContentType.SERIES_EPISODE, id)
        val effectiveCmd = cmd?.takeIf(String::isNotBlank) ?: seasonCmd?.takeIf(String::isNotBlank)
        val directStreamUrl = effectiveCmd
            ?.substringAfter(' ', missingDelimiterValue = effectiveCmd)
            ?.trim()
            ?.takeIf(UrlSecurityPolicy::isAllowedStreamEntryUrl)
        val resolvedStreamUrl = effectiveCmd?.let { resolvedCmd ->
            StalkerUrlFactory.buildInternalStreamUrl(
                providerId = providerId,
                kind = StalkerStreamKind.EPISODE,
                itemId = numericId,
                cmd = resolvedCmd,
                containerExtension = containerExtension,
                seriesNumber = playbackSelector
                    ?: seasonShellEpisodeSelector(resolvedCmd, episodeNumber),
                playbackDescriptor = buildStalkerPlaybackDescriptor(
                    primaryCmd = resolvedCmd,
                    capabilities = StalkerPortalCapabilities()
                )
            )
        } ?: parentTemplateUrl
            ?.let(StalkerUrlFactory::parseInternalStreamUrl)
            ?.let { template ->
                StalkerUrlFactory.buildInternalStreamUrl(
                    providerId = providerId,
                    kind = StalkerStreamKind.EPISODE,
                    itemId = numericId,
                    cmd = template.cmd,
                    containerExtension = containerExtension ?: template.containerExtension,
                    seriesNumber = playbackSelector ?: episodeNumber.takeIf { it > 0 },
                    playbackDescriptor = template.playbackDescriptor
                )
            }
            ?: directStreamUrl.orEmpty()
        return Episode(
            id = numericId,
            title = title.ifBlank { "Episode $fallbackEpisodeNumber" },
            episodeNumber = episodeNumber.coerceAtLeast(1),
            seasonNumber = seasonNumber.takeIf { it > 0 } ?: fallbackSeasonNumber.coerceAtLeast(1),
            streamUrl = resolvedStreamUrl,
            containerExtension = containerExtension,
            coverUrl = coverUrl,
            plot = plot,
            durationSeconds = durationSeconds.coerceAtLeast(0),
            rating = rating.coerceIn(0f, 10f),
            releaseDate = releaseDate,
            seriesId = fallbackSeriesId,
            providerId = providerId,
            isAdult = false,
            isUserProtected = false,
            episodeId = id.toLongOrNull() ?: numericId
        )
    }

    private fun seasonShellEpisodeSelector(cmd: String, episodeNumber: Int): Int? {
        if (episodeNumber <= 0) {
            return null
        }
        val decoded = runCatching {
            String(Base64.getDecoder().decode(cmd.trim()), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val normalized = decoded.lowercase(Locale.ROOT)
        if (!normalized.contains("\"type\":\"series\"")) {
            return null
        }
        if (!normalized.contains("\"season_num\"") && !normalized.contains("\"season_id\"")) {
            return null
        }
        if (normalized.contains("\"episode_number\"") || normalized.contains("\"series_number\"")) {
            return null
        }
        return episodeNumber
    }

    private fun StalkerProgramRecord.toProgram(): Program =
        Program(
            id = id.toLongOrNull() ?: stableItemId(ContentType.LIVE, id),
            channelId = channelId,
            title = title,
            description = description,
            startTime = startTimeMillis,
            endTime = endTimeMillis,
            hasArchive = hasArchive,
            isNowPlaying = isNowPlaying,
            providerId = providerId
        )

    private fun resolveCategory(type: ContentType, rawId: String?, rawName: String?): CategorySeed {
        val normalizedName = rawName?.trim().takeUnless { it.isNullOrBlank() }
        val normalizedRawId = rawId?.trim().takeUnless { it.isNullOrBlank() }
        val cached = categoryCache[type]
            ?.firstOrNull { category ->
                category.rawId == normalizedRawId ||
                    (normalizedName != null && category.name.equals(normalizedName, ignoreCase = true))
            }
        if (cached != null) {
            return cached
        }
        val fallbackSeed = normalizedRawId ?: normalizedName ?: "uncategorized"
        return CategorySeed(
            id = syntheticCategoryId(type, fallbackSeed),
            rawId = normalizedRawId ?: fallbackSeed,
            name = normalizedName ?: "Category $fallbackSeed"
        )
    }

    private fun stableItemId(type: ContentType, rawId: String): Long =
        remoteIdentityCache[type to rawId.trim()]
            ?: rawId.trim().toLongOrNull()?.takeIf { it > 0L }
            ?: fallbackStableId(type, rawId)

    private fun syntheticCategoryId(type: ContentType, seed: String): Long {
        remoteIdentityCache[type to seed.trim()]?.let { return it }
        return fallbackStableId(type, seed)
    }

    /**
     * Test/fallback path used when a provider is created without the persistent resolver.
     * Keep the SHA-derived value for stability, but resolve collisions in this provider
     * instance instead of allowing two remote records to share a local Long ID.
     */
    private fun fallbackStableId(type: ContentType, rawId: String): Long {
        val normalized = rawId.trim()
        val key = type to normalized
        return synchronized(remoteIdentityCache) {
            remoteIdentityCache[key] ?: run {
                val preferred = stalkerStableHashId(providerId, type, normalized)
                val used = remoteIdentityCache
                    .filterKeys { (existingType, _) -> existingType == type }
                    .values
                    .toSet()
                val candidate = if (preferred !in used) {
                    preferred
                } else {
                    generateSequence(FALLBACK_SURROGATE_FLOOR) { it + 1L }
                        .first { it !in used }
                }
                putRemoteIdentity(key, candidate)
                candidate
            }
        }
    }

    private fun putRemoteIdentity(key: Pair<ContentType, String>, value: Long) {
        remoteIdentityCache[key] = value
        if (remoteIdentityCache.size > MAX_REMOTE_IDENTITY_CACHE_ENTRIES) {
            remoteIdentityCache.keys.take(remoteIdentityCache.size - MAX_REMOTE_IDENTITY_CACHE_ENTRIES)
                .forEach(remoteIdentityCache::remove)
        }
    }

    private fun normalizedMacAddress(): String =
        macAddress.trim().uppercase(Locale.ROOT)

    private fun normalizedUsername(): String =
        username.trim()

    private fun normalizedPassword(): String =
        password

    private fun normalizedDeviceProfile(): String =
        deviceProfile.trim().ifBlank { "MAG250" }

    private fun normalizedTimezone(): String =
        timezone.trim().ifBlank { java.util.TimeZone.getDefault().id }

    private fun normalizedLocale(): String =
        locale.trim().ifBlank { Locale.getDefault().language.ifBlank { "en" } }

    private fun normalizedSerialNumber(): String =
        serialNumber.trim().uppercase(Locale.ROOT)

    private fun normalizedDeviceId(): String =
        deviceId.trim().uppercase(Locale.ROOT)

    private fun normalizedDeviceId2(): String =
        deviceId2.trim().uppercase(Locale.ROOT)

    private fun normalizedSignature(): String =
        signature.trim().uppercase(Locale.ROOT)

    private fun authCacheKey(): String {
        val normalized = listOf(
            System.identityHashCode(api).toString(),
            providerId.toString(),
            StalkerUrlFactory.normalizePortalUrl(portalUrl),
            normalizedMacAddress(),
            authMode.name,
            normalizedUsername(),
            normalizedPassword(),
            httpUserAgent.trim(),
            httpHeaders,
            portalFingerprintHint.name,
            magPresetHint.name,
            bootstrapRecipeHint.name,
            endpointPreferenceHint.name,
            cookieModeHint.name,
            playbackBackendHint.name,
            portalProfileHint.name,
            preferredPlaybackMode?.name.orEmpty(),
            normalizedDeviceProfile(),
            normalizedTimezone(),
            normalizedLocale(),
            normalizedSerialNumber(),
            normalizedDeviceId(),
            normalizedDeviceId2(),
            normalizedSignature(),
            stalkerAdvancedOptionsJson
        ).joinToString(separator = "\u001f")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "provider:$providerId|$digest"
    }

    /**
     * Stable cross-instance session identity. Unlike [authCacheKey], this deliberately excludes
     * volatile learned hints (fingerprint/recipe/endpoint/cookie/playback/profile/device extras)
     * so a sync-side provider built from persisted learning can reuse the activation session
     * instead of firing a second handshake into the portal rate limiter.
     */
    private fun portalIdentityKey(): String {
        val normalized = listOf(
            System.identityHashCode(api).toString(),
            providerId.toString(),
            StalkerUrlFactory.normalizePortalUrl(portalUrl),
            normalizedMacAddress(),
            authMode.name,
            normalizedUsername(),
            normalizedPassword()
        ).joinToString(separator = "\u001f")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "portal:$providerId|$digest"
    }

    private fun authMutexKey(): String = "provider:$providerId|auth"

    private fun resolveProviderStatus(profile: StalkerProviderProfile): ProviderStatus {
        val normalizedStatus = profile.statusLabel?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (normalizedStatus in setOf("disabled", "blocked", "banned")) {
            return ProviderStatus.DISABLED
        }
        val expirationDate = profile.expirationDate
        if (expirationDate != null && expirationDate in 1 until System.currentTimeMillis()) {
            return ProviderStatus.EXPIRED
        }
        if (normalizedStatus in setOf("active", "enabled", "1")) {
            return ProviderStatus.ACTIVE
        }
        if (normalizedStatus == "0" || profile.authAccess == false || profile.ambiguousState) {
            return ProviderStatus.PARTIAL
        }
        return ProviderStatus.UNKNOWN
    }

    private fun isAuthorizationFailure(message: String, exception: Throwable?): Boolean {
        return com.streamvault.data.remote.stalker.isStalkerAuthorizationFailure(message, exception)
    }

    private fun validateArchiveWindow(
        kind: StalkerStreamKind,
        archiveStartSeconds: Long?,
        archiveEndSeconds: Long?
    ): String? {
        if (kind != StalkerStreamKind.ARCHIVE) {
            return null
        }
        val safeStart = archiveStartSeconds?.takeIf { it > 0L }
            ?: return "Archive playback requires a valid start time."
        val safeEnd = archiveEndSeconds?.takeIf { it > safeStart }
            ?: return "Archive playback requires an end time after the start time."
        val maxWindowSeconds = 7L * 24L * 60L * 60L
        return if (safeEnd - safeStart > maxWindowSeconds) {
            "Archive playback window is too large for a single request."
        } else {
            null
        }
    }
}

private inline fun <T, R> Result<T>.mapData(transform: (T) -> R): Result<R> = when (this) {
    is Result.Success -> Result.success(transform(data))
    is Result.Error -> Result.error(message, exception)
    is Result.Loading -> Result.error("Unexpected loading state")
}
