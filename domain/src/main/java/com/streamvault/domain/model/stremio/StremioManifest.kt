package com.streamvault.domain.model.stremio

data class StremioMediaType private constructor(val typeName: String) {
    companion object {
        val MOVIE = StremioMediaType("movie")
        val SERIES = StremioMediaType("series")
        val CHANNEL = StremioMediaType("channel")
        val TV = StremioMediaType("tv")
        val UNKNOWN = StremioMediaType("unknown")
        val entries = listOf(MOVIE, SERIES, CHANNEL, TV, UNKNOWN)

        fun fromTypeName(value: String?): StremioMediaType {
            val normalized = value?.trim().orEmpty()
            if (normalized.isEmpty() || normalized.length > 128 || normalized.any(Char::isISOControl)) return UNKNOWN
            return entries.firstOrNull { it.typeName.equals(normalized, ignoreCase = true) }
                ?: StremioMediaType(normalized)
        }
    }
}

enum class StremioResourceKind(val resourceName: String) {
    CATALOG("catalog"),
    META("meta"),
    STREAM("stream"),
    SUBTITLES("subtitles"),
    ADDON_CATALOG("addon_catalog"),
    PLAYER("player"),
    LIBRARY("library"),
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
    /** String declarations inherit manifest-level filters; object declarations use their own. */
    val isStringResource: Boolean = false,
    /** `null` means omitted; an empty list is an explicit match-none filter. */
    val types: List<StremioMediaType>? = null,
    /** `null` means omitted; an empty list is an explicit match-none filter. */
    val idPrefixes: List<String>? = null
)

data class StremioCatalogExtraDef(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
    val optionsLimit: Int = 1
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
    val supportsGenre: Boolean get() = extras.any { it.name.equals("genre", ignoreCase = true) }
}

data class StremioManifestBehaviorHints(
    val configurable: Boolean = false,
    val configurationRequired: Boolean = false,
    val epgProvider: Boolean = false
)

data class StremioManifest(
    val id: String,
    val name: String,
    val version: String,
    val description: String? = null,
    val logo: String? = null,
    val background: String? = null,
    /** `null` means omitted; an empty list is an explicit match-none filter. */
    val types: List<StremioMediaType>? = null,
    val resources: List<StremioManifestResource> = emptyList(),
    val catalogs: List<StremioCatalogRef> = emptyList(),
    /** `null` means omitted; an empty list is an explicit match-none filter. */
    val idPrefixes: List<String>? = null,
    val behaviorHints: StremioManifestBehaviorHints = StremioManifestBehaviorHints(),
    val addonCatalogs: List<StremioCatalogRef> = emptyList()
) {
    fun supportsResource(
        kind: StremioResourceKind,
        type: StremioMediaType,
        exactId: String? = null
    ): Boolean =
        resources.any { resource ->
            if (resource.kind != kind) {
                false
            } else {
                val effectiveTypes = resource.types ?: types
                val typeMatches = effectiveTypes == null || type in effectiveTypes
                val effectivePrefixes = if (resource.isStringResource) idPrefixes else resource.idPrefixes
                val prefixMatches = when {
                    effectivePrefixes == null -> true
                    effectivePrefixes.isEmpty() -> false
                    exactId == null -> true
                    else -> effectivePrefixes.any(exactId::startsWith)
                }
                typeMatches && prefixMatches
            }
        }
}
