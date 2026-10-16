package com.streamvault.data.remote.xtream

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.streamvault.data.remote.dto.XtreamLiveStreamRow

/** Reads the sync projection without a per-row JSON tree or a second JSON parser. */
internal object XtreamLiveRowReader {
    private val listSeparator = Regex("""\s*(?:,|\|)\s*""")
    private const val MAX_LIST_DEPTH = 128

    fun read(reader: JsonReader, ignoreUnknownKeys: Boolean = true): XtreamLiveStreamRow {
        var num = 0
        var name = ""
        var streamId = 0L
        var streamIcon: String? = null
        var epgChannelId: String? = null
        var categoryId: String? = null
        var categoryName: String? = null
        var categoryIds: List<String>? = null
        var tvArchive = 0
        var tvArchiveDuration: Int? = null
        var containerExtension: String? = null
        var isAdult: Boolean? = null
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            // Match the previous JSON-tree projection's last-key-wins behavior,
            // including a duplicate non-nullable field replaced with null.
            when (key) {
                "num" -> num = scalar(reader)?.toIntOrNull() ?: 0
                "name" -> name = scalar(reader)?.trim().orEmpty()
                "stream_id" -> streamId = scalar(reader)?.toLongOrNull() ?: 0L
                "stream_icon" -> streamIcon = nullableText(reader)
                "epg_channel_id" -> epgChannelId = nullableText(reader)
                "category_id" -> categoryId = nullableText(reader)
                "category_name" -> categoryName = nullableText(reader)
                "category_ids" -> categoryIds = stringList(reader, 0)
                "tv_archive" -> tvArchive = scalar(reader)?.toIntOrNull() ?: 0
                "tv_archive_duration" -> tvArchiveDuration = scalar(reader)?.toIntOrNull()
                "container_extension" -> containerExtension = nullableText(reader)
                "is_adult" -> isAdult = when (scalar(reader)?.trim()?.lowercase()) {
                    "1", "true", "yes", "y", "on" -> true
                    "0", "false", "no", "n", "off" -> false
                    else -> null
                }
                else -> {
                    if (!ignoreUnknownKeys) throw XtreamParsingException("Unexpected field in live row")
                    reader.skipValue()
                }
            }
        }
        reader.endObject()
        return XtreamLiveStreamRow(num, name, streamId, streamIcon, epgChannelId, categoryId,
            categoryName, categoryIds, tvArchive, tvArchiveDuration, containerExtension, isAdult)
    }

    private fun scalar(reader: JsonReader): String? = when (reader.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> reader.nextString()
        JsonToken.BOOLEAN -> reader.nextBoolean().toString()
        JsonToken.NULL -> { reader.nextNull(); null }
        else -> { reader.skipValue(); null }
    }

    private fun nullableText(reader: JsonReader) = scalar(reader)?.trim()?.takeIf { it.isNotEmpty() }

    private fun stringList(reader: JsonReader, depth: Int): List<String>? {
        if (depth > MAX_LIST_DEPTH) throw XtreamParsingException("Live category list nesting limit exceeded")
        return when (reader.peek()) {
            JsonToken.NULL -> { reader.nextNull(); null }
            JsonToken.BEGIN_ARRAY -> {
                val values = ArrayList<String>()
                reader.beginArray()
                while (reader.hasNext()) values.addAll(stringList(reader, depth + 1).orEmpty())
                reader.endArray()
                normalize(values)
            }
            JsonToken.BEGIN_OBJECT -> {
                val values = LinkedHashMap<String, List<String>>()
                reader.beginObject()
                while (reader.hasNext()) {
                    val key = reader.nextName()
                    values[key] = stringList(reader, depth + 1).orEmpty()
                }
                reader.endObject()
                normalize(values.values.flatten())
            }
            else -> {
                val normalized = scalar(reader)?.trim().orEmpty()
                if (normalized.isEmpty() || normalized.equals("null", ignoreCase = true)) emptyList()
                else normalized.removePrefix("[").removeSuffix("]").split(listSeparator)
                    .map { it.trim().trim('"', '\'') }
                    .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                    .ifEmpty { listOf(normalized.trim('"', '\'')) }
            }
        }
    }

    private fun normalize(values: List<String>) = values.map { it.trim().trim('"', '\'') }
        .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
}
