package com.streamvault.data.provider

import com.streamvault.domain.model.Provider as StableProvider

import com.google.gson.Gson
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.mapper.toEntity
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.*
import com.streamvault.domain.model.LegacyProvider as Provider

data class ProviderConfigRevisionEnvelope(
    val envelopeVersion: Int = CURRENT_VERSION,
    val provider: StableProvider,
    val configurationGeneration: Long,
    val configurationType: ProviderType,
    val configurationSchemaVersion: Int,
    val identityKey: String,
    val encryptedConfigurationJson: String,
    val accountRuntime: ProviderAccountRuntime
) {
    companion object {
        const val CURRENT_VERSION = 2
    }
}

data class DecodedProviderConfigRevision(
    val candidate: Provider,
    val secureEntity: ProviderEntity,
    val configurationGeneration: Long,
    val wasLegacy: Boolean
)

/** Versioned candidate codec with a legacy ProviderEntity fallback for process-death upgrades. */
class ProviderConfigRevisionCodec(
    private val gson: Gson,
    private val configurationCodec: ProviderConfigurationCodec,
    private val credentialCrypto: CredentialCrypto
) {
    fun encode(candidate: Provider, generation: Long): String {
        val configuration = candidate.toTypedConfiguration()
        return gson.toJson(
            ProviderConfigRevisionEnvelope(
                provider = StableProvider(
                    id = candidate.id,
                    name = candidate.name,
                    type = candidate.type,
                    isActive = candidate.isActive,
                    status = candidate.status,
                    lastSyncedAt = candidate.lastSyncedAt,
                    createdAt = candidate.createdAt
                ),
                configurationGeneration = generation,
                configurationType = configuration.type,
                configurationSchemaVersion = configuration.schemaVersion,
                identityKey = configurationCodec.identityKey(configuration),
                encryptedConfigurationJson = configurationCodec.encode(configuration),
                accountRuntime = candidate.toAccountRuntime()
            )
        )
    }

    fun decode(payload: String): DecodedProviderConfigRevision {
        val envelope = runCatching { gson.fromJson(payload, ProviderConfigRevisionEnvelope::class.java) }
            .getOrNull()
            ?.takeIf { it.envelopeVersion == ProviderConfigRevisionEnvelope.CURRENT_VERSION }
        if (envelope != null) {
            require(envelope.provider.type == envelope.configurationType) { "Revision provider/configuration type mismatch" }
            val configuration = configurationCodec.decode(envelope.configurationType, envelope.encryptedConfigurationJson)
            require(configurationCodec.identityKey(configuration) == envelope.identityKey) {
                "Revision configuration identity is invalid"
            }
            val candidate = configuration.toLegacyProvider(envelope.provider, envelope.accountRuntime).let { provider ->
                if (provider.type == ProviderType.STALKER_PORTAL) {
                    provider.copy(stalkerConfigurationGeneration = envelope.configurationGeneration)
                } else {
                    provider
                }
            }
            val secure = candidate.toEntity()
            return DecodedProviderConfigRevision(candidate, secure, envelope.configurationGeneration, wasLegacy = false)
        }

        val legacyJson = gson.fromJson(payload, com.google.gson.JsonObject::class.java)
            ?: throw IllegalArgumentException("Saved provider edit was empty")
        val serializedProvider = gson.fromJson(payload, Provider::class.java)
            ?: throw IllegalArgumentException("Saved provider edit was empty")
        val allowedFormats = legacyJson.get("allowedOutputFormatsJson")
            ?.asString
            ?.let { runCatching { gson.fromJson(it, Array<String>::class.java).toList() }.getOrNull() }
            .orEmpty()
        val candidate = serializedProvider.copy(
            password = credentialCrypto.decryptIfNeeded(serializedProvider.password),
            allowedOutputFormats = allowedFormats
        )
        val secure = candidate.toEntity()
        return DecodedProviderConfigRevision(
            candidate = candidate,
            secureEntity = secure,
            configurationGeneration = candidate.stalkerConfigurationGeneration,
            wasLegacy = true
        )
    }
}

fun ProviderConfiguration.toLegacyProvider(
    identity: StableProvider,
    runtime: ProviderAccountRuntime
): Provider {
    val common = Provider(
        id = identity.id,
        name = identity.name,
        type = identity.type,
        serverUrl = when (this) {
            is XtreamConfig -> serverUrl
            is M3uConfig -> playlistUrl
            is StalkerConfig -> portalUrl
            is JellyfinConfig -> serverUrl
        },
        isActive = identity.isActive,
        maxConnections = runtime.maxConnections,
        expirationDate = runtime.expirationDate,
        apiVersion = runtime.apiVersion,
        allowedOutputFormats = runtime.allowedOutputFormats,
        catalogLayout = runtime.catalogLayout,
        catalogLayoutDetectionVersion = runtime.catalogLayoutDetectionVersion,
        status = identity.status,
        lastSyncedAt = identity.lastSyncedAt,
        createdAt = identity.createdAt
    )
    return when (this) {
        is XtreamConfig -> common.copy(
            username = username,
            password = password,
            httpUserAgent = httpUserAgent,
            httpHeaders = httpHeaders,
            epgSyncMode = epgSyncMode,
            guideSourcePolicy = guideSourcePolicy,
            channelLogoSourcePolicy = channelLogoSourcePolicy,
            xtreamFastSyncEnabled = fastSyncEnabled,
            xtreamLiveSyncMode = liveSyncMode,
            preferredLiveStreamFormat = preferredLiveStreamFormat
        )
        is M3uConfig -> common.copy(
            m3uUrl = playlistUrl,
            epgUrl = epgUrl,
            httpUserAgent = httpUserAgent,
            httpHeaders = httpHeaders,
            epgSyncMode = epgSyncMode,
            guideSourcePolicy = guideSourcePolicy,
            channelLogoSourcePolicy = channelLogoSourcePolicy,
            m3uVodClassificationEnabled = vodClassificationEnabled
        )
        is StalkerConfig -> common.copy(
            username = username,
            password = password,
            httpUserAgent = httpUserAgent,
            httpHeaders = httpHeaders,
            stalkerMacAddress = device.macAddress,
            stalkerDeviceProfile = device.deviceProfile,
            stalkerDeviceTimezone = device.timezone,
            stalkerDeviceLocale = device.locale,
            stalkerSerialNumber = device.serialNumber,
            stalkerDeviceId = device.deviceId,
            stalkerDeviceId2 = device.deviceId2,
            stalkerSignature = device.signature,
            stalkerAdvancedOptionsJson = advancedOptionsJson,
            stalkerAuthMode = authMode,
            stalkerProtocolPreference = protocolPreference,
            stalkerTransportMode = transportGrant?.mode ?: StalkerTransportMode.AUTO_STRICT,
            stalkerTransportOrigin = transportGrant?.origin?.authority.orEmpty(),
            stalkerTlsSpkiSha256 = transportGrant?.spkiSha256.orEmpty(),
            stalkerTransportConsentAt = transportGrant?.consentedAt ?: 0L,
            stalkerConfigurationGeneration = 0L,
            stalkerRequestedProfileId = requestedProfileId,
            epgSyncMode = epgSyncMode,
            stalkerCatalogMode = catalogMode,
            guideSourcePolicy = guideSourcePolicy,
            channelLogoSourcePolicy = channelLogoSourcePolicy
        )
        is JellyfinConfig -> common.copy(username = username, password = credential)
    }
}

/** Temporary compatibility projection used only inside data-layer adapters during schema 74 cutover. */
fun ProviderSnapshot.toLegacyProvider(): Provider =
    configuration.toLegacyProvider(provider, accountRuntime).let { legacy ->
        if (provider.type != ProviderType.STALKER_PORTAL) return@let legacy
        val learning = stalkerLearning
        legacy.copy(
            // Generation is part of the configuration snapshot even when no learning has been
            // observed yet. Leaving this at the legacy mapper's zero default makes a fresh edit
            // appear stale and can attach later observations to the wrong configuration.
            stalkerConfigurationGeneration = configurationGeneration,
            stalkerLearnedProfileId = learning?.profileId?.value ?: legacy.stalkerLearnedProfileId,
            stalkerProfileRevision = learning?.profileRevision?.value ?: legacy.stalkerProfileRevision,
            stalkerProfileVerification = learning?.profileVerification?.value
                ?: legacy.stalkerProfileVerification,
            stalkerPortalProfile = learning?.portalProfile?.value ?: legacy.stalkerPortalProfile,
            stalkerPortalFingerprint = learning?.portalFingerprint?.value
                ?: legacy.stalkerPortalFingerprint,
            stalkerMagPreset = learning?.magPreset?.value ?: legacy.stalkerMagPreset,
            stalkerProtocolFamily = learning?.protocolFamily?.value ?: legacy.stalkerProtocolFamily,
            stalkerLastBootstrapRecipe = learning?.bootstrapRecipe?.value
                ?: legacy.stalkerLastBootstrapRecipe,
            stalkerEndpointPreference = learning?.endpointPreference?.value
                ?: legacy.stalkerEndpointPreference,
            stalkerCookieMode = learning?.cookieMode?.value ?: legacy.stalkerCookieMode,
            stalkerPlaybackBackendHint = learning?.playbackBackendHint?.value
                ?: legacy.stalkerPlaybackBackendHint,
            stalkerLastPlaybackMode = learning?.lastPlaybackMode?.value
                ?: legacy.stalkerLastPlaybackMode
        )
    }
