package com.streamvault.domain.model

data class ExternalSubtitle(
    val id: String,
    val language: String,
    val displayLanguage: String,
    val releaseName: String,
    val source: ExternalSubtitleSource,
    val downloadId: String = id,
    val downloadCount: Long = 0L,
)

data class ExternalSubtitleLanguage(
    val code: String,
    val name: String,
)

enum class ExternalSubtitleSource {
    OPENSUBTITLES
}
