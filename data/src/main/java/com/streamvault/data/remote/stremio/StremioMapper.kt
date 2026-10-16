package com.streamvault.data.remote.stremio

import com.streamvault.domain.model.stremio.StremioCatalogExtraDef
import com.streamvault.domain.model.stremio.StremioCatalogPage
import com.streamvault.domain.model.stremio.StremioCatalogRef
import com.streamvault.domain.model.stremio.StremioManifest
import com.streamvault.domain.model.stremio.StremioManifestBehaviorHints
import com.streamvault.domain.model.stremio.StremioManifestResource
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaDetail
import com.streamvault.domain.model.stremio.StremioMetaPreview
import com.streamvault.domain.model.stremio.StremioProxyHeaders
import com.streamvault.domain.model.stremio.StremioResourceKind
import com.streamvault.domain.model.stremio.StremioStream
import com.streamvault.domain.model.stremio.StremioStreamBehaviorHints
import com.streamvault.domain.model.stremio.StremioSubtitle
import com.streamvault.domain.model.stremio.StremioVideo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

internal fun StremioManifestDto.toDomain(): StremioManifest? {
    val manifestId = id?.trim().orEmpty()
    if (manifestId.isEmpty()) return null
    return StremioManifest(
        id = manifestId,
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: manifestId,
        version = version?.trim().orEmpty(),
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        logo = logo?.trim()?.takeIf { it.isNotEmpty() },
        background = background?.trim()?.takeIf { it.isNotEmpty() },
        types = types?.map(StremioMediaType::fromTypeName)
            ?.filter { it != StremioMediaType.UNKNOWN }
            ?.distinct(),
        resources = parseResources(resources),
        catalogs = parseCatalogs(catalogs),
        idPrefixes = idPrefixes?.mapNotNull { it.trim().takeIf(String::isNotEmpty) }?.distinct(),
        behaviorHints = behaviorHints?.toDomain() ?: StremioManifestBehaviorHints()
    )
}

internal fun StremioManifestBehaviorHintsDto.toDomain(): StremioManifestBehaviorHints =
    StremioManifestBehaviorHints(
        configurable = configurable ?: false,
        configurationRequired = configurationRequired ?: false
    )

internal fun StremioCatalogResponseDto.toDomain(): StremioCatalogPage =
    StremioCatalogPage(
        metas = metas.orEmpty().mapNotNull { it.toDomain() },
        hasMore = hasMore,
        rawItemCount = metas.orEmpty().size
    )

internal fun StremioMetaPreviewDto.toDomain(): StremioMetaPreview? {
    val previewId = id?.trim().orEmpty()
    if (previewId.isEmpty()) return null
    return StremioMetaPreview(
        id = previewId,
        type = StremioMediaType.fromTypeName(type),
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: previewId,
        poster = poster?.trim()?.takeIf { it.isNotEmpty() },
        background = background?.trim()?.takeIf { it.isNotEmpty() },
        logo = logo?.trim()?.takeIf { it.isNotEmpty() },
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        releaseInfo = releaseInfo?.trim()?.takeIf { it.isNotEmpty() },
        imdbRating = imdbRating?.trim()?.takeIf { it.isNotEmpty() },
        genres = genres.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
        runtime = runtime?.trim()?.takeIf { it.isNotEmpty() }
    )
}

internal fun StremioMetaDto.toDomain(): StremioMetaDetail? {
    val metaId = id?.trim().orEmpty()
    if (metaId.isEmpty()) return null
    return StremioMetaDetail(
        id = metaId,
        type = StremioMediaType.fromTypeName(type),
        name = name?.trim()?.takeIf { it.isNotEmpty() } ?: metaId,
        poster = poster?.trim()?.takeIf { it.isNotEmpty() },
        background = background?.trim()?.takeIf { it.isNotEmpty() },
        logo = logo?.trim()?.takeIf { it.isNotEmpty() },
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        releaseInfo = releaseInfo?.trim()?.takeIf { it.isNotEmpty() },
        imdbRating = imdbRating?.trim()?.takeIf { it.isNotEmpty() },
        genres = genres.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
        runtime = runtime?.trim()?.takeIf { it.isNotEmpty() },
        cast = cast.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
        director = director.asStringOrStringList(),
        videos = videos.orEmpty().mapNotNull { it.toDomain() }
    )
}

internal fun StremioVideoDto.toDomain(): StremioVideo? {
    val videoId = id?.trim().orEmpty()
    if (videoId.isEmpty()) return null
    return StremioVideo(
        id = videoId,
        title = name?.trim()?.takeIf { it.isNotEmpty() } ?: title?.trim()?.takeIf { it.isNotEmpty() },
        season = season,
        episode = episode ?: number,
        released = released?.trim()?.takeIf { it.isNotEmpty() },
        thumbnail = thumbnail?.trim()?.takeIf { it.isNotEmpty() },
        overview = overview?.trim()?.takeIf { it.isNotEmpty() }
    )
}

internal fun StremioStreamDto.toDomain(): StremioStream? {
    val stream = StremioStream(
        name = name?.trim()?.takeIf { it.isNotEmpty() },
        title = title?.trim()?.takeIf { it.isNotEmpty() },
        description = description?.trim()?.takeIf { it.isNotEmpty() },
        url = url?.trim()?.takeIf { it.isNotEmpty() },
        ytId = ytId?.trim()?.takeIf { it.isNotEmpty() },
        infoHash = infoHash?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(),
        fileIdx = fileIdx?.takeIf { it >= 0 },
        externalUrl = externalUrl?.trim()?.takeIf { it.isNotEmpty() },
        sources = sources.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
        subtitles = subtitles.orEmpty().mapNotNull { it.toDomain() },
        behaviorHints = behaviorHints?.toDomain()
    )
    // Keep recognizable-but-unsupported protocol rows visible to the picker as disabled entries.
    return stream
}

internal fun StremioStreamBehaviorHintsDto.toDomain(): StremioStreamBehaviorHints =
    StremioStreamBehaviorHints(
        notWebReady = notWebReady ?: false,
        bingeGroup = bingeGroup?.trim()?.takeIf { it.isNotEmpty() },
        filename = filename?.trim()?.takeIf { it.isNotEmpty() },
        videoSize = videoSize?.takeIf { it > 0 },
        videoHash = videoHash?.trim()?.takeIf { it.isNotEmpty() },
        proxyHeaders = proxyHeaders?.let { headers ->
            StremioProxyHeaders(
                request = headers.request.orEmpty().filterValues { it.isNotBlank() },
                response = headers.response.orEmpty().filterValues { it.isNotBlank() }
            ).takeIf { it.request.isNotEmpty() || it.response.isNotEmpty() }
        }
    )

internal fun StremioSubtitleDto.toDomain(): StremioSubtitle? {
    val subtitleUrl = url?.trim().orEmpty()
    if (subtitleUrl.isEmpty()) return null
    return StremioSubtitle(
        url = subtitleUrl,
        lang = lang?.trim().orEmpty(),
        id = id?.trim()?.takeIf { it.isNotEmpty() }
    )
}

private fun parseResources(resources: List<JsonElement>?): List<StremioManifestResource> =
    resources.orEmpty().mapNotNull { element ->
        when (element) {
            is JsonPrimitive -> element.contentOrNull
                ?.let(StremioResourceKind::fromResourceName)
                ?.takeIf { it != StremioResourceKind.UNKNOWN }
                ?.let { StremioManifestResource(kind = it, isStringResource = true) }
            is JsonObject -> {
                val kind = StremioResourceKind.fromResourceName(element["name"].asString())
                if (kind == StremioResourceKind.UNKNOWN) {
                    null
                } else {
                    StremioManifestResource(
                        kind = kind,
                        types = element["types"].asStringListOrNull()
                            ?.map(StremioMediaType::fromTypeName)
                            ?.filter { it != StremioMediaType.UNKNOWN }
                            ?.distinct(),
                        idPrefixes = element["idPrefixes"].asStringListOrNull()?.distinct()
                    )
                }
            }
            else -> null
        }
    }

private fun parseCatalogs(catalogs: List<StremioCatalogDto>?): List<StremioCatalogRef> =
    catalogs.orEmpty().mapNotNull { dto ->
        val catalogId = dto.id?.trim().orEmpty()
        val type = StremioMediaType.fromTypeName(dto.type)
        if (catalogId.isEmpty() || type == StremioMediaType.UNKNOWN) {
            null
        } else {
            StremioCatalogRef(
                type = type,
                id = catalogId,
                name = dto.name?.trim()?.takeIf { it.isNotEmpty() },
                genres = dto.genres.orEmpty().mapNotNull { it.trim().takeIf(String::isNotEmpty) },
                extras = parseCatalogExtras(dto.extra)
            )
        }
    }

private fun parseCatalogExtras(extra: JsonElement?): List<StremioCatalogExtraDef> =
    when (extra) {
        is JsonArray -> extra.mapNotNull { entry ->
            when (entry) {
                is JsonPrimitive -> entry.contentOrNull
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { StremioCatalogExtraDef(name = it) }
                is JsonObject -> entry["name"].asString()?.let { name ->
                    StremioCatalogExtraDef(
                        name = name,
                        isRequired = entry["isRequired"].asBoolean(),
                        options = entry["options"].asOptionList()
                    )
                }
                else -> null
            }
        }
        else -> emptyList()
    }

private fun JsonElement?.asString(): String? =
    (this as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

private fun JsonElement?.asStringListOrNull(): List<String>? =
    (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }

private fun JsonElement?.asBoolean(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false

private fun JsonElement?.asOptionList(): List<String> =
    (this as? JsonArray).orEmpty().mapNotNull { entry ->
        when (entry) {
            is JsonPrimitive -> entry.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            is JsonObject -> entry["value"].asString() ?: entry["label"].asString()
            else -> null
        }
    }

private fun JsonElement?.asStringOrStringList(): List<String> =
    when (this) {
        is JsonPrimitive -> listOfNotNull(contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
        is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        else -> emptyList()
    }
