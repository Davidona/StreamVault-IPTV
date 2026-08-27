package com.streamvault.feature.settings.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.feature.settings.R

private data class SettingsNavEntry(
    val id: String,
    val label: String,
    val icon: String,
    val accent: Color
)

@Composable
public fun SettingsNavigationRail(
    selectedCategory: Int,
    focusRequester: FocusRequester,
    onCategorySelected: (Int) -> Unit
) {
    val entries = listOf(
        SettingsNavEntry(
            id = "providers",
            label = stringResource(R.string.settings_providers),
            icon = "P",
            accent = Primary
        ),
        SettingsNavEntry(
            id = "playback",
            label = stringResource(R.string.settings_playback),
            icon = ">",
            accent = Color(0xFF9E8FFF)
        ),
        SettingsNavEntry(
            id = "browsing",
            label = stringResource(R.string.settings_browsing),
            icon = "#",
            accent = Color(0xFF26A69A)
        ),
        SettingsNavEntry(
            id = "privacy",
            label = stringResource(R.string.settings_privacy),
            icon = "L",
            accent = Color(0xFFFFB74D)
        ),
        SettingsNavEntry(
            id = "recording",
            label = stringResource(R.string.settings_recording_title),
            icon = "R",
            accent = Color(0xFFEF5350)
        ),
        SettingsNavEntry(
            id = "backup_restore",
            label = stringResource(R.string.settings_backup_restore),
            icon = "B",
            accent = Color(0xFF42A5F5)
        ),
        SettingsNavEntry(
            id = "epg_sources",
            label = "EPG Sources",
            icon = "E",
            accent = Color(0xFF66BB6A)
        ),
        SettingsNavEntry(
            id = "about",
            label = stringResource(R.string.settings_about),
            icon = "i",
            accent = Color(0xFF78909C)
        )
    )

    LazyColumn(
        modifier = Modifier
            .width(236.dp)
            .fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.25f)),
        contentPadding = PaddingValues(top = 76.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        itemsIndexed(
            items = entries,
            key = { _, entry -> entry.id },
            contentType = { _, _ -> "settings_navigation_entry" }
        ) { index, entry ->
            SettingsNavItem(
                label = entry.label,
                badgeChar = entry.icon,
                accentColor = entry.accent,
                isSelected = selectedCategory == index,
                modifier = if (selectedCategory == index) Modifier.focusRequester(focusRequester) else Modifier,
                onClick = { onCategorySelected(index) }
            )
        }
    }
}

@Composable
public fun SettingsNavItem(
    label: String,
    badgeChar: String,
    accentColor: Color,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.11f) else Color.Transparent,
            focusedContainerColor = Primary.copy(alpha = 0.22f)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .mouseClickable(focusRequester = focusRequester, onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .background(
                        color = if (isSelected) Primary else Color.Transparent,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
            Box(
                Modifier
                    .size(28.dp)
                    .background(accentColor.copy(alpha = 0.18f), RoundedCornerShape(7.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = badgeChar,
                    style = MaterialTheme.typography.labelSmall,
                    color = accentColor,
                    fontWeight = FontWeight.Bold
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (isSelected) Primary else OnBackground,
                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
            )
        }
    }
}
