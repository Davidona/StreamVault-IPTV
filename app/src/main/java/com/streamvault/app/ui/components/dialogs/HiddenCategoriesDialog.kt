package com.streamvault.app.ui.components.dialogs

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.PrimaryLight
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.Category

/**
 * Quick-action dialog that lists the currently-hidden Live categories and lets
 * the user restore them one by one (tap-immediate) or in bulk via "Unhide all".
 *
 * Each row is a full-width [TvClickableSurface] ג€” tapping it restores the
 * category immediately. Pattern mirrors David's `CategoryVisibilityCard` in
 * `ParentalControlGroupScreen` so the affordance is consistent across the app.
 *
 * Hosted by `HomeScreen` from the Live TV *Filtres rapides* block (M5). Backend
 * mutations are routed through `HomeViewModel.unhideCategory` /
 * `unhideAllLiveCategories`, which in turn call David's existing
 * `PreferencesRepository.setCategoryHidden` / `setHiddenCategoryIds` ג€” no schema
 * change, just a new entry point into the existing visibility plumbing.
 */
@Composable
fun HiddenCategoriesDialog(
    hiddenCategories: List<Category>,
    onUnhide: (Category) -> Unit,
    onUnhideAll: () -> Unit,
    onDismiss: () -> Unit
) {
    PremiumDialog(
        title = stringResource(R.string.hidden_categories_dialog_title),
        subtitle = stringResource(R.string.hidden_categories_dialog_subtitle),
        onDismissRequest = onDismiss,
        widthFraction = 0.55f,
        heightFraction = 0.95f,
        bodyHeightFraction = 0.85f,
        content = {
            TvClickableSurface(
                onClick = onUnhideAll,
                enabled = hiddenCategories.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = SurfaceElevated,
                    focusedContainerColor = SurfaceHighlight
                ),
                border = ClickableSurfaceDefaults.border(
                    focusedBorder = Border(
                        border = BorderStroke(2.dp, PrimaryLight),
                        shape = RoundedCornerShape(10.dp)
                    )
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.hidden_categories_dialog_unhide_all),
                        style = MaterialTheme.typography.labelMedium,
                        color = Primary
                    )
                }
            }
            hiddenCategories.forEach { category ->
                key(category.id) {
                    HiddenCategoryRow(
                        category = category,
                        onUnhide = { onUnhide(category) }
                    )
                }
            }
        },
        footer = {
            PremiumDialogFooterButton(
                label = stringResource(R.string.hidden_categories_dialog_close),
                onClick = onDismiss
            )
        }
    )
}

@Composable
private fun HiddenCategoryRow(
    category: Category,
    onUnhide: () -> Unit
) {
    TvClickableSurface(
        onClick = onUnhide,
        modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = SurfaceElevated.copy(alpha = 0.4f),
            focusedContainerColor = SurfaceHighlight
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, PrimaryLight),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = category.name,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurface,
                modifier = Modifier.weight(1f).padding(end = 12.dp)
            )
            if (category.count > 0) {
                Text(
                    text = category.count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}
