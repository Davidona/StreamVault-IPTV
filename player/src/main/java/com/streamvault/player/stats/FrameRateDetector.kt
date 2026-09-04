package com.streamvault.player.stats

/**
 * Detects the nominal frame rate of a stream from the presentation timestamps of rendered frames
 * and then **locks**: after [framesToLock] usable frames it computes the rate once, snaps it to the
 * nearest standard broadcast rate (23.976 / 24 / 25 / 29.97 / 30 / 50 / 59.94 / 60) and stops doing
 * any work. This is deliberately cheaper than counting frames per second forever, and unlike a
 * rendered-frames-per-second measurement it reports the *stream* rate even when a slow device is
 * dropping frames, because it uses the smallest timestamp gap rather than the render throughput.
 *
 * Cost while detecting: one subtraction and one comparison per rendered frame. Cost after locking:
 * a single volatile read per frame.
 *
 * Threading: [onFrame] is called on ExoPlayer's playback thread, [lockedFrameRate] is read from the
 * Main thread, and [reset] may be called from Main. The working state is only touched on the
 * playback thread; the published result is `@Volatile`.
 */
class FrameRateDetector(
    private val framesToLock: Int = DEFAULT_FRAMES_TO_LOCK
) {
    @Volatile
    private var locked = 0f

    @Volatile
    private var generation = 0

    private var activeGeneration = -1
    private var lastPresentationTimeUs = Long.MIN_VALUE
    private var minDeltaUs = Long.MAX_VALUE
    private var usableFrames = 0

    /** Detected frame rate, or 0 until enough frames have been seen. */
    val lockedFrameRate: Float get() = locked

    /** Forget everything; the next stream is detected from scratch. Safe to call from any thread. */
    fun reset() {
        locked = 0f
        generation++
    }

    /** Playback-thread only. */
    fun onFrame(presentationTimeUs: Long) {
        if (locked > 0f) return
        if (activeGeneration != generation) {
            activeGeneration = generation
            lastPresentationTimeUs = Long.MIN_VALUE
            minDeltaUs = Long.MAX_VALUE
            usableFrames = 0
        }
        val last = lastPresentationTimeUs
        lastPresentationTimeUs = presentationTimeUs
        if (last == Long.MIN_VALUE) return

        val delta = presentationTimeUs - last
        // Ignore seeks/discontinuities and duplicate timestamps: only 5..240 fps gaps count.
        if (delta < MIN_DELTA_US || delta > MAX_DELTA_US) return
        if (delta < minDeltaUs) minDeltaUs = delta
        usableFrames++
        if (usableFrames >= framesToLock) {
            locked = snapToStandardRate(1_000_000f / minDeltaUs)
        }
    }

    companion object {
        const val DEFAULT_FRAMES_TO_LOCK = 60
        private const val MIN_DELTA_US = 1_000_000L / 240
        private const val MAX_DELTA_US = 1_000_000L / 5
        private val STANDARD_RATES = floatArrayOf(23.976f, 24f, 25f, 29.97f, 30f, 48f, 50f, 59.94f, 60f, 100f, 120f)

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
