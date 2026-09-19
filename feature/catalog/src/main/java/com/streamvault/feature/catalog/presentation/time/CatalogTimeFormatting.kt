package com.streamvault.feature.catalog.presentation.time

import androidx.compose.runtime.compositionLocalOf
import com.streamvault.core.ui.time.UiTimeFormat
import com.streamvault.core.ui.time.createDateTimeFormat
import com.streamvault.domain.model.AppTimeFormat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/** Catalog's millisecond position formatter; negative positions render as zero. */
fun formatCatalogPositionMs(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** Compatibility alias used by moved detail screens. */
fun formatPositionMs(positionMs: Long): String = formatCatalogPositionMs(positionMs)

/** Catalog consumes the Core UI time contract rather than an app-owned CompositionLocal. */
val LocalCatalogTimeFormat = compositionLocalOf { UiTimeFormat.SYSTEM }

fun UiTimeFormat.createCatalogDateTimeFormat(locale: Locale = Locale.getDefault()): DateFormat =
    createDateTimeFormat(locale)

fun UiTimeFormat.createCatalogTimeFormat(locale: Locale = Locale.getDefault()): DateFormat = when (this) {
    UiTimeFormat.SYSTEM -> DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    UiTimeFormat.TWELVE_HOUR -> SimpleDateFormat("h:mm a", locale)
    UiTimeFormat.TWENTY_FOUR_HOUR -> SimpleDateFormat("HH:mm", locale)
}

fun UiTimeFormat.createCatalogTimeFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter = when (this) {
    UiTimeFormat.SYSTEM -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    UiTimeFormat.TWELVE_HOUR -> DateTimeFormatter.ofPattern("h:mm a", locale)
    UiTimeFormat.TWENTY_FOUR_HOUR -> DateTimeFormatter.ofPattern("HH:mm", locale)
}
