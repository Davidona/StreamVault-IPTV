package com.streamvault.feature.catalog.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.design.BrowseColors
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
    DetailActionButton(
        label = stringResource(
            if (rating == null) R.string.trakt_rating_action else R.string.trakt_rating_current,
            *if (rating == null) emptyArray<Any>() else arrayOf<Any>(rating),
        ),
        onClick = onClick,
        enabled = connected && !busy,
        compact = true,
        icon = Icons.Filled.Star,
    )
}

@Composable
internal fun TraktRatingDialog(
    currentRating: Int?,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    PremiumDialog(
        title = stringResource(R.string.trakt_rating_action),
        subtitle = stringResource(R.string.trakt_rating_dialog_message),
        onDismissRequest = { if (!busy) onDismiss() },
        widthFraction = 0.42f,
        heightFraction = null,
        bodyHeightFraction = 0.36f,
        content = {
            currentRating?.let { rating ->
                Text(
                    text = stringResource(R.string.trakt_rating_current, rating),
                    color = BrowseColors.TextSecondary,
                )
            }
            (1..10).chunked(5).forEach { rowValues ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    rowValues.forEach { rating ->
                        PremiumDialogActionButton(
                            label = rating.toString(),
                            onClick = { onSelect(rating) },
                            modifier = Modifier.weight(1f),
                            enabled = !busy,
                            emphasized = currentRating == rating,
                        )
                    }
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss,
                enabled = !busy,
            )
        },
    )
}

@Composable
internal fun TraktRatingSummary(rating: Int?) {
    if (rating == null) return
    Text(
        text = stringResource(R.string.trakt_rating_current, rating),
        style = MaterialTheme.typography.labelMedium,
        color = BrowseColors.TextSecondary,
    )
}
