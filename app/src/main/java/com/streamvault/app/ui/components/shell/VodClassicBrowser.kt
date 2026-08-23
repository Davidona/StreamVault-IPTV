package com.streamvault.app.ui.components.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.FocusedMarqueeText
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.FocusSpec
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvIconButton

data class VodClassicCategoryOption(
    val key: String,
    val label: String,
    val count: Int,
    val isSelected: Boolean,
    val onClick: () -> Unit,
    val onLongClick: (() -> Unit)? = null,
    val isLocked: Boolean = false
)

@Composable
fun VodClassicSplitLayout(
    railTitle: String,
    railSearchValue: String,
    onRailSearchValueChange: (String) -> Unit,
    railSearchPlaceholder: String,
    categories: List<VodClassicCategoryOption>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        CategoryRailPanel(
            title = railTitle,
            searchValue = railSearchValue,
            onSearchValueChange = onRailSearchValueChange,
            searchPlaceholder = railSearchPlaceholder,
            modifier = Modifier
                .width(320.dp)
                .fillMaxHeight(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            items(categories, key = { it.key }) { category ->
                var isFocused by remember(category.key) { mutableStateOf(false) }
                TvClickableSurface(
                    onClick = category.onClick,
                    onLongClick = category.onLongClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { isFocused = it.isFocused },
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (category.isSelected) AppColors.Brand.copy(alpha = 0.22f) else AppColors.SurfaceElevated,
                        focusedContainerColor = AppColors.SurfaceEmphasis,
                        contentColor = if (category.isSelected) AppColors.BrandStrong else AppColors.TextPrimary,
                        focusedContentColor = AppColors.TextPrimary
                    ),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(FocusSpec.BorderWidth, AppColors.Focus),
                            shape = RoundedCornerShape(14.dp)
                        )
                    ),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = FocusSpec.FocusedScale)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FocusedMarqueeText(
                            text = category.label,
                            isFocused = isFocused,
                            style = MaterialTheme.typography.titleSmall,
                            color = if (category.isSelected) AppColors.BrandStrong else AppColors.TextPrimary,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        if (category.isLocked) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.home_locked_short),
                                style = MaterialTheme.typography.labelSmall,
                                color = AppColors.BrandStrong
                            )
                        }
                        if (category.count > 0) {
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = category.count.toString(),
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSecondary
                            )
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(AppColors.Canvas, RoundedCornerShape(28.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            content()
        }
    }
}

@Composable
fun VodClassicContentHeader(
    title: String,
    subtitle: String,
    actions: List<VodActionChip>,
    selectedActionKey: String? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = AppColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { action ->
                VodClassicHeaderActionButton(
                    action = action,
                    isSelected = action.key == selectedActionKey
                )
            }
        }
    }
}

@Composable
private fun VodClassicHeaderActionButton(
    action: VodActionChip,
    isSelected: Boolean = false,
    modifier: Modifier = Modifier
) {
    TvClickableSurface(
        onClick = action.onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) AppColors.Brand.copy(alpha = 0.18f) else AppColors.SurfaceElevated,
            focusedContainerColor = AppColors.SurfaceEmphasis,
            contentColor = if (isSelected) AppColors.BrandStrong else AppColors.TextPrimary,
            focusedContentColor = AppColors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, AppColors.Focus),
                shape = RoundedCornerShape(18.dp)
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = action.label,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            action.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSelected) AppColors.Brand else AppColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
