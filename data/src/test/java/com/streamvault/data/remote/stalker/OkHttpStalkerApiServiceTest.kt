package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerCompatibilityProfileIds
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class OkHttpStalkerApiServiceTest {

    @Test
    fun authenticate_rejects_profile_msg_that_reports_invalid_mac() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to """{"js":{"token":"token-123"}}""",
                "get_profile" to """{"js":{"status":1,"msg":"Not valid MAC"}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.Authorization::class.java)
        assertThat(error.message).contains("Not valid MAC")
    }

    @Test
    fun getVodCategories_classifies_plain_text_unauthorized_as_authorization_failure() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient("get_categories" to "Unauthorized request."),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getVodCategories(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).exception)
            .isInstanceOf(StalkerApiError.Authorization::class.java)
    }

    @Test
    fun cancellableTransport_cancelsUnderlyingOkHttpCall() = runTest {
        val call = PendingCall()
        val job = launch {
            withCancellableStalkerCall(call) { error("A pending call must not produce a response") }
        }

        runCurrent()
        assertThat(call.enqueued).isTrue()

        job.cancelAndJoin()

        assertThat(call.cancelled).isTrue()
    }

    @Test
    fun cancellableTransport_keepsCallCancellationActiveWhileResponseIsBeingConsumed() = runTest {
        val call = PendingCall()
        var responseConsumptionStarted = false
        val job = launch {
            withCancellableStalkerCall(call) {
                responseConsumptionStarted = true
                awaitCancellation()
            }
        }

        runCurrent()
        call.respond()
        runCurrent()
        assertThat(responseConsumptionStarted).isTrue()

        job.cancelAndJoin()

        assertThat(call.cancelled).isTrue()
    }

    @Test
    fun cookieJar_handlesConcurrentUpdatesWithoutLosingProviderSessionCookies() = runTest {
        val jar = InMemoryStalkerCookieJar()
        val url = "https://portal.example.com/server/load.php".toHttpUrl()

        coroutineScope {
            (0 until 100).map { index ->
                async(Dispatchers.Default) {
                    jar.saveFromResponse(
                        url,
                        listOf(
                            Cookie.Builder()
                                .name("session_$index")
                                .value(index.toString())
                                .hostOnlyDomain(url.host)
                                .path("/")
                                .build()
                        )
                    )
                }
            }.awaitAll()
        }

        assertThat(jar.loadForRequest(url).map { cookie -> cookie.name }.toSet()).hasSize(100)
    }

    @Test
    fun offsetlessExpiration_usesDeviceProfileTimezoneIncludingNonHourOffsets() {
        val zone = ZoneId.of("Asia/Kathmandu")

        val parsed = parseExpirationDate("2026-07-13 12:30:00", zone)

        assertThat(parsed).isEqualTo(LocalDateTime.of(2026, 7, 13, 12, 30).atZone(zone).toInstant().toEpochMilli())
    }

    @Test
    fun explicitOffsetExpiration_isNotReinterpretedInPortalTimezone() {
        val parsed = parseExpirationDate("2026-07-13T12:30:00+03:30", ZoneId.of("America/New_York"))

        assertThat(parsed).isEqualTo(java.time.OffsetDateTime.parse("2026-07-13T12:30:00+03:30").toInstant().toEpochMilli())
    }

    @Test
    fun authenticate_retries_with_legacy_recipe_and_updates_profile_metadata() = runTest {
        val requestedVersions = mutableListOf<String>()
        val requestedImages = mutableListOf<String>()
        var handshakeCount = 0
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    if (action == "get_profile") {
                        requestedVersions += request.url.queryParameter("ver").orEmpty()
                        requestedImages += request.url.queryParameter("image_version").orEmpty()
                    }
                    val body = when (action) {
                        "handshake" -> {
                            handshakeCount++
                            """{"js":{"token":"token-$handshakeCount"}}"""
                        }
                        "get_profile" -> if (request.url.queryParameter("image_version") != "216") {
                            ""
                        } else {
                            """{"js":{"id":"42","name":"Legacy Box","status":"1","auth_access":true}}"""
                        }
                        "get_main_info" -> """{"js":{"id":"42","name":"Legacy Box","status":"1","auth_access":true}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                authMode = StalkerAuthMode.AUTO,
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.first.magPreset).isEqualTo(StalkerMagPreset.MAG250_LEGACY)
        assertThat(success.data.first.bootstrapRecipe).isEqualTo(StalkerBootstrapRecipe.LEGACY_MAG)
        assertThat(success.data.second.magPreset).isEqualTo(StalkerMagPreset.MAG250_LEGACY)
        assertThat(success.data.second.bootstrapRecipe).isEqualTo(StalkerBootstrapRecipe.LEGACY_MAG)
        assertThat(success.data.first.recipeEvidence).containsAtLeast("fallback_recipe", "rediscovery_attempted")
        assertThat(requestedImages).containsAtLeast("218", "216").inOrder()
        assertThat(requestedVersions.last()).contains("0.2.16-r17-250")
        assertThat(handshakeCount).isEqualTo(2)
    }

    @Test
    fun authenticate_auto_triesMacOnlyBeforeCredentialBackedAuth() = runTest {
        val requestedActions = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    requestedActions += action
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> """
                            {"js":{"id":"42","name":"MAC Account","status":"1","auth_access":true}}
                        """.trimIndent()
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                authMode = StalkerAuthMode.AUTO,
                username = "optional-user",
                password = "optional-password",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val session = (result as Result.Success).data.first
        assertThat(session.effectiveAuthMode).isEqualTo(StalkerAuthMode.MAC_ONLY)
        assertThat(requestedActions).containsExactly("handshake", "get_profile").inOrder()
    }

    @Test
    fun authenticate_cached_profile_stops_after_fresh_session_catalog_denial() = runTest {
        var handshakeCount = 0
        val requestedImages = mutableListOf<String>()
        val progress = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    if (action == "get_profile") {
                        requestedImages += request.url.queryParameter("image_version").orEmpty()
                    }
                    val body = when (action) {
                        "handshake" -> {
                            handshakeCount++
                            """{"js":{"token":"token-$handshakeCount","random":"random-$handshakeCount"}}"""
                        }
                        "get_profile" -> """{"js":{"id":"42","name":"Accepted Box","status":"1","stb_type":"MAG254"}}"""
                        "get_main_info", "get_modules" -> """{"js":{}}"""
                        "get_genres", "get_categories" -> """{"js":{"not_valid_token":true}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en",
                requestedProfileId = StalkerCompatibilityProfileIds.CLASSIC_MAG250_GENERIC,
                requireCatalogValidation = true,
                allowCompatibilityDiscovery = false,
                onProgress = progress::add
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.PartialAuthorization::class.java)
        assertThat(error.message).contains("accepted the device profile")
        assertThat(handshakeCount).isEqualTo(2)
        assertThat(requestedImages).containsExactly("218", "218")
        assertThat(progress).containsAtLeast(
            "Trying MAG250 (Generic)",
            "Handshake accepted",
            "Profile accepted; checking catalog",
            "Validating Live TV categories",
            "Catalog authorization rejected; retrying fresh session"
        )
    }

    @Test
    fun authenticate_auto_continues_after_profile_scoped_catalog_denial_and_finds_mag254() = runTest {
        var handshakeCount = 0
        val requestedModels = mutableListOf<String>()
        val progress = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    val isMag254 = request.header("User-Agent").orEmpty().contains("MAG254")
                    if (action == "get_profile") {
                        requestedModels += request.url.queryParameter("stb_type").orEmpty()
                    }
                    val body = when (action) {
                        "handshake" -> {
                            handshakeCount++
                            """{"js":{"token":"token-$handshakeCount","random":"random-$handshakeCount"}}"""
                        }
                        "get_profile" -> """{"js":{"id":"42","name":"Accepted Box","status":"1","stb_type":"MAG254"}}"""
                        "get_main_info", "get_localization", "get_events" -> """{"js":{}}"""
                        "get_genres" -> if (isMag254) {
                            """{"js":[{"id":"1","title":"News"}]}"""
                        } else {
                            "Not valid MAC"
                        }
                        "get_ordered_list" -> """{"js":{"data":[{"id":"7","name":"News 1","cmd":"ffmpeg http://localhost/ch/7_"}],"total_items":1,"max_page_items":14}}"""
                        "create_link" -> """{"js":{"cmd":"ffmpeg https://cdn.example.com/live/7.m3u8"}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en",
                requireCatalogValidation = true,
                onProgress = progress::add
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.first.compatibilityProfileId)
            .isEqualTo(StalkerCompatibilityProfileIds.CLASSIC_MAG254_STRICT)
        assertThat(success.data.first.bootstrapEvidence).containsAtLeast(
            "catalog:itv:categories_valid",
            "catalog:itv:page_valid",
            "playback:live:create_link"
        )
        assertThat(requestedModels).containsAtLeast("MAG250", "MAG254").inOrder()
        assertThat(handshakeCount).isEqualTo(2)
        assertThat(progress).contains("Portal reports MAG254 (Strict); validating that profile")
        assertThat(progress).contains("Validating Live TV playback link")
    }

    @Test
    fun authenticate_cachedOnly_uses_exact_learned_profile_once() = runTest {
        var handshakeCount = 0
        val requestedImages = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    val body = when (action) {
                        "handshake" -> {
                            handshakeCount++
                            """{"js":{"token":"token-$handshakeCount"}}"""
                        }
                        "get_profile" -> {
                            requestedImages += request.url.queryParameter("image_version").orEmpty()
                            """{"js":{"status":1,"msg":"Not valid MAC"}}"""
                        }
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val profile = buildStalkerDeviceProfile(
            portalUrl = "https://portal.example.com/c",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en",
            learnedProfileId = StalkerCompatibilityProfileIds.CLASSIC_MAG322_MODERN,
            allowCompatibilityDiscovery = false
        ).copy(providerId = 42L)

        val result = service.authenticate(profile)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(handshakeCount).isEqualTo(1)
        assertThat(requestedImages).containsExactly("221")
    }

    @Test
    fun authenticate_reports_higherRanked_primary_failure_instead_of_last_fallback() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token"}}"""
                        "get_profile" -> if (request.url.queryParameter("image_version") == "218") {
                            """{"js":{"status":1,"msg":"Not valid MAC"}}"""
                        } else {
                            ""
                        }
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(stalkerProfile())

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("Not valid MAC")
        assertThat(result.exception).isInstanceOf(StalkerApiError.InvalidMac::class.java)
    }

    @Test
    fun authenticate_accountBlocked_is_terminal_for_discovery() = runTest {
        var profileRequests = 0
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token"}}"""
                        "get_profile" -> {
                            profileRequests++
                            """{"js":{"error":"Account blocked"}}"""
                        }
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(stalkerProfile())

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).exception).isInstanceOf(StalkerApiError.AccountBlocked::class.java)
        assertThat(profileRequests).isEqualTo(1)
    }

    @Test
    fun authenticate_reads_token_and_profile_from_js_wrapper() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to """{"js":{"token":"token-123"}}""",
                "get_profile" to """{"js":{"name":"Living Room","status":"1","max_online":"2"}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.first.token).isEqualTo("token-123")
        assertThat(success.data.second.accountName).isEqualTo("Living Room")
        assertThat(success.data.second.maxConnections).isEqualTo(2)
    }

    @Test
    fun authenticate_rejects_token_when_getProfile_returns_authorization_failed() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to """{"js":{"token":"token-123"}}""",
                "get_profile" to """{"js":{"error":"Authorization failed"}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("Authorization failed")
    }

    @Test
    fun authenticate_switchesEndpointsWhenHandshakeSucceedsButProfileFails() = runTest {
        val requestedPaths = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedPaths += request.url.encodedPath
                    val action = request.url.queryParameter("action").orEmpty()
                    val body = when (request.url.encodedPath) {
                        "/server/load.php" -> when (action) {
                            "handshake" -> """{"js":{"token":"token-123"}}"""
                            "get_profile" -> ""
                            else -> """{"js":{}}"""
                        }
                        "/portal.php" -> when (action) {
                            "handshake" -> """{"js":{"token":"portal-token"}}"""
                            "get_profile" -> """{"js":{"name":"Portal Endpoint","status":"1"}}"""
                            else -> """{"js":{}}"""
                        }
                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedPaths).contains("/portal.php")
        assertThat(requestedPaths).contains("/server/load.php")
    }

    @Test
    fun authenticate_uses_effective_loadScript_after_http_redirect() = runTest {
        val requestedPaths = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    requestedPaths += request.url.encodedPath
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> """{"js":{"name":"Redirected Portal","status":"1","auth_access":true}}"""
                        else -> """{"js":{}}"""
                    }
                    val effectiveRequest = if (action == "handshake") {
                        request.newBuilder()
                            .url("https://portal.example.com/stalker_portal/server/load.php?type=stb&action=handshake")
                            .build()
                    } else {
                        request
                    }
                    Response.Builder()
                        .request(effectiveRequest)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val session = (result as Result.Success).data.first
        assertThat(session.loadUrl)
            .isEqualTo("https://portal.example.com/stalker_portal/server/load.php")
        // First request is the bare-base hint probe (GET /), then the original candidate,
        // then every call after the handshake redirect was adopted.
        assertThat(requestedPaths.first()).isEqualTo("/")
        assertThat(requestedPaths[1]).isEqualTo("/server/load.php")
        assertThat(requestedPaths.drop(2).distinct())
            .containsExactly("/stalker_portal/server/load.php")
    }

    @Test
    fun authenticate_usesOnlyLearnedServerLoadEndpoint() = runTest {
        val requestedPaths = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedPaths += request.url.encodedPath
                    val action = request.url.queryParameter("action").orEmpty()
                    if (request.url.encodedPath == "/portal.php") {
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("""{"js":{}}""".toResponseBody("application/json".toMediaType()))
                            .build()
                    } else {
                        val body = when (action) {
                            "handshake" -> """{"js":{"token":"token-123"}}"""
                            "get_profile" -> """{"js":{"name":"Server Load","status":"1","auth_access":true}}"""
                            else -> """{"js":{}}"""
                        }
                        Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(body.toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                authMode = StalkerAuthMode.AUTO,
                endpointPreferenceHint = StalkerEndpointPreference.SERVER_LOAD,
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedPaths).contains("/server/load.php")
        assertThat(requestedPaths).doesNotContain("/portal.php")
    }

    @Test
    fun authenticate_scopes_endpoint_handshake_evidence_to_each_identity_profile() = runTest {
        val requestedPaths = mutableListOf<String>()
        val requestedActions = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedPaths += request.url.encodedPath
                    requestedActions += "${request.url.encodedPath}:${request.url.queryParameter("action").orEmpty()}"
                    val action = request.url.queryParameter("action").orEmpty()
                    when (request.url.encodedPath) {
                        "/server/load.php" -> Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(404)
                            .message("Not Found")
                            .body("""{"js":{}}""".toResponseBody("application/json".toMediaType()))
                            .build()

                        "/portal.php" -> {
                            val body = when (action) {
                                "handshake" -> """{"js":{"token":"portal-token"}}"""
                                "get_profile" -> """{"js":{"name":"Portal Endpoint","status":"1","auth_access":true}}"""
                                else -> """{"js":{}}"""
                            }
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(body.toResponseBody("application/json".toMediaType()))
                                .build()
                        }

                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                authMode = StalkerAuthMode.AUTO,
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedPaths.count { it == "/server/load.php" }).isEqualTo(4)
        assertThat(requestedActions.first()).isEqualTo("/server/load.php:handshake")
        assertThat(requestedActions).containsAtLeast(
            "/portal.php:handshake",
            "/portal.php:get_profile"
        )
        assertThat(requestedPaths.distinct()).containsExactly(
            "/server/load.php",
            "/portal.php"
        )
    }

    @Test
    fun createLink_usesSessionEndpointForTempLinkStrictLivePlayback() = runTest {
        val requestedPaths = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedPaths += request.url.encodedPath
                    val body = when (request.url.queryParameter("action")) {
                        "create_link" -> """{"js":{"cmd":"ffmpeg http://cdn.example.com/live/1.ts"}}"""
                        else -> error("Unexpected action '${request.url.queryParameter("action")}'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )
        val session = StalkerSession(
            loadUrl = "https://portal.example.com/server/load.php",
            portalReferer = "https://portal.example.com/c/",
            token = "token-123",
            fingerprintEvidence = StalkerFingerprintEvidence(
                endpointPreference = StalkerEndpointPreference.SERVER_LOAD,
                playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT
            )
        )

        val result = service.createLink(
            session = session,
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                endpointPreferenceHint = StalkerEndpointPreference.SERVER_LOAD,
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            kind = StalkerStreamKind.LIVE,
            cmd = "ffmpeg http://cdn.example.com/live/1.ts",
            seriesNumber = null,
            archiveStartSeconds = null,
            archiveEndSeconds = null
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedPaths).containsExactly("/server/load.php")
    }

    @Test
    fun authenticate_applies_getProfile_param_overrides_and_keeps_jshttprequest_last() = runTest {
        var profileRawQuery = ""
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    if (action == "get_profile") {
                        profileRawQuery = request.url.encodedQuery.orEmpty()
                    }
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> """{"js":{"name":"Living Room","status":"1"}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en",
                stalkerAdvancedOptionsJson = StalkerAdvancedOptionsCodec.encode(
                    StalkerAdvancedOptions(
                        requestRules = listOf(
                            StalkerRequestRule(
                                action = "get_profile",
                                paramOverrides = listOf(
                                    StalkerParamOverride("hd", "0"),
                                    StalkerParamOverride("signature", ""),
                                    StalkerParamOverride("custom_param", "custom-value")
                                )
                            )
                        )
                    )
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(profileRawQuery).contains("hd=0")
        assertThat(profileRawQuery).doesNotContain("&signature=")
        assertThat(profileRawQuery).contains("custom_param=custom-value")
        assertThat(profileRawQuery.substringAfterLast("&")).isEqualTo("JsHttpRequest=1-xml")
    }

    @Test
    fun authenticate_blockedCriticalRequestFailsLogin() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to """{"js":{"token":"token-123"}}""",
                "get_profile" to """{"js":{"name":"Living Room","status":"1"}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en",
                stalkerAdvancedOptionsJson = StalkerAdvancedOptionsCodec.encode(
                    StalkerAdvancedOptions(
                        requestRules = listOf(
                            StalkerRequestRule(action = "get_profile", blockRequest = true)
                        )
                    )
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).message).contains("blocked")
    }

    @Test
    fun authenticate_applies_stalker_custom_header_overrides_and_removals() = runTest {
        val seenUserAgents = mutableListOf<String?>()
        val seenReferers = mutableListOf<String?>()
        val seenCustomHeaders = mutableListOf<String?>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    seenUserAgents += request.header("User-Agent")
                    seenReferers += request.header("Referer")
                    seenCustomHeaders += request.header("X-Test")
                    val action = request.url.queryParameter("action").orEmpty()
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> """{"js":{"name":"Living Room","status":"1"}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en",
                httpUserAgentOverride = "Dedicated Agent/1.0",
                httpHeadersOverride = "User-Agent: Header Agent/2.0 | Referer: | X-Test: enabled"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(seenUserAgents).contains("Header Agent/2.0")
        assertThat(seenReferers).doesNotContain("https://portal.example.com/c/")
        assertThat(seenCustomHeaders).contains("enabled")
    }

    @Test
    fun authenticate_dedicatedApiUserAgentOverridesCustomHeaderUserAgent() = runTest {
        val seenUserAgents = mutableListOf<String?>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    seenUserAgents += request.header("User-Agent")
                    val action = request.url.queryParameter("action").orEmpty()
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> """{"js":{"name":"Living Room","status":"1"}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en",
                httpHeadersOverride = "User-Agent: Header Agent/2.0",
                stalkerAdvancedOptionsJson = StalkerAdvancedOptionsCodec.encode(
                    StalkerAdvancedOptions(apiUserAgent = "API Agent/9.0")
                )
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(seenUserAgents).contains("API Agent/9.0")
        assertThat(seenUserAgents).doesNotContain("Header Agent/2.0")
    }

    @Test
    fun authenticate_module_gated_recipe_rebuilds_profile_for_modern_mag_preset() = runTest {
        val requestedStbTypes = mutableListOf<String>()
        val requestedAgents = mutableListOf<String>()
        val requestedPaths = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedPaths += request.url.encodedPath
                    val action = request.url.queryParameter("action").orEmpty()
                    if (action == "get_profile") {
                        requestedStbTypes += request.url.queryParameter("stb_type").orEmpty()
                        requestedAgents += request.header("X-User-Agent").orEmpty()
                    }
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> """{"js":{"id":"55","name":"Module Portal","status":"1","auth_access":true}}"""
                        "get_main_info" -> """{"js":{"id":"55","name":"Module Portal","status":"1","auth_access":true}}"""
                        "get_localization" -> """{"js":{"lang":"en","timezone":"UTC"}}"""
                        "get_modules" -> """{"js":{"modules":{"itv":1,"vod":1}}}"""
                        "get_events" -> """{"js":[] }"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                authMode = StalkerAuthMode.AUTO,
                magPresetHint = StalkerMagPreset.GENERIC_SAFE,
                portalFingerprintHint = com.streamvault.domain.model.StalkerPortalFingerprint.MODULE_GATED,
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedPaths.first()).isEqualTo("/portal.php")
        assertThat(requestedStbTypes).contains("MAG322")
        assertThat(requestedAgents.last()).contains("MAG322")
    }

    @Test
    fun createLink_reads_cmd_from_js_wrapper() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "create_link" to """{"js":{"cmd":"ffmpeg http://cdn.example.com/live/stream.ts"}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            kind = StalkerStreamKind.LIVE,
            cmd = "ffmpeg http://placeholder"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data).isEqualTo("http://cdn.example.com/live/stream.ts")
    }

    @Test
    fun requestJson_retries_empty_body_with_mac_query_and_remembers_portal_requirement() = runTest {
        val requestedMacQueries = mutableListOf<String?>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedMacQueries += request.url.queryParameter("mac")
                    val body = if (request.url.queryParameter("mac").isNullOrBlank()) {
                        ""
                    } else {
                        """{"js":{"cmd":"ffmpeg http://cdn.example.com/live/stream.ts"}}"""
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )
        val session = stalkerSession()
        val profile = stalkerProfile()

        val first = service.createLink(
            session = session,
            profile = profile,
            kind = StalkerStreamKind.LIVE,
            cmd = "ffmpeg http://placeholder"
        )
        val second = service.createLink(
            session = session,
            profile = profile,
            kind = StalkerStreamKind.LIVE,
            cmd = "ffmpeg http://placeholder-2"
        )

        assertThat(first).isInstanceOf(Result.Success::class.java)
        assertThat(second).isInstanceOf(Result.Success::class.java)
        assertThat(requestedMacQueries).containsExactly(null, "00:1A:79:12:34:56", "00:1A:79:12:34:56")
            .inOrder()
    }

    @Test
    fun createLink_uses_mag_live_storage_selector_without_changing_vod() = runTest {
        val requested = mutableListOf<Pair<String, String>>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requested += request.url.queryParameter("type").orEmpty() to
                        request.url.queryParameter("forced_storage").orEmpty()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"cmd":"ffmpeg http://cdn.example.com/media.ts"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )
        val session = StalkerSession(
            loadUrl = "https://portal.example.com/server/load.php",
            portalReferer = "https://portal.example.com/c/",
            token = "token-123"
        )
        val profile = buildStalkerDeviceProfile(
            portalUrl = "https://portal.example.com/c",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        service.createLink(
            session = session,
            profile = profile,
            kind = StalkerStreamKind.LIVE,
            cmd = "ffmpeg http://localhost/ch/301_"
        )
        service.createLink(
            session = session,
            profile = profile,
            kind = StalkerStreamKind.MOVIE,
            cmd = "ffmpeg http://localhost/movie/401"
        )

        assertThat(requested).containsExactly("itv" to "undefined", "vod" to "0").inOrder()
    }

    @Test
    fun createLink_keeps_session_endpoint_for_strict_live_temp_links() = runTest {
        val requestedPaths = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedPaths += request.url.encodedPath
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"cmd":"ffmpeg http://portal.example.com/play/live.php?stream=301&play_token=abc"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123",
                fingerprintEvidence = StalkerFingerprintEvidence(
                    playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT
                )
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG322",
                timezone = "UTC",
                locale = "en"
            ),
            kind = StalkerStreamKind.LIVE,
            cmd = "ffmpeg http://localhost/ch/301_"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedPaths).containsExactly("/server/load.php")
    }

    @Test
    fun createLink_keeps_server_endpoint_for_strict_vod_links() = runTest {
        val requestedPaths = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedPaths += request.url.encodedPath
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"cmd":"ffmpeg http://portal.example.com/play/movie.php?stream=401.mkv&play_token=abc"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123",
                fingerprintEvidence = StalkerFingerprintEvidence(
                    playbackBackendHint = StalkerPlaybackBackendHint.TEMP_LINK_STRICT
                )
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG322",
                timezone = "UTC",
                locale = "en"
            ),
            kind = StalkerStreamKind.MOVIE,
            cmd = "ffmpeg http://localhost/movie/401"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedPaths).containsExactly("/server/load.php")
    }

    @Test
    fun createLink_uses_episode_number_as_series_selector_for_stalker_shell_episode() = runTest {
        var requestedSeries: String? = null
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedSeries = request.url.queryParameter("series")
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"cmd":"ffmpeg http://cdn.example.com/series/episode11.mkv"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            kind = StalkerStreamKind.EPISODE,
            cmd = "eyJzZXJpZXNfaWQiOjUzOTk5LCJzZWFzb25fbnVtIjoxLCJ0eXBlIjoic2VyaWVzIn0=",
            seriesNumber = 11
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedSeries).isEqualTo("11")
        val success = result as Result.Success
        assertThat(success.data).isEqualTo("http://cdn.example.com/series/episode11.mkv")
    }

    @Test
    fun createLink_appends_archive_window_for_archive_streams() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"cmd":"ffmpeg http://portal.example.com/play/live.php?stream=301&play_token=abc"}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            kind = StalkerStreamKind.ARCHIVE,
            cmd = "ffmpeg http://localhost/ch/301_",
            archiveStartSeconds = 1000L,
            archiveEndSeconds = 1300L
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data).contains("utc=1000")
        assertThat(success.data).contains("lutc=1300")
    }

    @Test
    fun buildStalkerDeviceProfile_sanitizes_impossible_auth_mode_hints() {
        val credentialsOnly = buildStalkerDeviceProfile(
            portalUrl = "https://portal.example.com/c",
            macAddress = "",
            authMode = com.streamvault.domain.model.StalkerAuthMode.MAC_PLUS_CREDENTIALS,
            username = "alice",
            password = "secret",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        val macOnly = buildStalkerDeviceProfile(
            portalUrl = "https://portal.example.com/c",
            macAddress = "00:1A:79:12:34:56",
            authMode = com.streamvault.domain.model.StalkerAuthMode.CREDENTIALS_ONLY,
            username = "",
            password = "",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
        val strictProfile = buildStalkerDeviceProfile(
            portalUrl = "https://portal.example.com/c",
            macAddress = "00:1A:79:12:34:56",
            authMode = com.streamvault.domain.model.StalkerAuthMode.MAC_ONLY,
            magPresetHint = com.streamvault.domain.model.StalkerMagPreset.MAG254_STRICT,
            username = "",
            password = "",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        assertThat(credentialsOnly.authMode).isEqualTo(com.streamvault.domain.model.StalkerAuthMode.CREDENTIALS_ONLY)
        assertThat(macOnly.authMode).isEqualTo(com.streamvault.domain.model.StalkerAuthMode.MAC_ONLY)
        assertThat(strictProfile.deviceProfile).isEqualTo("MAG254")
        assertThat(strictProfile.userAgent).contains("MAG254")
    }

    @Test
    fun buildStalkerDeviceProfile_leaves_optional_identity_fields_empty_when_not_provided() {
        val profile = buildStalkerDeviceProfile(
            portalUrl = "https://portal.example.com/c",
            macAddress = "00:1A:79:12:34:56",
            username = "alice",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )

        assertThat(profile.serialNumber).isEmpty()
        assertThat(profile.deviceId).isEmpty()
        assertThat(profile.deviceId2).isEmpty()
        assertThat(profile.signature).isEmpty()
    }

    @Test
    fun authenticate_uses_handshake_random_and_truthful_identity_metrics() = runTest {
        val requestedMetrics = mutableListOf<String>()
        val requestedAuthSecondSteps = mutableListOf<String>()
        val requestedHardwareHashes = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    if (action == "get_profile") {
                        requestedMetrics += request.url.queryParameter("metrics").orEmpty()
                        requestedAuthSecondSteps += request.url.queryParameter("auth_second_step").orEmpty()
                        requestedHardwareHashes += request.url.queryParameter("hw_version_2").orEmpty()
                    }
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123","random":"challenge-456"}}"""
                        "get_profile" -> """{"js":{"name":"Living Room","status":"1"}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en",
                serialNumberOverride = "SERIAL123",
                deviceIdOverride = "DEVICEID1234567890",
                deviceId2Override = "DEVICEID2ABCDEFGH",
                signatureOverride = "SIGNATURE12345678"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val metrics = requestedMetrics.single()
        assertThat(metrics).contains("\"uid\":\"DEVICEID2ABCDEFGH\"")
        assertThat(metrics).contains("\"random\":\"challenge-456\"")
        assertThat(metrics).doesNotContain("signature")
        assertThat(metrics).doesNotContain("video_out")
        assertThat(requestedAuthSecondSteps).containsExactly("0")
        assertThat(requestedHardwareHashes).containsExactly("")
    }

    @Test
    fun authenticate_sends_literal_false_for_prehash() = runTest {
        val requestedPrehash = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    if (action == "get_profile") {
                        requestedPrehash += request.url.queryParameter("prehash").orEmpty()
                    }
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> """{"js":{"name":"Living Room","status":"1"}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                magPresetHint = StalkerMagPreset.MINISTRA_MODERN,
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat(requestedPrehash).isNotEmpty()
        assertThat(requestedPrehash).contains("false")
        assertThat(requestedPrehash).doesNotContain("1")
        assertThat(requestedPrehash).doesNotContain("0")
    }

    @Test
    fun authenticate_reads_json_from_callback_wrapper_and_control_char_noise() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to "\u0000callback({\"js\":{\"token\":\"token-123\"}});",
                "get_profile" to "\u0000callback({\"js\":{\"name\":\"Living Room\",\"status\":\"1\"}});"
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.first.token).isEqualTo("token-123")
        assertThat(success.data.second.accountName).isEqualTo("Living Room")
    }

    @Test
    fun authenticate_retains_server_cookies_for_follow_up_playback_requests() = runTest {
        val observedCookies = mutableListOf<String>()
        val profileCookies = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    if (action == "create_link") {
                        observedCookies += request.header("Cookie").orEmpty()
                    }
                    if (action == "get_profile") {
                        profileCookies += request.header("Cookie").orEmpty()
                    }
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> """{"js":{"name":"Living Room","status":"1"}}"""
                        "create_link" -> """{"js":{"cmd":"ffmpeg http://cdn.example.com/live/stream.ts"}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    val responseBuilder = Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                    if (action == "handshake") {
                        responseBuilder.addHeader("Set-Cookie", "PHPSESSID=session-42; Path=/; HttpOnly")
                    }
                    responseBuilder.build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val authResult = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "Europe/Amsterdam",
                locale = "en us",
                serialNumberOverride = "serial-123",
                deviceIdOverride = "device-123",
                deviceId2Override = "device-456",
                signatureOverride = "signature-789"
            )
        ) as Result.Success

        val createLinkResult = service.createLink(
            session = authResult.data.first,
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "Europe/Amsterdam",
                locale = "en us",
                serialNumberOverride = "serial-123",
                deviceIdOverride = "device-123",
                deviceId2Override = "device-456",
                signatureOverride = "signature-789"
            ),
            kind = StalkerStreamKind.LIVE,
            cmd = "ffmpeg http://localhost/ch/1234_"
        )

        assertThat(createLinkResult).isInstanceOf(Result.Success::class.java)
        assertThat(authResult.data.first.serverCookieHeader).contains("PHPSESSID=session-42")
        assertThat(profileCookies.single()).contains("PHPSESSID=session-42")
        assertThat(profileCookies.single()).contains("mac=00%3A1A%3A79%3A12%3A34%3A56")
        assertThat(observedCookies.single()).contains("PHPSESSID=session-42")
        assertThat(observedCookies.single()).contains("mac=00%3A1A%3A79%3A12%3A34%3A56")
        assertThat(observedCookies.single()).contains("stb_lang=en%20us")
        assertThat(observedCookies.single()).contains("timezone=Europe%2FAmsterdam")
        assertThat(observedCookies.single()).doesNotContain("sn=")
        assertThat(observedCookies.single()).doesNotContain("device_id=")
        assertThat(observedCookies.single()).doesNotContain("device_id2=")
        assertThat(observedCookies.single()).doesNotContain("signature=")
    }

    @Test
    fun provider_profiles_never_share_cookie_jars() = runTest {
        val playbackCookies = mutableMapOf<String, String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    val cookie = request.header("Cookie").orEmpty()
                    val profileKey = if (cookie.contains("12%3A34%3A56")) "a" else "b"
                    if (action == "create_link") playbackCookies[profileKey] = cookie
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-$profileKey"}}"""
                        "get_profile" -> """{"js":{"name":"Profile $profileKey","status":"1"}}"""
                        "create_link" -> """{"js":{"cmd":"ffmpeg http://cdn.example.com/$profileKey.ts"}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .apply {
                            if (action == "handshake") {
                                addHeader("Set-Cookie", "PHPSESSID=session-$profileKey; Path=/; HttpOnly")
                            }
                        }
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )
        val profileA = buildStalkerDeviceProfile(
            portalUrl = "https://portal.example.com/c",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        ).copy(providerId = 101L)
        val profileB = buildStalkerDeviceProfile(
            portalUrl = "https://portal.example.com/c",
            macAddress = "00:1A:79:12:34:57",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        ).copy(providerId = 202L)
        val sessionA = (service.authenticate(profileA) as Result.Success).data.first
        val sessionB = (service.authenticate(profileB) as Result.Success).data.first

        service.createLink(sessionA, profileA, StalkerStreamKind.LIVE, "ffmpeg http://localhost/ch/1_")
        service.createLink(sessionB, profileB, StalkerStreamKind.LIVE, "ffmpeg http://localhost/ch/2_")

        assertThat(playbackCookies.getValue("a")).contains("PHPSESSID=session-a")
        assertThat(playbackCookies.getValue("a")).doesNotContain("session-b")
        assertThat(playbackCookies.getValue("b")).contains("PHPSESSID=session-b")
        assertThat(playbackCookies.getValue("b")).doesNotContain("session-a")
        assertThat(sessionA.sessionScopeKey).contains("provider:101|epoch:")
        assertThat(sessionB.sessionScopeKey).contains("provider:202|epoch:")

        service.invalidateSessionScopes(101L)
        val sessionA2 = (service.authenticate(profileA) as Result.Success).data.first
        assertThat(sessionA2.sessionScopeKey).isNotEqualTo(sessionA.sessionScopeKey)
    }

    @Test
    fun authenticate_reports_access_denied_html_clearly() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to """<!DOCTYPE html><html><body>Access Denied.</body></html>"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.message).isEqualTo("Portal denied the request for handshake.")
    }

    @Test
    fun getLiveCategories_classifies_notValidToken_marker_as_authorization_without_transport_retries() = runTest {
        var requestCount = 0
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestCount++
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"js":{"not_valid_token":1}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getLiveCategories(stalkerSession(), stalkerProfile())

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.Authorization::class.java)
        assertThat(error.message).isEqualTo("Portal token is invalid.")
        assertThat(requestCount).isEqualTo(1)
    }

    @Test
    fun getLiveCategories_classifies_http200_denial_html_as_authorization_without_transport_retries() = runTest {
        var requestCount = 0
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestCount++
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            "<!DOCTYPE html><html><body>Access Denied.</body></html>"
                                .toResponseBody("text/html".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getLiveCategories(stalkerSession(), stalkerProfile())

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.Authorization::class.java)
        assertThat(error.message).isEqualTo("Portal denied the request for get_genres.")
        assertThat(requestCount).isEqualTo(1)
    }

    @Test
    fun http_authentication_error_matrix_distinguishes_expired_session_and_forbidden_request() = runTest {
        suspend fun requestResult(code: Int, body: String): Result<List<StalkerCategoryRecord>> {
            val service = OkHttpStalkerApiService(
                okHttpClient = OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        Response.Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(code)
                            .message("HTTP $code")
                            .body(body.toResponseBody("application/json".toMediaType()))
                            .build()
                    }
                    .build(),
                json = Json { ignoreUnknownKeys = true }
            )
            return service.getLiveCategories(stalkerSession(), stalkerProfile())
        }

        val unauthorized = requestResult(401, "{\"error\":\"expired\"}") as Result.Error
        val tokenRejected = requestResult(403, "{\"error\":\"not_valid_token\"}") as Result.Error
        val forbidden = requestResult(403, "{\"error\":\"forbidden\"}") as Result.Error

        assertThat(unauthorized.exception).isInstanceOf(StalkerApiError.SessionExpired::class.java)
        assertThat(tokenRejected.exception).isInstanceOf(StalkerApiError.SessionExpired::class.java)
        assertThat(forbidden.exception).isInstanceOf(StalkerApiError.BlockedOrConfiguration::class.java)
        assertThat(forbidden.exception).isNotInstanceOf(StalkerApiError.Authorization::class.java)
    }

    @Test
    fun getVodStreams_reports_huge_catalogs_as_truncated_instead_of_returning_partial_success() = runTest {
        var requestCount = 0
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestCount++
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"total_items":201,"max_page_items":1,"data":[{"id":"$requestCount","name":"Movie $requestCount","category_id":"42","cmd":"ffmpeg http://example.com/movie.mp4"}]}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getVodStreams(stalkerSession(), stalkerProfile(), categoryId = "42")

        // Bulk APIs cannot carry a resume cursor, so a partial aggregate must be
        // reported explicitly. The paged API is responsible for resumable loads.
        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.CatalogTruncated::class.java)
        val truncation = error.exception as StalkerApiError.CatalogTruncated
        assertThat(truncation.advertisedTotalPages).isEqualTo(201)
        assertThat(truncation.pageLimit).isEqualTo(200)
        assertThat(requestCount).isEqualTo(200)
    }

    @Test
    fun getVodStreamsPage_preserves_page_201_for_resume_after_aggregate_limit() = runTest {
        var requestedPage: String? = null
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    requestedPage = chain.request().url.queryParameter("p")
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"total_items":300,"max_page_items":1,"data":[{"id":"201","name":"Movie 201","category_id":"42","cmd":"ffmpeg http://example.com/movie.mp4"}]}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getVodStreamsPage(stalkerSession(), stalkerProfile(), "42", page = 201)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val page = (result as Result.Success).data
        assertThat(requestedPage).isEqualTo("201")
        assertThat(page.page).isEqualTo(201)
        assertThat(page.totalPages).isEqualTo(300)
        assertThat(page.advertisedTotalItems).isEqualTo(300)
        assertThat(page.isComplete).isFalse()
        assertThat(page.isTruncated).isFalse()
    }

    @Test
    fun getVodStreamsPage_treats_199_as_incomplete_and_200_as_complete_when_total_is_200() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_ordered_list" to """{"js":{"total_items":200,"max_page_items":1,"data":[{"id":"item","name":"Movie","category_id":"42","cmd":"ffmpeg http://example.com/movie.mp4"}]}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val beforeLimit = service.getVodStreamsPage(stalkerSession(), stalkerProfile(), "42", page = 199)
        val atLimit = service.getVodStreamsPage(stalkerSession(), stalkerProfile(), "42", page = 200)

        assertThat((beforeLimit as Result.Success).data.isComplete).isFalse()
        assertThat((atLimit as Result.Success).data.isComplete).isTrue()
    }

    @Test
    fun getVodStreamsPage_does_not_claim_completion_when_totals_are_missing() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_ordered_list" to """{"js":{"data":[{"id":"1","name":"Movie","category_id":"42","cmd":"ffmpeg http://example.com/movie.mp4"}]}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getVodStreamsPage(stalkerSession(), stalkerProfile(), "42", page = 1)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val page = (result as Result.Success).data
        assertThat(page.hasAdvertisedTotal).isFalse()
        assertThat(page.advertisedTotalPages).isNull()
        assertThat(page.isComplete).isFalse()
    }

    @Test
    fun getVodStreamsPage_rejects_empty_page_before_advertised_end() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_ordered_list" to """{"js":{"total_items":3,"max_page_items":1,"data":[]}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getVodStreamsPage(stalkerSession(), stalkerProfile(), "42", page = 1)

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).exception).isInstanceOf(StalkerApiError.Malformed::class.java)
    }

    @Test
    fun getVodStreams_rejects_changing_advertised_page_totals() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val page = chain.request().url.queryParameter("p")?.toIntOrNull() ?: 1
                    val totalItems = if (page == 1) 4 else 6
                    val id = page.toString()
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """{"js":{"total_items":$totalItems,"max_page_items":2,"data":[{"id":"$id","name":"Movie $id","category_id":"42","cmd":"ffmpeg http://example.com/movie.mp4"}]}}"""
                                .toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getVodStreams(stalkerSession(), stalkerProfile(), categoryId = "42")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).exception).isInstanceOf(StalkerApiError.Malformed::class.java)
    }

    @Test
    fun getVodStreams_rejects_repeated_page_payloads() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_ordered_list" to """{"js":{"total_items":3,"max_page_items":1,"data":[{"id":"same","name":"Repeated","category_id":"42","cmd":"ffmpeg http://example.com/movie.mp4"}]}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getVodStreams(stalkerSession(), stalkerProfile(), categoryId = "42")

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat((result as Result.Error).exception).isInstanceOf(StalkerApiError.Malformed::class.java)
    }

    @Test
    fun getVodStreamsPage_parses_supported_isSeries_representations_and_tracks_missing_markers() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_ordered_list" to """
                    {"js":{"total_items":"8","max_page_items":"20","data":[
                      {"id":"1","name":"Numeric series","category_id":"42","is_series":1},
                      {"id":"2","name":"String series","category_id":"42","is_series":"1"},
                      {"id":"3","name":"Boolean series","category_id":"42","is_series":true},
                      {"id":"4","name":"Numeric movie","category_id":"42","is_series":0},
                      {"id":"5","name":"String movie","category_id":"42","is_series":"0"},
                      {"id":"6","name":"Boolean movie","category_id":"42","is_series":false},
                      {"id":"7","name":"Missing marker","category_id":"42"},
                      {"id":"8","name":"Invalid marker","category_id":"42","is_series":"maybe"}
                    ]}}
                """.trimIndent()
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getVodStreamsPage(stalkerSession(), stalkerProfile(), "42", page = 1)

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val items = (result as Result.Success).data.items
        assertThat(items.take(3).map { it.isSeries }).containsExactly(true, true, true).inOrder()
        assertThat(items.drop(3).map { it.isSeries }).containsExactly(false, false, false, false, false).inOrder()
        assertThat(items.take(6).map { it.hasSeriesMarker }).containsExactly(true, true, true, true, true, true).inOrder()
        assertThat(items.drop(6).map { it.hasSeriesMarker }).containsExactly(false, false).inOrder()
    }

    @Test
    fun getLiveCategories_stays_on_selected_endpoint_after_authentication() = runTest {
        val requestedUrls = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val request = chain.request()
                    requestedUrls += request.url.toString()
                    val body = when (request.url.encodedPath) {
                        "/server/load.php" -> if (request.url.queryParameter("action") == "get_genres") {
                            throw java.io.IOException("\\n not found: limit=1 content=0d…")
                        } else {
                            """{"js":{"token":"token-123"}}"""
                        }
                        "/portal.php" -> """{"js":[{"id":"10","title":"News"}]}"""
                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                })
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getLiveCategories(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.message).contains("not found")
        assertThat(requestedUrls).containsExactly(
            "https://portal.example.com/server/load.php?type=itv&action=get_genres&JsHttpRequest=1-xml",
            "https://portal.example.com/server/load.php?type=itv&action=get_genres&JsHttpRequest=1-xml",
            "https://portal.example.com/server/load.php?type=itv&action=get_genres&JsHttpRequest=1-xml"
        )
    }

    @Test
    fun streamLiveStreams_stays_on_selected_endpoint_after_authentication() = runTest {
        val requestedUrls = mutableListOf<String>()
        val streamed = mutableListOf<StalkerItemRecord>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val request = chain.request()
                    requestedUrls += request.url.toString()
                    val response = when (request.url.encodedPath) {
                        "/server/load.php" -> throw java.io.IOException("stream endpoint failed")
                        "/portal.php" -> Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                """{"js":{"data":[{"id":"100","name":"News","tv_genre_id":"10","cmd":"ffmpeg http://example.com/live.ts"}]}}"""
                                    .toResponseBody("application/json".toMediaType())
                            )
                            .build()
                        else -> error("Unexpected path ${request.url.encodedPath}")
                    }
                    response
                })
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.streamLiveStreams(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        ) { item ->
            streamed += item
        }

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.message).contains("stream endpoint failed")
        assertThat(streamed).isEmpty()
        assertThat(requestedUrls).containsExactly(
            "https://portal.example.com/server/load.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml",
            "https://portal.example.com/server/load.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml",
            "https://portal.example.com/server/load.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml"
        )
    }

    @Test
    fun getLiveStreams_prefers_get_all_channels_for_bulk_live_loads() = runTest {
        val requestedActions = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    requestedActions += action
                    val body = when (action) {
                        "get_all_channels" -> """
                            {"js":{"data":[{"id":"100","name":"News","tv_genre_id":"10","cmd":"ffmpeg http://example.com/live.ts"}]}}
                        """.trimIndent()
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getLiveStreams(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            categoryId = null
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.map { it.name }).containsExactly("News")
        assertThat(requestedActions).containsExactly("get_all_channels")
    }

    @Test
    fun getLiveStreams_preserves_command_variants_and_temp_link_flags() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_all_channels" to """
                    {"js":{"data":[
                        {
                            "id":"100",
                            "name":"News",
                            "tv_genre_id":"10",
                            "cmd":"ffmpeg http://localhost/ch/100_",
                            "cmd_1":"ffmpeg http://backup.example.com/play/live.php?stream=100",
                            "cmd_2":"ffmpeg http://edge.example.com/live/news.m3u8",
                            "mc_cmd":"ffmpeg http://mc.example.com/live/100.ts",
                            "cmds":[{"url":"ffmpeg http://multi.example.com/live/100.ts"}],
                            "use_http_tmp_link":"1",
                            "nginx_secure_link":"1",
                            "allow_local_timeshift":"1",
                            "archive":"1"
                        }
                    ]}}
                """.trimIndent()
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getLiveStreams(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            categoryId = null
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val item = (result as Result.Success).data.single()
        assertThat(item.commandVariants.map { it.sourceKey })
            .containsAtLeast("cmd", "cmd_1", "cmd_2", "mc_cmd", "cmds[0]")
        assertThat(item.commandVariants.map { it.cmd })
            .contains("ffmpeg http://edge.example.com/live/news.m3u8")
        assertThat(item.playbackDescriptor?.primaryMode).isEqualTo(StalkerPlaybackMode.MULTI_CMD)
        assertThat(item.portalCapabilities.useHttpTemporaryLink).isTrue()
        assertThat(item.portalCapabilities.nginxSecureLink).isTrue()
        assertThat(item.portalCapabilities.allowLocalTimeshift).isTrue()
        assertThat(item.portalCapabilities.archiveAvailable).isTrue()
    }

    @Test
    fun streamLiveStreams_emits_bulk_channels_from_js_data_without_list_materialization() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_all_channels" to """
                    {"js":{"data":[
                        {"id":"100","name":"News","tv_genre_id":"10","cmd":"ffmpeg http://example.com/news.ts"},
                        {"id":"101","name":"Sports","tv_genre_id":"11","cmd":"ffmpeg http://example.com/sports.ts"}
                    ]}}
                """.trimIndent()
            ),
            json = Json { ignoreUnknownKeys = true }
        )
        val streamed = mutableListOf<StalkerItemRecord>()

        val result = service.streamLiveStreams(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        ) { item ->
            streamed += item
        }

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data).isEqualTo(2)
        assertThat(streamed.map { it.name }).containsExactly("News", "Sports").inOrder()
        assertThat(streamed.map { it.categoryId }).containsExactly("10", "11").inOrder()
    }

    @Test
    fun getLiveStreams_falls_back_to_paged_get_ordered_list_when_all_channels_is_unavailable() = runTest {
        val requestedUrls = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedUrls += request.url.toString()
                    val action = request.url.queryParameter("action").orEmpty()
                    val response = when (action) {
                        "get_all_channels" -> Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("".toResponseBody("application/json".toMediaType()))
                            .build()
                        "get_ordered_list" -> Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(
                                """
                                    {"js":{"total_items":"1","max_page_items":"50","data":[{"id":"100","name":"News","tv_genre_id":"10","cmd":"ffmpeg http://example.com/live.ts"}]}}
                                """.trimIndent().toResponseBody("application/json".toMediaType())
                            )
                            .build()
                        else -> error("Unexpected action '$action'")
                    }
                    response
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getLiveStreams(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            categoryId = null
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.map { it.name }).containsExactly("News")
        assertThat(requestedUrls).containsAtLeast(
            "https://portal.example.com/server/load.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml",
            "https://portal.example.com/server/load.php?type=itv&action=get_ordered_list&JsHttpRequest=1-xml&force_ch_link_check=0&fav=0&p=1"
        )
    }

    @Test
    fun getSeriesPage_requests_only_requested_page_and_reports_total_pages() = runTest {
        val requestedUrls = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedUrls += request.url.toString()
                    val page = request.url.queryParameter("p")
                    check(page == "3") { "Unexpected page '$page'" }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(
                            """
                                {"js":{"total_items":"45","max_page_items":"15","data":[{"id":"300","name":"Drama","category_id":"147"}]}}
                            """.trimIndent().toResponseBody("application/json".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getSeriesPage(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            categoryId = "147",
            page = 3
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.items.map { it.name }).containsExactly("Drama")
        assertThat(success.data.page).isEqualTo(3)
        assertThat(success.data.totalPages).isEqualTo(3)
        assertThat(success.data.isComplete).isTrue()
        assertThat(requestedUrls).containsExactly(
            "https://portal.example.com/server/load.php?type=series&action=get_ordered_list&JsHttpRequest=1-xml&category=147&p=3"
        )
    }

    @Test
    fun getSeriesPage_parses_datetime_added_field_into_last_modified_source_timestamp() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_ordered_list" to """
                    {"js":{"total_items":"1","max_page_items":"15","data":[{"id":"300","name":"Drama","category_id":"147","added":"2026-05-18 13:02:23","is_series":"1"}]}}
                """.trimIndent()
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getSeriesPage(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "Europe/Amsterdam",
                locale = "en"
            ),
            categoryId = "147",
            page = 1
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        val item = success.data.items.single()
        val expectedAddedAt = LocalDateTime.of(2026, 5, 18, 13, 2, 23)
            .atZone(ZoneId.of("Europe/Amsterdam"))
            .toInstant()
            .toEpochMilli()
        assertThat(item.addedAt).isEqualTo(expectedAddedAt)
    }

    @Test
    fun getBulkEpg_parses_channel_ids_from_bulk_response_rows() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_epg_info" to """
                    {"js":[
                        {"id":"p1","ch_id":"100","name":"Morning News","descr":"Top stories","start_timestamp":"1700000000","stop_timestamp":"1700003600"},
                        {"id":"p2","channel_id":"sports-guide-id","name":"Live Sports","descr":"Match coverage","start_timestamp":"1700003600","stop_timestamp":"1700007200"}
                    ]}
                """.trimIndent()
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getBulkEpg(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            periodHours = 6
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.map { it.channelId }).containsExactly("100", "sports-guide-id")
        assertThat(success.data.map { it.title }).containsExactly("Morning News", "Live Sports")
    }

    @Test
    fun getBulkEpg_interpretsOffsetlessProgramTimesInPortalTimezone() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_epg_info" to """
                    {"js":[
                        {"id":"p1","ch_id":"100","name":"Evening News","time":"2026-07-13 20:00:00","time_to":"2026-07-13 21:00:00"}
                    ]}
                """.trimIndent()
            ),
            json = Json { ignoreUnknownKeys = true }
        )
        val zone = ZoneId.of("Europe/Amsterdam")

        val result = service.getBulkEpg(
            session = stalkerSession(),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = zone.id,
                locale = "en"
            ),
            periodHours = 6
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val program = (result as Result.Success).data.single()
        assertThat(program.startTimeMillis).isEqualTo(
            LocalDateTime.of(2026, 7, 13, 20, 0).atZone(zone).toInstant().toEpochMilli()
        )
        assertThat(program.endTimeMillis).isEqualTo(
            LocalDateTime.of(2026, 7, 13, 21, 0).atZone(zone).toInstant().toEpochMilli()
        )
    }

    @Test
    fun getSeriesDetails_expands_season_shell_rows_into_episode_placeholders() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "get_ordered_list" to """
                    {"js":{"total_items":1,"max_page_items":14,"data":[{"id":"55000:1","name":"Season 1","description":"Doc","series":[1,2,3,4],"cmd":"eyJzZXJpZXNfaWQiOjU1MDAwLCJzZWFzb25fbnVtIjoxLCJ0eXBlIjoic2VyaWVzIn0=","screenshot_uri":"https://img.example.com/season1.jpg"}]}}
                """.trimIndent()
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getSeriesDetails(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            seriesId = "55000:55000"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(success.data.series.name).isEmpty()
        assertThat(success.data.seasons).hasSize(1)
        val season = success.data.seasons.single()
        assertThat(season.seasonNumber).isEqualTo(1)
        assertThat(season.episodes.map { it.episodeNumber }).containsExactly(1, 2, 3, 4).inOrder()
        assertThat(season.episodes.first().cmd).isEqualTo("eyJzZXJpZXNfaWQiOjU1MDAwLCJzZWFzb25fbnVtIjoxLCJ0eXBlIjoic2VyaWVzIn0=")
    }

    @Test
    fun getSeriesDetails_fetches_shell_season_page_for_explicit_episode_cmds() = runTest {
        val requestedSeasonIds = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    val seasonId = request.url.queryParameter("season_id").orEmpty()
                    val body = when {
                        action != "get_ordered_list" -> error("Unexpected action '$action'")
                        seasonId == "0" -> """
                            {"js":{"total_items":1,"max_page_items":14,"data":[{"id":"55000:1","name":"Season 1","description":"Doc","series":[1,2,3,4],"cmd":"eyJzZXJpZXNfaWQiOjU1MDAwLCJzZWFzb25fbnVtIjoxLCJ0eXBlIjoic2VyaWVzIn0=","screenshot_uri":"https://img.example.com/season1.jpg"}]}}
                        """.trimIndent()
                        seasonId == "1" -> {
                            requestedSeasonIds += seasonId
                            """
                                {"js":{"total_items":1,"max_page_items":14,"data":[{"id":"episode-1","name":"Episode 1","series_number":"1","season_id":"1","cmd":"ffmpeg http://example.com/episode1.mp4","screenshot_uri":"https://img.example.com/episode1.jpg"}]}}
                            """.trimIndent()
                        }
                        else -> error("Unexpected season_id '$seasonId'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getSeriesDetails(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            seriesId = "55000:55000"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(requestedSeasonIds).containsExactly("1")
        assertThat(success.data.seasons).hasSize(1)
        val season = success.data.seasons.single()
        assertThat(season.episodes).hasSize(1)
        assertThat(season.episodes.single().cmd).isEqualTo("ffmpeg http://example.com/episode1.mp4")
    }

    @Test
    fun getSeriesDetails_preserves_shell_season_numbers_when_followup_rows_omit_them() = runTest {
        val requestedSeasonIds = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    val seasonId = request.url.queryParameter("season_id").orEmpty()
                    val body = when {
                        action != "get_ordered_list" -> error("Unexpected action '$action'")
                        seasonId == "0" ->
                            """
                                {"js":{"total_items":2,"max_page_items":14,"data":[
                                    {"id":"55000:alpha","name":"Season 1","description":"Alpha","series":[1,2],"cmd":"shell-cmd-1"},
                                    {"id":"55000:beta","name":"Season 2","description":"Beta","series":[1,2,3],"cmd":"shell-cmd-2"}
                                ]}}
                            """.trimIndent()
                        seasonId == "1" -> {
                            requestedSeasonIds += seasonId
                            """
                                {"js":{"total_items":2,"max_page_items":14,"data":[
                                    {"id":"55000:alpha","name":"Season 1","description":"Alpha","series":[1,2],"cmd":"shell-cmd-1"},
                                    {"id":"55000:beta","name":"Season 2","description":"Beta","series":[1,2,3],"cmd":"shell-cmd-2"}
                                ]}}
                            """.trimIndent()
                        }
                        seasonId == "2" -> {
                            requestedSeasonIds += seasonId
                            """
                                {"js":{"total_items":2,"max_page_items":14,"data":[
                                    {"id":"55000:alpha","name":"Season 1","description":"Alpha","series":[1,2],"cmd":"shell-cmd-1"},
                                    {"id":"55000:beta","name":"Season 2","description":"Beta","series":[1,2,3],"cmd":"shell-cmd-2"}
                                ]}}
                            """.trimIndent()
                        }
                        else -> error("Unexpected season_id '$seasonId'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getSeriesDetails(
            session = StalkerSession(
                loadUrl = "https://portal.example.com/server/load.php",
                portalReferer = "https://portal.example.com/c/",
                token = "token-123"
            ),
            profile = buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com/c",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            ),
            seriesId = "55000:55000"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val success = result as Result.Success
        assertThat(requestedSeasonIds).containsExactly("1", "2")
        assertThat(success.data.seasons.map { it.seasonNumber }).containsExactly(1, 2).inOrder()
        assertThat(success.data.seasons.map { it.name }).containsExactly("Season 1", "Season 2").inOrder()
        assertThat(success.data.seasons[0].episodes.map { it.episodeNumber }).containsExactly(1, 2).inOrder()
        assertThat(success.data.seasons[1].episodes.map { it.episodeNumber }).containsExactly(1, 2, 3).inOrder()
    }

    @Test
    fun getVodSeriesDetails_uses_shell_id_and_retains_all_episode_pages_in_provider_order() = runTest {
        val requestedSeasonIds = mutableListOf<String>()
        val requestedPages = mutableListOf<Int>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    assertThat(request.url.queryParameter("type")).isEqualTo("vod")
                    val seasonId = request.url.queryParameter("season_id").orEmpty()
                    val page = request.url.queryParameter("p")?.toIntOrNull() ?: 1
                    val body = if (seasonId == "0") {
                        """{"js":{"total_items":1,"max_page_items":14,"data":[{"id":"season-shell-77","video_id":"55000","name":"Season 1","season_number":"1","is_season":"1"}]}}"""
                    } else {
                        requestedSeasonIds += seasonId
                        requestedPages += page
                        val start = (page - 1) * 14 + 1
                        val end = minOf(page * 14, 39)
                        val entries = (start..end).joinToString(",") { episode ->
                            """{"id":"episode-$episode","name":"Episode $episode","series_number":"$episode","season_id":"season-shell-77","is_episode":true,"series":[1,2,3,4,5,6,7,8]}"""
                        }
                        """{"js":{"total_items":39,"max_page_items":14,"data":[$entries]}}"""
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.getVodSeriesDetails(
            session = stalkerSession(),
            profile = stalkerProfile(),
            seriesId = "55000"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val details = (result as Result.Success).data
        assertThat(requestedSeasonIds).containsExactly(
            "season-shell-77", "season-shell-77", "season-shell-77"
        ).inOrder()
        assertThat(requestedSeasonIds).doesNotContain("55000")
        assertThat(requestedPages).containsExactly(1, 2, 3).inOrder()
        assertThat(details.seasons.single().episodes).hasSize(39)
        assertThat(details.seasons.single().episodes.map { it.episodeNumber })
            .containsExactlyElementsIn((1..39).toList()).inOrder()
        assertThat(details.paginationEvidence.single().successfulPages).isEqualTo(3)
    }

    @Test
    fun createLink_maps_nothing_to_play_to_content_unavailable() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "create_link" to """{"js":{"error":"nothing_to_play","cmd":""}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = stalkerSession(),
            profile = stalkerProfile(),
            kind = StalkerStreamKind.MOVIE,
            cmd = "ffmpeg http://provider.invalid/movie"
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.ContentUnavailable::class.java)
        assertThat(error.message).contains("currently unavailable")
    }

    @Test
    fun createLink_readsPortalErrorBeforeOversizedDiagnosticText() = runTest {
        val oversizedDiagnostic = "storage timeout ".repeat(8_000)
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "create_link" to
                    """{"js":{"id":0,"cmd":"","error":"nothing_to_play"},"text":"$oversizedDiagnostic"}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = stalkerSession(),
            profile = stalkerProfile(),
            kind = StalkerStreamKind.MOVIE,
            cmd = "/media/331155.mpg"
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.ContentUnavailable::class.java)
        assertThat(error.exception).isNotInstanceOf(StalkerApiError.ResponseTooLarge::class.java)
    }

    @Test
    fun createLink_readsPlayableCommandBeforeOversizedDiagnosticText() = runTest {
        val oversizedDiagnostic = "ignored backend diagnostics ".repeat(8_000)
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "create_link" to
                    """{"js":{"id":42,"cmd":"ffmpeg https://cdn.example.com/movie.m3u8","error":""},"text":"$oversizedDiagnostic"}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = stalkerSession(),
            profile = stalkerProfile(),
            kind = StalkerStreamKind.MOVIE,
            cmd = "/media/42.mpg"
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        assertThat((result as Result.Success).data).isEqualTo("https://cdn.example.com/movie.m3u8")
    }

    @Test
    fun createLink_maps_scalar_nothing_to_play_to_content_unavailable() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "create_link" to """{"js":"nothing_to_play"}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            session = stalkerSession(),
            profile = stalkerProfile(),
            kind = StalkerStreamKind.MOVIE,
            cmd = "/media/123.mpg"
        )

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.ContentUnavailable::class.java)
        assertThat(error.message).contains("currently unavailable")
    }

    @Test
    fun createLink_429_opensProviderCircuitAndSuppressesSecondHttpRequest() = runTest {
        val requestCount = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                requestCount.incrementAndGet()
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(429)
                    .message("Too Many Requests")
                    .header("Retry-After", "120")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
        val coordinator = StalkerRequestCoordinator()
        val service = OkHttpStalkerApiService(
            okHttpClient = client,
            json = Json { ignoreUnknownKeys = true },
            requestCoordinator = coordinator
        )
        val profile = stalkerProfile().copy(providerId = 91L)

        val first = service.createLink(
            stalkerSession(), profile, StalkerStreamKind.MOVIE, "/media/1.mpg"
        )
        val second = service.createLink(
            stalkerSession(), profile, StalkerStreamKind.MOVIE, "/media/2.mpg"
        )

        assertThat(first).isInstanceOf(Result.Error::class.java)
        assertThat(second).isInstanceOf(Result.Error::class.java)
        assertThat((second as Result.Error).exception)
            .isInstanceOf(StalkerApiError.RateLimited::class.java)
        assertThat(requestCount.get()).isEqualTo(1)
    }

    @Test
    fun createLink_returnsAuthenticatedEndpointWhenPortalStreamsMediaInsteadOfJson() = runTest {
        val media = ByteArray(70 * 1024)
        media[0] = 0x47
        media[188] = 0x47
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(media.toResponseBody("video/mpeg".toMediaType()))
                    .build()
            }
            .build()
        val service = OkHttpStalkerApiService(
            okHttpClient = client,
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.createLink(
            stalkerSession(),
            stalkerProfile(),
            StalkerStreamKind.EPISODE,
            "/media/123.mpg",
            seriesNumber = 2
        )

        assertThat(result).isInstanceOf(Result.Success::class.java)
        val endpoint = (result as Result.Success).data
        assertThat(endpoint).contains("action=create_link")
        assertThat(endpoint).contains("series=2")
    }

    @Test
    fun authenticate_classifies_device_conflict_envelope_as_terminal_device_conflict() = runTest {
        val requestedActions = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    requestedActions += action
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123","random":"abc"}}"""
                        "get_profile" -> """{"js":{"status":1,"msg":"Device conflict - device_id mismatch","block_msg":"Please contact your provider<br>to register this device."}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(stalkerProfile())

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.DeviceConflict::class.java)
        assertThat(error.message).contains("Device conflict")
        assertThat(error.message).contains("Please contact your provider to register this device.")
        // Terminal: no bootstrap/catalog call may follow a conflicted profile.
        assertThat(requestedActions).containsExactly("handshake", "get_profile").inOrder()
    }

    @Test
    fun authenticate_tokenlessHandshake_abortsAsRateLimitedWithoutFurtherHandshakes() = runTest {
        val requestedActions = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedActions += request.url.queryParameter("action").orEmpty()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"js":{}}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(stalkerProfile())

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.RateLimited::class.java)
        // Soft throttle: no recipe-loop follow-up handshake may fire into the limiter.
        assertThat(requestedActions).containsExactly("handshake")
    }

    @Test
    fun authenticate_classifies_status2_envelope_as_device_not_registered() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> """{"js":{"status":2,"msg":"Authentication request","launcher_profile_url":"http://portal.example.com/stalker_portal/server/api/launcher_profile.php"}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(stalkerProfile())

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.DeviceNotRegistered::class.java)
        assertThat(error.message).contains("Authentication request")
    }

    @Test
    fun authenticate_classifies_time_out_of_sync_envelope_as_clock_skew() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val action = request.url.queryParameter("action").orEmpty()
                    val body = when (action) {
                        "handshake" -> """{"js":{"token":"token-123"}}"""
                        "get_profile" -> """{"js":{"status":1,"msg":"Time out of sync","block_msg":"Time on the device is not synchronized."}}"""
                        else -> error("Unexpected action '$action'")
                    }
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(stalkerProfile())

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.ClockSkew::class.java)
        assertThat(error.message).contains("clock")
        assertThat(error.message).contains("Time out of sync")
    }

    @Test
    fun authenticate_accepts_profile_payload_with_numeric_status_and_no_message() = runTest {
        // Some Ministra builds embed "status":"1" in a fully valid profile payload.
        val service = OkHttpStalkerApiService(
            okHttpClient = fakeClient(
                "handshake" to """{"js":{"token":"token-123"}}""",
                "get_profile" to """{"js":{"name":"Living Room","status":"1","max_online":"2"}}"""
            ),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(stalkerProfile())

        assertThat(result).isInstanceOf(Result.Success::class.java)
    }

    @Test
    fun authenticate_classifies_html_403_as_blocked_not_authorization() = runTest {
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    Response.Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .body(
                            (
                                "<!DOCTYPE html><html><head><title>Attention Required</title></head>" +
                                    "<body>Blocked by edge WAF</body></html>"
                                ).toResponseBody("text/html".toMediaType())
                        )
                        .build()
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(stalkerProfile())

        assertThat(result).isInstanceOf(Result.Error::class.java)
        val error = result as Result.Error
        assertThat(error.exception).isInstanceOf(StalkerApiError.BlockedOrConfiguration::class.java)
        assertThat(error.exception).isNotInstanceOf(StalkerApiError.Authorization::class.java)
    }

    @Test
    fun authenticate_prefers_redirect_hinted_portal_base_for_first_handshake() = runTest {
        val requestedUrls = mutableListOf<String>()
        val service = OkHttpStalkerApiService(
            okHttpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requestedUrls += request.url.toString()
                    val action = request.url.queryParameter("action").orEmpty()
                    val path = request.url.encodedPath
                    when {
                        action.isNotEmpty() -> {
                            val body = when (action) {
                                "handshake" -> """{"js":{"token":"token-123"}}"""
                                // Stop discovery right after the profile; only URL order matters here.
                                "get_profile" -> """{"js":{"status":1,"msg":"Device conflict - device_id mismatch"}}"""
                                else -> error("Unexpected action '$action'")
                            }
                            Response.Builder()
                                .request(request)
                                .protocol(Protocol.HTTP_1_1)
                                .code(200)
                                .message("OK")
                                .body(body.toResponseBody("application/json".toMediaType()))
                                .build()
                        }
                        // Test fakes short-circuit the chain before the redirect interceptor,
                        // so simulate the already-followed redirect like authenticate_uses_
                        // effective_loadScript_after_http_redirect does: the response carries
                        // the final effective request URL.
                        path == "/" -> Response.Builder()
                            .request(
                                request.newBuilder()
                                    .url("https://portal.example.com/stalker_portal/c/")
                                    .build()
                            )
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body("<html>portal</html>".toResponseBody("text/html".toMediaType()))
                            .build()
                        else -> error("Unexpected request ${request.url}")
                    }
                }
                .build(),
            json = Json { ignoreUnknownKeys = true }
        )

        val result = service.authenticate(
            buildStalkerDeviceProfile(
                portalUrl = "https://portal.example.com",
                macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250",
                timezone = "UTC",
                locale = "en"
            )
        )

        // The device-conflict stop is intentional; what matters is request routing.
        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(requestedUrls.first()).isEqualTo("https://portal.example.com/")
        val firstHandshake = requestedUrls.first { it.contains("action=handshake") }
        assertThat(firstHandshake)
            .startsWith("https://portal.example.com/stalker_portal/server/load.php?")
    }

    private fun fakeClient(vararg responses: Pair<String, String>): OkHttpClient {
        val byAction = responses.toMap()
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val action = request.url.queryParameter("action").orEmpty()
                val body = byAction[action] ?: error("Missing fake response for action '$action'")
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(body.toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    private fun stalkerSession() = StalkerSession(
        loadUrl = "https://portal.example.com/server/load.php",
        portalReferer = "https://portal.example.com/c/",
        token = "token-123"
    )

    private fun stalkerProfile() = buildStalkerDeviceProfile(
        portalUrl = "https://portal.example.com/c",
        macAddress = "00:1A:79:12:34:56",
        deviceProfile = "MAG250",
        timezone = "UTC",
        locale = "en"
    )

    private class PendingCall : Call {
        private val request = Request.Builder().url("https://portal.example.com/server/load.php").build()
        @Volatile private var callback: Callback? = null
        @Volatile var enqueued = false
        @Volatile var cancelled = false

        override fun request(): Request = request
        override fun execute(): Response = error("Synchronous execution must not be used")
        override fun enqueue(responseCallback: Callback) {
            enqueued = true
            callback = responseCallback
        }
        override fun cancel() {
            cancelled = true
        }
        override fun isExecuted(): Boolean = enqueued
        override fun isCanceled(): Boolean = cancelled
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = PendingCall()

        fun respond() {
            callback?.onResponse(
                this,
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("{}".toResponseBody("application/json".toMediaType()))
                    .build()
            ) ?: error("Call was not enqueued")
        }
    }
}
