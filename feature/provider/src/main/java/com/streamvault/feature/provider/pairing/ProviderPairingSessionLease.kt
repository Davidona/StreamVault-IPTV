package com.streamvault.feature.provider.pairing

internal class ProviderPairingSessionLease(
    private val token: String,
    private val expiresAtMs: Long,
) {
    private val lock = Any()
    private var active = true
    private var submissionInProgress = false

    fun isValid(candidate: String?, nowMs: Long): Boolean = synchronized(lock) {
        isValidLocked(candidate, nowMs)
    }

    fun tryBeginSubmission(candidate: String?, nowMs: Long): Boolean = synchronized(lock) {
        if (!isValidLocked(candidate, nowMs) || submissionInProgress) {
            false
        } else {
            submissionInProgress = true
            true
        }
    }

    fun endSubmission() = synchronized(lock) {
        submissionInProgress = false
    }

    fun invalidate() = synchronized(lock) {
        active = false
    }

    private fun isValidLocked(candidate: String?, nowMs: Long): Boolean =
        active && candidate == token && nowMs < expiresAtMs
}
