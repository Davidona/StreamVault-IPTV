package com.streamvault.player.playback

/** A monotonic, playable-progress budget for an initial live tune, separate from network retries. */
class LiveStartupWatchdog(
    private val noProgressTimeoutMs: Long = 45_000L,
    private val minBufferProgressMs: Long = 250L
) {
    enum class Action { NONE, REFRESH_SOURCE, GIVE_UP }

    private var lastProgressAtMs: Long? = null
    private var reserveHighWaterMs = 0L
    private var refreshUsed = false
    private var reported = false
    private var completed = false

    init {
        require(noProgressTimeoutMs > 0 && minBufferProgressMs > 0)
    }

    fun reset(preserveEpisode: Boolean = false) {
        if (!preserveEpisode || completed) refreshUsed = false
        lastProgressAtMs = null
        reserveHighWaterMs = 0L
        reported = false
        completed = false
    }

    /** Pause/detach must clear elapsed time even if the next polling tick never runs. */
    fun pauseClock() {
        lastProgressAtMs = null
        reserveHighWaterMs = 0L
    }

    fun evaluate(
        nowMs: Long,
        eligible: Boolean,
        firstVideoFrameRendered: Boolean,
        audioOnlyPlaying: Boolean,
        bufferedDurationMs: Long,
        sourceRetryAlreadyUsed: Boolean = false
    ): Action {
        if (firstVideoFrameRendered || audioOnlyPlaying) completed = true
        if (completed || reported) return Action.NONE
        if (!eligible) {
            pauseClock()
            return Action.NONE
        }
        val reserve = bufferedDurationMs.coerceAtLeast(0)
        val lastProgress = lastProgressAtMs
        if (lastProgress == null || reserve - reserveHighWaterMs >= minBufferProgressMs) {
            lastProgressAtMs = nowMs
            reserveHighWaterMs = maxOf(reserveHighWaterMs, reserve)
            return Action.NONE
        }
        if (nowMs - lastProgress < noProgressTimeoutMs) return Action.NONE
        reported = true
        return if (!refreshUsed && !sourceRetryAlreadyUsed) {
            refreshUsed = true
            Action.REFRESH_SOURCE
        } else Action.GIVE_UP
    }
}
