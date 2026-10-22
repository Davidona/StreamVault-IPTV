package com.streamvault.player.playback

/** Limits audio renderer restarts without resetting the budget on each reprepare. */
internal class AudioRendererRecoveryPolicy(
    private val cooldownMs: Long = 15_000L,
    private val stableIntervalMs: Long = 5 * 60_000L,
    private val maxAttemptsPerMedia: Int = 2
) {
    private var mediaId: String? = null
    private var attempts = 0
    private var lastAttemptAtMs = Long.MIN_VALUE

    fun reset() {
        mediaId = null
        attempts = 0
        lastAttemptAtMs = Long.MIN_VALUE
    }

    fun shouldRecover(currentMediaId: String, nowMs: Long): Boolean {
        if (mediaId != currentMediaId) {
            mediaId = currentMediaId
            attempts = 0
            lastAttemptAtMs = Long.MIN_VALUE
        }
        if (lastAttemptAtMs != Long.MIN_VALUE && nowMs - lastAttemptAtMs >= stableIntervalMs) {
            attempts = 0
        }
        if (attempts >= maxAttemptsPerMedia) return false
        if (lastAttemptAtMs != Long.MIN_VALUE && nowMs - lastAttemptAtMs < cooldownMs) return false
        attempts++
        lastAttemptAtMs = nowMs
        return true
    }
}
