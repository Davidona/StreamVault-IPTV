@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.upstream.DefaultAllocator

/**
 * Keep Media3's loading/start/rebuffer decisions, with a byte target sized for the
 * selected tracks and this device. Size takes priority when samples stop draining.
 * A target is not a hard ceiling: an in-flight chunk can overshoot it.
 */
internal class PlaybackLoadControl(
    private val policy: PlaybackBufferPolicy,
    heapBytes: Long,
    multiview: Boolean
) : DefaultLoadControl(
    DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
    policy.minBufferMs, policy.minBufferMs, policy.maxBufferMs, policy.maxBufferMs,
    policy.playbackBufferMs, policy.playbackBufferMs, policy.rebufferMs, policy.rebufferMs,
    C.LENGTH_UNSET, false, false, 0, false
) {
    @Volatile private var multiviewMode = multiview
    // Accessed only on Media3's playback thread.
    private val selections = mutableMapOf<PlayerId, Array<out ExoTrackSelection?>>()
    private val appliedModes = mutableMapOf<PlayerId, Boolean>()
    private val references = mutableMapOf<PlayerId, Int>()
    private var foregroundTargetBytes = DEFAULT_MIN_BUFFER_SIZE
    private data class AllocationSnapshot(val playerId: PlayerId, val bytes: Int, val target: Int, val atMs: Long)
    @Volatile private var foregroundAllocation: AllocationSnapshot? = null

    /** Small immutable snapshot published from the playback thread for transition diagnostics. */
    fun describeAllocation(): String {
        val snapshot = foregroundAllocation ?: return "allocatedBytes=unknown targetBytes=unknown"
        return "allocatedBytes=${snapshot.bytes} targetBytes=${snapshot.target} " +
            "allocationSampleAgeMs=${(SystemClock.elapsedRealtime() - snapshot.atMs).coerceAtLeast(0L)}"
    }

    override fun onPrepared(playerId: PlayerId) {
        if (references[playerId] == null && playerId != PlayerId.PRELOAD) {
            foregroundTargetBytes = DEFAULT_MIN_BUFFER_SIZE
        }
        references[playerId] = (references[playerId] ?: 0) + 1
        super.onPrepared(playerId)
    }

    fun setMultiview(multiview: Boolean) {
        multiviewMode = multiview
    }

    override fun onTracksSelected(
        parameters: LoadControl.Parameters,
        trackGroups: TrackGroupArray,
        trackSelections: Array<out ExoTrackSelection?>
    ) {
        selections[parameters.playerId] = trackSelections.copyOf()
        appliedModes[parameters.playerId] = multiviewMode
        super.onTracksSelected(parameters, trackGroups, trackSelections)
    }

    override fun shouldContinueLoading(parameters: LoadControl.Parameters): Boolean {
        if (appliedModes[parameters.playerId] != multiviewMode) {
            selections[parameters.playerId]?.let {
                super.onTracksSelected(parameters, TrackGroupArray.EMPTY, it)
                appliedModes[parameters.playerId] = multiviewMode
            }
        }
        val loading = super.shouldContinueLoading(parameters)
        if (parameters.playerId != PlayerId.PRELOAD) {
            foregroundAllocation = AllocationSnapshot(
                parameters.playerId, getAllocator(parameters.playerId).totalBytesAllocated,
                foregroundTargetBytes, SystemClock.elapsedRealtime()
            )
        }
        return loading
    }

    override fun onStopped(playerId: PlayerId) {
        removeReference(playerId)
        super.onStopped(playerId)
    }

    override fun onReleased(playerId: PlayerId) {
        removeReference(playerId)
        super.onReleased(playerId)
    }

    private fun removeReference(playerId: PlayerId) {
        val remaining = (references[playerId] ?: return) - 1
        if (remaining > 0) {
            references[playerId] = remaining
        } else {
            references.remove(playerId)
            selections.remove(playerId)
            appliedModes.remove(playerId)
            if (foregroundAllocation?.playerId == playerId) foregroundAllocation = null
        }
    }
    // Reserve half the Java heap for the app, decoder bookkeeping and chunk overshoot.
    // Multiview has at most four foreground players; each gets one quarter of this pool.
    // This is a conservative allocation policy, not a guarantee against every OOM.
    private val deviceBudget = (heapBytes / 2).coerceAtMost(DEFAULT_MAX_BUFFER_SIZE.toLong())

    override fun calculateTargetBufferBytes(
        parameters: LoadControl.Parameters,
        trackSelectionArray: Array<out ExoTrackSelection?>
    ): Int {
        val preload = parameters.playerId == PlayerId.PRELOAD
        val sessionBudget = deviceBudget / if (multiviewMode) 4 else 1
        val preloadBudget = (sessionBudget / 4).coerceAtLeast(C.DEFAULT_BUFFER_SEGMENT_SIZE.toLong())
        val foregroundBudget = (sessionBudget - preloadBudget)
            .coerceAtLeast(C.DEFAULT_BUFFER_SEGMENT_SIZE.toLong())
        val budget = if (preload) preloadBudget else foregroundBudget
        val durationMs = if (preload) PLAYER_PRELOAD_RANGE_MS else policy.maxBufferMs.toLong()
        var knownBytes = 0L
        var unknownBytes = 0L
        for (selection in trackSelectionArray.filterNotNull()) {
            // Include only eligible selection tracks, so Auto can move up without
            // rebuilding the player, while a manual override retains its own sizing.
            val bitrates = (0 until selection.length()).map { selection.getFormat(it).bitrate }
            if (bitrates.all { it > 0 }) {
                // 25% allows for bitrate variation and compressed sample overhead.
                knownBytes += bitrates.max().toLong() * durationMs / 8_000 * 5 / 4
            } else {
                // Missing bitrate is common on IPTV. Use Media3's track-type estimate,
                // rather than confusing download throughput with encoded media bitrate.
                unknownBytes += super.calculateTargetBufferBytes(parameters, arrayOf(selection))
            }
        }
        val floor = if (preload) DEFAULT_MIN_BUFFER_SIZE else
            maxOf(DEFAULT_MIN_BUFFER_SIZE, policy.targetBufferBytes)
        val target = (knownBytes + unknownBytes).coerceAtLeast(floor.toLong()).coerceAtMost(budget).toInt()
        if (!preload) foregroundTargetBytes = target
        return target
    }
}
