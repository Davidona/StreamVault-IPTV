package com.streamvault.data.remote.stalker

import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.StalkerPortalProfile
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerProfileVerification
import com.streamvault.domain.model.StalkerProtocolFamily
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.DiscoveryBudget
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class StalkerDeviceProfile(
    val portalUrl: String,
    val macAddress: String,
    val authMode: StalkerAuthMode,
    val magPreset: StalkerMagPreset,
    val portalFingerprint: StalkerPortalFingerprint,
    val bootstrapRecipe: StalkerBootstrapRecipe,
    val endpointPreference: StalkerEndpointPreference,
    val cookieMode: StalkerCookieMode,
    val playbackBackendHint: StalkerPlaybackBackendHint,
    val username: String,
    val password: String,
    val deviceProfile: String,
    val timezone: String,
    val locale: String,
    val serialNumber: String,
    val deviceId: String,
    val deviceId2: String,
    val signature: String,
    val userAgent: String,
    val playerUserAgent: String,
    val xUserAgent: String,
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val headerOverrides: Map<String, String?> = emptyMap(),
    val advancedOptions: StalkerAdvancedOptions = StalkerAdvancedOptions(),
    val providerId: Long = 0L,
    /** Authentication generation used to isolate cookies and learned endpoint state. */
    val authEpoch: Long = 0L,
    val protocolPreference: StalkerProtocolPreference = StalkerProtocolPreference.AUTO,
    val transportGrant: StalkerTransportGrant? = null,
    val requestedProfileId: String = StalkerCompatibilityProfileIds.AUTO,
    val compatibilityProfileId: String = StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC,
    val requireCatalogValidation: Boolean = false,
    /** Broad profile/endpoint discovery is reserved for explicit Add/Edit/Connect operations. */
    val allowCompatibilityDiscovery: Boolean = true,
    val discoveryBudget: DiscoveryBudget = DiscoveryBudget(),
    val discoveryRuntime: StalkerDiscoveryRuntime = StalkerDiscoveryRuntime(discoveryBudget),
    val onProgress: ((String) -> Unit)? = null
)

class StalkerDiscoveryRuntime(
    private val budget: DiscoveryBudget
) {
    private val active = AtomicBoolean(false)
    private val requests = AtomicInteger(0)
    @Volatile private var startedAtNanos: Long = 0L

    fun begin() {
        requests.set(0)
        startedAtNanos = System.nanoTime()
        active.set(true)
    }

    fun end() {
        active.set(false)
    }

    fun consumeRequest() {
        if (!active.get()) return
        val request = requests.incrementAndGet()
        if (request > budget.maxRequests) {
            throw StalkerApiError.DiscoveryBudgetExceeded(
                "Portal discovery exceeded its ${budget.maxRequests}-request budget."
            )
        }
        val elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L
        if (elapsedMillis > budget.maxElapsedMillis) {
            throw StalkerApiError.DiscoveryBudgetExceeded(
                "Portal discovery exceeded its ${budget.maxElapsedMillis}ms time budget."
            )
        }
    }

    val requestCount: Int
        get() = requests.get()

    fun remainingMillis(): Long {
        if (!active.get()) return Long.MAX_VALUE
        val elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L
        return (budget.maxElapsedMillis - elapsedMillis).coerceAtLeast(1L)
    }
}

data class StalkerSession(
    val loadUrl: String,
    val portalReferer: String,
    val token: String,
    val sessionScopeKey: String = "",
    val authEpoch: Long = 0L,
    val authenticatedAtMillis: Long = System.currentTimeMillis(),
    val expiresAtMillis: Long? = null,
    val serverCookieHeader: String = "",
    val effectiveAuthMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    val portalProfile: StalkerPortalProfile = StalkerPortalProfile.MAG_BASIC,
    val portalFingerprint: StalkerPortalFingerprint = StalkerPortalFingerprint.BASIC_MAC,
    val magPreset: StalkerMagPreset = StalkerMagPreset.GENERIC_SAFE,
    val bootstrapRecipe: StalkerBootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
    val fingerprintEvidence: StalkerFingerprintEvidence = StalkerFingerprintEvidence(),
    val bootstrapEvidence: List<String> = emptyList(),
    val recipeEvidence: List<String> = emptyList(),
    val rediscoveryAttempted: Boolean = false,
    val compatibilityProfileId: String = StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC
) {
    fun isExpired(nowMillis: Long = System.currentTimeMillis()): Boolean =
        nowMillis >= (expiresAtMillis ?: (authenticatedAtMillis + STALKER_SESSION_MAX_AGE_MILLIS))
}

internal const val STALKER_SESSION_MAX_AGE_MILLIS = 30L * 60L * 1000L

data class StalkerFingerprintEvidence(
    val endpointPreference: StalkerEndpointPreference = StalkerEndpointPreference.AUTO,
    val cookieMode: StalkerCookieMode = StalkerCookieMode.NONE,
    val playbackBackendHint: StalkerPlaybackBackendHint = StalkerPlaybackBackendHint.AUTO,
    val localizationRequired: Boolean = false,
    val modulesRequired: Boolean = false,
    val alternateEndpointAccepted: Boolean = false,
    val genericPresetRejected: Boolean = false,
    val strictPresetAccepted: Boolean = false,
    val archiveViaCreateLink: Boolean = false,
    val archiveViaDirectUrl: Boolean = false,
    val archiveRequiresBootstrapPrep: Boolean = false,
    val archiveRequiresStrictCookies: Boolean = false,
    val archiveEndpointPreference: StalkerEndpointPreference = StalkerEndpointPreference.AUTO
)

data class StalkerProviderProfile(
    val accountId: String? = null,
    val accountName: String? = null,
    val maxConnections: Int? = null,
    val expirationDate: Long? = null,
    val statusLabel: String? = null,
    val authAccess: Boolean? = null,
    /** Model reported by the portal for the bound account, when middleware exposes it. */
    val reportedStbType: String? = null,
    val moduleNames: List<String> = emptyList(),
    val bootstrapStrategy: StalkerBootstrapStrategy = StalkerBootstrapStrategy.AUTO,
    val effectiveAuthMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    val portalProfile: StalkerPortalProfile = StalkerPortalProfile.MAG_BASIC,
    val portalFingerprint: StalkerPortalFingerprint = StalkerPortalFingerprint.BASIC_MAC,
    val magPreset: StalkerMagPreset = StalkerMagPreset.GENERIC_SAFE,
    val bootstrapRecipe: StalkerBootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
    val fingerprintEvidence: StalkerFingerprintEvidence = StalkerFingerprintEvidence(),
    val portalCapabilities: StalkerPortalCapabilities = StalkerPortalCapabilities(),
    val credentialRequired: Boolean = false,
    val macRequired: Boolean = true,
    val bootstrapEvidence: List<String> = emptyList(),
    val recipeEvidence: List<String> = emptyList(),
    val strictFingerprintRequired: Boolean = false,
    val fallbackRecipeUsed: Boolean = false,
    val rediscoveryAttempted: Boolean = false,
    val ambiguousState: Boolean = false,
    val compatibilityProfileId: String = StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC,
    val profileRevision: Int = StalkerCompatibilityRegistry.REVISION,
    val profileVerification: StalkerProfileVerification = StalkerProfileVerification.UNVERIFIED,
    val protocolFamily: StalkerProtocolFamily = StalkerProtocolFamily.CLASSIC_MAG
)

data class StalkerCategoryRecord(
    val id: String,
    val name: String,
    val alias: String? = null,
    val advertisedItemCount: Int? = null
)

data class StalkerItemRecord(
    val id: String,
    val name: String,
    val categoryId: String? = null,
    val categoryName: String? = null,
    val number: Int = 0,
    val logoUrl: String? = null,
    val epgChannelId: String? = null,
    val cmd: String? = null,
    val streamUrl: String? = null,
    val plot: String? = null,
    val cast: String? = null,
    val director: String? = null,
    val genre: String? = null,
    val releaseDate: String? = null,
    val rating: Float = 0f,
    val tmdbId: Long? = null,
    val youtubeTrailer: String? = null,
    val backdropUrl: String? = null,
    val containerExtension: String? = null,
    val playbackDescriptor: StalkerPlaybackDescriptor? = null,
    val commandVariants: List<StalkerCommandVariant> = emptyList(),
    val portalCapabilities: StalkerPortalCapabilities = StalkerPortalCapabilities(),
    val mcCmd: String? = null,
    val useHttpTemporaryLink: Boolean? = null,
    val nginxSecureLink: Boolean? = null,
    val flussonicTemporaryLink: Boolean? = null,
    val wowzaTemporaryLink: Boolean? = null,
    val useLoadBalancing: Boolean? = null,
    val allowLocalTimeshift: Boolean? = null,
    val allowLocalPvr: Boolean? = null,
    val allowRemotePvr: Boolean? = null,
    val archiveAvailable: Boolean? = null,
    val addedAt: Long = 0L,
    val isAdult: Boolean = false,
    val isSeries: Boolean = false,
    val hasSeriesMarker: Boolean = false
)

data class StalkerPagedItems(
    val items: List<StalkerItemRecord>,
    val page: Int,
    val totalPages: Int,
    val pageSize: Int,
    val advertisedTotalItems: Int? = null,
    val advertisedTotalPages: Int? = null,
    /** True when the portal supplied a reliable total-page hint for this response. */
    val hasAdvertisedTotal: Boolean = true,
    val isTruncated: Boolean = false,
    val terminationReason: String? = null
) {
    /**
     * A response without totals is complete only when it is empty. A non-empty page with
     * missing pagination metadata must be continued until an empty page or a safety boundary.
     */
    val isComplete: Boolean
        get() = !isTruncated && if (hasAdvertisedTotal) page >= totalPages else items.isEmpty()
}

data class StalkerSeriesDetails(
    val series: StalkerItemRecord,
    val seasons: List<StalkerSeasonRecord>,
    val paginationEvidence: List<StalkerEpisodePaginationEvidence> = emptyList()
)

data class StalkerEpisodePaginationEvidence(
    val seasonSelector: String,
    val attemptedPages: Int,
    val successfulPages: Int,
    val advertisedTotalPages: Int?,
    val repeatedPageDetected: Boolean = false,
    val malformedPagination: Boolean = false,
    val pageLimitReached: Boolean = false
)

data class StalkerSeasonRecord(
    val seasonNumber: Int,
    val name: String,
    val coverUrl: String? = null,
    val cmd: String? = null,
    val episodes: List<StalkerEpisodeRecord>
)

data class StalkerEpisodeRecord(
    val id: String,
    val title: String,
    val episodeNumber: Int,
    val seasonNumber: Int,
    val cmd: String? = null,
    val playbackSelector: Int? = null,
    val coverUrl: String? = null,
    val plot: String? = null,
    val durationSeconds: Int = 0,
    val releaseDate: String? = null,
    val rating: Float = 0f,
    val containerExtension: String? = null
)

data class StalkerProgramRecord(
    val id: String,
    val channelId: String,
    val title: String,
    val description: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val hasArchive: Boolean = false,
    val isNowPlaying: Boolean = false
)

interface StalkerApiService {
    suspend fun authenticate(profile: StalkerDeviceProfile): Result<Pair<StalkerSession, StalkerProviderProfile>>

    /** Removes all in-memory transport/session state owned by one provider. */
    fun invalidateSessionScopes(providerId: Long) = Unit

    suspend fun getLiveCategories(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): Result<List<StalkerCategoryRecord>>

    suspend fun getLiveStreams(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?
    ): Result<List<StalkerItemRecord>>

    suspend fun streamLiveStreams(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        onItem: suspend (StalkerItemRecord) -> Unit
    ): Result<Int>

    suspend fun getVodCategories(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): Result<List<StalkerCategoryRecord>>

    suspend fun getVodStreams(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?
    ): Result<List<StalkerItemRecord>>

    suspend fun getVodStreamsPage(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?,
        page: Int,
        searchQuery: String? = null
    ): Result<StalkerPagedItems>

    suspend fun getSeriesCategories(
        session: StalkerSession,
        profile: StalkerDeviceProfile
    ): Result<List<StalkerCategoryRecord>>

    suspend fun getSeries(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?
    ): Result<List<StalkerItemRecord>>

    suspend fun getSeriesPage(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        categoryId: String?,
        page: Int
    ): Result<StalkerPagedItems>

    suspend fun getSeriesDetails(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        seriesId: String
    ): Result<StalkerSeriesDetails>

    suspend fun getVodSeriesDetails(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        seriesId: String
    ): Result<StalkerSeriesDetails> = getSeriesDetails(session, profile, seriesId)

    suspend fun getShortEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        channelId: String,
        limit: Int
    ): Result<List<StalkerProgramRecord>>

    suspend fun getEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        channelId: String
    ): Result<List<StalkerProgramRecord>>

    suspend fun getBulkEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        periodHours: Int = 6
    ): Result<List<StalkerProgramRecord>>

    /**
     * Streams the bulk Stalker EPG payload one program at a time.
     *
     * Many portals return a single very large JSON body for `get_epg_info` regardless of `period`/`ch_id`.
     * Building a full Gson tree of that payload is what causes the EPG OOM on TV devices, so this path
     * walks the response with `JsonReader` and emits records via [onProgram] without ever holding the
     * full payload in memory.
     */
    suspend fun streamBulkEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        periodHours: Int = 6,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Result<Int>

    /**
     * Streams a per-channel Stalker EPG response. Mirrors [streamBulkEpg]; the [channelId] is forwarded
     * as the portal's `ch_id` query parameter.
     */
    suspend fun streamEpg(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        channelId: String,
        periodHours: Int = 6,
        onProgram: suspend (StalkerProgramRecord) -> Unit
    ): Result<Int>

    suspend fun createLink(
        session: StalkerSession,
        profile: StalkerDeviceProfile,
        kind: StalkerStreamKind,
        cmd: String,
        seriesNumber: Int? = null,
        archiveStartSeconds: Long? = null,
        archiveEndSeconds: Long? = null
    ): Result<String>

    fun currentCookieHeader(session: StalkerSession): String = session.serverCookieHeader
}
