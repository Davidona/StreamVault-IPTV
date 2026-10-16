package com.streamvault.feature.catalog.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.streamvault.feature.catalog.R

@Composable
internal fun TraktWatchlistActionButton(
    hasIdentity: Boolean,
    connected: Boolean,
    loaded: Boolean,
    watchlisted: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    if (!hasIdentity) return
    val actionLabel = when {
        !connected -> R.string.trakt_watchlist_connect
        !loaded -> R.string.trakt_watchlist_load
        watchlisted -> R.string.trakt_watchlist_remove
        else -> R.string.trakt_watchlist_add
    }
    DetailActionButton(
        label = stringResource(actionLabel),
        onClick = onClick,
        enabled = connected && !busy,
        compact = true,
        icon = if (watchlisted) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
    )
}
