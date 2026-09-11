package com.streamvault.domain.model

enum class LiveClockFont(val storageValue: String) {
    CLEAN("clean"),
    DIGITAL_MONO("digital_mono"),
    CLASSIC_SERIF("classic_serif");

    companion object {
        fun fromStorage(value: String?): LiveClockFont =
            entries.firstOrNull { it.storageValue.equals(value?.trim(), ignoreCase = true) }
                ?: DIGITAL_MONO
    }
}
