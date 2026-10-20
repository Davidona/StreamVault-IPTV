package com.streamvault.data.remote.stalker

/** Pure, provider-owned pacing state. Admission, not parallelism, controls the wire rate. */
internal class StalkerAdaptiveIndexPolicy(
    intervalMillis: Long = INITIAL_INTERVAL_MILLIS,
    minimumIntervalMillis: Long = MIN_INTERVAL_MILLIS
) {
    var minimumIntervalMillis = minimumIntervalMillis.coerceIn(MIN_INTERVAL_MILLIS, MAX_INTERVAL_MILLIS)
        private set
    var intervalMillis = intervalMillis.coerceIn(this.minimumIntervalMillis, MAX_INTERVAL_MILLIS)
        private set
    private var healthyResponses = 0
    private var healthySince: Long? = null

    fun success(now: Long, durationMillis: Long, playbackActive: Boolean): Boolean {
        if (playbackActive) {
            resetHealth()
            return false
        }
        if (durationMillis >= SLOW_RESPONSE_MILLIS) return stress()
        if (healthySince == null) healthySince = now
        healthyResponses++
        if (healthyResponses < HEALTHY_RESPONSES || now - requireNotNull(healthySince) < HEALTHY_WINDOW_MILLIS) return false
        val previous = intervalMillis
        intervalMillis = (intervalMillis * 3 / 4).coerceAtLeast(minimumIntervalMillis)
        resetHealth()
        return intervalMillis != previous
    }

    /** Remember the failed rate as a ceiling, including across process restarts. */
    fun stress(): Boolean {
        val previous = intervalMillis
        minimumIntervalMillis = (intervalMillis * 2).coerceAtMost(MAX_INTERVAL_MILLIS)
        intervalMillis = minimumIntervalMillis
        resetHealth()
        return intervalMillis != previous
    }

    fun resetHealth() {
        healthyResponses = 0
        healthySince = null
    }

    companion object {
        const val INITIAL_INTERVAL_MILLIS = 5_000L
        const val MIN_INTERVAL_MILLIS = 500L
        const val MAX_INTERVAL_MILLIS = 30_000L
        const val PLAYBACK_INTERVAL_MILLIS = 20_000L
        const val HEALTHY_RESPONSES = 12
        const val HEALTHY_WINDOW_MILLIS = 15_000L
        const val SLOW_RESPONSE_MILLIS = 2_000L
    }
}
