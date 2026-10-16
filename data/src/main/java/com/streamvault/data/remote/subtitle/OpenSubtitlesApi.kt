package com.streamvault.data.remote.subtitle

import com.google.gson.Gson
import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.domain.model.Result
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor

internal const val OPENSUBTITLES_DEFAULT_BASE_URL = "https://api.opensubtitles.com/api/v1"
internal const val OPENSUBTITLES_DEFAULT_BASE_HOST = "api.opensubtitles.com"

@Singleton
class OpenSubtitlesApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    @param:Named("opensubtitlesApiKey") private val apiKey: String,
    @param:Named("opensubtitlesUserAgent") private val userAgent: String = "StreamVault vdev",
) {
    private val loginRequestMutex = Mutex()
    private var lastLoginRequestStartedAtNanos: Long? = null

    data class DownloadLink(
        val url: String,
        val fileName: String,
        val remaining: Int?,
        val resetTimeUtc: String?,
    )

    data class SearchQuery(
        val query: String? = null,
        val tmdbId: Long? = null,
        val imdbId: Long? = null,
        val parentTmdbId: Long? = null,
        val parentImdbId: Long? = null,
        val seasonNumber: Int? = null,
        val episodeNumber: Int? = null,
        val languages: String? = null,
        val orderBy: String? = null,
    ) {
        fun toQueryParams(): Map<String, String> = buildMap {
            query?.takeIf { it.isNotBlank() }?.let { put("query", it) }
            tmdbId?.takeIf { it > 0 }?.let { put("tmdb_id", it.toString()) }
            imdbId?.takeIf { it > 0 }?.let { put("imdb_id", it.toString()) }
            parentTmdbId?.takeIf { it > 0 }?.let { put("parent_tmdb_id", it.toString()) }
            parentImdbId?.takeIf { it > 0 }?.let { put("parent_imdb_id", it.toString()) }
            seasonNumber?.takeIf { it > 0 }?.let { put("season_number", it.toString()) }
            episodeNumber?.takeIf { it > 0 }?.let { put("episode_number", it.toString()) }
            languages?.takeIf { it.isNotBlank() }?.let { put("languages", it) }
            orderBy?.takeIf { it.isNotBlank() }?.let { put("order_by", it) }
        }
    }

    suspend fun login(
        username: String,
        password: String,
        baseUrl: String = OPENSUBTITLES_DEFAULT_BASE_URL,
    ): Result<OpenSubtitlesSession> = try {
        val session = loginRequestMutex.withLock {
            val previousStart = lastLoginRequestStartedAtNanos
            if (previousStart != null) {
                val remainingNanos = MIN_LOGIN_INTERVAL_NANOS - (System.nanoTime() - previousStart)
                if (remainingNanos > 0L) {
                    delay(TimeUnit.NANOSECONDS.toMillis(remainingNanos) + 1L)
                }
            }
            lastLoginRequestStartedAtNanos = System.nanoTime()
            withContext(Dispatchers.IO) { loginSession(username, password, baseUrl) }
        }
        Result.success(session)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.error("OpenSubtitles login failed: ${e.message}", e)
    }

    suspend fun logout(token: String, baseUrlHost: String): Result<Unit> = try {
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(buildUrl(baseUrlHost, "/logout", emptyMap()))
                .header("Accept", "application/json")
                .header("Api-Key", requireApiKey())
                .header("Authorization", "Bearer $token")
                .header("User-Agent", userAgent)
                .delete()
                .build()
            execute(request, "OpenSubtitles logout")
        }
        Result.success(Unit)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.error("OpenSubtitles logout failed: ${e.message}", e)
    }

    suspend fun search(
        query: SearchQuery,
        token: String? = null,
        baseUrlHost: String = OPENSUBTITLES_DEFAULT_BASE_HOST,
    ): Result<List<OpenSubtitlesSubtitleDto>> = try {
        val subtitles = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(buildUrl(baseUrlHost, "/subtitles", query.toQueryParams()))
                .header("Accept", "application/json")
                .header("Api-Key", requireApiKey())
                .header("User-Agent", userAgent)
                .apply { token?.trim()?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") } }
                .get()
                .build()
            val body = execute(request, "OpenSubtitles search")
            gson.fromJson(body, OpenSubtitlesSearchResponseDto::class.java).data.orEmpty()
        }
        Result.success(subtitles)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.error("OpenSubtitles search failed: ${e.message}", e)
    }

    suspend fun languages(
        baseUrlHost: String = OPENSUBTITLES_DEFAULT_BASE_HOST,
    ): Result<List<OpenSubtitlesLanguageDto>> = try {
        val languages = withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(buildUrl(baseUrlHost, "/infos/languages", emptyMap()))
                .header("Accept", "application/json")
                .header("Api-Key", requireApiKey())
                .header("User-Agent", userAgent)
                .get()
                .build()
            val body = execute(request, "OpenSubtitles languages")
            gson.fromJson(body, OpenSubtitlesLanguagesResponseDto::class.java).data.orEmpty()
        }
        Result.success(languages)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.error("OpenSubtitles languages request failed: ${e.message}", e)
    }

    suspend fun downloadLink(
        fileId: Long,
        token: String? = null,
        baseUrlHost: String = OPENSUBTITLES_DEFAULT_BASE_HOST,
        subFormat: String = "srt",
    ): Result<DownloadLink> = try {
        val link = withContext(Dispatchers.IO) {
            val payload = gson.toJson(OpenSubtitlesDownloadRequestDto(fileId = fileId, subFormat = subFormat))
            val request = Request.Builder()
                .url(buildUrl(baseUrlHost, "/download", emptyMap()))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("Api-Key", requireApiKey())
                .header("User-Agent", userAgent)
                .apply { token?.trim()?.takeIf { it.isNotEmpty() }?.let { header("Authorization", "Bearer $it") } }
                .post(payload.toRequestBody("application/json".toMediaType()))
                .build()
            val body = execute(request, "OpenSubtitles download")
            val response = gson.fromJson(body, OpenSubtitlesDownloadResponseDto::class.java)
            val url = response.link?.takeIf { it.isNotBlank() }
                ?: throw OpenSubtitlesApiException("OpenSubtitles download returned no link")
            DownloadLink(
                url = url,
                fileName = response.fileName.orEmpty(),
                remaining = response.remaining,
                resetTimeUtc = response.resetTimeUtc,
            )
        }
        Result.success(link)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.error("OpenSubtitles download failed: ${e.message}", e)
    }

    suspend fun downloadSubtitleFile(link: DownloadLink, destination: File): Result<File> = try {
        val file = withContext(Dispatchers.IO) {
            destination.parentFile?.mkdirs()
            val temporaryFile = File(destination.parentFile, "${destination.name}.part")
            temporaryFile.delete()
            try {
                val request = Request.Builder()
                    .url(link.url)
                    .header("User-Agent", userAgent)
                    .get()
                    .build()
                // OpenSubtitles links contain short-lived download tokens in their URL. Keep the
                // shared connection pool and interceptors, but never log this request line.
                okHttpClient.newBuilder()
                    .apply {
                        interceptors().removeAll { it is HttpLoggingInterceptor }
                        networkInterceptors().removeAll { it is HttpLoggingInterceptor }
                    }
                    .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .build()
                    .newCall(request)
                    .useCancellableResponse { response ->
                        if (!response.isSuccessful) {
                            throw OpenSubtitlesApiException(
                                message = "OpenSubtitles subtitle file download failed with HTTP ${response.code}",
                                statusCode = response.code,
                            )
                        }
                        val body = response.body
                            ?: throw OpenSubtitlesApiException("OpenSubtitles subtitle file download returned an empty body")
                        temporaryFile.outputStream().use { out ->
                            body.byteStream().use { input -> input.copyTo(out) }
                        }
                    }
                if (temporaryFile.length() <= 0L) {
                    throw OpenSubtitlesApiException("OpenSubtitles subtitle file download returned an empty file")
                }
                if (destination.exists() && !destination.delete()) {
                    throw OpenSubtitlesApiException("Could not replace the cached subtitle file")
                }
                if (!temporaryFile.renameTo(destination)) {
                    throw OpenSubtitlesApiException("Could not save the downloaded subtitle file")
                }
                destination
            } finally {
                temporaryFile.delete()
            }
        }
        Result.success(file)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Result.error("OpenSubtitles subtitle file download failed: ${e.message}", e)
    }

    private suspend fun loginSession(username: String, password: String, baseUrl: String): OpenSubtitlesSession {
        val payload = gson.toJson(OpenSubtitlesLoginRequestDto(username = username, password = password))
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/login")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Api-Key", requireApiKey())
            .header("User-Agent", userAgent)
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        val body = execute(request, "OpenSubtitles login")
        val response = gson.fromJson(body, OpenSubtitlesLoginResponseDto::class.java)
        val token = response.token?.takeIf { it.isNotBlank() }
            ?: throw OpenSubtitlesApiException("OpenSubtitles login did not return a token")
        return OpenSubtitlesSession(
            token = token,
            baseUrl = response.baseUrl?.takeIf { it.isNotBlank() } ?: OPENSUBTITLES_DEFAULT_BASE_HOST,
            username = username,
            allowedDownloads = response.user?.allowedDownloads ?: 0,
            level = response.user?.level,
            vip = response.user?.vip == true,
        )
    }

    private suspend fun execute(request: Request, errorPrefix: String): String {
        return okHttpClient.newBuilder()
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .useCancellableResponse { response ->
                if (!response.isSuccessful) {
                    throw OpenSubtitlesApiException(
                        message = "$errorPrefix failed with HTTP ${response.code}",
                        statusCode = response.code,
                        isUnauthorized = response.code == 401,
                        isRateLimited = response.code == 429,
                    )
                }
                response.body?.string().orEmpty()
            }
    }

    private fun buildUrl(baseUrlHost: String, path: String, query: Map<String, String>): String {
        val normalized = baseUrlHost.trimEnd('/')
        val host = if (normalized.startsWith("http://") || normalized.startsWith("https://")) {
            normalized
        } else {
            "https://$normalized"
        }
        val qs = query.toSortedMap().entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        return "$host/api/v1$path${if (qs.isNotBlank()) "?$qs" else ""}"
    }

    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun requireApiKey(): String = apiKey.trim().takeIf { it.isNotEmpty() }
        ?: throw OpenSubtitlesApiException(
            "OpenSubtitles API key is missing. Add opensubtitles.api.key to the root local.properties file and rebuild."
        )

    private companion object {
        const val REQUEST_TIMEOUT_SECONDS = 30L
        const val MIN_LOGIN_INTERVAL_NANOS = 1_000_000_000L
    }
}
