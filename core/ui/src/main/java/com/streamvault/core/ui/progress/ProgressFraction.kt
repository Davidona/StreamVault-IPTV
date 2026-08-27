package com.streamvault.core.ui.progress

private val progressRatioRegex = Regex("(\\d+)\\s*/\\s*(\\d+)")

fun extractProgressFraction(message: String): Float? {
    val match = progressRatioRegex.find(message) ?: return null
    val current = match.groupValues[1].toIntOrNull() ?: return null
    val total = match.groupValues[2].toIntOrNull() ?: return null
    if (total <= 0) return null
    return (current.toFloat() / total.toFloat()).coerceIn(0f, 1f)
}
