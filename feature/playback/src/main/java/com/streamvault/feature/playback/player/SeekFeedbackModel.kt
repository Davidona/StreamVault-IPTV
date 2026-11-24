package com.streamvault.feature.playback.player

import kotlin.math.abs

internal const val SEEK_STEP_MS = 10_000L

/** Presses closer together than this add up into one displayed amount. */
internal const val SEEK_STACK_WINDOW_MS = 1_200L

enum class SeekDirection { BACKWARD, FORWARD }

/** One applied skip. [appliedDeltaMs] is already clamped to the file, so it can be smaller than the step. */
data class SeekFeedbackEvent(
    val direction: SeekDirection,
    val appliedDeltaMs: Long,
    val fromMs: Long,
    val toMs: Long,
    val durationMs: Long
)

/**
 * Works out where each skip lands. The engine position is a polled value, so presses inside the
 * stacking window continue from the previous target instead of reading a stale position.
 */
internal class SeekTargetTracker(
    private val stepMs: Long = SEEK_STEP_MS,
    private val stackWindowMs: Long = SEEK_STACK_WINDOW_MS
) {
    private var lastTargetMs = 0L
    private var lastPressAtMs: Long? = null

    /** Returns null when the skip changes nothing, for example Back at 0:00. */
    fun next(
        direction: SeekDirection,
        enginePositionMs: Long,
        durationMs: Long,
        nowMs: Long
    ): SeekFeedbackEvent? {
        val lastAt = lastPressAtMs
        val fromMs = if (lastAt != null && nowMs - lastAt <= stackWindowMs) lastTargetMs else enginePositionMs
        val requestedMs = if (direction == SeekDirection.FORWARD) fromMs + stepMs else fromMs - stepMs
        val upperBoundMs = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        val toMs = requestedMs.coerceIn(0L, upperBoundMs)
        val appliedMs = abs(toMs - fromMs)
        if (appliedMs == 0L) return null
        lastTargetMs = toMs
        lastPressAtMs = nowMs
        return SeekFeedbackEvent(direction, appliedMs, fromMs, toMs, durationMs)
    }
}

/** What the badge and the strip show. [pulse] changes on every press so the ripple can replay. */
internal data class SeekFeedbackState(
    val direction: SeekDirection,
    val accumulatedMs: Long,
    val stackStartMs: Long,
    val toMs: Long,
    val durationMs: Long,
    val lastEventAtMs: Long,
    val pulse: Int
)

internal fun reduceSeekFeedback(
    previous: SeekFeedbackState?,
    event: SeekFeedbackEvent,
    nowMs: Long,
    stackWindowMs: Long = SEEK_STACK_WINDOW_MS
): SeekFeedbackState {
    val continuesStack = previous != null &&
        previous.direction == event.direction &&
        nowMs - previous.lastEventAtMs <= stackWindowMs
    return SeekFeedbackState(
        direction = event.direction,
        accumulatedMs = if (continuesStack) previous!!.accumulatedMs + event.appliedDeltaMs else event.appliedDeltaMs,
        stackStartMs = if (continuesStack) previous!!.stackStartMs else event.fromMs,
        toMs = event.toMs,
        durationMs = event.durationMs,
        lastEventAtMs = nowMs,
        pulse = (previous?.pulse ?: 0) + 1
    )
}
