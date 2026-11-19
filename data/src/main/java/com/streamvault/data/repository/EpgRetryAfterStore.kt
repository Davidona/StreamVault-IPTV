package com.streamvault.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** Persists guide-specific server deadlines without storing credential-bearing URLs. */
@Singleton
class EpgRetryAfterStore internal constructor(private val preferences: SharedPreferences) {
    @Inject constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences("epg_retry_after", Context.MODE_PRIVATE)
    )

    private fun key(providerId: Long, url: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
        return "$providerId:" + digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun remainingMillis(providerId: Long, url: String, now: Long): Long =
        (preferences.getLong(key(providerId, url), 0L) - now).coerceAtLeast(0L)

    fun record(providerId: Long, url: String, now: Long, delayMillis: Long) {
        val deadline = now + delayMillis.coerceIn(0L, Long.MAX_VALUE - now)
        val key = key(providerId, url)
        val existing = preferences.getLong(key, 0L)
        if (deadline > existing) preferences.edit(commit = true) { putLong(key, deadline) }
    }

    fun clear(providerId: Long, url: String) {
        preferences.edit { remove(key(providerId, url)) }
    }

    fun clearProvider(providerId: Long) {
        val prefix = "$providerId:"
        preferences.edit {
            preferences.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }
    }
}
