package com.streamvault.feature.catalog.presentation.vod

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import com.streamvault.core.ui.design.requestFocusSafely
import kotlinx.coroutines.delay

/** Restore focus once after entering a category, without interrupting later search or paging. */
@Composable
internal fun VodSelectedCategoryFocus(
    category: String?,
    contentReady: Boolean,
    focusRequester: FocusRequester
) {
    var focusApplied by remember(category) { mutableStateOf(false) }
    LaunchedEffect(category, contentReady) {
        if (category == null || !contentReady || focusApplied) return@LaunchedEffect
        delay(100)
        focusApplied = focusRequester.requestFocusSafely(
            tag = "VodSelectedCategoryFocus",
            target = "Selected category content"
        )
    }
}
