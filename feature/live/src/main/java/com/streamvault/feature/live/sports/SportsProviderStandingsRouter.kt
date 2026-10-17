package com.streamvault.feature.live.sports

import com.streamvault.domain.sports.provider.ProviderLeague
import com.streamvault.domain.sports.provider.ProviderStandings
import com.streamvault.domain.sports.provider.ProviderStandingsRequest
import com.streamvault.domain.sports.provider.ProviderAttribution
import com.streamvault.domain.sports.provider.StandingsProvider
import com.streamvault.domain.sports.provider.SportsCapability
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderResult
import com.streamvault.domain.sports.provider.SportsStandingsSourceMode
import com.streamvault.domain.sports.provider.SportsStandingsSourcePreference
import com.streamvault.domain.sports.provider.SportsSourceRef
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal data class LoadedSportsStandings(
    val standings: ProviderStandings,
    val instanceId: String,
    val typeId: String,
    val providerName: String,
    val attribution: ProviderAttribution?,
    val isStale: Boolean,
)

/** Loads one league at a time, with bounded provider traffic and account-scoped caching. */
@Singleton
internal class SportsProviderStandingsRouter @Inject constructor(
    private val registry: SportsProviderRegistry,
) {
    private val cache = object : LinkedHashMap<StandingsCacheKey, CachedStandings>(16, 0.75f, true) {}
    private val failureCooldowns = object : LinkedHashMap<SportsProviderConfig, Instant>(16, 0.75f, true) {}

    fun eligibleProviders(
        configs: Collection<SportsProviderConfig>,
        preference: SportsStandingsSourcePreference,
        eventLeagueSource: SportsSourceRef.Provider?,
    ): List<SportsProviderConfig> {
        if (preference.mode == SportsStandingsSourceMode.OFF) return emptyList()
        val eligible = registry.eligible(configs, SportsCapability.STANDINGS)
        return when (preference.mode) {
            SportsStandingsSourceMode.AUTOMATIC -> eligible.sortedWith(
                compareBy<SportsProviderConfig> {
                    if (it.instanceId == eventLeagueSource?.instanceId ||
                        it.typeId == eventLeagueSource?.typeId
                    ) 0 else 1
                }.thenBy(SportsProviderConfig::priority).thenBy(SportsProviderConfig::instanceId),
            )
            SportsStandingsSourceMode.OFF -> emptyList()
            SportsStandingsSourceMode.PROVIDER -> eligible.filter { it.instanceId == preference.providerInstanceId }
        }
    }

    suspend fun load(
        providers: List<SportsProviderConfig>,
        eventLeague: ProviderLeague?,
        eventLeagueSource: SportsSourceRef.Provider?,
        now: Instant = Instant.now(),
    ): LoadedSportsStandings? {
        val leagueName = eventLeague?.name?.trim()?.takeIf(String::isNotEmpty) ?: return null
        var staleFallback: LoadedSportsStandings? = null
        for (config in providers) {
            val sameProviderIdentity = config.instanceId == eventLeagueSource?.instanceId
            val scopedIds = config.eventLeagueIds
            val leagueIds = buildList {
                if (sameProviderIdentity) eventLeague?.id?.let(::add)
                addAll(scopedIds)
            }.distinct().take(MAX_STANDINGS_LEAGUES_PER_REQUEST)
            if (leagueIds.isEmpty()) continue

            val provider = try {
                registry.create(config) as? StandingsProvider
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } ?: continue

            for (leagueId in leagueIds) {
                val key = StandingsCacheKey(config, leagueId)
                val cached = synchronized(cache) { cache[key] }
                if (cached != null && now.isBefore(cached.fetchedAt.plus(CACHE_TTL))) {
                    if (matchesLeague(cached.value, leagueId, leagueName, sameProviderIdentity)) {
                        return loaded(config, cached.value, stale = false)
                    }
                    continue
                }
                val cooldownUntil = synchronized(failureCooldowns) { failureCooldowns[config] }
                if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
                    val stale = cached?.takeIf { now.isBefore(it.fetchedAt.plus(STALE_CACHE_TTL)) }
                    if (stale != null && matchesLeague(stale.value, leagueId, leagueName, sameProviderIdentity) &&
                        staleFallback == null
                    ) staleFallback = loaded(config, stale.value, stale = true)
                    continue
                }

                val result = try {
                    provider.standings(ProviderStandingsRequest(leagueId))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                when (result) {
                    is SportsProviderResult.Success -> {
                        val value = result.value
                        if (!matchesLeague(value, leagueId, leagueName, sameProviderIdentity)) continue
                        synchronized(cache) {
                            cache[key] = CachedStandings(value, now)
                            trim(cache, MAX_CACHE_ENTRIES)
                        }
                        synchronized(failureCooldowns) { failureCooldowns.remove(config) }
                        return loaded(config, value, stale = false)
                    }
                    is SportsProviderResult.Failure, null -> {
                        val retryAt = (result as? SportsProviderResult.Failure)?.retryAt
                        synchronized(failureCooldowns) {
                            failureCooldowns[config] = retryAt?.takeIf { it.isAfter(now) }
                                ?: now.plus(FAILURE_COOLDOWN)
                            trim(failureCooldowns, MAX_CACHE_ENTRIES)
                        }
                        val stale = cached?.takeIf { now.isBefore(it.fetchedAt.plus(STALE_CACHE_TTL)) }
                        if (stale != null && matchesLeague(stale.value, leagueId, leagueName, sameProviderIdentity) &&
                            staleFallback == null
                        ) staleFallback = loaded(config, stale.value, stale = true)
                        break
                    }
                }
            }
        }
        return staleFallback
    }

    private fun matchesLeague(
        standings: ProviderStandings,
        requestedId: String,
        requestedName: String,
        sameProviderIdentity: Boolean,
    ): Boolean {
        val returnedId = standings.league.id
        if (returnedId != null && returnedId != requestedId) return false
        if (sameProviderIdentity || returnedId == requestedId) return true
        val returnedName = standings.league.name?.trim()?.takeIf(String::isNotEmpty) ?: return false
        return normalize(returnedName) == normalize(requestedName)
    }

    private fun loaded(config: SportsProviderConfig, value: ProviderStandings, stale: Boolean) =
        LoadedSportsStandings(
            standings = value,
            instanceId = config.instanceId,
            typeId = config.typeId,
            providerName = registry.descriptor(config.typeId)?.displayName ?: config.typeId,
            attribution = registry.descriptor(config.typeId)?.compliance?.attribution,
            isStale = stale,
        )

    private fun normalize(value: String): String = value.lowercase().filter(Char::isLetterOrDigit)

    private data class StandingsCacheKey(val config: SportsProviderConfig, val leagueId: String)
    private data class CachedStandings(val value: ProviderStandings, val fetchedAt: Instant)

    private fun <K, V> trim(cache: LinkedHashMap<K, V>, maxSize: Int) {
        while (cache.size > maxSize) cache.entries.iterator().run { next(); remove() }
    }

    private companion object {
        val CACHE_TTL: Duration = Duration.ofMinutes(30)
        val STALE_CACHE_TTL: Duration = Duration.ofHours(12)
        val FAILURE_COOLDOWN: Duration = Duration.ofMinutes(5)
        const val MAX_STANDINGS_LEAGUES_PER_REQUEST = 10
        const val MAX_CACHE_ENTRIES = 64
    }
}
