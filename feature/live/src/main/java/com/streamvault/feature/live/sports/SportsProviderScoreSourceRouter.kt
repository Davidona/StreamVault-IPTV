package com.streamvault.feature.live.sports

import com.streamvault.domain.sports.provider.LiveScoreProvider
import com.streamvault.domain.sports.provider.ProviderEvent
import com.streamvault.domain.sports.provider.ProviderEventScope
import com.streamvault.domain.sports.provider.ProviderScore
import com.streamvault.domain.sports.provider.ProviderScoreRequest
import com.streamvault.domain.sports.provider.CUSTOM_SPORTS_PROVIDER_TYPE_ID
import com.streamvault.domain.sports.provider.SourcedValue
import com.streamvault.domain.sports.provider.SportsCapability
import com.streamvault.domain.sports.provider.SportsProviderConfig
import com.streamvault.domain.sports.provider.SportsProviderRegistry
import com.streamvault.domain.sports.provider.SportsProviderResult
import com.streamvault.domain.sports.provider.SportsScoreSourceMode
import com.streamvault.domain.sports.provider.SportsScoreSourcePreference
import com.streamvault.domain.sports.provider.SportsSourceRef
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

internal data class LoadedSportsProviderScores(
    val instanceId: String? = null,
    val typeId: String? = null,
    val events: List<ProviderEvent> = emptyList(),
    val scores: List<SourcedValue<ProviderScore>> = emptyList(),
)

/** Chooses score-capable accounts independently and bounds score-provider traffic. */
@Singleton
internal class SportsProviderScoreSourceRouter @Inject constructor(
    private val registry: SportsProviderRegistry,
    private val eventRouter: SportsProviderEventSourceRouter,
) {
    private val eventCache = object : LinkedHashMap<EventCacheKey, CachedEvents>(16, 0.75f, true) {}
    private val scoreCache = object : LinkedHashMap<ScoreCacheKey, CachedScores>(16, 0.75f, true) {}
    private val failureCooldowns = object : LinkedHashMap<SportsProviderConfig, Instant>(16, 0.75f, true) {}

    fun eligibleProviders(
        configs: Collection<SportsProviderConfig>,
        preference: SportsScoreSourcePreference,
        eventProviderInstanceId: String?,
    ): List<SportsProviderConfig> {
        if (preference.mode == SportsScoreSourceMode.OFF) return emptyList()
        val eventInstances = registry.eligible(configs, SportsCapability.EVENTS)
            .mapTo(mutableSetOf(), SportsProviderConfig::instanceId)
        val eligible = registry.eligible(configs, SportsCapability.LIVE_SCORES)
            // Scores are keyed by provider event ID. A score source must expose its event
            // catalog so IDs can be safely linked when it differs from the event source.
            .filter { it.instanceId in eventInstances }
        return when (preference.mode) {
            SportsScoreSourceMode.AUTOMATIC -> eligible.sortedWith(
                compareBy<SportsProviderConfig> { if (it.instanceId == eventProviderInstanceId) 0 else 1 }
                    .thenBy(SportsProviderConfig::priority)
                    .thenBy(SportsProviderConfig::instanceId),
            )
            SportsScoreSourceMode.OFF -> emptyList()
            SportsScoreSourceMode.PROVIDER -> eligible.filter { it.instanceId == preference.providerInstanceId }
        }
    }

    suspend fun load(
        providers: List<SportsProviderConfig>,
        reusableEventLoad: LoadedSportsProviderEvents,
        from: Instant,
        until: Instant,
        now: Instant = Instant.now(),
    ): LoadedSportsProviderScores {
        var staleFallback: LoadedSportsProviderScores? = null
        for (config in providers) {
            val providerEvents = loadEvents(config, reusableEventLoad, from, until, now)
            if (providerEvents.isEmpty()) continue
            val eventIds = providerEvents.asSequence()
                .filter { it.isRelevantForScoreRefresh(now) }
                .sortedWith(compareBy<com.streamvault.domain.sports.provider.ProviderEvent> {
                    if (it.status?.state == com.streamvault.domain.sports.provider.ProviderGameState.LIVE) 0 else 1
                }.thenBy { it.eventStart?.let { start -> Duration.between(now, start).abs() } ?: Duration.ofDays(365) })
                .take(MAX_SCORE_EVENTS)
                .map(ProviderEvent::providerEventId)
                .toCollection(linkedSetOf())
            if (eventIds.isEmpty()) continue

            val cacheKey = ScoreCacheKey(config, eventIds.sorted())
            val cached = synchronized(scoreCache) { scoreCache[cacheKey] }
            if (cached != null && now.isBefore(cached.fetchedAt.plus(SCORE_CACHE_TTL))) {
                return loaded(config, providerEvents, cached.scores, stale = false, fetchedAt = cached.fetchedAt)
            }
            val cooldownUntil = synchronized(failureCooldowns) { failureCooldowns[config] }
            if (cooldownUntil != null && now.isBefore(cooldownUntil)) {
                val stale = cached?.takeIf { now.isBefore(it.fetchedAt.plus(STALE_SCORE_CACHE_TTL)) }
                if (stale != null && staleFallback == null) {
                    staleFallback = loaded(config, providerEvents, stale.scores, stale = true, fetchedAt = stale.fetchedAt)
                }
                continue
            }

            val provider = try {
                registry.create(config) as? LiveScoreProvider
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            if (provider == null) {
                putFailureCooldown(config, retryAt = null, now = now)
                continue
            }
            val result = try {
                provider.liveScores(ProviderScoreRequest(
                    eventIds = eventIds,
                    eventScopes = if (config.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID) {
                        providerEvents.associate { event ->
                            event.providerEventId to ProviderEventScope(event.sport, event.league?.id)
                        }
                    } else emptyMap(),
                ))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            when (result) {
                is SportsProviderResult.Success -> {
                    val scores = result.value.items.filter { it.providerEventId in eventIds }
                    synchronized(scoreCache) {
                        scoreCache[cacheKey] = CachedScores(scores, now)
                        trim(scoreCache, MAX_SCORE_CACHE_ENTRIES)
                    }
                    synchronized(failureCooldowns) { failureCooldowns.remove(config) }
                    return loaded(config, providerEvents, scores, stale = false, fetchedAt = now)
                }
                is SportsProviderResult.Failure -> {
                    putFailureCooldown(config, result.retryAt, now)
                    val stale = cached?.takeIf { now.isBefore(it.fetchedAt.plus(STALE_SCORE_CACHE_TTL)) }
                    if (stale != null && staleFallback == null) {
                        staleFallback = loaded(config, providerEvents, stale.scores, stale = true, fetchedAt = stale.fetchedAt)
                    }
                }
                null -> {
                    putFailureCooldown(config, retryAt = null, now = now)
                    val stale = cached?.takeIf { now.isBefore(it.fetchedAt.plus(STALE_SCORE_CACHE_TTL)) }
                    if (stale != null && staleFallback == null) {
                        staleFallback = loaded(config, providerEvents, stale.scores, stale = true, fetchedAt = stale.fetchedAt)
                    }
                }
            }
        }
        return staleFallback ?: LoadedSportsProviderScores()
    }

    private suspend fun loadEvents(
        config: SportsProviderConfig,
        reusableEventLoad: LoadedSportsProviderEvents,
        from: Instant,
        until: Instant,
        now: Instant,
    ): List<ProviderEvent> {
        if (reusableEventLoad.instanceId == config.instanceId && reusableEventLoad.typeId == config.typeId) {
            putEvents(config, reusableEventLoad.events, from, until, now)
            return reusableEventLoad.events
        }

        val key = EventCacheKey(config)
        val cached = synchronized(eventCache) {
            eventCache[key]?.takeIf { candidate ->
                now.isBefore(candidate.fetchedAt.plus(EVENT_CACHE_TTL)) &&
                    !from.isBefore(candidate.from) && !until.isAfter(candidate.until.plus(EVENT_WINDOW_DRIFT))
            }
        }
        cached?.let { return it.events }
        val cooldownUntil = synchronized(failureCooldowns) { failureCooldowns[config] }
        if (cooldownUntil != null && now.isBefore(cooldownUntil)) return emptyList()
        val loaded = eventRouter.load(listOf(config), from, until)
        if (loaded.instanceId != config.instanceId || loaded.typeId != config.typeId) {
            putFailureCooldown(config, retryAt = null, now = now)
            return emptyList()
        }
        putEvents(config, loaded.events, from, until, now)
        return loaded.events
    }

    private fun putFailureCooldown(config: SportsProviderConfig, retryAt: Instant?, now: Instant) {
        synchronized(failureCooldowns) {
            failureCooldowns[config] = retryAt?.takeIf { it.isAfter(now) }
                ?: now.plus(FAILURE_COOLDOWN)
            trim(failureCooldowns, MAX_SCORE_CACHE_ENTRIES)
        }
    }

    private fun putEvents(
        config: SportsProviderConfig,
        events: List<ProviderEvent>,
        from: Instant,
        until: Instant,
        now: Instant,
    ) {
        val key = EventCacheKey(config)
        synchronized(eventCache) {
            eventCache[key] = CachedEvents(events, from, until, now)
            trim(eventCache, MAX_EVENT_CACHE_ENTRIES)
        }
    }

    private fun loaded(
        config: SportsProviderConfig,
        events: List<ProviderEvent>,
        scores: List<ProviderScore>,
        stale: Boolean,
        fetchedAt: Instant,
    ) = LoadedSportsProviderScores(
        instanceId = config.instanceId,
        typeId = config.typeId,
        events = events,
        scores = scores.map { score ->
            val event = events.firstOrNull { it.providerEventId == score.providerEventId }
            SourcedValue(
                value = score,
                source = SportsSourceRef.Provider(
                    instanceId = config.instanceId,
                    typeId = config.typeId,
                    externalEventId = score.providerEventId,
                    sport = event?.sport.takeIf { config.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID },
                    leagueId = event?.league?.id.takeIf { config.typeId == CUSTOM_SPORTS_PROVIDER_TYPE_ID },
                ),
                updatedAt = score.updatedAt ?: score.status?.updatedAt ?: fetchedAt,
                isStale = stale,
            )
        },
    )

    private fun ProviderEvent.isRelevantForScoreRefresh(now: Instant): Boolean {
        if (status?.state in TERMINAL_STATES) return false
        if (status?.state == com.streamvault.domain.sports.provider.ProviderGameState.LIVE) return true
        val start = eventStart ?: return false
        return !start.isBefore(now.minus(SCORE_LOOKBACK)) && !start.isAfter(now.plus(SCORE_LOOKAHEAD))
    }

    private data class EventCacheKey(val config: SportsProviderConfig)

    private data class CachedEvents(
        val events: List<ProviderEvent>,
        val from: Instant,
        val until: Instant,
        val fetchedAt: Instant,
    )

    private data class ScoreCacheKey(val config: SportsProviderConfig, val eventIds: List<String>)
    private data class CachedScores(val scores: List<ProviderScore>, val fetchedAt: Instant)

    private fun <K, V> trim(cache: LinkedHashMap<K, V>, maxSize: Int) {
        while (cache.size > maxSize) cache.entries.iterator().run { next(); remove() }
    }

    private companion object {
        val SCORE_CACHE_TTL: Duration = Duration.ofMinutes(2)
        val STALE_SCORE_CACHE_TTL: Duration = Duration.ofMinutes(10)
        val EVENT_CACHE_TTL: Duration = Duration.ofMinutes(5)
        val FAILURE_COOLDOWN: Duration = Duration.ofMinutes(2)
        val EVENT_WINDOW_DRIFT: Duration = Duration.ofMinutes(10)
        val SCORE_LOOKBACK: Duration = Duration.ofHours(12)
        val SCORE_LOOKAHEAD: Duration = Duration.ofHours(12)
        val TERMINAL_STATES = setOf(
            com.streamvault.domain.sports.provider.ProviderGameState.CANCELLED,
            com.streamvault.domain.sports.provider.ProviderGameState.POSTPONED,
        )
        const val MAX_SCORE_CACHE_ENTRIES = 64
        const val MAX_SCORE_EVENTS = 50
        const val MAX_EVENT_CACHE_ENTRIES = 32
    }
}
