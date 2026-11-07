package com.streamvault.feature.live.phone

import com.google.common.truth.Truth.assertThat
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.mock

class PhoneOrderingServerTest {
    @Test fun `server limits origins and issues a protected session cookie`() = runTest {
        val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val host = "127.0.0.1:${listener.localPort}"
        val guard = PhoneSessionGuard(7, host, 0)
        val code = guard.pairingCode()!!
        val api = PhoneOrderingApi(guard, mock(), PhoneLabels("Favorites", "Recent", "All")) { 0 }
        val server = PhoneOrderingServer(listener, guard, api, host, "page", "script")
        server.start()
        try {
            val page = call(listener.localPort, "GET / HTTP/1.1\r\nHost: $host\r\n\r\n")
            assertThat(page).contains("HTTP/1.1 200 OK")
            assertThat(page).contains("Content-Security-Policy:")
            assertThat(call(listener.localPort, "GET / HTTP/1.1\r\nHost: evil.test\r\n\r\n")).contains("403 Forbidden")
            val body = """{"secret":"$code"}"""
            val pair = call(listener.localPort, "POST /api/pair HTTP/1.1\r\nHost: $host\r\nOrigin: http://$host\r\nContent-Type: application/json\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body")
            assertThat(pair).contains("200 OK")
            assertThat(pair).contains("HttpOnly; SameSite=Strict; Path=/")
            assertThat(pair).doesNotContain(code)
        } finally { server.close() }
        assertThat(guard.pairingCode()).isNull()
        assertThat(listener.isClosed).isTrue()
    }

    private fun call(port: Int, request: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 5_000
        socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }
}
