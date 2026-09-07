package com.streamvault.player.playback

import android.content.Context
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.streamvault.domain.model.VodHttpProtocolMode
import com.streamvault.domain.model.PlaybackTransportPolicy
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.util.BoundedExpiringCache
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol

internal fun shouldUsePlatformHttpDataSource(resolvedStreamType: ResolvedStreamType): Boolean =
    false

@UnstableApi
class PlayerDataSourceFactoryProvider(
    private val context: Context,
    private val baseClient: OkHttpClient,
    /** Receives byte counts for foreground (non-preload) playback; used for measured bitrate stats. */
    private val transferListener: TransferListener? = null
) {
    private companion object {
        private const val TAG = "PlayerDataSource"
        private const val MAX_CLIENT_CACHE_ENTRIES = 32
        private const val CLIENT_CACHE_TTL_MILLIS = 30L * 60L * 1_000L
    }

    private data class ClientKey(
        val profile: PlayerTimeoutProfile,
        val forceHttp1: Boolean,
        val port: Int,
        val transportPolicy: PlaybackTransportPolicy?,
        val allowInvalidSsl: Boolean,
        val proxyHost: String,
        val proxyPort: Int?
    )

    private val addressHealthStore = PlayerAddressHealthStore()
    private val clientsByKey = BoundedExpiringCache<ClientKey, OkHttpClient>(
        maxEntries = MAX_CLIENT_CACHE_ENTRIES,
        ttlMillis = CLIENT_CACHE_TTL_MILLIS
    )

    fun createFactory(
        streamInfo: StreamInfo,
        resolvedStreamType: ResolvedStreamType,
        vodHttpProtocolMode: VodHttpProtocolMode = VodHttpProtocolMode.COMPATIBILITY_HTTP1,
        preload: Boolean = false
    ): Pair<PlayerTimeoutProfile, DataSource.Factory> {
        val profile = PlayerTimeoutProfile.resolve(streamInfo, resolvedStreamType, preload)
        val headers = effectivePlaybackRequestProperties(
            headers = streamInfo.headers,
            userAgent = streamInfo.userAgent
        )
        logRequestShape(streamInfo, headers, preload)
        val forceHttp1 = PlayerHttpProtocolPolicy.forceHttp1(
            resolvedStreamType = resolvedStreamType,
            vodHttpProtocolMode = vodHttpProtocolMode
        )
        val port = streamPort(streamInfo.url)
        val clientKey = ClientKey(
            profile = profile,
            forceHttp1 = forceHttp1,
            port = port,
            transportPolicy = streamInfo.playbackTransportPolicy,
            allowInvalidSsl = streamInfo.allowInvalidSsl,
            proxyHost = streamInfo.proxyHost.trim(),
            proxyPort = streamInfo.proxyPort
        )
        val client = clientsByKey.getOrPut(clientKey) {
            val transportPolicy = streamInfo.playbackTransportPolicy
            val builder = when {
                transportPolicy != null ->
                    baseClient.newBuilder()
                        .applyPlaybackTransportPolicy(transportPolicy)
                streamInfo.allowInvalidSsl ->
                    baseClient.newBuilder().applyUnsafeTlsBypass()
                else -> baseClient.newBuilder()
            }
            builder
                .addInterceptor(StalkerPlaybackRequestLoggingInterceptor)
                .connectTimeout(profile.connectTimeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(profile.readTimeoutMs, TimeUnit.MILLISECONDS)
                .writeTimeout(profile.writeTimeoutMs, TimeUnit.MILLISECONDS)
                .dns(PlayerDnsPolicy.healthAwareDns(port = port, healthStore = addressHealthStore))
                .eventListener(PlayerAddressHealthEventListener(addressHealthStore))
                .apply {
                    if (forceHttp1) {
                        protocols(listOf(Protocol.HTTP_1_1))
                    }
                    streamInfo.httpProxy()?.let { proxy(it) }
                }
                .build()
        }
        if (forceHttp1) {
            Log.i(TAG, "data-source streamType=$resolvedStreamType timeout=$profile httpProtocol=HTTP_1_1")
        }
        val upstreamFactory = OkHttpDataSource.Factory(client).apply {
            if (headers.isNotEmpty()) {
                setDefaultRequestProperties(headers)
            }
        }
        val defaultFactory = DefaultDataSource.Factory(context, upstreamFactory)
        if (!preload && transferListener != null) {
            defaultFactory.setTransferListener(transferListener)
        }
        val factory = if (shouldWrapDataSourceReadStats(resolvedStreamType)) {
            PlayerDataSourceReadStatsFactory(
                upstream = defaultFactory,
                resolvedStreamType = resolvedStreamType,
                initialTargetUrl = streamInfo.url
            )
        } else {
            defaultFactory
        }
        return profile to factory
    }

    internal fun clientCacheSizeForTests(): Int = clientsByKey.size()

    private fun logRequestShape(
        streamInfo: StreamInfo,
        headers: Map<String, String>,
        preload: Boolean
    ) {
        val hasStalkerHeaders = headers.containsKey("X-User-Agent") ||
            headers.containsKey("Authorization") ||
            headers["Cookie"]?.contains("mac=", ignoreCase = true) == true
        if (!hasStalkerHeaders) {
            return
        }
        val uri = runCatching { URI(streamInfo.url) }.getOrNull()
        Log.d(
            TAG,
            "Playback request headers preload=$preload host=${uri?.host.orEmpty()} path=${uri?.path.orEmpty()} " +
                "ua=${!streamInfo.userAgent.isNullOrBlank()} referer=${headers.containsKey("Referer")} " +
                "cookie=${headers.containsKey("Cookie")} auth=${headers.containsKey("Authorization")} " +
                "xua=${headers.containsKey("X-User-Agent")}"
        )
    }

    private fun streamPort(url: String): Int {
        val uri = runCatching { URI(url) }.getOrNull()
        uri?.port?.takeIf { it > 0 }?.let { return it }
        return when (uri?.scheme?.lowercase()) {
            "http" -> 80
            "https" -> 443
            else -> -1
        }
    }

    private fun StreamInfo.httpProxy(): Proxy? {
        val host = proxyHost.trim().takeIf { it.isNotBlank() } ?: return null
        val port = proxyPort ?: return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, port))
    }
}

internal fun effectivePlaybackRequestProperties(
    headers: Map<String, String>,
    userAgent: String?
): Map<String, String> {
    val normalizedUserAgent = userAgent?.trim().orEmpty()
    if (normalizedUserAgent.isBlank()) {
        return headers
    }
    return buildMap(headers.size + 1) {
        headers.forEach { (name, value) ->
            if (!name.equals("User-Agent", ignoreCase = true)) {
                put(name, value)
            }
        }
        put("User-Agent", normalizedUserAgent)
    }
}

private object StalkerPlaybackRequestLoggingInterceptor : Interceptor {
    private const val TAG = "PlayerDataSource"

    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        if (!request.hasStalkerPlaybackShape()) {
            return chain.proceed(request)
        }
        Log.d(
            TAG,
            "Playback request actual method=${request.method} target=${PlaybackLogSanitizer.sanitizeUrl(request.url.toString())} " +
                "ua=${request.header("User-Agent") != null} referer=${request.header("Referer") != null} " +
                "cookie=${request.header("Cookie") != null} auth=${request.header("Authorization") != null} " +
                "xua=${request.header("X-User-Agent") != null} range=${request.header("Range") != null} " +
                "acceptEncoding=${request.header("Accept-Encoding")?.take(24).orEmpty()} cookieKeys=${request.cookieKeySummary()}"
        )
        val response = chain.proceed(request)
        Log.d(
            TAG,
            "Playback response actual target=${PlaybackLogSanitizer.sanitizeUrl(request.url.toString())} " +
                "code=${response.code} length=${response.header("Content-Length").orEmpty()} " +
                "type=${response.header("Content-Type").orEmpty()}"
        )
        return response
    }

    private fun okhttp3.Request.hasStalkerPlaybackShape(): Boolean {
        val path = url.encodedPath.lowercase()
        return header("X-User-Agent") != null ||
            header("Authorization") != null ||
            header("Cookie")?.contains("mac=", ignoreCase = true) == true ||
            path.endsWith("/play/live.php") ||
            path.endsWith("/play/movie.php")
    }

    private fun okhttp3.Request.cookieKeySummary(): String {
        val cookie = header("Cookie") ?: return ""
        return cookie.split(';')
            .mapNotNull { part -> part.substringBefore('=', missingDelimiterValue = "").trim().takeIf(String::isNotBlank) }
            .take(12)
            .joinToString("|")
    }
}
