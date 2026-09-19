package com.streamvault.feature.playback.player

import com.streamvault.domain.model.AppTimeFormat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal fun formatLiveClock(
    timestampMs: Long,
    timeFormat: AppTimeFormat,
    locale: Locale = Locale.getDefault(),
    timeZone: TimeZone = TimeZone.getDefault()
): String {
    val formatter: DateFormat = when (timeFormat) {
        AppTimeFormat.SYSTEM -> DateFormat.getTimeInstance(DateFormat.SHORT, locale)
        AppTimeFormat.TWELVE_HOUR -> SimpleDateFormat("h:mm a", locale)
        AppTimeFormat.TWENTY_FOUR_HOUR -> SimpleDateFormat("HH:mm", locale)
    }
    formatter.timeZone = timeZone
    return formatter.format(Date(timestampMs))
}
