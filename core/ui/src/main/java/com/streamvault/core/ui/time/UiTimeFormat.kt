package com.streamvault.core.ui.time

import androidx.compose.runtime.compositionLocalOf
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

enum class UiTimeFormat {
    SYSTEM,
    TWELVE_HOUR,
    TWENTY_FOUR_HOUR
}

val LocalUiTimeFormat = compositionLocalOf { UiTimeFormat.SYSTEM }

fun UiTimeFormat.createTimeFormat(locale: Locale = Locale.getDefault()): DateFormat = when (this) {
    UiTimeFormat.SYSTEM -> DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    UiTimeFormat.TWELVE_HOUR -> SimpleDateFormat("h:mm a", locale)
    UiTimeFormat.TWENTY_FOUR_HOUR -> SimpleDateFormat("HH:mm", locale)
}

fun UiTimeFormat.createDateTimeFormat(locale: Locale = Locale.getDefault()): DateFormat = when (this) {
    UiTimeFormat.SYSTEM -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
    UiTimeFormat.TWELVE_HOUR -> SimpleDateFormat("MMM d, h:mm a", locale)
    UiTimeFormat.TWENTY_FOUR_HOUR -> SimpleDateFormat("MMM d, HH:mm", locale)
}

fun UiTimeFormat.createTimeFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter = when (this) {
    UiTimeFormat.SYSTEM -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    UiTimeFormat.TWELVE_HOUR -> DateTimeFormatter.ofPattern("h:mm a", locale)
    UiTimeFormat.TWENTY_FOUR_HOUR -> DateTimeFormatter.ofPattern("HH:mm", locale)
}
