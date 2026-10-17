package com.streamvault.domain.sports.provider

import java.net.URI
import java.time.Instant

/** Data capabilities only. A sports-data provider can never supply playback URLs. */
enum class SportsCapability {
    EVENTS, LIVE_SCORES, GAME_STATUS, STANDINGS, TEAM_METADATA, LEAGUE_METADATA,
    ARTWORK, BROADCASTERS, LINEUPS, STATISTICS,
}

const val CUSTOM_SPORTS_PROVIDER_TYPE_ID = "custom-json"
const val CUSTOM_SPORTS_PROVIDER_MAX_CONFIGURATION_CHARS = 32 * 1024

/** Describes credential placement; secrets are stored separately from configuration. */
sealed interface SportsProviderAuth {
    data object None : SportsProviderAuth
    data class ApiKeyHeader(val name: String) : SportsProviderAuth {
        init {
            require(name.matches(Regex("[A-Za-z0-9_-]+")))
            require(name.lowercase() !in setOf(
                "host", "connection", "content-length", "transfer-encoding", "cookie",
                "proxy-authorization", "accept-encoding",
            ))
        }
    }
    data class ApiKeyQuery(val name: String) : SportsProviderAuth {
        init { require(name.matches(Regex("[A-Za-z0-9_-]+"))) }
    }
    data object Bearer : SportsProviderAuth
    data object Basic : SportsProviderAuth
    /** OAuth flows and refresh are implemented by a reviewed adapter, not a generic URL. */
    data class OAuth2(val adapterId: String) : SportsProviderAuth {
        init { require(adapterId.isNotBlank()) }
    }
}

data class SportsProviderDescriptor(
    val typeId: String,
    val capabilities: Set<SportsCapability>,
    val auth: SportsProviderAuth,
    /** Optional human-readable provider name used for data attribution. */
    val displayName: String? = null,
    val configurationVersion: Int = 1,
    val defaultBaseUrl: String? = null,
    val supportsCustomBaseUrl: Boolean = false,
    val compliance: ProviderComplianceInfo? = null,
    /** True when adapter behavior has fixture coverage but has not been checked against a live account. */
    val isBeta: Boolean = false,
) {
    init {
        require(typeId.matches(Regex("[a-z0-9][a-z0-9._-]*")))
        require(configurationVersion > 0)
        defaultBaseUrl?.let(::requireSportsBaseUrl)
    }
}

/** One account/endpoint instance; typeId is intentionally not its unique identity. */
data class SportsProviderConfig(
    val instanceId: String,
    val typeId: String,
    val configurationVersion: Int = 1,
    val enabled: Boolean = true,
    val priority: Int = 0,
    val baseUrl: String? = null,
    /** Optional provider-neutral event query scope, such as the leagues an account follows. */
    val eventLeagueIds: List<String> = emptyList(),
    /** Declarative custom-provider mapping. It is stored inside the encrypted provider envelope. */
    val customDefinitionJson: String? = null,
    /** Incremented when an account is edited so process-local provider caches cannot cross edits. */
    val cacheRevision: Long = 0,
) {
    init {
        require(instanceId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")))
        require(typeId.matches(Regex("[a-z0-9][a-z0-9._-]*")))
        require(configurationVersion > 0)
        require(cacheRevision >= 0)
        baseUrl?.let(::requireSportsBaseUrl)
        require(eventLeagueIds.size <= MAX_EVENT_LEAGUE_IDS)
        require(eventLeagueIds.all { it.matches(EVENT_LEAGUE_ID) })
        require(eventLeagueIds.distinct().size == eventLeagueIds.size)
        require(customDefinitionJson == null || typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID)
        require(customDefinitionJson == null ||
            customDefinitionJson.length <= CUSTOM_SPORTS_PROVIDER_MAX_CONFIGURATION_CHARS)
    }

    override fun toString(): String =
        "SportsProviderConfig(instanceId=$instanceId, typeId=$typeId, version=$configurationVersion, enabled=$enabled)"

    private companion object {
        const val MAX_EVENT_LEAGUE_IDS = 50
        val EVENT_LEAGUE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    }
}

/** HTTPS endpoints have no inline credentials, query keys, or fragments. */
private fun requireSportsBaseUrl(value: String) {
    val uri = try { URI(value) } catch (_: Exception) { throw IllegalArgumentException() }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank())
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
}

/** Deliberately not a data class: generated toString/copy/component methods leak secrets. */
class SportsProviderCredentials(val secret: String, val username: String? = null) {
    override fun toString(): String = "SportsProviderCredentials([REDACTED])"
}

interface SportsProvider {
    val descriptor: SportsProviderDescriptor
    val instanceId: String
}

interface SportsProviderFactory {
    val descriptor: SportsProviderDescriptor

    /** Must preserve instance identity and leave credentials untouched during migration. */
    fun migrate(config: SportsProviderConfig): SportsProviderConfig = config

    /** Account-level capability gates may narrow a generic adapter's declared capability set. */
    fun capabilitiesFor(config: SportsProviderConfig): Set<SportsCapability> = descriptor.capabilities

    /** Account-level display names are useful for user-configured providers. */
    fun displayNameFor(config: SportsProviderConfig): String? = descriptor.displayName

    fun create(config: SportsProviderConfig): SportsProvider
}

enum class SportsProviderError {
    AUTHENTICATION, RATE_LIMITED, SERVER, NETWORK, INVALID_RESPONSE,
    INVALID_CONFIGURATION, RESPONSE_TOO_LARGE, HTTP,
}

/** No server body, URL, exception message, or Throwable may cross this boundary. */
sealed interface SportsProviderResult<out T> {
    data class Success<T>(val value: T) : SportsProviderResult<T>
    data class Failure(
        val category: SportsProviderError,
        val httpStatus: Int? = null,
        val retryAt: Instant? = null,
    ) : SportsProviderResult<Nothing>
}

data class SportsProviderHealth(
    val lastSuccessAt: Instant? = null,
    val lastFailure: SportsProviderResult.Failure? = null,
) {
    fun after(result: SportsProviderResult<*>, now: Instant): SportsProviderHealth = when (result) {
        is SportsProviderResult.Success -> SportsProviderHealth(lastSuccessAt = now)
        is SportsProviderResult.Failure -> copy(lastFailure = result)
    }
}

/** Storage never participates in the general preferences export or cloud backup. */
interface SportsProviderStore {
    suspend fun configurations(): List<SportsProviderConfig>
    suspend fun credentials(instanceId: String): SportsProviderCredentials?
    suspend fun credentialedInstanceIds(): Set<String> = configurations().mapNotNull { config ->
        config.instanceId.takeIf { credentials(it) != null }
    }.toSet()
    /** Null preserves an existing secret, allowing non-secret configuration migrations. */
    suspend fun save(config: SportsProviderConfig, credentials: SportsProviderCredentials? = null)
    suspend fun remove(instanceId: String)
}
