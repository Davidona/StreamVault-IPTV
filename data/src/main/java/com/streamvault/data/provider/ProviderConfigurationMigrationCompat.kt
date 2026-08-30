package com.streamvault.data.provider

import com.google.gson.Gson
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.ProviderConfiguration
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.XtreamConfig

internal fun decodeProviderConfigurationCompat(
    gson: Gson,
    credentialCrypto: CredentialCrypto,
    type: ProviderType,
    payload: String
): ProviderConfiguration {
    val payloadHasType = gson.providerPayloadHasEmbeddedType(payload)
    val decoded = when (type) {
        ProviderType.XTREAM_CODES -> gson.fromJson(payload, XtreamConfig::class.java)
        ProviderType.M3U -> gson.fromJson(payload, M3uConfig::class.java)
        ProviderType.STALKER_PORTAL -> gson.fromJson(payload, StalkerConfig::class.java)
        ProviderType.JELLYFIN -> gson.fromJson(payload, JellyfinConfig::class.java)
    } ?: throw IllegalArgumentException("Provider configuration payload is empty")

    val normalized = if (payloadHasType) decoded else decoded.withCanonicalProviderType()
    require(normalized.type == type) { "Configuration payload does not match stored provider type" }

    return when (normalized) {
        is XtreamConfig -> normalized.copy(password = credentialCrypto.decryptIfNeeded(normalized.password))
        is M3uConfig -> normalized
        is StalkerConfig -> normalized.copy(password = credentialCrypto.decryptIfNeeded(normalized.password))
        is JellyfinConfig -> normalized.copy(credential = credentialCrypto.decryptIfNeeded(normalized.credential))
    }
}
