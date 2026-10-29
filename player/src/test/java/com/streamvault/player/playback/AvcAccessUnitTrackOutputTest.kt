@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AvcAccessUnitTrackOutputTest {

    @Test
    fun `holds AUD SPS PPS and SEI samples and merges their bytes and flags into following VCL`() {
        val delegate = RecordingTrackOutput()
        val output = AvcAccessUnitTrackOutput(delegate)
        output.format(avcFormat())
        val headers = listOf(
            annexB(9, 0xf0),
            annexB3(7, 0x11, 0x12),
            annexB(8, 0x21),
            annexB3(6, 0x31, 0x32)
        )
        headers.forEachIndexed { index, bytes ->
            writeSample(output, bytes, timeUs = 10_000L + index, flags = if (index == 0) OTHER_FLAG else 0)
        }

        assertThat(delegate.samples).isEmpty()

        val picture = annexB(5, 0x41, 0x42, 0x43)
        writeSample(output, picture, timeUs = 33_333L, flags = C.BUFFER_FLAG_KEY_FRAME)

        assertThat(delegate.samples).hasSize(1)
        assertSample(
            delegate.samples.single(),
            bytes = headers.fold(byteArrayOf()) { all, next -> all + next } + picture,
            timeUs = 33_333L,
            flags = OTHER_FLAG or C.BUFFER_FLAG_KEY_FRAME
        )
    }

    @Test
    fun `keeps separate VCL samples even when their timestamps are equal`() {
        val delegate = RecordingTrackOutput()
        val output = AvcAccessUnitTrackOutput(delegate)
        output.format(avcFormat())
        val first = annexB(5, 0x51, 0x52)
        val second = annexB3(1, 0x61, 0x62)

        writeSample(output, first, timeUs = 90_000L, flags = C.BUFFER_FLAG_KEY_FRAME)
        writeSample(output, second, timeUs = 90_000L, flags = 0)

        assertThat(delegate.samples).hasSize(2)
        assertSample(delegate.samples[0], first, 90_000L, C.BUFFER_FLAG_KEY_FRAME)
        assertSample(delegate.samples[1], second, 90_000L, 0)
    }

    @Test
    fun `delivers VCL without AUD immediately`() {
        val delegate = RecordingTrackOutput()
        val output = AvcAccessUnitTrackOutput(delegate)
        output.format(avcFormat())
        val picture = annexB3(1, 0x71, 0x72)

        writeSample(output, picture, timeUs = 123_000L)

        assertThat(delegate.samples).hasSize(1)
        assertSample(delegate.samples.single(), picture, 123_000L, 0)
    }

    @Test
    fun `recognizes split three and four byte prefixes written before format`() {
        val delegate = RecordingTrackOutput()
        val output = AvcAccessUnitTrackOutput(delegate)
        val aud = annexB3(9, 0xf0)
        val split = 2
        output.sampleData(ParsableByteArray(aud.copyOfRange(0, split)), split)
        output.sampleData(ParsableByteArray(aud.copyOfRange(split, aud.size)), aud.size - split)
        output.format(avcFormat())
        output.sampleMetadata(0L, 0, aud.size, 0, null)
        val picture = annexB(5, 0x01, 0x02)

        assertThat(delegate.samples).isEmpty()
        writeSample(output, picture, timeUs = 40_000L, flags = C.BUFFER_FLAG_KEY_FRAME)

        assertThat(delegate.samples).hasSize(1)
        assertSample(delegate.samples.single(), aud + picture, 40_000L, C.BUFFER_FLAG_KEY_FRAME)
    }

    @Test
    fun `uses metadata size and offset when sample data is written ahead`() {
        val delegate = RecordingTrackOutput()
        val output = AvcAccessUnitTrackOutput(delegate)
        output.format(avcFormat())
        val prefix = annexB(9, 0xf0)
        val firstPicture = annexB3(5, 0x11, 0x12)
        val secondPicture = annexB(1, 0x21, 0x22, 0x23)
        val trailing = byteArrayOf(0x55, 0x66)
        val writtenAhead = prefix + firstPicture + secondPicture + trailing
        output.sampleData(ParsableByteArray(writtenAhead), writtenAhead.size)

        output.sampleMetadata(
            0L,
            OTHER_FLAG,
            prefix.size,
            writtenAhead.size - prefix.size,
            null
        )
        output.sampleMetadata(
            33_333L,
            C.BUFFER_FLAG_KEY_FRAME,
            firstPicture.size,
            secondPicture.size + trailing.size,
            null
        )
        output.sampleMetadata(33_333L, 0, secondPicture.size, trailing.size, null)

        assertThat(delegate.samples).hasSize(2)
        assertSample(
            delegate.samples[0],
            prefix + firstPicture,
            33_333L,
            OTHER_FLAG or C.BUFFER_FLAG_KEY_FRAME
        )
        assertSample(delegate.samples[1], secondPicture, 33_333L, 0)
    }

    @Test
    fun `reset discards pending headers and prevents cross seek merge`() {
        val delegate = RecordingTrackOutput()
        val output = AvcAccessUnitTrackOutput(delegate)
        output.format(avcFormat())
        val header = annexB(9, 0xf0)
        val picture = annexB3(1, 0x31, 0x32)
        writeSample(output, header, timeUs = 100_000L)

        output.reset()
        writeSample(output, picture, timeUs = 0L)

        assertThat(delegate.samples).hasSize(1)
        assertSample(delegate.samples.single(), picture, 0L, 0)
    }

    @Test
    fun `passes non AVC and DRM crypto samples through unchanged`() {
        val nonAvcDelegate = RecordingTrackOutput()
        val nonAvc = AvcAccessUnitTrackOutput(nonAvcDelegate)
        nonAvc.format(Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).build())
        val h265LikeHeader = annexB(9, 0xf0)
        val h265LikePicture = annexB3(1, 0x31)
        writeSample(nonAvc, h265LikeHeader, 10_000L)
        writeSample(nonAvc, h265LikePicture, 10_000L)
        assertThat(nonAvcDelegate.samples).hasSize(2)
        assertSample(nonAvcDelegate.samples[0], h265LikeHeader, 10_000L, 0)
        assertSample(nonAvcDelegate.samples[1], h265LikePicture, 10_000L, 0)

        val drmDelegate = RecordingTrackOutput()
        val drmOutput = AvcAccessUnitTrackOutput(drmDelegate)
        drmOutput.format(avcFormat().buildUpon().setDrmInitData(DrmInitData("cenc", emptyList())).build())
        val key = ByteArray(16) { it.toByte() }
        val crypto = TrackOutput.CryptoData(C.CRYPTO_MODE_AES_CTR, key, 0, 0)
        val encryptedHeaderNal = annexB(9, 0xf0)
        writeSample(drmOutput, encryptedHeaderNal, 20_000L, flags = 0, cryptoData = crypto)

        assertThat(drmDelegate.samples).hasSize(1)
        assertSample(drmDelegate.samples.single(), encryptedHeaderNal, 20_000L, 0)
        assertThat(drmDelegate.samples.single().cryptoData).isSameInstanceAs(crypto)
    }

    @Test
    fun `clear AVC format with crypto metadata flushes prefix and preserves encrypted sample`() {
        val delegate = RecordingTrackOutput()
        val output = AvcAccessUnitTrackOutput(delegate)
        output.format(avcFormat())
        val prefix = annexB(9, 0xf0)
        val encryptedPicture = annexB3(5, 0x25, 0x26)
        val followingPrefix = annexB3(9, 0xf0)
        val followingPicture = annexB(1, 0x27, 0x28)
        val crypto = TrackOutput.CryptoData(C.CRYPTO_MODE_AES_CTR, ByteArray(16) { it.toByte() }, 0, 0)

        writeSample(output, prefix, timeUs = 10_000L, flags = OTHER_FLAG)
        writeSample(output, encryptedPicture, timeUs = 33_333L, flags = C.BUFFER_FLAG_KEY_FRAME, cryptoData = crypto)
        writeSample(output, followingPrefix, timeUs = 40_000L, flags = OTHER_FLAG)
        writeSample(output, followingPicture, timeUs = 66_666L)

        assertThat(delegate.samples).hasSize(4)
        assertSample(delegate.samples[0], prefix, 10_000L, OTHER_FLAG)
        assertSample(delegate.samples[1], encryptedPicture, 33_333L, C.BUFFER_FLAG_KEY_FRAME)
        assertThat(delegate.samples[1].cryptoData).isSameInstanceAs(crypto)
        assertSample(delegate.samples[2], followingPrefix, 40_000L, OTHER_FLAG)
        assertSample(delegate.samples[3], followingPicture, 66_666L, 0)
    }

    @Test
    fun `opaque DataReader path flushes prefix and preserves byte order`() {
        val delegate = RecordingTrackOutput()
        val output = AvcAccessUnitTrackOutput(delegate)
        output.format(avcFormat())
        val prefix = annexB(9, 0xf0)
        val picture = annexB3(1, 0x35, 0x36)
        writeSample(output, prefix, timeUs = 10_000L, flags = OTHER_FLAG)

        val input = ByteArrayInputStream(picture)
        val reader = DataReader { target, offset, length -> input.read(target, offset, length) }
        assertThat(output.sampleData(reader, picture.size, false, TrackOutput.SAMPLE_DATA_PART_MAIN)).isEqualTo(picture.size)
        output.sampleMetadata(33_333L, C.BUFFER_FLAG_KEY_FRAME, picture.size, 0, null)

        assertThat(delegate.samples).hasSize(2)
        assertSample(delegate.samples[0], prefix, 10_000L, OTHER_FLAG)
        assertSample(delegate.samples[1], picture, 33_333L, C.BUFFER_FLAG_KEY_FRAME)
    }

    @Test
    fun `byte sample and NAL marker limits fall back to original metadata`() {
        val header = annexB(9, 0xf0)
        val picture = annexB3(1, 0x31, 0x32)

        val byteLimitedDelegate = RecordingTrackOutput()
        val byteLimited = AvcAccessUnitTrackOutput(
            delegate = byteLimitedDelegate,
            maxPendingHeaderBytes = header.size - 1
        )
        byteLimited.format(avcFormat())
        writeSample(byteLimited, header, 10_000L, flags = OTHER_FLAG)
        writeSample(byteLimited, picture, 33_333L, flags = C.BUFFER_FLAG_KEY_FRAME)
        assertThat(byteLimitedDelegate.samples).hasSize(2)
        assertSample(byteLimitedDelegate.samples[0], header, 10_000L, OTHER_FLAG)
        assertSample(byteLimitedDelegate.samples[1], picture, 33_333L, C.BUFFER_FLAG_KEY_FRAME)

        val sampleLimitedDelegate = RecordingTrackOutput()
        val sampleLimited = AvcAccessUnitTrackOutput(
            delegate = sampleLimitedDelegate,
            maxPendingHeaderSamples = 1
        )
        sampleLimited.format(avcFormat())
        val secondHeader = annexB3(6, 0x41)
        writeSample(sampleLimited, header, 10_000L)
        writeSample(sampleLimited, secondHeader, 10_001L)
        writeSample(sampleLimited, picture, 33_333L)
        assertThat(sampleLimitedDelegate.samples).hasSize(3)
        assertSample(sampleLimitedDelegate.samples[0], header, 10_000L, 0)
        assertSample(sampleLimitedDelegate.samples[1], secondHeader, 10_001L, 0)
        assertSample(sampleLimitedDelegate.samples[2], picture, 33_333L, 0)

        val markerLimitedDelegate = RecordingTrackOutput()
        val markerLimited = AvcAccessUnitTrackOutput(
            delegate = markerLimitedDelegate,
            maxNalMarkers = 1
        )
        markerLimited.format(avcFormat())
        val twoHeaders = annexB(9, 0xf0) + annexB3(6, 0x51)
        writeSample(markerLimited, twoHeaders, 10_000L)
        writeSample(markerLimited, picture, 33_333L)
        assertThat(markerLimitedDelegate.samples).hasSize(2)
        assertSample(markerLimitedDelegate.samples[0], twoHeaders, 10_000L, 0)
        assertSample(markerLimitedDelegate.samples[1], picture, 33_333L, 0)
    }

    private fun avcFormat() = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()

    private fun writeSample(
        output: TrackOutput,
        bytes: ByteArray,
        timeUs: Long,
        flags: Int = 0,
        cryptoData: TrackOutput.CryptoData? = null
    ) {
        output.sampleData(ParsableByteArray(bytes), bytes.size)
        output.sampleMetadata(timeUs, flags, bytes.size, 0, cryptoData)
    }

    private fun assertSample(sample: CapturedSample, bytes: ByteArray, timeUs: Long, flags: Int) {
        assertThat(sample.bytes.toList()).isEqualTo(bytes.toList())
        assertThat(sample.timeUs).isEqualTo(timeUs)
        assertThat(sample.flags).isEqualTo(flags)
    }

    private fun annexB(type: Int, vararg payload: Int) =
        byteArrayOf(0, 0, 0, 1, type.toByte()) + payload.map(Int::toByte).toByteArray()

    private fun annexB3(type: Int, vararg payload: Int) =
        byteArrayOf(0, 0, 1, type.toByte()) + payload.map(Int::toByte).toByteArray()

    private data class CapturedSample(
        val bytes: ByteArray,
        val timeUs: Long,
        val flags: Int,
        val cryptoData: TrackOutput.CryptoData?
    )

    private class RecordingTrackOutput : TrackOutput {
        private val pending = ByteArrayOutputStream()
        val samples = mutableListOf<CapturedSample>()

        override fun format(format: Format) = Unit

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            val count = input.read(bytes, 0, length)
            if (count == C.RESULT_END_OF_INPUT && allowEndOfInput) return C.RESULT_END_OF_INPUT
            if (count < 0) throw java.io.EOFException()
            pending.write(bytes, 0, count)
            return count
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            pending.write(data.data, data.position, length)
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            val written = pending.toByteArray()
            val start = written.size - offset - size
            check(start >= 0 && start + size <= written.size) {
                "Invalid sample boundary size=$size offset=$offset available=${written.size}"
            }
            val sampleBytes = written.copyOfRange(start, start + size)
            val trailing = written.copyOfRange(start + size, written.size)
            pending.reset()
            if (trailing.isNotEmpty()) pending.write(trailing)
            samples += CapturedSample(sampleBytes, timeUs, flags, cryptoData)
        }
    }

    private companion object {
        const val OTHER_FLAG = 0x20
    }
}
