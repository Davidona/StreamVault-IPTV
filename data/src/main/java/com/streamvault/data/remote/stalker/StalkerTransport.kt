package com.streamvault.data.remote.stalker

import com.streamvault.domain.model.StalkerTransportChallenge
import com.streamvault.domain.model.StalkerTransportChallengeReason
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.StalkerTransportOrigin
import java.net.InetSocketAddress
import java.io.IOException
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Produces Stalker clients without weakening the application-wide OkHttp client.
 *
 * HTTPS clients keep normal certificate validation unless the user approved a pinned key.
 * HTTP portals are allowed directly and remain scoped against unapproved cross-origin redirects.
 */
class StalkerTransportFactory(
    private val strictClient: OkHttpClient
) {
    private val approvedClients = ConcurrentHashMap<String, OkHttpClient>()
    private val strictStalkerClient: OkHttpClient by lazy {
        buildRedirectScopedClient(strictClient, null)
    }

    fun clientFor(url: String, grant: StalkerTransportGrant?): OkHttpClient {
        val httpUrl = url.toHttpUrlOrNull()
            ?: throw StalkerApiError.TransportConsentRequired(
                invalidOriginChallenge(url)
            )
        val origin = httpUrl.toTransportOrigin()
        if (httpUrl.isHttps) {
            if (grant?.mode != StalkerTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS) {
                if (grant == null) return strictStalkerClient
                val key = "strict|${grant.mode}|${grant.origin.authority}|${grant.spkiSha256.orEmpty()}"
                return approvedClients.computeIfAbsent(key) {
                    buildRedirectScopedClient(strictClient, grant)
                }
            }
            requireMatchingGrant(origin, grant)
            val expectedPin = grant.spkiSha256?.takeIf { it.isNotBlank() }
                ?: throw StalkerApiError.TransportConsentRequired(
                    tlsChallenge(origin, null, "MISSING_APPROVED_KEY")
                )
            val key = "${origin.authority}|$expectedPin"
            return approvedClients.computeIfAbsent(key) {
                buildPinnedUnverifiedClient(origin, expectedPin)
            }
        }

        val redirectGrant = grant?.takeIf {
            it.mode == StalkerTransportMode.USER_ACCEPTED_HTTP &&
                it.origin.normalized() == origin.normalized()
        }
        val redirectGrantKey = redirectGrant?.origin?.authority ?: "same-origin"
        val key = "http|${origin.authority}|$redirectGrantKey"
        return approvedClients.computeIfAbsent(key) {
            buildRedirectScopedClient(strictClient, redirectGrant)
        }
    }

    suspend fun challengeForTlsFailure(url: String, error: Throwable): StalkerTransportChallenge? {
        if (!error.isTlsIdentityFailure()) return null
        val httpUrl = url.toHttpUrlOrNull()?.takeIf(HttpUrl::isHttps) ?: return null
        val origin = httpUrl.toTransportOrigin()
        val pin = probeSpki(origin)
        return tlsChallenge(
            origin = origin,
            spkiSha256 = pin,
            detailCode = when (error) {
                is SSLPeerUnverifiedException -> "HOSTNAME_OR_PEER_UNVERIFIED"
                else -> "CERTIFICATE_CHAIN_UNVERIFIED"
            }
        )
    }

    private fun requireMatchingGrant(
        origin: StalkerTransportOrigin,
        grant: StalkerTransportGrant
    ) {
        if (grant.origin.normalized() != origin.normalized()) {
            throw StalkerApiError.TransportConsentRequired(
                StalkerTransportChallenge(
                    reason = StalkerTransportChallengeReason.ORIGIN_CHANGED,
                    origin = origin,
                    displayHost = origin.displayHost(),
                    detailCode = "TRANSPORT_ORIGIN_CHANGED"
                )
            )
        }
    }

    private fun buildPinnedUnverifiedClient(
        origin: StalkerTransportOrigin,
        expectedPin: String
    ): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) = Unit

            override fun checkServerTrusted(
                chain: Array<out X509Certificate>?,
                authType: String?
            ) {
                val leaf = chain?.firstOrNull()
                    ?: throw CertificateException("Portal did not present a certificate.")
                if (leaf.spkiSha256() != expectedPin) {
                    throw CertificateException("Portal certificate public key changed.")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        val pinnedClient = strictClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { hostname, session ->
                if (!hostname.equals(origin.host, ignoreCase = true)) {
                    false
                } else {
                    runCatching {
                        (session.peerCertificates.firstOrNull() as? X509Certificate)
                            ?.spkiSha256() == expectedPin
                    }.getOrDefault(false)
                }
            }
            .build()
        return buildRedirectScopedClient(pinnedClient, null)
    }

    private fun buildRedirectScopedClient(
        base: OkHttpClient,
        acceptedRedirectGrant: StalkerTransportGrant?
    ): OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(StalkerRedirectInterceptor(acceptedRedirectGrant))
        .build()

    /**
     * Performs TLS only, with no HTTP request and therefore no MAC, credentials, cookies, or
     * custom headers. The returned key is public certificate material used to populate the
     * foreground consent challenge.
     */
    private suspend fun probeSpki(origin: StalkerTransportOrigin): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val captureManager = object : X509TrustManager {
                    override fun checkClientTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?
                    ) = Unit

                    override fun checkServerTrusted(
                        chain: Array<out X509Certificate>?,
                        authType: String?
                    ) = Unit

                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                val sslContext = SSLContext.getInstance("TLS").apply {
                    init(null, arrayOf(captureManager), SecureRandom())
                }
                Socket().use { raw ->
                    raw.connect(InetSocketAddress(origin.host, origin.port), PROBE_TIMEOUT_MILLIS)
                    raw.soTimeout = PROBE_TIMEOUT_MILLIS
                    (sslContext.socketFactory.createSocket(
                        raw,
                        origin.host,
                        origin.port,
                        true
                    ) as SSLSocket).use { tls ->
                        tls.startHandshake()
                        (tls.session.peerCertificates.firstOrNull() as? X509Certificate)
                            ?.spkiSha256()
                    }
                }
            }.getOrNull()
        }

    private fun invalidOriginChallenge(raw: String): StalkerTransportChallenge =
        StalkerTransportChallenge(
            reason = StalkerTransportChallengeReason.ORIGIN_CHANGED,
            origin = StalkerTransportOrigin("https", "invalid", 443),
            displayHost = raw.take(128),
            detailCode = "INVALID_PORTAL_ORIGIN"
        )

    private fun tlsChallenge(
        origin: StalkerTransportOrigin,
        spkiSha256: String?,
        detailCode: String
    ): StalkerTransportChallenge = StalkerTransportChallenge(
        reason = StalkerTransportChallengeReason.INVALID_TLS,
        origin = origin,
        displayHost = origin.displayHost(),
        proposedSpkiSha256 = spkiSha256,
        detailCode = detailCode
    )

    private companion object {
        const val PROBE_TIMEOUT_MILLIS = 5_000
    }
}

private class StalkerRedirectInterceptor(
    private val acceptedRedirectGrant: StalkerTransportGrant?
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        var request = chain.request()
        repeat(MAX_REDIRECTS + 1) { hop ->
            val response = chain.proceed(request)
            if (response.code !in REDIRECT_CODES) return response
            if (hop >= MAX_REDIRECTS) {
                response.close()
                throw IOException("Stalker redirect limit exceeded.")
            }
            val target = response.header("Location")
                ?.let(request.url::resolve)
                ?: return response
            val sourceOrigin = request.url.toTransportOrigin()
            val targetOrigin = target.toTransportOrigin()
            val sameOrigin = sourceOrigin.normalized() == targetOrigin.normalized()
            if (!sameOrigin && acceptedRedirectGrant?.origin?.normalized() != targetOrigin.normalized()) {
                response.close()
                throw StalkerApiError.TransportConsentRequired(
                    StalkerTransportChallenge(
                        reason = StalkerTransportChallengeReason.ORIGIN_CHANGED,
                        origin = targetOrigin,
                        displayHost = targetOrigin.displayHost(),
                        detailCode = "CROSS_ORIGIN_REDIRECT"
                    )
                )
            }
            response.close()
            request = request.newBuilder()
                .url(target)
                .apply {
                    if (!sameOrigin) {
                        SENSITIVE_REDIRECT_HEADERS.forEach(::removeHeader)
                    }
                }
                .build()
        }
        throw IOException("Stalker redirect limit exceeded.")
    }

    private companion object {
        const val MAX_REDIRECTS = 5
        val REDIRECT_CODES = setOf(300, 301, 302, 303, 307, 308)
        val SENSITIVE_REDIRECT_HEADERS = listOf(
            "Authorization",
            "Cookie",
            "X-User-Agent",
            "X-Device-Id",
            "X-Signature"
        )
    }
}

internal fun HttpUrl.toTransportOrigin(): StalkerTransportOrigin =
    StalkerTransportOrigin(
        scheme = scheme.lowercase(Locale.ROOT),
        host = host.lowercase(Locale.ROOT),
        port = port
    )

internal fun StalkerTransportOrigin.normalized(): StalkerTransportOrigin = copy(
    scheme = scheme.lowercase(Locale.ROOT),
    host = host.lowercase(Locale.ROOT)
)

internal fun StalkerTransportOrigin.displayHost(): String {
    val defaultPort = (scheme.equals("https", true) && port == 443) ||
        (scheme.equals("http", true) && port == 80)
    return if (defaultPort) host else "$host:$port"
}

internal fun X509Certificate.spkiSha256(): String =
    "sha256/" + Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(publicKey.encoded)
    )

internal fun Throwable.isTlsIdentityFailure(): Boolean =
    generateSequence(this) { it.cause }.any { cause ->
        cause is SSLHandshakeException ||
            cause is SSLPeerUnverifiedException ||
            cause is CertificateException
    }
