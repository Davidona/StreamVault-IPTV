package com.streamvault.data.remote.stalker

import com.streamvault.domain.model.StalkerCompatibilityRegistry

import android.content.Context
import com.streamvault.data.security.CredentialCrypto
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderStatus
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.Series
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerProfileVerification
import com.streamvault.domain.model.StalkerProtocolFamily
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.provider.*
import java.io.IOException
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class MinistraApiV3Credentials(
    val portalUrl: String,
    val login: String,
    val password: String,
    val macAddress: String = "",
    val serialNumber: String = "",
    val activationCode: String = ""
)

data class MinistraApiV3Token(
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAt: Long
) {
    fun isUsable(now: Long = System.currentTimeMillis()): Boolean =
        accessToken.isNotBlank() && expiresAt > now + 30_000L
}

/** Implementations must encrypt values at rest; tokens never belong in Provider or backups. */
interface MinistraApiV3TokenStore {
    suspend fun read(providerId: Long): MinistraApiV3Token?
    suspend fun write(providerId: Long, token: MinistraApiV3Token)
    suspend fun clear(providerId: Long)
}

@Singleton
class EncryptedMinistraApiV3TokenStore @Inject constructor(
    @ApplicationContext context: Context,
    private val credentialCrypto: CredentialCrypto
) : MinistraApiV3TokenStore {
    private val preferences = context.getSharedPreferences("ministra_api_v3_tokens", Context.MODE_PRIVATE)

    override suspend fun read(providerId: Long): MinistraApiV3Token? {
        val access = preferences.getString("$providerId.access", null) ?: return null
        return runCatching {
            MinistraApiV3Token(
                accessToken = credentialCrypto.decryptIfNeeded(access),
                refreshToken = preferences.getString("$providerId.refresh", "").orEmpty()
                    .let(credentialCrypto::decryptIfNeeded),
                expiresAt = preferences.getString("$providerId.expiry", "").orEmpty()
                    .let(credentialCrypto::decryptIfNeeded).toLong()
            )
        }.getOrNull()
    }

    override suspend fun write(providerId: Long, token: MinistraApiV3Token) {
        preferences.edit()
            .putString("$providerId.access", credentialCrypto.encryptIfNeeded(token.accessToken))
            .putString("$providerId.refresh", credentialCrypto.encryptIfNeeded(token.refreshToken))
            .putString("$providerId.expiry", credentialCrypto.encryptIfNeeded(token.expiresAt.toString()))
            .apply()
    }

    override suspend fun clear(providerId: Long) {
        preferences.edit()
            .remove("$providerId.access")
            .remove("$providerId.refresh")
            .remove("$providerId.expiry")
            .apply()
        Unit
    }
}

/** Transport contract for a provider-documented API-v3 deployment. */
interface MinistraApiV3Client {
    suspend fun authenticate(credentials: MinistraApiV3Credentials): MinistraApiV3Token
    suspend fun refresh(refreshToken: String): MinistraApiV3Token
    suspend fun liveCategories(token: String): List<Category>
    suspend fun live(token: String, categoryId: Long?): List<Channel>
    suspend fun vodCategories(token: String): List<Category>
    suspend fun vod(token: String, categoryId: Long?): List<Movie>
    suspend fun vodInfo(token: String, id: Long): Movie
    suspend fun seriesCategories(token: String): List<Category>
    suspend fun series(token: String, categoryId: Long?): List<Series>
    suspend fun seriesInfo(token: String, id: Long): Series
    suspend fun epg(token: String, channelId: String, limit: Int?): List<Program>
    suspend fun playback(token: String, type: ContentType, id: Long): String
    suspend fun catchUp(token: String, id: Long, start: Long, end: Long): String?
}

class MinistraLicenseRequiredException(message: String) : IOException(message)
class MinistraApiV3CompatibilityException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Android MAG support. This class cannot share classic cookies, tokens, fingerprints, or identity
 * generation. A concrete client is enabled only for a provider-documented API-v3 contract.
 */
class MinistraApiV3Provider(
    val providerId: Long,
    private val credentials: MinistraApiV3Credentials,
    private val requestedProfileId: String,
    private val client: MinistraApiV3Client,
    private val tokenStore: MinistraApiV3TokenStore
) : ProviderAuthenticator,
    LiveCatalogSource,
    VodCatalogSource,
    SeriesCatalogSource,
    GuideSource,
    PlaybackResolver,
    CatchUpSource {
    private var memoryToken: MinistraApiV3Token? = null

    override suspend fun authenticate(): Result<Provider> = apiCall("Ministra API-v3 authentication failed") {
        token(forceLogin = true)
        Provider(
            id = providerId,
            name = credentials.login.ifBlank { "Ministra API v3" },
            type = ProviderType.STALKER_PORTAL,
            serverUrl = credentials.portalUrl,
            username = credentials.login,
            password = credentials.password,
            stalkerMacAddress = credentials.macAddress,
            stalkerSerialNumber = credentials.serialNumber,
            stalkerProtocolPreference = StalkerProtocolPreference.MINISTRA_API_V3,
            stalkerRequestedProfileId = requestedProfileId.ifBlank { StalkerCompatibilityProfileIds.AUTO },
            stalkerLearnedProfileId = requestedProfileId,
            stalkerProfileRevision = StalkerCompatibilityRegistry.REVISION,
            stalkerProfileVerification = StalkerCompatibilityRegistry.find(requestedProfileId)?.verification
                ?: StalkerProfileVerification.UNVERIFIED,
            stalkerProtocolFamily = StalkerProtocolFamily.MINISTRA_API_V3,
            apiVersion = "Ministra API v3",
            status = ProviderStatus.PARTIAL
        )
    }

    override suspend fun getLiveCategories() = apiCall("Failed to load API-v3 live categories") { client.liveCategories(token().accessToken) }
    override suspend fun getLiveStreams(categoryId: Long?) = apiCall("Failed to load API-v3 live channels") { client.live(token().accessToken, categoryId) }
    override suspend fun getVodCategories() = apiCall("Failed to load API-v3 VOD categories") { client.vodCategories(token().accessToken) }
    override suspend fun getVodStreams(categoryId: Long?) = apiCall("Failed to load API-v3 VOD") { client.vod(token().accessToken, categoryId) }
    override suspend fun getVodInfo(vodId: Long) = apiCall("Failed to load API-v3 VOD details") { client.vodInfo(token().accessToken, vodId) }
    override suspend fun getSeriesCategories() = apiCall("Failed to load API-v3 series categories") { client.seriesCategories(token().accessToken) }
    override suspend fun getSeriesList(categoryId: Long?) = apiCall("Failed to load API-v3 series") { client.series(token().accessToken, categoryId) }
    override suspend fun getSeriesInfo(seriesId: Long) = apiCall("Failed to load API-v3 series details") { client.seriesInfo(token().accessToken, seriesId) }
    override suspend fun getEpg(channelId: String) = apiCall("Failed to load API-v3 EPG") { client.epg(token().accessToken, channelId, null) }
    override suspend fun getShortEpg(channelId: String, limit: Int) = apiCall("Failed to load API-v3 EPG") { client.epg(token().accessToken, channelId, limit) }
    override suspend fun buildStreamUrl(streamId: Long, containerExtension: String?): String = client.playback(token().accessToken, ContentType.LIVE, streamId)
    override suspend fun buildCatchUpUrl(streamId: Long, start: Long, end: Long): String? = client.catchUp(token().accessToken, streamId, start, end)

    private suspend fun token(forceLogin: Boolean = false): MinistraApiV3Token {
        val cached = memoryToken ?: tokenStore.read(providerId)
        if (!forceLogin && cached?.isUsable() == true) return cached.also { memoryToken = it }
        val renewed = if (!forceLogin && !cached?.refreshToken.isNullOrBlank()) {
            runCatching { client.refresh(cached!!.refreshToken) }.getOrNull()
        } else null
        val resolved = renewed ?: client.authenticate(credentials)
        if (!resolved.isUsable()) throw MinistraApiV3CompatibilityException("API-v3 returned an invalid or expired token.")
        memoryToken = resolved
        tokenStore.write(providerId, resolved)
        return resolved
    }

    private suspend fun <T> apiCall(message: String, block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (error: Exception) {
        Result.error(error.message ?: message, error)
    }
}
