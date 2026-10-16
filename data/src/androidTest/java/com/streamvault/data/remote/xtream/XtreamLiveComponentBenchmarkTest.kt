package com.streamvault.data.remote.xtream

import android.os.Debug
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.streamvault.data.remote.dto.XtreamLiveStreamRow
import com.streamvault.domain.util.ChannelNormalizer
import java.io.StringReader
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic component comparison. It excludes network, Room and screen readiness. */
@RunWith(AndroidJUnit4::class)
class XtreamLiveComponentBenchmarkTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }
    private val rows = 10_000

    @Test
    fun compareLiveDecoderAndGroupIdentity() {
        assumeTrue("Explicitly enable the component benchmark",
            InstrumentationRegistry.getArguments().getString("xtreamLiveComponentBenchmark") == "true")
        listOf(false, true).forEach { heavy ->
            val unused = if (heavy) ",\"unused_metadata\":{\"plot\":\"${"x".repeat(512)}\",\"nested\":[1,2,3]}" else ""
            val payload = (1..rows).joinToString(",", "[", "]") { id ->
                """{"stream_id":$id,"num":$id,"name":"US: Synthetic $id HD [EN]","category_ids":["${id % 100}"],"tv_archive":1,"tv_archive_duration":3,"container_extension":"m3u8"$unused}"""
            }
            val expected = rows.toLong() * (rows + 1) / 2
            val scenario = if (heavy) "extra-metadata" else "core-fields"
            repeat(2) { decode(payload, true); decode(payload, false) }
            repeat(5) { sample ->
                val order = if (sample % 2 == 0) listOf(true, false) else listOf(false, true)
                order.forEach { direct ->
                    measure(scenario, "parse", if (direct) "direct" else "tree", sample, expected) { decode(payload, direct) }
                }
            }
        }
        val names = (1..rows).map { "US: Synthetic $it HD [EN]" }
        val expected = names.sumOf { ChannelNormalizer.classify(it, 42L).logicalGroupId.hashCode().toLong() }
        repeat(2) { groupIdentity(names, true); groupIdentity(names, false) }
        repeat(5) { sample ->
            val order = if (sample % 2 == 0) listOf(true, false) else listOf(false, true)
            order.forEach { direct ->
                measure("tagged-names", "group-id", if (direct) "identity" else "classification", sample, expected) {
                    groupIdentity(names, direct)
                }
            }
        }
        Log.i(TAG, "event=complete rows=$rows samples=30")
    }

    private fun decode(payload: String, direct: Boolean): Long = JsonReader(StringReader(payload)).use { reader ->
        reader.isLenient = true
        reader.beginArray()
        var fingerprint = 0L
        var count = 0
        while (reader.hasNext()) {
            val row = if (direct) XtreamLiveRowReader.read(reader) else
                json.decodeFromString(XtreamLiveStreamRow.serializer(), JsonParser.parseReader(reader).toString())
            fingerprint += row.streamId
            count++
        }
        reader.endArray()
        assertEquals(rows, count)
        fingerprint
    }

    private fun groupIdentity(names: List<String>, direct: Boolean) = names.sumOf {
        (if (direct) ChannelNormalizer.getLogicalGroupId(it, 42L) else ChannelNormalizer.classify(it, 42L).logicalGroupId)
            .hashCode().toLong()
    }

    private fun measure(scenario: String, component: String, method: String, sample: Int, expected: Long, block: () -> Long) {
        val gcBefore = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
        val cpuStarted = Debug.threadCpuTimeNanos()
        val started = System.nanoTime()
        val fingerprint = block()
        val elapsedUs = (System.nanoTime() - started) / 1_000
        val cpuUs = (Debug.threadCpuTimeNanos() - cpuStarted) / 1_000
        val gcAfter = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull()
        assertEquals(expected, fingerprint)
        val gcDelta = if (gcBefore != null && gcAfter != null) gcAfter - gcBefore else -1
        Log.i(TAG, "scenario=$scenario component=$component method=$method sample=$sample rows=$rows elapsed_us=$elapsedUs cpu_us=$cpuUs gc_delta=$gcDelta")
    }

    private companion object { const val TAG = "XtreamLiveComponentBench" }
}
