package com.streamvault.torrent

import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalRangeServerTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private val file = "0123456789".toByteArray()

    @Test
    fun `serves only loopback and streams byte ranges`() {
        val server = LocalRangeServer(fakeStreamer())
        try {
            val connection = connection(server.baseUrl)
            connection.setRequestProperty("Range", "bytes=3-6")
            assertEquals("127.0.0.1", URL(server.baseUrl).host)
            assertEquals(206, connection.responseCode)
            assertEquals("bytes 3-6/10", connection.getHeaderField("Content-Range"))
            assertArrayEquals("3456".toByteArray(), connection.inputStream.use { it.readBytes() })
            connection.disconnect()
        } finally {
            server.close()
        }
    }

    @Test
    fun `supports head and rejects invalid ranges`() {
        val server = LocalRangeServer(fakeStreamer())
        try {
            val head = connection(server.baseUrl).apply { requestMethod = "HEAD" }
            assertEquals(200, head.responseCode)
            assertEquals("10", head.getHeaderField("Content-Length"))
            head.disconnect()

            val invalid = connection(server.baseUrl).apply {
                setRequestProperty("Range", "bytes=10-")
            }
            assertEquals(416, invalid.responseCode)
            assertEquals("bytes */10", invalid.getHeaderField("Content-Range"))
            invalid.disconnect()
        } finally {
            server.close()
        }
    }

    private fun connection(baseUrl: String): HttpURLConnection =
        (URL("$baseUrl/stream/$token").openConnection() as HttpURLConnection).apply {
            connectTimeout = 2_000
            readTimeout = 2_000
        }

    private fun fakeStreamer() = object : TorrentFileStreamer {
        override fun fileInfo(token: String): TorrentFileInfo {
            require(token == this@LocalRangeServerTest.token)
            return TorrentFileInfo("sample.mp4", file.size.toLong(), "video/mp4")
        }

        override fun stream(token: String, start: Long, endInclusive: Long, output: java.io.OutputStream) {
            require(token == this@LocalRangeServerTest.token)
            output.write(file, start.toInt(), (endInclusive - start + 1L).toInt())
            output.flush()
        }
    }
}
