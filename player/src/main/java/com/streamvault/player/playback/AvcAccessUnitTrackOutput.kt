package com.streamvault.player.playback

import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput
import java.util.ArrayDeque

/** Keeps non-picture AVC prefix samples with their following picture. */
@UnstableApi
internal class AvcAccessUnitTrackOutput(
    delegate: TrackOutput,
    private val maxPendingHeaderBytes: Int = 256 * 1024,
    private val maxPendingHeaderSamples: Int = 64,
    private val maxNalMarkers: Int = 4096
) : ForwardingTrackOutput(delegate) {
    private data class Nal(val position: Long, val type: Int)
    private data class Header(val timeUs: Long, val flags: Int, val start: Long, val end: Long)

    private val nalMarkers = ArrayDeque<Nal>()
    private val pendingHeaders = ArrayDeque<Header>()
    private val prefixFlags = BooleanArray(3)
    private var bytesWritten = 0L
    private var formatKnown = false
    private var clearAvc = false
    private var passthroughUntilReset = false

    init {
        require(maxPendingHeaderBytes > 0 && maxPendingHeaderSamples > 0 && maxNalMarkers > 0)
    }

    override fun format(format: Format) {
        val nextClearAvc = format.sampleMimeType == MimeTypes.VIDEO_H264 && format.drmInitData == null
        if (!nextClearAvc) {
            flushHeaders()
            nalMarkers.clear()
            prefixFlags.fill(false)
        }
        formatKnown = true
        clearAvc = nextClearAvc
        super.format(format)
    }

    override fun sampleData(data: ParsableByteArray, length: Int) =
        sampleData(data, length, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (sampleDataPart != TrackOutput.SAMPLE_DATA_PART_MAIN) {
            fallBackToPassthrough()
        } else if (!passthroughUntilReset && (!formatKnown || clearAvc)) {
            // Scan before Format is known too: one TS payload can contain both CSD and VCL.
            scanNals(data.data, data.position, data.position + length)
        }
        super.sampleData(data, length, sampleDataPart)
        bytesWritten += length
    }

    override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean): Int =
        sampleData(input, length, allowEndOfInput, TrackOutput.SAMPLE_DATA_PART_MAIN)

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        // H264Reader uses ParsableByteArray. Preserve unfamiliar/opaque writer paths verbatim.
        fallBackToPassthrough()
        return super.sampleData(input, length, allowEndOfInput, sampleDataPart).also {
            if (it > 0) bytesWritten += it
        }
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        val end = bytesWritten - offset
        val start = end - size
        if (cryptoData != null) fallBackToPassthrough()
        if (!clearAvc || passthroughUntilReset || cryptoData != null ||
            size <= 0 || offset < 0 || start < 0
        ) {
            flushHeaders()
            discardMarkersBefore(end)
            super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
            return
        }

        discardMarkersBefore(start)
        var hasNal = false
        var hasPicture = false
        var onlyPrefixNals = true
        for (nal in nalMarkers) {
            if (nal.position >= end) break
            hasNal = true
            if (nal.type in 1..5 || nal.type in 19..21) hasPicture = true
            if (nal.type !in PREFIX_NAL_TYPES) onlyPrefixNals = false
        }
        discardMarkersBefore(end)

        if (pendingHeaders.isNotEmpty() && pendingHeaders.last.end != start) flushHeaders()
        if (hasPicture) {
            val mergedSize = if (pendingHeaders.isEmpty()) size.toLong() else end - pendingHeaders.first.start
            if (mergedSize > Int.MAX_VALUE) {
                fallBackToPassthrough()
                super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
                return
            }
            var mergedFlags = flags
            for (header in pendingHeaders) mergedFlags = mergedFlags or header.flags
            pendingHeaders.clear()
            // Keep the picture's timestamp. Equal timestamps on actual pictures are not deduplicated.
            super.sampleMetadata(timeUs, mergedFlags, mergedSize.toInt(), offset, cryptoData)
        } else if (hasNal && onlyPrefixNals) {
            pendingHeaders.addLast(Header(timeUs, flags, start, end))
            if (pendingHeaders.size > maxPendingHeaderSamples ||
                end - pendingHeaders.first.start > maxPendingHeaderBytes
            ) fallBackToPassthrough()
        } else {
            // Unknown NALs or missing boundaries must not silently discard samples.
            flushHeaders()
            super.sampleMetadata(timeUs, flags, size, offset, cryptoData)
        }
    }

    /** The owning extractor's seek resets its sample queues; old metadata must not be flushed. */
    fun reset() {
        pendingHeaders.clear()
        nalMarkers.clear()
        prefixFlags.fill(false)
        bytesWritten = 0L
        passthroughUntilReset = false
    }

    private fun scanNals(data: ByteArray, start: Int, end: Int) {
        var offset = start
        while (offset < end) {
            val nalStart = NalUnitUtil.findNalUnit(data, offset, end, prefixFlags)
            if (nalStart == end) return
            val header = nalStart + 3
            if (header !in start until end || nalMarkers.size >= maxNalMarkers) {
                fallBackToPassthrough()
                return
            }
            nalMarkers.addLast(Nal(bytesWritten + header - start, data[header].toInt() and 0x1f))
            offset = header + 1
        }
    }

    private fun discardMarkersBefore(position: Long) {
        while (nalMarkers.isNotEmpty() && nalMarkers.first.position < position) nalMarkers.removeFirst()
    }

    private fun flushHeaders() {
        while (pendingHeaders.isNotEmpty()) {
            val header = pendingHeaders.removeFirst()
            super.sampleMetadata(
                header.timeUs, header.flags, (header.end - header.start).toInt(),
                (bytesWritten - header.end).toInt(), null
            )
        }
    }

    private fun fallBackToPassthrough() {
        flushHeaders()
        nalMarkers.clear()
        prefixFlags.fill(false)
        passthroughUntilReset = true
    }

    private companion object {
        // SEI, SPS, PPS, AUD, filler, SPS extension, prefix and subset SPS.
        val PREFIX_NAL_TYPES = setOf(6, 7, 8, 9, 12, 13, 14, 15)
    }
}
