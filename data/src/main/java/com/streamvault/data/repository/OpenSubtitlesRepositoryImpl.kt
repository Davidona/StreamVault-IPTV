package com.streamvault.data.repository

import android.content.Context
import com.streamvault.data.remote.subtitle.OpenSubtitlesApi
import com.streamvault.data.remote.subtitle.OpenSubtitlesApiException
import com.streamvault.data.remote.subtitle.OpenSubtitlesCredentials
import com.streamvault.data.remote.subtitle.OpenSubtitlesSession
import com.streamvault.data.remote.subtitle.OpenSubtitlesSessionStore
import com.streamvault.data.remote.subtitle.OpenSubtitlesSubtitleDto
import com.streamvault.domain.manager.OpenSubtitlesAccount
import com.streamvault.domain.manager.OpenSubtitlesAccountManager
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class OpenSubtitlesRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: OpenSubtitlesApi,
    private val sessionStore: OpenSubtitlesSessionStore,
) : ExternalSubtitleRepository, OpenSubtitlesAccountManager {

    private val sessionMutex = Mutex()

    override val account: Flow<OpenSubtitlesAccount?> = sessionStore.session
        .map { it?.toAccount() }
        .distinctUntilChanged()

    override suspend fun signIn(username: String, password: String): Result<OpenSubtitlesAccount> {
        val cleanUsername = username.trim()
        if (cleanUsername.isBlank() || password.isBlank()) {
            return Result.error("Enter your OpenSubtitles username and password.")
        }
        return try {
            sessionMutex.withLock {
                when (val login = api.login(cleanUsername, password)) {
                    is Result.Success -> {
                        sessionStore.saveSignedInAccount(login.data, password)
                        Result.success(login.data.toAccount())
                    }
                    is Result.Error -> Result.error(login.message, login.exception)
                    is Result.Loading -> Result.Loading
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.error("OpenSubtitles sign-in failed: ${e.message}", e)
        }
    }

    override suspend fun signOut(): Result<Unit> = try {
        sessionMutex.withLock {
            val session = sessionStore.loadSession()
            if (session != null) api.logout(session.token, session.baseUrl)
            sessionStore.clearAll()
        }
        // Local sign-out always succeeds, even if OpenSubtitles is temporarily unreachable.
        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.error("Could not sign out of OpenSubtitles: ${e.message}", e)
    }

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
        val query = buildSearchQuery(contentType, title, year, tmdbId, parentTmdbId, seasonNumber, episodeNumber, language)
        authenticatedRequest { session -> api.search(query, session.token, session.baseUrl) }
            .map { subtitles -> subtitles.mapNotNull(::mapSubtitle) }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.error("OpenSubtitles search failed: ${e.message}", e)
    }

    override suspend fun downloadToCache(subtitle: ExternalSubtitle): Result<File> {
        return try {
            val fileId = subtitle.downloadId.toLongOrNull()?.takeIf { it > 0L }
                ?: return Result.error("This subtitle has no downloadable file.")
            val destination = subtitleCacheFile(fileId)
            if (destination.isFile && destination.length() > 0L) return Result.success(destination)

            authenticatedRequest { session ->
                if (session.remainingDownloads == 0) {
                    return@authenticatedRequest Result.error("Your OpenSubtitles download allowance is used for today.")
                }
                when (val link = api.downloadLink(fileId, session.token, session.baseUrl)) {
                    is Result.Success -> {
                        link.data.remaining?.let { remaining ->
                            sessionStore.updateRemainingDownloads(remaining, link.data.resetTimeUtc)
                        }
                        api.downloadSubtitleFile(link.data, destination)
                    }
                    is Result.Error -> Result.error(link.message, link.exception)
                    is Result.Loading -> Result.Loading
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.error("OpenSubtitles download failed: ${e.message}", e)
        }
    }

    private suspend fun <T> authenticatedRequest(
        request: suspend (OpenSubtitlesSession) -> Result<T>,
    ): Result<T> {
        val initial = obtainSession()
        val session = initial.getOrNull() ?: return initial.asError("Sign in to OpenSubtitles in Settings to search for subtitles.")
        val response = request(session)
        if (!response.isUnauthorized()) return response

        val refreshed = refreshSession(rejectedToken = session.token)
        val refreshedSession = refreshed.getOrNull() ?: return refreshed.asError(
            "Your OpenSubtitles session expired. Sign in again in Settings."
        )
        val retry = request(refreshedSession)
        if (retry.isUnauthorized()) invalidateRejectedSession(refreshedSession.token)
        return retry
    }

    private suspend fun invalidateRejectedSession(rejectedToken: String) {
        sessionMutex.withLock {
            if (sessionStore.loadSession()?.token == rejectedToken) sessionStore.clearAll()
        }
    }

    private suspend fun obtainSession(): Result<OpenSubtitlesSession> = sessionMutex.withLock {
        sessionStore.loadSession()?.let { return@withLock Result.success(it) }
        val credentials = sessionStore.loadCredentials()
            ?: return@withLock Result.error(NOT_SIGNED_IN_MESSAGE)
        loginAndStore(credentials)
    }

    /** Reuses a token another concurrent request has already refreshed. */
    private suspend fun refreshSession(rejectedToken: String): Result<OpenSubtitlesSession> = sessionMutex.withLock {
        sessionStore.loadSession()
            ?.takeIf { it.token != rejectedToken }
            ?.let { return@withLock Result.success(it) }
        val credentials = sessionStore.loadCredentials()
            ?: return@withLock Result.error("OpenSubtitles session expired and no saved credentials are available.")
        loginAndStore(credentials)
    }

    private suspend fun loginAndStore(credentials: OpenSubtitlesCredentials): Result<OpenSubtitlesSession> =
        when (val login = api.login(credentials.username, credentials.password)) {
            is Result.Success -> {
                sessionStore.saveSession(login.data)
                Result.success(login.data)
            }
            is Result.Error -> {
                if (login.exception.isUnauthorized()) sessionStore.clearAll()
                Result.error(login.message, login.exception)
            }
            is Result.Loading -> Result.Loading
        }

    private fun mapSubtitle(dto: OpenSubtitlesSubtitleDto): ExternalSubtitle? {
        val attributes = dto.attributes ?: return null
        val fileId = attributes.files.orEmpty()
            .firstNotNullOfOrNull { file -> file.fileId?.takeIf { it > 0L } }
            ?: return null
        val language = attributes.language.orEmpty()
        if (language.isBlank()) return null
        return ExternalSubtitle(
            id = dto.id?.takeIf { it.isNotBlank() } ?: fileId.toString(),
            language = language,
            displayLanguage = attributes.languageName?.takeIf { it.isNotBlank() } ?: language,
            releaseName = attributes.release.orEmpty(),
            source = ExternalSubtitleSource.OPENSUBTITLES,
            downloadId = fileId.toString(),
            downloadCount = attributes.downloadCount ?: 0L,
        )
    }

    private fun subtitleCacheFile(fileId: Long): File {
        val directory = File(context.cacheDir, "subtitles/opensubtitles").apply { mkdirs() }
        return File(directory, "$fileId.srt")
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
        val languageCode = language.trim().lowercase().takeIf { it.isNotBlank() && it != "auto" }
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
        (this as? Result.Error)?.exception.isUnauthorized()

    private fun Throwable?.isUnauthorized(): Boolean =
        this is OpenSubtitlesApiException && isUnauthorized

    private fun <T> Result<*>.asError(fallbackMessage: String): Result<T> = when (this) {
        is Result.Error -> Result.error(message, exception)
        is Result.Loading -> Result.error(fallbackMessage)
        is Result.Success -> Result.error(fallbackMessage)
    }

    private fun OpenSubtitlesSession.toAccount() = OpenSubtitlesAccount(
        username = username,
        allowedDownloads = allowedDownloads,
        remainingDownloads = remainingDownloads,
        level = level,
        vip = vip,
    )

    private companion object {
        const val NOT_SIGNED_IN_MESSAGE =
            "OpenSubtitles is not signed in. Add your OpenSubtitles.com account in Settings."
    }
}
