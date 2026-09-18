package com.streamvault.data.repository

import android.content.Context
import com.streamvault.data.remote.subtitle.OpenSubtitlesApi
import com.streamvault.data.remote.subtitle.OpenSubtitlesApiException
import com.streamvault.data.remote.subtitle.OpenSubtitlesCredentials
import com.streamvault.data.remote.subtitle.OpenSubtitlesSession
import com.streamvault.data.remote.subtitle.OpenSubtitlesSessionStore
import com.streamvault.data.remote.subtitle.OpenSubtitlesSubtitleDto
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ExternalSubtitle
import com.streamvault.domain.model.ExternalSubtitleSource
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ExternalSubtitleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class OpenSubtitlesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: OpenSubtitlesApi,
    private val sessionStore: OpenSubtitlesSessionStore,
) : ExternalSubtitleRepository {

    override suspend fun search(
        contentType: ContentType,
        title: String,
        year: Int?,
        tmdbId: Long?,
        parentTmdbId: Long?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        language: String,
    ): Result<List<ExternalSubtitle>> = try {
        val session = obtainSession()
            ?: return Result.error(NOT_SIGNED_IN_MESSAGE)
        val query = buildSearchQuery(contentType, title, year, tmdbId, parentTmdbId, seasonNumber, episodeNumber, language)
        var result = api.search(query, session.token, session.baseUrl)
        if (result.isUnauthorized()) {
            val refreshed = refreshSession()
            if (refreshed != null) {
                result = api.search(query, refreshed.token, refreshed.baseUrl)
            }
        }
        when (result) {
            is Result.Success -> Result.success(result.data.map(::mapSubtitle))
            is Result.Error -> Result.error(result.message, result.exception)
            is Result.Loading -> Result.Loading
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.error("OpenSubtitles search failed: ${e.message}", e)
    }

    override suspend fun downloadToCache(subtitle: ExternalSubtitle): Result<File> = try {
        val session = obtainSession()
            ?: return Result.error(NOT_SIGNED_IN_MESSAGE)
        val fileId = subtitle.downloadId.toLongOrNull()
            ?: return Result.error("OpenSubtitles subtitle has no file id")
        var link = api.downloadLink(fileId, session.token, session.baseUrl)
        if (link.isUnauthorized()) {
            val refreshed = refreshSession()
            if (refreshed != null) {
                link = api.downloadLink(fileId, refreshed.token, refreshed.baseUrl)
            }
        }
        when (link) {
            is Result.Success -> {
                val destination = subtitleCacheFile(subtitle, link.data.fileName)
                api.downloadSubtitleFile(link.data, destination)
            }
            is Result.Error -> Result.error(link.message, link.exception)
            is Result.Loading -> Result.Loading
        }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.error("OpenSubtitles download failed: ${e.message}", e)
    }

    private suspend fun obtainSession(): OpenSubtitlesSession? {
        sessionStore.loadSession()?.let { return it }
        val credentials = sessionStore.loadCredentials() ?: return null
        val result = api.login(credentials.username, credentials.password)
        val session = result.getOrNull() ?: return null
        sessionStore.saveSession(session)
        return session
    }

    private suspend fun refreshSession(): OpenSubtitlesSession? {
        val credentials = sessionStore.loadCredentials() ?: return null
        val result = api.login(credentials.username, credentials.password)
        val session = result.getOrNull() ?: return null
        sessionStore.saveSession(session)
        return session
    }

    private fun mapSubtitle(dto: OpenSubtitlesSubtitleDto): ExternalSubtitle {
        val attributes = dto.attributes
        val file = attributes?.files?.firstOrNull()
        val fileId = file?.fileId?.toString()
            ?: attributes?.subtitleId
            ?: dto.id
            ?: ""
        val language = attributes?.language.orEmpty()
        return ExternalSubtitle(
            id = dto.id.orEmpty(),
            language = language,
            displayLanguage = attributes?.languageName?.takeIf { it.isNotBlank() } ?: language,
            releaseName = attributes?.release.orEmpty(),
            source = ExternalSubtitleSource.OPENSUBTITLES,
            downloadId = fileId,
        )
    }

    private fun subtitleCacheFile(subtitle: ExternalSubtitle, fileName: String): File {
        val directory = File(context.cacheDir, "subtitles").apply { mkdirs() }
        val extension = fileName.substringAfterLast('.', "srt").takeIf { it.isNotBlank() } ?: "srt"
        val baseName = subtitle.downloadId.ifBlank { subtitle.id }.ifBlank { "subtitle" }
        return File(directory, "$baseName.$extension")
    }

    private fun buildSearchQuery(
        contentType: ContentType,
        title: String,
        year: Int?,
        tmdbId: Long?,
        parentTmdbId: Long?,
        seasonNumber: Int?,
        episodeNumber: Int?,
        language: String,
    ): OpenSubtitlesApi.SearchQuery {
        val languageCode = language.trim().lowercase().takeIf { it.isNotBlank() }
        return when (contentType) {
            ContentType.MOVIE -> OpenSubtitlesApi.SearchQuery(
                query = buildTitleQuery(title, year),
                tmdbId = tmdbId?.takeIf { it > 0 },
                languages = languageCode,
                orderBy = "download_count",
            )
            ContentType.SERIES -> OpenSubtitlesApi.SearchQuery(
                query = title.trim().takeIf { it.isNotBlank() },
                tmdbId = tmdbId?.takeIf { it > 0 },
                languages = languageCode,
                orderBy = "download_count",
            )
            ContentType.SERIES_EPISODE -> OpenSubtitlesApi.SearchQuery(
                query = buildEpisodeQuery(title, seasonNumber, episodeNumber),
                parentTmdbId = parentTmdbId?.takeIf { it > 0 },
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                languages = languageCode,
                orderBy = "download_count",
            )
            else -> OpenSubtitlesApi.SearchQuery(
                query = title.trim().takeIf { it.isNotBlank() },
                languages = languageCode,
                orderBy = "download_count",
            )
        }
    }

    private fun buildTitleQuery(title: String, year: Int?): String? {
        val normalized = title.trim()
        if (normalized.isBlank()) return null
        return if (year != null && year > 0) "$normalized $year" else normalized
    }

    private fun buildEpisodeQuery(title: String, seasonNumber: Int?, episodeNumber: Int?): String? {
        val normalized = title.trim()
        if (normalized.isBlank()) return null
        val season = seasonNumber?.takeIf { it > 0 }
        val episode = episodeNumber?.takeIf { it > 0 }
        return when {
            season != null && episode != null ->
                "$normalized S${season.toString().padStart(2, '0')}E${episode.toString().padStart(2, '0')}"
            season != null -> "$normalized S${season.toString().padStart(2, '0')}"
            else -> normalized
        }
    }

    private fun Result<*>.isUnauthorized(): Boolean =
        (this as? Result.Error)?.exception?.let { it is OpenSubtitlesApiException && it.isUnauthorized } == true

    private companion object {
        const val NOT_SIGNED_IN_MESSAGE =
            "OpenSubtitles is not signed in. Add your OpenSubtitles.com account in Settings."
    }
}
