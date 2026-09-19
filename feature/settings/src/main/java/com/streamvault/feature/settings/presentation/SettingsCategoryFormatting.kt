package com.streamvault.feature.settings.presentation

import android.content.Context
import com.streamvault.domain.model.CategorySortMode
import com.streamvault.domain.model.ContentType
import com.streamvault.feature.settings.R

public fun formatQualityCapLabel(maxHeight: Int?, autoLabel: String): String =
    maxHeight?.let { "${it}p" } ?: autoLabel

public fun sortModeLabel(mode: CategorySortMode, context: Context): String = when (mode) {
    CategorySortMode.DEFAULT -> context.getString(R.string.settings_category_sort_default)
    CategorySortMode.TITLE_ASC -> context.getString(R.string.settings_category_sort_az)
    CategorySortMode.TITLE_DESC -> context.getString(R.string.settings_category_sort_za)
    CategorySortMode.COUNT_DESC -> context.getString(R.string.settings_category_sort_most_items)
    CategorySortMode.COUNT_ASC -> context.getString(R.string.settings_category_sort_least_items)
}

public fun formatCategorySortModeLabel(mode: CategorySortMode, context: Context): String =
    sortModeLabel(mode, context)

public fun categoryTypeLabel(type: ContentType, context: Context): String = when (type) {
    ContentType.LIVE -> context.getString(R.string.settings_category_sort_live)
    ContentType.MOVIE,
    ContentType.VOD -> context.getString(R.string.nav_vod)
    ContentType.SERIES,
    ContentType.SERIES_EPISODE -> context.getString(R.string.settings_category_sort_series)
}

public fun categoryTypeDescription(type: ContentType, context: Context): String = when (type) {
    ContentType.LIVE -> context.getString(R.string.settings_category_type_live_description)
    ContentType.MOVIE,
    ContentType.VOD -> context.getString(R.string.settings_category_type_movies_description)
    ContentType.SERIES,
    ContentType.SERIES_EPISODE -> context.getString(R.string.settings_category_type_series_description)
}
