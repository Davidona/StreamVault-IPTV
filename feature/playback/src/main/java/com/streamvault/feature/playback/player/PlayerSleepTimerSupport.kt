package com.streamvault.feature.playback.player

internal fun computeSleepTimerUiState(
    current: SleepTimerUiState,
    nowElapsedMs: Long,
    stopPlaybackTimerEndsAtMs: Long,
    idleStandbyTimerEndsAtMs: Long,
    stopMinutes: Int = current.stopTimerMinutes,
    idleMinutes: Int = current.idleTimerMinutes
): SleepTimerUiState {
    return current.copy(
        stopTimerMinutes = stopMinutes,
        stopRemainingMs = if (stopPlaybackTimerEndsAtMs > 0L) {
            (stopPlaybackTimerEndsAtMs - nowElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        },
        idleTimerMinutes = idleMinutes,
        idleRemainingMs = if (idleStandbyTimerEndsAtMs > 0L) {
            (idleStandbyTimerEndsAtMs - nowElapsedMs).coerceAtLeast(0L)
        } else {
            0L
        }
    )
}

internal fun sanitizePlaybackTimerMinutes(minutes: Int, allowedPresets: Set<Int>): Int =
    minutes.takeIf { it in allowedPresets } ?: 0

internal fun formatTimerMinutesNotice(minutes: Int): String =
    if (minutes == 1) "1 minute" else "$minutes minutes"