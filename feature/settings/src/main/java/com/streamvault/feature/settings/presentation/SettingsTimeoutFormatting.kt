package com.streamvault.feature.settings.presentation

import android.content.Context
import com.streamvault.feature.settings.R

fun formatTimeoutSecondsLabel(seconds: Int, context: Context): String {
    return context.resources.getQuantityString(
        R.plurals.settings_timeout_seconds,
        seconds,
        seconds
    )
}
