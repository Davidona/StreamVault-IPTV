package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.stream.JsonReader
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Test
import java.io.StringReader
import java.time.ZoneId

class StalkerLiveRowReaderTest {
    private val service = OkHttpStalkerApiService(OkHttpClient(), Json { ignoreUnknownKeys = true })

    private fun decode(payload: String, direct: Boolean) = JsonReader(StringReader(payload)).use { reader ->
        reader.isLenient = true
        service.readLiveItem(reader, ZoneId.of("Asia/Jerusalem"), direct)
    }

    @Test fun directReaderMatchesLegacyForCompleteMappedFields() {
        val payload = """{
            "id":" 001 ","name":" News 世界 ","tv_genre_id":"20","category_name":"Adult",
            "number":"12","logo":"null","screenshot_uri":"/image.png","xmltv_id":"epg.channel",
            "cmd":"ffmpeg http://localhost/ch/1_","cmd_1":"http://cdn.example/one.m3u8",
            "cmd_2":"http://cdn.example/one.m3u8","cmd_3":"http://cdn.example/two.ts",
            "mc_cmd":"http://cdn.example/three.ts","cmds":[null,7,{"url":" ","cmd":"http://cdn.example/four.ts"},{"url":"http://cdn.example/five.ts"}],
            "use_http_tmp_link":"1","nginx_secure_link":true,"flussonic_tmp_link":"yes",
            "wowza_tmp_link":"TRUE","use_load_balancing":1,"allow_local_timeshift":"1",
            "allow_local_pvr":true,"allow_remote_pvr":"yes","archive":"true","censored":"1",
            "description":"plot","actors":"cast","director":"director","genres_str":"genre",
            "year":"2020","added":"2026-10-10 12:00:00","rating_imdb":"7.2","tmdb_id":42,
            "trailer_url":"trailer","backdrop_path":"/backdrop.jpg","container_extension":"ts","is_series":"0",
            "ignored":{"array":[{"nested":[1,2,3]}]}
        }"""
        val legacy = decode(payload, false)
        assertThat(legacy).isNotNull()
        assertThat(decode(payload, true)).isEqualTo(legacy)
    }

    @Test fun directReaderMatchesMixedScalarAliasesAndDefaults() {
        val gson = Gson()
        val values: List<Any?> = listOf(null, "", " null ", " 1 ", "yes", "false", 0, 1, 1.5, true, false, listOf(1), mapOf("ignored" to true))
        val keys = listOf("id", "ch_id", "video_id", "series_id", "name", "title", "tv_genre_id", "category_id", "genre_id", "category_name", "number", "logo", "cover", "screenshot_uri", "xmltv_id", "epg_id", "cmd", "cmd_1", "mc_cmd", "archive", "censored", "allow_remote_pvr", "is_series", "year", "added", "rating", "rating_imdb", "tmdb_id", "container_extension")
        repeat(1000) { row ->
            val fields = keys.mapIndexed { index, key -> key to values[(row * 7 + index * 3) % values.size] }.toMap()
            val payload = gson.toJson(fields)
            assertThat(decode(payload, true)).isEqualTo(decode(payload, false))
        }
    }

    @Test fun duplicateKeysUseLastValueAndAliasesKeepPrecedence() {
        val rows = listOf(
            """{"id":"first","id":null,"ch_id":12,"name":"first","name":" final ","cmd":"http://cdn.example/a.ts"}""",
            """{"id":1,"name":"name","cmds":{},"cmds":[{"url":true,"url":"http://cdn.example/a.ts"}]}""",
            """{"id":1,"name":"name","category_id":1,"tv_genre_id":2,"archive":1,"archive":false}""",
            """{"name":"missing id","cmds":{}}"""
        )
        rows.forEach { assertThat(decode(it, true)).isEqualTo(decode(it, false)) }
    }

    @Test fun malformedCommandShapesAndTruncatedRowsFail() {
        listOf(
            """{"id":1,"name":"name","cmds":null}""",
            """{"id":1,"name":"name","cmds":{}}""",
            """{"id":1,"name":"name","cmds":[{"url":"http://cdn.example/a.ts"}"""
        ).forEach { payload ->
            assertThat(runCatching { decode(payload, false) }.isFailure).isTrue()
            assertThat(runCatching { decode(payload, true) }.isFailure).isTrue()
        }
    }
}
