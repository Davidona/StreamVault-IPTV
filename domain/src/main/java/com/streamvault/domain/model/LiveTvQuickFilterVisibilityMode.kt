package com.streamvault.domain.model

enum class LiveTvQuickFilterVisibilityMode(val storageValue: String) {
    HIDE("hide"),
    SHOW_WHEN_FILTERS_AVAILABLE("available"),
    ALWAYS_VISIBLE("always");

    companion object {
        fun fromStorage(value: String?): LiveTvQuickFilterVisibilityMode =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) }
                ?: ALWAYS_VISIBLE
    }
}
