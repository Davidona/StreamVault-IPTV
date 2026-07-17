package com.streamvault.domain.model

enum class VodDuplicateHandlingMode(val storageValue: String) {
    SHOW_ALL("show_all"),
    GROUPED("grouped"),
    SMART("smart");

    companion object {
        fun fromStorage(value: String?): VodDuplicateHandlingMode =
            entries.firstOrNull { it.storageValue.equals(value, ignoreCase = true) } ?: GROUPED
    }
}
