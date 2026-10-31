@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import android.net.Uri
import androidx.media3.common.util.TimestampAdjuster
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.text.SubtitleParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AvcAccessUnitExtractorsFactoryTest {

    @Test
    fun `wraps TS extractors and preserves non TS extractor instances`() {
        val nonTs = PassthroughExtractor()
        val ts = TsExtractor(
            TsExtractor.MODE_SINGLE_PMT,
            TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
            SubtitleParser.Factory.UNSUPPORTED,
            TimestampAdjuster(0),
            DefaultTsPayloadReaderFactory(
                DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                    DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
            ),
            TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
        )
        val upstream = FixedExtractorsFactory(arrayOf(nonTs, ts))

        val wrapped = AvcAccessUnitExtractorsFactory(upstream).createExtractors()

        assertThat(wrapped).hasLength(2)
        assertThat(wrapped[0]).isSameInstanceAs(nonTs)
        assertThat(wrapped[1]).isNotSameInstanceAs(ts)
        assertThat((wrapped[1] as androidx.media3.extractor.ForwardingExtractor).underlyingImplementation)
            .isSameInstanceAs(ts)
    }

    @Test
    fun `unnormalized factory path leaves TS extractor direct`() {
        val extractors = liveMpegTsExtractorsFactory(normalizeAvcSamples = false).createExtractors()

        assertThat(extractors.none { it.javaClass.simpleName == "AvcAccessUnitExtractor" }).isTrue()
        assertThat(extractors.any { it is TsExtractor }).isTrue()
    }

    private class FixedExtractorsFactory(private val extractors: Array<Extractor>) : ExtractorsFactory {
        override fun createExtractors(): Array<Extractor> = extractors

        override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
            extractors
    }

    private class PassthroughExtractor : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = false
        override fun init(output: ExtractorOutput) = Unit
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = Extractor.RESULT_END_OF_INPUT
        override fun seek(position: Long, timeUs: Long) = Unit
        override fun release() = Unit
    }
}
