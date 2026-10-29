package com.streamvault.app.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Opt-in Android-side HTTP chunk parser comparison; this does not create a player or change app policy. */
@RunWith(AndroidJUnit4::class)
class ChunkedHttpReaderDiagnosticTest {
    @Test
    fun compareBareReadersAgainstChunkedFixture() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("chunkedReaderDiagnostic") == "true")
        val runId = requireNotNull(args.getString("runId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        val baseUrl = requireNotNull(args.getString("fixtureBaseUrl")).trimEnd('/')
        val baseUri = URI(baseUrl)
        require(
            baseUri.scheme == "http" &&
                baseUri.host?.lowercase() in setOf("127.0.0.1", "localhost", "10.0.2.2") &&
                baseUri.port in 1..65_535 &&
                baseUri.userInfo == null &&
                baseUri.rawPath.isNullOrEmpty() &&
                baseUri.rawQuery == null &&
                baseUri.rawFragment == null
        ) { "fixtureBaseUrl must be a plain HTTP loopback URL with an explicit port" }
        val mediaPath = args.getString("mediaPath") ?: "/raw/1002.ts"
        val expectedBytes = requireNotNull(args.getString("expectedBytes")).toLong()
        val expectedSha256 = requireNotNull(args.getString("expectedSha256"))
        val callTimeoutSeconds = args.getString("callTimeoutSeconds")?.toLong() ?: 90L
        require(mediaPath.startsWith("/raw/") && !mediaPath.contains(".."))

        val report = JSONObject()
            .put("runId", runId)
            .put("fixtureBaseUrl", baseUrl)
            .put("mediaPath", mediaPath)
            .put("expectedBytes", expectedBytes)
            .put("expectedSha256", expectedSha256)
            .put("callTimeoutSeconds", callTimeoutSeconds)
            .put("okHttpVersionFromGradle", "4.12.0")
            .put("kotlinRuntimeVersion", KotlinVersion.CURRENT.toString())
            .put("readers", JSONArray())

        val reset = readJson("$baseUrl/control?reset=1")
        report.put("fixtureReset", reset)

        val okHttp = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
            .build()
        val readers = report.getJSONArray("readers")
        readers.put(readWithOkHttpBodyStream(okHttp, "$baseUrl$mediaPath?reader=android-okhttp-body-stream-940", 940))
        readers.put(readWithOkHttp(okHttp, "$baseUrl$mediaPath?reader=android-okhttp-940", 940))
        readers.put(readWithOkHttp(okHttp, "$baseUrl$mediaPath?reader=android-okhttp-8192", 8_192))
        readers.put(readWithOkHttp(okHttp, "$baseUrl$mediaPath?reader=android-okhttp-32768", 32_768))
        readers.put(readWithHttpUrlConnection("$baseUrl$mediaPath?reader=android-httpurlconnection"))
        readers.put(readChunkedWireDirectly("$baseUrl$mediaPath?reader=android-raw-socket"))
        for (index in 0 until readers.length()) {
            val reader = readers.getJSONObject(index)
            reader.put("matchesExpectedBytes", reader.optLong("payloadBytes", -1L) == expectedBytes)
            reader.put("matchesExpectedSha256", reader.optString("payloadSha256") == expectedSha256)
        }

        report.put("fixtureEventsAfterRetrievedExternally", true)
        report.put("elapsedRealtimeMs", android.os.SystemClock.elapsedRealtime())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File(context.cacheDir, "chunked-http-reader-$runId.json")
        file.writeText(report.toString(2), Charsets.UTF_8)
        println("ChunkedHttpReaderDiagnostic report=${file.absolutePath} readers=${readers.length()}")
    }

    private fun readWithOkHttp(client: OkHttpClient, url: String, bufferBytes: Int): JSONObject {
        val started = android.os.SystemClock.elapsedRealtime()
        var responseCode: Int? = null
        var responseHeaders = JSONArray()
        val digest = MessageDigest.getInstance("SHA-256")
        var payloadBytes = 0L
        var reachedBodyEof = false
        var failure: Throwable? = null
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .build()
            client.newCall(request).execute().use { response ->
                responseCode = response.code
                responseHeaders = headersJson(response.headers.toMultimap())
                val source = requireNotNull(response.body).source()
                val buffer = Buffer()
                while (true) {
                    val read = source.read(buffer, bufferBytes.toLong())
                    if (read == -1L) {
                        reachedBodyEof = true
                        break
                    }
                    val bytes = buffer.readByteArray(read)
                    digest.update(bytes)
                    payloadBytes += bytes.size
                }
            }
        } catch (error: Throwable) {
            failure = error
        }
        return result("okhttp-$bufferBytes", started, responseCode, responseHeaders, payloadBytes,
            digest.digest(), reachedBodyEof, failure).put("readBufferBytes", bufferBytes)
    }

    private fun readWithOkHttpBodyStream(client: OkHttpClient, url: String, bufferBytes: Int): JSONObject {
        val started = android.os.SystemClock.elapsedRealtime()
        var responseCode: Int? = null
        var responseHeaders = JSONArray()
        val digest = MessageDigest.getInstance("SHA-256")
        var payloadBytes = 0L
        var reachedBodyEof = false
        var failure: Throwable? = null
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .build()
            client.newCall(request).execute().use { response ->
                responseCode = response.code
                responseHeaders = headersJson(response.headers.toMultimap())
                val input = requireNotNull(response.body).byteStream()
                val buffer = ByteArray(bufferBytes)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        reachedBodyEof = true
                        break
                    }
                    digest.update(buffer, 0, read)
                    payloadBytes += read
                }
            }
        } catch (error: Throwable) {
            failure = error
        }
        return result("okhttp-body-stream-$bufferBytes", started, responseCode, responseHeaders,
            payloadBytes, digest.digest(), reachedBodyEof, failure).put("readBufferBytes", bufferBytes)
    }

    private fun readWithHttpUrlConnection(url: String): JSONObject {
        val started = android.os.SystemClock.elapsedRealtime()
        var responseCode: Int? = null
        var responseHeaders = JSONArray()
        val digest = MessageDigest.getInstance("SHA-256")
        var payloadBytes = 0L
        var reachedBodyEof = false
        var failure: Throwable? = null
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept-Encoding", "identity")
            connection.setRequestProperty("Connection", "close")
            responseCode = connection.responseCode
            responseHeaders = headersJson(connection.headerFields.filterKeys { it != null })
            connection.inputStream.use { input ->
                val buffer = ByteArray(8_192)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) {
                        reachedBodyEof = true
                        break
                    }
                    digest.update(buffer, 0, read)
                    payloadBytes += read
                }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            connection.disconnect()
        }
        return result("httpurlconnection", started, responseCode, responseHeaders, payloadBytes,
            digest.digest(), reachedBodyEof, failure).put("readBufferBytes", 8_192)
    }

    private fun readChunkedWireDirectly(url: String): JSONObject {
        val started = android.os.SystemClock.elapsedRealtime()
        val uri = URI(url)
        val socket = Socket()
        val digest = MessageDigest.getInstance("SHA-256")
        val tail = TailRing(128)
        val rawChunkTerminator = ByteArrayOutputStream()
        var statusLine: String? = null
        var responseHeaders = JSONArray()
        var payloadBytes = 0L
        var wireBytes = 0L
        var reachedBodyEof = false
        var terminatorSeen = false
        var trailingBytes = 0L
        var failure: Throwable? = null
        try {
            socket.connect(InetSocketAddress(uri.host, uri.port), 5_000)
            socket.soTimeout = 15_000
            val output = socket.getOutputStream()
            val request = "GET ${uri.rawPath}${uri.rawQuery?.let { "?$it" } ?: ""} HTTP/1.1\r\n" +
                "Host: ${uri.host}:${uri.port}\r\n" +
                "Accept-Encoding: identity\r\n" +
                "Connection: close\r\n\r\n"
            output.write(request.toByteArray(Charsets.US_ASCII))
            output.flush()
            val input = socket.getInputStream()
            statusLine = readCrlfLine(input, tail).toString(Charsets.US_ASCII)
            wireBytes += statusLine!!.length + 2
            while (true) {
                val line = readCrlfLine(input, tail)
                wireBytes += line.size + 2
                if (line.isEmpty()) break
                responseHeaders.put(String(line, Charsets.US_ASCII))
            }
            while (true) {
                val sizeLineBytes = readCrlfLine(input, tail)
                wireBytes += sizeLineBytes.size + 2
                val sizeLine = String(sizeLineBytes, Charsets.US_ASCII)
                if (sizeLineBytes.isEmpty()) throw java.net.ProtocolException("empty chunk-size line")
                val sizeText = sizeLine.substringBefore(';').trim()
                val chunkBytes = sizeText.toLongOrNull(16)
                    ?: throw java.net.ProtocolException("invalid chunk-size line")
                if (chunkBytes == 0L) {
                    terminatorSeen = true
                    rawChunkTerminator.write(sizeLineBytes)
                    rawChunkTerminator.write(byteArrayOf(13, 10))
                    while (true) {
                        val trailer = readCrlfLine(input, tail)
                        wireBytes += trailer.size + 2
                        rawChunkTerminator.write(trailer)
                        rawChunkTerminator.write(byteArrayOf(13, 10))
                        if (trailer.isEmpty()) break
                    }
                    while (true) {
                        val extra = input.read()
                        if (extra == -1) {
                            reachedBodyEof = true
                            break
                        }
                        trailingBytes++
                        wireBytes++
                        tail.append(extra.toByte())
                    }
                    break
                }
                var remaining = chunkBytes
                val buffer = ByteArray(16_384)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read == -1) throw EOFException("EOF inside chunk body after $payloadBytes bytes")
                    digest.update(buffer, 0, read)
                    tail.append(buffer, 0, read)
                    payloadBytes += read
                    wireBytes += read
                    remaining -= read
                }
                val cr = input.read()
                val lf = input.read()
                if (cr != 13 || lf != 10) throw java.net.ProtocolException("chunk lacked CRLF terminator")
                tail.append(cr.toByte())
                tail.append(lf.toByte())
                wireBytes += 2
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            runCatching { socket.close() }
        }
        return JSONObject()
            .put("reader", "raw-socket-chunk-decoder")
            .put("startedElapsedRealtimeMs", started)
            .put("finishedElapsedRealtimeMs", android.os.SystemClock.elapsedRealtime())
            .put("responseCodeLine", statusLine ?: JSONObject.NULL)
            .put("responseHeaders", responseHeaders)
            .put("payloadBytes", payloadBytes)
            .put("payloadSha256", digest.digest().toHex())
            .put("wireBytes", wireBytes)
            .put("terminalChunkSeen", terminatorSeen)
            .put("terminalChunkHex", rawChunkTerminator.toByteArray().toHex())
            .put("rawTailHex", tail.snapshot().toHex())
            .put("trailingBytesAfterTerminator", trailingBytes)
            .put("reachedSocketEof", reachedBodyEof)
            .put("error", failure?.let(::throwableJson) ?: JSONObject.NULL)
    }

    private fun readCrlfLine(input: InputStream, tail: TailRing): ByteArray {
        val line = ByteArrayOutputStream()
        var previous = -1
        while (true) {
            val current = input.read()
            if (current == -1) throw EOFException("EOF before CRLF line terminator")
            tail.append(current.toByte())
            if (previous == 13 && current == 10) {
                val bytes = line.toByteArray()
                return bytes.copyOf(bytes.size - 1)
            }
            line.write(current)
            previous = current
        }
    }

    private fun readJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.useCaches = false
        return try {
            require(connection.responseCode == 200) { "Fixture returned HTTP ${connection.responseCode} for $url" }
            JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun headersJson(headers: Map<String, List<String>>): JSONArray = JSONArray().apply {
        headers.forEach { (name, values) ->
            values.forEach { value -> put("$name: $value") }
        }
    }

    private fun result(
        name: String,
        started: Long,
        status: Int?,
        headers: JSONArray,
        bytes: Long,
        digest: ByteArray,
        reachedEof: Boolean,
        failure: Throwable?
    ) = JSONObject()
        .put("reader", name)
        .put("startedElapsedRealtimeMs", started)
        .put("finishedElapsedRealtimeMs", android.os.SystemClock.elapsedRealtime())
        .put("status", status ?: JSONObject.NULL)
        .put("responseHeaders", headers)
        .put("payloadBytes", bytes)
        .put("payloadSha256", digest.toHex())
        .put("reachedBodyEof", reachedEof)
        .put("error", failure?.let(::throwableJson) ?: JSONObject.NULL)

    private fun throwableJson(error: Throwable): JSONObject {
        val causes = JSONArray()
        generateSequence(error) { it.cause }.take(8).forEach { cause ->
            causes.put(JSONObject()
                .put("type", cause.javaClass.name)
                .put("message", cause.message.orEmpty().take(300))
                .put("stack", JSONArray(cause.stackTrace.take(12).map { it.toString() })))
        }
        return JSONObject().put("causes", causes)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private class TailRing(private val capacity: Int) {
        private val bytes = ByteArray(capacity)
        private var next = 0
        private var size = 0

        fun append(value: Byte) {
            bytes[next] = value
            next = (next + 1) % capacity
            if (size < capacity) size++
        }

        fun append(value: ByteArray, offset: Int, length: Int) {
            val start = maxOf(offset, offset + length - capacity)
            for (index in start until offset + length) append(value[index])
        }

        fun snapshot(): ByteArray = ByteArray(size) { index ->
            bytes[(next - size + index + capacity) % capacity]
        }
    }
}
