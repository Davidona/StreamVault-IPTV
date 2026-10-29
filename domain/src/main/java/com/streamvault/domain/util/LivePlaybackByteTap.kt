package com.streamvault.domain.util

import com.streamvault.domain.model.LivePlaybackIdentity
import java.io.Closeable
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.Channel

private data class LivePlaybackSubscriber(
    val id: Long,
    val channel: Channel<ByteArray>,
    var bytesToPacketBoundary: Int
)

private data class CachedHlsResource(
    val id: Long,
    val url: String,
    val aliases: Set<HlsResourceKey>,
    val bytes: ByteArray,
    val updatedAtMs: Long,
    val isPlaylist: Boolean,
    val isMediaSegment: Boolean,
    val wasReferencedDependency: Boolean,
    val references: HlsPlaylistReferences?
)

private data class HlsPlaylistReferences(val media: Set<String>, val dependencies: Set<String>)

/** Identify resource roles conservatively before reclaiming media or consumed dependencies. */
private fun hlsPlaylistReferences(baseUrl: String, bytes: ByteArray): HlsPlaylistReferences? = runCatching {
    require(bytes.looksLikeHlsPlaylist())
    val base = URI(baseUrl)
    val media = HashSet<String>()
    val dependencies = HashSet<String>()
    var mediaUriExpected = false
    bytes.toString(Charsets.UTF_8).lineSequence().forEach { rawLine ->
        val line = rawLine.trim().removePrefix("\uFEFF")
        if (line.startsWith("#EXTINF:")) mediaUriExpected = true
        if (line.startsWith("#") && ':' in line) {
            val attributes = line.substringAfter(':')
            val uris = HLS_URI_ATTRIBUTE.findAll(attributes).map { it.groupValues[1] }.toList()
            // Unknown/unresolved attribute syntax must not make a dependency evictable.
            if ("URI=" in attributes) require(uris.isNotEmpty())
            uris.forEach { dependencies.add(base.resolve(it).toString()) }
        } else if (line.isNotEmpty() && !line.startsWith("#")) {
            if (mediaUriExpected) media.add(base.resolve(line).toString())
            mediaUriExpected = false
        }
    }
    HlsPlaylistReferences(media, dependencies)
}.getOrNull()

private val HLS_URI_ATTRIBUTE = Regex("(?:^|,)URI=\"([^\"]*)\"")

private data class HlsResourceKey(
    val url: String,
    val rangeStart: Long,
    val rangeLength: Long
)

private fun ByteArray.looksLikeHlsPlaylist(): Boolean {
    var offset = if (
        size >= 3 && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()
    ) {
        3
    } else {
        0
    }
    while (offset < size && this[offset].toInt() in HLS_WHITESPACE_BYTES) offset++
    val marker = "#EXTM3U"
    if (size - offset < marker.length) return false
    return marker.indices.all { index -> this[offset + index].toInt().toChar() == marker[index] }
}

private fun ByteArray.looksLikeText(): Boolean = all { byte ->
    val value = byte.toInt() and 0xFF
    value in 0x09..0x0D || value in 0x20..0x7E
}

private val HLS_WHITESPACE_BYTES = setOf(' '.code, '\t'.code, '\n'.code, '\r'.code)

/** Accumulates responses without copying all earlier bytes whenever the buffer grows. */
private class HlsResponseBuffer {
    private val chunks = ArrayList<ByteArray>()
    private var tailSize = 0
    var size: Int = 0
        private set

    fun write(bytes: ByteArray, offset: Int, length: Int) {
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            if (chunks.isEmpty() || tailSize == chunks.last().size) {
                // Small playlists and keys do not need a full segment-sized chunk.
                chunks.add(ByteArray(if (chunks.isEmpty()) remaining.coerceIn(4096, 65536) else 65536))
                tailSize = 0
            }
            val tail = chunks.last()
            val count = minOf(remaining, tail.size - tailSize)
            bytes.copyInto(tail, tailSize, sourceOffset, sourceOffset + count)
            tailSize += count
            sourceOffset += count
            remaining -= count
            size += count
        }
    }

    fun takeBytes(): ByteArray {
        val result = if (chunks.size == 1 && tailSize == chunks[0].size) {
            chunks[0]
        } else {
            ByteArray(size).also { bytes ->
                var offset = 0
                chunks.forEachIndexed { index, chunk ->
                    val count = if (index == chunks.lastIndex) tailSize else chunk.size
                    chunk.copyInto(bytes, offset, 0, count)
                    offset += count
                }
            }
        }
        clear()
        return result
    }

    fun clear() {
        chunks.clear()
        tailSize = 0
        size = 0
    }
}

class LivePlaybackCaptureBackpressureException : IOException(
    "Live playback capture exceeded its bounded buffer."
)

/**
 * Shares foreground live data with matching recordings without opening another upstream request.
 * MPEG-TS uses bounded queues and HLS uses a bounded response cache. Capture failure is explicit
 * instead of dropping data or making the player wait for the recording output.
 */
@Singleton
class LivePlaybackByteTap @Inject constructor() {
    private data class SourceKey(
        val identity: LivePlaybackIdentity,
        val requestFingerprint: String
    )

    interface Publisher : Closeable {
        fun publish(buffer: ByteArray, offset: Int, length: Int)
    }

    interface Capture : Closeable {
        suspend fun readChunk(): ByteArray?
    }

    interface HlsPublisher : Closeable {
        fun publish(buffer: ByteArray, offset: Int, length: Int)
        fun complete()
    }

    interface HlsCapture : Closeable {
        fun resource(url: String, rangeStart: Long = 0L, rangeLength: Long = -1L): HlsResource?
        fun newestResource(urls: Collection<String>): HlsResource?
        fun recordingResource(url: String, rangeStart: Long = 0L, rangeLength: Long = -1L): HlsResource?
        fun consume(resource: HlsResource)
        fun isPlaybackActive(): Boolean
        fun throwIfFailed()
    }

    data class HlsResource(
        val resourceId: Long,
        val url: String,
        val bytes: ByteArray,
        val updatedAtMs: Long,
        val isPlaylist: Boolean,
        val rangeStart: Long = 0L,
        val rangeLength: Long = -1L
    )

    private val activeSources = HashMap<SourceKey, SourceSession>()
    private val activeHlsSources = HashMap<SourceKey, HlsSourceSession>()
    private val hlsCleanupExecutor = ScheduledThreadPoolExecutor(1) { task ->
        Thread(task, "LivePlaybackHlsTapCleanup").apply { isDaemon = true }
    }

    fun begin(
        identity: LivePlaybackIdentity,
        requestFingerprint: String
    ): Publisher {
        val key = SourceKey(identity, requestFingerprint)
        val session = SourceSession(key)
        val replaced = synchronized(activeSources) { activeSources.put(key, session) }
        replaced?.finish()
        return session
    }

    fun attach(
        identity: LivePlaybackIdentity,
        requestFingerprint: String
    ): Capture? {
        val session = synchronized(activeSources) {
            activeSources[SourceKey(identity, requestFingerprint)]
        } ?: return null
        return session.attach()
    }

    /**
     * Publishes one complete HLS playlist, segment, or key response from foreground playback.
     * HLS resources are cached because playlist parsing and segment assembly happen at a different
     * pace from Media3's requests. The cache is bounded and never blocks the player thread.
     */
    fun beginHlsResource(
        identity: LivePlaybackIdentity,
        requestFingerprint: String,
        requestedUrl: String,
        resolvedUrl: String,
        isPlaylist: Boolean,
        rangeStart: Long = 0L,
        rangeLength: Long = -1L
    ): HlsPublisher {
        val key = SourceKey(identity, requestFingerprint)
        val nowMs = elapsedRealtimeMs()
        val session = synchronized(activeHlsSources) {
            activeHlsSources.entries.removeAll { (activeKey, activeSession) ->
                activeKey != key && activeSession.isExpiredForRegistry(nowMs)
            }
            activeHlsSources[key]
                ?.takeIf { it.canReuse(nowMs) }
                ?: HlsSourceSession(key).also { activeHlsSources[key] = it }
        }
        return session.beginResource(
            requestedUrl = requestedUrl,
            resolvedUrl = resolvedUrl,
            isPlaylist = isPlaylist,
            rangeStart = rangeStart,
            rangeLength = rangeLength
        ).also {
            scheduleHlsCleanup(session)
        }
    }

    fun attachHls(
        identity: LivePlaybackIdentity,
        requestFingerprint: String
    ): HlsCapture? {
        val nowMs = elapsedRealtimeMs()
        val session = synchronized(activeHlsSources) {
            activeHlsSources.entries.removeAll { (activeKey, activeSession) ->
                activeKey != SourceKey(identity, requestFingerprint) &&
                    activeSession.isExpiredForRegistry(nowMs)
            }
            activeHlsSources[SourceKey(identity, requestFingerprint)]
                ?.takeIf { it.isFresh(nowMs) }
        } ?: return null
        return session.attach()
    }

    private fun remove(key: SourceKey, session: SourceSession) {
        synchronized(activeSources) {
            if (activeSources[key] === session) activeSources.remove(key)
        }
    }

    private fun scheduleHlsCleanup(session: HlsSourceSession) {
        hlsCleanupExecutor.schedule(
            {
                synchronized(activeHlsSources) {
                    if (activeHlsSources[session.sourceKey] === session &&
                        session.isExpiredForRegistry(elapsedRealtimeMs())
                    ) {
                        activeHlsSources.remove(session.sourceKey)
                    }
                }
            },
            HLS_SESSION_IDLE_TTL_MS,
            TimeUnit.MILLISECONDS
        )
    }

    private inner class SourceSession(
        private val key: SourceKey
    ) : Publisher {
        private val subscribers = LinkedHashMap<Long, LivePlaybackSubscriber>()
        private var nextSubscriberId = 1L
        private var totalBytesPublished = 0L
        private var closed = false

        @Synchronized
        fun attach(): Capture? {
            if (closed) return null
            val id = nextSubscriberId++
            val channel = Channel<ByteArray>(capacity = MAX_BUFFERED_CHUNKS)
            val offsetWithinPacket = (totalBytesPublished % MPEG_TS_PACKET_SIZE.toLong()).toInt()
            val bytesToBoundary = if (offsetWithinPacket == 0) 0 else MPEG_TS_PACKET_SIZE - offsetWithinPacket
            subscribers[id] = LivePlaybackSubscriber(id, channel, bytesToBoundary)
            return CaptureImpl(id, channel)
        }

        @Synchronized
        override fun publish(buffer: ByteArray, offset: Int, length: Int) {
            if (closed || length <= 0) return
            require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
            if (subscribers.isEmpty()) {
                totalBytesPublished += length
                return
            }
            val payload = buffer.copyOfRange(offset, offset + length)
            totalBytesPublished += length
            val iterator = subscribers.values.iterator()
            while (iterator.hasNext()) {
                val subscriber = iterator.next()
                val skip = subscriber.bytesToPacketBoundary.coerceAtMost(payload.size)
                subscriber.bytesToPacketBoundary -= skip
                if (skip == payload.size) continue

                val deliver = if (skip == 0) payload else payload.copyOfRange(skip, payload.size)
                if (subscriber.channel.trySend(deliver).isFailure) {
                    subscriber.channel.close(LivePlaybackCaptureBackpressureException())
                    iterator.remove()
                }
            }
        }

        @Synchronized
        fun finish() {
            if (closed) return
            closed = true
            subscribers.values.forEach { it.channel.close() }
            subscribers.clear()
            remove(key, this)
        }

        override fun close() = finish()

        private inner class CaptureImpl(
            private val id: Long,
            private val channel: Channel<ByteArray>
        ) : Capture {
            override suspend fun readChunk(): ByteArray? {
                val result = channel.receiveCatching()
                result.exceptionOrNull()?.let { throw it }
                return result.getOrNull()
            }

            override fun close() {
                synchronized(this@SourceSession) {
                    subscribers.remove(id)
                    channel.cancel()
                }
            }
        }
    }

    private inner class HlsSourceSession(
        val sourceKey: SourceKey
    ) {
        private val resourcesById = LinkedHashMap<Long, CachedHlsResource>()
        private val resourcesByUrl = HashMap<HlsResourceKey, CachedHlsResource>()
        private var nextResourceId = 1L
        private var playlistBytes = 0
        private var payloadBytes = 0
        private val captures = LinkedHashSet<HlsCaptureImpl>()
        private var lastActivityAtMs = elapsedRealtimeMs()
        private var failure: LivePlaybackCaptureBackpressureException? = null

        @Synchronized
        fun canReuse(nowMs: Long): Boolean =
            captures.isNotEmpty() || nowMs - lastActivityAtMs <= HLS_SESSION_IDLE_TTL_MS

        @Synchronized
        fun isFresh(nowMs: Long): Boolean =
            nowMs - lastActivityAtMs <= HLS_SESSION_IDLE_TTL_MS

        @Synchronized
        fun isExpiredForRegistry(nowMs: Long): Boolean =
            captures.isEmpty() && nowMs - lastActivityAtMs > HLS_SESSION_IDLE_TTL_MS

        @Synchronized
        fun beginResource(
            requestedUrl: String,
            resolvedUrl: String,
            isPlaylist: Boolean,
            rangeStart: Long,
            rangeLength: Long
        ): HlsPublisher {
            lastActivityAtMs = elapsedRealtimeMs()
            return HlsResourcePublisher(requestedUrl, resolvedUrl, isPlaylist, rangeStart, rangeLength)
        }

        @Synchronized
        fun attach(): HlsCapture {
            return HlsCaptureImpl(firstRecordingResourceId = nextResourceId).also { captures.add(it) }
        }

        @Synchronized
        fun resource(url: String, rangeStart: Long, rangeLength: Long): HlsResource? {
            return resourcesByUrl[HlsResourceKey(url, rangeStart, rangeLength)]?.toPublicResource()
        }

        @Synchronized
        fun newestResource(urls: Collection<String>): HlsResource? {
            val nowMs = elapsedRealtimeMs()
            return urls.asSequence()
                .mapNotNull { url -> resourcesByUrl[HlsResourceKey(url, 0L, -1L)] }
                .filter { it.isPlaylist }
                .filter { nowMs - it.updatedAtMs <= HLS_SESSION_IDLE_TTL_MS }
                .maxWithOrNull(compareBy<CachedHlsResource> { it.updatedAtMs }.thenBy { it.id })
                ?.toPublicResource()
        }

        @Synchronized
        fun recordingResource(
            url: String,
            rangeStart: Long,
            rangeLength: Long,
            capture: HlsCaptureImpl
        ): HlsResource? {
            val key = HlsResourceKey(url, rangeStart, rangeLength)
            return resourcesById.values.firstOrNull {
                it.id >= capture.firstRecordingResourceId &&
                    !it.isPlaylist &&
                    key in it.aliases &&
                    it.id !in capture.consumedResourceIds
            }
                ?.toPublicResource()
        }

        @Synchronized
        private fun consume(resourceId: Long, capture: HlsCaptureImpl) {
            val resource = resourcesById[resourceId] ?: return
            if (capture !in captures || resource.id < capture.firstRecordingResourceId &&
                !resource.wasReferencedDependency && !isReferencedDependency(resource)
            ) return
            capture.consumedResourceIds += resource.id
            // Initialization maps can be consumed by one recording and still be needed
            // by another recording which attaches after Media3's one-time map request.
            if (!isRequiredByCapture(resource) && !resource.isPlaylist &&
                !resource.wasReferencedDependency && !isReferencedDependency(resource)
            ) removeResource(resource)
        }

        @Synchronized
        fun isPlaybackActive(): Boolean =
            elapsedRealtimeMs() - lastActivityAtMs <= HLS_SESSION_IDLE_TTL_MS

        @Synchronized
        fun throwIfFailed() {
            failure?.let { throw it }
        }

        @Synchronized
        private fun detach(capture: HlsCaptureImpl) {
            captures.remove(capture)
            if (captures.isEmpty()) {
                failure = null
                // Keep the current manifest and warm dependencies for a subsequent
                // recording. Closing the last reader must not discard a cached key/map
                // which playback may never request again.
                pruneIdleObsoleteResources()
                scheduleHlsCleanup(this@HlsSourceSession)
            } else {
                resourcesById.values.toList()
                    .filter { it.isMediaSegment && !isRequiredByCapture(it) && !isReferencedDependency(it) }
                    .forEach(::removeResource)
            }
        }

        private fun isReferencedDependency(resource: CachedHlsResource): Boolean =
            resourcesById.values.any { playlist ->
                playlist.references?.dependencies?.let { dependencies ->
                    resource.aliases.any { it.url in dependencies }
                } == true
            }

        /** Idle readers never record media from before attachment. Retain the current
         * window conservatively, but release classified resources once every cached
         * playlist has rolled past them. Unknown manifests/resources remain bounded
         * by the existing cache policy rather than being guessed disposable here. */
        private fun pruneIdleObsoleteResources() {
            if (captures.isNotEmpty()) return
            val playlists = resourcesById.values.filter { it.isPlaylist }
            if (playlists.isEmpty()) return
            val references = playlists.map { it.references ?: return }
            val referenced = HashSet<String>()
            references.forEach {
                referenced.addAll(it.media)
                referenced.addAll(it.dependencies)
            }
            resourcesById.values.toList()
                .filter { resource ->
                    !resource.isPlaylist && (resource.isMediaSegment || resource.wasReferencedDependency) &&
                        resource.aliases.none { it.url in referenced }
                }
                .forEach(::removeResource)
        }

        private fun isRequiredByCapture(resource: CachedHlsResource): Boolean =
            captures.any { capture ->
                resource.id !in capture.consumedResourceIds &&
                    (resource.id >= capture.firstRecordingResourceId ||
                        resource.wasReferencedDependency || isReferencedDependency(resource))
            }

        @Synchronized
        private fun store(
            requestedUrl: String,
            resolvedUrl: String,
            bytes: ByteArray,
            isPlaylist: Boolean,
            rangeStart: Long,
            rangeLength: Long
        ) {
            lastActivityAtMs = elapsedRealtimeMs()
            if (bytes.isEmpty()) return
            val aliases = setOf(
                HlsResourceKey(requestedUrl, rangeStart, rangeLength),
                HlsResourceKey(resolvedUrl, rangeStart, rangeLength)
            )
            aliases.mapNotNull(resourcesByUrl::get).distinct().forEach { existing ->
                if (existing.isPlaylist || !isRequiredByCapture(existing)) removeResource(existing)
            }

            val limit = if (isPlaylist) MAX_HLS_PLAYLIST_BYTES else MAX_HLS_PAYLOAD_BYTES
            if (bytes.size > MAX_HLS_RESOURCE_BYTES || bytes.size > limit) {
                if (captures.isNotEmpty()) failure = LivePlaybackCaptureBackpressureException()
                return
            }

            val playlists = resourcesById.values.filter { it.isPlaylist }
            val references = playlists.mapNotNull { it.references }
            val dependenciesKnown = playlists.isNotEmpty() && references.size == playlists.size
            val dependencies = references.flatMapTo(HashSet()) { it.dependencies }
            val isMediaSegment = !isPlaylist &&
                aliases.any { alias -> references.any { alias.url in it.media } } &&
                aliases.none { it.url in dependencies }
            while ((if (isPlaylist) playlistBytes else payloadBytes) + bytes.size > limit) {
                val oldest = resourcesById.values.firstOrNull { resource ->
                    resource.isPlaylist == isPlaylist &&
                        // Keep referenced keys/maps warm even before a recording attaches.
                        resource.aliases.none { it.url in dependencies } &&
                        (captures.isEmpty() ||
                            dependenciesKnown && !isRequiredByCapture(resource) &&
                                (resource.isMediaSegment || resource.wasReferencedDependency))
                } ?: break
                removeResource(oldest)
            }
            if ((if (isPlaylist) playlistBytes else payloadBytes) + bytes.size > limit) {
                // Never evict a segment still owed to a recording, or guess that a warm key,
                // initialization response, or unclassified payload is disposable media.
                if (captures.isNotEmpty()) failure = LivePlaybackCaptureBackpressureException()
                return
            }

            val resourceId = nextResourceId++
            val entry = CachedHlsResource(
                id = resourceId,
                url = resolvedUrl,
                aliases = aliases,
                bytes = bytes,
                updatedAtMs = elapsedRealtimeMs(),
                isPlaylist = isPlaylist,
                isMediaSegment = isMediaSegment,
                wasReferencedDependency = !isPlaylist && aliases.any { it.url in dependencies },
                references = if (isPlaylist) hlsPlaylistReferences(resolvedUrl, bytes) else null
            )
            resourcesById[resourceId] = entry
            aliases.forEach { resourcesByUrl[it] = entry }
            if (isPlaylist) playlistBytes += bytes.size else payloadBytes += bytes.size
            if (isPlaylist) pruneIdleObsoleteResources()
        }

        private fun CachedHlsResource.toPublicResource() =
            HlsResource(
                resourceId = id,
                url = url,
                bytes = bytes,
                updatedAtMs = updatedAtMs,
                isPlaylist = isPlaylist,
                rangeStart = aliases.firstOrNull()?.rangeStart ?: 0L,
                rangeLength = aliases.firstOrNull()?.rangeLength ?: -1L
            )

        private fun removeResource(resource: CachedHlsResource) {
            val entry = resourcesById.entries.firstOrNull { it.value === resource } ?: return
            resourcesById.remove(entry.key)
            // IDs are monotonic and cannot become readable again after removal.
            // Keeping their consumption history would grow for the entire recording.
            captures.forEach { it.consumedResourceIds.remove(resource.id) }
            resource.aliases.forEach { alias ->
                if (resourcesByUrl[alias] === resource) resourcesByUrl.remove(alias)
            }
            if (resource.isPlaylist) playlistBytes -= resource.bytes.size else payloadBytes -= resource.bytes.size
        }

        private inner class HlsResourcePublisher(
            private val requestedUrl: String,
            private val resolvedUrl: String,
            private val isPlaylist: Boolean,
            private val rangeStart: Long,
            private val rangeLength: Long
        ) : HlsPublisher {
            private val output = HlsResponseBuffer()
            private var completed = false
            private var oversized = false

            override fun publish(buffer: ByteArray, offset: Int, length: Int) {
                if (completed || length <= 0) return
                require(offset >= 0 && length >= 0 && offset + length <= buffer.size)
                synchronized(this@HlsSourceSession) {
                    if (completed || oversized) return
                    lastActivityAtMs = elapsedRealtimeMs()
                    if (length > MAX_HLS_RESOURCE_BYTES - output.size) {
                        oversized = true
                        output.clear()
                        return
                    }
                    output.write(buffer, offset, length)
                }
            }

            override fun complete() {
                synchronized(this@HlsSourceSession) {
                    if (completed) return
                    completed = true
                    if (oversized) {
                        if (captures.isNotEmpty()) failure = LivePlaybackCaptureBackpressureException()
                    } else {
                        val bytes = output.takeBytes()
                        val bodyIsPlaylist = bytes.looksLikeHlsPlaylist() ||
                            (isPlaylist && bytes.looksLikeText())
                        store(requestedUrl, resolvedUrl, bytes, bodyIsPlaylist, rangeStart, rangeLength)
                    }
                    output.clear()
                }
                scheduleHlsCleanup(this@HlsSourceSession)
            }

            override fun close() {
                synchronized(this@HlsSourceSession) {
                    if (completed) return
                    completed = true
                    output.clear()
                }
                scheduleHlsCleanup(this@HlsSourceSession)
            }
        }

        private inner class HlsCaptureImpl(
            val firstRecordingResourceId: Long
        ) : HlsCapture {
            @Volatile
            private var closed = false
            val consumedResourceIds = HashSet<Long>()

            override fun resource(url: String, rangeStart: Long, rangeLength: Long): HlsResource? =
                sessionResource(url, rangeStart, rangeLength)

            override fun newestResource(urls: Collection<String>): HlsResource? =
                sessionNewestResource(urls)

            override fun recordingResource(url: String, rangeStart: Long, rangeLength: Long): HlsResource? =
                sessionRecordingResource(url, rangeStart, rangeLength)

            override fun consume(resource: HlsResource) {
                if (!closed) sessionConsume(resource.resourceId)
            }

            override fun isPlaybackActive(): Boolean = !closed && sessionIsPlaybackActive()

            override fun throwIfFailed() {
                if (!closed) sessionThrowIfFailed()
            }

            override fun close() {
                synchronized(this@HlsSourceSession) {
                    if (closed) return
                    closed = true
                    detach(this)
                }
            }

            private fun sessionResource(url: String, rangeStart: Long, rangeLength: Long) = if (closed) {
                null
            } else {
                this@HlsSourceSession.resource(url, rangeStart, rangeLength)
            }
            private fun sessionNewestResource(urls: Collection<String>) =
                if (closed) null else this@HlsSourceSession.newestResource(urls)
            private fun sessionRecordingResource(url: String, rangeStart: Long, rangeLength: Long) =
                if (closed) null else this@HlsSourceSession.recordingResource(url, rangeStart, rangeLength, this)
            private fun sessionConsume(resourceId: Long) =
                this@HlsSourceSession.consume(resourceId, this)
            private fun sessionIsPlaybackActive() = this@HlsSourceSession.isPlaybackActive()
            private fun sessionThrowIfFailed() = this@HlsSourceSession.throwIfFailed()
        }
    }

    private companion object {
        const val MAX_BUFFERED_CHUNKS = 64
        const val MPEG_TS_PACKET_SIZE = 188
        const val HLS_SESSION_IDLE_TTL_MS = 30_000L
        const val MAX_HLS_RESOURCE_BYTES = 32 * 1024 * 1024
        const val MAX_HLS_PLAYLIST_BYTES = 2 * 1024 * 1024
        const val MAX_HLS_PAYLOAD_BYTES = 32 * 1024 * 1024
    }
}

private fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L

/** A stable opaque key that never stores raw URLs or provider credentials in the tap registry. */
fun livePlaybackRequestFingerprint(url: String, headers: Map<String, String>): String {
    val canonical = buildString {
        append(url)
        append('\n')
        headers.entries
            .sortedBy { it.key.lowercase() }
            .forEach { (name, value) ->
                append(name.lowercase())
                append(':')
                append(value)
                append('\n')
            }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(canonical.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}
