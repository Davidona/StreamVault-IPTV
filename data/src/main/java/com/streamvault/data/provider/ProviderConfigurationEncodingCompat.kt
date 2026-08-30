package com.streamvault.data.provider

import com.google.gson.Gson
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.ProviderConfiguration
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
