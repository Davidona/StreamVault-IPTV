package com.streamvault.feature.settings.presentation

/** Formats a backup timestamp using the platform's locale-aware short date/time styles. */
public fun formatBackupTimestamp(lastModifiedMs: Long, unknownDate: String): String {
    return if (lastModifiedMs > 0L) {
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.SHORT,
            java.text.DateFormat.SHORT,
        ).format(java.util.Date(lastModifiedMs))
    } else {
        unknownDate
    }
}
