package com.streamvault.domain.model

enum class LiveClockPosition(val storageValue: String) {
    TOP_START("top_start"),
    TOP_END("top_end"),
    BOTTOM_START("bottom_start"),
    BOTTOM_END("bottom_end");

    companion object {
        fun fromStorage(value: String?): LiveClockPosition =
            entries.firstOrNull { it.storageValue.equals(value?.trim(), ignoreCase = true) }
                ?: TOP_END
    }
}
