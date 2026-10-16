package com.streamvault.domain.model

enum class AppTheme(val storageValue: String) {
    CLASSIC_BLUE("classic_blue"),
    M3_PURPLE("m3_purple"),
    LIGHT("light"),
    FOREST("forest"),
    SUNSET("sunset"),
    OCEAN("ocean"),
    ROSE("rose"),
    AMOLED_BLACK("amoled_black"),
    VINTAGE("vintage");

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
