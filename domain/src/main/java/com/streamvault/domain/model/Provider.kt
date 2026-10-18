package com.streamvault.domain.model

/**
 * Compatibility envelope for legacy callers, revisions, and backup formats.
 *
 * New persistence and provider execution must use [ProviderSnapshot] so configuration and
 * generation-bound observations cannot leak back into a flattened union model.
 */
data class LegacyProvider(
    val id: Long = 0,
    val name: String,
    val type: ProviderType,
    val serverUrl: String,
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val epgUrl: String = "",
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val stalkerMacAddress: String = "",
    val stalkerDeviceProfile: String = "",
    val stalkerDeviceTimezone: String = "",
    val stalkerDeviceLocale: String = "",
    val stalkerSerialNumber: String = "",
    val stalkerDeviceId: String = "",
    val stalkerDeviceId2: String = "",
    val stalkerSignature: String = "",
    val stalkerAdvancedOptionsJson: String = "",
    val stalkerAuthMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    val stalkerPortalProfile: StalkerPortalProfile = StalkerPortalProfile.MAG_BASIC,
    val stalkerPortalFingerprint: StalkerPortalFingerprint = StalkerPortalFingerprint.BASIC_MAC,
    val stalkerMagPreset: StalkerMagPreset = StalkerMagPreset.GENERIC_SAFE,
    val stalkerProtocolPreference: StalkerProtocolPreference = StalkerProtocolPreference.AUTO,
    val stalkerTransportMode: StalkerTransportMode = StalkerTransportMode.AUTO_STRICT,
    val stalkerTransportOrigin: String = "",
    val stalkerTlsSpkiSha256: String = "",
    val stalkerTransportConsentAt: Long = 0L,
    val stalkerConfigurationGeneration: Long = 0L,
    val stalkerDiscoverySummary: String = "",
    val stalkerCapabilitiesJson: String = "",
    val stalkerRequestedProfileId: String = StalkerCompatibilityProfileIds.AUTO,
    val stalkerLearnedProfileId: String = "",
    val stalkerProfileRevision: Int = 0,
    val stalkerProfileVerification: StalkerProfileVerification = StalkerProfileVerification.UNVERIFIED,
    val stalkerProtocolFamily: StalkerProtocolFamily = StalkerProtocolFamily.CLASSIC_MAG,
    val stalkerLastBootstrapRecipe: StalkerBootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
    val stalkerEndpointPreference: StalkerEndpointPreference = StalkerEndpointPreference.AUTO,
    val stalkerCookieMode: StalkerCookieMode = StalkerCookieMode.NONE,
    val stalkerPlaybackBackendHint: StalkerPlaybackBackendHint = StalkerPlaybackBackendHint.AUTO,
    val stalkerLastPlaybackMode: String? = null,
    val stalkerCredentialsRequired: Boolean = false,
    val stalkerMacRequired: Boolean = true,
    val stalkerUsesTemporaryLinks: Boolean = false,
    val stalkerModuleRestricted: Boolean = false,
    val stalkerStrictFingerprintRequired: Boolean = false,
    val stalkerRecipeFallbackUsed: Boolean = false,
    val stalkerRecipeRediscoveryAttempts: Int = 0,
    val isActive: Boolean = true,
    val maxConnections: Int = 1,
    val expirationDate: Long? = null,
    val apiVersion: String? = null,
    val allowedOutputFormats: List<String> = emptyList(),
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.UPFRONT,
    val stalkerCatalogMode: StalkerCatalogMode = StalkerCatalogMode.ON_DEMAND,
    val guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
    val channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
    val xtreamFastSyncEnabled: Boolean = true,
    val xtreamLiveSyncMode: ProviderXtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
    val preferredLiveStreamFormat: LiveStreamFormatMode? = null,
    val m3uVodClassificationEnabled: Boolean = false,
    val catalogLayout: CatalogLayout = CatalogLayout.SPLIT,
    val catalogLayoutDetectionVersion: Int = 0,
    val status: ProviderStatus = ProviderStatus.UNKNOWN,
    val lastSyncedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(name.isNotBlank()) { "Provider name must not be blank" }
        require(maxConnections > 0) { "maxConnections must be positive" }
        require(lastSyncedAt >= 0) { "lastSyncedAt must be non-negative" }
    }

    override fun toString(): String =
        "LegacyProvider(id=$id, name=$name, type=$type, status=$status, isActive=$isActive)"
}

/** Provider-scoped presentation of the VOD catalog. */
enum class CatalogLayout {
    UNKNOWN,
    SPLIT,
    UNIFIED_VOD
}

enum class ProviderType {
    XTREAM_CODES,
    M3U,
    STALKER_PORTAL,
    JELLYFIN
}

enum class ProviderEpgSyncMode {
    UPFRONT,
    BACKGROUND,
    SKIP
}

enum class StalkerCatalogMode {
    ON_DEMAND,
    BACKGROUND_INDEX
}

enum class StalkerReadiness {
    AUTHENTICATING,
    LIVE_READY,
    CATEGORIES_READY,
    READY,
    READY_WITH_WARNINGS
}

/**
 * Provider-scoped snapshot of the fast Stalker readiness path.
 *
 * Timestamps are epoch milliseconds and are populated only after the corresponding
 * operation (and, for catalog stages, its database commit) has completed.
 */
data class StalkerReadinessSnapshot(
    val providerId: Long,
    val state: StalkerReadiness,
    val syncStartedAt: Long,
    val authenticatedAt: Long? = null,
    val liveReadyAt: Long? = null,
    val categoriesReadyAt: Long? = null,
    val readyAt: Long? = null,
    val warningCount: Int = 0
)

enum class StalkerIndexState {
    DISABLED,
    QUEUED,
    RUNNING,
    RETRY_WAIT,
    PARTIAL,
    COMPLETE,
    TRUNCATED,
    FAILED
}

enum class CatalogCompleteness {
    COMPLETE,
    PARTIAL,
    INDEXING,
    TRUNCATED
}

enum class StalkerRequestPriority {
    OPEN_CATEGORY,
    VISIBLE_PREVIEW,
    EPG,
    BACKGROUND_INDEX
}

enum class GuideSourcePolicy {
    AUTO,
    EXTERNAL_ONLY,
    PROVIDER_ONLY,
    DISABLED
}

enum class ChannelLogoSourcePolicy {
    SUPPLIER_PREFERRED,
    EPG_PREFERRED,
    SUPPLIER_ONLY,
    EPG_ONLY
}

enum class ProviderXtreamLiveSyncMode {
    AUTO,
    CATEGORY_BY_CATEGORY,
    STREAM_ALL
}

enum class StalkerAuthMode {
    AUTO,
    MAC_ONLY,
    MAC_PLUS_CREDENTIALS,
    CREDENTIALS_ONLY
}

enum class StalkerPortalProfile {
    MAG_BASIC,
    MAG_STRICT,
    AUTH_REQUIRED,
    AUTH_PLUS_MAG,
    MODULE_GATED
}

enum class StalkerPortalFingerprint {
    BASIC_MAC,
    STRICT_MAG,
    AUTH_ONLY,
    AUTH_STRICT_MAG,
    MODULE_GATED,
    TEMP_LINK_STRICT
}

enum class StalkerMagPreset {
    GENERIC_SAFE,
    MAG250_LEGACY,
    MAG254_STRICT,
    MINISTRA_MODERN
}

/** User intent. AUTO may select either protocol, but never mixes their session state. */
enum class StalkerProtocolPreference {
    AUTO,
    CLASSIC_MAG,
    MINISTRA_API_V3
}

enum class StalkerProtocolFamily {
    CLASSIC_MAG,
    MINISTRA_API_V3
}

/**
 * Persisted transport decision for a single Stalker provider.
 *
 * AUTO_STRICT never relaxes platform TLS checks and never sends provider identity over HTTP.
 * User-accepted modes are valid only for the exact [StalkerTransportGrant.origin] stored with
 * the provider.
 */
enum class StalkerTransportMode {
    AUTO_STRICT,
    VERIFIED_HTTPS,
    USER_ACCEPTED_UNVERIFIED_HTTPS,
    USER_ACCEPTED_HTTP
}

data class StalkerTransportOrigin(
    val scheme: String,
    val host: String,
    val port: Int
) {
    val authority: String
        get() = "$scheme://${host.lowercase()}:$port"
}

data class StalkerTransportGrant(
    val mode: StalkerTransportMode,
    val origin: StalkerTransportOrigin,
    /** Base64 SHA-256 of SubjectPublicKeyInfo; required for accepted unverified HTTPS. */
    val spkiSha256: String? = null,
    val consentedAt: Long
)

enum class StalkerTransportChallengeReason {
    INVALID_TLS,
    CLEARTEXT_HTTP,
    ORIGIN_CHANGED
}

data class StalkerTransportChallenge(
    val reason: StalkerTransportChallengeReason,
    val origin: StalkerTransportOrigin,
    val displayHost: String,
    /** Public key fingerprint proposed for an unverified HTTPS grant. */
    val proposedSpkiSha256: String? = null,
    val detailCode: String? = null
) {
    fun acceptedGrant(now: Long = System.currentTimeMillis()): StalkerTransportGrant =
        StalkerTransportGrant(
            mode = when (reason) {
                StalkerTransportChallengeReason.INVALID_TLS ->
                    StalkerTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS
                StalkerTransportChallengeReason.CLEARTEXT_HTTP ->
                    StalkerTransportMode.USER_ACCEPTED_HTTP
                StalkerTransportChallengeReason.ORIGIN_CHANGED ->
                    if (origin.scheme.equals("https", ignoreCase = true)) {
                        StalkerTransportMode.VERIFIED_HTTPS
                    } else {
                        StalkerTransportMode.USER_ACCEPTED_HTTP
                    }
            },
            origin = origin,
            spkiSha256 = proposedSpkiSha256,
            consentedAt = now
        )
}

class StalkerTransportConsentRequiredException(
    val challenge: StalkerTransportChallenge
) : Exception("Transport consent is required for ${challenge.displayHost}.")

enum class CapabilityState {
    SUPPORTED,
    UNSUPPORTED,
    RESTRICTED,
    EMPTY,
    INCONCLUSIVE,
    NOT_PROBED
}

data class DiscoveryObservation(
    val stage: String,
    val code: String,
    val scope: String,
    val elapsedMillis: Long,
    val attempt: Int
)

data class DiscoveryAttemptKey(
    val origin: String,
    val endpoint: String,
    val authMode: StalkerAuthMode,
    val profileId: String,
    val recipe: StalkerBootstrapRecipe,
    val headerPolicyHash: String,
    val cookiePolicy: StalkerCookieMode,
    val transportGeneration: Long
)

data class DiscoveryBudget(
    val maxElapsedMillis: Long = 75_000L,
    val maxRequests: Int = 24,
    val maxEndpointCandidates: Int = 8,
    val maxIdentityProfiles: Int = 6,
    val maxFreshSessions: Int = 2,
    val maxRedirectsPerChain: Int = 5,
    val maxLiveCategorySamples: Int = 8
)

data class StalkerConnectionFailure(
    val stage: String,
    val code: String,
    val retryable: Boolean,
    val safeMessage: String
)

enum class StalkerDiscoveryStage {
    NORMALIZING,
    TRANSPORT,
    ENDPOINT,
    AUTHENTICATION,
    LIVE_READINESS,
    CAPABILITIES,
    COMMITTING
}

data class StalkerDiscoveryProgress(
    val stage: StalkerDiscoveryStage,
    val attempt: Int,
    val limit: Int,
    val elapsedMillis: Long,
    val latestObservation: DiscoveryObservation? = null,
    val canCancel: Boolean = true
)

data class StalkerDiscoveryResult(
    val normalizedPortal: String,
    val workingEndpoint: String,
    val protocolFamily: StalkerProtocolFamily,
    val identityProfileId: String,
    val effectiveAuthMode: StalkerAuthMode,
    val transportGrant: StalkerTransportGrant?,
    val capabilities: Map<String, CapabilityState>,
    val observations: List<DiscoveryObservation>,
    val warnings: List<String>
)

enum class StalkerProfileVerification {
    VERIFIED,
    EXPERIMENTAL,
    CUSTOM,
    UNVERIFIED
}

/** Stable persistence IDs. Model names and enum ordinals are deliberately not persisted as identity. */
object StalkerCompatibilityProfileIds {
    const val AUTO = "auto"
    const val CLASSIC_MAG250_GENERIC = "classic.mag250.generic"
    const val CLASSIC_MAG250_LEGACY = "classic.mag250.legacy"
    const val CLASSIC_MAG254_STRICT = "classic.mag254.strict"
    const val CLASSIC_MAG322_MODERN = "classic.mag322.modern"
    const val CUSTOM = "custom"
}

enum class StalkerBootstrapRecipe {
    GENERIC_SAFE,
    LEGACY_MAG,
    STRICT_MAG,
    PORTAL_PREFERRED,
    LOCALIZATION_STRICT,
    AUTH_ONLY,
    AUTH_STRICT_MAG,
    MODULE_GATED
}

enum class StalkerEndpointPreference {
    AUTO,
    SERVER_LOAD,
    PORTAL
}

enum class StalkerCookieMode {
    NONE,
    CREATE_LINK,
    PLAYBACK,
    BOTH
}

enum class StalkerPlaybackBackendHint {
    AUTO,
    DIRECT,
    PLAY_LIVE,
    PLAY_MOVIE,
    TEMP_LINK,
    TEMP_LINK_STRICT
}

enum class ProviderStatus {
    ACTIVE,
    PARTIAL,
    EXPIRED,
    DISABLED,
    ERROR,
    UNKNOWN
}

class ProviderSavedWithSyncErrorException(
    val provider: LegacyProvider,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Authentication and the selected classic-MAG identity succeeded, but the bounded Live
 * readiness check ended on transient or otherwise inconclusive evidence.
 */
class StalkerReadinessInconclusiveException(
    val evidenceCode: String,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
