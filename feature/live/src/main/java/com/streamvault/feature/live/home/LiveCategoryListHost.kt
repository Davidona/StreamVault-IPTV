package com.streamvault.feature.live.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.streamvault.domain.model.Category

@Composable
fun LiveCategoryListHost(
    categories: List<Category>,
    categoryFocusRequesters: MutableMap<Long, FocusRequester>,
    itemContent: @Composable (category: Category, focusRequester: FocusRequester) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(
            items = categories,
            key = { it.id },
            contentType = { "live_category" }
        ) { category ->
            val focusRequester = categoryFocusRequesters.getOrPut(category.id) { FocusRequester() }
            itemContent(category, focusRequester)
        }
    }
}
