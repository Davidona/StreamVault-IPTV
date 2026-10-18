package com.streamvault.player.playback

import java.security.MessageDigest

// Reused for media-source and preload identity fingerprints.
private val sha256: MessageDigest = MessageDigest.getInstance("SHA-256")

/** SHA-256 fingerprint truncated to 16 hex chars — stable across JVM restarts. */
internal fun stableHash(input: String): String {
    sha256.reset()
    val digest = sha256.digest(input.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
}
