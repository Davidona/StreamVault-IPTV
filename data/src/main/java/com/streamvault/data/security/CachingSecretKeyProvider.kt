package com.streamvault.data.security

import javax.crypto.SecretKey

internal class CachingSecretKeyProvider(
    private val loadOrCreate: () -> SecretKey
) {
    @Volatile
    private var cached: SecretKey? = null

    fun get(): SecretKey {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: loadOrCreate().also { cached = it }
        }
    }
}
