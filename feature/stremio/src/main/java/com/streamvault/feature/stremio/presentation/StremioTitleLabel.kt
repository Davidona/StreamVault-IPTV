package com.streamvault.feature.stremio.presentation

private val releaseSeparators = Regex("[._]+")
private val releaseWhitespace = Regex("\\s+")
private val releaseMarker = Regex(
    "\\b(?:2160p|1080p|720p|480p|BRRip|BDRip|DVDRip|WEBRip|HDRip|BluRay|XviD|x26[45]|H ?26[45])\\b|WEB[ -]?DL",
    RegexOption.IGNORE_CASE
)
private val releaseYear = Regex("\\b(?:19|20)\\d{2}\\b")

internal data class StremioTitleLabel(val title: String, val year: String? = null)

/** Presentation only: keep provider identifiers, metadata and playback titles intact. */
internal fun stremioTitleLabel(name: String): StremioTitleLabel {
    val normalized = name.replace(releaseSeparators, " ").replace(releaseWhitespace, " ").trim()
    val marker = releaseMarker.find(normalized) ?: return StremioTitleLabel(name)
    val year = releaseYear.findAll(normalized).firstOrNull { it.range.first > 0 && it.range.first < marker.range.first }
    val end = year?.range?.first ?: marker.range.first
    val title = normalized.take(end).trimEnd(' ', '[', '(', '-', ',')
    return if (title.isBlank()) StremioTitleLabel(name) else StremioTitleLabel(title, year?.value)
}
