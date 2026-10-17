package com.streamvault.data.sports.provider

import com.streamvault.domain.sports.provider.*
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*

/** Allow-listed numeric response metadata; it never includes response text or credentials. */
data class SportsProviderResponseMetadata(
    val requestLimit: Int? = null,
    val requestsRemaining: Int? = null,
    val resetAfterSeconds: Int? = null,
)

/**
 * Dedicated client: no shared URL logger, cookie jar, disk cache, or automatic redirects/retries.
 * Adapters own pagination/caching and schedule retries from typed failures and Retry-After.
 */
class SportsProviderHttpClient(
    private val userAgent: String,
    private val clock: Clock = Clock.systemUTC(),
    private val maxResponseBytes: Int = 2 * 1024 * 1024,
    clientOverride: OkHttpClient? = null,
) {
    init { require(maxResponseBytes in 1..(8 * 1024 * 1024)) }

    private val client = (clientOverride ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()).newBuilder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .build()

    /** Request construction and parsing exceptions never escape with credential-bearing messages. */
    suspend fun <T> get(
        url: HttpUrl,
        auth: SportsProviderAuth,
        credentials: SportsProviderCredentials?,
        // Explicit opt-in for a user's self-hosted HTTP endpoint; HTTPS remains the default.
        allowInsecureHttp: Boolean = false,
        onResponseMetadata: (SportsProviderResponseMetadata) -> Unit = {},
        decode: (String) -> T,
    ): SportsProviderResult<T> {
        val request = try {
            require(url.isHttps || allowInsecureHttp)
            require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null)
            val builder = Request.Builder().url(url)
                .header("User-Agent", userAgent).header("Accept", "application/json")
            when (auth) {
                SportsProviderAuth.None -> Unit
                is SportsProviderAuth.ApiKeyHeader -> builder.header(auth.name, requireSecret(credentials))
                is SportsProviderAuth.ApiKeyQuery -> builder.url(
                    url.newBuilder().setQueryParameter(auth.name, requireSecret(credentials)).build()
                )
                SportsProviderAuth.Bearer, is SportsProviderAuth.OAuth2 ->
                    builder.header("Authorization", "Bearer ${requireSecret(credentials)}")
                SportsProviderAuth.Basic -> {
                    val username = requireNotNull(credentials?.username)
                    require(':' !in username)
                    builder.header("Authorization", Credentials.basic(username, requireSecret(credentials)))
                }
            }
            builder.build()
        } catch (_: Exception) {
            return SportsProviderResult.Failure(SportsProviderError.INVALID_CONFIGURATION)
        }
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resume(
                        SportsProviderResult.Failure(SportsProviderError.NETWORK)
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val result = response.use { parse(it, onResponseMetadata, decode) }
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: CancellationException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resume(SportsProviderResult.Failure(
                            if (e is IOException) SportsProviderError.NETWORK else SportsProviderError.INVALID_RESPONSE
                        ))
                    }
                }
            })
        }
    }

    private fun requireSecret(credentials: SportsProviderCredentials?): String =
        requireNotNull(credentials?.secret).also {
            require(it.isNotBlank() && '\r' !in it && '\n' !in it)
        }

    private fun <T> parse(
        response: Response,
        onResponseMetadata: (SportsProviderResponseMetadata) -> Unit,
        decode: (String) -> T,
    ): SportsProviderResult<T> {
        val metadata = SportsProviderResponseMetadata(
            requestLimit = response.header("x-ratelimit-requests-limit")?.toIntOrNull()
                ?.takeIf { it in 0..MAX_REPORTED_REQUESTS },
            requestsRemaining = (response.header("x-ratelimit-requests-remaining")
                ?: response.header("x-requestsavailable"))?.toIntOrNull()
                ?.takeIf { it in 0..MAX_REPORTED_REQUESTS },
            resetAfterSeconds = response.header("x-requestcounter-reset")?.toIntOrNull()
                ?.takeIf { it in 0..MAX_RESET_SECONDS },
        )
        try {
            onResponseMetadata(metadata)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Usage reporting must not change the provider result.
        }
        if (!response.isSuccessful) return SportsProviderResult.Failure(
            category = when (response.code) {
                401, 403 -> SportsProviderError.AUTHENTICATION
                429 -> SportsProviderError.RATE_LIMITED
                in 500..599 -> SportsProviderError.SERVER
                else -> SportsProviderError.HTTP
            },
            httpStatus = response.code,
            retryAt = retryAt(response.header("Retry-After"))
                ?: metadata.resetAfterSeconds?.let { clock.instant().plusSeconds(it.toLong()) },
        )
        val text = try {
            val body = response.body ?: return SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
            if (body.contentLength() > maxResponseBytes) throw SportsResponseTooLargeException()
            body.byteStream().readBytesBounded(maxResponseBytes).toString(Charsets.UTF_8)
        } catch (_: SportsResponseTooLargeException) {
            return SportsProviderResult.Failure(SportsProviderError.RESPONSE_TOO_LARGE)
        } catch (_: IOException) {
            return SportsProviderResult.Failure(SportsProviderError.NETWORK)
        }
        return try {
            SportsProviderResult.Success(decode(text))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            SportsProviderResult.Failure(SportsProviderError.INVALID_RESPONSE)
        }
    }

    private fun retryAt(value: String?): Instant? {
        if (value == null) return null
        val now = clock.instant()
        return runCatching {
            value.trim().toLongOrNull()?.let { seconds ->
                if (seconds < 0) null else now.plusSeconds(seconds)
            } ?: ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                .takeIf { !it.isBefore(now) }
        }.getOrNull()
    }

    private companion object {
        const val MAX_REPORTED_REQUESTS = 10_000_000
        const val MAX_RESET_SECONDS = 86_400
    }
}
