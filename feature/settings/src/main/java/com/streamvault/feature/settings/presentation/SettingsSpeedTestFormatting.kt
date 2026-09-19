package com.streamvault.feature.settings.presentation

import android.content.Context
import com.streamvault.feature.settings.R
import java.text.DateFormat
import java.util.Locale

public fun formatSpeedTestValueLabel(speedTest: InternetSpeedTestUiModel): String =
    String.format(Locale.getDefault(), "%.1f Mbps", speedTest.megabitsPerSecond)

public fun formatSpeedTestSummary(
    speedTest: InternetSpeedTestUiModel,
    context: Context,
    dateTimeFormat: DateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
): String {
    val transportLabel = when (speedTest.transportLabel) {
        InternetSpeedTestTransport.WIFI.name -> context.getString(R.string.settings_speed_test_transport_wifi)
        InternetSpeedTestTransport.ETHERNET.name -> context.getString(R.string.settings_speed_test_transport_ethernet)
        InternetSpeedTestTransport.CELLULAR.name -> context.getString(R.string.settings_speed_test_transport_cellular)
        InternetSpeedTestTransport.OTHER.name -> context.getString(R.string.settings_speed_test_transport_other)
        else -> context.getString(R.string.settings_speed_test_transport_unknown)
    }
    val measuredAtLabel = formatTimestamp(speedTest.measuredAtMs, dateTimeFormat)
    return if (speedTest.isEstimated) {
        context.getString(R.string.settings_speed_test_summary_estimated, transportLabel, measuredAtLabel)
    } else {
        context.getString(R.string.settings_speed_test_summary_measured, transportLabel, measuredAtLabel)
    }
}
