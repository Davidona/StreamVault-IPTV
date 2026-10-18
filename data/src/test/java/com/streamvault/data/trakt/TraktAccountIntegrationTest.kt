package com.streamvault.data.trakt

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.streamvault.data.security.AndroidKeystoreCredentialCrypto
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Local acceptance coverage using the real HTTP client, crypto and disk store.
 * Browser consent and Android App Link delivery still require a real device test.
 */
@RunWith(RobolectricTestRunner::class)
class TraktAccountIntegrationTest {
    private val config = TraktConfig("public-client", "https://streamvault.test/oauth/trakt/callback/index.html")
    private var now = 2000000L
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun store() = EncryptedTraktTokenStore(context, AndroidKeystoreCredentialCrypto(context))
    private fun account(server: MockWebServer) = TraktAuthService(config, store(),
        TraktApiClient(config, OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(), server.url("/"))) { now }
    private fun state(url: String) = URI(url).rawQuery.split('&').associate {
        val parts = it.split('=', limit = 2)
        parts[0] to URLDecoder.decode(parts[1], "UTF-8")
    }.getValue("state")
    private fun tokenResponse(access: String, refresh: String) = MockResponse().setBody(
        """{"access_token":"$access","refresh_token":"$refresh","created_at":$now,"expires_in":604800}""")
    private fun profileResponse() = MockResponse().setBody("""{"user":{"username":"viewer"}}""")

    @Test fun `login survives restart and persists rotating token pair through HTTP and encryption`() = runBlocking {
        MockWebServer().use { server ->
            store().write(TraktStoredSession())
            val first = account(server)
            val state = state(first.beginSignIn())
            val verifier = store().read().pending!!.verifier
            val restored = account(server)
            restored.load()
            assertTrue(restored.state.value.connecting)
            server.enqueue(tokenResponse("first-access", "first-refresh"))
            server.enqueue(profileResponse())
            restored.completeSignIn("${config.redirectUri}?code=authorization-code&state=$state")
            assertTrue(restored.state.value.connected)
            assertEquals("viewer", restored.state.value.username)
            val exchange = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/oauth/token", exchange.path)
            val exchangeBody = Gson().fromJson(exchange.body.readUtf8(), JsonObject::class.java)
            assertEquals(verifier, exchangeBody["code_verifier"].asString)
            assertEquals(config.redirectUri, exchangeBody["redirect_uri"].asString)
            assertEquals("Bearer first-access", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            val record = File(context.noBackupFilesDir, "trakt_session").readText()
            assertTrue(record.startsWith("enc:v1:"))
            assertFalse(record.contains("first-access"))
            assertFalse(record.contains("first-refresh"))
            assertNull(store().read().pending)

            server.enqueue(profileResponse())
            val restarted = account(server)
            restarted.load()
            assertEquals("viewer", restarted.state.value.username)
            assertEquals("Bearer first-access", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            now += 604801
            server.enqueue(tokenResponse("rotated-access", "rotated-refresh"))
            server.enqueue(profileResponse())
            restarted.refreshProfile()
            val refreshBody = Gson().fromJson(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8(), JsonObject::class.java)
            assertEquals("refresh_token", refreshBody["grant_type"].asString)
            assertEquals("first-refresh", refreshBody["refresh_token"].asString)
            assertFalse(refreshBody.has("client_secret"))
            assertEquals("Bearer rotated-access", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("Authorization"))
            val saved = store().read().tokens!!
            assertEquals("rotated-access", saved.accessToken)
            assertEquals("rotated-refresh", saved.refreshToken)

            restarted.disconnect()
            val disconnected = account(server)
            disconnected.load()
            assertFalse(disconnected.state.value.connected)
            assertFalse(File(context.noBackupFilesDir, "trakt_session").exists())
        }
    }

    @Test fun `denial and cancellation after restart leave no credentials and make no HTTP requests`() = runBlocking {
        MockWebServer().use { server ->
            for (denied in listOf(true, false)) {
                val state = state(account(server).beginSignIn())
                val restored = account(server)
                restored.load()
                if (denied) restored.completeSignIn("${config.redirectUri}?error=access_denied&state=$state")
                else {
                    restored.cancelSignIn()
                    restored.completeSignIn("${config.redirectUri}?code=late-code&state=$state")
                }
                assertFalse(restored.state.value.connected)
                assertFalse(restored.state.value.connecting)
                assertNull(restored.state.value.message)
                assertNull(store().read().pending)
                assertNull(store().read().tokens)
            }
            assertEquals(0, server.requestCount)
        }
    }
}
