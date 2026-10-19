package com.streamvault.data.provider

import com.google.gson.Gson
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.JellyfinConfig
import com.streamvault.domain.model.M3uConfig
import com.streamvault.domain.model.ProviderConfiguration
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.XtreamConfig
import java.net.URI
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps secret handling at the data boundary; plaintext credentials never enter Room JSON. */
@Singleton
class ProviderConfigurationCodec @Inject constructor(
    private val gson: Gson,
    private val credentialCrypto: CredentialCrypto
) {
    internal data class MigrationDecodeResult(
        val configuration: ProviderConfiguration,
        val requiresReencryption: Boolean
    )

    internal data class MigrationEncodeResult(
        val payload: String,
        val usesPreferredKey: Boolean
    )

    fun encode(configuration: ProviderConfiguration): String =
        encodeProviderConfiguration(gson, credentialCrypto, configuration)

    internal fun encodeForKeyMigration(configuration: ProviderConfiguration): MigrationEncodeResult {
        var usesPreferredKey = true
        fun encrypt(value: String): String {
            val result = credentialCrypto.encryptWithKeyMigration(value)
            usesPreferredKey = usesPreferredKey && result.usesPreferredKey
            return result.encryptedValue
        }
        val encrypted = when (configuration) {
            is XtreamConfig -> configuration.copy(password = encrypt(configuration.password))
            is M3uConfig -> configuration
            is StalkerConfig -> configuration.copy(password = encrypt(configuration.password))
            is JellyfinConfig -> configuration.copy(credential = encrypt(configuration.credential))
        }
        return MigrationEncodeResult(gson.toJson(encrypted), usesPreferredKey)
    }

    fun decode(type: ProviderType, payload: String): ProviderConfiguration =
        decodeProviderConfigurationCompat(gson, credentialCrypto, type, payload)

    internal fun decodeForKeyMigration(type: ProviderType, payload: String): MigrationDecodeResult {
        var requiresReencryption = false
        val configuration = decodeProviderConfigurationCompat(
            gson = gson,
            credentialCrypto = credentialCrypto,
            type = type,
            payload = payload,
            onCredentialReencrypted = { requiresReencryption = true }
        )
        return MigrationDecodeResult(configuration, requiresReencryption)
    }

    /**
     * Decode provider metadata for public projections without asking Android Keystore to open
     * credentials that the projection will immediately discard.
     */
    fun decodeForPublicProjection(type: ProviderType, payload: String): ProviderConfiguration {
        val configuration = decodeProviderConfigurationCompat(
            gson = gson,
            credentialCrypto = credentialCrypto,
            type = type,
            payload = payload,
            decryptCredentials = false
        )
        return when (configuration) {
            is XtreamConfig -> configuration.copy(password = "")
            is M3uConfig -> configuration
            is StalkerConfig -> configuration.copy(password = "")
            is JellyfinConfig -> configuration.copy(credential = "")
        }
    }

    fun identityKey(configuration: ProviderConfiguration): String {
        val canonical = when (configuration) {
            is XtreamConfig -> listOf(configuration.type.name, normalizeOrigin(configuration.serverUrl), configuration.username.trim())
            is M3uConfig -> listOf(configuration.type.name, normalizeUrl(configuration.playlistUrl))
            is StalkerConfig -> listOf(
                configuration.type.name,
                normalizeOrigin(configuration.portalUrl),
                configuration.device.macAddress.trim().uppercase(),
                configuration.username.trim()
            )
            is JellyfinConfig -> listOf(configuration.type.name, normalizeOrigin(configuration.serverUrl), configuration.username.trim())
        }.joinToString("\u0000")
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun normalizeOrigin(value: String): String = runCatching {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase().orEmpty()
        val host = uri.host?.lowercase().orEmpty()
        val port = when {
            uri.port >= 0 -> uri.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> -1
        }
        "$scheme://$host:$port${uri.path.orEmpty().trimEnd('/')}"
    }.getOrElse { value.trim().trimEnd('/').lowercase() }

    private fun normalizeUrl(value: String): String = value.trim()
}
