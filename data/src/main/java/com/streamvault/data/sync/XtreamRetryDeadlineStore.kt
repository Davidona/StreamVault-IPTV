package com.streamvault.data.sync

import android.content.Context
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

interface XtreamRetryDeadlines {
    fun deadline(providerId: Long): Long
    fun extend(providerId: Long, deadline: Long)
    fun clear(providerId: Long)
}

/** Persists server-requested catalog cooldowns without provider credentials. */
@Singleton
class XtreamRetryDeadlineStore @Inject constructor(@ApplicationContext context: Context) : XtreamRetryDeadlines {
    private val preferences = context.getSharedPreferences("xtream_catalog_retry_after", Context.MODE_PRIVATE)

    override fun deadline(providerId: Long): Long = preferences.getLong(providerId.toString(), 0L)

    @Synchronized
    override fun extend(providerId: Long, deadline: Long) {
        if (providerId > 0L && deadline > this.deadline(providerId)) {
            preferences.edit(commit = true) { putLong(providerId.toString(), deadline) }
        }
    }

    @Synchronized
    override fun clear(providerId: Long) {
        preferences.edit { remove(providerId.toString()) }
    }
}
