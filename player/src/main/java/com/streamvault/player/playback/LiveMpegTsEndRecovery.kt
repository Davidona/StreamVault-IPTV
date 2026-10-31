package com.streamvault.player.playback

import com.streamvault.domain.model.PlaybackContentKind
import com.streamvault.domain.model.StreamInfo
import java.net.URI

/** A clean end is unexpected only for an explicitly live, directly owned HTTP TS source. */
fun canRecoverLiveMpegTsEnd(
    streamInfo: StreamInfo?,
    streamType: ResolvedStreamType,
    playbackStarted: Boolean,
    playWhenReady: Boolean,
    hasIndependentSourceOwner: Boolean,
    retryPending: Boolean
): Boolean = streamInfo != null &&
    streamInfo.playbackContentKind == PlaybackContentKind.LIVE &&
    streamType == ResolvedStreamType.MPEG_TS_LIVE && streamInfo.drmInfo == null &&
    runCatching { URI(streamInfo.url).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false) &&
    playbackStarted && playWhenReady && !hasIndependentSourceOwner && !retryPending

/** One refresh per tune; a source that repeatedly ends must not reopen forever. */
class LiveMpegTsEndRecovery {
    private val drain = StartupRefreshGate()
    private var attempted = false
    val hasAttempted: Boolean get() = attempted
    val isPending: Boolean get() = drain.isPending
    var abandoned = false
        private set

    fun onEnded(nowMs: Long, eligible: Boolean): Boolean {
        if (!eligible || attempted) return false
        attempted = true
        drain.begin(nowMs)
        return true
    }

    fun evaluate(nowMs: Long, canResume: Boolean, hasActiveTransfers: Boolean): StartupRefreshGate.Action =
        drain.evaluate(nowMs, canResume, hasActiveTransfers).also {
            if (it == StartupRefreshGate.Action.GIVE_UP) abandoned = true
        }

    fun pauseClock() = drain.pauseClock()

    fun reset(preserveAttempt: Boolean = false) {
        drain.clear()
        abandoned = false
        if (!preserveAttempt) attempted = false
    }
}
