package com.streamvault.feature.settings.presentation

import java.text.DateFormat
import java.util.Locale

public fun formatBytes(bytes: Long): String {
    val kb = 1024.0
    val mb = kb * 1024.0
    val gb = mb * 1024.0
    return when {
        bytes >= gb -> String.format(Locale.getDefault(), "%.1f GB", bytes / gb)
        bytes >= mb -> String.format(Locale.getDefault(), "%.1f MB", bytes / mb)
        bytes >= kb -> String.format(Locale.getDefault(), "%.1f KB", bytes / kb)
        else -> "$bytes B"
    }
}

public fun formatTimestamp(
    timestampMs: Long,
    dateTimeFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
): String {
    if (timestampMs <= 0L) return "--:--"
    return dateTimeFormat.format(java.util.Date(timestampMs))
}

public fun formatPlaybackSpeedLabel(speed: Float): String {
    return if (speed % 1f == 0f) {
        "${speed.toInt()}x"
    } else {
        "${("%.2f".format(Locale.US, speed)).trimEnd('0').trimEnd('.')}x"
    }
}

public fun playerTimeoutOptions(): List<Int> = listOf(2, 3, 4, 5, 6, 8, 10, 15, 20, 30)
