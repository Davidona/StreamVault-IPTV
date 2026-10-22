package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.StalkerTransportChallengeReason
import com.streamvault.domain.model.StalkerTransportGrant
import com.streamvault.domain.model.StalkerTransportMode
import com.streamvault.domain.model.StalkerTransportOrigin
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test

class StalkerTransportFactoryTest {

    @Test
    fun `HTTP requests proceed without explicit transport consent`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val factory = StalkerTransportFactory(OkHttpClient())

            val client = factory.clientFor(server.url("/").toString(), null)
            client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                assertThat(it.isSuccessful).isTrue()
            }
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `cross-origin redirect requires consent and strips sensitive headers after acceptance`() {
        MockWebServer().use { target ->
            MockWebServer().use { source ->
                val sourceCertificate = HeldCertificate.Builder()
                    .commonName("localhost")
                    .addSubjectAlternativeName("localhost")
                    .build()
                val sourceServerCertificates = HandshakeCertificates.Builder()
                    .heldCertificate(sourceCertificate)
                    .build()
                source.useHttps(sourceServerCertificates.sslSocketFactory(), false)
                val sourceClientCertificates = HandshakeCertificates.Builder()
                    .addTrustedCertificate(sourceCertificate.certificate)
                    .build()
                val strictClient = OkHttpClient.Builder()
                    .sslSocketFactory(
                        sourceClientCertificates.sslSocketFactory(),
                        sourceClientCertificates.trustManager
                    )
                    .build()
                target.enqueue(MockResponse().setBody("ok"))
                source.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", target.url("/portal.php"))
                )
                val sourceUrl = source.url("/c/").newBuilder().host("localhost").build()
                val factory = StalkerTransportFactory(strictClient)
                val request = Request.Builder()
                    .url(sourceUrl)
                    .header("Authorization", "Bearer secret")
                    .header("Cookie", "mac=00:1A:79:00:00:01")
                    .header("X-User-Agent", "Model: MAG250")
                    .build()

                val failure = runCatching {
                    factory.clientFor(sourceUrl.toString(), null)
                        .newCall(request)
                        .execute()
                        .close()
                }.exceptionOrNull()
                assertThat(failure).isInstanceOf(StalkerApiError.TransportConsentRequired::class.java)
                assertThat(target.requestCount).isEqualTo(0)

                source.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .setHeader("Location", target.url("/portal.php"))
                )
                val targetOrigin = target.url("/").toTransportOrigin()
                val acceptedClient = factory.clientFor(
                    sourceUrl.toString(),
                    StalkerTransportGrant(
                        mode = StalkerTransportMode.USER_ACCEPTED_HTTP,
                        origin = targetOrigin,
                        consentedAt = 2L
                    )
                )
                acceptedClient.newCall(request).execute().use {
                    assertThat(it.isSuccessful).isTrue()
                }
                val redirected = target.takeRequest()
                assertThat(redirected.getHeader("Authorization")).isNull()
                assertThat(redirected.getHeader("Cookie")).isNull()
                assertThat(redirected.getHeader("X-User-Agent")).isNull()
            }
        }
    }

    @Test
    fun `self-signed HTTPS is accepted only for approved origin and SPKI`() = runTest {
        val certificate = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.enqueue(MockResponse().setBody("ok"))
            val url = server.url("/server/load.php").newBuilder()
                .host("localhost")
                .build()
            val factory = StalkerTransportFactory(OkHttpClient())

            val strictError = runCatching {
                factory.clientFor(url.toString(), null)
                    .newCall(Request.Builder().url(url).build())
                    .execute()
                    .close()
            }.exceptionOrNull()
            assertThat(strictError).isInstanceOf(IOException::class.java)

            val challenge = factory.challengeForTlsFailure(url.toString(), strictError!!)
            assertThat(challenge).isNotNull()
            assertThat(challenge!!.reason).isEqualTo(StalkerTransportChallengeReason.INVALID_TLS)
            assertThat(challenge.proposedSpkiSha256).isNotEmpty()

            val acceptedClient = factory.clientFor(url.toString(), challenge.acceptedGrant(now = 3L))
            acceptedClient.newCall(Request.Builder().url(url).build()).execute().use {
                assertThat(it.isSuccessful).isTrue()
            }

            val wrongOrigin = StalkerTransportGrant(
                mode = StalkerTransportMode.USER_ACCEPTED_UNVERIFIED_HTTPS,
                origin = StalkerTransportOrigin("https", "example.invalid", 443),
                spkiSha256 = challenge.proposedSpkiSha256,
                consentedAt = 4L
            )
            val mismatch = runCatching { factory.clientFor(url.toString(), wrongOrigin) }
                .exceptionOrNull()
            assertThat(mismatch).isInstanceOf(StalkerApiError.TransportConsentRequired::class.java)
        }
    }
}
