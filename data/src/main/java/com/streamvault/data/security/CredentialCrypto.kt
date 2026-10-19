package com.streamvault.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Contract for AES-GCM credential encryption backed by Android Keystore.
 * Expressed as an interface so implementations can be replaced with test fakes.
 */
interface CredentialCrypto {
    fun encryptIfNeeded(value: String): String
    fun decryptIfNeeded(value: String): String

    /** Reports whether encryption used the key selected as preferred for future reads. */
    fun encryptWithKeyMigration(value: String): CredentialEncryptionResult =
        CredentialEncryptionResult(encryptedValue = encryptIfNeeded(value))

    /** Indicates whether a successful fallback decrypt should be persisted with the preferred key. */
    fun decryptWithKeyMigration(value: String): CredentialDecryptionResult =
        CredentialDecryptionResult(cleartext = decryptIfNeeded(value))
}

data class CredentialEncryptionResult(
    val encryptedValue: String,
    val usesPreferredKey: Boolean = true
)

data class CredentialDecryptionResult(
    val cleartext: String,
    val requiresReencryption: Boolean = false
)

/**
 * Production implementation backed by the Android Keystore system.
 *
 * Values are persisted as: enc:v1:<base64(iv + ciphertext)>
 */
@Singleton
class AndroidKeystoreCredentialCrypto @Inject constructor(
    @ApplicationContext private val context: Context
) : CredentialCrypto {
    private val TAG = "CredentialCrypto"
    private val KEYSTORE_TYPE = "AndroidKeyStore"
    private val KEY_ALIAS = "streamvault_credentials"
    private val TRANSFORMATION = "AES/GCM/NoPadding"
    private val IV_SIZE_BYTES = 12
    private val AUTH_TAG_BITS = 128
    private val PREFIX = "enc:v1:"
    private val SOFTWARE_KEY_FILE_NAME = "streamvault_credential_software.key"
    private val SOFTWARE_KEY_ALGORITHM = "AES"
    private val SOFTWARE_KEY_SIZE_BITS = 256
    private val softwareKeyFile: File
        get() = File(context.filesDir, SOFTWARE_KEY_FILE_NAME)
    private val softwareKeyAtomicFile: AtomicFile
        get() = AtomicFile(softwareKeyFile)
    private val hardwareSecretKeyProvider = CachingSecretKeyProvider(::loadOrCreateHardwareSecretKey)
    private val softwareSecretKeyProvider = CachingSecretKeyProvider(::loadOrCreateSoftwareSecretKey)

    @Volatile
    private var preferSoftwareKey = false

    private enum class KeySource {
        HARDWARE,
        SOFTWARE
    }

    private data class SelectedKey(
        val secretKey: SecretKey,
        val source: KeySource
    )

    override fun encryptIfNeeded(value: String): String =
        encryptWithKeyMigration(value).encryptedValue

    override fun encryptWithKeyMigration(value: String): CredentialEncryptionResult {
        if (value.isBlank() || value.startsWith(PREFIX)) {
            return CredentialEncryptionResult(encryptedValue = value)
        }

        val selectedKey = try {
            selectPreferredKey()
        } catch (e: Exception) {
            throw encryptionFailure(e)
        }

        return try {
            CredentialEncryptionResult(encryptedValue = encryptWithKey(value, selectedKey.secretKey))
        } catch (primaryException: Exception) {
            // Either key can fail after it has loaded. Try the other available source before
            // failing; decryption will also try that source if it opens this ciphertext later.
            Log.w(
                TAG,
                "Preferred credential key encryption failed; retrying with an alternate key.",
                primaryException
            )
            try {
                when (selectedKey.source) {
                    KeySource.HARDWARE -> {
                        val softwareKey = softwareSecretKeyProvider.get()
                        preferSoftwareKey = true
                        CredentialEncryptionResult(
                            encryptedValue = encryptWithKey(value, softwareKey)
                        )
                    }
                    KeySource.SOFTWARE -> CredentialEncryptionResult(
                        encryptedValue = encryptWithKey(value, hardwareSecretKeyProvider.get()),
                        usesPreferredKey = false
                    )
                }
            } catch (fallbackException: Exception) {
                if (fallbackException !== primaryException) {
                    primaryException.addSuppressed(fallbackException)
                }
                throw encryptionFailure(primaryException)
            }
        }
    }

    override fun decryptIfNeeded(value: String): String = decryptWithKeyMigration(value).cleartext

    override fun decryptWithKeyMigration(value: String): CredentialDecryptionResult {
        if (!value.startsWith(PREFIX)) return CredentialDecryptionResult(cleartext = value)

        return try {
            val payload = value.removePrefix(PREFIX)
            val bytes = java.util.Base64.getDecoder().decode(payload)
            if (bytes.size <= IV_SIZE_BYTES) {
                throw CredentialDecryptionException(cause = IllegalArgumentException("Encrypted credential payload is truncated"))
            }

            val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
            val ciphertext = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)

            val selectedKey = selectPreferredKey()
            try {
                CredentialDecryptionResult(
                    cleartext = decryptWithKey(selectedKey.secretKey, iv, ciphertext)
                )
            } catch (primaryException: Exception) {
                val alternateKey = try {
                    when (selectedKey.source) {
                        KeySource.HARDWARE -> if (softwareKeyFile.exists()) {
                            softwareSecretKeyProvider.get()
                        } else {
                            null
                        }

                        KeySource.SOFTWARE -> loadExistingHardwareSecretKey()
                    }
                } catch (alternateKeyException: Exception) {
                    if (alternateKeyException !== primaryException) {
                        primaryException.addSuppressed(alternateKeyException)
                    }
                    null
                }

                if (alternateKey == null || alternateKey === selectedKey.secretKey) {
                    throw primaryException
                }

                try {
                    CredentialDecryptionResult(
                        cleartext = decryptWithKey(alternateKey, iv, ciphertext),
                        requiresReencryption = true
                    )
                } catch (alternateException: Exception) {
                    if (alternateException !== primaryException) {
                        primaryException.addSuppressed(alternateException)
                    }
                    throw primaryException
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Keystore decryption failed. Stored credential is unreadable.", e)
            throw CredentialDecryptionException(cause = e)
        }
    }

    @Synchronized
    private fun selectPreferredKey(): SelectedKey {
        val softwareFileExists = softwareKeyFile.exists()
        if (preferSoftwareKey || softwareFileExists) {
            try {
                val softwareKey = softwareSecretKeyProvider.get()
                preferSoftwareKey = true
                return SelectedKey(softwareKey, KeySource.SOFTWARE)
            } catch (softwareException: Exception) {
                if (softwareFileExists) {
                    // Preserve any hardware-encrypted credentials if the software key file was
                    // damaged or could not be read. Do not overwrite it with a new random key.
                    Log.w(
                        TAG,
                        "The existing software credential key is unavailable; trying hardware.",
                        softwareException
                    )
                }
                return try {
                    SelectedKey(hardwareSecretKeyProvider.get(), KeySource.HARDWARE)
                } catch (hardwareException: Exception) {
                    if (hardwareException !== softwareException) {
                        softwareException.addSuppressed(hardwareException)
                    }
                    throw softwareException
                }
            }
        }

        return try {
            val hardwareKey = hardwareSecretKeyProvider.get()
            if (preferSoftwareKey || softwareKeyFile.exists()) {
                preferSoftwareKey = true
                SelectedKey(softwareSecretKeyProvider.get(), KeySource.SOFTWARE)
            } else {
                SelectedKey(hardwareKey, KeySource.HARDWARE)
            }
        } catch (e: Exception) {
            // Some devices ship a broken Keystore that cannot load or generate hardware keys.
            // Keep using the app-private key if one is needed; its file also records that this
            // installation may already have credentials encrypted with that key.
            Log.w(TAG, "Hardware-backed Keystore unavailable; falling back to a software key.", e)
            preferSoftwareKey = true
            SelectedKey(softwareSecretKeyProvider.get(), KeySource.SOFTWARE)
        }
    }

    private fun encryptWithKey(value: String, secretKey: SecretKey): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val packed = iv + encrypted
        return PREFIX + java.util.Base64.getEncoder().encodeToString(packed)
    }

    private fun decryptWithKey(secretKey: SecretKey, iv: ByteArray, ciphertext: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            secretKey,
            GCMParameterSpec(AUTH_TAG_BITS, iv)
        )
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun encryptionFailure(cause: Exception): SecurityException {
        // Do NOT fall back to plaintext — surface the failure so the credential is never stored unencrypted.
        Log.e(TAG, "Credential encryption failed. Credential will NOT be stored.", cause)
        return SecurityException("Failed to encrypt credential: ${cause.message}", cause)
    }

    private fun loadExistingHardwareSecretKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun loadOrCreateHardwareSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_TYPE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()

        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    /**
     * Best-effort software AES key persisted in app-private storage. This is used when hardware key
     * creation or a hardware-backed cipher operation fails. Keeping the same file-backed key across
     * app updates lets the app continue reading credentials written while this fallback was active.
     * The key material stays in the app sandbox and credentials remain encrypted at rest.
     */
    private fun loadOrCreateSoftwareSecretKey(): SecretKey {
        val atomicFile = softwareKeyAtomicFile
        if (softwareKeyFile.exists()) {
            return readSoftwareSecretKey(atomicFile)
        }

        val keyGenerator = KeyGenerator.getInstance(SOFTWARE_KEY_ALGORITHM)
        keyGenerator.init(SOFTWARE_KEY_SIZE_BITS)
        val key = keyGenerator.generateKey()
        val output = atomicFile.startWrite()
        try {
            output.write(key.encoded)
            atomicFile.finishWrite(output)
        } catch (e: Exception) {
            runCatching { atomicFile.failWrite(output) }
                .onFailure { cleanupException -> e.addSuppressed(cleanupException) }
            throw e
        }
        // Read the committed bytes back so this provider caches exactly what survives a restart.
        return readSoftwareSecretKey(atomicFile)
    }

    private fun readSoftwareSecretKey(atomicFile: AtomicFile): SecretKey {
        val encoded = atomicFile.openRead().use { it.readBytes() }
        if (encoded.size != SOFTWARE_KEY_SIZE_BITS / 8) {
            throw IOException("Software credential key has an invalid length")
        }
        return SecretKeySpec(encoded, SOFTWARE_KEY_ALGORITHM)
    }
}
