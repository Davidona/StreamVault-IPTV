package com.streamvault.data.remote.stalker

import com.streamvault.data.BuildConfig
import com.streamvault.data.sync.AutomaticSyncExecution
import com.streamvault.domain.sync.AutomaticSyncActivity
import java.util.concurrent.ConcurrentHashMap

internal object StalkerTrafficCoordinator {
    private const val NORMAL_BACKGROUND_PAGE_DELAY_MILLIS = 1_000L
    private const val PLAYBACK_BACKGROUND_PAGE_DELAY_MILLIS = 2_000L
    private val activePlaybackCountsByProvider = ConcurrentHashMap<Long, Int>()

    fun notePlaybackStarted(providerId: Long) {
        if (providerId <= 0L) return
        activePlaybackCountsByProvider.compute(providerId) { _, current ->
            (current ?: 0) + 1
        }
    }

    fun notePlaybackStopped(providerId: Long) {
        if (providerId <= 0L) return
        activePlaybackCountsByProvider.compute(providerId) { _, current ->
            when {
                current == null || current <= 1 -> null
                else -> current - 1
            }
        }
    }

    fun isPlaybackActive(providerId: Long): Boolean =
        providerId > 0L && (hasExplicitPlaybackMarker(providerId) || AutomaticSyncActivity.isProviderPlaybackActive(providerId))

    fun forgetProvider(providerId: Long) {
        if (providerId > 0L) activePlaybackCountsByProvider.remove(providerId)
    }

    /** Playback never blocks interactive requests; only maintenance slows between pages. */
    fun backgroundInterPageDelayMillis(
        providerId: Long,
        adaptiveIndexingEnabled: Boolean = BuildConfig.STALKER_ADAPTIVE_INDEXING
    ): Long =
        if (adaptiveIndexingEnabled) {
            // Every wire request, including page-base probes, is paced by network admission.
            0L
        } else
        if (shouldDeferIndexForPlayback(providerId)) {
            PLAYBACK_BACKGROUND_PAGE_DELAY_MILLIS
        } else {
            NORMAL_BACKGROUND_PAGE_DELAY_MILLIS
        }

    fun shouldDeferIndexForPlayback(providerId: Long): Boolean =
        hasExplicitPlaybackMarker(providerId) || (
            AutomaticSyncExecution.automatic.get() == true &&
                AutomaticSyncExecution.policy.get()?.deferWhileBusy != false &&
                AutomaticSyncActivity.isProviderPlaybackActive(providerId)
            )

    private fun hasExplicitPlaybackMarker(providerId: Long): Boolean =
        providerId > 0L && (activePlaybackCountsByProvider[providerId] ?: 0) > 0

    internal fun resetForTests() {
        activePlaybackCountsByProvider.clear()
    }
}
