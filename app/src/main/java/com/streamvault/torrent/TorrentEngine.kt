package com.streamvault.torrent

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import com.streamvault.app.R
import com.streamvault.domain.model.TorrentCacheClearResult
import com.streamvault.domain.model.TorrentCacheStatus
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal data class PreparedTorrent(
    val token: String,
    val outputUrl: String,
    val displayName: String
)

internal data class TorrentStatusSnapshot(
    val activeCount: Int,
    val downloadBytesPerSecond: Long,
    val uploadBytesPerSecond: Long,
    val peers: Int,
    val label: String,
    val activeSessions: List<String>
) {
    val summary: String
        get() = buildString {
            append(label)
            append(" · ")
            append(peers)
            append(if (peers == 1) " peer" else " peers")
            append(" · ↓ ")
            append(formatRate(downloadBytesPerSecond))
            append(" · ↑ ")
            append(formatRate(uploadBytesPerSecond))
            if (activeSessions.isNotEmpty()) {
                append("\n")
                append(activeSessions.joinToString("\n"))
            }
        }

    private fun formatRate(bytesPerSecond: Long): String {
        if (bytesPerSecond < 1024L) return "$bytesPerSecond B/s"
        val kib = bytesPerSecond / 1024.0
        if (kib < 1024.0) return "%.0f KiB/s".format(kib)
        return "%.1f MiB/s".format(kib / 1024.0)
    }
}

internal data class TorrentFileInfo(
    val name: String,
    val size: Long,
    val contentType: String
)

internal interface TorrentFileStreamer {
    fun fileInfo(token: String): TorrentFileInfo
    fun stream(token: String, start: Long, endInclusive: Long, output: java.io.OutputStream)
}

/** Owns the torrent session and exposes only selected files through loopback HTTP. */
internal class TorrentEngine(
    context: Context,
    private val preferences: TorrentPreferences
) : Closeable, TorrentFileStreamer {
    private val appContext = context.applicationContext
    private val root = TorrentStorageLocations.torrentDirectory(context, preferences.storageLocationIndex)
        .apply { mkdirs() }
        .canonicalFile
    private val sessionManager = SessionManager()
    private val resolverExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "torrent-metadata").apply { isDaemon = true }
    }
    private val sessions = ConcurrentHashMap<String, TorrentSession>()
    private val byHash = ConcurrentHashMap<String, TorrentSession>()
    private val reservedPieces = mutableMapOf<ReservedPiece, Int>()
    private val closed = AtomicBoolean(false)
    @Volatile private var loopbackBaseUrl: String? = null

    init {
        check(root.isDirectory && root.canWrite()) { "Torrent storage is not writable" }
        TorrentStorageLocations.cleanupOrphanedSessionDirectories(root)
        sessionManager.start(SessionParams())
        sessionManager.startDht()
        applyPreferences()
    }

    @Synchronized
    fun prepare(rawMagnet: String, forDownload: Boolean = false): PreparedTorrent = TorrentStorageLocations.withCacheLock {
        check(!closed.get()) { "Torrent engine is stopping" }
        val magnet = MagnetLinkParser.parse(rawMagnet)
            ?: throw IllegalArgumentException("The add-on returned an invalid or unsupported magnet link")
        byHash[magnet.infoHash]?.let { existing ->
            check(existing.magnet.selectedFileIndex == magnet.selectedFileIndex) {
                "This torrent is already playing a different file"
            }
            if (forDownload) existing.forDownload = true
            existing.touch()
            return@withCacheLock existing.prepared
        }

        val token = UUID.randomUUID().toString().replace("-", "")
        val serverBaseUrl = checkNotNull(loopbackBaseUrl) { "Loopback stream server is not ready" }
        val prepared = PreparedTorrent(
            token = token,
            outputUrl = "$serverBaseUrl/stream/$token",
            displayName = magnet.displayName ?: "Torrent stream"
        )
        val saveDirectory = File(root, magnet.infoHash.lowercase()).canonicalFile
        require(saveDirectory.path.startsWith(root.path + File.separator)) {
            "Torrent storage path is outside the configured directory"
        }
        check(saveDirectory.mkdirs() || saveDirectory.isDirectory) { "Torrent storage could not be created" }

        val torrent = TorrentSession(magnet, prepared, saveDirectory, forDownload)
        sessions[token] = torrent
        byHash[magnet.infoHash] = torrent
        try {
            // Upload mode allows peer discovery and metadata exchange without downloading unwanted files.
            sessionManager.download(magnet.toEngineUri(), saveDirectory, TorrentFlags.UPLOAD_MODE)
            resolverExecutor.execute { resolveMetadata(torrent) }
        } catch (error: Throwable) {
            sessions.remove(token)
            byHash.remove(magnet.infoHash, torrent)
            saveDirectory.deleteRecursively()
            throw IllegalStateException("Unable to start torrent: ${safeMessage(error)}", error)
        }
        prepared
    }

    fun setLoopbackBaseUrl(value: String) {
        val uri = URI(value)
        require(uri.scheme.equals("http", ignoreCase = true))
        require(uri.host == "127.0.0.1" && uri.port in 1..65535)
        require(uri.rawUserInfo == null && uri.rawPath.orEmpty().isEmpty())
        loopbackBaseUrl = value
    }

    fun stop(outputUrl: String) = TorrentStorageLocations.withCacheLock {
        val token = runCatching { URI(outputUrl).rawPath.orEmpty().substringAfterLast('/') }
            .getOrNull()
            ?.takeIf { it.length == TOKEN_LENGTH && it.all(Char::isLetterOrDigit) }
            ?: return@withCacheLock
        sessions.remove(token)?.let(::stopSession)
    }

    fun stopAll() = TorrentStorageLocations.withCacheLock {
        sessions.entries.toList().forEach { (token, session) ->
            if (sessions.remove(token, session)) stopSession(session)
        }
    }

    fun isIdle(): Boolean = sessions.isEmpty()

    fun expireIdleSessions(nowElapsedRealtime: Long = SystemClock.elapsedRealtime()): Int =
        TorrentStorageLocations.withCacheLock {
            var expired = 0
            sessions.entries.toList().forEach { (token, session) ->
                val allowedIdleMillis =
                    if (session.failure == null) SESSION_IDLE_TIMEOUT_MILLIS else FAILED_SESSION_TIMEOUT_MILLIS
                if (nowElapsedRealtime - session.lastTouchedElapsedRealtime >= allowedIdleMillis &&
                    sessions.remove(token, session)
                ) {
                    stopSession(session)
                    expired++
                }
            }
            expired
        }

    fun statusSnapshot(): TorrentStatusSnapshot {
        val rateDown = runCatching { sessionManager.downloadRate() }.getOrDefault(0L).coerceAtLeast(0L)
        val rateUp = runCatching { sessionManager.uploadRate() }.getOrDefault(0L).coerceAtLeast(0L)
        val currentSessions = sessions.values.toList()
        val peers = currentSessions.sumOf { session ->
            runCatching { session.handle?.peerInfo()?.size ?: 0 }.getOrDefault(0)
        }
        val label = when {
            currentSessions.isEmpty() -> "Ready; no active torrent sessions"
            currentSessions.any { it.failure != null } -> "A torrent could not be prepared"
            currentSessions.any { it.resolvedFile == null } -> "Waiting for torrent metadata"
            else -> "Streaming ${currentSessions.size} torrent${if (currentSessions.size == 1) "" else "s"}"
        }
        val names = currentSessions.map { session ->
            session.resolvedFile?.name ?: session.magnet.displayName ?: "Resolving torrent metadata"
        }
        return TorrentStatusSnapshot(currentSessions.size, rateDown, rateUp, peers, label, names)
    }

    fun applyPreferences() {
        if (!sessionManager.isRunning()) return
        runCatching { sessionManager.downloadRateLimit(preferences.downloadLimitBytesPerSecond) }
        runCatching { sessionManager.uploadRateLimit(preferences.uploadLimitBytesPerSecond) }
        runCatching { sessionManager.maxPeers(MAX_PEERS) }
    }

    override fun fileInfo(token: String): TorrentFileInfo {
        val torrent = sessions[token] ?: throw StreamHttpException(404, "Stream session has ended")
        torrent.touch()
        val file = awaitResolvedFile(torrent)
        return TorrentFileInfo(file.name, file.size, contentType(file.name))
    }

    override fun stream(token: String, start: Long, endInclusive: Long, output: java.io.OutputStream) {
        val torrent = sessions[token] ?: throw StreamHttpException(404, "Stream session has ended")
        val file = awaitResolvedFile(torrent)
        if (start < 0 || start >= file.size || endInclusive < start || endInclusive >= file.size) {
            throw StreamHttpException(416, "Requested range is outside the selected file")
        }

        var cursor = start
        while (cursor <= endInclusive) {
            if (torrent.cancelled.get()) throw StreamHttpException(410, "Playback session has ended")
            torrent.touch()
            val length = minOf(CHUNK_BYTES.toLong(), endInclusive - cursor + 1L).toInt()
            awaitPieces(torrent, file, cursor, length)
            torrent.handle?.flushCache()
            RandomAccessFile(file.file, "r").use { input ->
                input.seek(cursor)
                var remaining = length
                val buffer = ByteArray(minOf(length, IO_BUFFER_BYTES))
                while (remaining > 0) {
                    if (torrent.cancelled.get()) throw StreamHttpException(410, "Playback session has ended")
                    val read = input.read(buffer, 0, minOf(remaining, buffer.size))
                    if (read < 0) throw StreamHttpException(503, "Downloaded torrent data is not ready yet")
                    output.write(buffer, 0, read)
                    remaining -= read
                }
            }
            output.flush()
            cursor += length
        }
        torrent.touch()
    }

    fun cacheStatus(): TorrentCacheStatus = TorrentStorageLocations.withCacheLock {
        TorrentCacheStatus(
            cachedBytes = downloadedTemporaryTorrentBytes(),
            cacheLimitBytes = TorrentCachePolicy.MAX_CACHE_BYTES,
            availableStorageBytes = availableStorageBytes(),
            activeSessionCount = sessions.size
        )
    }

    fun clearDownloadedData(): TorrentCacheClearResult = TorrentStorageLocations.withCacheLock {
        if (!isIdle()) return@withCacheLock TorrentCacheClearResult.PLAYBACK_ACTIVE
        val cleared = TorrentStorageLocations.clearSessionDirectories(root)
        reservedPieces.clear()
        if (cleared) TorrentCacheClearResult.CLEARED else TorrentCacheClearResult.FAILED
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopAll()
        resolverExecutor.shutdownNow()
        runCatching { sessionManager.stop() }
    }

    private fun resolveMetadata(torrent: TorrentSession) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(METADATA_TIMEOUT_SECONDS)
        try {
            var handle: TorrentHandle? = null
            var info: TorrentInfo? = null
            val hash = Sha1Hash.parseHex(torrent.magnet.infoHash)
            while (!torrent.cancelled.get() && System.nanoTime() < deadline) {
                handle = runCatching { sessionManager.find(hash) }.getOrNull()
                if (handle?.isValid() == true) {
                    torrent.handle = handle
                    info = runCatching { handle.torrentFile() }.getOrNull()
                }
                if (info?.isValid() == true && info.numFiles() > 0) break
                if (torrent.cancelled.get()) {
                    handle?.takeIf(TorrentHandle::isValid)?.let { runCatching { sessionManager.remove(it) } }
                    return
                }
                Thread.sleep(METADATA_POLL_MILLIS)
            }
            if (torrent.cancelled.get()) {
                handle?.takeIf(TorrentHandle::isValid)?.let { runCatching { sessionManager.remove(it) } }
                return
            }
            if (handle?.isValid() != true || info?.isValid() != true || info.numFiles() == 0) {
                throw IllegalStateException("No torrent metadata arrived; check tracker and peer availability")
            }
            torrent.handle = handle
            torrent.info = info
            val files = info.files()
            val selectedIndex = selectFile(torrent.magnet.selectedFileIndex, info)
            val virtualPath = files.filePath(selectedIndex)
            val file = File(torrent.saveDirectory, virtualPath).canonicalFile
            require(file.path.startsWith(torrent.saveDirectory.path + File.separator)) {
                "Torrent path is outside private storage"
            }
            val size = files.fileSize(selectedIndex)
            require(size > 0L) { "Selected torrent file is empty" }

            val priorities = Priority.array(Priority.IGNORE, info.numFiles())
            priorities[selectedIndex] = Priority.DEFAULT
            handle.prioritizeFiles(priorities)
            handle.unsetFlags(TorrentFlags.UPLOAD_MODE)
            handle.resume()

            val fileOffset = files.fileOffset(selectedIndex)
            val pieceLength = info.pieceLength().toLong()
            require(pieceLength > 0L) { "Torrent has an invalid piece size" }
            val firstPiece = (fileOffset / pieceLength).toInt()
            handle.setSequentialRange(firstPiece)

            torrent.resolvedFile = ResolvedTorrentFile(
                index = selectedIndex,
                name = File(virtualPath).name,
                file = file,
                size = size,
                fileOffset = fileOffset,
                pieceLength = pieceLength,
                info = info
            )
            torrent.touch()
            torrent.readyMonitor.synchronizedNotifyAll()
        } catch (error: Throwable) {
            if (!torrent.cancelled.get()) {
                torrent.failure = safeMessage(error)
                torrent.readyMonitor.synchronizedNotifyAll()
            }
        }
    }

    private fun stopSession(torrent: TorrentSession) {
        byHash.remove(torrent.magnet.infoHash, torrent)
        torrent.cancelled.set(true)
        torrent.readyMonitor.synchronizedNotifyAll()
        torrent.handle?.let { handle ->
            if (runCatching { handle.isValid() }.getOrDefault(false)) {
                runCatching { handle.pause() }
                runCatching { sessionManager.remove(handle) }
            }
        }
        torrent.saveDirectory.deleteRecursively()
    }

    private fun selectFile(requestedIndex: Int?, info: TorrentInfo): Int {
        val storage = info.files()
        if (requestedIndex != null) {
            require(requestedIndex in 0 until info.numFiles()) { "Selected file index is not in this torrent" }
            require(!storage.padFileAt(requestedIndex)) { "Selected torrent file is padding data" }
            return requestedIndex
        }
        val videoExtensions = setOf("mkv", "mp4", "m4v", "webm", "avi", "mov", "mpg", "mpeg", "ts", "wmv")
        return (0 until info.numFiles())
            .filterNot(storage::padFileAt)
            .filter { File(storage.filePath(it)).extension.lowercase() in videoExtensions }
            .maxByOrNull(storage::fileSize)
            ?: throw IllegalStateException("Torrent metadata contains no supported video file")
    }

    private fun contentType(fileName: String): String = when (File(fileName).extension.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"
        "mov" -> "video/quicktime"
        "mpg", "mpeg" -> "video/mpeg"
        "ts" -> "video/mp2t"
        "wmv" -> "video/x-ms-wmv"
        else -> "application/octet-stream"
    }

    private fun awaitResolvedFile(torrent: TorrentSession): ResolvedTorrentFile {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(STREAM_READY_TIMEOUT_SECONDS)
        synchronized(torrent.readyMonitor) {
            while (torrent.resolvedFile == null && torrent.failure == null && !torrent.cancelled.get()) {
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0L) throw StreamHttpException(503, "Timed out waiting for torrent metadata")
                TimeUnit.NANOSECONDS.timedWait(torrent.readyMonitor, remaining)
            }
        }
        if (torrent.cancelled.get()) throw StreamHttpException(410, "Playback session has ended")
        torrent.resolvedFile?.let { return it }
        throw StreamHttpException(503, torrent.failure ?: "Torrent metadata is unavailable")
    }

    private fun awaitPieces(torrent: TorrentSession, file: ResolvedTorrentFile, offset: Long, length: Int) {
        val handle = torrent.handle ?: throw StreamHttpException(503, "Torrent engine is not ready")
        val absoluteStart = file.fileOffset + offset
        val absoluteEnd = absoluteStart + length - 1L
        val firstPiece = (absoluteStart / file.pieceLength).toInt()
        val lastPiece = (absoluteEnd / file.pieceLength).toInt()
        val prefetchEnd = minOf(file.size - 1L, offset + preferences.bufferMiB * BYTES_PER_MIB)
        val prefetchLast = ((file.fileOffset + prefetchEnd) / file.pieceLength).toInt()
        val targetLast = maxOf(lastPiece, prefetchLast)
        val reserved = reserveCacheBudget(torrent, file, firstPiece, targetLast)
        try {
            handle.setSequentialRange(firstPiece, targetLast)
            for (piece in firstPiece..targetLast) {
                if (!handle.havePiece(piece)) {
                    val relativePiece = piece - firstPiece
                    handle.setPieceDeadline(
                        piece,
                        (relativePiece * PIECE_DEADLINE_STEP_MS).coerceAtMost(MAX_PIECE_DEADLINE_MS)
                    )
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PIECE_TIMEOUT_SECONDS)
            while (System.nanoTime() < deadline) {
                if (torrent.cancelled.get()) throw StreamHttpException(410, "Playback session has ended")
                var ready = true
                for (piece in firstPiece..lastPiece) {
                    if (!handle.havePiece(piece)) {
                        ready = false
                        break
                    }
                }
                if (ready) return
                Thread.sleep(PIECE_POLL_MILLIS)
            }
            throw StreamHttpException(503, "Timed out waiting for torrent pieces")
        } finally {
            releaseCacheBudget(reserved)
        }
    }

    private fun reserveCacheBudget(
        torrent: TorrentSession,
        file: ResolvedTorrentFile,
        firstPiece: Int,
        lastPiece: Int
    ): List<ReservedPiece> = TorrentStorageLocations.withCacheLock {
        val handle = torrent.handle ?: throw StreamHttpException(503, "Torrent engine is not ready")
        val requested = (firstPiece..lastPiece)
            .filterNot(handle::havePiece)
            .map { ReservedPiece(torrent.prepared.token, it) }
        val newBytes = requested
            .filterNot(reservedPieces::containsKey)
            .sumOf { piece -> pieceBytesInSelectedFile(file, piece.index) }
        val pendingTemporaryBytes = reservedPieces.keys.sumOf { piece ->
            if (sessions[piece.token]?.forDownload == true) 0L else reservedPieceBytes(piece)
        }
        val projectedTemporaryBytes = downloadedTemporaryTorrentBytes() + pendingTemporaryBytes +
            if (torrent.forDownload) 0L else newBytes
        if (!torrent.forDownload && projectedTemporaryBytes > TorrentCachePolicy.MAX_CACHE_BYTES) {
            rejectForCachePolicy(torrent, R.string.torrent_cache_capacity_reached)
        }
        val pendingStorageBytes = reservedPieces.keys.sumOf { piece ->
            val bytes = reservedPieceBytes(piece)
            if (sessions[piece.token]?.forDownload == true) bytes * 2L else bytes
        }
        val newStorageBytes = newBytes * if (torrent.forDownload) 2L else 1L
        if (pendingStorageBytes + newStorageBytes > 0L) {
            val availableBytes = availableStorageBytes()
                ?: rejectForCachePolicy(torrent, R.string.torrent_storage_check_failed)
            if (availableBytes - pendingStorageBytes - newStorageBytes < TorrentCachePolicy.MIN_FREE_STORAGE_BYTES) {
                rejectForCachePolicy(torrent, R.string.torrent_cache_capacity_reached)
            }
        }
        requested.forEach { piece -> reservedPieces[piece] = (reservedPieces[piece] ?: 0) + 1 }
        requested
    }

    private fun rejectForCachePolicy(torrent: TorrentSession, messageRes: Int): Nothing {
        val message = appContext.getString(messageRes)
        if (sessions.remove(torrent.prepared.token, torrent)) stopSession(torrent)
        throw StreamHttpException(507, message)
    }

    private fun releaseCacheBudget(reserved: List<ReservedPiece>) {
        TorrentStorageLocations.withCacheLock {
            reserved.forEach { piece ->
                val references = reservedPieces[piece] ?: return@forEach
                if (references <= 1) reservedPieces.remove(piece) else reservedPieces[piece] = references - 1
            }
        }
    }

    private fun reservedPieceBytes(piece: ReservedPiece): Long {
        val session = sessions[piece.token] ?: return 0L
        val file = session.resolvedFile ?: return 0L
        val handle = session.handle ?: return 0L
        if (runCatching { handle.havePiece(piece.index) }.getOrDefault(false)) return 0L
        return pieceBytesInSelectedFile(file, piece.index)
    }

    private fun pieceBytesInSelectedFile(file: ResolvedTorrentFile, pieceIndex: Int): Long {
        val pieceStart = pieceIndex.toLong() * file.pieceLength
        val pieceEnd = pieceStart + file.pieceLength
        val fileEnd = file.fileOffset + file.size
        return (minOf(pieceEnd, fileEnd) - maxOf(pieceStart, file.fileOffset)).coerceAtLeast(0L)
    }

    private fun downloadedTemporaryTorrentBytes(): Long = sessions.values
        .filterNot { it.forDownload }
        .sumOf { session ->
        runCatching { session.handle?.status()?.totalDone()?.coerceAtLeast(0L) ?: 0L }.getOrDefault(0L)
    }

    private fun availableStorageBytes(): Long? =
        runCatching { StatFs(root.absolutePath).availableBytes }.getOrNull()

    private data class ResolvedTorrentFile(
        val index: Int,
        val name: String,
        val file: File,
        val size: Long,
        val fileOffset: Long,
        val pieceLength: Long,
        val info: TorrentInfo
    )

    private data class ReservedPiece(val token: String, val index: Int)

    private class TorrentSession(
        val magnet: MagnetLink,
        val prepared: PreparedTorrent,
        val saveDirectory: File,
        @Volatile var forDownload: Boolean
    ) {
        val cancelled = AtomicBoolean(false)
        val readyMonitor = Object()
        @Volatile var handle: TorrentHandle? = null
        @Volatile var info: TorrentInfo? = null
        @Volatile var resolvedFile: ResolvedTorrentFile? = null
        @Volatile var failure: String? = null
        @Volatile var lastTouchedElapsedRealtime: Long = SystemClock.elapsedRealtime()

        fun touch() {
            lastTouchedElapsedRealtime = SystemClock.elapsedRealtime()
        }
    }

    private fun Object.synchronizedNotifyAll() {
        synchronized(this) { notifyAll() }
    }

    private fun safeMessage(error: Throwable): String =
        error.message?.take(240)?.takeIf(String::isNotBlank) ?: "Torrent operation failed"

    companion object {
        private const val TOKEN_LENGTH = 32
        private const val MAX_PEERS = 80
        private const val MAX_PIECE_DEADLINE_MS = 20_000
        private const val PIECE_DEADLINE_STEP_MS = 500
        private const val PIECE_POLL_MILLIS = 125L
        private const val METADATA_POLL_MILLIS = 250L
        private const val METADATA_TIMEOUT_SECONDS = 180L
        private const val STREAM_READY_TIMEOUT_SECONDS = 180L
        private const val PIECE_TIMEOUT_SECONDS = 90L
        private const val SESSION_IDLE_TIMEOUT_MILLIS = 30L * 60L * 1_000L
        private const val FAILED_SESSION_TIMEOUT_MILLIS = 60L * 1_000L
        private const val CHUNK_BYTES = 256 * 1024
        private const val IO_BUFFER_BYTES = 64 * 1024
        private const val BYTES_PER_MIB = 1024L * 1024L
    }
}

internal data class ByteRange(val start: Long, val endInclusive: Long)

internal class StreamHttpException(val statusCode: Int, override val message: String) : Exception(message)
