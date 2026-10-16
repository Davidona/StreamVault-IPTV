package com.streamvault.feature.catalog.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.interaction.TvIconButton
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
        !connected -> stringResource(R.string.trakt_watchlist_connect)
        !loaded -> stringResource(R.string.trakt_watchlist_load)
        watchlisted -> stringResource(R.string.trakt_watchlist_remove)
        else -> stringResource(R.string.trakt_watchlist_add)
    }
    TvIconButton(
        onClick = onClick,
        enabled = connected && !busy,
        colors = ButtonDefaults.colors(
            containerColor = if (watchlisted) AppColors.Brand else AppColors.SurfaceEmphasis,
            contentColor = if (watchlisted) androidx.compose.ui.graphics.Color.White else AppColors.TextSecondary,
        ),
    ) {
        Icon(
            imageVector = if (watchlisted) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
            contentDescription = actionLabel,
        )
    }
}
