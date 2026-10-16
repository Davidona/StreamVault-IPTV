package com.streamvault.feature.catalog.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.MaterialTheme
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvIconButton
import com.streamvault.feature.catalog.R

@Composable
internal fun TraktRatingActionButton(
    hasIdentity: Boolean,
    connected: Boolean,
    busy: Boolean,
    rating: Int?,
    onClick: () -> Unit,
) {
    if (!hasIdentity) return
    TvIconButton(
        onClick = onClick,
        enabled = connected && !busy,
        colors = ButtonDefaults.colors(
            containerColor = if (rating != null) AppColors.Brand else AppColors.SurfaceEmphasis,
            contentColor = if (rating != null) Color.White else AppColors.TextSecondary,
        ),
    ) {
        Icon(
            imageVector = if (rating != null) Icons.Filled.Star else Icons.Outlined.StarOutline,
            contentDescription = stringResource(
                if (rating == null) R.string.trakt_rating_action else R.string.trakt_rating_current,
                *if (rating == null) emptyArray<Any>() else arrayOf<Any>(rating),
            ),
        )
    }
}

@Composable
internal fun TraktRatingDialog(
    currentRating: Int?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            colors = SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated),
        ) {
            Column(
                modifier = Modifier.widthIn(min = 320.dp, max = 520.dp).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.trakt_rating_action), color = AppColors.TextPrimary)
                Text(stringResource(R.string.trakt_rating_dialog_message), color = AppColors.TextSecondary)
                (0..1).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (1..5).forEach { column ->
                            val value = row * 5 + column
                            TvButton(
                                onClick = { onSelect(value) },
                                enabled = !busy,
                                colors = ButtonDefaults.colors(
                                    containerColor = if (currentRating == value) AppColors.Brand else AppColors.SurfaceEmphasis,
                                    contentColor = if (currentRating == value) Color.White else AppColors.TextPrimary,
                                ),
                            ) {
                                Text(value.toString())
                            }
                        }
                    }
                }
                currentRating?.let { rating ->
                    Text(
                        stringResource(R.string.trakt_rating_current, rating),
                        color = AppColors.TextSecondary,
                    )
                }
                TvButton(onClick = onDismiss, enabled = !busy) {
                    Text(stringResource(R.string.settings_cancel))
                }
            }
        }
    }
}

@Composable
internal fun TraktRatingSummary(rating: Int?) {
    if (rating == null) return
    Text(
        text = stringResource(R.string.trakt_rating_current, rating),
        style = MaterialTheme.typography.bodyMedium,
        color = AppColors.Brand
    )
}
