package com.streamvault.data.manager.recording

import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ChannelDao
import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.data.remote.xtream.ResolvedStreamUrl
import com.streamvault.data.remote.xtream.XtreamStreamUrlResolver
import com.streamvault.data.repository.withM3uPlaybackMetadata
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LivePlaybackIdentity
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingSourceType
import com.streamvault.domain.model.StreamInfo
import com.streamvault.domain.model.StreamType
import com.streamvault.domain.util.effectiveHttpRequestProperties
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class ResolvedRecordingSource(
    val url: String,
    val sourceType: RecordingSourceType,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
    val networkProfile: RecordingNetworkProfile = RecordingNetworkProfile(),
    val livePlaybackIdentity: LivePlaybackIdentity? = null,
    val expirationTime: Long? = null,
    val providerLabel: String? = null,
    val failureCategory: RecordingFailureCategory = RecordingFailureCategory.NONE
)

@Singleton
class RecordingSourceResolver @Inject constructor(
    private val recordingHttpClientFactory: RecordingHttpClientFactory,
    private val providerDao: ProviderDao,
    private val xtreamStreamUrlResolver: XtreamStreamUrlResolver,
    private val channelDao: ChannelDao
) {

    suspend fun resolveLiveSource(
        providerId: Long,
        channelId: Long,
        logicalUrl: String
    ): ResolvedRecordingSource {
        val resolved = xtreamStreamUrlResolver.resolveAndCommitMetadata(
            url = logicalUrl,
            fallbackProviderId = providerId,
            fallbackStreamId = channelId,
            fallbackContentType = ContentType.LIVE,
            // Use the direct-source CDN URL when available — many Xtream servers
            // only serve streams via their CDN URLs and return 404 on the
            // credential-based portal path.  If no direct-source is present the
            // resolver already falls back to the portal URL automatically.
            preferStableUrl = false
        ) ?: throw IOException("Recording stream URL could not be resolved.")

        val providerLabel = providerDao.getById(providerId)?.let { provider ->
            when {
                provider.type == com.streamvault.domain.model.ProviderType.XTREAM_CODES -> "${provider.name} • Xtream"
                provider.type == com.streamvault.domain.model.ProviderType.M3U -> "${provider.name} • M3U"
                provider.type == com.streamvault.domain.model.ProviderType.STALKER_PORTAL -> "${provider.name} • Stalker"
                else -> provider.name
            }
        }
        val networkProfile = RecordingNetworkProfile(
            playbackTransportPolicy = resolved.playbackTransportPolicy,
            allowInvalidSsl = resolved.allowInvalidSsl,
            proxyHost = resolved.proxyHost,
            proxyPort = resolved.proxyPort
        )
        val channel = channelDao.getById(channelId)
            ?.takeIf { it.providerId == providerId }
            ?: channelDao.getByStreamId(providerId, channelId)
        val playbackInfo = StreamInfo(
            url = resolved.url,
            headers = resolved.headers,
            userAgent = resolved.userAgent,
            playbackTransportPolicy = resolved.playbackTransportPolicy,
            allowInvalidSsl = resolved.allowInvalidSsl,
            proxyHost = resolved.proxyHost,
            proxyPort = resolved.proxyPort,
            streamType = StreamType.fromContainerExtension(resolved.containerExtension),
            containerExtension = resolved.containerExtension,
            expirationTime = resolved.expirationTime
        ).withM3uPlaybackMetadata(channel?.playbackMetadataJson)
        val recordingResolved = resolved.copy(
            headers = playbackInfo.headers,
            userAgent = playbackInfo.userAgent
        )
        val client = recordingHttpClientFactory.create(networkProfile)
        val inferred = sourceTypeFromUrl(recordingResolved.url)
            ?: probeAdaptiveType(recordingResolved, client).takeIf { it != RecordingSourceType.UNKNOWN }
            ?: playbackInfo.streamType.toRecordingSourceType()
            ?: RecordingSourceType.UNKNOWN
        return ResolvedRecordingSource(
            url = resolved.url,
            sourceType = inferred,
            headers = playbackInfo.headers,
            userAgent = playbackInfo.userAgent,
            networkProfile = networkProfile,
            livePlaybackIdentity = if (providerId > 0L && channelId > 0L) {
                LivePlaybackIdentity(providerId, channelId)
            } else {
                null
            },
            expirationTime = resolved.expirationTime,
            providerLabel = providerLabel
        )
    }

    private fun sourceTypeFromUrl(url: String): RecordingSourceType? {
        val parsedUrl = url.toHttpUrlOrNull() ?: return null
        val pathExtension = parsedUrl.encodedPath
            .substringAfterLast('/')
            .substringAfterLast('.', missingDelimiterValue = "")
        sourceTypeFromContainerExtension(pathExtension)?.let { return it }

        return sequenceOf("output", "ext", "format")
            .flatMap { name -> parsedUrl.queryParameterValues(name).asSequence() }
            .mapNotNull(::sourceTypeFromContainerExtension)
            .firstOrNull()
    }

    private suspend fun probeAdaptiveType(resolved: ResolvedStreamUrl, client: OkHttpClient): RecordingSourceType {
        // Try HEAD first — lighter and avoids Range-header rejections.
        val headResult = try {
            val headRequest = buildRequest(resolved, isHead = true)
            client.newCall(headRequest).useCancellableResponse { response ->
                if (!response.isSuccessful) return@useCancellableResponse null
                val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                when {
                    "application/vnd.apple.mpegurl" in contentType || "application/x-mpegurl" in contentType -> RecordingSourceType.HLS
                    "application/dash+xml" in contentType -> RecordingSourceType.DASH
                    "video/mp2t" in contentType || "video/mpeg" in contentType -> RecordingSourceType.TS
                    else -> null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            null
        }
        if (headResult != null) return headResult

        // Fall back to a bounded GET to inspect the body prefix. Live stream URLs
        // can be unbounded, so never read the entire response body for detection.
        val request = buildRequest(resolved, isHead = false)

        val bodyPrefix = try {
            client.newCall(request).useCancellableResponse { response ->
                if (!response.isSuccessful) return@useCancellableResponse ""
                val contentType = response.header("Content-Type").orEmpty().lowercase(Locale.ROOT)
                when {
                    "application/vnd.apple.mpegurl" in contentType || "application/x-mpegurl" in contentType -> return RecordingSourceType.HLS
                    "application/dash+xml" in contentType -> return RecordingSourceType.DASH
                }
                response.body?.byteStream()?.use { input ->
                    val buffer = ByteArray(1024)
                    var count = 0
                    // A socket read can end in the middle of an HLS or DASH signature.
                    while (count < buffer.size) {
                        val read = input.read(buffer, count, buffer.size - count)
                        if (read <= 0) break
                        count += read
                    }
                    String(buffer, 0, count, Charsets.UTF_8)
                }.orEmpty()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            ""
        }

        return when {
            bodyPrefix.contains("#EXTM3U", ignoreCase = true) -> RecordingSourceType.HLS
            bodyPrefix.contains("<MPD", ignoreCase = true) -> RecordingSourceType.DASH
            bodyPrefix.isEmpty() -> RecordingSourceType.UNKNOWN
            else -> RecordingSourceType.TS
        }
    }

    private fun buildRequest(resolved: ResolvedStreamUrl, isHead: Boolean): Request {
        return Request.Builder()
            .url(resolved.url)
            .apply {
                effectiveHttpRequestProperties(resolved.headers, resolved.userAgent).forEach { (name, value) ->
                    header(name, value)
                }
                if (isHead) {
                    head()
                } else {
                    get()
                }
            }
            .build()
    }
}

private fun sourceTypeFromContainerExtension(extension: String?): RecordingSourceType? =
    when (extension?.trim()?.removePrefix(".")?.lowercase(Locale.ROOT)) {
        "ts", "mpegts", "mpeg-ts", "m2ts" -> RecordingSourceType.TS
        "m3u8" -> RecordingSourceType.HLS
        "mpd" -> RecordingSourceType.DASH
        else -> null
    }

private fun StreamType.toRecordingSourceType(): RecordingSourceType? = when (this) {
    StreamType.HLS -> RecordingSourceType.HLS
    StreamType.DASH -> RecordingSourceType.DASH
    StreamType.MPEG_TS -> RecordingSourceType.TS
    else -> null
}
