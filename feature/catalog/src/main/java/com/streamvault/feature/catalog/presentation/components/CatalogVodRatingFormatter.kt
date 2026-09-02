package com.streamvault.feature.catalog.presentation.components

import java.util.Locale

fun formatVodRatingLabel(rating: Float): String {
    val normalizedRating = rating.coerceAtLeast(0f)
    val scale = if (normalizedRating <= 5f) 5 else 10
    val valueText = formatVodRatingValue(normalizedRating)
    return "\u2605 $valueText/$scale"
}

private fun formatVodRatingValue(rating: Float): String {
    val formatted = String.format(Locale.US, "%.1f", rating)
    return formatted.removeSuffix(".0")
}

