package com.streamvault.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.FormatHolder

/** Shifts text sample timestamps while leaving audio and video timing unchanged. */
@UnstableApi
internal class SubtitleOffsetMediaSource(
    mediaSource: MediaSource,
    private val offsetUs: () -> Long
) : WrappingMediaSource(mediaSource) {
    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod = SubtitleOffsetMediaPeriod(
        delegate = super.createPeriod(id, allocator, startPositionUs),
        offsetUs = offsetUs
    )

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        val wrapped = mediaPeriod as? SubtitleOffsetMediaPeriod
            ?: error("Unexpected media period released by subtitle-offset source")
        super.releasePeriod(wrapped.delegate)
    }
}

@UnstableApi
internal class SubtitleOffsetMediaPeriod(
    internal val delegate: MediaPeriod,
    private val offsetUs: () -> Long
) : MediaPeriod by delegate {
    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        delegate.prepare(
            object : MediaPeriod.Callback {
                override fun onPrepared(mediaPeriod: MediaPeriod) {
                    callback.onPrepared(this@SubtitleOffsetMediaPeriod)
                }

                override fun onContinueLoadingRequested(source: MediaPeriod) {
                    callback.onContinueLoadingRequested(this@SubtitleOffsetMediaPeriod)
                }
            },
            positionUs
        )
    }

    override fun selectTracks(
        selections: Array<out ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<out SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        val existingWrappers = Array<SubtitleOffsetSampleStream?>(streams.size) { index ->
            streams[index] as? SubtitleOffsetSampleStream
        }
        val childStreams = Array<SampleStream?>(streams.size) { index ->
            existingWrappers[index]?.delegate ?: streams[index]
        }
        val selectedPositionUs = delegate.selectTracks(
            selections,
            mayRetainStreamFlags,
            childStreams,
            streamResetFlags,
            positionUs
        )

        @Suppress("UNCHECKED_CAST")
        val mutableStreams = streams as Array<SampleStream?>
        for (index in mutableStreams.indices) {
            val selection = selections[index]
            val sampleMimeType = selection?.selectedFormat?.sampleMimeType
            mutableStreams[index] = childStreams[index]?.let { childStream ->
                if (sampleMimeType != null && MimeTypes.getTrackType(sampleMimeType) == C.TRACK_TYPE_TEXT) {
                    existingWrappers[index]?.takeIf { it.delegate === childStream }
                        ?: SubtitleOffsetSampleStream(childStream, offsetUs)
                } else {
                    childStream
                }
            }
        }
        return selectedPositionUs
    }
}

@UnstableApi
internal class SubtitleOffsetSampleStream(
    internal val delegate: SampleStream,
    private val offsetUs: () -> Long
) : SampleStream by delegate {
    override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        val result = delegate.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_BUFFER_READ && !buffer.isEndOfStream) {
            buffer.timeUs += offsetUs()
        }
        return result
    }

    override fun skipData(positionUs: Long): Int = delegate.skipData(positionUs - offsetUs())
}
