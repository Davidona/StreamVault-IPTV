package com.streamvault.feature.live.presentation.home

internal fun shouldCollapseLiveCategorySidebar(
    autoHideCategories: Boolean = false,
    isLocked: Boolean = false,
    isReorderMode: Boolean = false
): Boolean = autoHideCategories && !isLocked && !isReorderMode
