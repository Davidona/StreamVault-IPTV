package com.streamvault.data.stremio.account

import android.content.Context
import android.util.AtomicFile
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.streamvault.data.security.CredentialCrypto
import java.io.File

interface StremioAccountStore {
    fun read(): JsonObject
    fun write(value: JsonObject)
    fun clear()
}

/** Auth credentials, pending linking and the account-specific sync ledger never enter backups. */
class EncryptedStremioAccountStore(context: Context, private val crypto: CredentialCrypto,name:String="stremio_account") : StremioAccountStore {
    private val file = AtomicFile(File(context.noBackupFilesDir, name.also { require(it.matches(Regex("[a-z_]+"))) }))
    @Synchronized override fun read(): JsonObject = if (!file.baseFile.exists()) JsonObject() else
        JsonParser.parseString(crypto.decryptIfNeeded(file.openRead().bufferedReader().use { it.readText() })).asJsonObject

    @Synchronized override fun write(value: JsonObject) {
        val bytes = crypto.encryptIfNeeded(value.toString()).toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream)
        } catch (error: Exception) { file.failWrite(stream); throw error }
    }
    @Synchronized override fun clear() { file.delete() }
}
