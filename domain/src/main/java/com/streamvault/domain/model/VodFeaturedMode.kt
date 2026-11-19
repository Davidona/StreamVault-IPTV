package com.streamvault.domain.model

enum class VodFeaturedMode {
    HIDDEN, LATEST_RELEASE, RECENTLY_ADDED, CONTINUE_WATCHING, RANDOM, FOR_YOU, UP_NEXT;
    companion object {
        fun fromStorage(value: String?, series: Boolean = false): VodFeaturedMode =
            entries.firstOrNull { it.name == value && (series || it != UP_NEXT) }
                ?: if (series) UP_NEXT else CONTINUE_WATCHING
    }
}
