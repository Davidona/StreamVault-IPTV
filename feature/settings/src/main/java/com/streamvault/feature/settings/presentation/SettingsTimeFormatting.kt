package com.streamvault.feature.settings.presentation

import com.streamvault.domain.model.AppTimeFormat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal fun AppTimeFormat.createSettingsTimeFormat(locale: Locale = Locale.getDefault()): DateFormat = when (this) {
    AppTimeFormat.SYSTEM -> DateFormat.getTimeInstance(DateFormat.SHORT, locale)
    AppTimeFormat.TWELVE_HOUR -> SimpleDateFormat("h:mm a", locale)
    AppTimeFormat.TWENTY_FOUR_HOUR -> SimpleDateFormat("HH:mm", locale)
}

internal fun AppTimeFormat.createSettingsDateTimeFormat(locale: Locale = Locale.getDefault()): DateFormat = when (this) {
    AppTimeFormat.SYSTEM -> DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
    AppTimeFormat.TWELVE_HOUR -> SimpleDateFormat("MMM d, h:mm a", locale)
    AppTimeFormat.TWENTY_FOUR_HOUR -> SimpleDateFormat("MMM d, HH:mm", locale)
}

internal fun AppTimeFormat.createSettingsTimeFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter = when (this) {
    AppTimeFormat.SYSTEM -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    AppTimeFormat.TWELVE_HOUR -> DateTimeFormatter.ofPattern("h:mm a", locale)
    AppTimeFormat.TWENTY_FOUR_HOUR -> DateTimeFormatter.ofPattern("HH:mm", locale)
}
