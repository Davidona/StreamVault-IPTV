package com.streamvault.domain.model

enum class AppTextSizeMode { DEFAULT, SYSTEM, CUSTOM }

data class AppTextSize(
    val mode: AppTextSizeMode = AppTextSizeMode.DEFAULT,
    val customScale: Float = 1f,
) {
    fun resolvedScale(systemScale: Float): Float = when (mode) {
        AppTextSizeMode.DEFAULT -> 1f
        AppTextSizeMode.SYSTEM -> systemScale
        AppTextSizeMode.CUSTOM -> normalizedCustomScale
    }

    val normalizedCustomScale: Float
        get() = if (customScale.isFinite()) customScale.coerceIn(0.8f, 1.5f) else 1f

    fun toStorage(): String = "${mode.name}:$normalizedCustomScale"

    companion object {
        fun fromStorage(value: String?): AppTextSize {
            val parts = value?.split(":") ?: return AppTextSize()
            val mode = AppTextSizeMode.entries.firstOrNull { it.name == parts.firstOrNull() }
                ?: return AppTextSize()
            val scale = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
            return AppTextSize(mode, scale).let { it.copy(customScale = it.normalizedCustomScale) }
        }
    }
}
