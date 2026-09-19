@file:Suppress("DEPRECATION")

package com.streamvault.feature.catalog.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged

/**
 * Prevents a parent scrollable (e.g. LazyColumn) from micro-scrolling vertically
 * when D-pad focus moves between children of an inner horizontal row (e.g. LazyRow).
 *
 * Works by replacing the per-card BringIntoView rect with the full height of this
 * container, so the parent only scrolls to ensure the whole row section is visible.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.suppressParentVerticalScroll(): Modifier = composed {
    var height by remember { mutableFloatStateOf(0f) }

    this
        .onSizeChanged { height = it.height.toFloat() }
        .bringIntoViewResponder(object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect): Rect =
                Rect(localRect.left, 0f, localRect.right, height)

            override suspend fun bringChildIntoView(localRect: () -> Rect?) {
                // No-op: horizontal scrolling is handled by the inner LazyRow.
            }
        })
}

