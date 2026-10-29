package com.streamvault.player.playback

import com.streamvault.player.PlaybackState

class VideoStallDetector(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val initialGraceMs: Long = 8_000L,
    private val stallThresholdMs: Long = 5_000L,
    private val bufferingStallThresholdMs: Long = 8_000L,
    private val minPositionAdvanceMs: Long = 1_500L
) {
    private var startedAtMs: Long = 0L
    private var lastFrameAtMs: Long = 0L
    private var firstFramePositionMs: Long = 0L
    private var lastPositionMs: Long = 0L
    private var lastPositionCheckMs: Long = 0L
    private var stalled = false
    private var bufferingReserveHighWaterMs: Long? = null
    private var lastBufferingProgressAtMs: Long? = null

    @Synchronized
    fun restartAfterRecoveryAttempt() {
        startedAtMs = nowMs()
        lastPositionCheckMs = startedAtMs
        stalled = false
        clearBufferingProgress()
        // Retain lastFrameAtMs: a seek attempt is not evidence of a rendered frame.
    }

    @Synchronized
    fun reset() {
        val now = nowMs()
        startedAtMs = now
        lastFrameAtMs = 0L
        firstFramePositionMs = 0L
        lastPositionMs = 0L
        lastPositionCheckMs = now
        stalled = false
        clearBufferingProgress()
    }

    @Synchronized
    fun onVideoFrameRendered(currentPositionMs: Long = 0L) {
        if (lastFrameAtMs <= 0L) {
            firstFramePositionMs = currentPositionMs.coerceAtLeast(0L)
        }
        lastFrameAtMs = nowMs()
        stalled = false
    }

    @Synchronized
    fun lastVideoFrameAgoMs(): Long {
        val frameAt = lastFrameAtMs
        return if (frameAt <= 0L) 0L else (nowMs() - frameAt).coerceAtLeast(0L)
    }

    @Synchronized
    fun shouldReportStall(
        playbackState: PlaybackState,
        isPlaying: Boolean,
        playbackStarted: Boolean,
        currentPositionMs: Long,
        bufferedDurationMs: Long,
        playWhenReady: Boolean = isPlaying,
        recoverBufferingStalls: Boolean = false,
        recoverReadyStalls: Boolean = true,
        recoverPositionAdvancingReadyStalls: Boolean = true,
        recoverFrameSilentReadyStalls: Boolean = false,
        deferReporting: Boolean = false
    ): Boolean {
        val now = nowMs()
        val playbackRequested = isPlaying || playWhenReady
        val canEvaluateReadyStall = recoverReadyStalls && playbackState == PlaybackState.READY
        val canEvaluateBufferingStall = recoverBufferingStalls && playbackState == PlaybackState.BUFFERING
        if ((!canEvaluateReadyStall && !canEvaluateBufferingStall) || !playbackRequested || !playbackStarted) {
            lastPositionMs = currentPositionMs
            lastPositionCheckMs = now
            stalled = false
            clearBufferingProgress()
            return false
        }
        if (canEvaluateBufferingStall) {
            val previousReserve = bufferingReserveHighWaterMs
            if (previousReserve == null || bufferedDurationMs > previousReserve) {
                bufferingReserveHighWaterMs = bufferedDurationMs
                // A first observation is only a baseline. Later growth proves active refill;
                // frame silence is expected while load control waits for enough media.
                if (previousReserve != null) lastBufferingProgressAtMs = now
            }
        } else {
            clearBufferingProgress()
        }
        if (deferReporting) {
            // Observe refill throughout startup grace, but do not consume a one-shot stall
            // that the caller cannot handle yet. Keep frame and progress clocks intact.
            stalled = false
            return false
        }
        if (now - startedAtMs < initialGraceMs) return false
        if (canEvaluateBufferingStall) {
            val frameAtMs = if (lastFrameAtMs <= 0L) startedAtMs else lastFrameAtMs
            val lastProgressAtMs = maxOf(frameAtMs, lastBufferingProgressAtMs ?: frameAtMs)
            val frameSilent = now - lastProgressAtMs >= bufferingStallThresholdMs
            if (frameSilent && !stalled) {
                stalled = true
                return true
            }
            if (!frameSilent) stalled = false
            return false
        }
        if (lastFrameAtMs <= 0L) return false
        val frameSilent = now - lastFrameAtMs >= stallThresholdMs
        val requestedButNotAdvancing = playWhenReady && !isPlaying && frameSilent
        val frameSilentReadyRecovery = recoverFrameSilentReadyStalls && frameSilent
        if (!requestedButNotAdvancing && !frameSilentReadyRecovery && bufferedDurationMs <= 1_000L) {
            lastPositionMs = currentPositionMs
            lastPositionCheckMs = now
            stalled = false
            return false
        }
        if (
            !requestedButNotAdvancing &&
            !frameSilentReadyRecovery &&
            currentPositionMs - firstFramePositionMs < minPositionAdvanceMs
        ) {
            return false
        }

        val positionAdvanced = currentPositionMs - lastPositionMs >= minPositionAdvanceMs
        val checkWindowElapsed = now - lastPositionCheckMs >= minPositionAdvanceMs
        if (checkWindowElapsed) {
            lastPositionMs = currentPositionMs
            lastPositionCheckMs = now
        }

        val nextStalled = (
            frameSilentReadyRecovery ||
                requestedButNotAdvancing ||
                recoverPositionAdvancingReadyStalls && positionAdvanced
            ) && frameSilent
        if (nextStalled && !stalled) {
            stalled = true
            return true
        }
        if (!frameSilent) stalled = false
        return false
    }

    private fun clearBufferingProgress() {
        bufferingReserveHighWaterMs = null
        lastBufferingProgressAtMs = null
    }
}
