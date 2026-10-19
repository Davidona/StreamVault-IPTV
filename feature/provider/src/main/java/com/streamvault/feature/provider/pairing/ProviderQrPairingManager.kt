package com.streamvault.feature.provider.pairing

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.streamvault.domain.model.ChannelLogoSourcePolicy
import com.streamvault.domain.model.GuideSourcePolicy
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderXtreamLiveSyncMode
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerTransportChallenge
import com.streamvault.domain.model.StalkerTransportChallengeReason
import com.streamvault.domain.usecase.JellyfinProviderSetupCommand
import com.streamvault.domain.usecase.M3uProviderSetupCommand
import com.streamvault.domain.usecase.StalkerProviderSetupCommand
import com.streamvault.domain.usecase.ValidateAndAddProvider
import com.streamvault.domain.usecase.ValidateAndAddProviderResult
import com.streamvault.domain.usecase.XtreamProviderSetupCommand
import com.streamvault.feature.provider.R
import com.streamvault.feature.provider.setup.ProviderSetupErrorMessages
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.EnumMap
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val PAIRING_SESSION_MS = 5 * 60 * 1_000L
private const val PAIRING_QR_SIZE = 384
private const val CLIENT_READ_TIMEOUT_MS = 5_000
private const val CLIENT_REQUEST_DEADLINE_MS = 20_000L
private const val MAX_CONCURRENT_PAIRING_CLIENTS = 4
private const val TAG = "ProviderQrPairing"

@Singleton
class ProviderQrPairingManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val validateAndAddProvider: ValidateAndAddProvider,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val secureRandom = SecureRandom()
    private val lifecycleMutex = Mutex()
    private val stateLock = Any()
    private val clientSlots = Semaphore(MAX_CONCURRENT_PAIRING_CLIENTS)
    private val _state = MutableStateFlow(ProviderQrPairingState())
    val state: StateFlow<ProviderQrPairingState> = _state.asStateFlow()

    @Volatile
    private var activeSession: PairingSession? = null

    suspend fun startPairing() {
        var unclaimedSocket: ServerSocket? = null
        try {
            lifecycleMutex.withLock {
                val previousSession = synchronized(stateLock) {
                    activeSession.also {
                        activeSession = null
                        _state.value = ProviderQrPairingState()
                    }
                }
                previousSession?.let(::cancelSessionWork)

                try {
                    val host = withContext(Dispatchers.IO) { resolveLanIpv4Address() }
                    if (host.isNullOrBlank()) {
                        setErrorState()
                        return@withLock
                    }

                    val socket = withContext(Dispatchers.IO) {
                        ServerSocket().apply {
                            bind(InetSocketAddress(InetAddress.getByName(host), 0), 8)
                        }
                    }
                    unclaimedSocket = socket

                    val token = generateToken()
                    val url = "http://$host:${socket.localPort}/pair?t=$token"
                    val expiresAt = System.currentTimeMillis() + PAIRING_SESSION_MS
                    val qrBitmap = withContext(Dispatchers.IO) { createQrBitmap(url) }
                    val session = PairingSession(
                        token = token,
                        expiresAtMs = expiresAt,
                        serverSocket = socket,
                    )
                    unclaimedSocket = null
                    synchronized(stateLock) {
                        activeSession = session
                        _state.value = ProviderQrPairingState(
                            status = ProviderQrPairingStatus.READY,
                            url = url,
                            qrBitmap = qrBitmap,
                            expiresAtMs = expiresAt,
                            message = context.getString(R.string.setup_phone_pairing_same_wifi),
                        )
                    }

                    session.acceptJob = scope.launch { acceptLoop(session) }
                    session.timeoutJob = scope.launch {
                        delay(PAIRING_SESSION_MS)
                        stopSession(session, errorState())
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Could not start provider pairing")
                    runCatching { unclaimedSocket?.close() }
                    unclaimedSocket = null
                    setErrorState()
                }
            }
        } finally {
            runCatching { unclaimedSocket?.close() }
        }
    }

    fun stopPairing() {
        scope.launch { stopPairingSession() }
    }

    private suspend fun stopPairingSession() {
        val oldSession = lifecycleMutex.withLock {
            synchronized(stateLock) {
                activeSession.also {
                    activeSession = null
                    _state.value = ProviderQrPairingState()
                }
            }
        }
        oldSession?.let(::cancelSessionWork)
    }

    private suspend fun stopSession(session: PairingSession, finalState: ProviderQrPairingState) {
        val stopped = lifecycleMutex.withLock {
            synchronized(stateLock) {
                if (activeSession !== session) {
                    false
                } else {
                    activeSession = null
                    _state.value = finalState
                    true
                }
            }
        }
        if (stopped) cancelSessionWork(session)
    }

    private fun cancelSessionWork(session: PairingSession) {
        session.lease.invalidate()
        runCatching { session.serverSocket.close() }
        session.clientSockets.toList().forEach { client -> runCatching { client.close() } }
        session.acceptJob?.cancel()
        session.timeoutJob?.cancel()
        session.clientJobs.toList().forEach { it.cancel() }
    }

    private suspend fun acceptLoop(session: PairingSession) {
        while (isCurrentSession(session) && !session.serverSocket.isClosed) {
            val client = runCatching { session.serverSocket.accept() }.getOrNull() ?: break
            if (!clientSlots.tryAcquire()) {
                runCatching {
                    client.soTimeout = CLIENT_READ_TIMEOUT_MS
                    writeResponse(client.getOutputStream(), 503, "text/plain; charset=utf-8", "")
                }
                runCatching { client.close() }
                continue
            }

            session.clientSockets.add(client)
            val clientJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    handleClient(session, client)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w(TAG, "Pairing client request failed")
                    runCatching { client.close() }
                }
            }
            session.clientJobs.add(clientJob)
            clientJob.invokeOnCompletion {
                runCatching { client.close() }
                session.clientSockets.remove(client)
                session.clientJobs.remove(clientJob)
                clientSlots.release()
            }
            val started = synchronized(stateLock) {
                activeSession === session && !session.serverSocket.isClosed && clientJob.start()
            }
            if (!started) clientJob.cancel()
        }
        if (isActiveSession(session)) stopSession(session, errorState())
    }

    private suspend fun handleClient(session: PairingSession, socket: Socket) {
        socket.use { client ->
            client.soTimeout = CLIENT_READ_TIMEOUT_MS
            val requestDeadline = scope.launch {
                delay(CLIENT_REQUEST_DEADLINE_MS)
                runCatching { client.close() }
            }
            val request = try {
                ProviderPairingHttpParser.read(client.getInputStream())
            } catch (error: ProviderPairingRequestException) {
                writeResponse(client.getOutputStream(), error.statusCode, "text/plain; charset=utf-8", "")
                return
            } catch (error: SocketTimeoutException) {
                writeResponse(client.getOutputStream(), 408, "text/plain; charset=utf-8", "")
                return
            } finally {
                requestDeadline.cancel()
            }
            val phoneContext = localizedContext(request.headers["accept-language"])
            val path = request.pathAndQuery.substringBefore('?')

            if (!isCurrentSession(session)) {
                writeResponse(client.getOutputStream(), 403, "text/plain; charset=utf-8", "")
                return
            }

            when {
                request.method == "GET" && path == "/pair" -> {
                    val token = queryParams(request.pathAndQuery)?.get("t")
                    if (!isTokenValid(session, token)) {
                        writeHtml(client.getOutputStream(), 403, errorPage(phoneContext))
                    } else {
                        writeHtml(client.getOutputStream(), 200, formPage(session.token, phoneContext))
                    }
                }

                request.method == "POST" && path == "/submit" -> {
                    val contentType = request.headers["content-type"]
                        ?.substringBefore(';')
                        ?.trim()
                        ?.lowercase(Locale.US)
                    if (contentType != "application/x-www-form-urlencoded") {
                        writeResponse(client.getOutputStream(), 415, "text/plain; charset=utf-8", "")
                        return
                    }

                    val form = parseForm(request.body)
                    if (form == null) {
                        writeHtml(client.getOutputStream(), 400, errorPage(phoneContext))
                        return
                    }
                    when (beginSubmission(session, form["token"], System.currentTimeMillis())) {
                        null -> {
                            writeHtml(client.getOutputStream(), 403, errorPage(phoneContext))
                            return
                        }
                        false -> {
                            writeHtml(client.getOutputStream(), 409, errorPage(phoneContext))
                            return
                        }
                        true -> Unit
                    }

                    updateSessionState(
                        session,
                        ProviderQrPairingStatus.RECEIVING,
                        context.getString(R.string.setup_phone_pairing_receiving),
                    )
                    try {
                        val result = try {
                            addProviderFromForm(form, session, phoneContext)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            Log.w(TAG, "Provider setup from phone failed")
                            val type = form["type"].orEmpty().lowercase(Locale.US)
                            ProviderPairingSubmitResult.Failed(
                                providerErrorMessage(
                                    errorFormatterType(type, form["serverUrl"].orEmpty()),
                                    ValidateAndAddProviderResult.Error(
                                        message = error.message ?: phoneContext.getString(
                                            R.string.setup_phone_pairing_error_help,
                                        ),
                                        exception = error,
                                    ),
                                    phoneContext,
                                ),
                            )
                        }

                        when (result) {
                            is ProviderPairingSubmitResult.Added -> {
                                writeHtml(client.getOutputStream(), 200, successPage(phoneContext, result.warning))
                                stopSession(
                                    session,
                                    ProviderQrPairingState(
                                        status = ProviderQrPairingStatus.COMPLETE,
                                        message = context.getString(R.string.setup_phone_pairing_complete),
                                        createdProviderId = result.providerId,
                                        createdProviderName = result.providerName,
                                        completionWarning = result.warning,
                                        offerCombinedAttach = result.offerCombinedAttach,
                                    ),
                                )
                            }

                            is ProviderPairingSubmitResult.StalkerTransportConsentRequired -> {
                                updateSessionStalkerPrompt(
                                    session = session,
                                    message = phoneContext.getString(R.string.setup_phone_pairing_continue_on_tv),
                                    command = result.command,
                                    challenge = result.challenge,
                                )
                                writeHtml(client.getOutputStream(), 400, errorPage(phoneContext, result.details))
                            }

                            is ProviderPairingSubmitResult.StalkerVerificationInconclusive -> {
                                updateSessionStalkerPrompt(
                                    session = session,
                                    message = phoneContext.getString(R.string.setup_phone_pairing_continue_on_tv),
                                    command = result.command,
                                    verificationInconclusive = result.verificationMessage,
                                )
                                writeHtml(client.getOutputStream(), 400, errorPage(phoneContext, result.details))
                            }

                            is ProviderPairingSubmitResult.Failed -> {
                                updateSessionState(
                                    session,
                                    ProviderQrPairingStatus.READY,
                                    context.getString(R.string.setup_phone_pairing_error_help),
                                )
                                writeHtml(client.getOutputStream(), 400, errorPage(phoneContext, result.message))
                            }
                        }
                    } finally {
                        session.lease.endSubmission()
                    }
                }

                request.method != "GET" && request.method != "POST" ->
                    writeResponse(client.getOutputStream(), 405, "text/plain; charset=utf-8", "")

                else -> writeResponse(client.getOutputStream(), 404, "text/plain; charset=utf-8", "")
            }
        }
    }

    private suspend fun addProviderFromForm(
        form: Map<String, String>,
        session: PairingSession,
        phoneContext: Context,
    ): ProviderPairingSubmitResult {
        val type = form["type"].orEmpty().lowercase(Locale.US)
        val name = form["name"].orEmpty().trim()
        val serverUrl = form["serverUrl"].orEmpty()
        val xtreamPlaylistUrl = type == "xtream" && isXtreamPlaylistUrl(serverUrl)
        val stalkerCommand = if (type == "stalker") {
            StalkerProviderSetupCommand(
                portalUrl = serverUrl,
                macAddress = form["macAddress"].orEmpty(),
                authMode = StalkerAuthMode.AUTO,
                username = form["username"].orEmpty(),
                password = form["password"].orEmpty(),
                name = name,
                epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                guideSourcePolicy = GuideSourcePolicy.AUTO,
                channelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
            )
        } else {
            null
        }
        val onProgress: (String) -> Unit = { progress ->
            updateSessionState(
                session,
                ProviderQrPairingStatus.RECEIVING,
                progress.ifBlank { context.getString(R.string.setup_phone_pairing_receiving) },
            )
        }

        val result = when (type) {
            "m3u" -> validateAndAddProvider.addM3u(
                M3uProviderSetupCommand(
                    url = form["m3uUrl"].orEmpty(),
                    name = name,
                    epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                    guideSourcePolicy = GuideSourcePolicy.AUTO,
                    channelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
                ),
                onProgress = onProgress,
            )

            "stalker" -> validateAndAddProvider.loginStalker(
                checkNotNull(stalkerCommand),
                onProgress = onProgress,
            )

            "jellyfin" -> validateAndAddProvider.loginJellyfin(
                JellyfinProviderSetupCommand(
                    serverUrl = form["serverUrl"].orEmpty(),
                    username = form["username"].orEmpty(),
                    password = form["password"].orEmpty(),
                    name = name,
                ),
                onProgress = onProgress,
            )

            "xtream" -> if (xtreamPlaylistUrl) {
                // Match the native M3U URL flow, which recognizes Xtream get.php links and
                // converts their embedded credentials into an Xtream provider.
                validateAndAddProvider.addM3u(
                    M3uProviderSetupCommand(
                        url = serverUrl,
                        name = name,
                        epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                        guideSourcePolicy = GuideSourcePolicy.AUTO,
                        channelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
                    ),
                    onProgress = onProgress,
                )
            } else {
                validateAndAddProvider.loginXtream(
                    XtreamProviderSetupCommand(
                        serverUrl = serverUrl,
                        username = form["username"].orEmpty(),
                        password = form["password"].orEmpty(),
                        name = name,
                        epgSyncMode = ProviderEpgSyncMode.BACKGROUND,
                        xtreamLiveSyncMode = ProviderXtreamLiveSyncMode.AUTO,
                        guideSourcePolicy = GuideSourcePolicy.AUTO,
                        channelLogoSourcePolicy = ChannelLogoSourcePolicy.SUPPLIER_PREFERRED,
                    ),
                    onProgress = onProgress,
                )
            }

            else -> return ProviderPairingSubmitResult.Failed(
                phoneContext.getString(R.string.setup_phone_pairing_error_help),
            )
        }

        return when (result) {
            is ValidateAndAddProviderResult.Success -> ProviderPairingSubmitResult.Added(
                providerId = result.provider.id,
                providerName = result.provider.name,
                offerCombinedAttach = type == "m3u" || xtreamPlaylistUrl,
            )

            is ValidateAndAddProviderResult.SavedWithWarning -> ProviderPairingSubmitResult.Added(
                providerId = result.provider.id,
                providerName = result.provider.name,
                warning = result.warning,
                offerCombinedAttach = type == "m3u" || xtreamPlaylistUrl,
            )

            is ValidateAndAddProviderResult.ValidationError ->
                ProviderPairingSubmitResult.Failed(result.message)

            is ValidateAndAddProviderResult.TransportConsentRequired -> {
                val command = stalkerCommand ?: return ProviderPairingSubmitResult.Failed(
                    phoneContext.getString(R.string.setup_phone_pairing_error_help),
                )
                ProviderPairingSubmitResult.StalkerTransportConsentRequired(
                    challenge = result.challenge,
                    command = command,
                    details = stalkerTransportChallengeMessage(result.challenge, phoneContext),
                )
            }

            is ValidateAndAddProviderResult.VerificationInconclusive -> {
                val command = stalkerCommand ?: return ProviderPairingSubmitResult.Failed(
                    phoneContext.getString(R.string.setup_phone_pairing_error_help),
                )
                ProviderPairingSubmitResult.StalkerVerificationInconclusive(
                    command = command,
                    verificationMessage = result.message,
                    details = listOf(
                        phoneContext.getString(R.string.stalker_verification_inconclusive_title),
                        phoneContext.getString(R.string.stalker_verification_inconclusive_body),
                        phoneContext.getString(R.string.setup_phone_pairing_continue_on_tv),
                    ).joinToString("\n\n"),
                )
            }

            is ValidateAndAddProviderResult.Error -> ProviderPairingSubmitResult.Failed(
                providerErrorMessage(errorFormatterType(type, serverUrl), result, phoneContext),
            )
        }
    }

    private fun errorFormatterType(type: String, serverUrl: String): String =
        if (type == "xtream" && isXtreamPlaylistUrl(serverUrl)) "m3u" else type

    private fun isXtreamPlaylistUrl(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        val scheme = uri.scheme?.lowercase(Locale.US)
        val queryKeys = uri.rawQuery.orEmpty()
            .split('&')
            .mapNotNull { part ->
                runCatching {
                    URLDecoder.decode(part.substringBefore('='), StandardCharsets.UTF_8.name())
                }.getOrNull()
            }
            .toSet()
        (scheme == "http" || scheme == "https") &&
            !uri.host.isNullOrBlank() &&
            uri.path.orEmpty().endsWith("/get.php", ignoreCase = true) &&
            "username" in queryKeys &&
            "password" in queryKeys
    }.getOrDefault(false)

    private fun providerErrorMessage(
        type: String,
        result: ValidateAndAddProviderResult.Error,
        strings: Context,
    ): String {
        val message = when (type) {
            "m3u" -> ProviderSetupErrorMessages.m3u(result)
            "stalker" -> ProviderSetupErrorMessages.stalker(result)
            "jellyfin" -> ProviderSetupErrorMessages.jellyfin(result)
            "xtream" -> ProviderSetupErrorMessages.xtream(result)
            else -> result.message
        }
        return message.ifBlank { strings.getString(R.string.setup_phone_pairing_error_help) }
    }

    private fun updateSessionStalkerPrompt(
        session: PairingSession,
        message: String,
        command: StalkerProviderSetupCommand,
        challenge: StalkerTransportChallenge? = null,
        verificationInconclusive: String? = null,
    ) {
        synchronized(stateLock) {
            if (activeSession === session) {
                _state.value = _state.value.copy(
                    status = ProviderQrPairingStatus.READY,
                    message = message,
                    pendingStalkerCommand = command,
                    stalkerTransportChallenge = challenge,
                    stalkerVerificationInconclusive = verificationInconclusive,
                )
            }
        }
    }

    fun clearPendingStalkerPrompt() {
        synchronized(stateLock) {
            val current = _state.value
            if (current.pendingStalkerCommand != null) {
                _state.value = current.copy(
                    message = context.getString(R.string.setup_phone_pairing_same_wifi),
                    pendingStalkerCommand = null,
                    stalkerTransportChallenge = null,
                    stalkerVerificationInconclusive = null,
                )
            }
        }
    }

    private fun stalkerTransportChallengeMessage(
        challenge: StalkerTransportChallenge,
        strings: Context,
    ): String {
        val (title, body) = when (challenge.reason) {
            StalkerTransportChallengeReason.INVALID_TLS ->
                R.string.stalker_transport_invalid_tls_title to R.string.stalker_transport_invalid_tls_body

            StalkerTransportChallengeReason.CLEARTEXT_HTTP ->
                R.string.stalker_transport_http_title to R.string.stalker_transport_http_body

            StalkerTransportChallengeReason.ORIGIN_CHANGED ->
                R.string.stalker_transport_origin_changed_title to R.string.stalker_transport_origin_changed_body
        }
        return buildList {
            add(strings.getString(title))
            add(strings.getString(body, challenge.displayHost))
            if (
                challenge.reason == StalkerTransportChallengeReason.INVALID_TLS &&
                challenge.proposedSpkiSha256.isNullOrBlank()
            ) {
                add(strings.getString(R.string.setup_phone_pairing_stalker_certificate_error))
            }
            add(strings.getString(R.string.setup_phone_pairing_continue_on_tv))
        }.joinToString("\n\n")
    }

    private fun updateSessionState(
        session: PairingSession,
        status: ProviderQrPairingStatus,
        message: String,
    ) {
        synchronized(stateLock) {
            if (activeSession === session) {
                _state.value = _state.value.copy(status = status, message = message)
            }
        }
    }

    private fun isCurrentSession(session: PairingSession): Boolean =
        isActiveSession(session) && !session.serverSocket.isClosed

    private fun isActiveSession(session: PairingSession): Boolean =
        synchronized(stateLock) { activeSession === session }

    private fun isTokenValid(session: PairingSession, token: String?): Boolean =
        synchronized(stateLock) {
            activeSession === session && session.lease.isValid(token, System.currentTimeMillis())
        }

    /** Returns null for an invalid/stale token, false for an in-flight submit, or true if acquired. */
    private fun beginSubmission(session: PairingSession, token: String?, nowMs: Long): Boolean? =
        synchronized(stateLock) {
            if (activeSession !== session || !session.lease.isValid(token, nowMs)) {
                null
            } else {
                session.lease.tryBeginSubmission(token, nowMs)
            }
        }

    private fun setErrorState() {
        synchronized(stateLock) { _state.value = errorState() }
    }

    private fun errorState(): ProviderQrPairingState = ProviderQrPairingState(
        status = ProviderQrPairingStatus.ERROR,
        message = context.getString(R.string.setup_phone_pairing_error_help),
    )

    private fun generateToken(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun createQrBitmap(value: String): Bitmap {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java)
        hints[EncodeHintType.MARGIN] = 4
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, PAIRING_QR_SIZE, PAIRING_QR_SIZE, hints)
        val pixels = IntArray(PAIRING_QR_SIZE * PAIRING_QR_SIZE)
        for (y in 0 until PAIRING_QR_SIZE) {
            for (x in 0 until PAIRING_QR_SIZE) {
                pixels[y * PAIRING_QR_SIZE + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(PAIRING_QR_SIZE, PAIRING_QR_SIZE, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, PAIRING_QR_SIZE, 0, 0, PAIRING_QR_SIZE, PAIRING_QR_SIZE)
        }
    }

    private fun resolveLanIpv4Address(): String? {
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNetwork = connectivity?.activeNetwork
        val capabilities = activeNetwork?.let(connectivity::getNetworkCapabilities)
        val isLanTransport = capabilities?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        } == true
        if (isLanTransport) {
            val activeAddress = connectivity?.getLinkProperties(activeNetwork)
                ?.linkAddresses
                ?.asSequence()
                ?.mapNotNull { it.address as? Inet4Address }
                ?.firstOrNull(::isUsableLanAddress)
                ?.hostAddress
            if (!activeAddress.isNullOrBlank()) return activeAddress
        }
        return NetworkInterface.getNetworkInterfaces()
            ?.toList()
            .orEmpty()
            .asSequence()
            .filter { networkInterface ->
                networkInterface.name.startsWith("wlan", ignoreCase = true) ||
                    networkInterface.name.startsWith("eth", ignoreCase = true) ||
                    networkInterface.name.startsWith("en", ignoreCase = true) ||
                    networkInterface.name.startsWith("p2p", ignoreCase = true)
            }
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { networkInterface ->
                val priority = when {
                    networkInterface.name.startsWith("wlan", ignoreCase = true) -> 0
                    networkInterface.name.startsWith("eth", ignoreCase = true) -> 1
                    networkInterface.name.startsWith("en", ignoreCase = true) -> 2
                    else -> 3
                }
                networkInterface.inetAddresses.toList().asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter(::isUsableLanAddress)
                    .map { priority to it }
            }
            .sortedBy { it.first }
            .firstOrNull()
            ?.second
            ?.hostAddress
    }

    private fun isUsableLanAddress(address: Inet4Address): Boolean =
        !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isAnyLocalAddress

    private fun queryParams(pathAndQuery: String): Map<String, String>? =
        pathAndQuery.substringAfter('?', "").let(::parseForm)

    private fun parseForm(body: String): Map<String, String>? {
        if (body.isBlank()) return emptyMap()
        val values = LinkedHashMap<String, String>()
        body.split('&').forEach { part ->
            if (part.isBlank()) return@forEach
            val separator = part.indexOf('=')
            val key = decode(part.substring(0, separator.takeIf { it >= 0 } ?: part.length))
                ?: return null
            val value = decode(if (separator >= 0) part.substring(separator + 1) else "")
                ?: return null
            if (key.isBlank() || key.any(Char::isISOControl) || value.any(Char::isISOControl)) return null
            if (key in values) return null
            values[key] = value
        }
        return values
    }

    private fun decode(value: String): String? = try {
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun localizedContext(acceptLanguage: String?): Context {
        val languageTag = acceptLanguage
            ?.split(',')
            ?.firstOrNull()
            ?.substringBefore(';')
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: return context
        val locale = Locale.forLanguageTag(languageTag)
        if (locale.language.isBlank()) return context
        val configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.createConfigurationContext(configuration)
    }

    private fun writeHtml(output: OutputStream, status: Int, html: String) {
        writeResponse(output, status, "text/html; charset=utf-8", html)
    }

    private fun writeResponse(output: OutputStream, status: Int, contentType: String, body: String) {
        val statusText = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            408 -> "Request Timeout"
            409 -> "Conflict"
            411 -> "Length Required"
            413 -> "Content Too Large"
            415 -> "Unsupported Media Type"
            431 -> "Request Header Fields Too Large"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val header = (
            "HTTP/1.1 $status $statusText\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "X-Content-Type-Options: nosniff\r\n" +
                "Referrer-Policy: no-referrer\r\n" +
                "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'\r\n" +
                "Connection: close\r\n\r\n"
            ).toByteArray(StandardCharsets.UTF_8)
        runCatching {
            output.write(header)
            output.write(bytes)
            output.flush()
        }.onFailure {
            Log.w(TAG, "Pairing client disconnected before response was written")
        }
    }

    private fun formPage(token: String, strings: Context): String {
        fun text(resourceId: Int): String = strings.getString(resourceId).escapeHtml()
        val language = strings.resources.configuration.locales[0].toLanguageTag().escapeHtml()
        return """
            <!doctype html>
            <html lang="$language" dir="${pageDirection(strings)}">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>${text(R.string.setup_phone_pairing_title)}</title>
              <meta name="color-scheme" content="dark">
              <meta name="theme-color" content="#0b0e14">
              <style>${pageStyles()}</style>
            </head>
            <body>
            <main>
              ${pageBrand(strings)}
              <header>
                <span class="eyebrow"><span class="status-dot" aria-hidden="true"></span>${text(R.string.setup_phone_pairing_idle)}</span>
                <h1>${text(R.string.setup_provider_title)}</h1>
                <p>${text(R.string.setup_form_subtitle)}</p>
              </header>
              <div class="form-card">
              <form method="post" action="/submit" accept-charset="utf-8">
                <input type="hidden" name="token" value="${token.escapeHtml()}">
                <div class="source-section">
                <label for="type">${text(R.string.setup_source_type_label)}</label>
                <select name="type" id="type" onchange="updateType()">
                  <option value="xtream">${text(R.string.setup_xtream)}</option>
                  <option value="m3u">${text(R.string.setup_m3u)}</option>
                  <option value="stalker">${text(R.string.setup_stalker)}</option>
                  <option value="jellyfin">${text(R.string.setup_tab_jellyfin)}</option>
                </select>
                </div>
                <div class="details-section">
                <label for="name">${text(R.string.setup_name)}</label>
                <input id="name" name="name" autocomplete="off">
                <div id="serverFields" class="field-group active">
                  <label for="serverUrl">${text(R.string.setup_server_url)}</label>
                  <input id="serverUrl" name="serverUrl" inputmode="url" autocomplete="url" autocapitalize="none" spellcheck="false" dir="ltr">
                  <div class="credentials-row"><div>
                  <label for="username">${text(R.string.setup_username)}</label>
                  <input id="username" name="username" autocomplete="username" autocapitalize="none" spellcheck="false">
                  </div><div>
                  <label for="password">${text(R.string.setup_password)}</label>
                  <input id="password" name="password" type="password" autocomplete="current-password">
                  </div></div>
                </div>
                <div id="m3uFields" class="field-group">
                  <label for="m3uUrl">${text(R.string.setup_m3u_hint)}</label>
                  <input id="m3uUrl" name="m3uUrl" inputmode="url" autocomplete="url" autocapitalize="none" spellcheck="false" dir="ltr" disabled>
                </div>
                <div id="stalkerFields" class="field-group">
                  <label for="macAddress">${text(R.string.setup_phone_pairing_mac_label)}</label>
                  <input id="macAddress" name="macAddress" autocomplete="off" autocapitalize="none" spellcheck="false" dir="ltr" disabled>
                  <p>${text(R.string.setup_info_stalker_body)}</p>
                </div>
                </div>
                <div class="submit-section">
                  <button type="submit"><span>${text(R.string.setup_provider_title)}</span><span class="button-arrow" aria-hidden="true">&rarr;</span></button>
                </div>
              </form>
              </div>
              <aside class="warning"><span class="warning-icon" aria-hidden="true">!</span><p>${text(R.string.setup_phone_pairing_security_warning)}</p></aside>
              <footer>${text(R.string.setup_phone_pairing_same_wifi)}</footer>
            </main>
            <script>
              function setGroup(id, active){
                const group=document.getElementById(id);
                group.classList.toggle('active', active);
                group.querySelectorAll('input,select').forEach(input => input.disabled = !active);
              }
              function updateType(){
                const type=document.getElementById('type').value;
                setGroup('serverFields', type !== 'm3u');
                setGroup('m3uFields', type === 'm3u');
                setGroup('stalkerFields', type === 'stalker');
              }
              updateType();
            </script>
            </body>
            </html>
        """.trimIndent()
    }

    private fun successPage(strings: Context, warning: String?): String =
        resultPage(strings, success = true, details = warning)

    private fun errorPage(strings: Context, details: String? = null): String =
        resultPage(strings, success = false, details = details)

    private fun pageDirection(strings: Context): String =
        if (strings.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL) "rtl" else "ltr"

    private fun pageBrand(strings: Context): String = """
        <div class="brand">
          <span class="brand-mark" aria-hidden="true"><svg viewBox="0 0 32 32" fill="none"><path d="M8 9h16v11H8z" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/><path d="m14 12 6 3.5-6 3.5v-7Z" fill="currentColor"/><path d="M12 24h8" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg></span>
          <span>${strings.getString(R.string.setup_title_streamvault).escapeHtml()}</span>
        </div>
    """.trimIndent()

    private fun resultPage(strings: Context, success: Boolean, details: String? = null): String {
        val title = strings.getString(
            if (success) R.string.setup_phone_pairing_complete else R.string.setup_phone_pairing_error,
        ).escapeHtml()
        val hasDetails = !details.isNullOrBlank()
        val help = if (success || hasDetails) {
            ""
        } else {
            "<p>${strings.getString(R.string.setup_phone_pairing_error_help).escapeHtml()}</p>"
        }
        val detailsHtml = details
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let(::paragraphsHtml)
            .orEmpty()
        return """
            <!doctype html><html lang="${strings.resources.configuration.locales[0].toLanguageTag().escapeHtml()}" dir="${pageDirection(strings)}">
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="color-scheme" content="dark"><meta name="theme-color" content="#0b0e14">
            <title>$title</title><style>${pageStyles()}</style></head>
            <body><main>${pageBrand(strings)}
              <section class="form-card result" role="${if (success) "status" else "alert"}">
                <span class="result-icon ${if (success) "success" else "failure"}" aria-hidden="true">${if (success) "&#10003;" else "!"}</span>
                <h1>$title</h1>$detailsHtml$help
              </section>
            </main></body></html>
        """.trimIndent()
    }

    private fun paragraphsHtml(value: String): String = value
        .split(Regex("\\n\\s*\\n"))
        .filter(String::isNotBlank)
        .joinToString(separator = "") { paragraph ->
            "<p class=\"result-detail\">${paragraph.trim().escapeHtml().replace("\n", "<br>")}</p>"
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
        .source-section{padding:24px 28px;background:#ffffff02;border-bottom:1px solid #2b2e3f}
        .details-section{padding:8px 28px 6px}
        label{display:block;margin:19px 0 9px;font-size:12px;font-weight:600;color:#c4c8d5;letter-spacing:.2px}
        .source-section label{margin-top:0}
        input,select{display:block;width:100%;min-width:0;min-height:50px;padding:13px 14px;border-radius:10px;border:1px solid #333747;background:#0f121c;color:#f0f1f6;font:inherit;font-size:16px;transition:border-color .15s,box-shadow .15s}
        select{cursor:pointer;padding-inline-end:32px}
        input:hover,select:hover{border-color:#515368}
        input:focus-visible,select:focus-visible{outline:0;border-color:#ac96f3;box-shadow:0 0 0 3px #a68be51c}
        input:autofill{box-shadow:0 0 0 100px #191c28 inset}
        .credentials-row{display:grid;grid-template-columns:1fr 1fr;gap:16px}
        .credentials-row>div{min-width:0}
        .field-group{display:none}.field-group.active{display:block}
        .field-group p{font-size:12px;margin-top:10px}
        .submit-section{padding:24px 28px 28px}
        button{display:flex;align-items:center;justify-content:center;gap:14px;width:100%;min-height:52px;padding:14px 20px;border:1px solid #c4b5f6;border-radius:11px;background:linear-gradient(110deg,#c0acf6,#a98aeb);color:#1d1236;font:inherit;font-weight:750;font-size:15px;cursor:pointer;box-shadow:0 4px 20px #a98aeb16;transition:filter .15s,transform .15s}
        button:hover{filter:brightness(1.09)}button:active{transform:translateY(1px)}
        button:focus-visible{outline:2px solid #e0d6ff;outline-offset:4px}
        .button-arrow{font-size:21px;line-height:1}[dir=rtl] .button-arrow{transform:scaleX(-1)}
        .warning{display:flex;align-items:flex-start;gap:11px;border:1px solid #d4a76526;border-radius:12px;background:#d4a76507;padding:14px 16px;margin-top:20px}
        .warning p{font-size:12px;line-height:1.6;color:#b9a789}
        .warning-icon{display:grid;place-items:center;flex-shrink:0;width:17px;height:17px;border:1px solid #b9a789;border-radius:50%;font-size:11px;font-weight:700;color:#b9a789;margin-top:2px}
        footer{padding:20px 20px 0;color:#828a9e;font-size:11px;line-height:1.7;text-align:center}
        .result{text-align:center;padding:48px 28px}.result p{max-width:420px;margin:0 auto 14px}.result .result-detail{color:#e8eaf2;white-space:pre-line;overflow-wrap:anywhere}.result .result-detail:last-of-type{margin-bottom:20px}
        .result-icon{display:grid;place-items:center;width:64px;height:64px;margin:0 auto 24px;border-radius:50%;font-size:30px}
        .success{color:#9cd6bc;background:#9cd6bc12;border:1px solid #9cd6bc40}
        .failure{color:#e6ad9b;background:#e6ad9b12;border:1px solid #e6ad9b40}
        @media(max-width:480px){body{padding:28px 20px}.brand{margin-bottom:34px}.source-section{padding:20px}.details-section{padding:4px 20px}.submit-section{padding:22px 20px}.credentials-row{grid-template-columns:1fr;gap:0}.form-card{border-radius:18px}}
        @media(prefers-reduced-motion:reduce){*{transition:none!important}}
    """.trimIndent()

    private fun String.escapeHtml(): String =
        replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")

    private class PairingSession(
        token: String,
        val expiresAtMs: Long,
        val serverSocket: ServerSocket,
    ) {
        val lease = ProviderPairingSessionLease(token, expiresAtMs)
        val token: String = token
        val clientJobs = ConcurrentHashMap.newKeySet<Job>()
        val clientSockets = ConcurrentHashMap.newKeySet<Socket>()
        @Volatile var acceptJob: Job? = null
        @Volatile var timeoutJob: Job? = null
    }

    private sealed interface ProviderPairingSubmitResult {
        data class Added(
            val providerId: Long,
            val providerName: String,
            val warning: String? = null,
            val offerCombinedAttach: Boolean = false,
        ) : ProviderPairingSubmitResult

        data class StalkerTransportConsentRequired(
            val challenge: StalkerTransportChallenge,
            val command: StalkerProviderSetupCommand,
            val details: String,
        ) : ProviderPairingSubmitResult

        data class StalkerVerificationInconclusive(
            val command: StalkerProviderSetupCommand,
            val verificationMessage: String,
            val details: String,
        ) : ProviderPairingSubmitResult

        data class Failed(val message: String) : ProviderPairingSubmitResult
    }
}

data class ProviderQrPairingState(
    val status: ProviderQrPairingStatus = ProviderQrPairingStatus.IDLE,
    val url: String? = null,
    val qrBitmap: Bitmap? = null,
    val expiresAtMs: Long = 0L,
    val message: String? = null,
    val createdProviderId: Long? = null,
    val createdProviderName: String? = null,
    val completionWarning: String? = null,
    val offerCombinedAttach: Boolean = false,
    val pendingStalkerCommand: StalkerProviderSetupCommand? = null,
    val stalkerTransportChallenge: StalkerTransportChallenge? = null,
    val stalkerVerificationInconclusive: String? = null,
)

enum class ProviderQrPairingStatus {
    IDLE,
    READY,
    RECEIVING,
    COMPLETE,
    ERROR,
}
