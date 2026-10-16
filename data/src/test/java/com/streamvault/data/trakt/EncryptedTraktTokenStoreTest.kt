package com.streamvault.data.trakt

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.streamvault.data.security.CredentialCrypto
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
class EncryptedTraktTokenStoreTest {
    // Reversible encoding is only a test double, not production encryption.
    private class Crypto : CredentialCrypto {
        var fail = false
        override fun encryptIfNeeded(value: String): String {
            if (fail) throw SecurityException("Test encryption failure")
            return "enc:test:" + Base64.getEncoder().encodeToString(value.toByteArray())
        }
        override fun decryptIfNeeded(value: String) = String(Base64.getDecoder().decode(value.removePrefix("enc:test:")))
    }

    @Test fun `encrypted no backup record rotates tokens together and survives recreation`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val crypto = Crypto()
        val store = EncryptedTraktTokenStore(context, crypto)
        store.write(TraktStoredSession(pending = TraktPendingLogin("pending-verifier", "pending-state", 2000000)))
        val pending = EncryptedTraktTokenStore(context, crypto).read().pending!!
        assertEquals("pending-verifier", pending.verifier)
        assertEquals("pending-state", pending.state)
        assertEquals(2000000L, pending.createdAt)
        assertFalse(File(context.noBackupFilesDir, "trakt_session").readText().contains("pending-verifier"))
        store.write(TraktStoredSession(TraktTokens("old-access", "old-refresh", 2000000, 604800)))
        val file = File(context.noBackupFilesDir, "trakt_session")
        assertTrue(file.exists())
        assertFalse(file.readText().contains("old-access"))
        store.write(TraktStoredSession(TraktTokens("new-access", "new-refresh", 2000001, 604800)))
        val recreated = EncryptedTraktTokenStore(context, crypto).read()
        assertEquals("new-access", recreated.tokens!!.accessToken)
        assertEquals("new-refresh", recreated.tokens.refreshToken)
        crypto.fail = true
        try {
            store.write(TraktStoredSession(TraktTokens("failed-access", "failed-refresh", 2000002, 604800)))
            fail("Expected encryption failure")
        } catch (_: SecurityException) { }
        assertEquals("new-refresh", store.read().tokens!!.refreshToken)
        store.write(TraktStoredSession()) // Clearing works even if the Keystore is broken.
        assertFalse(file.exists())
        assertNull(store.read().tokens)
    }
}
