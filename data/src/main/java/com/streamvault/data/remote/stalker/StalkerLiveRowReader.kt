package com.streamvault.data.remote.stalker

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.util.Locale

/** Retains mapped scalar fields and command variants, skipping unknown nested metadata. */
internal class StalkerLiveRowFields(
    private val scalars: Map<String, String?>,
    private val arrayCommands: List<Pair<String, String>>,
    private val invalidCommandsShape: Boolean
) {
    fun string(key: String): String? = scalars[key]
    fun boolean(key: String): Boolean? = when (string(key)?.lowercase(Locale.ROOT)) {
        "1", "true", "yes" -> true
        "0", "false", "no" -> false
        else -> null
    }

    fun alternateCommands(): List<Pair<String, String>> {
        check(!invalidCommandsShape) { "Portal command variants must be an array" }
        return (listOf("cmd_1", "cmd_2", "cmd_3", "mc_cmd").mapNotNull { key ->
            string(key)?.let { key to it }
        } + arrayCommands).distinctBy { it.second.trim() }
    }
}

internal object StalkerLiveRowReader {
    private val scalarKeys = setOf(
        "id", "ch_id", "video_id", "series_id", "name", "title", "tv_genre_id", "category_id",
        "genre_id", "category_name", "number", "logo", "screenshot_uri", "cover", "xmltv_id", "epg_id",
        "cmd", "cmd_1", "cmd_2", "cmd_3", "mc_cmd", "use_http_tmp_link", "nginx_secure_link",
        "flussonic_tmp_link", "wowza_tmp_link", "use_load_balancing", "allow_local_timeshift",
        "allow_local_pvr", "allow_remote_pvr", "archive", "description", "plot", "actors", "director",
        "genres_str", "genre", "year", "released", "added", "rating_imdb", "rating", "tmdb_id",
        "trailer_url", "backdrop_path", "container_extension", "censored", "is_series"
    )

    fun read(reader: JsonReader): StalkerLiveRowFields {
        val scalars = HashMap<String, String?>()
        var commands: List<Pair<String, String>> = emptyList()
        var invalidCommandsShape = false
        reader.beginObject()
        while (reader.hasNext()) {
            when (val key = reader.nextName()) {
                "cmds" -> {
                    invalidCommandsShape = reader.peek() != JsonToken.BEGIN_ARRAY
                    commands = if (invalidCommandsShape) { reader.skipValue(); emptyList() } else readCommands(reader)
                }
                in scalarKeys -> scalars[key] = readScalar(reader)
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        return StalkerLiveRowFields(scalars, commands, invalidCommandsShape)
    }

    private fun readCommands(reader: JsonReader): List<Pair<String, String>> {
        val commands = ArrayList<Pair<String, String>>()
        reader.beginArray()
        var index = 0
        while (reader.hasNext()) {
            if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                var url: String? = null
                var cmd: String? = null
                reader.beginObject()
                while (reader.hasNext()) {
                    when (reader.nextName()) {
                        "url" -> url = readScalar(reader)
                        "cmd" -> cmd = readScalar(reader)
                        else -> reader.skipValue()
                    }
                }
                reader.endObject()
                (url ?: cmd)?.let { commands += "cmds[$index]" to it }
            } else reader.skipValue()
            index++
        }
        reader.endArray()
        return commands
    }

    private fun readScalar(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString().trim().takeIf(String::isNotBlank)
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        JsonToken.NULL -> { reader.nextNull(); null }
        else -> { reader.skipValue(); null }
    }
}
