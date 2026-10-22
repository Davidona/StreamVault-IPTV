package com.streamvault.data.remote.stalker

/** Provider-owned adaptive window shared by movie and series page requests. */
internal class StalkerAdaptiveIndexPolicy(concurrency: Int = INITIAL_CONCURRENCY) {
    var concurrency = concurrency.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY)
        private set
    private var healthyResponses = 0

    fun success(playbackActive: Boolean): Boolean {
        if (playbackActive) {
            resetHealth()
            return false
        }
        if (++healthyResponses < HEALTHY_RESPONSES) return false
        resetHealth()
        val previous = concurrency
        concurrency = (concurrency + 1).coerceAtMost(MAX_CONCURRENCY)
        return concurrency != previous
    }

    /** In-flight requests finish; new admissions observe the smaller window immediately. */
    fun stress(): Boolean {
        resetHealth()
        val previous = concurrency
        concurrency = (concurrency / 2).coerceAtLeast(MIN_CONCURRENCY)
        return concurrency != previous
    }

    fun resetHealth() {
        healthyResponses = 0
    }

    companion object {
        const val INITIAL_CONCURRENCY = 3
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 8
        const val PLAYBACK_INTERVAL_MILLIS = 20_000L
        const val HEALTHY_RESPONSES = 20
    }
}
