package com.streamvault.domain.model

enum class AppTheme(val storageValue: String) {
    CLASSIC_BLUE("classic_blue"),
    M3_PURPLE("m3_purple"),
    LIGHT("light");

    companion object {
        val DEFAULT: AppTheme = CLASSIC_BLUE

        fun fromStorage(value: String?): AppTheme {
            val normalized = value?.trim().orEmpty()
            return entries.firstOrNull { theme ->
                theme.storageValue.equals(normalized, ignoreCase = true)
            } ?: DEFAULT
        }
    }
}
