package com.streamvault.data.remote.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put

@OptIn(ExperimentalSerializationApi::class)
object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int = decodePrimitive(decoder)?.toIntOrNull() ?: 0

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientNullableIntSerializer : KSerializer<Int?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int? = decodePrimitive(decoder)?.toIntOrNull()

    override fun serialize(encoder: Encoder, value: Int?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeInt(value)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long = decodePrimitive(decoder)?.toLongOrNull() ?: 0L

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientNullableLongSerializer : KSerializer<Long?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long? = decodePrimitive(decoder)?.toLongOrNull()

    override fun serialize(encoder: Encoder, value: Long?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeLong(value)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientNullableBooleanSerializer : KSerializer<Boolean?> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("LenientNullableBoolean", PrimitiveKind.BOOLEAN)

    override fun deserialize(decoder: Decoder): Boolean? {
        return when (decodePrimitive(decoder)?.trim()?.lowercase()) {
            null, "" -> null
            "1", "true", "yes", "y", "on" -> true
            "0", "false", "no", "n", "off" -> false
            else -> null
        }
    }

    override fun serialize(encoder: Encoder, value: Boolean?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeBoolean(value)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientStringSerializer : KSerializer<String> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String = decodePrimitive(decoder)?.trim().orEmpty()

    override fun serialize(encoder: Encoder, value: String) {
        encoder.encodeString(value)
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientNullableStringSerializer : KSerializer<String?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableString", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String? = decodePrimitive(decoder)?.trim()?.takeIf { it.isNotEmpty() }

    override fun serialize(encoder: Encoder, value: String?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientStringListSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientStringList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<String> = decodeStringList(decoder).orEmpty()

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeString(value.joinToString(","))
    }
}

@OptIn(ExperimentalSerializationApi::class)
object LenientNullableStringListSerializer : KSerializer<List<String>?> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LenientNullableStringList", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): List<String>? = decodeStringList(decoder)

    override fun serialize(encoder: Encoder, value: List<String>?) {
        if (value == null) {
            encoder.encodeNull()
        } else {
            encoder.encodeString(value.joinToString(","))
        }
    }
}

object XtreamSeriesInfoResponseSerializer : KSerializer<XtreamSeriesInfoResponse> {
    override val descriptor: SerialDescriptor = buildClassSerialDescriptor("XtreamSeriesInfoResponse") {
        element<XtreamSeriesItem?>("info", isOptional = true)
        element<Map<String, List<XtreamEpisode>>>("episodes", isOptional = true)
        element<List<XtreamSeason>>("seasons", isOptional = true)
    }

    override fun deserialize(decoder: Decoder): XtreamSeriesInfoResponse {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("XtreamSeriesInfoResponseSerializer only supports JSON")
        val element = jsonDecoder.decodeJsonElement()
        val obj = element as? JsonObject ?: return XtreamSeriesInfoResponse()
        val json = jsonDecoder.json
        val info = obj["info"].decodeSeriesItemOrNull(json)
            ?: obj.takeIf { it.looksLikeSeriesInfoObject() }?.decodeSeriesItemOrNull(json)

        return XtreamSeriesInfoResponse(
            info = info,
            episodes = obj["episodes"].decodeEpisodeMap(json),
            seasons = obj["seasons"].decodeSeasonList(json)
        )
    }

    override fun serialize(encoder: Encoder, value: XtreamSeriesInfoResponse) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("XtreamSeriesInfoResponseSerializer only supports JSON")
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                value.info?.let { put("info", jsonEncoder.json.encodeToJsonElement(XtreamSeriesItem.serializer(), it)) }
                if (value.episodes.isNotEmpty()) {
                    put(
                        "episodes",
                        jsonEncoder.json.encodeToJsonElement(
                            MapSerializer(String.serializer(), ListSerializer(XtreamEpisode.serializer())),
                            value.episodes
                        )
                    )
                }
                if (value.seasons.isNotEmpty()) {
                    put(
                        "seasons",
                        jsonEncoder.json.encodeToJsonElement(
                            ListSerializer(XtreamSeason.serializer()),
                            value.seasons
                        )
                    )
                }
            }
        )
    }
}

private fun decodePrimitive(decoder: Decoder): String? {
    return when (decoder) {
        is JsonDecoder -> decoder.decodeJsonElement().primitiveContentOrNull()
        else -> runCatching { decoder.decodeString() }.getOrNull()
    }
}

private fun JsonElement.primitiveContentOrNull(): String? {
    if (this == JsonNull) return null
    val primitive = this as? JsonPrimitive ?: return null
    return if (primitive.isString) primitive.content else primitive.toString()
}

private fun decodeStringList(decoder: Decoder): List<String>? {
    return when (decoder) {
        is JsonDecoder -> decoder.decodeJsonElement().stringListOrNull()
        else -> runCatching { decoder.decodeString() }.getOrNull()?.toLenientStringList()
    }
}

private fun JsonElement.stringListOrNull(): List<String>? {
    return when (this) {
        JsonNull -> null
        is JsonPrimitive -> this.primitiveContentOrNull()?.toLenientStringList()
        is JsonArray -> this.flatMap { element -> element.stringListOrNull().orEmpty() }.normalizeStringList()
        is JsonObject -> this.values.flatMap { element -> element.stringListOrNull().orEmpty() }.normalizeStringList()
        else -> null
    }
}

private fun String.toLenientStringList(): List<String> {
    val normalized = trim()
    if (normalized.isEmpty() || normalized.equals("null", ignoreCase = true)) {
        return emptyList()
    }
    val unwrapped = normalized.removePrefix("[").removeSuffix("]")
    val splitRegex = Regex("""\s*(?:,|\|)\s*""")
    return unwrapped
        .split(splitRegex)
        .map { token -> token.trim().trim('"', '\'') }
        .filter { token -> token.isNotEmpty() && !token.equals("null", ignoreCase = true) }
        .ifEmpty { listOf(normalized.trim('"', '\'')) }
}

private fun List<String>.normalizeStringList(): List<String> =
    map { it.trim().trim('"', '\'') }
        .filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

private fun JsonElement?.decodeSeriesItemOrNull(json: kotlinx.serialization.json.Json): XtreamSeriesItem? {
    val element = this ?: return null
    return runCatching { json.decodeFromJsonElement<XtreamSeriesItem>(element) }
        .getOrNull()
        ?.takeIf { item ->
            item.seriesId > 0L ||
                item.name.isNotBlank() ||
                !item.cover.isNullOrBlank() ||
                !item.coverBig.isNullOrBlank() ||
                !item.movieImage.isNullOrBlank() ||
                !item.plot.isNullOrBlank() ||
                !item.description.isNullOrBlank() ||
                !item.categoryId.isNullOrBlank() ||
                item.backdropPath.orEmpty().isNotEmpty()
        }
}

private fun JsonElement?.decodeEpisodeMap(json: kotlinx.serialization.json.Json): Map<String, List<XtreamEpisode>> {
    val element = this ?: return emptyMap()
    return when (element) {
        JsonNull -> emptyMap()
        is JsonArray -> element.decodeEpisodeList(json)
            .groupBySeasonKey()
        is JsonObject -> {
            if (element.looksLikeEpisodeObject()) {
                element.decodeEpisodeList(json).groupBySeasonKey()
            } else {
                buildMap {
                    element.forEach { (key, value) ->
                        val episodes = value.decodeEpisodeList(json, seasonFallback = key.toIntOrNull())
                        if (episodes.isNotEmpty()) {
                            put(key, episodes)
                        }
                    }
                }.takeIf { it.isNotEmpty() }
                    ?: element.values.flatMap { value -> value.decodeEpisodeList(json) }.groupBySeasonKey()
            }
        }
        else -> emptyMap()
    }
}

private fun JsonElement?.decodeEpisodeList(
    json: kotlinx.serialization.json.Json,
    seasonFallback: Int? = null
): List<XtreamEpisode> {
    val element = this ?: return emptyList()
    return when (element) {
        JsonNull -> emptyList()
        is JsonArray -> element.flatMapIndexed { index, value ->
            when (value) {
                is JsonArray -> value.decodeEpisodeList(json, seasonFallback = index)
                is JsonObject -> value.decodeEpisodeContainer(
                    json = json,
                    seasonFallback = seasonFallback,
                    indexFallback = index
                )
                else -> emptyList()
            }
        }
        is JsonObject -> element.decodeEpisodeContainer(json, seasonFallback, indexFallback = null)
        else -> emptyList()
    }
}

private fun JsonObject.decodeEpisodeContainer(
    json: kotlinx.serialization.json.Json,
    seasonFallback: Int?,
    indexFallback: Int?
): List<XtreamEpisode> = when {
    looksLikeEpisodeObject() -> listOfNotNull(decodeEpisodeOrNull(json, seasonFallback))
    "episodes" in this -> this["episodes"].decodeEpisodeList(json, seasonFallback ?: indexFallback)
    else -> values.flatMap { value ->
        value.decodeEpisodeList(json, seasonFallback = seasonFallback ?: indexFallback)
    }
}

private fun JsonObject.decodeEpisodeOrNull(
    json: kotlinx.serialization.json.Json,
    seasonFallback: Int?
): XtreamEpisode? {
    val infoElement = this["info"]
    val info = infoElement
        ?.takeIf { it !is JsonNull }
        ?.let { element -> runCatching { json.decodeFromJsonElement<XtreamEpisodeInfo>(element) }.getOrNull() }
    val episodeElement = if (infoElement == null) this else JsonObject(this - "info")
    val episode = runCatching { json.decodeFromJsonElement<XtreamEpisode>(episodeElement) }.getOrNull()
        ?: return null
    return episode.copy(
        info = info,
        season = if (episode.season <= 0 && seasonFallback != null && seasonFallback > 0) {
            seasonFallback
        } else {
            episode.season
        }
    )
}

private fun JsonElement?.decodeSeasonList(json: kotlinx.serialization.json.Json): List<XtreamSeason> {
    val element = this ?: return emptyList()
    return when (element) {
        JsonNull -> emptyList()
        is JsonArray -> element.mapNotNull { value -> value.decodeSeasonOrNull(json) }
        is JsonObject -> {
            if (element.looksLikeSeasonObject()) {
                listOfNotNull(element.decodeSeasonOrNull(json))
            } else {
                element.values.mapNotNull { value -> value.decodeSeasonOrNull(json) }
            }
        }
        else -> emptyList()
    }
}

private fun JsonElement.decodeSeasonOrNull(json: kotlinx.serialization.json.Json): XtreamSeason? =
    runCatching { json.decodeFromJsonElement<XtreamSeason>(this) }.getOrNull()

private fun List<XtreamEpisode>.groupBySeasonKey(): Map<String, List<XtreamEpisode>> =
    if (isEmpty()) {
        emptyMap()
    } else {
        groupBy { episode -> episode.season.takeIf { it > 0 }?.toString() ?: "0" }
            .toSortedMap(compareBy(String::toIntOrNull, { it }))
    }

private fun JsonObject.looksLikeSeriesInfoObject(): Boolean =
    containsKey("series_id") ||
        containsKey("name") ||
        containsKey("cover") ||
        containsKey("cover_big") ||
        containsKey("movie_image") ||
        containsKey("plot") ||
        containsKey("description") ||
        containsKey("backdrop_path") ||
        containsKey("tmdb") ||
        containsKey("tmdb_id")

private fun JsonObject.looksLikeEpisodeObject(): Boolean =
    containsKey("episode_num") ||
        containsKey("season") ||
        containsKey("container_extension") ||
        containsKey("title") ||
        containsKey("info")

private fun JsonObject.looksLikeSeasonObject(): Boolean =
    containsKey("season_number") ||
        containsKey("episode_count") ||
        containsKey("air_date") ||
        containsKey("cover")
