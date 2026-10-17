package com.streamvault.feature.live.sports

import com.streamvault.domain.sports.provider.EventProvider
import com.streamvault.domain.sports.provider.ProviderEvent
import com.streamvault.domain.sports.provider.ProviderEventRequest
import com.streamvault.domain.sports.provider.SportsCapability
import com.streamvault.domain.sports.provider.SportsEventSourceMode
import com.streamvault.domain.sports.provider.SportsEventSourcePreference
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderResult
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal data class LoadedSportsProviderEvents(
    val instanceId: String? = null,
    val typeId: String? = null,
    val events: List<ProviderEvent> = emptyList(),
)

/** Applies event capability and account selection, then falls through failed providers safely. */
@Singleton
internal class SportsProviderEventSourceRouter @Inject constructor(
    private val registry: SportsProviderRegistry,
) {
    fun eligibleProviders(
        configs: Collection<SportsProviderConfig>,
        preference: SportsEventSourcePreference,
    ): List<SportsProviderConfig> {
        if (preference.mode == SportsEventSourceMode.EPG) return emptyList()
        val eligible = registry.eligible(configs, SportsCapability.EVENTS)
        return when (preference.mode) {
            SportsEventSourceMode.AUTOMATIC -> eligible
            SportsEventSourceMode.EPG -> emptyList()
            SportsEventSourceMode.PROVIDER -> eligible.filter { it.instanceId == preference.providerInstanceId }
        }
    }

    suspend fun load(
        providers: List<SportsProviderConfig>,
        from: Instant,
        until: Instant,
    ): LoadedSportsProviderEvents {
        for (config in providers) {
            val provider = try {
                registry.create(config) as? EventProvider
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: continue

            val leagueIds: List<String?> = config.eventLeagueIds.takeIf { it.isNotEmpty() }
                ?.map { it }
                ?: listOf(null)
            var succeeded = false
            val events = mutableListOf<ProviderEvent>()
            for (leagueId in leagueIds) {
                val result = try {
                    provider.events(ProviderEventRequest(from = from, until = until, leagueId = leagueId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    continue
                }
                when (result) {
                    is SportsProviderResult.Success -> {
                        succeeded = true
                        events += result.value.items
                    }
                    is SportsProviderResult.Failure -> Unit
                }
            }
            if (succeeded) {
                return LoadedSportsProviderEvents(
                    instanceId = config.instanceId,
                    typeId = config.typeId,
                    events = events.distinctBy(ProviderEvent::providerEventId),
                )
            }
        }
        return LoadedSportsProviderEvents()
    }
}
