package com.streamvault.domain.repository

import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ExternalSubtitle
import com.streamvault.domain.model.ExternalSubtitleLanguage
import com.streamvault.domain.model.Result
import java.io.File

interface ExternalSubtitleRepository {

    suspend fun search(
        contentType: ContentType,
        title: String,
        year: Int?,
        tmdbId: Long?,
        parentTmdbId: Long?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        language: String?
    ): Result<List<ExternalSubtitle>>

    suspend fun languages(): Result<List<ExternalSubtitleLanguage>>

    suspend fun downloadToCache(subtitle: ExternalSubtitle): Result<File>

    suspend fun cachedSubtitleFile(downloadId: String): File? = null

    suspend fun deleteCachedSubtitle(downloadId: String) = Unit
}
