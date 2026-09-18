package com.streamvault.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
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
}

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
    private val secretKeyProvider = CachingSecretKeyProvider(::loadOrCreateSecretKey)

    override fun encryptIfNeeded(value: String): String {
        if (value.isBlank() || value.startsWith(PREFIX)) return value

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKeyProvider.get())
            val iv = cipher.iv
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = iv + encrypted
            PREFIX + java.util.Base64.getEncoder().encodeToString(packed)
        } catch (e: Exception) {
            // Do NOT fall back to plaintext — rethrow so the caller can surface the failure.
            Log.e(TAG, "Keystore encryption failed. Credential will NOT be stored.", e)
            throw SecurityException("Failed to encrypt credential: ${e.message}", e)
        }
    }

    override fun decryptIfNeeded(value: String): String {
        if (!value.startsWith(PREFIX)) return value

        return try {
            val payload = value.removePrefix(PREFIX)
            val bytes = java.util.Base64.getDecoder().decode(payload)
            if (bytes.size <= IV_SIZE_BYTES) {
                throw CredentialDecryptionException(cause = IllegalArgumentException("Encrypted credential payload is truncated"))
            }

            val iv = bytes.copyOfRange(0, IV_SIZE_BYTES)
            val ciphertext = bytes.copyOfRange(IV_SIZE_BYTES, bytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKeyProvider.get(),
                GCMParameterSpec(AUTH_TAG_BITS, iv)
            )
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Keystore decryption failed. Stored credential is unreadable.", e)
            throw CredentialDecryptionException(cause = e)
        }
    }

    private fun loadOrCreateSecretKey(): SecretKey {
        return try {
            loadOrCreateHardwareSecretKey()
        } catch (e: Exception) {
            // Some devices (notably budget Android TV boxes) ship a broken Keymaster/TEE whose
            // hardware-backed Keystore cannot generate keys. Fall back to a software key so
            // credential persistence keeps working instead of crashing on those devices.
            Log.w(TAG, "Hardware-backed Keystore unavailable; falling back to a software key.", e)
            loadOrCreateSoftwareSecretKey()
        }
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
     * Best-effort software AES key persisted in app-private storage. This is only reached when the
     * hardware-backed Keystore is unusable, where a hardware-protected key is impossible by
     * definition. The key material lives in the app sandbox (invisible to other apps) and keeps
     * credentials encrypted at rest rather than storing them as plaintext.
     */
    private fun loadOrCreateSoftwareSecretKey(): SecretKey {
        val keyFile = File(context.filesDir, SOFTWARE_KEY_FILE_NAME)
        if (keyFile.exists()) {
            return SecretKeySpec(keyFile.readBytes(), SOFTWARE_KEY_ALGORITHM)
        }

        val keyGenerator = KeyGenerator.getInstance(SOFTWARE_KEY_ALGORITHM)
        keyGenerator.init(SOFTWARE_KEY_SIZE_BITS)
        val key = keyGenerator.generateKey()
        keyFile.writeBytes(key.encoded)
        return key
    }
}
