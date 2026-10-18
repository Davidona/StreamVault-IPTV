package com.streamvault.feature.playback.player

import android.media.MediaDataSource
import android.os.Build
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

/** Random-access HTTP input whose active reads can be interrupted when thumbnail work is cancelled. */
internal class CancellableHttpMediaDataSource(
    private val url: String,
    baseClient: OkHttpClient,
    headers: Map<String, String>,
    callTimeoutMs: Long
) : MediaDataSource() {
    private val client = baseClient.newBuilder()
        .callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()
    private val requestHeaders = buildMap {
        headers.forEach { (name, value) ->
            if (!name.equals("Range", ignoreCase = true)) put(name, value)
        }
        if (keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            put("User-Agent", "Stagefright/1.2 (Linux;Android ${Build.VERSION.RELEASE})")
        }
    }
    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()
    private val closed = AtomicBoolean(false)
    private val contentLength = AtomicLong(-1L)

    override fun getSize(): Long = contentLength.get()

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (closed.get()) return -1
        if (position < 0L || offset < 0 || size < 0 || offset + size > buffer.size) {
            throw IllegalArgumentException("Invalid byte range")
        }
        if (size == 0) return 0
        val knownLength = contentLength.get()
        if (knownLength >= 0L && position >= knownLength) return -1

        val lastByte = if (Long.MAX_VALUE - position < size - 1L) {
            Long.MAX_VALUE
        } else {
            position + size - 1L
        }
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$position-$lastByte")
            .apply { requestHeaders.forEach { (name, value) -> header(name, value) } }
            .get()
            .build()
        val call = client.newCall(request)
        activeCalls.add(call)
        if (closed.get()) call.cancel()

        try {
            call.execute().use { response ->
                updateContentLength(
                    response.header("Content-Range"),
                    response.body?.contentLength(),
                    response.code
                )
                if (response.code == 416 && position >= contentLength.get()) return -1
                if (!response.isSuccessful) throw IOException("Thumbnail byte read failed: HTTP ${response.code}")

                val body = response.body ?: return -1
                val rangeStart = response.header("Content-Range")
                    ?.let(CONTENT_RANGE_START::matchEntire)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toLongOrNull()
                if (response.code == 206 && rangeStart != position) {
                    throw IOException("Thumbnail range response started at $rangeStart, requested $position")
                }

                val source = body.source()
                if (response.code != 206 && position > 0L) source.skip(position)
                return source.read(buffer, offset, size)
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        activeCalls.forEach(Call::cancel)
        activeCalls.clear()
    }

    private fun updateContentLength(contentRange: String?, bodyLength: Long?, statusCode: Int) {
        val rangeLength = contentRange
            ?.let(CONTENT_RANGE_LENGTH::matchEntire)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
        val unsatisfiedRangeLength = contentRange
            ?.let(UNSATISFIED_RANGE_LENGTH::matchEntire)
            ?.groupValues
            ?.getOrNull(1)
            ?.toLongOrNull()
        val length = rangeLength ?: unsatisfiedRangeLength ?: if (statusCode == 200) {
            bodyLength?.takeIf { it >= 0L }
        } else {
            null
        }
        if (length != null) contentLength.compareAndSet(-1L, length)
    }

    private companion object {
        val CONTENT_RANGE_START = Regex("bytes (\\d+)-\\d+/(?:\\d+|\\*)", RegexOption.IGNORE_CASE)
        val CONTENT_RANGE_LENGTH = Regex("bytes \\d+-\\d+/(\\d+)", RegexOption.IGNORE_CASE)
        val UNSATISFIED_RANGE_LENGTH = Regex("bytes \\*/(\\d+)", RegexOption.IGNORE_CASE)
    }
}
