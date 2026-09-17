package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.*
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvIconButton
import com.streamvault.core.ui.theme.*
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.design.AppColors

internal val SettingsCategory.icon: ImageVector get() = when (this) {
    SettingsCategory.SOURCES -> Icons.Rounded.Dns
    SettingsCategory.PLAYBACK -> Icons.Rounded.PlayCircle
    SettingsCategory.LIVE_TV -> Icons.Rounded.LiveTv
    SettingsCategory.MOVIES -> Icons.Rounded.Movie
    SettingsCategory.APP -> Icons.Rounded.Tune
    SettingsCategory.PRIVACY -> Icons.Rounded.Lock
    SettingsCategory.RECORDING -> Icons.Rounded.FiberManualRecord
    SettingsCategory.BACKUP -> Icons.Rounded.CloudUpload
    SettingsCategory.GUIDE -> Icons.Rounded.CalendarMonth
    SettingsCategory.ABOUT -> Icons.Rounded.Info
}

@Composable
public fun SettingsNavigationRail(
    selectedCategory: Int,
    focusRequester: FocusRequester,
    onCategorySelected: (Int) -> Unit,
    onBack: () -> Unit = {},
    onSearch: () -> Unit = {},
    searchModifier: Modifier = Modifier,
    compact: Boolean = LocalConfiguration.current.screenWidthDp < 600
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    var selectedEntryPlaced by remember(compact, selectedCategory) { mutableStateOf(false) }
    LaunchedEffect(compact, selectedCategory, selectedEntryPlaced) {
        if (compact && selectedEntryPlaced) focusRequester.requestFocus()
    }
    val entry: @Composable (SettingsCategory) -> Unit = { category ->
        val isSelected = selectedCategory == category.legacyId
        TvClickableSurface(
            onClick = { onCategorySelected(category.legacyId) },
            modifier = Modifier.fillMaxWidth()
                .then(if (isSelected) Modifier.focusRequester(focusRequester) else Modifier)
                .then(if (isSelected) Modifier.onGloballyPositioned { selectedEntryPlaced = true } else Modifier)
                .semantics { selected = isSelected },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(SettingsDesignTokens.groupRadius)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = if (isSelected) colors.focusedSurface else Color.Transparent,
                focusedContainerColor = colors.focusedSurface
            ),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(
                    androidx.compose.foundation.BorderStroke(SettingsDesignTokens.focusStroke, colors.focusOutline),
                    shape = RoundedCornerShape(SettingsDesignTokens.groupRadius))),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(category.icon, null, tint = if (isSelected) colors.accent else colors.secondaryText,
                    modifier = Modifier.size(20.dp))
                Text(stringResource(category.title), style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) colors.accent else colors.primaryText,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal)
            }
        }
    }
    if (compact) {
        Column(
            modifier = Modifier.fillMaxSize().background(colors.canvas).padding(
                start = SettingsDesignTokens.compactInset,
                top = SettingsDesignTokens.space12,
                end = SettingsDesignTokens.compactInset,
            )
        ) {
            SettingsLocalHeader(
                title = stringResource(R.string.settings_title),
                description = "",
                parentTitle = stringResource(R.string.settings_back_to_app),
                onBack = onBack,
                onSearch = onSearch,
                searchModifier = searchModifier,
            )
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(top = SettingsDesignTokens.space12, bottom = SettingsDesignTokens.space24),
                verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space4),
            ) {
                items(SettingsCategory.entries, key = { it.legacyId }) { entry(it) }
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.width(SettingsDesignTokens.railWidth).fillMaxHeight().background(colors.canvas),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = SettingsDesignTokens.space16),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space8),
                ) {
                    TvIconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.parental_group_back),
                            tint = colors.primaryText,
                        )
                    }
                    Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall,
                        color = colors.primaryText, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.semantics { heading() })
                    Spacer(Modifier.weight(1f))
                    TvIconButton(onClick = onSearch, modifier = searchModifier) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = stringResource(R.string.settings_search_action),
                            tint = colors.primaryText,
                        )
                    }
                }
            }
            items(SettingsCategory.entries, key = { it.legacyId }) { entry(it) }
        }
    }
}
