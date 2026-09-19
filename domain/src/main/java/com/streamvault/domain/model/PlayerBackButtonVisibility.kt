package com.streamvault.domain.model

enum class PlayerBackButtonVisibility(val storageValue: String) {
    ALWAYS("always"),
    WITH_CONTROLS("with_controls"),
    HIDDEN("hidden");

    companion object {
        val DEFAULT: PlayerBackButtonVisibility = WITH_CONTROLS

        fun fromStorage(value: String?): PlayerBackButtonVisibility {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { visibility ->
                visibility.storageValue.equals(normalized, ignoreCase = true)
            } ?: DEFAULT
        }
    }
}
