package com.streamvault.app.trakt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.app.BuildConfig
import com.streamvault.data.security.AndroidKeystoreCredentialCrypto
import com.streamvault.data.trakt.EncryptedTraktTokenStore
import com.streamvault.data.trakt.TraktApi
import com.streamvault.data.trakt.TraktApiClient
import com.streamvault.data.trakt.TraktAuthService
import com.streamvault.data.trakt.TraktConfig
import com.streamvault.data.trakt.TraktTokens
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

/** Explicitly opt in on an isolated, signed-in device; never run in general CI. */
@RunWith(AndroidJUnit4::class)
class TraktLiveAccountInstrumentationTest {
    @Test
    fun rotatesRealTokenPairAndReloadsEncryptedSession() = runBlocking {
        assumeTrue("Requires explicit live_trakt opt-in",
            InstrumentationRegistry.getArguments().getString("live_trakt") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = TraktConfig(BuildConfig.TRAKT_CLIENT_ID, BuildConfig.TRAKT_REDIRECT_URI)
        assertTrue("Trakt must be configured", config.configured)
        fun store() = EncryptedTraktTokenStore(context, AndroidKeystoreCredentialCrypto(context))
        val before = store().read().tokens
        assertTrue("Sign in on the isolated device first", before != null)
        val original = requireNotNull(before)
        val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
        val productionApi = TraktApiClient(config, client,
            userAgent = "StreamVault/${BuildConfig.VERSION_NAME}")
        var refreshCount = 0
        val countedApi = object : TraktApi by productionApi {
            override suspend fun refresh(refreshToken: String): TraktTokens {
                refreshCount++
                return productionApi.refresh(refreshToken)
            }
        }
        // Expire only the old pair in memory. Production persists/rotates the real pair.
        val service = TraktAuthService(config, store(), countedApi,
            now = { original.createdAt + original.expiresIn })
        service.load()
        assertEquals("Exactly one refresh is allowed", 1, refreshCount)
        assertTrue("Account must remain connected", service.state.value.connected)
        assertTrue("Profile must load", !service.state.value.username.isNullOrBlank())
        assertTrue("No account error expected", service.state.value.message == null)
        val after = store().read().tokens
        assertTrue("New pair must be persisted", after != null)
        val rotated = requireNotNull(after)
        // Boolean assertions ensure failure output cannot include either credential.
        assertTrue("Access token must rotate", original.accessToken != rotated.accessToken)
        assertTrue("Refresh token must rotate", original.refreshToken != rotated.refreshToken)
        assertTrue("Record must be encrypted",
            File(context.noBackupFilesDir, "trakt_session").readText().startsWith("enc:v1:"))
        val reloaded = TraktAuthService(config, store(), countedApi)
        reloaded.load()
        assertTrue("Reloaded account must remain connected", reloaded.state.value.connected)
        assertTrue("Reloaded profile must match", reloaded.state.value.username == service.state.value.username)
        assertTrue("Reload must have no error", reloaded.state.value.message == null)
        assertEquals("Reload must reuse the new pair", 1, refreshCount)
    }
}
