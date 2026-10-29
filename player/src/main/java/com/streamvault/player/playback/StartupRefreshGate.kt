package com.streamvault.player.playback

/** Cancel first, then wait for owned HTTP sources to drain before opening a replacement. */
class StartupRefreshGate(
    private val drainTimeoutMs: Long = 25_000L,
    private val quietPeriodMs: Long = 500L
) {
    init { require(drainTimeoutMs > 0 && quietPeriodMs >= 0) }
    enum class Action { WAIT, READY, GIVE_UP }
    var isPending = false
        private set
    private var startedAtMs: Long? = null
    private var quietAtMs: Long? = null

    fun begin(nowMs: Long) {
        isPending = true
        startedAtMs = nowMs
        quietAtMs = null
    }

    fun pauseClock() {
        startedAtMs = null
        quietAtMs = null
    }

    fun clear() {
        isPending = false
        pauseClock()
    }

    fun evaluate(nowMs: Long, canResume: Boolean, hasActiveTransfers: Boolean): Action {
        if (!isPending) return Action.WAIT
        if (!canResume) { pauseClock(); return Action.WAIT }
        val started = startedAtMs ?: nowMs.also { startedAtMs = it }
        if (hasActiveTransfers) {
            quietAtMs = null
            if (nowMs - started >= drainTimeoutMs) {
                clear()
                return Action.GIVE_UP
            }
            return Action.WAIT
        }
        val quiet = quietAtMs ?: nowMs.also { quietAtMs = it }
        if (nowMs - quiet < quietPeriodMs) return Action.WAIT
        clear()
        return Action.READY
    }
}
