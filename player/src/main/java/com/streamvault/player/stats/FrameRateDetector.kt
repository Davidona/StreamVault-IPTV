package com.streamvault.player.stats

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Detects the nominal frame rate of a stream from the presentation timestamps of rendered frames
 * and then locks. At least [framesToLock] usable intervals and four seconds of timing are needed:
 * millisecond-rounded timestamps otherwise cannot reliably distinguish 59.94 from 60 fps.
 * A supported cluster of short intervals establishes the frame period; longer intervals contribute
 * their nearest whole number of frames, preserving cadence when the renderer drops frames.
 * Isolated short gaps cannot establish a faster rate. Insufficient evidence stays unknown.
 *
 * Observation uses a bounded primitive ring buffer. Estimation is occasional and stops after a lock;
 * the steady-state frame callback only checks the published result and source generation.
 *
 * Threading: [onFrame] is called on ExoPlayer's playback thread, [lockedFrameRate] is read from the
 * Main thread, and [reset] may be called from Main. The working state is only touched on the
 * playback thread; the published result is `@Volatile` and tagged with its source generation.
 */
class FrameRateDetector(
    private val framesToLock: Int = DEFAULT_FRAMES_TO_LOCK
) {
    @Volatile
    private var result: Detection? = null

    private val generation = AtomicInteger()
    private data class Detection(val generation: Int, val rate: Float)

    private var activeGeneration = -1
    private var lastPresentationTimeUs = Long.MIN_VALUE
    private val intervals = LongArray(MAX_SAMPLES)
    private var sampleCount = 0
    private var nextSample = 0
    private var sampleSpanUs = 0L
    private var samplesSinceEstimate = 0

    /** Detected frame rate, or 0 until enough frames have been seen. */
    val lockedFrameRate: Float get() {
        val detected = result
        return if (detected?.generation == generation.get()) detected.rate else 0f
    }

    /** Forget everything; the next stream is detected from scratch. Safe to call from any thread. */
    fun reset() {
        // Invalidate publication too: an old rendering callback may still finish after reset.
        generation.incrementAndGet()
    }

    /** Playback-thread only. */
    fun onFrame(presentationTimeUs: Long) {
        if (lockedFrameRate > 0f) return
        val currentGeneration = generation.get()
        if (activeGeneration != currentGeneration) {
            activeGeneration = currentGeneration
            lastPresentationTimeUs = Long.MIN_VALUE
            clearSamples()
        }
        val last = lastPresentationTimeUs
        lastPresentationTimeUs = presentationTimeUs
        if (last == Long.MIN_VALUE) return

        val delta = presentationTimeUs - last
        if (delta == 0L) return
        if (delta < 0L || delta > MAX_DELTA_US) {
            clearSamples()
            return
        }
        if (delta < MIN_DELTA_US) return
        if (sampleCount == intervals.size) sampleSpanUs -= intervals[nextSample] else sampleCount++
        intervals[nextSample] = delta
        sampleSpanUs += delta
        nextSample = (nextSample + 1) % intervals.size
        samplesSinceEstimate++
        if (sampleCount >= framesToLock && sampleSpanUs >= MIN_SPAN_US &&
            samplesSinceEstimate >= ESTIMATE_INTERVAL
        ) {
            samplesSinceEstimate = 0
            estimate()?.let { result = Detection(activeGeneration, it) }
        }
    }

    private fun clearSamples() {
        sampleCount = 0
        nextSample = 0
        sampleSpanUs = 0
        samplesSinceEstimate = 0
    }

    private fun estimate(): Float? {
        val sorted = intervals.copyOf(sampleCount).also { it.sort() }
        var periodUs = 0.0
        for (start in sorted.indices) {
            var end = start + 1
            while (end < sorted.size && sorted[end] - sorted[start] <= CLUSTER_SPREAD_US) end++
            if (end - start >= 2) {
                // A rare normal interval can reveal a faster source under heavy frame dropping.
                // Do not mistake it for an outlier and lock to half-rate before more timing arrives.
                if (start > 0 && sampleSpanUs < OUTLIER_OBSERVATION_US) return null
                var sum = 0L
                for (index in start until end) sum += sorted[index]
                periodUs = sum.toDouble() / (end - start)
                break
            }
        }
        if (periodUs == 0.0) return null
        var spanUs = 0L
        var frameIntervals = 0L
        var supported = 0
        for (gap in sorted) {
            val frames = (gap / periodUs).roundToInt()
            if (frames >= 1 && abs(gap - frames * periodUs) <= INTERVAL_ERROR_US) {
                spanUs += gap
                frameIntervals += frames
                supported++
            }
        }
        if (supported * 100 < sampleCount * 95 || spanUs < MIN_SPAN_US) return null
        val rate = (1_000_000.0 * frameIntervals / spanUs).toFloat()
        return rate.takeIf { it in 5f..240f }?.let(::snapToStandardRate)
    }

    companion object {
        const val DEFAULT_FRAMES_TO_LOCK = 60
        private const val MAX_SAMPLES = 1024
        private const val MIN_SPAN_US = 4_000_000L
        private const val OUTLIER_OBSERVATION_US = 8_000_000L
        private const val ESTIMATE_INTERVAL = 30
        // Allow whole-millisecond rounding even at 240 fps; validate the final rate separately.
        private const val MIN_DELTA_US = 3_000L
        private const val MAX_DELTA_US = 1_000_000L / 5
        private const val CLUSTER_SPREAD_US = 2_000L
        private const val INTERVAL_ERROR_US = 1_100
        private val STANDARD_RATES = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 47.952f, 48f, 50f, 59.94f, 60f, 100f, 119.88f, 120f, 240f)

        /** Snap to a standard rate when within 2 %, otherwise keep two decimals. */
        fun snapToStandardRate(rate: Float): Float {
            var best = 0f
            var bestDistance = Float.MAX_VALUE
            for (standard in STANDARD_RATES) {
                val distance = kotlin.math.abs(rate - standard) / standard
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = standard
                }
            }
            return if (bestDistance <= 0.02f) best else Math.round(rate * 100f) / 100f
        }
    }
}
