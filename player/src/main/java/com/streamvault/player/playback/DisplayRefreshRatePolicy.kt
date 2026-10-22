package com.streamvault.player.playback

import kotlin.math.abs
import kotlin.math.roundToInt

/** Display pixels are output pixels, never the decoded video's dimensions. */
internal data class RefreshRateDisplayMode(val id: Int, val width: Int, val height: Int, val hz: Float)

internal object DisplayRefreshRatePolicy {
    fun validRate(rate: Float): Boolean = rate.isFinite() && rate in 5f..240f

    /** Keep fractional broadcast rates distinct from their integer counterparts. */
    fun hasEvenCadence(hz: Float, fps: Float): Boolean {
        if (!validRate(fps) || !hz.isFinite() || hz <= 0f) return false
        val multiple = (hz / fps).roundToInt()
        return multiple >= 1 && abs(hz - multiple * fps) <= hz * 0.0005f
    }

    fun select(
        fps: Float,
        current: RefreshRateDisplayMode,
        supported: List<RefreshRateDisplayMode>
    ): RefreshRateDisplayMode? {
        if (!validRate(fps) || current.width <= 0 || current.height <= 0) return null
        val matching = supported.filter {
            it.id > 0 && it.width == current.width && it.height == current.height && hasEvenCadence(it.hz, fps)
        }
        // Avoid unnecessary HDMI resyncs, including 24 fps already displayed evenly at 120 Hz.
        matching.firstOrNull { it.id == current.id }?.let { return it }
        // Prefer 50/59.94/60 Hz for 25/29.97/30 fps when switching is needed.
        val preferredHz = if (fps in 24.9f..30.1f) fps * 2 else fps
        return matching.minWithOrNull(compareBy<RefreshRateDisplayMode> { abs(it.hz - preferredHz) }.thenBy { it.id })
    }
}
