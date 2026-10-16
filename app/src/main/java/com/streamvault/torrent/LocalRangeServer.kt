package com.streamvault.torrent

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Minimal single-file HTTP/1.1 server. It binds only to IPv4 loopback. */
internal class LocalRangeServer(private val engine: TorrentFileStreamer) : Closeable {
    private val closed = AtomicBoolean(false)
    private val socket = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
    private val workers = ThreadPoolExecutor(
        2,
        8,
        30L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "torrent-http").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy()
    )
    private val acceptThread = Thread(::acceptLoop, "torrent-http-accept").apply {
        isDaemon = true
        start()
    }

    val baseUrl: String get() = "http://127.0.0.1:${socket.localPort}"

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket.close() }
        workers.shutdownNow()
        acceptThread.interrupt()
    }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                if (!closed.get()) continue else return
            }
            try {
                workers.execute { handle(client) }
            } catch (_: Throwable) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) {
        client.use { connection ->
            runCatching {
                connection.tcpNoDelay = true
                connection.soTimeout = HEADER_TIMEOUT_MILLIS
                val reader = InputStreamReader(
                    BufferedInputStream(connection.getInputStream()),
                    StandardCharsets.ISO_8859_1
                )
                val requestLine = readLine(reader, MAX_HEADER_BYTES) ?: return
                val parts = requestLine.split(' ', limit = 3)
                if (parts.size != 3 || parts[0].isBlank() ||
                    !parts[2].matches(HTTP_VERSION_PATTERN)
                ) {
                    writeError(connection, 400, "Bad request")
                    return
                }

                val method = parts[0].uppercase()
                if (method != "GET" && method != "HEAD") {
                    writeError(connection, 405, "Method not allowed", "Allow: GET, HEAD\r\n")
                    return
                }

                val path = runCatching { java.net.URI(parts[1]).rawPath.orEmpty() }.getOrDefault("")
                val segments = path.split('/').filter(String::isNotEmpty)
                if (segments.size != 2 || segments[0] != "stream" ||
                    segments[1].length != TOKEN_LENGTH ||
                    !segments[1].all(Char::isLetterOrDigit)
                ) {
                    writeError(connection, 404, "Not found", includeBody = method == "GET")
                    return
                }

                val headers = readHeaders(reader) ?: run {
                    writeError(connection, 431, "Request headers too large", includeBody = method == "GET")
                    return
                }
                val token = segments[1]
                val fileInfo = try {
                    engine.fileInfo(token)
                } catch (error: StreamHttpException) {
                    writeError(connection, error.statusCode, error.message, includeBody = method == "GET")
                    return
                } catch (_: Throwable) {
                    writeError(connection, 503, "Torrent stream is not ready", includeBody = method == "GET")
                    return
                }
                if (fileInfo.size <= 0L) {
                    writeError(connection, 503, "Torrent file is empty", includeBody = method == "GET")
                    return
                }

                val rangeHeader = headers.entries.firstOrNull { it.key.equals("range", true) }?.value
                val range = try {
                    HttpByteRange.parse(rangeHeader, fileInfo.size)
                } catch (_: IllegalArgumentException) {
                    writeHeaders(
                        connection,
                        status = "416 Range Not Satisfiable",
                        contentType = fileInfo.contentType,
                        contentLength = 0,
                        extraHeaders = "Accept-Ranges: bytes\r\nContent-Range: bytes */${fileInfo.size}\r\n"
                    )
                    return
                }
                val start = range?.start ?: 0L
                val end = range?.endInclusive ?: (fileInfo.size - 1L)
                val responseLength = end - start + 1L
                val status = if (range == null) "200 OK" else "206 Partial Content"
                val extraHeaders = buildString {
                    append("Accept-Ranges: bytes\r\n")
                    if (range != null) append("Content-Range: bytes $start-$end/${fileInfo.size}\r\n")
                    append("Cache-Control: no-store\r\n")
                }
                writeHeaders(connection, status, fileInfo.contentType, responseLength, extraHeaders)
                if (method == "GET") {
                    connection.soTimeout = 0
                    engine.stream(token, start, end, BufferedOutputStream(connection.getOutputStream()))
                }
            }.onFailure {
                // Playback owns retry/cancellation; closing this socket releases a blocked read.
            }
        }
    }

    private fun readHeaders(reader: InputStreamReader): Map<String, String>? {
        val headers = linkedMapOf<String, String>()
        var consumed = 0
        while (true) {
            val line = readLine(reader, MAX_HEADER_BYTES - consumed) ?: return null
            consumed += line.length + CRLF_BYTES
            if (consumed > MAX_HEADER_BYTES) return null
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
    }

    private fun readLine(reader: InputStreamReader, maxLength: Int): String? {
        if (maxLength <= 0) return null
        val result = StringBuilder()
        var previous = -1
        while (result.length <= maxLength) {
            val char = reader.read()
            if (char < 0) return if (result.isEmpty()) null else result.toString()
            if (previous == '\r'.code && char == '\n'.code) {
                result.setLength(result.length - 1)
                return result.toString()
            }
            result.append(char.toChar())
            previous = char
        }
        return null
    }

    private fun writeError(
        socket: Socket,
        code: Int,
        message: String,
        extraHeaders: String = "",
        includeBody: Boolean = true
    ) {
        val body = "$message\n".toByteArray(StandardCharsets.UTF_8)
        writeHeaders(
            socket,
            "$code ${statusText(code)}",
            "text/plain; charset=utf-8",
            if (includeBody) body.size.toLong() else 0L,
            extraHeaders
        )
        if (includeBody) socket.getOutputStream().apply { write(body); flush() }
    }

    private fun writeHeaders(
        socket: Socket,
        status: String,
        contentType: String,
        contentLength: Long,
        extraHeaders: String = ""
    ) {
        val head = buildString {
            append("HTTP/1.1 $status\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: $contentLength\r\n")
            append(extraHeaders)
            append("Connection: close\r\n\r\n")
        }.toByteArray(StandardCharsets.ISO_8859_1)
        socket.getOutputStream().apply { write(head); flush() }
    }

    private fun statusText(code: Int): String = when (code) {
        400 -> "Bad Request"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        410 -> "Gone"
        416 -> "Range Not Satisfiable"
        431 -> "Request Header Fields Too Large"
        else -> "Service Unavailable"
    }

    companion object {
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val HEADER_TIMEOUT_MILLIS = 15_000
        private const val CRLF_BYTES = 2
        private const val TOKEN_LENGTH = 32
        private val HTTP_VERSION_PATTERN = Regex("HTTP/1\\.[01]")
    }
}

internal object HttpByteRange {
    fun parse(header: String?, size: Long): ByteRange? {
        if (header.isNullOrBlank()) return null
        require(size > 0L) { "Range cannot address an empty file" }
        require(header.startsWith("bytes=", ignoreCase = true))
        val value = header.substringAfter('=').trim()
        require(!value.contains(','))
        val dash = value.indexOf('-')
        require(dash >= 0)
        val startText = value.substring(0, dash).trim()
        val endText = value.substring(dash + 1).trim()
        val start: Long
        val end: Long
        if (startText.isEmpty()) {
            val suffixLength = endText.toLongOrNull() ?: throw IllegalArgumentException()
            require(suffixLength > 0L)
            start = (size - suffixLength).coerceAtLeast(0L)
            end = size - 1L
        } else {
            start = startText.toLongOrNull() ?: throw IllegalArgumentException()
            end = if (endText.isEmpty()) size - 1L else endText.toLongOrNull() ?: throw IllegalArgumentException()
        }
        require(start >= 0L && end >= start && start < size)
        return ByteRange(start, minOf(end, size - 1L))
    }
}
