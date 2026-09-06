package com.streamvault.feature.playback.player

import com.streamvault.domain.model.LegacyProvider
import com.streamvault.domain.model.Result
import com.streamvault.domain.provider.PlayerCredentialFailure
import com.streamvault.domain.repository.ProviderRepository
import javax.inject.Inject

/** Feature-owned provider access for playback, catch-up, and recovery decisions. */
class PlayerProviderCoordinator @Inject constructor(
    private val repository: ProviderRepository
) {
    internal suspend fun getProvider(providerId: Long): LegacyProvider? =
        repository.getProvider(providerId)

    internal suspend fun buildCatchUpUrls(
        providerId: Long,
        streamId: Long,
        start: Long,
        end: Long
    ): Result<List<String>> = try {
        Result.success(repository.buildCatchUpUrls(providerId, streamId, start, end))
    } catch (error: PlayerCredentialFailure) {
        Result.error(error.message ?: "Stored provider credentials are unavailable.", error)
    }
}
