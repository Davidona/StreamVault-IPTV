package com.streamvault.feature.live.presentation.components

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn

/** Shared clock for live-program progress indicators. */
object LiveChannelProgressTicker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val nowMs: StateFlow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(30_000L)
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 30_000L),
        initialValue = System.currentTimeMillis(),
    )
}

fun liveChannelProgressFraction(
    nowMs: Long,
    startTimeMs: Long,
    endTimeMs: Long,
): Float {
    val durationMs = endTimeMs - startTimeMs
    if (durationMs <= 0L) return 0f

    return ((nowMs - startTimeMs).toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
}
