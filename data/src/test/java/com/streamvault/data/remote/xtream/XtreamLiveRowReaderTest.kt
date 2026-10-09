package com.streamvault.data.remote.xtream

import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.streamvault.data.remote.dto.XtreamLiveStreamRow
import java.io.StringReader
import kotlin.random.Random
import kotlinx.serialization.json.Json
import org.junit.Test

class XtreamLiveRowReaderTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; explicitNulls = false; coerceInputValues = true }

    @Test
    fun `matches previous decoder for every retained field and lenient scalar type`() {
        val values = listOf("null", "true", "false", "1", "0", "-2", "1.0", "1e2", "9223372036854775807",
            "\"\"", "\" 7 \"", "\" Yes \"", "\"null\"", "\" +7 \"", "[]", "{}", "[1,null,\"2\"]",
            "{\"x\":1,\"y\":\"2|3\"}", "\"Téléfilm \\u05e9\\u05dc\\u05d5\\u05dd &amp; News\\nHD\"")
        fields.forEach { field -> values.forEach { value -> assertEquivalent("{\"$field\":$value}") } }
        assertEquivalent("{}")
    }

    @Test
    fun `matches nested category ids and duplicate keys`() {
        listOf(
            """{"stream_id":1,"stream_id":null,"name":"old","name":null,"num":3,"num":null}""",
            """{"name":"first","name":"last","tv_archive":1,"tv_archive":{}}""",
            """{"category_ids":{"a":["1","2"],"b":{"x":"3|4","y":null},"a":"5"}}""",
            """{"category_ids":[[" 1 ","'2'"],{"a":[3,null,"null"],"b":"[4,5]"},"",true]}""",
            """{"category_ids":"[]","stream_icon":{},"container_extension":[],"name":123}""",
            """{"stream_id":"+42","name":"One","unknown":{"stream_id":9,"values":[{},[],null]},"direct_source":"ignored"}""",
            """{'stream_id':'7','name':'escaped \\ quote','category_ids':'1|2|3'}"""
        ).forEach(::assertEquivalent)
    }

    @Test
    fun `matches deterministic mixed provider rows`() {
        val random = Random(15)
        val values = listOf("null", "true", "0", "1", "\" News HD \"", "\".M3U8\"", "\"1|2\"",
            "{\"key\":[\"2\",null,\"3\"]}", "[]", "[\"4\",\"5\"]")
        repeat(1_000) {
            val body = fields.shuffled(random).take(random.nextInt(1, fields.size))
                .joinToString(",", "{", "}") { "\"$it\":${values.random(random)}" }
            assertEquivalent(body)
        }
    }

    @Test
    fun `strict configuration rejects unknown keys`() {
        val failure = JsonReader(StringReader("""{"stream_id":1,"extra":"ignored only in lenient configuration"}""")).use {
            runCatching { XtreamLiveRowReader.read(it, ignoreUnknownKeys = false) }.exceptionOrNull()
        }
        assertThat(failure).isInstanceOf(XtreamParsingException::class.java)
    }

    @Test
    fun `category list nesting has an explicit bound`() {
        val body = "{\"category_ids\":" + "[".repeat(130) + "1" + "]".repeat(130) + "}"
        val failure = runCatching { read(body) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(XtreamParsingException::class.java)
    }

    private fun assertEquivalent(body: String) {
        // This is the exact previous per-row conversion: Gson tree, JSON text,
        // then the lenient Kotlin DTO serializer. It also fixes duplicate keys.
        val expected = json.decodeFromString(XtreamLiveStreamRow.serializer(), JsonParser.parseString(body).toString())
        assertThat(read(body)).isEqualTo(expected)
    }

    private fun read(body: String): XtreamLiveStreamRow = JsonReader(StringReader(body)).use {
        it.isLenient = true
        XtreamLiveRowReader.read(it).also { _ -> assertThat(it.peek()).isEqualTo(JsonToken.END_DOCUMENT) }
    }

    private val fields = listOf("num", "name", "stream_id", "stream_icon", "epg_channel_id", "category_id",
        "category_name", "category_ids", "tv_archive", "tv_archive_duration", "container_extension", "is_adult")
}
