package com.streamvault.domain.model

enum class LiveClockSize(val storageValue: String) {
    SMALL("small"),
    MEDIUM("medium"),
    LARGE("large");

    companion object {
        fun fromStorage(value: String?): LiveClockSize =
            entries.firstOrNull { it.storageValue.equals(value?.trim(), ignoreCase = true) }
                ?: MEDIUM
    }
}
