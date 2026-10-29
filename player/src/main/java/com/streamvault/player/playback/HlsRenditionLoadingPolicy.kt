@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.CompositeSequenceableLoaderFactory
import androidx.media3.exoplayer.source.DefaultCompositeSequenceableLoaderFactory
import androidx.media3.exoplayer.source.SequenceableLoader
import com.streamvault.domain.model.PlaybackContentKind

/** Keep an in-flight optional rendition from pinning live audio/video prefetch. */
fun HlsMediaSource.Factory.applyPlaybackRenditionLoadingPolicy(
    contentKind: PlaybackContentKind
): HlsMediaSource.Factory = setCompositeSequenceableLoaderFactory(
    if (isLivePlayback(contentKind, ResolvedStreamType.HLS)) {
        InFlightOptionalRenditionLoaderFactory()
    } else {
        DefaultCompositeSequenceableLoaderFactory()
    }
)

internal class InFlightOptionalRenditionLoaderFactory : CompositeSequenceableLoaderFactory {
    private val upstream = DefaultCompositeSequenceableLoaderFactory()

    override fun empty(): SequenceableLoader = upstream.empty()

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun createCompositeSequenceableLoader(vararg loaders: SequenceableLoader): SequenceableLoader =
        upstream.createCompositeSequenceableLoader(*loaders)

    override fun create(
        loaders: List<SequenceableLoader>,
        loaderTrackTypes: List<List<Int>>
    ): SequenceableLoader {
        val aggregate = upstream.create(loaders, loaderTrackTypes)
        val optional = BooleanArray(loaders.size) { index ->
            val types = loaderTrackTypes[index]
            types.isNotEmpty() && types.all { it == C.TRACK_TYPE_TEXT || it == C.TRACK_TYPE_METADATA }
        }
        if (optional.none { it } || optional.all { it }) return aggregate
        return InFlightOptionalRenditionLoader(aggregate, loaders.toList(), optional)
    }
}

private class InFlightOptionalRenditionLoader(
    private val aggregate: SequenceableLoader,
    private val loaders: List<SequenceableLoader>,
    private val optional: BooleanArray
) : SequenceableLoader by aggregate {
    private val skipForThisCall = BooleanArray(loaders.size)

    override fun getNextLoadPositionUs(): Long {
        if (!hasUnfinishedPrimary()) return aggregate.nextLoadPositionUs
        var next = Long.MAX_VALUE
        for (index in loaders.indices) {
            if (optional[index] && loaders[index].isLoading) continue
            val position = loaders[index].nextLoadPositionUs
            if (position != C.TIME_END_OF_SOURCE) next = minOf(next, position)
        }
        return if (next == Long.MAX_VALUE) C.TIME_END_OF_SOURCE else next
    }

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean {
        if (!hasUnfinishedPrimary()) return aggregate.continueLoading(loadingInfo)
        for (index in loaders.indices) {
            skipForThisCall[index] = optional[index] && loaders[index].isLoading
        }
        // Preserve Media3's minimum-position/behind-playback scheduling. HLS wrappers
        // permit one load in flight, so another continue call cannot advance a busy one.
        // Snapshot eligibility so starting a text request does not also advance A/V past
        // the load-control budget within this same progress loop.
        var madeProgress = false
        do {
            var next = Long.MAX_VALUE
            for (index in loaders.indices) {
                if (skipForThisCall[index]) continue
                val position = loaders[index].nextLoadPositionUs
                if (position != C.TIME_END_OF_SOURCE) next = minOf(next, position)
            }
            if (next == Long.MAX_VALUE) break
            var progressed = false
            for (index in loaders.indices) {
                if (skipForThisCall[index]) continue
                val loader = loaders[index]
                val position = loader.nextLoadPositionUs
                val behind = position != C.TIME_END_OF_SOURCE && position <= loadingInfo.playbackPositionUs
                if (position == next || behind) progressed = loader.continueLoading(loadingInfo) || progressed
            }
            madeProgress = madeProgress || progressed
        } while (progressed)
        return madeProgress
    }

    private fun hasUnfinishedPrimary(): Boolean = loaders.indices.any {
        !optional[it] && loaders[it].nextLoadPositionUs != C.TIME_END_OF_SOURCE
    }
}
