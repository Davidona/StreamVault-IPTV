package com.streamvault.player.playback

internal data class DecodePressureRendition(val height: Int, val bitrate: Int)
internal data class DecodePressureSample(
    val timeMs: Long, val groupKey: Any, val eligible: Boolean, val readyAndPlaying: Boolean,
    val bufferedMs: Long, val rendered: Int, val dropped: Int, val height: Int, val bitrate: Int,
    val renditions: List<DecodePressureRendition>
)
internal data class DecodePressureChange(
    val maxHeight: Int?,
    val maxBitrate: Int? = null,
    val restorationStableMs: Long? = null
)

internal class AdaptiveDecodePressurePolicy {
    // Trial thresholds from docs/PLAYBACK-STAGE3-EVIDENCE-2026-10-01.md:
    // software 4K dropped ~80% with >=4s buffered; controls had sparse bursts.
    // Require two 5s windows with >=20% / 30 dropped frames, after 10s settling.
    // These are conservative engineering gates, not calibrated TV-device optima.
    private var group: Any? = null
    private var cap: Int? = null
    private var previous: DecodePressureSample? = null
    private var window: DecodePressureSample? = null
    private var warmUntilMs = 0L
    private var changeAfterMs = 0L
    private var pressureWindows = 0
    private var stableMs = 0L
    private var pressureBufferHealthy = true
    private var stableBufferHealthy = true
    private var restorationStableMs = BASE_RESTORATION_STABLE_MS
    private var lastRestorationMs: Long? = null

    fun reset() {
        group = null
        cap = null
        changeAfterMs = 0
        clearRestorationHistory()
        resetObservation()
    }

    fun discontinuity() {
        clearRestorationHistory()
        resetObservation()
    }

    private fun clearRestorationHistory() {
        restorationStableMs = BASE_RESTORATION_STABLE_MS
        lastRestorationMs = null
    }

    private fun resetObservation() {
        previous = null
        window = null
        warmUntilMs = 0
        pressureWindows = 0
        stableMs = 0
        pressureBufferHealthy = true
        stableBufferHealthy = true
    }

    fun observe(sample: DecodePressureSample): DecodePressureChange? {
        if (!sample.eligible || (group != null && group != sample.groupKey)) {
            val hadCap = cap != null
            reset()
            if (hadCap) return DecodePressureChange(null)
            if (!sample.eligible) return null
        }
        group = sample.groupKey
        if (!sample.readyAndPlaying) {
            // Pause/buffering time cannot add stable or pressure evidence, but it does not
            // end this source's restoration history. Explicit position discontinuities do.
            resetObservation()
            return null
        }
        lastRestorationMs?.let { restoredAt ->
            if (sample.timeMs >= restoredAt + RAPID_RECURRENCE_WINDOW_MS) {
                // A full minute of healthy restored output means the recurrence has ended.
                restorationStableMs = BASE_RESTORATION_STABLE_MS
                lastRestorationMs = null
            }
        }
        val old = previous
        if (old == null || sample.height != old.height || sample.timeMs <= old.timeMs ||
            sample.timeMs - old.timeMs > 2_500 || sample.rendered < old.rendered || sample.dropped < old.dropped
        ) {
            // Decoder/format/counter boundaries restart measurements but retain the policy's
            // restoration history. Explicit user seeks use discontinuity() above.
            resetObservation()
            previous = sample
            warmUntilMs = sample.timeMs + 10_000
            return null
        }
        previous = sample
        if (sample.timeMs < warmUntilMs) return null
        pressureBufferHealthy = pressureBufferHealthy && sample.bufferedMs >= 4_000
        stableBufferHealthy = stableBufferHealthy && sample.bufferedMs >= 2_000
        val start = window ?: run { window = sample; return null }
        val elapsed = sample.timeMs - start.timeMs
        if (elapsed < 5_000) return null
        val dropped = (sample.dropped - start.dropped).toLong()
        val frames = dropped + (sample.rendered - start.rendered).toLong()
        val ratio = if (frames > 0) dropped.toDouble() / frames else 0.0
        val pressure = pressureBufferHealthy && frames >= 60 && dropped >= 30 && ratio >= 0.20
        val stable = stableBufferHealthy && frames >= 60 && ratio <= 0.02
        window = sample
        pressureBufferHealthy = sample.bufferedMs >= 4_000
        stableBufferHealthy = sample.bufferedMs >= 2_000
        pressureWindows = if (pressure) pressureWindows + 1 else 0
        stableMs = if (stable) stableMs + elapsed else 0
        if (sample.timeMs < changeAfterMs) return null

        if (pressureWindows >= 2) {
            val next = sample.renditions.filter {
                it.height > 0 && it.height < minOf(sample.height, cap ?: Int.MAX_VALUE) &&
                    it.bitrate > 0 && sample.bitrate > 0 && it.bitrate <= sample.bitrate
            }.maxWithOrNull(compareBy<DecodePressureRendition> { it.height }.thenBy { it.bitrate }) ?: return null
            return change(sample.timeMs, next.height, next.bitrate)
        }
        val limit = cap ?: return null
        if (stableMs >= restorationStableMs) {
            val heights = sample.renditions.filter { it.height > 0 && it.bitrate > 0 }.map { it.height }.distinct().sorted()
            val next = heights.firstOrNull { it > limit } ?: return null
            return change(sample.timeMs, next.takeUnless { it == heights.last() },
                sample.renditions.filter { it.height == next }.maxOf { it.bitrate })
        }
        return null
    }

    private fun change(nowMs: Long, height: Int?, bitrate: Int?): DecodePressureChange {
        val oldCap = cap
        val restoring = oldCap != null && (height == null || height > oldCap)
        if (restoring) {
            lastRestorationMs = nowMs
        } else if (oldCap == null && height != null) {
            val sinceRestorationMs = lastRestorationMs?.let { nowMs - it }
            if (sinceRestorationMs != null && sinceRestorationMs in 0..RAPID_RECURRENCE_WINDOW_MS) {
                // V8 measured renewed pressure ~31s after restore. One 30s increment gives
                // that source an extra stable window while keeping restoration bounded.
                restorationStableMs = BACKED_OFF_RESTORATION_STABLE_MS
            }
            lastRestorationMs = null
        }
        cap = height
        changeAfterMs = nowMs + 30_000
        resetObservation()
        return DecodePressureChange(
            maxHeight = height,
            maxBitrate = bitrate.takeIf { height != null },
            restorationStableMs = restorationStableMs.takeIf { restoring }
        )
    }

    private companion object {
        const val BASE_RESTORATION_STABLE_MS = 60_000L
        const val BACKED_OFF_RESTORATION_STABLE_MS = 90_000L
        const val RAPID_RECURRENCE_WINDOW_MS = 60_000L
    }
}
