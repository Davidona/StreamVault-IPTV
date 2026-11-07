package com.streamvault.feature.live.phone

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Local credentials for one fixed source session. The caller must verify the parental PIN before construction. */
internal class PhoneSessionGuard(
    val providerId: Long,
    private val expectedHost: String,
    private val parentalLevel: Int,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val random: SecureRandom = SecureRandom(),
    val combinedProfileId: Long? = null
) {
    init {
        require((providerId > 0L) xor (combinedProfileId != null && combinedProfileId > 0L))
    }
    private val created = clockMillis()
    private var lastActivity = created
    private var stopped = false
    private var pairingSecret: String? = token()
    private var cookie: String? = null
    private var csrf: String? = null

    data class Credentials(val cookie: String, val csrf: String)

    @Synchronized fun pairingCode(): String? = if (valid() && clockMillis() - created < PAIRING_TTL) pairingSecret else null
    @Synchronized fun isActive(): Boolean = valid()

    /** Pairing is single-use and never grants a different parental policy or provider scope. */
    @Synchronized fun pair(host: String?, origin: String?, secret: String, currentParentalLevel: Int): Credentials? {
        if (!validPolicy(host, origin, currentParentalLevel) || clockMillis() - created >= PAIRING_TTL) return null
        val expected = pairingSecret ?: return null
        if (!secureEquals(expected, secret)) return null
        pairingSecret = null
        val credentials = Credentials(token(), token())
        cookie = credentials.cookie
        csrf = credentials.csrf
        lastActivity = clockMillis()
        return credentials
    }

    @Synchronized fun authorize(host: String?, origin: String?, suppliedCookie: String?, suppliedCsrf: String?, currentParentalLevel: Int): Boolean {
        if (!validPolicy(host, origin, currentParentalLevel)) return false
        val expectedCookie = cookie ?: return false
        val expectedCsrf = csrf ?: return false
        if (suppliedCookie == null || suppliedCsrf == null ||
            !secureEquals(expectedCookie, suppliedCookie) || !secureEquals(expectedCsrf, suppliedCsrf)) return false
        lastActivity = clockMillis()
        return true
    }

    @Synchronized fun stop() {
        stopped = true
        pairingSecret = null
        cookie = null
        csrf = null
    }

    private fun validPolicy(host: String?, origin: String?, currentParentalLevel: Int): Boolean =
        valid() && currentParentalLevel == parentalLevel && host == expectedHost && origin == "http://$expectedHost"

    private fun valid(): Boolean {
        val now = clockMillis()
        return !stopped && now >= created && now - created < HARD_TTL &&
            (parentalLevel == 0 || now - created < PARENTAL_TTL) &&
            now >= lastActivity && now - lastActivity < IDLE_TTL
    }

    private fun token(): String = ByteArray(32).also(random::nextBytes).let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it)
    }

    private fun secureEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    private companion object {
        const val PAIRING_TTL = 5 * 60_000L
        const val IDLE_TTL = 5 * 60_000L
        const val HARD_TTL = 30 * 60_000L
        const val PARENTAL_TTL = 5 * 60_000L
    }
}
