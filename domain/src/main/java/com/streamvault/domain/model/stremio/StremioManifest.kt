package com.streamvault.domain.model.stremio

enum class StremioMediaType(val typeName: String) {
    MOVIE("movie"),
    SERIES("series"),
    CHANNEL("channel"),
    TV("tv"),
    UNKNOWN("unknown");

    companion object {
        fun fromTypeName(value: String?): StremioMediaType {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { it.typeName.equals(normalized, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

enum class StremioResourceKind(val resourceName: String) {
    CATALOG("catalog"),
    META("meta"),
    STREAM("stream"),
    SUBTITLES("subtitles"),
    UNKNOWN("unknown");

    companion object {
        fun fromResourceName(value: String?): StremioResourceKind {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { it.resourceName.equals(normalized, ignoreCase = true) } ?: UNKNOWN
        }
    }
}

data class StremioManifestResource(
    val kind: StremioResourceKind,
    val types: List<StremioMediaType> = emptyList(),
    val idPrefixes: List<String> = emptyList()
)

data class StremioCatalogExtraDef(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList()
)

data class StremioCatalogRef(
    val type: StremioMediaType,
    val id: String,
    val name: String? = null,
    val genres: List<String> = emptyList(),
    val extras: List<StremioCatalogExtraDef> = emptyList()
) {
    val settingsKey: String get() = "${type.typeName}:$id"
    val supportsSearch: Boolean get() = extras.any { it.name.equals("search", ignoreCase = true) }
    val supportsSkip: Boolean get() = extras.any { it.name.equals("skip", ignoreCase = true) }
    val supportsGenre: Boolean
        get() = genres.isNotEmpty() || extras.any { it.name.equals("genre", ignoreCase = true) }
}

data class StremioManifestBehaviorHints(
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false
)

data class StremioManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val logo: String? = null,
    val background: String? = null,
    val types: List<StremioMediaType> = emptyList(),
    val resources: List<StremioManifestResource> = emptyList(),
    val catalogs: List<StremioCatalogRef> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
    val behaviorHints: StremioManifestBehaviorHints = StremioManifestBehaviorHints()
) {
    fun supportsResource(kind: StremioResourceKind, type: StremioMediaType): Boolean =
        resources.any { resource ->
            resource.kind == kind && (resource.types.isEmpty() || type in resource.types)
        }
}
