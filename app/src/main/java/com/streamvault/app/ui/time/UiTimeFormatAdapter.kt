package com.streamvault.app.ui.time

import com.streamvault.core.ui.time.UiTimeFormat
import com.streamvault.domain.model.AppTimeFormat

fun AppTimeFormat.toUiTimeFormat(): UiTimeFormat = when (this) {
    AppTimeFormat.SYSTEM -> UiTimeFormat.SYSTEM
    AppTimeFormat.TWELVE_HOUR -> UiTimeFormat.TWELVE_HOUR
    AppTimeFormat.TWENTY_FOUR_HOUR -> UiTimeFormat.TWENTY_FOUR_HOUR
}
