package com.streamvault.data.remote.stremio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class StremioManifestDto(
    val id: String? = null,
    val version: String? = null,
    val name: String? = null,
    val description: String? = null,
    val logo: String? = null,
    val background: String? = null,
    val types: List<String>? = null,
    val resources: List<JsonElement>? = null,
    val catalogs: List<StremioCatalogDto>? = null,
    val idPrefixes: List<String>? = null,
    val behaviorHints: StremioManifestBehaviorHintsDto? = null
)

@Serializable
internal data class StremioManifestBehaviorHintsDto(
    val configurable: Boolean? = null,
    val configurationRequired: Boolean? = null
)

@Serializable
internal data class StremioCatalogDto(
    val type: String? = null,
    val id: String? = null,
    val name: String? = null,
    val genres: List<String>? = null,
    val extra: JsonElement? = null
)

@Serializable
internal data class StremioCatalogResponseDto(
    val metas: List<StremioMetaPreviewDto>? = null,
    val hasMore: Boolean? = null
)

@Serializable
internal data class StremioMetaPreviewDto(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String>? = null,
    val runtime: String? = null
)

@Serializable
internal data class StremioMetaResponseDto(
    val meta: StremioMetaDto? = null
)

@Serializable
internal data class StremioMetaDto(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val genres: List<String>? = null,
    val runtime: String? = null,
    val cast: List<String>? = null,
    val director: JsonElement? = null,
    val videos: List<StremioVideoDto>? = null
)

@Serializable
internal data class StremioVideoDto(
    val id: String? = null,
    val name: String? = null,
    val title: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val number: Int? = null,
    val released: String? = null,
    val thumbnail: String? = null,
    val overview: String? = null
)

@Serializable
internal data class StremioStreamResponseDto(
    val streams: List<StremioStreamDto>? = null
)

@Serializable
internal data class StremioStreamDto(
    val name: String? = null,
    val title: String? = null,
    val description: String? = null,
    val url: String? = null,
    val ytId: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val externalUrl: String? = null,
    val sources: List<String>? = null,
    val behaviorHints: StremioStreamBehaviorHintsDto? = null
)

@Serializable
internal data class StremioStreamBehaviorHintsDto(
    val notWebReady: Boolean? = null,
    val bingeGroup: String? = null,
    val filename: String? = null,
    val videoSize: Long? = null,
    val videoHash: String? = null,
    val proxyHeaders: StremioProxyHeadersDto? = null
)

@Serializable
internal data class StremioProxyHeadersDto(
    val request: Map<String, String>? = null,
    val response: Map<String, String>? = null
)

@Serializable
internal data class StremioSubtitleResponseDto(
    val subtitles: List<StremioSubtitleDto>? = null
)

@Serializable
internal data class StremioSubtitleDto(
    val id: String? = null,
    val url: String? = null,
    val lang: String? = null
)
