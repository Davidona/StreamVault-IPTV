package com.streamvault.data.provider

import com.streamvault.data.preferences.PreferencesRepository
import com.streamvault.data.remote.http.buildGenericProviderRequestProfile
import com.streamvault.data.remote.jellyfin.JellyfinProvider
import com.streamvault.data.remote.stalker.StalkerApiService
import com.streamvault.data.remote.stalker.StalkerPlaybackMode
import com.streamvault.data.remote.stalker.StalkerPortalStateStore
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerRemoteIdentityResolver
import com.streamvault.data.remote.stalker.StalkerResolvedCategoryHandoff
import com.streamvault.data.remote.xtream.XtreamApiService
import com.streamvault.data.remote.xtream.XtreamProvider
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.LiveStreamFormatMode
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.StalkerPortalProfile
import com.streamvault.domain.model.XtreamConfig
import com.streamvault.domain.provider.CapabilityResolution
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import com.streamvault.data.remote.xtream.XtreamStreamKind

/**
 * Constructs provider protocol clients from the typed snapshot boundary.
 *
 * This is deliberately stateless. Playback's existing Stalker cache and learning side effects
 * remain owned by its resolver adapter until ARCH-004 is handled in WP5.
 */
@Singleton
class TypedProviderClientFactory @Inject constructor(
    private val xtreamApiService: XtreamApiService,
    private val stalkerApiService: StalkerApiService,
    private val jellyfinProvider: JellyfinProvider,
    private val preferencesRepository: PreferencesRepository,
    private val stalkerRemoteIdentityResolver: StalkerRemoteIdentityResolver,
    private val stalkerPortalStateStore: StalkerPortalStateStore
) {
    suspend fun xtream(
        snapshot: ProviderSnapshot,
        options: XtreamClientOptions = XtreamClientOptions()
    ): CapabilityResolution<XtreamProvider> {
        val config = snapshot.configuration as? XtreamConfig
            ?: return CapabilityResolution.ConfigurationError("Xtream capability requires XtreamConfig")
        if (config.serverUrl.isBlank() || config.username.isBlank() || config.password.isBlank()) {
            return CapabilityResolution.ConfigurationError("Xtream server URL and credentials are required")
        }
        return CapabilityResolution.Available(
            XtreamProvider(
                providerId = snapshot.provider.id,
                api = xtreamApiService,
                serverUrl = config.serverUrl,
                username = config.username,
                password = config.password,
                allowedOutputFormats = snapshot.accountRuntime.allowedOutputFormats,
                useTextClassification = options.useTextClassification,
                enableBase64TextCompatibility = options.enableBase64TextCompatibility
                    ?: preferencesRepository.xtreamBase64TextCompatibility.first(),
                requestProfile = buildGenericProviderRequestProfile(
                    ownerTag = "provider:${snapshot.provider.id}/xtream",
                    httpUserAgent = config.httpUserAgent,
                    httpHeaders = config.httpHeaders
                )
            )
        )
    }

    fun stalker(
        snapshot: ProviderSnapshot,
        options: StalkerClientOptions = StalkerClientOptions()
    ): CapabilityResolution<StalkerProvider> {
        val config = snapshot.configuration as? StalkerConfig
            ?: return CapabilityResolution.ConfigurationError("Stalker capability requires StalkerConfig")
        if (config.portalUrl.isBlank() || config.device.macAddress.isBlank()) {
            return CapabilityResolution.ConfigurationError("Stalker portal URL and MAC address are required")
        }
        val learning = snapshot.stalkerLearning
        val identity = learning?.effectiveIdentity?.value ?: config.device
        return CapabilityResolution.Available(
            StalkerProvider(
                providerId = snapshot.provider.id,
                api = stalkerApiService,
                portalUrl = config.portalUrl,
                macAddress = identity.macAddress,
                authMode = config.authMode,
                username = config.username,
                password = config.password,
                httpUserAgent = config.httpUserAgent,
                httpHeaders = config.httpHeaders,
                portalFingerprintHint = learning?.portalFingerprint?.value
                    ?: StalkerPortalFingerprint.BASIC_MAC,
                magPresetHint = learning?.magPreset?.value ?: StalkerMagPreset.GENERIC_SAFE,
                bootstrapRecipeHint = learning?.bootstrapRecipe?.value
                    ?: StalkerBootstrapRecipe.GENERIC_SAFE,
                endpointPreferenceHint = learning?.endpointPreference?.value
                    ?: StalkerEndpointPreference.AUTO,
                cookieModeHint = learning?.cookieMode?.value ?: StalkerCookieMode.NONE,
                playbackBackendHint = learning?.playbackBackendHint?.value
                    ?: StalkerPlaybackBackendHint.AUTO,
                portalProfileHint = learning?.portalProfile?.value ?: StalkerPortalProfile.MAG_BASIC,
                preferredPlaybackMode = learning?.lastPlaybackMode?.value
                    ?.let { runCatching { StalkerPlaybackMode.valueOf(it) }.getOrNull() },
                deviceProfile = identity.deviceProfile,
                timezone = identity.timezone,
                locale = identity.locale,
                serialNumber = identity.serialNumber,
                deviceId = identity.deviceId,
                deviceId2 = identity.deviceId2,
                signature = identity.signature,
                stalkerAdvancedOptionsJson = config.advancedOptionsJson,
                protocolPreference = config.protocolPreference,
                transportGrant = config.transportGrant,
                requestedProfileId = config.requestedProfileId,
                learnedProfileId = learning?.profileId?.value.orEmpty(),
                configurationGeneration = snapshot.configurationGeneration,
                requireCatalogValidation = options.requireCatalogValidation,
                catalogLayoutHint = snapshot.accountRuntime.catalogLayout,
                catalogLayoutDetectionVersionHint = snapshot.accountRuntime.catalogLayoutDetectionVersion,
                identityResolver = stalkerRemoteIdentityResolver,
                portalStateStore = stalkerPortalStateStore,
                onProgress = options.onProgress,
                resolvedCategoryHandoffKey = StalkerResolvedCategoryHandoff.key(
                    snapshot.provider.id, snapshot.configurationGeneration, config
                )
            )
        )
    }

    fun jellyfin(snapshot: ProviderSnapshot): CapabilityResolution<JellyfinClientContext> {
        val config = snapshot.configuration as? JellyfinConfig
            ?: return CapabilityResolution.ConfigurationError("Jellyfin capability requires JellyfinConfig")
        if (config.serverUrl.isBlank() || config.credential.isBlank()) {
            return CapabilityResolution.ConfigurationError("Jellyfin server URL and credential are required")
        }
        return CapabilityResolution.Available(
            JellyfinClientContext(
                client = jellyfinProvider,
                provider = Provider(
                    id = snapshot.provider.id,
                    name = snapshot.provider.name,
                    type = snapshot.provider.type,
                    serverUrl = config.serverUrl,
                    username = config.username,
                    password = config.credential,
                    isActive = snapshot.provider.isActive,
                    status = snapshot.provider.status,
                    lastSyncedAt = snapshot.provider.lastSyncedAt,
                    createdAt = snapshot.provider.createdAt
                )
            )
        )
    }

    suspend fun resolveLiveContainerExtension(
        snapshot: ProviderSnapshot,
        kind: XtreamStreamKind,
        value: String?
    ): String? {
        if (kind != XtreamStreamKind.LIVE) return value
        val providerPreference = (snapshot.configuration as? XtreamConfig)?.preferredLiveStreamFormat
        return when (preferencesRepository.playerLiveStreamFormatMode.first()) {
            LiveStreamFormatMode.AUTO -> when (providerPreference) {
                LiveStreamFormatMode.HLS -> "m3u8"
                LiveStreamFormatMode.MPEG_TS -> "ts"
                LiveStreamFormatMode.AUTO, null -> value
            }
            LiveStreamFormatMode.HLS -> "m3u8"
            LiveStreamFormatMode.MPEG_TS -> "ts"
        }
    }
}

data class XtreamClientOptions(
    val useTextClassification: Boolean = true,
    val enableBase64TextCompatibility: Boolean? = null
)

data class StalkerClientOptions(
    val requireCatalogValidation: Boolean = true,
    val onProgress: ((String) -> Unit)? = null
)

data class JellyfinClientContext(
    val client: JellyfinProvider,
    val provider: Provider
)
