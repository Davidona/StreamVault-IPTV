package com.streamvault.feature.live.phone

import com.streamvault.core.ui.companion.CompanionHttpParser
import com.streamvault.core.ui.companion.CompanionRequestException
import com.streamvault.core.ui.companion.MAX_ORDERING_BODY_BYTES
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One temporary LAN listener. The caller must perform TV-side parental authorization first. */
internal class PhoneOrderingServer(
    private val socket: ServerSocket,
    private val guard: PhoneSessionGuard,
    private val api: PhoneOrderingApi,
    private val expectedHost: String,
    private val page: String,
    private val script: String,
    private val onExpired: () -> Unit = {},
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(4)
    private var acceptJob: Job? = null

    @Synchronized fun start() {
        check(acceptJob == null && socket.isBound && !socket.isClosed)
        acceptJob = scope.launch {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: Exception) { break }
                if (!slots.tryAcquire()) { runCatching { client.close() }; continue }
                clients.add(client)
                scope.launch {
                    val timeout = launch { delay(20_000); runCatching { client.close() } }
                    try { handle(client) } finally {
                        timeout.cancel(); clients.remove(client); runCatching { client.close() }; slots.release()
                    }
                }
            }
        }
        scope.launch {
            while (!socket.isClosed) {
                delay(2_000)
                if (!guard.isActive()) { close(); onExpired(); break }
            }
        }
    }

    private suspend fun handle(client: Socket) {
        client.soTimeout = 5_000
        try {
            val request = CompanionHttpParser.read(client.getInputStream(), MAX_ORDERING_BODY_BYTES)
            if (request.headers["host"] != expectedHost) { respond(client, 403, "application/json", "{}"); return }
            when {
                request.method == "GET" && request.pathAndQuery == "/" -> respond(client, 200, "text/html", page)
                request.method == "GET" && request.pathAndQuery == "/app.js" -> respond(client, 200, "text/javascript", script)
                request.pathAndQuery.startsWith("/api/") -> {
                    val result = api.handle(request)
                    respond(client, result.status, "application/json", result.body, result.cookie)
                }
                else -> respond(client, 404, "application/json", "{}")
            }
        } catch (e: CompanionRequestException) {
            runCatching { respond(client, e.statusCode, "application/json", "{}") }
        } catch (_: Exception) {
            runCatching { respond(client, 500, "application/json", "{}") }
        }
    }

    private fun respond(client: Socket, status: Int, type: String, body: String, cookie: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found";
            409 -> "Conflict"; 411 -> "Length Required"; 413 -> "Payload Too Large"; 431 -> "Request Header Fields Too Large";
            else -> "Internal Server Error" }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\nContent-Type: $type; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n")
            append("Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'\r\n")
            if (cookie != null) append("Set-Cookie: ordering_session=$cookie; HttpOnly; SameSite=Strict; Path=/\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply { write(headers.toByteArray(Charsets.ISO_8859_1)); write(bytes); flush() }
    }

    override fun close() {
        guard.stop()
        runCatching { socket.close() }
        clients.forEach { runCatching { it.close() } }
        scope.cancel()
    }
}
