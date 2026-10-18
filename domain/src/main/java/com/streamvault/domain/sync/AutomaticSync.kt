package com.streamvault.domain.sync

import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.SyncMetadata

/** Persisted policy with independent section selection. Times are local preferred windows. */
data class AutomaticSyncSettings(
    val enabled: Boolean = true,
    val catalogHours: Int = 24,
    val epgHours: Int = 6,
    val live: Boolean = true,
    val movies: Boolean = true,
    val series: Boolean = true,
    val epg: Boolean = true,
    val deferWhileBusy: Boolean = true,
    val unmeteredOnly: Boolean = false,
    val chargingOnly: Boolean = false,
    val deviceIdleOnly: Boolean = false,
    val windowStartHour: Int = 0,
    val windowEndHour: Int = 0
) {
    fun normalized() = copy(
        catalogHours = catalogHours.coerceIn(1, 168),
        epgHours = epgHours.coerceIn(1, 168),
        windowStartHour = windowStartHour.coerceIn(0, 23),
        windowEndHour = windowEndHour.coerceIn(0, 23)
    )

    fun allowsHour(hour: Int): Boolean = when {
        windowStartHour == windowEndHour -> true
        windowStartHour < windowEndHour -> hour in windowStartHour until windowEndHour
        else -> hour >= windowStartHour || hour < windowEndHour
    }
}

data class AutomaticSyncConfiguration(
    val defaults: AutomaticSyncSettings = AutomaticSyncSettings(),
    val providers: Map<Long, AutomaticSyncSettings> = emptyMap()
) {
    fun forProvider(id: Long): AutomaticSyncSettings =
        (providers[id] ?: defaults).normalized().let { it.copy(enabled = defaults.enabled && it.enabled) }
}

enum class RefreshSection { LIVE, MOVIES, SERIES, EPG }

data class ProviderRefreshPlan(val sections: List<RefreshSection>, val nextDueAt: Long?)

/** Used by both explicit refreshes and automatic checks; it never uses attempt timestamps. */
object ProviderRefreshPlanner {
    fun plan(
        type: ProviderType,
        metadata: SyncMetadata?,
        settings: AutomaticSyncSettings,
        now: Long,
        force: Boolean = false,
        epgAvailable: Boolean = true,
        unifiedVod: Boolean = false
    ): ProviderRefreshPlan {
        if (!force && !settings.enabled) return ProviderRefreshPlan(emptyList(), null)
        val policy = settings.normalized()
        val candidates = buildList {
            if (policy.live && type != ProviderType.JELLYFIN) add(RefreshSection.LIVE to (metadata?.lastLiveSuccess ?: 0L))
            if (policy.movies) add(RefreshSection.MOVIES to (metadata?.lastMovieSuccess ?: 0L))
            if (policy.series && type != ProviderType.M3U && !unifiedVod) add(RefreshSection.SERIES to (metadata?.lastSeriesSuccess ?: 0L))
            if (policy.epg && epgAvailable && type != ProviderType.JELLYFIN) add(RefreshSection.EPG to (metadata?.lastEpgSuccess ?: 0L))
        }
        val due = candidates.map { (section, success) ->
            val interval = (if (section == RefreshSection.EPG) policy.epgHours else policy.catalogHours) * 3_600_000L
            section to if (success <= 0L || success > now) now else success + interval
        }
        return ProviderRefreshPlan(
            sections = due.filter { force || it.second <= now }.map { it.first },
            nextDueAt = due.minOfOrNull { it.second }
        )
    }
}

enum class AutomaticSyncOutcome { UP_TO_DATE, WAITING_NETWORK, WAITING_CHARGING, WAITING_IDLE, WAITING_PLAYBACK, WAITING_WINDOW, DISABLED, RUNNING, QUEUED, REFRESHED, PARTIAL, FAILED }

data class AutomaticSyncStatus(
    val checkedAt: Long = 0,
    val outcome: AutomaticSyncOutcome = AutomaticSyncOutcome.UP_TO_DATE,
    val nextDueAt: Long? = null,
    val sections: List<RefreshSection> = emptyList()
)

/** In-process playback ownership, including paused/buffering players and split screen. */
object AutomaticSyncActivity {
    private val players = java.util.concurrent.ConcurrentHashMap.newKeySet<Any>()
    private val mutablePlaybackActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    val playbackActive: kotlinx.coroutines.flow.StateFlow<Boolean> get() = mutablePlaybackActive
    val hasPlayback: Boolean get() = players.isNotEmpty()
    fun started(owner: Any) { synchronized(players) { players.add(owner); mutablePlaybackActive.value = players.isNotEmpty() } }
    fun stopped(owner: Any) { synchronized(players) { players.remove(owner); mutablePlaybackActive.value = players.isNotEmpty() } }
}
