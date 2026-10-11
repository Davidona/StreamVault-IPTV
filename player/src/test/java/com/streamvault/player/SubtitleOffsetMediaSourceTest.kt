package com.streamvault.player

import androidx.media3.common.C
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.source.SampleStream
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SubtitleOffsetMediaSourceTest {

    @Test
    fun `sample timestamps shift both earlier and later as the offset changes`() {
        val delegate = TestSampleStream(sampleTimeUs = 2_000_000L)
        var offsetUs = 300_000L
        val stream = SubtitleOffsetSampleStream(delegate) { offsetUs }
        val holder = FormatHolder()
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

        assertThat(stream.readData(holder, buffer, 0)).isEqualTo(C.RESULT_BUFFER_READ)
        assertThat(buffer.timeUs).isEqualTo(2_300_000L)

        offsetUs = -450_000L
        assertThat(stream.readData(holder, buffer, 0)).isEqualTo(C.RESULT_BUFFER_READ)
        assertThat(buffer.timeUs).isEqualTo(1_550_000L)
    }

    @Test
    fun `skip position compensates for the subtitle timestamp offset`() {
        val delegate = TestSampleStream(sampleTimeUs = 0L)
        val stream = SubtitleOffsetSampleStream(delegate) { -250_000L }

        stream.skipData(positionUs = 1_000_000L)

        assertThat(delegate.lastSkipPositionUs).isEqualTo(1_250_000L)
    }

    @Test
    fun `end of stream marker is not shifted`() {
        val delegate = TestSampleStream(sampleTimeUs = 0L, endOfStream = true)
        val stream = SubtitleOffsetSampleStream(delegate) { 500_000L }
        val buffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)

        assertThat(stream.readData(FormatHolder(), buffer, 0)).isEqualTo(C.RESULT_BUFFER_READ)
        assertThat(buffer.isEndOfStream).isTrue()
        assertThat(buffer.timeUs).isEqualTo(0L)
    }

    private class TestSampleStream(
        private val sampleTimeUs: Long,
        private val endOfStream: Boolean = false
    ) : SampleStream {
        var lastSkipPositionUs: Long? = null
            private set

        override fun isReady(): Boolean = true

        override fun maybeThrowError() = Unit

        override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
            buffer.timeUs = sampleTimeUs
            if (endOfStream) buffer.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            return C.RESULT_BUFFER_READ
        }

        override fun skipData(positionUs: Long): Int {
            lastSkipPositionUs = positionUs
            return 1
        }
    }
}
