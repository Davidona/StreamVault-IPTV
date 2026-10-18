package com.streamvault.feature.live.sports

import com.streamvault.domain.sports.provider.LeagueMetadataProvider
import com.streamvault.domain.sports.provider.CUSTOM_SPORTS_PROVIDER_TYPE_ID
import com.streamvault.domain.sports.provider.ProviderLeagueMetadata
import com.streamvault.domain.sports.provider.ProviderLeagueMetadataRequest
import com.streamvault.domain.sports.provider.ProviderTeamMetadata
import com.streamvault.domain.sports.provider.ProviderTeamMetadataRequest
import com.streamvault.domain.sports.provider.SportsCapability
import com.streamvault.domain.sports.provider.SportsMetadataSourceMode
import com.streamvault.domain.sports.provider.SportsMetadataSourcePreference
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderResult
import com.streamvault.domain.sports.provider.SportsSourceRef
import com.streamvault.domain.sports.provider.SourcedValue
import com.streamvault.domain.sports.provider.TeamMetadataProvider
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal data class LoadedSportsProviderMetadata(
    val instanceId: String? = null,
    val typeId: String? = null,
    val providerName: String? = null,
    val teams: Map<String, LoadedMetadataValue<ProviderTeamMetadata>> = emptyMap(),
    val leagues: Map<String, LoadedMetadataValue<ProviderLeagueMetadata>> = emptyMap(),
    val scopedTeams: Map<ProviderMetadataKey, LoadedMetadataValue<ProviderTeamMetadata>> = emptyMap(),
    val scopedLeagues: Map<ProviderMetadataKey, LoadedMetadataValue<ProviderLeagueMetadata>> = emptyMap(),
    val eventCatalogs: Map<ProviderEventKey, com.streamvault.domain.sports.provider.ProviderEvent> = emptyMap(),
)

internal data class LoadedMetadataValue<out T>(val value: T, val isStale: Boolean)

internal data class ProviderEventKey(val instanceId: String, val typeId: String, val eventId: String)
internal data class ProviderMetadataKey(val id: String, val sport: String?, val leagueId: String?)

/** Bounded metadata enrichment; stable provider IDs and account-scoped caches avoid fuzzy API fan-out. */
@Singleton
internal class SportsProviderMetadataRouter @Inject constructor(
    private val registry: SportsProviderRegistry,
) {
    private val teamCache = object : LinkedHashMap<MetadataCacheKey, CachedTeam>(32, 0.75f, true) {}
    private val leagueCache = object : LinkedHashMap<MetadataCacheKey, CachedLeague>(16, 0.75f, true) {}
    private val failureCooldowns = object : LinkedHashMap<SportsProviderConfig, Instant>(16, 0.75f, true) {}

    fun eligibleProviders(
        configs: Collection<SportsProviderConfig>,
        preference: SportsMetadataSourcePreference,
        eventProviderInstanceId: String?,
        eventProviderInstanceIds: Set<String>,
        eventProviderTypeIds: Set<String>,
    ): List<SportsProviderConfig> {
        if (preference.mode == SportsMetadataSourceMode.OFF) return emptyList()
        val candidates = configs.filter { config ->
            config.enabled && (
                config.instanceId in eventProviderInstanceIds ||
                    (config.typeId != CUSTOM_SPORTS_PROVIDER_TYPE_ID && config.typeId in eventProviderTypeIds)
                ) && registry.capabilities(config).any {
                    it == SportsCapability.TEAM_METADATA || it == SportsCapability.LEAGUE_METADATA
                }
        }
        val ordered = candidates.sortedWith(
            compareBy<SportsProviderConfig> { if (it.instanceId == eventProviderInstanceId) 0 else 1 }
                .thenBy(SportsProviderConfig::priority)
                .thenBy(SportsProviderConfig::instanceId),
        )
        return when (preference.mode) {
            SportsMetadataSourceMode.AUTOMATIC -> ordered
            SportsMetadataSourceMode.OFF -> emptyList()
            SportsMetadataSourceMode.PROVIDER -> ordered.filter { it.instanceId == preference.providerInstanceId }
        }
    }

    suspend fun load(
        configs: Collection<SportsProviderConfig>,
        preference: SportsMetadataSourcePreference,
        eventCatalogs: List<LoadedSportsProviderEvents>,
        eventProviderInstanceId: String?,
        now: Instant = Instant.now(),
    ): LoadedSportsProviderMetadata {
        val catalogInstanceIds = eventCatalogs.mapNotNullTo(mutableSetOf(), LoadedSportsProviderEvents::instanceId)
        val catalogTypeIds = eventCatalogs.mapNotNullTo(mutableSetOf(), LoadedSportsProviderEvents::typeId)
        val providers = eligibleProviders(
            configs, preference, eventProviderInstanceId, catalogInstanceIds, catalogTypeIds,
        )
        for (config in providers) {
            // Custom REST definitions can point at unrelated ID spaces despite sharing one type ID.
            val compatibleCatalogs = eventCatalogs.filter { catalog ->
                catalog.typeId == config.typeId &&
                    (catalog.instanceId == config.instanceId || config.typeId != CUSTOM_SPORTS_PROVIDER_TYPE_ID)
            }
            val providerEvents = compatibleCatalogs.flatMap { it.events }.distinctBy { it.providerEventId }
            val eventIndex = compatibleCatalogs.flatMap { catalog ->
                catalog.events.map { event ->
                    ProviderEventKey(catalog.instanceId!!, catalog.typeId!!, event.providerEventId) to event
                }
            }.toMap()
            val provider = try {
                registry.create(config)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                continue
            }
            val teams = linkedMapOf<String, LoadedMetadataValue<ProviderTeamMetadata>>()
            val leagues = linkedMapOf<String, LoadedMetadataValue<ProviderLeagueMetadata>>()
            val scopedTeams = linkedMapOf<ProviderMetadataKey, LoadedMetadataValue<ProviderTeamMetadata>>()
            val scopedLeagues = linkedMapOf<ProviderMetadataKey, LoadedMetadataValue<ProviderLeagueMetadata>>()
            val teamRequests = providerEvents.flatMap { event ->
                event.competitors.mapNotNull { competitor ->
                    competitor.id?.let {
                        metadataKey(config.typeId, it, event.sport, event.league?.id)
                    }
                }
            }.distinct().take(MAX_TEAM_REQUESTS)
            val leagueRequests = providerEvents.mapNotNull { event ->
                event.league?.let { league ->
                    league.id?.let { leagueId ->
                        metadataKey(config.typeId, leagueId, event.sport ?: league.sport, leagueId)
                    }
                }
            }.distinct().take(MAX_LEAGUE_REQUESTS)

            val capabilities = registry.capabilities(config)
            if (provider is TeamMetadataProvider && SportsCapability.TEAM_METADATA in capabilities) {
                for (teamRequest in teamRequests) {
                    val value = loadTeam(
                        config,
                        provider,
                        teamRequest.id,
                        teamRequest.sport,
                        teamRequest.leagueId,
                        now,
                    )
                    if (value != null) {
                        teams[teamRequest.id] = value
                        scopedTeams[teamRequest] = value
                    }
                }
            }
            if (provider is LeagueMetadataProvider &&
                SportsCapability.LEAGUE_METADATA in capabilities
            ) {
                for (leagueRequest in leagueRequests) {
                    val value = loadLeague(
                        config,
                        provider,
                        leagueRequest.id,
                        leagueRequest.sport,
                        now,
                    )
                    if (value != null) {
                        leagues[leagueRequest.id] = value
                        scopedLeagues[leagueRequest] = value
                    }
                }
            }
            if (scopedTeams.isNotEmpty() || scopedLeagues.isNotEmpty()) {
                return LoadedSportsProviderMetadata(
                    instanceId = config.instanceId,
                    typeId = config.typeId,
                    providerName = registry.descriptor(config.typeId)?.displayName ?: config.typeId,
                    teams = teams,
                    leagues = leagues,
                    scopedTeams = scopedTeams,
                    scopedLeagues = scopedLeagues,
                    eventCatalogs = eventIndex,
                )
            }
        }
        return LoadedSportsProviderMetadata()
    }

    fun enrichEvents(
        events: List<SportsEvent>,
        metadata: LoadedSportsProviderMetadata,
    ): List<SportsEvent> {
        val instanceId = metadata.instanceId ?: return events
        val typeId = metadata.typeId ?: return events
        if (metadata.teams.isEmpty() && metadata.leagues.isEmpty() &&
            metadata.scopedTeams.isEmpty() && metadata.scopedLeagues.isEmpty()
        ) return events
        return events.map { event ->
            val sourceAndProviderEvent = event.provenance.sources.asSequence()
                .filterIsInstance<SportsSourceRef.Provider>()
                .filter { it.typeId == typeId }
                .mapNotNull { source ->
                    metadata.eventCatalogs[ProviderEventKey(source.instanceId, source.typeId, source.externalEventId)]
                        ?.let { source to it }
                }
                .firstOrNull() ?: return@map event
            val (eventSource, providerEvent) = sourceAndProviderEvent
            val teams = providerEvent.competitors.mapNotNull { competitor ->
                competitor.id?.let {
                    metadata.team(typeId, it, providerEvent.sport, providerEvent.league?.id)
                }?.value
            }.distinctBy { it.team.id ?: it.team.name }
            val teamMetadataStale = providerEvent.competitors.mapNotNull { competitor ->
                competitor.id?.let {
                    metadata.team(typeId, it, providerEvent.sport, providerEvent.league?.id)
                }
            }.any(LoadedMetadataValue<ProviderTeamMetadata>::isStale)
            val leagueResult = providerEvent.league?.id?.let { leagueId ->
                metadata.league(typeId, metadataKey(
                    typeId,
                    leagueId,
                    providerEvent.sport ?: providerEvent.league?.sport,
                    leagueId,
                ))
            }
            val league = leagueResult?.value
            val source = SportsSourceRef.Provider(
                instanceId = instanceId,
                typeId = typeId,
                externalEventId = eventSource.externalEventId,
                sport = eventSource.sport,
                leagueId = eventSource.leagueId,
            )
            val teamValue = teams.takeIf { it.isNotEmpty() }?.let {
                SourcedValue(
                    it,
                    source,
                    teams.mapNotNull(ProviderTeamMetadata::updatedAt).maxOrNull(),
                    isStale = teamMetadataStale,
                )
            }
            val leagueValue = league?.let {
                SourcedValue(it, source, it.updatedAt, isStale = leagueResult?.isStale == true)
            }
            event.copy(
                metadataAttributionName = metadata.providerName,
                provenance = event.provenance.copy(
                    sources = event.provenance.sources + source,
                    teamMetadata = teamValue ?: event.provenance.teamMetadata,
                    leagueMetadata = leagueValue ?: event.provenance.leagueMetadata,
                ),
            )
        }
    }

    private suspend fun loadTeam(
        config: SportsProviderConfig,
        provider: TeamMetadataProvider,
        teamId: String,
        sport: String?,
        leagueId: String?,
        now: Instant,
    ): LoadedMetadataValue<ProviderTeamMetadata>? {
        val key = MetadataCacheKey(config, teamId, sport, leagueId)
        val cached = synchronized(teamCache) { teamCache[key] }
        if (cached != null && now.isBefore(cached.fetchedAt.plus(CACHE_TTL))) {
            return LoadedMetadataValue(cached.value, isStale = false)
        }
        val cooling = isCoolingDown(config, now)
        if (cooling) return cached?.takeIf { now.isBefore(it.fetchedAt.plus(STALE_CACHE_TTL)) }
            ?.let { LoadedMetadataValue(it.value, isStale = true) }
        val result = try {
            provider.teamMetadata(ProviderTeamMetadataRequest(teamId, sport, leagueId))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return when (result) {
            is SportsProviderResult.Success -> {
                val value = result.value
                if (value.team.id != teamId) {
                    recordFailure(config, retryAt = null, now)
                    cached?.takeIf { now.isBefore(it.fetchedAt.plus(STALE_CACHE_TTL)) }
                        ?.let { LoadedMetadataValue(it.value, isStale = true) }
                } else {
                    synchronized(teamCache) {
                        teamCache[key] = CachedTeam(value, now)
                        trim(teamCache, MAX_TEAM_CACHE_ENTRIES)
                    }
                    clearCooldown(config)
                    LoadedMetadataValue(value, isStale = false)
                }
            }
            is SportsProviderResult.Failure, null -> {
                recordFailure(config, (result as? SportsProviderResult.Failure)?.retryAt, now)
                cached?.takeIf { now.isBefore(it.fetchedAt.plus(STALE_CACHE_TTL)) }
                    ?.let { LoadedMetadataValue(it.value, isStale = true) }
            }
        }
    }

    private suspend fun loadLeague(
        config: SportsProviderConfig,
        provider: LeagueMetadataProvider,
        leagueId: String,
        sport: String?,
        now: Instant,
    ): LoadedMetadataValue<ProviderLeagueMetadata>? {
        val key = MetadataCacheKey(config, leagueId, sport, leagueId)
        val cached = synchronized(leagueCache) { leagueCache[key] }
        if (cached != null && now.isBefore(cached.fetchedAt.plus(CACHE_TTL))) {
            return LoadedMetadataValue(cached.value, isStale = false)
        }
        if (isCoolingDown(config, now)) {
            return cached?.takeIf { now.isBefore(it.fetchedAt.plus(STALE_CACHE_TTL)) }
                ?.let { LoadedMetadataValue(it.value, isStale = true) }
        }
        val result = try {
            provider.leagueMetadata(ProviderLeagueMetadataRequest(leagueId, sport))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        return when (result) {
            is SportsProviderResult.Success -> {
                val value = result.value
                if (value.league.id != leagueId) {
                    recordFailure(config, retryAt = null, now)
                    cached?.takeIf { now.isBefore(it.fetchedAt.plus(STALE_CACHE_TTL)) }
                        ?.let { LoadedMetadataValue(it.value, isStale = true) }
                } else {
                    synchronized(leagueCache) {
                        leagueCache[key] = CachedLeague(value, now)
                        trim(leagueCache, MAX_LEAGUE_CACHE_ENTRIES)
                    }
                    clearCooldown(config)
                    LoadedMetadataValue(value, isStale = false)
                }
            }
            is SportsProviderResult.Failure, null -> {
                recordFailure(config, (result as? SportsProviderResult.Failure)?.retryAt, now)
                cached?.takeIf { now.isBefore(it.fetchedAt.plus(STALE_CACHE_TTL)) }
                    ?.let { LoadedMetadataValue(it.value, isStale = true) }
            }
        }
    }

    private fun isCoolingDown(config: SportsProviderConfig, now: Instant): Boolean =
        synchronized(failureCooldowns) { failureCooldowns[config]?.isAfter(now) == true }

    private fun metadataKey(
        typeId: String,
        id: String,
        sport: String?,
        leagueId: String?,
    ) = if (typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID) {
        ProviderMetadataKey(id, sport, leagueId)
    } else {
        ProviderMetadataKey(id, sport = null, leagueId = null)
    }

    private fun LoadedSportsProviderMetadata.team(
        typeId: String,
        id: String,
        sport: String?,
        leagueId: String?,
    ): LoadedMetadataValue<ProviderTeamMetadata>? =
        scopedTeams[metadataKey(typeId, id, sport, leagueId)]
            ?: teams[id].takeIf { typeId != CUSTOM_SPORTS_PROVIDER_TYPE_ID }

    private fun LoadedSportsProviderMetadata.league(
        typeId: String,
        key: ProviderMetadataKey,
    ): LoadedMetadataValue<ProviderLeagueMetadata>? =
        scopedLeagues[key]
            ?: leagues[key.id].takeIf { typeId != CUSTOM_SPORTS_PROVIDER_TYPE_ID }

    private fun clearCooldown(config: SportsProviderConfig) {
        synchronized(failureCooldowns) { failureCooldowns.remove(config) }
    }

    private fun recordFailure(config: SportsProviderConfig, retryAt: Instant?, now: Instant) {
        synchronized(failureCooldowns) {
            failureCooldowns[config] = retryAt?.takeIf { it.isAfter(now) } ?: now.plus(FAILURE_COOLDOWN)
            trim(failureCooldowns, MAX_LEAGUE_CACHE_ENTRIES)
        }
    }

    private data class MetadataCacheKey(
        val config: SportsProviderConfig,
        val entityId: String,
        val sport: String?,
        val leagueId: String?,
    )
    private data class CachedTeam(val value: ProviderTeamMetadata, val fetchedAt: Instant)
    private data class CachedLeague(val value: ProviderLeagueMetadata, val fetchedAt: Instant)

    private fun <K, V> trim(cache: LinkedHashMap<K, V>, maxSize: Int) {
        while (cache.size > maxSize) cache.entries.iterator().run { next(); remove() }
    }

    private companion object {
        val CACHE_TTL: Duration = Duration.ofHours(24)
        val STALE_CACHE_TTL: Duration = Duration.ofDays(7)
        val FAILURE_COOLDOWN: Duration = Duration.ofMinutes(10)
        const val MAX_TEAM_REQUESTS = 24
        const val MAX_LEAGUE_REQUESTS = 12
        const val MAX_TEAM_CACHE_ENTRIES = 256
        const val MAX_LEAGUE_CACHE_ENTRIES = 64
    }
}
