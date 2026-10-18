package com.streamvault.data.remote.stremio

import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioCatalogPage
import com.streamvault.domain.model.stremio.StremioCatalogQuery
import com.streamvault.domain.model.stremio.StremioManifest
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaDetail
import com.streamvault.domain.model.stremio.StremioStream
import com.streamvault.domain.model.stremio.StremioSubtitle
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.Authenticator
import okhttp3.CookieJar

@Singleton
class StremioAddonClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val json: Json
) {
    fun parseManifest(manifestJson: String): StremioManifest? = runCatching {
        json.decodeFromString(StremioManifestDto.serializer(), manifestJson)
    }.getOrNull()?.toDomain()

    suspend fun fetchManifest(addonUrl: String): Result<StremioManifest> =
        fetchManifestSnapshot(addonUrl).map { it.manifest }

    suspend fun fetchManifestSnapshot(addonUrl: String): Result<StremioManifestSnapshot> {
        val location = StremioAddonUrl.parse(addonUrl) ?: return Result.error(INVALID_URL_MESSAGE)
        return fetchBody(location.manifestUrl, StremioManifestDto.serializer()) { dto, raw ->
            val manifest = dto.toDomain() ?: throw StremioParseException("Addon manifest is missing its id")
            StremioManifestSnapshot(
                manifest = manifest,
                manifestJson = raw,
                manifestUrl = location.manifestUrl
            )
        }
    }

    suspend fun fetchCatalog(
        addonUrl: String,
        type: StremioMediaType,
        catalogId: String,
        query: StremioCatalogQuery = StremioCatalogQuery()
    ): Result<StremioCatalogPage> {
        if (catalogId.isBlank()) return Result.error("Addon catalog id is missing")
        if (type == StremioMediaType.UNKNOWN) return Result.error(UNSUPPORTED_TYPE_MESSAGE)
        val location = StremioAddonUrl.parse(addonUrl) ?: return Result.error(INVALID_URL_MESSAGE)
        val path = StremioAddonUrl.catalogPath(type, catalogId, query.toExtras())
        return fetch(location.endpointUrl(path), StremioCatalogResponseDto.serializer()) { dto ->
            dto.toDomain()
        }
    }

    suspend fun fetchMeta(
        addonUrl: String,
        type: StremioMediaType,
        id: String
    ): Result<StremioMetaDetail> {
        if (id.isBlank()) return Result.error(MISSING_ID_MESSAGE)
        if (type == StremioMediaType.UNKNOWN) return Result.error(UNSUPPORTED_TYPE_MESSAGE)
        val location = StremioAddonUrl.parse(addonUrl) ?: return Result.error(INVALID_URL_MESSAGE)
        val path = StremioAddonUrl.metaPath(type, id)
        return fetch(location.endpointUrl(path), StremioMetaResponseDto.serializer()) { dto ->
            dto.meta?.toDomain() ?: throw StremioParseException("Addon did not return metadata for this item")
        }
    }

    suspend fun fetchStreams(
        addonUrl: String,
        type: StremioMediaType,
        id: String
    ): Result<List<StremioStream>> {
        if (id.isBlank()) return Result.error(MISSING_ID_MESSAGE)
        if (type == StremioMediaType.UNKNOWN) return Result.error(UNSUPPORTED_TYPE_MESSAGE)
        val location = StremioAddonUrl.parse(addonUrl) ?: return Result.error(INVALID_URL_MESSAGE)
        val path = StremioAddonUrl.streamPath(type, id)
        return fetch(location.endpointUrl(path), StremioStreamResponseDto.serializer()) { dto ->
            dto.streams.orEmpty().mapNotNull { it.toDomain() }
        }
    }

    suspend fun fetchSubtitles(
        addonUrl: String,
        type: StremioMediaType,
        id: String,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null
    ): Result<List<StremioSubtitle>> {
        if (id.isBlank()) return Result.error(MISSING_ID_MESSAGE)
        if (type == StremioMediaType.UNKNOWN) return Result.error(UNSUPPORTED_TYPE_MESSAGE)
        val location = StremioAddonUrl.parse(addonUrl) ?: return Result.error(INVALID_URL_MESSAGE)
        val extras = buildList {
            videoHash?.trim()?.takeIf { it.isNotEmpty() }?.let { add("videoHash" to it) }
            videoSize?.takeIf { it > 0L }?.let { add("videoSize" to it.toString()) }
            filename?.trim()?.takeIf { it.isNotEmpty() }?.let { add("filename" to it) }
        }
        val path = StremioAddonUrl.subtitlePath(type, id, extras)
        return fetch(location.endpointUrl(path), StremioSubtitleResponseDto.serializer()) { dto ->
            dto.subtitles.orEmpty().mapNotNull { it.toDomain() }
        }
    }

    private suspend fun <D, T> fetch(
        url: String,
        deserializer: DeserializationStrategy<D>,
        map: (D) -> T
    ): Result<T> = fetchBody(url, deserializer) { dto, _ -> map(dto) }

    private suspend fun <D, T> fetchBody(
        url: String,
        deserializer: DeserializationStrategy<D>,
        map: (D, String) -> T
    ): Result<T> = try {
        val body = withContext(Dispatchers.IO) { executeBody(url) }
        Result.success(map(json.decodeFromString(deserializer, body), body))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: SerializationException) {
        Result.error("Addon response could not be parsed")
    } catch (_: IllegalArgumentException) {
        Result.error("Addon response could not be parsed")
    } catch (error: Exception) {
        val message = when (error) {
            is StremioHttpException, is StremioResponseTooLargeException, is StremioParseException ->
                error.message ?: "Addon request failed"
            else -> "Addon request failed. Check the connection and addon configuration."
        }
        // Do not retain arbitrary OkHttp/parser exceptions: they can include a configured URL path or query.
        Result.error(message)
    }

    private suspend fun executeBody(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", ACCEPT_JSON)
            .get()
            .build()
        return okHttpClient.newBuilder()
            .applyStremioAddonRequestPolicy()
            .callTimeout(REQUEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .useCancellableResponse { response ->
                if (!response.isSuccessful) {
                    throw StremioHttpException(response.code)
                }
                readBodyBounded(response)
            }
    }

    private fun readBodyBounded(response: Response): String {
        val body = response.body ?: throw StremioParseException("Addon returned an empty response")
        val declaredLength = body.contentLength()
        if (declaredLength > MAX_RESPONSE_BYTES) {
            throw StremioResponseTooLargeException(declaredLength)
        }
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        var total = 0L
        body.byteStream().use { stream ->
            while (true) {
                val read = stream.read(chunk)
                if (read == -1) break
                total += read
                if (total > MAX_RESPONSE_BYTES) {
                    throw StremioResponseTooLargeException(total)
                }
                output.write(chunk, 0, read)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    internal companion object {
        internal const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        private const val REQUEST_TIMEOUT_SECONDS = 30L
        private const val ACCEPT_JSON = "application/json"
        private const val INVALID_URL_MESSAGE = "Addon URL is not a valid http(s) or stremio:// address"
        private const val MISSING_ID_MESSAGE = "Addon content id is missing"
        private const val UNSUPPORTED_TYPE_MESSAGE = "Addon request has an unsupported content type"
    }
}

/** Addon URLs may contain install credentials in paths or queries; keep them out of inherited app logging and state. */
private fun OkHttpClient.Builder.applyStremioAddonRequestPolicy(): OkHttpClient.Builder {
    interceptors().clear()
    networkInterceptors().clear()
    cookieJar(CookieJar.NO_COOKIES)
        .authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .cache(null)
    return this
}

class StremioHttpException(val statusCode: Int) : IOException("Addon request failed with HTTP $statusCode")

class StremioResponseTooLargeException(observedBytes: Long) :
    IOException("Addon response is too large ($observedBytes bytes)")

class StremioParseException(message: String) : IOException(message)

data class StremioManifestSnapshot(
    val manifest: StremioManifest,
    val manifestJson: String,
    val manifestUrl: String
)
