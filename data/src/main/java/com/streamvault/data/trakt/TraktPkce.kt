package com.streamvault.data.trakt

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object TraktPkce {
    private val random = SecureRandom()
    fun randomValue(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also(random::nextBytes))
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
