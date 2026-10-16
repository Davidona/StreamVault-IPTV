package com.streamvault.data.trakt

import android.content.Context
import android.util.AtomicFile
import com.google.gson.Gson
import com.streamvault.data.security.CredentialCrypto
import java.io.File

/** Separate from app backups; the verifier also survives browser-induced process death. */
class EncryptedTraktTokenStore(context: Context, private val crypto: CredentialCrypto) : TraktTokenStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, "trakt_session"))
    private val gson = Gson()

    @Synchronized override fun read(): TraktStoredSession {
        if (!file.baseFile.exists()) return TraktStoredSession()
        return gson.fromJson(crypto.decryptIfNeeded(file.openRead().bufferedReader().use { it.readText() }),
            TraktStoredSession::class.java) ?: TraktStoredSession()
    }

    @Synchronized override fun write(session: TraktStoredSession) {
        if (session.tokens == null && session.pending == null) {
            file.delete()
            return
        }
        val encrypted = crypto.encryptIfNeeded(gson.toJson(session))
        val stream = file.startWrite()
        try {
            stream.write(encrypted.toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (failure: Exception) {
            file.failWrite(stream)
            throw failure
        }
    }
}
