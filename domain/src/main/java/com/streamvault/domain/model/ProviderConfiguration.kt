package com.streamvault.domain.model

/**
 * User-owned provider configuration. Runtime/account observations deliberately do not belong
 * here: changing this value creates a new [ProviderSnapshot.configurationGeneration].
 */
sealed interface ProviderConfiguration {
    val type: ProviderType
    val schemaVersion: Int
}

data class XtreamConfig(
    val serverUrl: String,
    val username: String,
    val password: String,
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
    val guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
    val channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
    val fastSyncEnabled: Boolean = true,
    val liveSyncMode: ProviderXtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
    override val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val preferredLiveStreamFormat: LiveStreamFormatMode? = null
) : ProviderConfiguration {
    override val type: ProviderType = ProviderType.XTREAM_CODES

    companion object { const val CURRENT_SCHEMA_VERSION = 1 }
}

data class M3uConfig(
    val playlistUrl: String,
    val epgUrl: String = "",
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
    val guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
    val channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
    val vodClassificationEnabled: Boolean = false,
    override val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) : ProviderConfiguration {
    override val type: ProviderType = ProviderType.M3U

    companion object { const val CURRENT_SCHEMA_VERSION = 1 }
}

/** Requested Stalker device identity; learned/effective values live in StalkerPortalLearning. */
data class StalkerDeviceIdentity(
    val macAddress: String,
    val deviceProfile: String = "",
    val timezone: String = "",
    val locale: String = "",
    val serialNumber: String = "",
    val deviceId: String = "",
    val deviceId2: String = "",
    val signature: String = ""
)

data class StalkerConfig(
    val portalUrl: String,
    val device: StalkerDeviceIdentity,
    val username: String = "",
    val password: String = "",
    val httpUserAgent: String = "",
    val httpHeaders: String = "",
    val advancedOptionsJson: String = "",
    val authMode: StalkerAuthMode = StalkerAuthMode.AUTO,
    val requestedProfileId: String = StalkerCompatibilityProfileIds.AUTO,
    val protocolPreference: StalkerProtocolPreference = StalkerProtocolPreference.AUTO,
    val transportGrant: StalkerTransportGrant? = null,
    val epgSyncMode: ProviderEpgSyncMode = ProviderEpgSyncMode.BACKGROUND,
    val catalogMode: StalkerCatalogMode = StalkerCatalogMode.ON_DEMAND,
    val guideSourcePolicy: GuideSourcePolicy = GuideSourcePolicy.AUTO,
    val channelLogoSourcePolicy: ChannelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
    override val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) : ProviderConfiguration {
    override val type: ProviderType = ProviderType.STALKER_PORTAL

    companion object { const val CURRENT_SCHEMA_VERSION = 1 }
}

data class JellyfinConfig(
    val serverUrl: String,
    val username: String,
    /** Password before setup and access token after successful setup. */
    val credential: String,
    override val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) : ProviderConfiguration {
    override val type: ProviderType = ProviderType.JELLYFIN

    companion object { const val CURRENT_SCHEMA_VERSION = 1 }
}

data class ProviderAccountRuntime(
    val maxConnections: Int = 1,
    val expirationDate: Long? = null,
    val apiVersion: String? = null,
    val allowedOutputFormats: List<String> = emptyList(),
    val catalogLayout: CatalogLayout = CatalogLayout.SPLIT,
    val catalogLayoutDetectionVersion: Int = 0,
    val observedAt: Long = 0L
) {
    init { require(maxConnections > 0) { "maxConnections must be positive" } }
}

enum class StalkerObservationSource {
    DISCOVERY,
    AUTHENTICATION,
    CATALOG,
    GUIDE,
    PLAYBACK
}

/** A learned value that is valid only for one immutable configuration generation. */
data class StalkerObservation<T>(
    val value: T,
    val configurationGeneration: Long,
    val source: StalkerObservationSource,
    val observedAt: Long
)

data class StalkerPortalLearning(
    val configurationGeneration: Long,
    val effectiveIdentity: StalkerObservation<StalkerDeviceIdentity>? = null,
    val profileId: StalkerObservation<String>? = null,
    val profileRevision: StalkerObservation<Int>? = null,
    val profileVerification: StalkerObservation<StalkerProfileVerification>? = null,
    val portalProfile: StalkerObservation<StalkerPortalProfile>? = null,
    val portalFingerprint: StalkerObservation<StalkerPortalFingerprint>? = null,
    val magPreset: StalkerObservation<StalkerMagPreset>? = null,
    val protocolFamily: StalkerObservation<StalkerProtocolFamily>? = null,
    val bootstrapRecipe: StalkerObservation<StalkerBootstrapRecipe>? = null,
    val endpointPreference: StalkerObservation<StalkerEndpointPreference>? = null,
    val workingEndpoint: StalkerObservation<String>? = null,
    val cookieMode: StalkerObservation<StalkerCookieMode>? = null,
    val playbackBackendHint: StalkerObservation<StalkerPlaybackBackendHint>? = null,
    val lastPlaybackMode: StalkerObservation<String>? = null,
    val capabilities: Map<String, StalkerObservation<CapabilityState>> = emptyMap(),
    val discoveryEvidence: List<StalkerObservation<DiscoveryObservation>> = emptyList()
) {
    init {
        val observations = buildList {
            add(effectiveIdentity)
            add(profileId)
            add(profileRevision)
            add(profileVerification)
            add(portalProfile)
            add(portalFingerprint)
            add(magPreset)
            add(protocolFamily)
            add(bootstrapRecipe)
            add(endpointPreference)
            add(workingEndpoint)
            add(cookieMode)
            add(playbackBackendHint)
            add(lastPlaybackMode)
            addAll(capabilities.values)
            addAll(discoveryEvidence)
        }.filterNotNull()
        require(observations.all { it.configurationGeneration == configurationGeneration }) {
            "Every Stalker observation must match the learning configuration generation"
        }
    }
}

/** Stable identity/status row; configuration and observations are deliberately absent. */
data class Provider(
    val id: Long = 0L,
    val name: String,
    val type: ProviderType,
    val isActive: Boolean = true,
    val status: ProviderStatus = ProviderStatus.UNKNOWN,
    val lastSyncedAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
) {
    init {
        require(name.isNotBlank()) { "Provider name must not be blank" }
        require(lastSyncedAt >= 0L) { "lastSyncedAt must be non-negative" }
    }
}

data class ProviderSnapshot(
    val provider: Provider,
    val configuration: ProviderConfiguration,
    val configurationGeneration: Long,
    val accountRuntime: ProviderAccountRuntime = ProviderAccountRuntime(),
    val stalkerLearning: StalkerPortalLearning? = null
) {
    init {
        require(provider.type == configuration.type) {
            "Provider type ${provider.type} does not match configuration type ${configuration.type}"
        }
        require(configurationGeneration >= 0L) { "configurationGeneration must be non-negative" }
        require(stalkerLearning == null || provider.type == ProviderType.STALKER_PORTAL) {
            "Stalker learning is valid only for Stalker providers"
        }
        require(stalkerLearning == null || stalkerLearning.configurationGeneration == configurationGeneration) {
            "Stalker learning is stale for this snapshot"
        }
    }
}
