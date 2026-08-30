package com.streamvault.data.provider

import com.google.gson.Gson
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.ProviderConfiguration
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.XtreamConfig

internal fun encodeProviderConfiguration(
    gson: Gson,
    credentialCrypto: CredentialCrypto,
    configuration: ProviderConfiguration
): String {
    val encrypted = when (configuration) {
        is XtreamConfig -> configuration.copy(password = credentialCrypto.encryptIfNeeded(configuration.password))
        is M3uConfig -> configuration
        is StalkerConfig -> configuration.copy(password = credentialCrypto.encryptIfNeeded(configuration.password))
        is JellyfinConfig -> configuration.copy(credential = credentialCrypto.encryptIfNeeded(configuration.credential))
    }
    return gson.toJson(encrypted)
}

internal fun decodeProviderConfigurationCompat(
    gson: Gson,
    credentialCrypto: CredentialCrypto,
    type: ProviderType,
    payload: String
): ProviderConfiguration {
    val payloadHasType = gson.fromJson(payload, Map::class.java)?.containsKey("type") == true
    val decoded = when (type) {
        ProviderType.XTREAM_CODES -> gson.fromJson(payload, XtreamConfig::class.java)
        ProviderType.M3U -> gson.fromJson(payload, M3uConfig::class.java)
        ProviderType.STALKER_PORTAL -> gson.fromJson(payload, StalkerConfig::class.java)
        ProviderType.JELLYFIN -> gson.fromJson(payload, JellyfinConfig::class.java)
    } ?: throw IllegalArgumentException("Provider configuration payload is empty")

    // The 1.0.17 typed-provider backfill stored provider_configs.type separately but omitted
    // the matching JSON field. Gson may instantiate these Kotlin classes without running
    // body-property initializers, leaving decoded.type unset. Reconstruct only those legacy
    // payloads; an explicit mismatching JSON type must still fail below.
    val normalized = if (payloadHasType) decoded else when (decoded) {
        is XtreamConfig -> decoded.copy()
        is M3uConfig -> decoded.copy()
        is StalkerConfig -> decoded.copy()
        is JellyfinConfig -> decoded.copy()
    }

    require(normalized.type == type) { "Configuration payload does not match stored provider type" }

    return when (normalized) {
        is XtreamConfig -> normalized.copy(password = credentialCrypto.decryptIfNeeded(normalized.password))
        is M3uConfig -> normalized
        is StalkerConfig -> normalized.copy(password = credentialCrypto.decryptIfNeeded(normalized.password))
        is JellyfinConfig -> normalized.copy(credential = credentialCrypto.decryptIfNeeded(normalized.credential))
    }
}
