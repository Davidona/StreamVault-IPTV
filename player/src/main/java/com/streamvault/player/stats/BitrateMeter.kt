package com.streamvault.player.stats

/**
 * Running (exponentially smoothed) stream bitrate from a cumulative byte counter.
 *
 * Called once per stats tick with the cumulative network bytes; keeps two longs of state and does
 * a handful of integer operations. The result is rounded to [ROUNDING_BPS] so the value published
 * to the UI only changes when it moved by a visible amount, which avoids needless recompositions.
 */
class BitrateMeter(
    /** Weight of the newest sample, 0..1. 0.25 ≈ a 4-second running average at 1-second ticks. */
    private val alpha: Float = DEFAULT_ALPHA
) {
    private var lastBytes = Long.MIN_VALUE
    private var lastTimeMs = 0L
    private var smoothedBps = 0.0

    /** Current smoothed bitrate in bits per second, rounded; 0 until two samples have been seen. */
    var bitrateBps: Long = 0L
        private set

    fun reset() {
        lastBytes = Long.MIN_VALUE
        lastTimeMs = 0L
        smoothedBps = 0.0
        bitrateBps = 0L
    }

    fun addSample(timeMs: Long, cumulativeBytes: Long): Long {
        val previousBytes = lastBytes
        val previousTime = lastTimeMs
        lastBytes = cumulativeBytes
        lastTimeMs = timeMs
        if (previousBytes == Long.MIN_VALUE) return bitrateBps

        val deltaBytes = cumulativeBytes - previousBytes
        val deltaMs = timeMs - previousTime
        if (deltaBytes < 0L || deltaMs <= 0L) {
            // Counter reset or clock went backwards: start over from this sample.
            smoothedBps = 0.0
            bitrateBps = 0L
            return 0L
        }
        val instantBps = deltaBytes * 8_000.0 / deltaMs
        smoothedBps = if (smoothedBps == 0.0) instantBps else smoothedBps + alpha * (instantBps - smoothedBps)
        bitrateBps = (Math.round(smoothedBps / ROUNDING_BPS) * ROUNDING_BPS)
        return bitrateBps
    }

    companion object {
        const val DEFAULT_ALPHA = 0.25f
        const val ROUNDING_BPS = 100_000L
    }
}
