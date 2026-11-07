package com.streamvault.domain.repository

import com.streamvault.domain.manager.ProviderCredentials
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerCatalogMode
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerProtocolPreference
import com.streamvault.domain.model.StalkerTransportGrant
import kotlinx.coroutines.flow.Flow

sealed interface ProviderSetupRequest {
    data class Configured(
        val name: String,
        val configuration: com.streamvault.domain.model.ProviderConfiguration,
        val existingProviderId: Long? = null,
        val saveWithoutVerification: Boolean = false,
        val repairConnection: Boolean = false
    ) : ProviderSetupRequest

    data class JellyfinQuickConnect(
        val serverUrl: String,
        val name: String,
        val existingProviderId: Long? = null
    ) : ProviderSetupRequest
}

data class LiveStreamProgramRequest(
    val streamId: Long,
    val epgChannelId: String? = null
)

data class ProviderDeleteProgress(
    val message: String,
    val fraction: Float? = null
)

data class ProviderDeleteOutcome(
    val providerId: Long,
    val pendingCleanupActions: Int,
    val reconciliationRequested: Boolean
) {
    val cleanupPending: Boolean get() = pendingCleanupActions > 0
}

interface ProviderRepository {
    /** Reconcile saved Live ordering after a complete catalog refresh. */
    suspend fun reconcileLiveOrderingAfterRefresh(providerId: Long) = Unit
    fun getProviders(): Flow<List<Provider>>
    fun getActiveProvider(): Flow<Provider?>
    suspend fun getProvider(id: Long): Provider?
    suspend fun addProvider(provider: Provider): Result<Long>
    suspend fun updateProvider(provider: Provider): Result<Unit>
    suspend fun deleteProvider(
        id: Long,
        onProgress: ((ProviderDeleteProgress) -> Unit)? = null
    ): Result<ProviderDeleteOutcome>

    /**
     * Returns cleartext credentials for all providers that have both a
     * username and a password. Used by the Drive credentials sync path
     * (M3). Decryption happens inside the `:data` layer — the cleartext
     * payload is only ever exposed via this single typed method.
     */
    suspend fun getAllProviderCredentials(): List<ProviderCredentials>

    /**
     * Applies a cleartext password to the provider matched by
     * `(serverUrl, username)`. Encryption happens inside the `:data`
     * layer. Returns true if a matching provider was found and updated.
     */
    suspend fun updateProviderPassword(
        serverUrl: String,
        username: String,
        cleartextPassword: String,
    ): Boolean

    suspend fun setActiveProvider(id: Long): Result<Unit>
    suspend fun setupProvider(
        request: ProviderSetupRequest,
        onProgress: ((String) -> Unit)? = null,
        onCode: ((String) -> Unit)? = null
    ): Result<Provider>
    suspend fun refreshProviderData(
        providerId: Long,
        force: Boolean = false,
        movieFastSyncOverride: Boolean? = null,
        epgSyncModeOverride: ProviderEpgSyncMode? = null,
        onProgress: ((String) -> Unit)? = null
    ): Result<Unit>
    suspend fun buildStalkerSearchIndexOnce(providerId: Long): Result<Unit> =
        Result.error("Complete Stalker indexing is unavailable")
    suspend fun getProgramsForLiveStream(
        providerId: Long,
        streamId: Long,
        epgChannelId: String? = null,
        limit: Int = 12
    ): Result<List<Program>>
    suspend fun getProgramsForLiveStreams(
        providerId: Long,
        requests: List<LiveStreamProgramRequest>,
        limit: Int = 12
    ): Map<LiveStreamProgramRequest, Result<List<Program>>> =
        requests.distinct().associateWith { request ->
            getProgramsForLiveStream(
                providerId = providerId,
                streamId = request.streamId,
                epgChannelId = request.epgChannelId,
                limit = limit
            )
        }
    suspend fun buildCatchUpUrl(providerId: Long, streamId: Long, start: Long, end: Long): String?
    suspend fun buildCatchUpUrls(providerId: Long, streamId: Long, start: Long, end: Long): List<String> =
        listOfNotNull(buildCatchUpUrl(providerId, streamId, start, end))
}
