package com.streamvault.app.trakt

import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.streamvault.data.security.AndroidKeystoreCredentialCrypto
import com.streamvault.data.trakt.EncryptedTraktTokenStore
import com.streamvault.data.trakt.TraktApi
import com.streamvault.data.trakt.TraktAuthService
import com.streamvault.data.trakt.TraktConfig
import com.streamvault.data.trakt.TraktTokens
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real Android encrypted storage with synthetic OAuth responses; no real account changes. */
@RunWith(AndroidJUnit4::class)
class TraktAccountRecoveryInstrumentationTest {
    @Test
    fun recreatedLoginDenialCancellationAndLateCallbacks() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "trakt-recovery-${UUID.randomUUID()}")
        assertTrue(directory.mkdirs())
        val context = object : ContextWrapper(target) {
            override fun getNoBackupFilesDir(): File = directory
        }
        val config = TraktConfig("synthetic-client", "https://example.org/oauth/trakt/callback")
        val now = System.currentTimeMillis() / 1000
        var exchanges = 0
        val api = object : TraktApi {
            override suspend fun exchange(code: String, verifier: String): TraktTokens {
                exchanges++
                assertTrue(verifier.isNotBlank())
                return TraktTokens("synthetic-access", "synthetic-refresh", now, 3600)
            }
            override suspend fun refresh(refreshToken: String): TraktTokens = error("Unexpected refresh")
            override suspend fun username(accessToken: String): String = "synthetic-user"
        }
        fun store() = EncryptedTraktTokenStore(context, AndroidKeystoreCredentialCrypto(target))
        fun service() = TraktAuthService(config, store(), api)
        fun callback(state: String, query: String = "code=synthetic-code") =
            "${config.redirectUri}?state=$state&$query"
        try {
            // Pending state/verifier survive replacement of both service and disk-store instance.
            service().beginSignIn()
            val pending = requireNotNull(store().read().pending)
            val record = File(directory, "trakt_session").readText()
            assertTrue(record.startsWith("enc:v1:"))
            assertFalse(record.contains(pending.verifier))
            val recreated = service()
            recreated.load()
            assertTrue(recreated.state.value.connecting)
            recreated.completeSignIn(callback(pending.state))
            assertTrue(recreated.state.value.connected)
            assertEquals("synthetic-user", recreated.state.value.username)
            assertEquals(1, exchanges)
            recreated.disconnect()
            val disconnected = service()
            disconnected.load()
            assertFalse(disconnected.state.value.connected)
            disconnected.completeSignIn(callback(pending.state))
            assertFalse(disconnected.state.value.connected)
            assertEquals(1, exchanges)

            disconnected.beginSignIn()
            val deniedState = requireNotNull(store().read().pending).state
            disconnected.completeSignIn(callback(deniedState, "error=access_denied"))
            assertFalse(disconnected.state.value.connected)
            assertFalse(disconnected.state.value.connecting)
            assertTrue(disconnected.state.value.message == null)
            assertEquals(1, exchanges)

            disconnected.beginSignIn()
            val cancelledState = requireNotNull(store().read().pending).state
            disconnected.cancelSignIn()
            val afterCancel = service()
            afterCancel.load()
            afterCancel.completeSignIn(callback(cancelledState))
            assertFalse(afterCancel.state.value.connected)
            assertFalse(afterCancel.state.value.connecting)
            assertTrue(afterCancel.state.value.message == null)
            assertEquals(1, exchanges)
            assertFalse(File(directory, "trakt_session").exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
