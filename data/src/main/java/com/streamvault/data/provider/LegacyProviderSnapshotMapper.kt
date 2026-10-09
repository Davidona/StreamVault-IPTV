package com.streamvault.data.provider

import com.streamvault.domain.model.Provider as StableProvider

import com.streamvault.domain.model.*
import com.streamvault.domain.model.LegacyProvider as Provider

fun ProviderConfiguration.guidePolicy(): GuideSourcePolicy = when (this) {
    is XtreamConfig -> guideSourcePolicy
    is M3uConfig -> guideSourcePolicy
    is StalkerConfig -> guideSourcePolicy
    is JellyfinConfig -> GuideSourcePolicy.DISABLED
}

fun ProviderConfiguration.logoPolicy(): ChannelLogoSourcePolicy = when (this) {
    is XtreamConfig -> channelLogoSourcePolicy
    is M3uConfig -> channelLogoSourcePolicy
    is StalkerConfig -> channelLogoSourcePolicy
    is JellyfinConfig -> ChannelLogoSourcePolicy.SUPPLIER_PREFERRED
}

/** Temporary compatibility mapper used while schema-72 rows and backup v0-10 remain readable. */
fun Provider.toTypedConfiguration(): ProviderConfiguration = when (type) {
    ProviderType.XTREAM_CODES -> XtreamConfig(
        serverUrl = serverUrl,
        username = username,
        password = password,
        httpUserAgent = httpUserAgent,
        httpHeaders = httpHeaders,
        epgSyncMode = epgSyncMode,
        guideSourcePolicy = guideSourcePolicy,
        channelLogoSourcePolicy = channelLogoSourcePolicy,
        fastSyncEnabled = xtreamFastSyncEnabled,
        liveSyncMode = xtreamLiveSyncMode,
        preferredLiveStreamFormat = preferredLiveStreamFormat
    )
    ProviderType.M3U -> M3uConfig(
        playlistUrl = m3uUrl.ifBlank { serverUrl },
        epgUrl = epgUrl,
        httpUserAgent = httpUserAgent,
        httpHeaders = httpHeaders,
        epgSyncMode = epgSyncMode,
        guideSourcePolicy = guideSourcePolicy,
        channelLogoSourcePolicy = channelLogoSourcePolicy,
        vodClassificationEnabled = m3uVodClassificationEnabled
    )
    ProviderType.STALKER_PORTAL -> StalkerConfig(
        portalUrl = serverUrl,
        device = StalkerDeviceIdentity(
            macAddress = stalkerMacAddress,
            deviceProfile = stalkerDeviceProfile,
            timezone = stalkerDeviceTimezone,
            locale = stalkerDeviceLocale,
            serialNumber = stalkerSerialNumber,
            deviceId = stalkerDeviceId,
            deviceId2 = stalkerDeviceId2,
            signature = stalkerSignature
        ),
        username = username,
        password = password,
        httpUserAgent = httpUserAgent,
        httpHeaders = httpHeaders,
        advancedOptionsJson = stalkerAdvancedOptionsJson,
        authMode = stalkerAuthMode,
        requestedProfileId = stalkerRequestedProfileId,
        protocolPreference = stalkerProtocolPreference,
        transportGrant = toLegacyTransportGrant(),
        epgSyncMode = epgSyncMode,
        catalogMode = stalkerCatalogMode,
        guideSourcePolicy = guideSourcePolicy,
        channelLogoSourcePolicy = channelLogoSourcePolicy
    )
    ProviderType.JELLYFIN -> JellyfinConfig(
        serverUrl = serverUrl,
        username = username,
        credential = password
    )
}

fun Provider.toAccountRuntime() = ProviderAccountRuntime(
    maxConnections = maxConnections,
    expirationDate = expirationDate,
    apiVersion = apiVersion,
    allowedOutputFormats = allowedOutputFormats,
    catalogLayout = catalogLayout,
    catalogLayoutDetectionVersion = catalogLayoutDetectionVersion,
    observedAt = lastSyncedAt
)

fun Provider.toProviderSnapshot(): ProviderSnapshot {
    val generation = if (type == ProviderType.STALKER_PORTAL) stalkerConfigurationGeneration else 0L
    return ProviderSnapshot(
        provider = StableProvider(
            id = id,
            name = name,
            type = type,
            isActive = isActive,
            status = status,
            lastSyncedAt = lastSyncedAt,
            createdAt = createdAt
        ),
        configuration = toTypedConfiguration(),
        configurationGeneration = generation,
        accountRuntime = toAccountRuntime(),
        stalkerLearning = if (type == ProviderType.STALKER_PORTAL) toLegacyStalkerLearning(generation) else null
    )
}

private fun Provider.toLegacyStalkerLearning(generation: Long): StalkerPortalLearning {
    val observedAt = lastSyncedAt.takeIf { it > 0L } ?: createdAt
    fun <T> observation(value: T, source: StalkerObservationSource = StalkerObservationSource.DISCOVERY) =
        StalkerObservation(value, generation, source, observedAt)
    return StalkerPortalLearning(
        configurationGeneration = generation,
        effectiveIdentity = observation(
            StalkerDeviceIdentity(
                macAddress = stalkerMacAddress,
                deviceProfile = stalkerDeviceProfile,
                timezone = stalkerDeviceTimezone,
                locale = stalkerDeviceLocale,
                serialNumber = stalkerSerialNumber,
                deviceId = stalkerDeviceId,
                deviceId2 = stalkerDeviceId2,
                signature = stalkerSignature
            )
        ),
        profileId = stalkerLearnedProfileId.takeIf { it.isNotBlank() }?.let(::observation),
        profileRevision = observation(stalkerProfileRevision),
        profileVerification = observation(stalkerProfileVerification),
        portalProfile = observation(stalkerPortalProfile),
        portalFingerprint = observation(stalkerPortalFingerprint),
        magPreset = observation(stalkerMagPreset),
        protocolFamily = observation(stalkerProtocolFamily),
        bootstrapRecipe = observation(stalkerLastBootstrapRecipe),
        endpointPreference = observation(stalkerEndpointPreference),
        cookieMode = observation(stalkerCookieMode),
        playbackBackendHint = observation(stalkerPlaybackBackendHint),
        lastPlaybackMode = stalkerLastPlaybackMode?.let { observation(it, StalkerObservationSource.PLAYBACK) }
    )
}

private fun Provider.toLegacyTransportGrant(): StalkerTransportGrant? {
    if (stalkerTransportMode == StalkerTransportMode.AUTO_STRICT || stalkerTransportConsentAt <= 0L) return null
    val match = Regex("^(https?)://([^/:]+)(?::(\\d+))?$").matchEntire(stalkerTransportOrigin.trim()) ?: return null
    val scheme = match.groupValues[1].lowercase()
    val port = match.groupValues[3].toIntOrNull() ?: if (scheme == "https") 443 else 80
    return StalkerTransportGrant(
        mode = stalkerTransportMode,
        origin = StalkerTransportOrigin(scheme, match.groupValues[2], port),
        spkiSha256 = stalkerTlsSpkiSha256.takeIf { it.isNotBlank() },
        consentedAt = stalkerTransportConsentAt
    )
}
