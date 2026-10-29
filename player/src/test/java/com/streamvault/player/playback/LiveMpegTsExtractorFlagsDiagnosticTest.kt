@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.TimestampAdjuster
import android.net.Uri
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.extractor.text.SubtitleParser
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Opt-in diagnostic; it reads an external TS asset and never changes production parser settings. */
@RunWith(RobolectricTestRunner::class)
class LiveMpegTsExtractorFlagsDiagnosticTest {

    @Test
    fun `report raw live ts sample boundaries with and without access unit detection`() {
        val inputPath = System.getenv(INPUT_ENV)?.trim().orEmpty()
        val reportPath = System.getenv(REPORT_ENV)?.trim().orEmpty()
        assumeTrue("Set $INPUT_ENV and $REPORT_ENV to enable this diagnostic", inputPath.isNotEmpty() && reportPath.isNotEmpty())

        val inputFile = File(inputPath).canonicalFile
        val reportFile = File(reportPath).canonicalFile
        check(inputFile.isFile) { "Diagnostic TS input does not exist: $inputFile" }
        reportFile.parentFile?.mkdirs()

        val currentFlags =
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM
        val withoutAccessUnitDetection =
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM

        val current = extractVariant(inputFile, "current_flags", currentFlags)
        val withoutDetect = extractVariant(inputFile, "without_detect_access_units", withoutAccessUnitDetection)
        val coalesced = extractVariant(
            inputFile,
            "live_factory_avc_prefix_coalescing",
            currentFlags,
            useLiveFactory = true
        )
        check(coalesced.getString("vclSampleSequence") == withoutDetect.getString("vclSampleSequence")) {
            "AVC coalescing changed per-sample VCL PTS/flags/size/payload sequence"
        }
        check(coalesced.getInt("headerOnlyOrNonVclSamples") == 0) {
            "AVC coalescing left header-only samples: ${coalesced.getInt("headerOnlyOrNonVclSamples")}" 
        }
        check(coalesced.getString("samplePayloadSha256") == withoutDetect.getString("samplePayloadSha256")) {
            "AVC coalescing changed concatenated in-range sample payload bytes"
        }
        val report = JSONObject()
            .put("inputPath", inputFile.path)
            .put("inputBytes", inputFile.length())
            .put("inputSha256", sha256(inputFile))
            .put("extractorMode", "MODE_SINGLE_PMT")
            .put("inputLengthContract", "C.LENGTH_UNSET")
            .put("limits", JSONObject().put("maxVclTimeUsInclusive", MAX_VIDEO_TIME_US).put("maxVideoSamples", MAX_VIDEO_SAMPLES))
            .put("coverageNote", "Extraction continues until a VCL sample reaches the 15s boundary; summaries compare only samples with timeUs <= 15s, excluding later batch overshoot.")
            .put("variants", JSONArray().put(current).put(withoutDetect).put(coalesced))

        reportFile.writeText(report.toString(2), Charsets.UTF_8)
        println("LiveMpegTsExtractorFlagsDiagnostic report=${reportFile.path} current=${current.getInt("videoSampleCount")} withoutDetect=${withoutDetect.getInt("videoSampleCount")}")
    }

    private fun extractVariant(
        inputFile: File,
        name: String,
        payloadReaderFlags: Int,
        useLiveFactory: Boolean = false
    ): JSONObject {
        val output = RecordingExtractorOutput()
        val extractor: Extractor
        if (useLiveFactory) {
            val uri = Uri.fromFile(inputFile)
            val candidates = liveMpegTsExtractorsFactory().createExtractors(uri, emptyMap())
            extractor = candidates.firstOrNull { it.javaClass.simpleName == "AvcAccessUnitExtractor" }
                ?: error("Live raw-TS factory did not return its AVC-normalizing TS extractor: ${candidates.map { it.javaClass.name }}")
        } else {
            val payloadReaderFactory = DefaultTsPayloadReaderFactory(payloadReaderFlags)
            extractor = TsExtractor(
                TsExtractor.MODE_SINGLE_PMT,
                TsExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
                SubtitleParser.Factory.UNSUPPORTED,
                TimestampAdjuster(0),
                payloadReaderFactory,
                TsExtractor.DEFAULT_TIMESTAMP_SEARCH_BYTES
            )
        }
        val positionHolder = PositionHolder()
        var result = Extractor.RESULT_CONTINUE
        extractor.init(output)
        FileInputStream(inputFile).use { fileInput ->
            val dataReader = DataReader { buffer, offset, length -> fileInput.read(buffer, offset, length) }
            val input = DefaultExtractorInput(dataReader, 0L, C.LENGTH_UNSET.toLong())
            while (!output.shouldStop() && result != Extractor.RESULT_END_OF_INPUT) {
                result = extractor.read(input, positionHolder)
                check(result != Extractor.RESULT_SEEK) {
                    "Live extractor unexpectedly requested a seek for $name to ${positionHolder.position}"
                }
            }
        }
        extractor.release()
        return output.summary(name, payloadReaderFlags, result)
    }

    private class RecordingExtractorOutput : ExtractorOutput {
        private val tracks = linkedMapOf<Int, RecordingTrackOutput>()
        private var endedTracks = false
        private var seekMapDurationUs = C.TIME_UNSET

        override fun track(id: Int, type: Int): TrackOutput =
            tracks.getOrPut(id) { RecordingTrackOutput(id, type) }

        override fun endTracks() {
            endedTracks = true
        }

        override fun seekMap(seekMap: SeekMap) {
            seekMapDurationUs = seekMap.durationUs
        }

        fun shouldStop(): Boolean {
            val video = tracks.values.firstOrNull { it.trackType == C.TRACK_TYPE_VIDEO }
            return video?.shouldStop() == true
        }

        fun summary(name: String, flags: Int, readResult: Int): JSONObject {
            val videoTracks = tracks.values.filter { it.trackType == C.TRACK_TYPE_VIDEO }
            val allVideoSamples = videoTracks.flatMap { it.samples }
            val videoSamples = allVideoSamples.filter { it.timeUs <= MAX_VIDEO_TIME_US }
            val vclSequence = JSONArray()
            videoSamples.filter { it.hasVcl }.forEach { sample ->
                vclSequence.put(
                    JSONObject()
                        .put("timeUs", sample.timeUs)
                        .put("flags", sample.flags)
                        .put("size", sample.size)
                        .put("payloadSha256", sha256(sample.payload))
                )
            }
            var duplicateTimestamps = 0
            var timestampRegressions = 0
            videoSamples.zipWithNext().forEach { (previous, current) ->
                when {
                    current.timeUs == previous.timeUs -> duplicateTimestamps++
                    current.timeUs < previous.timeUs -> timestampRegressions++
                }
            }
            val firstVideoTimeUs = videoSamples.firstOrNull()?.timeUs
            val lastVideoTimeUs = videoSamples.lastOrNull()?.timeUs
            val nonVclSamples = videoSamples.count { !it.hasVcl }
            val payloadDigest = MessageDigest.getInstance("SHA-256")
            videoSamples.forEach { payloadDigest.update(it.payload) }
            val nalCounts = linkedMapOf<Int, Int>()
            videoSamples.flatMap { it.nalTypes }.forEach { type -> nalCounts[type] = (nalCounts[type] ?: 0) + 1 }
            val firstSamples = JSONArray()
            videoSamples.take(FIRST_SAMPLES_TO_REPORT).forEach { sample ->
                firstSamples.put(
                    JSONObject()
                        .put("timeUs", sample.timeUs)
                        .put("size", sample.size)
                        .put("flags", sample.flags)
                        .put("nalTypes", JSONArray(sample.nalTypes))
                        .put("hasVcl", sample.hasVcl)
                        .put("containsAud", sample.nalTypes.contains(H264_AUD_NAL_TYPE))
                )
            }
            val formats = JSONArray()
            videoTracks.mapNotNull { it.format }.distinctBy { it.sampleMimeType to it.codecs }.forEach { format ->
                formats.put(
                    JSONObject()
                        .put("sampleMimeType", format.sampleMimeType)
                        .put("codecs", format.codecs)
                        .put("width", format.width)
                        .put("height", format.height)
                        .put("frameRate", format.frameRate)
                )
            }
            return JSONObject()
                .put("name", name)
                .put("payloadReaderFlags", flags)
                .put("factoryPath", if (name.startsWith("live_factory")) "liveMpegTsExtractorsFactory" else "directTsExtractorReference")
                .put("extractorMode", if (name.startsWith("live_factory")) "DIRECT_LIVE_MPEG_TS_POLICY (MODE_SINGLE_PMT)" else "MODE_SINGLE_PMT")
                .put("inputLengthContract", "C.LENGTH_UNSET")
                .put("seekMapDurationUs", seekMapDurationUs)
                .put("readResult", readResult)
                .put("tracksEnded", endedTracks)
                .put("videoTrackCount", videoTracks.size)
                .put("videoSampleCount", videoSamples.size)
                .put("rawVideoSampleCountIncludingBoundaryOvershoot", allVideoSamples.size)
                .put("samplesBeyondMaxVclTime", allVideoSamples.count { it.timeUs > MAX_VIDEO_TIME_US })
                .put("videoSampleBytes", videoSamples.sumOf { it.size.toLong() })
                .put("samplePayloadSha256", payloadDigest.digest().joinToString("") { "%02x".format(it) })
                .put("vclSampleCount", videoSamples.count { it.hasVcl })
                .put("lastVclTimeUs", videoSamples.lastOrNull { it.hasVcl }?.timeUs ?: JSONObject.NULL)
                .put("vclSampleSequence", vclSequence.toString())
                .put("zeroSizeSamples", videoSamples.count { it.size == 0 })
                .put("nonVclSamples", nonVclSamples)
                .put("headerOnlyOrNonVclSamples", nonVclSamples)
                .put("audSamples", videoSamples.count { it.nalTypes.contains(H264_AUD_NAL_TYPE) })
                .put("nalTypeCounts", JSONObject().also { counts -> nalCounts.forEach { (type, count) -> counts.put(type.toString(), count) } })
                .put("duplicateTimestamps", duplicateTimestamps)
                .put("timestampRegressions", timestampRegressions)
                .put("firstVideoTimeUs", firstVideoTimeUs ?: JSONObject.NULL)
                .put("lastVideoTimeUs", lastVideoTimeUs ?: JSONObject.NULL)
                .put("videoFormats", formats)
                .put("firstSamples", firstSamples)
        }
    }

    private class RecordingTrackOutput(
        val trackId: Int,
        val trackType: Int
    ) : TrackOutput {
        private val pendingMainData = ByteArrayOutputStream()
        val samples = mutableListOf<VideoSample>()
        var format: Format? = null
            private set

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            if (length <= 0) return 0
            val bytes = ByteArray(length)
            var total = 0
            while (total < length) {
                val read = input.read(bytes, total, length - total)
                if (read == C.RESULT_END_OF_INPUT) {
                    if (total == 0 && allowEndOfInput) return C.RESULT_END_OF_INPUT
                    if (allowEndOfInput) break
                    throw java.io.EOFException("Extractor sample data ended after $total of $length bytes")
                }
                if (read == 0) continue
                total += read
            }
            if (trackType == C.TRACK_TYPE_VIDEO && sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
                pendingMainData.write(bytes, 0, total)
            }
            return total
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = data.data
            val start = data.position
            if (trackType == C.TRACK_TYPE_VIDEO && sampleDataPart == TrackOutput.SAMPLE_DATA_PART_MAIN) {
                pendingMainData.write(bytes, start, length)
            }
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            if (trackType != C.TRACK_TYPE_VIDEO) return
            val bytes = pendingMainData.toByteArray()
            val sampleStart = bytes.size - offset - size
            if (sampleStart < 0 || sampleStart + size > bytes.size) {
                samples += VideoSample(timeUs, size, flags, emptyList(), hasVcl = false, payload = byteArrayOf())
                pendingMainData.reset()
                return
            }
            val sampleBytes = bytes.copyOfRange(sampleStart, sampleStart + size)
            val trailingBytes = bytes.copyOfRange(sampleStart + size, bytes.size)
            pendingMainData.reset()
            if (trailingBytes.isNotEmpty()) pendingMainData.write(trailingBytes)
            val nals = parseH264NalTypes(sampleBytes)
            samples += VideoSample(
                timeUs = timeUs,
                size = size,
                flags = flags,
                nalTypes = nals,
                hasVcl = nals.any(::isVclNal),
                payload = sampleBytes
            )
        }

        fun shouldStop(): Boolean =
            samples.size >= MAX_VIDEO_SAMPLES || (samples.lastOrNull { it.hasVcl }?.timeUs ?: 0L) >= MAX_VIDEO_TIME_US
    }

    private data class VideoSample(
        val timeUs: Long,
        val size: Int,
        val flags: Int,
        val nalTypes: List<Int>,
        val hasVcl: Boolean,
        val payload: ByteArray
    )

    private companion object {
        const val INPUT_ENV = "STREAMVAULT_TS_EXTRACTOR_INPUT"
        const val REPORT_ENV = "STREAMVAULT_TS_EXTRACTOR_REPORT"
        const val MAX_VIDEO_TIME_US = 15_000_000L
        const val MAX_VIDEO_SAMPLES = 2_000
        const val FIRST_SAMPLES_TO_REPORT = 40
        const val H264_AUD_NAL_TYPE = 9

        fun isVclNal(type: Int): Boolean = type in 1..5 || type in 19..21

        fun parseH264NalTypes(sample: ByteArray): List<Int> {
            val result = mutableListOf<Int>()
            var index = 0
            while (index + 3 < sample.size) {
                val prefixLength = when {
                    sample[index] == 0.toByte() && sample[index + 1] == 0.toByte() &&
                        sample[index + 2] == 1.toByte() -> 3
                    index + 4 < sample.size && sample[index] == 0.toByte() &&
                        sample[index + 1] == 0.toByte() && sample[index + 2] == 0.toByte() &&
                        sample[index + 3] == 1.toByte() -> 4
                    else -> 0
                }
                if (prefixLength == 0) {
                    index++
                    continue
                }
                val headerIndex = index + prefixLength
                if (headerIndex < sample.size) result += sample[headerIndex].toInt() and 0x1f
                index = headerIndex + 1
            }
            return result
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
