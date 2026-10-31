package com.streamvault.data.manager.recording

import android.content.ContentResolver
import android.util.Log
import com.streamvault.data.remote.http.useCancellableResponse
import com.streamvault.domain.util.LivePlaybackByteTap
import com.streamvault.domain.model.RecordingFailureCategory
import com.streamvault.domain.model.RecordingSourceType
import com.streamvault.domain.util.effectiveHttpRequestProperties
import com.streamvault.domain.util.livePlaybackRequestFingerprint
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

data class CaptureProgress(
    val bytesWritten: Long,
    val averageThroughputBytesPerSecond: Long,
    val lastProgressAtMs: Long,
    val retryCount: Int = 0
)

private const val MAX_TRANSIENT_RETRIES = 3
private const val RETRY_BACKOFF_BASE_MS = 15_000L
private const val STALL_TIMEOUT_MS = 60_000L
private const val HLS_SHARED_POLL_INTERVAL_MS = 500L
private const val MAX_HLS_RANGE_BYTES = 32 * 1024 * 1024L
private const val RECORDING_CAPTURE_TAG = "RecordingCapture"
private val HLS_CONTENT_RANGE_PATTERN = Regex("""bytes\s+(\d+)-(\d+)/(?:\d+|\*)""", RegexOption.IGNORE_CASE)

private fun checkRecordingResponse(response: okhttp3.Response, source: ResolvedRecordingSource) {
    if (response.isSuccessful) return
    if (response.code == 403) {
        val request = response.request
        val policyMode = source.networkProfile.playbackTransportPolicy?.mode?.name
            ?: if (source.networkProfile.allowInvalidSsl) "LEGACY_UNVERIFIED" else "STRICT"
        Log.w(
            RECORDING_CAPTURE_TAG,
            "Upstream denied recording with HTTP 403 host=${request.url.host} type=${source.sourceType} " +
                "ua=${request.header("User-Agent") != null} referer=${request.header("Referer") != null} " +
                "cookie=${request.header("Cookie") != null} auth=${request.header("Authorization") != null} " +
                "xua=${request.header("X-User-Agent") != null} proxy=${source.networkProfile.proxyHost.isNotBlank()} " +
                "transport=$policyMode"
        )
    }
    throw IOException("Recording stream failed with HTTP ${response.code}")
}

private fun isTransientFailure(error: Throwable): Boolean {
    val msg = error.message.orEmpty().lowercase(Locale.ROOT)
    return error is java.net.SocketTimeoutException ||
        error is java.net.SocketException ||
        error is java.net.ConnectException ||
        error is java.net.UnknownHostException ||
        (error is IOException && ("timeout" in msg || "reset" in msg || "broken pipe" in msg))
}

interface RecordingCaptureEngine {
    val sourceType: RecordingSourceType

    suspend fun capture(
        source: ResolvedRecordingSource,
        outputTarget: RecordingOutputTarget,
        contentResolver: ContentResolver,
        scheduledEndMs: Long,
        onProgress: suspend (CaptureProgress) -> Unit,
        maxVideoHeight: Int? = null
    )
}

@Singleton
class TsPassThroughCaptureEngine @Inject constructor(
    private val recordingHttpClientFactory: RecordingHttpClientFactory,
    private val livePlaybackByteTap: LivePlaybackByteTap
) : RecordingCaptureEngine {
    constructor(okHttpClient: OkHttpClient) : this(
        RecordingHttpClientFactory { okHttpClient },
        LivePlaybackByteTap()
    )

    override val sourceType: RecordingSourceType = RecordingSourceType.TS

    override suspend fun capture(
        source: ResolvedRecordingSource,
        outputTarget: RecordingOutputTarget,
        contentResolver: ContentResolver,
        scheduledEndMs: Long,
        onProgress: suspend (CaptureProgress) -> Unit,
        maxVideoHeight: Int?
    ) {
        val startMs = System.currentTimeMillis()
        var bytesWritten = 0L
        var retryCount = 0
        val client = recordingHttpClientFactory.create(source.networkProfile)
        val headers = effectiveHttpRequestProperties(source.headers, source.userAgent)
        val requestFingerprint = source.livePlaybackIdentity?.let {
            livePlaybackRequestFingerprint(source.url, headers)
        }
        val output = outputTarget.openOutputStream(contentResolver, append = false)
            ?: throw IOException("Could not open recording output target")
        output.use { sink ->
            suspend fun reportBytes(count: Int) {
                bytesWritten += count
                val now = System.currentTimeMillis()
                val elapsedMs = (now - startMs).coerceAtLeast(1L)
                onProgress(
                    CaptureProgress(
                        bytesWritten = bytesWritten,
                        averageThroughputBytesPerSecond = (bytesWritten * 1000L) / elapsedMs,
                        lastProgressAtMs = now,
                        retryCount = retryCount
                    )
                )
            }

            val sharedCapture = source.livePlaybackIdentity?.let { identity ->
                requestFingerprint?.let { fingerprint -> livePlaybackByteTap.attach(identity, fingerprint) }
            }
            if (sharedCapture != null) {
                sharedCapture.use { capture ->
                    while (kotlinx.coroutines.currentCoroutineContext().isActive &&
                        System.currentTimeMillis() < scheduledEndMs
                    ) {
                        val chunk = withTimeoutOrNull(STALL_TIMEOUT_MS) { capture.readChunk() }
                            ?: break // Playback ended or stopped delivering; finish through the direct path.
                        if (chunk.isEmpty()) continue
                        sink.write(chunk)
                        reportBytes(chunk.size)
                    }
                    sink.flush()
                }
            }

            while (kotlinx.coroutines.currentCoroutineContext().isActive && System.currentTimeMillis() < scheduledEndMs) {
                try {
                    val request = Request.Builder().url(source.url).apply {
                        headers.forEach { (key, value) -> header(key, value) }
                    }.build()
                    client.newCall(request).useCancellableResponse { response ->
                        checkRecordingResponse(response, source)
                        val body = response.body ?: throw IOException("Recording stream returned an empty body")
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var lastDataAt = System.currentTimeMillis()
                            while (true) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                if (scheduledEndMs <= System.currentTimeMillis()) break
                                if (System.currentTimeMillis() - lastDataAt > STALL_TIMEOUT_MS) {
                                    throw IOException("Recording stream stalled — no data received for ${STALL_TIMEOUT_MS / 1000}s")
                                }
                                val read = input.read(buffer)
                                if (read <= 0) break
                                lastDataAt = System.currentTimeMillis()
                                sink.write(buffer, 0, read)
                                reportBytes(read)
                            }
                            sink.flush()
                        }
                    }
                    break // Normal completion
                } catch (e: Throwable) {
                    if (!isTransientFailure(e) || retryCount >= MAX_TRANSIENT_RETRIES) throw e
                    retryCount++
                    val backoff = RETRY_BACKOFF_BASE_MS * (1L shl (retryCount - 1).coerceAtMost(3))
                    delay(backoff.coerceAtMost(scheduledEndMs - System.currentTimeMillis()).coerceAtLeast(0))
                }
            }
        }
    }
}

@Singleton
class HlsLiveCaptureEngine @Inject constructor(
    private val recordingHttpClientFactory: RecordingHttpClientFactory,
    private val livePlaybackByteTap: LivePlaybackByteTap
) : RecordingCaptureEngine {
    constructor(okHttpClient: OkHttpClient) : this(
        RecordingHttpClientFactory { okHttpClient },
        LivePlaybackByteTap()
    )

    override val sourceType: RecordingSourceType = RecordingSourceType.HLS

    override suspend fun capture(
        source: ResolvedRecordingSource,
        outputTarget: RecordingOutputTarget,
        contentResolver: ContentResolver,
        scheduledEndMs: Long,
        onProgress: suspend (CaptureProgress) -> Unit,
        maxVideoHeight: Int?
    ) {
        val startMs = System.currentTimeMillis()
        var bytesWritten = 0L
        var retryCount = 0
        val client = recordingHttpClientFactory.create(source.networkProfile)
        val headers = effectiveHttpRequestProperties(source.headers, source.userAgent)
        val requestFingerprint = source.livePlaybackIdentity?.let {
            livePlaybackRequestFingerprint(source.url, headers)
        }
        val output = outputTarget.openOutputStream(contentResolver)
            ?: throw IOException("Could not open recording output target")
        var sharedHlsCapture = source.livePlaybackIdentity?.let { identity ->
            requestFingerprint?.let { livePlaybackByteTap.attachHls(identity, it) }
        }
        Log.i(
            RECORDING_CAPTURE_TAG,
            if (sharedHlsCapture != null) {
                "HLS recording source=foreground-playback"
            } else {
                "HLS recording source=direct-upstream"
            }
        )
        try {
            output.use { sink ->
                var currentPlaylistUrl = source.url
                var activeVariantUrls = emptyList<String>()
                val maxSeenSegments = 10_000
                val seenSegments = object : LinkedHashSet<String>() {
                    override fun add(element: String): Boolean {
                        val added = super.add(element)
                        if (size > maxSeenSegments) remove(first())
                        return added
                    }
                }
                var lastWrittenMapIdentity: String? = null
                var sharedRecordingStarted = false
                val byteRangeState = HlsByteRangeState()
                var lastDataAt = System.currentTimeMillis()
                while (kotlinx.coroutines.currentCoroutineContext().isActive && System.currentTimeMillis() < scheduledEndMs) {
                    val now = System.currentTimeMillis()
                    val sharedCapture = sharedHlsCapture
                    if (now - lastDataAt > STALL_TIMEOUT_MS &&
                        (sharedCapture == null || sharedCapture.isPlaybackActive())
                    ) {
                        throw IOException("HLS recording stalled — no new segments for ${STALL_TIMEOUT_MS / 1000}s")
                    }
                    sharedCapture?.throwIfFailed()
                    if (sharedCapture != null && !sharedCapture.isPlaybackActive()) {
                        if (now - lastDataAt > STALL_TIMEOUT_MS) {
                            sharedCapture.close()
                            sharedHlsCapture = null
                            activeVariantUrls = emptyList()
                            currentPlaylistUrl = source.url
                            retryCount = 0
                            Log.i(RECORDING_CAPTURE_TAG, "HLS recording source=direct-upstream reason=playback-inactive")
                        } else {
                            delay(HLS_SHARED_POLL_INTERVAL_MS)
                            continue
                        }
                    }
                    val activeCapture = sharedHlsCapture
                    if (activeCapture != null && activeVariantUrls.isNotEmpty()) {
                        activeCapture.newestResource(activeVariantUrls)?.let { activeVariant ->
                            currentPlaylistUrl = activeVariant.url
                        }
                    }
                    val sharedPlaylist = activeCapture?.resource(currentPlaylistUrl)
                    if (activeCapture != null && sharedPlaylist == null) {
                        delay(HLS_SHARED_POLL_INTERVAL_MS)
                        continue
                    }
                    val playlistText = if (activeCapture != null) {
                        requireNotNull(sharedPlaylist).bytes.toString(Charsets.UTF_8)
                    } else {
                        try {
                            fetchText(currentPlaylistUrl, headers, client, source)
                        } catch (e: Throwable) {
                            if (!isTransientFailure(e) || retryCount >= MAX_TRANSIENT_RETRIES) throw e
                            retryCount++
                            delay(RETRY_BACKOFF_BASE_MS * (1L shl (retryCount - 1).coerceAtMost(3)))
                            continue
                        }
                    }
                    val playlistBaseUrl = sharedPlaylist?.url ?: currentPlaylistUrl
                    val playlist = parsePlaylist(playlistBaseUrl, playlistText, maxVideoHeight, byteRangeState)
                    when (playlist) {
                        is ParsedHlsPlaylist.Master -> {
                            activeVariantUrls = playlist.variantUrls
                            currentPlaylistUrl = activeCapture
                                ?.newestResource(playlist.variantUrls)
                                ?.url
                                ?: playlist.bestVariantUrl
                        }
                        is ParsedHlsPlaylist.Media -> {
                            // Validate that all encryption methods are supported
                            if (playlist.segments.any { segment ->
                                    segment.initializationSection?.key?.let { key ->
                                        key.method.equals("AES-128", ignoreCase = true) && key.iv == null
                                    } == true
                                }
                            ) {
                                throw UnsupportedRecordingException(
                                    "This HLS stream uses unsupported DRM or encryption.",
                                    RecordingFailureCategory.DRM_UNSUPPORTED
                                )
                            }
                            playlist.segments.flatMap { segment ->
                                listOfNotNull(segment.key, segment.initializationSection?.key)
                            }
                                .distinctBy { it.uri }
                                .forEach { key ->
                                    if (!key.method.equals("NONE", ignoreCase = true) &&
                                        !key.method.equals("AES-128", ignoreCase = true)
                                    ) {
                                        throw UnsupportedRecordingException(
                                            "This HLS stream uses unsupported DRM or encryption.",
                                            RecordingFailureCategory.DRM_UNSUPPORTED
                                        )
                                    }
                                }
                            // Cache key bytes by URI so rotated keys are fetched once each
                            val keyCache = mutableMapOf<String, ByteArray>()
                            var newSegmentsThisRound = false
                            val skippedBeforeStart = ArrayList<String>()
                            for (segment in playlist.segments) {
                                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                                val rangeIdentity = segment.byteRange?.let { "@${it.offset}:${it.length}" }.orEmpty()
                                val segmentIdentity = "${segment.mediaSequenceNumber}:${segment.uri}$rangeIdentity"
                                if (segmentIdentity in seenSegments) continue
                                activeCapture?.throwIfFailed()
                                val segmentRangeStart = segment.byteRange?.offset ?: 0L
                                val segmentRangeLength = segment.byteRange?.length ?: -1L
                                val capturedSegment = activeCapture?.recordingResource(
                                    segment.uri,
                                    segmentRangeStart,
                                    segmentRangeLength
                                )
                                val bytes = if (activeCapture != null) {
                                    if (capturedSegment == null) {
                                        // The foreground player may already have downloaded the
                                        // beginning of this live window before recording attached.
                                        // Choose the first available post-attachment segment once;
                                        // after writing starts, every missing segment must be awaited.
                                        if (!sharedRecordingStarted) {
                                            skippedBeforeStart += segmentIdentity
                                            continue
                                        } else break
                                    }
                                    capturedSegment.bytes
                                } else {
                                    try {
                                        fetchBytes(segment.uri, headers, client, source, segment.byteRange)
                                    } catch (e: Throwable) {
                                        if (!isTransientFailure(e) || retryCount >= MAX_TRANSIENT_RETRIES) throw e
                                        retryCount++
                                        break // Retry this segment on the next playlist refresh.
                                    }
                                }
                                val segKey = segment.key?.takeIf { it.method.equals("AES-128", ignoreCase = true) }
                                val payload = if (segKey != null) {
                                    val keyBytes = try {
                                        resolveKeyBytes(segKey, activeCapture, keyCache, headers, client, source)
                                    } catch (e: Throwable) {
                                        if (!isTransientFailure(e) || retryCount >= MAX_TRANSIENT_RETRIES) throw e
                                        retryCount++
                                        break
                                    } ?: break
                                    decryptAes128(bytes, keyBytes, segKey.iv, segment.mediaSequenceNumber)
                                } else {
                                    bytes
                                }
                                val map = segment.initializationSection
                                val mapIdentity = map?.let { section ->
                                    val mapRangeIdentity = section.byteRange
                                        ?.let { "@${it.offset}:${it.length}" }
                                        .orEmpty()
                                    val mapKeyIdentity = section.key?.let { key ->
                                        "|${key.method}:${key.uri}:${key.iv.orEmpty()}"
                                    }.orEmpty()
                                    "${section.uri}$mapRangeIdentity$mapKeyIdentity"
                                }
                                val mapRangeStart = map?.byteRange?.offset ?: 0L
                                val mapRangeLength = map?.byteRange?.length ?: -1L
                                val capturedMap = if (map != null) {
                                    activeCapture?.resource(map.uri, mapRangeStart, mapRangeLength)
                                } else {
                                    null
                                }
                                val initializationPayload = if (map != null && mapIdentity != lastWrittenMapIdentity) {
                                    val mapBytes = if (activeCapture != null) {
                                        capturedMap?.bytes ?: break
                                    } else {
                                        try {
                                            fetchBytes(map.uri, headers, client, source, map.byteRange)
                                        } catch (e: Throwable) {
                                            if (!isTransientFailure(e) || retryCount >= MAX_TRANSIENT_RETRIES) throw e
                                            retryCount++
                                            break
                                        }
                                    }
                                    val mapKey = map.key?.takeIf { it.method.equals("AES-128", ignoreCase = true) }
                                    if (mapKey == null) {
                                        mapBytes
                                    } else {
                                        val mapKeyBytes = try {
                                            resolveKeyBytes(mapKey, activeCapture, keyCache, headers, client, source)
                                        } catch (e: Throwable) {
                                            if (!isTransientFailure(e) || retryCount >= MAX_TRANSIENT_RETRIES) throw e
                                            retryCount++
                                            break
                                        } ?: break
                                        decryptAes128(
                                            mapBytes,
                                            mapKeyBytes,
                                            mapKey.iv,
                                            map.mediaSequenceNumber
                                        )
                                    }
                                } else {
                                    null
                                }
                                initializationPayload?.let { initBytes ->
                                    sink.write(initBytes)
                                    bytesWritten += initBytes.size
                                    lastWrittenMapIdentity = mapIdentity
                                }
                                capturedMap?.let { activeCapture?.consume(it) }
                                sink.write(payload)
                                if (activeCapture != null && !sharedRecordingStarted) {
                                    seenSegments.addAll(skippedBeforeStart)
                                    sharedRecordingStarted = true
                                }
                                seenSegments += segmentIdentity
                                capturedSegment?.let { activeCapture?.consume(it) }
                                bytesWritten += payload.size
                                newSegmentsThisRound = true
                                lastDataAt = System.currentTimeMillis()
                                val elapsedMs = (System.currentTimeMillis() - startMs).coerceAtLeast(1L)
                                onProgress(
                                    CaptureProgress(
                                        bytesWritten = bytesWritten,
                                        averageThroughputBytesPerSecond = (bytesWritten * 1000L) / elapsedMs,
                                        lastProgressAtMs = System.currentTimeMillis(),
                                        retryCount = retryCount
                                    )
                                )
                                if (System.currentTimeMillis() >= scheduledEndMs) break
                            }
                            sink.flush()
                            if (newSegmentsThisRound) retryCount = 0
                            if (playlist.endList) break
                            delay((playlist.targetDurationSeconds.coerceAtLeast(2) * 1000L) / 2L)
                        }
                    }
                }
            }
        } finally {
            sharedHlsCapture?.close()
        }
    }

    private suspend fun fetchText(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        source: ResolvedRecordingSource
    ): String {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
        }.build()
        client.newCall(request).useCancellableResponse { response ->
            checkRecordingResponse(response, source)
            return response.body?.string().orEmpty()
        }
    }

    private suspend fun fetchBytes(
        url: String,
        headers: Map<String, String>,
        client: OkHttpClient,
        source: ResolvedRecordingSource,
        byteRange: HlsByteRange? = null
    ): ByteArray {
        val expectedRangeEndExclusive = byteRange?.let { range ->
            if (range.offset < 0L || range.length <= 0L || range.length > MAX_HLS_RANGE_BYTES) {
                throw IOException()
            }
            runCatching { Math.addExact(range.offset, range.length) }
                .getOrElse { throw IOException() }
        }
        val request = Request.Builder().url(url).apply {
            headers.forEach { (key, value) -> header(key, value) }
            byteRange?.let { range ->
                val endInclusive = requireNotNull(expectedRangeEndExclusive) - 1L
                header("Range", "bytes=${range.offset}-$endInclusive")
            }
        }.build()
        client.newCall(request).useCancellableResponse { response ->
            checkRecordingResponse(response, source)
            val body = response.body ?: if (byteRange == null) return ByteArray(0) else throw IOException()
            if (byteRange == null) return body.bytes()
            val endExclusive = requireNotNull(expectedRangeEndExclusive)
            if (response.code == 206) {
                val bytes = body.bytes()
                response.header("Content-Range")?.let { contentRange ->
                    val match = HLS_CONTENT_RANGE_PATTERN.matchEntire(contentRange.trim()) ?: throw IOException()
                    val actualStart = match.groupValues[1].toLongOrNull() ?: throw IOException()
                    val actualEndInclusive = match.groupValues[2].toLongOrNull() ?: throw IOException()
                    if (actualStart != byteRange.offset || actualEndInclusive != endExclusive - 1L) {
                        throw IOException()
                    }
                }
                if (bytes.size.toLong() != byteRange.length) throw IOException()
                return bytes
            }
            return body.byteStream().use { input ->
                readByteRange(input, byteRange.offset, byteRange.length)
            }
        }
    }

    private fun readByteRange(input: InputStream, offset: Long, length: Long): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var skipped = 0L
        while (skipped < offset) {
            val requestedSkip = offset - skipped
            val count = input.skip(requestedSkip)
            if (count > 0L) {
                skipped += count
            } else {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), requestedSkip).toInt())
                if (read < 0) throw IOException()
                skipped += read
            }
        }

        val output = ByteArrayOutputStream(minOf(length, DEFAULT_BUFFER_SIZE.toLong()).toInt())
        var remaining = length
        while (remaining > 0L) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw IOException()
            output.write(buffer, 0, read)
            remaining -= read
        }
        return output.toByteArray()
    }

    private suspend fun resolveKeyBytes(
        key: HlsKey,
        capture: LivePlaybackByteTap.HlsCapture?,
        keyCache: MutableMap<String, ByteArray>,
        headers: Map<String, String>,
        client: OkHttpClient,
        source: ResolvedRecordingSource
    ): ByteArray? {
        keyCache[key.uri]?.let { return it }
        val bytes = if (capture != null) {
            capture.throwIfFailed()
            capture.resource(key.uri)?.bytes
        } else {
            fetchBytes(key.uri, headers, client, source)
        } ?: return null
        keyCache[key.uri] = bytes
        return bytes
    }

    private fun decryptAes128(payload: ByteArray, keyBytes: ByteArray, ivHex: String?, mediaSequenceNumber: Long): ByteArray {
        val iv = if (ivHex != null) {
            ivHex.removePrefix("0x")
                .chunked(2)
                .mapNotNull { it.toIntOrNull(16)?.toByte() }
                .toByteArray()
                .takeIf { it.size == 16 }
                ?: throw IOException("Malformed AES-128 IV in HLS key: $ivHex")
        } else {
            // Per RFC 8216 §5.2: use media sequence number as big-endian 128-bit integer
            java.nio.ByteBuffer.allocate(16).apply {
                position(8)
                putLong(mediaSequenceNumber)
            }.array()
        }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(payload)
    }

    private fun parsePlaylist(
        baseUrl: String,
        rawText: String,
        maxVideoHeight: Int? = null,
        byteRangeState: HlsByteRangeState
    ): ParsedHlsPlaylist {
        val lines = rawText.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
        if (lines.any { it.startsWith("#EXT-X-STREAM-INF", ignoreCase = true) }) {
            data class HlsVariant(val bandwidth: Int, val height: Int?, val url: String)
            val variants = mutableListOf<HlsVariant>()
            lines.forEachIndexed { index, line ->
                if (line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                    val bandwidth = Regex("""BANDWIDTH=(\d+)""")
                        .find(line)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
                    val height = Regex("""RESOLUTION=\d+x(\d+)""")
                        .find(line)
                        ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    val next = lines.getOrNull(index + 1)?.takeIf { !it.startsWith("#") } ?: return@forEachIndexed
                    variants += HlsVariant(bandwidth, height, resolveRelativeUrl(baseUrl, next))
                }
            }
            val eligible = if (maxVideoHeight != null) {
                variants.filter { it.height == null || it.height <= maxVideoHeight }.ifEmpty { variants }
            } else {
                variants
            }
            val bestVariantUrl = eligible.maxByOrNull { it.bandwidth }?.url
                ?: throw IOException("No playable HLS variants were available.")
            return ParsedHlsPlaylist.Master(
                bestVariantUrl = bestVariantUrl,
                variantUrls = variants.map { it.url }
            )
        }

        var targetDuration = 6
        var endList = false
        var currentKey: HlsKey? = null
        var currentMap: HlsInitializationSection? = null
        var mediaSequence = 0L
        val segments = mutableListOf<HlsSegment>()
        val previousSegmentRangeEndByUri = mutableMapOf<String, Long>()
        val previousMapRangeEndByUri = mutableMapOf<String, Long>()
        val mapRangeOccurrenceCounts = mutableMapOf<String, Int>()
        var pendingSegmentByteRange: HlsByteRangeDeclaration? = null
        var segmentIndex = 0L
        lines.forEach { line ->
            when {
                line.startsWith("#EXT-X-TARGETDURATION", ignoreCase = true) -> {
                    targetDuration = line.substringAfter(':', "6").toIntOrNull() ?: 6
                }
                line.startsWith("#EXT-X-MEDIA-SEQUENCE", ignoreCase = true) -> {
                    mediaSequence = line.substringAfter(':', "0").toLongOrNull() ?: 0L
                    segmentIndex = 0L
                }
                line.startsWith("#EXT-X-ENDLIST", ignoreCase = true) -> {
                    endList = true
                }
                line.startsWith("#EXT-X-KEY", ignoreCase = true) -> {
                    val attrs = parseHlsAttributes(line.substringAfter(':'))
                    currentKey = HlsKey(
                        method = attrs["METHOD"].orEmpty(),
                        uri = attrs["URI"]?.let { resolveRelativeUrl(baseUrl, it) }.orEmpty(),
                        iv = attrs["IV"]
                    )
                }
                line.startsWith("#EXT-X-MAP", ignoreCase = true) -> {
                    val attrs = parseHlsAttributes(line.substringAfter(':'))
                    val mapUrl = attrs["URI"]
                        ?.let { resolveRelativeUrl(baseUrl, it) }
                        ?: throw IOException()
                    val mapRange = attrs["BYTERANGE"]?.let { range ->
                        byteRangeState.resolveMapRange(
                            declaration = parseByteRangeDeclaration(range),
                            resourceUrl = mapUrl,
                            previousRangeEnd = previousMapRangeEndByUri[mapUrl],
                            key = currentKey,
                            occurrenceCounts = mapRangeOccurrenceCounts
                        )
                    }
                    mapRange?.let { previousMapRangeEndByUri[mapUrl] = it.endExclusive() }
                    currentMap = HlsInitializationSection(
                        uri = mapUrl,
                        byteRange = mapRange,
                        key = currentKey?.copy(),
                        mediaSequenceNumber = mediaSequence
                    )
                }
                line.startsWith("#EXT-X-BYTERANGE", ignoreCase = true) -> {
                    pendingSegmentByteRange = parseByteRangeDeclaration(line.substringAfter(':'))
                }
                line.startsWith("#") -> Unit
                else -> {
                    val segmentUrl = resolveRelativeUrl(baseUrl, line)
                    val segmentSequence = mediaSequence + segmentIndex
                    val segmentRange = pendingSegmentByteRange?.let { declaration ->
                        byteRangeState.resolveSegmentRange(
                            declaration = declaration,
                            resourceUrl = segmentUrl,
                            sequenceNumber = segmentSequence,
                            previousRangeEnd = previousSegmentRangeEndByUri[segmentUrl]
                        )
                    }
                    segmentRange?.let { previousSegmentRangeEndByUri[segmentUrl] = it.endExclusive() }
                    segments += HlsSegment(
                        uri = segmentUrl,
                        key = currentKey?.copy(),
                        mediaSequenceNumber = segmentSequence,
                        byteRange = segmentRange,
                        initializationSection = currentMap?.copy()
                    )
                    pendingSegmentByteRange = null
                    segmentIndex++
                }
            }
        }
        return ParsedHlsPlaylist.Media(
            targetDurationSeconds = targetDuration,
            endList = endList,
            segments = segments
        )
    }

    private fun parseHlsAttributes(raw: String): Map<String, String> {
        val attrs = mutableMapOf<String, String>()
        var cursor = 0
        while (cursor < raw.length) {
            while (cursor < raw.length && (raw[cursor] == ',' || raw[cursor].isWhitespace())) cursor++
            val keyStart = cursor
            while (cursor < raw.length && raw[cursor] != '=' && raw[cursor] != ',') cursor++
            val key = raw.substring(keyStart, cursor).trim()
            if (cursor >= raw.length || raw[cursor] != '=') {
                while (cursor < raw.length && raw[cursor] != ',') cursor++
                continue
            }
            cursor++
            while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++
            val value = if (cursor < raw.length && raw[cursor] == '"') {
                cursor++
                val valueStart = cursor
                while (cursor < raw.length && raw[cursor] != '"') {
                    if (raw[cursor] == '\\' && cursor + 1 < raw.length) cursor++
                    cursor++
                }
                raw.substring(valueStart, cursor).replace("\\\"", "\"").also {
                    if (cursor < raw.length) cursor++
                }
            } else {
                val valueStart = cursor
                while (cursor < raw.length && raw[cursor] != ',') cursor++
                raw.substring(valueStart, cursor).trim()
            }
            if (key.isNotBlank()) attrs[key.uppercase(Locale.ROOT)] = value
        }
        return attrs
    }

    private fun parseByteRangeDeclaration(raw: String): HlsByteRangeDeclaration {
        val length = raw.substringBefore('@').trim().toLongOrNull()
            ?.takeIf { it > 0L }
            ?: throw IOException()
        val offset = raw.substringAfter('@', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.trim()
            ?.toLongOrNull()
            ?.takeIf { it >= 0L }
        if ('@' in raw && offset == null) throw IOException()
        return HlsByteRangeDeclaration(length = length, offset = offset)
    }

    private fun resolveRelativeUrl(baseUrl: String, value: String): String {
        return runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
    }
}

private sealed interface ParsedHlsPlaylist {
    data class Master(
        val bestVariantUrl: String,
        val variantUrls: List<String>
    ) : ParsedHlsPlaylist
    data class Media(
        val targetDurationSeconds: Int,
        val endList: Boolean,
        val segments: List<HlsSegment>
    ) : ParsedHlsPlaylist
}

private data class HlsSegment(
    val uri: String,
    val key: HlsKey? = null,
    val mediaSequenceNumber: Long = 0,
    val byteRange: HlsByteRange? = null,
    val initializationSection: HlsInitializationSection? = null
)

private data class HlsInitializationSection(
    val uri: String,
    val byteRange: HlsByteRange? = null,
    val key: HlsKey? = null,
    val mediaSequenceNumber: Long = 0
)

private data class HlsByteRange(
    val offset: Long,
    val length: Long
) {
    fun endExclusive(): Long = runCatching { Math.addExact(offset, length) }
        .getOrElse { throw IOException() }
}

private data class HlsByteRangeDeclaration(
    val length: Long,
    val offset: Long?
)

private data class HlsSegmentRangeKey(
    val sequenceNumber: Long,
    val resourceUrl: String
)

private class HlsByteRangeState {
    private val segmentRanges = LinkedHashMap<HlsSegmentRangeKey, HlsByteRange>()
    private val latestSegmentRangeByUrl = LinkedHashMap<String, Pair<Long, HlsByteRange>>()
    private val mapRangesBySignature = LinkedHashMap<String, MutableList<HlsByteRange>>()
    private val latestMapRangeByUrl = LinkedHashMap<String, HlsByteRange>()

    fun resolveSegmentRange(
        declaration: HlsByteRangeDeclaration,
        resourceUrl: String,
        sequenceNumber: Long,
        previousRangeEnd: Long?
    ): HlsByteRange {
        val key = HlsSegmentRangeKey(sequenceNumber, resourceUrl)
        val knownRange = segmentRanges[key]
        val priorRangeEnd = previousRangeEnd
            ?: latestSegmentRangeByUrl[resourceUrl]
                ?.takeIf { (knownSequence, _) -> knownSequence < sequenceNumber }
                ?.second
                ?.endExclusive()
        val range = resolveRange(declaration, knownRange, priorRangeEnd)
        segmentRanges[key] = range
        while (segmentRanges.size > MAX_RANGE_HISTORY) {
            segmentRanges.remove(segmentRanges.keys.first())
        }
        val latest = latestSegmentRangeByUrl[resourceUrl]
        if (latest == null || sequenceNumber >= latest.first) {
            latestSegmentRangeByUrl[resourceUrl] = sequenceNumber to range
        }
        while (latestSegmentRangeByUrl.size > MAX_RANGE_HISTORY) {
            latestSegmentRangeByUrl.remove(latestSegmentRangeByUrl.keys.first())
        }
        return range
    }

    fun resolveMapRange(
        declaration: HlsByteRangeDeclaration,
        resourceUrl: String,
        previousRangeEnd: Long?,
        key: HlsKey?,
        occurrenceCounts: MutableMap<String, Int>
    ): HlsByteRange {
        val signature = listOf(
            resourceUrl,
            declaration.length.toString(),
            key?.method.orEmpty(),
            key?.uri.orEmpty(),
            key?.iv.orEmpty()
        ).joinToString("\u0000")
        val occurrence = occurrenceCounts.getOrDefault(signature, 0)
        occurrenceCounts[signature] = occurrence + 1
        val knownRange = mapRangesBySignature[signature]?.getOrNull(occurrence)
        val offset = declaration.offset
            ?: previousRangeEnd
            ?: knownRange?.offset
            ?: latestMapRangeByUrl[resourceUrl]?.endExclusive()
            ?: throw IOException()
        val range = HlsByteRange(offset, declaration.length).also { it.endExclusive() }
        val rangesForSignature = mapRangesBySignature.getOrPut(signature) { mutableListOf() }
        while (rangesForSignature.size <= occurrence) rangesForSignature += range
        rangesForSignature[occurrence] = range
        latestMapRangeByUrl[resourceUrl] = range
        while (mapRangesBySignature.size > MAX_RANGE_HISTORY) {
            mapRangesBySignature.remove(mapRangesBySignature.keys.first())
        }
        while (latestMapRangeByUrl.size > MAX_RANGE_HISTORY) {
            latestMapRangeByUrl.remove(latestMapRangeByUrl.keys.first())
        }
        return range
    }

    private fun resolveRange(
        declaration: HlsByteRangeDeclaration,
        knownRange: HlsByteRange?,
        previousRangeEnd: Long?
    ): HlsByteRange {
        val offset = declaration.offset ?: knownRange?.offset ?: previousRangeEnd ?: throw IOException()
        return HlsByteRange(offset, declaration.length).also { it.endExclusive() }
    }

    private companion object {
        const val MAX_RANGE_HISTORY = 10_000
    }
}

private data class HlsKey(
    val method: String,
    val uri: String,
    val iv: String? = null
)

class UnsupportedRecordingException(
    message: String,
    val category: RecordingFailureCategory
) : IOException(message)
