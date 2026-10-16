package com.streamvault.core.ui.components.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.interaction.TvButton

@Composable
fun ExtensionsSectionTabs(
    selectedIndex: Int,
    pluginsLabel: String,
    stremioAddonsLabel: String,
    onTabSelected: (Int) -> Unit,
) {
    val selectedTabFocus = remember { FocusRequester() }
    var needsEntryFocus by rememberSaveable { mutableStateOf(true) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(pluginsLabel, stremioAddonsLabel).forEachIndexed { index, label ->
            val active = selectedIndex == index
            val shape = RoundedCornerShape(8.dp)
            TvButton(
                onClick = { if (!active) onTabSelected(index) },
                modifier = Modifier.heightIn(min = 40.dp)
                    .then(if (active) Modifier.focusRequester(selectedTabFocus) else Modifier)
                    .semantics {
                    selected = active
                    role = Role.Tab
                },
                scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                shape = ButtonDefaults.shape(shape),
                border = ButtonDefaults.border(
                    border = if (active) Border(BorderStroke(1.dp, AppColors.Brand), shape = shape) else Border.None,
                    focusedBorder = Border(BorderStroke(2.dp, AppColors.TextPrimary), shape = shape)
                ),
                colors = ButtonDefaults.colors(
                    containerColor = if (active) AppColors.BrandMuted else AppColors.Surface,
                    contentColor = if (active) AppColors.TextPrimary else AppColors.TextSecondary,
                    focusedContainerColor = if (active) AppColors.BrandMuted else AppColors.SurfaceEmphasis,
                    focusedContentColor = AppColors.TextPrimary
                )
            ) {
                Text(label, modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.titleSmall)
            }
        }
    }
    LaunchedEffect(selectedIndex) {
        if (needsEntryFocus) {
            selectedTabFocus.requestFocus()
            needsEntryFocus = false
        }
    }
}
