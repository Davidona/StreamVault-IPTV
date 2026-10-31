package com.streamvault.data.sync

import kotlinx.coroutines.CancellationException

/** Cooperative control flow for an uncommitted bulk LIVE attempt, never a provider failure. */
internal class XtreamLivePlaybackDeferredException : CancellationException("Automatic live catalog yielded for playback")

internal fun shouldYieldXtreamLiveBatch(automatic: Boolean, deferWhileBusy: Boolean, playbackActive: Boolean): Boolean =
    automatic && deferWhileBusy && playbackActive

internal fun Throwable.isXtreamLivePlaybackDeferral(): Boolean =
    this is XtreamLivePlaybackDeferredException ||
        (this is CancellationException && cause != null && cause !== this && cause!!.isXtreamLivePlaybackDeferral())
