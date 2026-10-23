package com.streamvault.app.phone

import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.XmltvTimezonePolicy
import com.streamvault.domain.phone.PhoneAddKind
import com.streamvault.domain.phone.PhoneAddSession
import com.streamvault.domain.phone.PhoneAddState
import com.streamvault.domain.repository.EpgSourceRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.StremioAddonRepository
import com.streamvault.feature.provider.R as ProviderR
import com.streamvault.feature.settings.R as SettingsR
import com.streamvault.feature.stremio.R as StremioR
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val SESSION_MS = 5 * 60 * 1_000L
private const val MAX_BODY = 8_192

@Singleton
class PhoneAddSessionImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val addons: StremioAddonRepository,
    private val epg: EpgSourceRepository,
    private val providers: ProviderRepository,
) : PhoneAddSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val random = SecureRandom()
    private val slots = Semaphore(4)
    private val clients = ConcurrentHashMap.newKeySet<Job>()
    private val mutableState = MutableStateFlow(PhoneAddState())
    override val state: StateFlow<PhoneAddState> = mutableState.asStateFlow()
    private var server: ServerSocket? = null
    private var token: String? = null
    private var kind: PhoneAddKind? = null
    private var expiresAt = 0L

    override suspend fun start(kind: PhoneAddKind) = mutex.withLock {
        closeLocked()
        val address = lanAddress()
        if (address == null) {
            mutableState.value = PhoneAddState(kind = kind, error = true)
            return@withLock
        }
        try {
            val socket = ServerSocket().apply { bind(InetSocketAddress(InetAddress.getByName(address), 0), 4) }
            val secret = ByteArray(24).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            server = socket
            token = secret
            this.kind = kind
            expiresAt = System.currentTimeMillis() + SESSION_MS
            mutableState.value = PhoneAddState(kind = kind, url = "http://$address:${socket.localPort}/add?t=$secret")
            scope.launch { acceptLoop(socket, secret, kind) }
            scope.launch {
                delay(SESSION_MS)
                mutex.withLock { if (server === socket) closeLocked() }
            }
        } catch (error: Exception) {
            Log.w("PhoneAddSession", "Could not start phone add session", error)
            closeLocked()
            mutableState.value = PhoneAddState(kind = kind, error = true)
        }
    }

    override fun stop(kind: PhoneAddKind) {
        scope.launch { mutex.withLock { if (this@PhoneAddSessionImpl.kind == kind) closeLocked() } }
    }

    private fun closeLocked(completed: Boolean = false) {
        runCatching { server?.close() }
        server = null
        token = null
        val previousKind = kind
        kind = null
        mutableState.value = if (completed) PhoneAddState(kind = previousKind, completed = true) else PhoneAddState()
        clients.toList().forEach { it.cancel() }
    }

    private suspend fun acceptLoop(socket: ServerSocket, secret: String, sessionKind: PhoneAddKind) {
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (_: Exception) { break }
            if (!slots.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            val job = scope.launch(start = CoroutineStart.LAZY) { handle(client, socket, secret, sessionKind) }
            clients.add(job)
            job.invokeOnCompletion {
                clients.remove(job)
                slots.release()
                runCatching { client.close() }
            }
            job.start()
        }
    }

    private suspend fun handle(client: Socket, socket: ServerSocket, secret: String, sessionKind: PhoneAddKind) {
        client.use {
            try {
                client.soTimeout = 5_000
                val input = BufferedInputStream(client.getInputStream())
                val request = readLine(input) ?: return
                val parts = request.split(' ')
                if (parts.size != 3 || parts[2] != "HTTP/1.1") return respond(client, 400, "")
                val headers = mutableMapOf<String, String>()
                var headerCount = 0
                while (true) {
                    if (++headerCount > 30) return respond(client, 400, "")
                    val line = readLine(input) ?: return respond(client, 400, "")
                    if (line.isEmpty()) break
                    val colon = line.indexOf(':')
                    if (colon > 0) headers[line.substring(0, colon).lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
                }
                val strings = localizedContext(headers["accept-language"])
                if (server !== socket || token != secret || System.currentTimeMillis() >= expiresAt) {
                    return respond(client, 403, page(strings.getString(ProviderR.string.setup_phone_pairing_error)))
                }
                if (parts[0] == "GET" && parts[1] == "/add?t=$secret") {
                    return respond(client, 200, form(strings, sessionKind, secret))
                }
                if (parts[0] != "POST" || parts[1] != "/submit" ||
                    headers["content-type"]?.substringBefore(';') != "application/x-www-form-urlencoded") {
                    return respond(client, 404, "")
                }
                val length = headers["content-length"]?.toIntOrNull()
                if (length == null || length !in 1..MAX_BODY) return respond(client, 400, "")
                val body = ByteArray(length)
                var received = 0
                while (received < length) {
                    val count = input.read(body, received, length - received)
                    if (count < 0) return respond(client, 400, "")
                    received += count
                }
                val fields = parseForm(String(body, StandardCharsets.UTF_8))
                if (fields == null || fields["token"] != secret) return respond(client, 403, "")
                val acquired = mutex.withLock {
                    if (server !== socket || mutableState.value.busy) false
                    else { mutableState.value = mutableState.value.copy(busy = true); true }
                }
                if (!acquired) return respond(client, 409, "")
                val result = try { submit(sessionKind, fields) } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.w("PhoneAddSession", "Phone submission failed", error)
                    false
                }
                if (result) {
                    respond(client, 200, page(strings.getString(ProviderR.string.setup_phone_pairing_complete)))
                    mutex.withLock { if (server === socket) closeLocked(completed = true) }
                } else {
                    mutex.withLock { if (server === socket) mutableState.value = mutableState.value.copy(busy = false) }
                    respond(client, 400, form(strings, sessionKind, secret, error = true))
                }
            } catch (error: Exception) {
                Log.w("PhoneAddSession", "Phone request failed", error)
            }
        }
    }

    private suspend fun submit(kind: PhoneAddKind, fields: Map<String, String>): Boolean = when (kind) {
        PhoneAddKind.STREMIO -> {
            val url = fields["url"]?.trim().orEmpty()
            url.isNotEmpty() && addons.installAddon(url) is Result.Success
        }
        PhoneAddKind.EPG -> {
            val name = fields["name"]?.trim().orEmpty()
            val url = fields["url"]?.trim().orEmpty()
            val zone = fields["timezone"]?.trim()?.takeIf(String::isNotEmpty)
            if (name.isEmpty() || url.isEmpty()) false else {
                val policy = when {
                    zone == null -> XmltvTimezonePolicy.REQUIRE_OFFSET
                    zone.equals("UTC", ignoreCase = true) -> XmltvTimezonePolicy.UTC
                    else -> XmltvTimezonePolicy.EXPLICIT_ZONE
                }
                val added = epg.addSource(name, url, policy, zone).getOrNull()
                if (added == null) false else {
                    val providerId = fields["provider"]?.toLongOrNull()
                    if (fields["provider"].orEmpty().isNotEmpty() &&
                        (providerId == null || providers.getProviders().first().none { it.id == providerId })) {
                        epg.deleteSource(added.id)
                        false
                    } else if (providerId != null) {
                        val assignments = epg.getAssignmentsForProvider(providerId).first()
                        val priority = (assignments.maxOfOrNull { it.priority } ?: 0) + 1
                        if (epg.assignSourceToProvider(providerId, added.id, priority) is Result.Success) {
                            scope.launch { runCatching { epg.refreshSource(added.id) } }
                            true
                        } else {
                            epg.deleteSource(added.id)
                            false
                        }
                    } else {
                        true
                    }
                }
            }
        }
    }

    private suspend fun form(strings: Context, kind: PhoneAddKind, secret: String, error: Boolean = false): String {
        val title = if (kind == PhoneAddKind.STREMIO) strings.getString(StremioR.string.stremio_install_title)
            else strings.getString(SettingsR.string.settings_epg_add_source)
        val description = if (kind == PhoneAddKind.STREMIO) strings.getString(StremioR.string.stremio_install_description)
            else strings.getString(SettingsR.string.settings_epg_sources_description)
        val urlLabel = if (kind == PhoneAddKind.STREMIO) strings.getString(StremioR.string.stremio_install_url_label)
            else strings.getString(ProviderR.string.setup_tab_url)
        val nameField = if (kind == PhoneAddKind.EPG) input("name", strings.getString(SettingsR.string.settings_epg_source_name_hint)) else ""
        val timezoneField = if (kind == PhoneAddKind.EPG) input("timezone", strings.getString(SettingsR.string.settings_epg_source_timezone_hint), required = false) else ""
        val providerField = if (kind == PhoneAddKind.EPG) {
            val options = providers.getProviders().first().joinToString("") {
                "<option value='${it.id.toString().html()}'>${it.name.html()}</option>"
            }
            "<label for=\"provider\">${strings.getString(SettingsR.string.settings_epg_assignments_title).html()}</label>" +
                "<select id=\"provider\" name=\"provider\"><option value=\"\"></option>$options</select>"
        } else ""
        val button = if (kind == PhoneAddKind.STREMIO) strings.getString(StremioR.string.stremio_install_confirm)
            else strings.getString(SettingsR.string.settings_epg_add_source_action)
        val errorText = if (error) "<p class=\"form-error\" role=\"alert\">${strings.getString(ProviderR.string.setup_phone_pairing_error).html()}</p>" else ""
        val warning = strings.getString(ProviderR.string.setup_phone_pairing_security_warning).html()
        val idle = strings.getString(ProviderR.string.setup_phone_pairing_idle).html()
        val brand = strings.getString(ProviderR.string.setup_title_streamvault).html()
        val sameWifi = strings.getString(ProviderR.string.setup_phone_pairing_same_wifi).html()
        val fields = buildString {
            append(nameField)
            append(input("url", urlLabel))
            append(timezoneField)
            append(providerField)
        }
        val body = """
            <main>
              <div class="brand">
                <span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 32 32" fill="none"><path d="M8 9h16v11H8z" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="m14 12 6 3.5-6 3.5v-7Z" fill="currentColor"/><path d="M12 24h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg></span>
                <span>$brand</span>
              </div>
              <header>
                <span class="eyebrow"><span class="status-dot" aria-hidden="true"></span>$idle</span>
                <h1>${title.html()}</h1>
                <p>${description.html()}</p>
              </header>
              <div class="form-card">
                <form method="post" action="/submit" accept-charset="utf-8">
                  <input type="hidden" name="token" value="${secret.html()}">
                  <div class="details-section">
                    $errorText
                    $fields
                  </div>
                  <div class="submit-section">
                    <button type="submit"><span>${button.html()}</span><span class="button-arrow" aria-hidden="true">&rarr;</span></button>
                  </div>
                </form>
              </div>
              <aside class="warning"><span class="warning-icon" aria-hidden="true">!</span><p>$warning</p></aside>
              <footer>$sameWifi</footer>
            </main>
        """.trimIndent()
        return page(body)
    }

    private fun input(name: String, label: String, required: Boolean = true): String {
        val keyboardHints = if (name == "url") {
            "autocomplete=\"off\" dir=\"ltr\""
        } else {
            "autocomplete=\"off\""
        }
        val requiredAttribute = if (required) "required" else ""
        return "<label for=\"$name\">${label.html()}</label><input id=\"$name\" name=\"$name\" $keyboardHints " +
            "$requiredAttribute maxlength=\"2048\">"
    }

    private fun page(body: String): String {
        val language = context.resources.configuration.locales[0].toLanguageTag().html()
        val direction = if (context.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"
        val pageTitle = context.getString(ProviderR.string.setup_phone_pairing_title).html()
        return """
            <!doctype html>
            <html lang="$language" dir="$direction">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>$pageTitle</title>
              <meta name="color-scheme" content="dark">
              <meta name="theme-color" content="#0b0e14">
              <style>${pageStyles()}</style>
            </head>
            <body>$body</body>
            </html>
        """.trimIndent()
    }

    private fun pageStyles(): String = """
        *{box-sizing:border-box}
        :root{color-scheme:dark;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;color:#eff2f8;background:#0b0e14}
        body{margin:0;min-height:100vh;min-height:100svh;padding:48px 24px;background:radial-gradient(ellipse at 50% 0,rgba(101,85,211,.14),transparent 65%);-webkit-font-smoothing:antialiased}
        main{max-width:600px;margin:0 auto}
        .brand{display:flex;align-items:center;gap:11px;font-size:20px;font-weight:750;letter-spacing:-.6px;margin-bottom:46px}
        .brand-mark{display:grid;place-items:center;width:40px;height:40px;border-radius:12px;color:#d7cfff;background:linear-gradient(140deg,#7660d1,#493381);box-shadow:0 4px 20px #7960d122}
        .brand-mark svg{width:30px;height:30px}
        header{margin-bottom:28px}
        .eyebrow{display:inline-flex;align-items:center;gap:7px;font-size:11px;font-weight:700;letter-spacing:1.4px;text-transform:uppercase;color:#aba2d5;margin-bottom:15px}
        .status-dot{height:6px;width:6px;border-radius:50%;background:#aa97ef;box-shadow:0 0 10px #aa97ef66}
        h1{font-size:clamp(28px,5vw,36px);line-height:1.2;letter-spacing:-1.2px;margin:0 0 12px;font-weight:750;overflow-wrap:anywhere}
        p{font-size:14px;line-height:1.65;color:#9ca4b6;margin:0}
        .form-card{background:linear-gradient(145deg,#191c28,#131620);border:1px solid #2b2e3f;border-radius:22px;box-shadow:0 24px 80px #00000035;overflow:hidden}
        form{margin:0}
        .details-section{padding:8px 28px 6px}
        label{display:block;margin:19px 0 9px;font-size:12px;font-weight:600;color:#c4c8d5;letter-spacing:.2px}
        input,select{display:block;width:100%;min-width:0;min-height:50px;padding:13px 14px;border-radius:10px;border:1px solid #333747;background:#0f121c;color:#f0f1f6;font:inherit;font-size:16px;transition:border-color .15s,box-shadow .15s}
        select{cursor:pointer;padding-inline-end:32px}
        input:hover,select:hover{border-color:#515368}
        input:focus-visible,select:focus-visible{outline:0;border-color:#ac96f3;box-shadow:0 0 0 3px #a68be51c}
        .form-error{color:#e6ad9b;margin:0 0 16px}
        .submit-section{padding:24px 28px 28px}
        button{display:flex;align-items:center;justify-content:center;gap:14px;width:100%;min-height:52px;padding:14px 20px;border:1px solid #c4b5f6;border-radius:11px;background:linear-gradient(110deg,#c0acf6,#a98aeb);color:#1d1236;font:inherit;font-weight:750;font-size:15px;cursor:pointer;box-shadow:0 4px 20px #a98aeb16;transition:filter .15s,transform .15s}
        button:hover{filter:brightness(1.09)}button:active{transform:translateY(1px)}
        button:focus-visible{outline:2px solid #e0d6ff;outline-offset:4px}
        .button-arrow{font-size:21px;line-height:1}[dir=rtl] .button-arrow{transform:scaleX(-1)}
        .warning{display:flex;align-items:flex-start;gap:11px;border:1px solid #d4a76526;border-radius:12px;background:#d4a76507;padding:14px 16px;margin-top:20px}
        .warning p{font-size:12px;line-height:1.6;color:#b9a789}
        .warning-icon{display:grid;place-items:center;flex-shrink:0;width:17px;height:17px;border:1px solid #b9a789;border-radius:50%;font-size:11px;font-weight:700;color:#b9a789;margin-top:2px}
        footer{padding:20px 20px 0;color:#828a9e;font-size:11px;line-height:1.7;text-align:center}
        @media(max-width:480px){body{padding:28px 20px}.brand{margin-bottom:34px}.details-section{padding:4px 20px}.submit-section{padding:22px 20px}.form-card{border-radius:18px}}
        @media(prefers-reduced-motion:reduce){*{transition:none!important}}
    """.trimIndent()

    private fun String.html(): String = replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    private fun parseForm(body: String): Map<String, String>? = try {
        val values = mutableMapOf<String, String>()
        body.split('&').forEach {
            val index = it.indexOf('=')
            if (index <= 0) return null
            val key = URLDecoder.decode(it.substring(0, index), "UTF-8")
            if (key in values) return null
            values[key] = URLDecoder.decode(it.substring(index + 1), "UTF-8")
        }
        values
    } catch (_: IllegalArgumentException) { null }

    private fun readLine(input: BufferedInputStream): String? {
        val bytes = ArrayList<Byte>()
        while (bytes.size <= 4_096) {
            val byte = input.read()
            if (byte < 0) return null
            if (byte == 10) return bytes.dropLastWhile { it == 13.toByte() }.toByteArray().toString(StandardCharsets.UTF_8)
            bytes += byte.toByte()
        }
        return null
    }

    private fun respond(client: Socket, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 $status\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'\r\nConnection: close\r\n\r\n"
        val output: OutputStream = client.getOutputStream()
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun localizedContext(header: String?): Context {
        val language = header?.substringBefore(',')?.substringBefore(';')?.trim()?.takeIf(String::isNotEmpty) ?: return context
        val config = Configuration(context.resources.configuration)
        config.setLocale(Locale.forLanguageTag(language))
        return context.createConfigurationContext(config)
    }

    private fun lanAddress(): String? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val active = connectivity?.activeNetwork
        val caps = active?.let(connectivity::getNetworkCapabilities)
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true &&
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) != true) return null
        return connectivity.getLinkProperties(active)?.linkAddresses?.mapNotNull { it.address as? Inet4Address }
            ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
            ?: NetworkInterface.getNetworkInterfaces()?.toList()?.asSequence()
                ?.filter { it.name.startsWith("wlan") || it.name.startsWith("eth") }
                ?.flatMap { it.inetAddresses.toList().asSequence() }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }?.hostAddress
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class PhoneAddSessionModule {
    @Binds abstract fun bindPhoneAddSession(impl: PhoneAddSessionImpl): PhoneAddSession
}
