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
import com.streamvault.domain.sports.provider.BroadcastProvider
import com.streamvault.domain.sports.provider.ProviderEventDetailRequest
import com.streamvault.domain.sports.provider.ProviderAttribution
import com.streamvault.domain.sports.provider.SourcedValue
import com.streamvault.domain.sports.provider.SportsSourceRef
import com.streamvault.domain.sports.provider.SportsEventProvenance
import com.streamvault.domain.sports.provider.CUSTOM_SPORTS_PROVIDER_TYPE_ID
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

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
    private val broadcasterCooldowns = linkedMapOf<SportsProviderConfig, Instant>()

    fun attributions(provenance: SportsEventProvenance): List<ProviderAttribution> = provenance.sources
        .filterIsInstance<SportsSourceRef.Provider>()
        .mapNotNull { registry.descriptor(it.typeId)?.compliance?.attribution }
        .distinctBy(ProviderAttribution::sourceName).sortedBy(ProviderAttribution::sourceName)

    /** TV metadata is only needed for events without a usable EPG broadcast. Never replace one. */
    suspend fun enrichBroadcasters(
        events: List<SportsResolvedEvent>,
        configs: Collection<SportsProviderConfig>,
        now: Instant,
    ): List<SportsResolvedEvent> {
        val eligible = registry.eligible(configs, SportsCapability.BROADCASTERS).associateBy { it.instanceId }
        val candidates = events.filter { event ->
            event.epgEvent?.broadcasts.orEmpty().none { it.channel.streamUrl.isNotBlank() } &&
                event.provenance.broadcasters?.value.isNullOrEmpty()
        }.sortedBy { event ->
            event.providerEvent?.value?.eventStart?.let { java.time.Duration.between(now, it).abs() }
                ?: java.time.Duration.ofDays(365)
        }.take(12)
        val enriched = mutableMapOf<String, SportsResolvedEvent>()
        val coolingAccounts = mutableSetOf<String>()
        for (event in candidates) {
            val sourced = event.providerEvent ?: continue
            val source = sourced.source as? SportsSourceRef.Provider ?: continue
            val config = eligible[source.instanceId]?.takeIf { it.typeId == source.typeId } ?: continue
            if (source.instanceId in coolingAccounts) continue
            if (synchronized(broadcasterCooldowns) { broadcasterCooldowns[config]?.isAfter(now) == true }) continue
            val result = try {
                val provider = registry.create(config) as? BroadcastProvider ?: continue
                provider.broadcasters(ProviderEventDetailRequest(
                    eventId = source.externalEventId,
                    sport = sourced.value.sport.takeIf { config.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID },
                    leagueId = sourced.value.league?.id.takeIf { config.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID },
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { continue }
            when (result) {
                is SportsProviderResult.Failure -> {
                    coolingAccounts.add(source.instanceId)
                    synchronized(broadcasterCooldowns) {
                        broadcasterCooldowns[config] = result.retryAt?.takeIf { it.isAfter(now) }
                            ?: now.plusSeconds(300)
                        while (broadcasterCooldowns.size > 64) {
                            broadcasterCooldowns.entries.iterator().run { next(); remove() }
                        }
                    }
                }
                is SportsProviderResult.Success -> if (result.value.items.isNotEmpty()) {
                    enriched[event.id] = event.copy(provenance = event.provenance.copy(
                        broadcasters = SourcedValue(result.value.items, source, now),
                        sources = event.provenance.sources + source,
                    ))
                }
            }
        }
        return events.map { enriched[it.id] ?: it }
    }

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
            val completed = withTimeoutOrNull(20_000L) {
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
                        is SportsProviderResult.Failure -> SportsDiagnostics.providerFailure(
                            "events", result.category, result.httpStatus,
                        )
                    }
                }
            }
            if (completed == null) SportsDiagnostics.providerFailure(
                "events-timeout", com.streamvault.domain.sports.provider.SportsProviderError.NETWORK,
            )
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
