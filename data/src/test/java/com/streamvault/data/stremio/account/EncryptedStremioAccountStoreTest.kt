package com.streamvault.data.stremio.account

import com.streamvault.data.security.CredentialCrypto
import java.io.File
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class EncryptedStremioAccountStoreTest {
    @Test fun sessionAndLedgerAreEncryptedOutsideBackupsAndFailedWritesPreserveTheOldSession() {
        var cryptoFails=false
        val crypto=object:CredentialCrypto {
            override fun encryptIfNeeded(value:String):String {
                if(cryptoFails) throw SecurityException()
                return Base64.getEncoder().encodeToString(value.toByteArray())
            }
            override fun decryptIfNeeded(value:String)=String(Base64.getDecoder().decode(value))
        }
        val context:android.content.Context=RuntimeEnvironment.getApplication()
        val store=EncryptedStremioAccountStore(context,crypto)
        store.write(jsonObject("authKey" to "private-key","baseline" to jsonObject("tt123" to "fingerprint")))
        val file=File(context.noBackupFilesDir,"stremio_account")
        assertFalse(file.readText().contains("private-key"))
        assertEquals("private-key",EncryptedStremioAccountStore(context,crypto).read().text("authKey"))
        cryptoFails=true
        try { store.write(jsonObject("authKey" to "replacement"));fail("Expected crypto failure") } catch(_:SecurityException) { }
        assertEquals("private-key",store.read().text("authKey"))
        store.clear();assertFalse(file.exists())
    }
}
