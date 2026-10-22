package com.streamvault.app.di

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.remote.stalker.StalkerApiError
import com.streamvault.data.remote.stalker.StalkerNetworkPriority
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.data.remote.stalker.StalkerRequestCoordinator
import com.streamvault.data.remote.stalker.StalkerRequestDescriptor
import com.streamvault.data.remote.stalker.StalkerSession
import com.streamvault.data.remote.stalker.StalkerDeviceProfile
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.StalkerRequestPriority
import com.streamvault.domain.model.StalkerAuthMode
import com.streamvault.domain.model.StalkerMagPreset
import com.streamvault.domain.model.StalkerPortalFingerprint
import com.streamvault.domain.model.StalkerBootstrapRecipe
import com.streamvault.domain.model.StalkerEndpointPreference
import com.streamvault.domain.model.StalkerCookieMode
import com.streamvault.domain.model.StalkerPlaybackBackendHint
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.StalkerTransportOrigin
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, manifest = Config.NONE, sdk = [35])
class NetworkModuleStalkerTest {
    @Test
    fun appFactoryUsesSharedAdaptivePoolForMovieAndSeriesHttpRequests() = runBlocking {
        org.junit.Assume.assumeTrue(com.streamvault.data.BuildConfig.STALKER_ADAPTIVE_INDEXING)
        org.junit.Assume.assumeTrue(com.streamvault.data.BuildConfig.STALKER_INDEX_PAGE_CONCURRENCY == 8)
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val count = active.incrementAndGet()
                    maximum.updateAndGet { maxOf(it, count) }
                    try { Thread.sleep(150L) } finally { active.decrementAndGet() }
                    return MockResponse().setBody("""{"js":{"total_items":2240,"max_page_items":14,"data":[{"id":"1","name":"Item","category_id":"42"}]}}""")
                }
            }
            server.start()
            val coordinator = StalkerRequestCoordinator(null)
            val client = NetworkModule.provideOkHttpClient(org.robolectric.RuntimeEnvironment.getApplication())
            val service = NetworkModule.provideStalkerApiService(client, Json { ignoreUnknownKeys = true }, coordinator)
            val profile = StalkerDeviceProfile(
                portalUrl = server.url("/c/").toString(), macAddress = "00:1A:79:12:34:56",
                deviceProfile = "MAG250", timezone = "UTC", locale = "en", providerId = 993L,
                authMode = StalkerAuthMode.AUTO, magPreset = StalkerMagPreset.GENERIC_SAFE,
                portalFingerprint = StalkerPortalFingerprint.BASIC_MAC,
                bootstrapRecipe = StalkerBootstrapRecipe.GENERIC_SAFE,
                endpointPreference = StalkerEndpointPreference.AUTO, cookieMode = StalkerCookieMode.NONE,
                playbackBackendHint = StalkerPlaybackBackendHint.AUTO,
                username = "", password = "", serialNumber = "fixture", deviceId = "fixture",
                deviceId2 = "fixture", signature = "fixture", userAgent = "fixture",
                playerUserAgent = "fixture", xUserAgent = "Model: MAG250; Link: WiFi",
                transportGrant = StalkerTransportGrant(StalkerTransportMode.USER_ACCEPTED_HTTP,
                    StalkerTransportOrigin("http", server.hostName, server.port), consentedAt = 1L)
            )
            val session = StalkerSession(loadUrl = server.url("/server/load.php").toString(),
                portalReferer = server.url("/c/").toString(), token = "fixture")
            val started = System.nanoTime()
            (1..160).map { page -> async {
                val movies = page % 2 == 0
                coordinator.execute(993L, StalkerRequestPriority.BACKGROUND_INDEX,
                    StalkerRequestDescriptor(if (movies) "MOVIE" else "SERIES", "INDEX_PAGE", page = page)) {
                    val result = if (movies) service.getVodStreamsPage(session, profile, "42", page, null, "added")
                        else service.getSeriesPage(session, profile, "42", page, "added")
                    assertThat(result).isInstanceOf(Result.Success::class.java)
                }
            } }.awaitAll()
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000L
            assertThat(server.requestCount).isEqualTo(160)
            assertThat(maximum.get()).isAtLeast(7)
            assertThat(maximum.get()).isAtMost(8)
            println("Stalker app transport fixture: requests=160 responseDelayMillis=150 elapsedMillis=$elapsedMillis maxConcurrent=${maximum.get()}")
        }
    }

    @Test
    fun metadataCooldownAlsoBlocksAppTransportBeforeHttp() = runTest {
        val coordinator = StalkerRequestCoordinator()
        coordinator.recordFailure(991L, StalkerApiError.RateLimited(retryAfterMillis = 120_000L))
        val requests = AtomicInteger()
        val provider = provider(991L, coordinator, requests, status = 200)

        val result = provider.authenticate()

        assertThat(result).isInstanceOf(Result.Error::class.java)
        assertThat(requests.get()).isEqualTo(0)
    }

    @Test
    fun appTransportRateLimitClosesTheSharedMetadataCircuit() = runTest {
        val coordinator = StalkerRequestCoordinator()
        val requests = AtomicInteger()
        val provider = provider(992L, coordinator, requests, status = 429)

        assertThat(provider.authenticate()).isInstanceOf(Result.Error::class.java)
        val rejected = runCatching {
            coordinator.acquireNetworkPermit(992L, StalkerNetworkPriority.INTERACTIVE)
        }.exceptionOrNull()

        assertThat(rejected).isInstanceOf(StalkerApiError.RateLimited::class.java)
        assertThat(requests.get()).isEqualTo(1)
    }

    @Test
    fun serverRetryAfterAlsoClosesTheSharedCircuit() = runTest {
        val coordinator = StalkerRequestCoordinator()
        val requests = AtomicInteger()
        val provider = provider(994L, coordinator, requests, status = 503)
        assertThat(provider.authenticate()).isInstanceOf(Result.Error::class.java)
        val rejected = runCatching {
            coordinator.acquireNetworkPermit(994L, StalkerNetworkPriority.INTERACTIVE)
        }.exceptionOrNull()
        assertThat(rejected).isInstanceOf(StalkerApiError.RateLimited::class.java)
        assertThat(requests.get()).isEqualTo(1)
    }

    private fun provider(
        providerId: Long,
        coordinator: StalkerRequestCoordinator,
        requests: AtomicInteger,
        status: Int
    ): StalkerProvider {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.incrementAndGet()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(status)
                .message(if (status == 429) "Too Many Requests" else "OK")
                .header("Retry-After", "120")
                .body("{}".toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return StalkerProvider(
            providerId = providerId,
            api = NetworkModule.provideStalkerApiService(client, Json { ignoreUnknownKeys = true }, coordinator),
            portalUrl = "https://portal-$providerId.invalid/c/",
            macAddress = "00:1A:79:12:34:56",
            deviceProfile = "MAG250",
            timezone = "UTC",
            locale = "en"
        )
    }
}
