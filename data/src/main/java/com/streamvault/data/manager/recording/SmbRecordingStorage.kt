package com.streamvault.data.manager.recording

import android.net.Uri
import com.streamvault.data.security.CredentialCrypto
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.util.Properties
import java.util.UUID
import jcifs.CIFSContext
import jcifs.context.BaseContext
import jcifs.config.PropertyConfiguration
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import jcifs.smb.SmbFileOutputStream
import org.json.JSONObject

class SmbRecordingCredentials internal constructor(
    internal val username: String,
    internal val password: String
)

internal object SmbRecordingStorage {
    private const val MIN_SMB_VERSION = "SMB202"
    private const val MAX_SMB_VERSION = "SMB311"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val RESPONSE_TIMEOUT_MS = 30_000
    private const val SOCKET_TIMEOUT_MS = 30_000

    fun normalizeDirectoryUrl(rawUrl: String): String {
        val value = rawUrl.trim()
        require(value.startsWith("smb://", ignoreCase = true)) {
            "Enter an SMB URL such as smb://server/share/recordings."
        }
        val parsed = runCatching { URI(value.replace(" ", "%20")) }.getOrElse {
            throw IllegalArgumentException("The SMB URL is invalid.", it)
        }
        require(parsed.scheme.equals("smb", ignoreCase = true) && !parsed.host.isNullOrBlank()) {
            "The SMB URL must include a server name and share."
        }
        require(parsed.rawUserInfo == null) {
            "Enter the username and password in their separate fields, not in the URL."
        }
        require(parsed.rawQuery == null && parsed.rawFragment == null) {
            "The SMB URL cannot include a query or fragment."
        }
        require(parsed.port == -1 || parsed.port in 1..65_535) {
            "The SMB server port is invalid."
        }

        val path = parsed.path.orEmpty()
        val segments = path.split('/').filter(String::isNotBlank)
        require(segments.isNotEmpty()) { "The SMB URL must include a share name." }
        require(segments.none { it == "." || it == ".." || '\\' in it }) {
            "The SMB path cannot contain relative path segments."
        }
        val normalizedPath = "/${segments.joinToString("/")}/"
        return URI("smb", null, parsed.host, parsed.port, normalizedPath, null, null).toASCIIString()
    }

    fun validateWritable(url: String, credentials: SmbRecordingCredentials) {
        val normalizedUrl = normalizeDirectoryUrl(url)
        withContext(credentials) { smbContext ->
            val directory = SmbFile(normalizedUrl, smbContext)
            try {
                if (!directory.exists() || !directory.isDirectory) {
                    throw IOException("The SMB path is unavailable or is not a folder.")
                }

                val probeUrl = normalizedUrl + ".streamvault-write-test-${UUID.randomUUID()}.tmp"
                val probe = SmbFile(probeUrl, smbContext)
                try {
                    SmbFileOutputStream(probe).use { it.write(0) }
                } finally {
                    runCatching { if (probe.exists()) probe.delete() }
                }
            } finally {
                runCatching { directory.close() }
            }
        }
    }

    fun findAvailableFileUrl(directoryUrl: String, fileName: String, credentials: SmbRecordingCredentials): String {
        val normalizedDirectory = normalizeDirectoryUrl(directoryUrl)
        return withContext(credentials) { smbContext ->
            val baseName = fileName.removeSuffix(".ts")
            var candidateName = fileName
            var counter = 1
            while (SmbFile(normalizedDirectory + Uri.encode(candidateName), smbContext).use { it.exists() }) {
                candidateName = "${baseName}_$counter.ts"
                counter++
                if (counter > 10_000) {
                    throw IOException("Could not choose a unique SMB recording file name.")
                }
            }
            normalizedDirectory + Uri.encode(candidateName)
        }
    }

    fun openOutputStream(url: String, credentials: SmbRecordingCredentials, append: Boolean): OutputStream {
        val context = newContext(credentials)
        try {
            val file = SmbFile(url, context)
            val smbOutput = SmbFileOutputStream(file, append)
            return BufferedOutputStream(object : FilterOutputStream(smbOutput) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        runCatching { context.close() }
                    }
                }
            })
        } catch (error: Throwable) {
            runCatching { context.close() }
            throw error
        }
    }

    fun openInputStream(url: String, credentials: SmbRecordingCredentials): InputStream {
        val context = newContext(credentials)
        try {
            val smbInput = SmbFile(url, context).inputStream
            return object : FilterInputStream(smbInput) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        runCatching { context.close() }
                    }
                }
            }
        } catch (error: Throwable) {
            runCatching { context.close() }
            throw error
        }
    }

    fun deleteFile(url: String, credentials: SmbRecordingCredentials) {
        withContext(credentials) { smbContext ->
            SmbFile(url, smbContext).use { file ->
                if (file.exists()) file.delete()
            }
        }
    }

    private inline fun <T> withContext(credentials: SmbRecordingCredentials, block: (CIFSContext) -> T): T {
        val context = newContext(credentials)
        return try {
            block(context)
        } finally {
            runCatching { context.close() }
        }
    }

    private fun newContext(credentials: SmbRecordingCredentials): CIFSContext {
        val properties = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", MIN_SMB_VERSION)
            setProperty("jcifs.smb.client.maxVersion", MAX_SMB_VERSION)
            setProperty("jcifs.smb.client.connTimeout", CONNECT_TIMEOUT_MS.toString())
            setProperty("jcifs.smb.client.responseTimeout", RESPONSE_TIMEOUT_MS.toString())
            setProperty("jcifs.smb.client.soTimeout", SOCKET_TIMEOUT_MS.toString())
        }
        val base = BaseContext(PropertyConfiguration(properties))
        return if (credentials.username.isBlank() && credentials.password.isBlank()) {
            base.withGuestCrendentials()
        } else {
            val domainSeparator = credentials.username.indexOf('\\')
            val domain = credentials.username.takeIf { domainSeparator > 0 }
                ?.substring(0, domainSeparator)
            val username = if (domainSeparator > 0) {
                credentials.username.substring(domainSeparator + 1)
            } else {
                credentials.username
            }
            base.withCredentials(
                NtlmPasswordAuthenticator(domain, username, credentials.password)
            )
        }
    }
}

internal fun SmbRecordingCredentials.encodeEncrypted(credentialCrypto: CredentialCrypto): String =
    credentialCrypto.encryptIfNeeded(
        JSONObject()
            .put("username", username)
            .put("password", password)
            .toString()
    )

internal fun decodeSmbCredentials(
    encrypted: String?,
    credentialCrypto: CredentialCrypto
): SmbRecordingCredentials? {
    if (encrypted.isNullOrBlank()) return null
    val json = JSONObject(credentialCrypto.decryptIfNeeded(encrypted))
    return SmbRecordingCredentials(
        username = json.optString("username"),
        password = json.optString("password")
    )
}
