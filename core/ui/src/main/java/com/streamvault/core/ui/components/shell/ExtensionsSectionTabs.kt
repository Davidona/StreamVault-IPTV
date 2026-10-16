package com.streamvault.core.ui.components.shell

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.BrowseShapes
import com.streamvault.core.ui.design.BrowseFocusSpec
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
            val shape = BrowseShapes.Control
            TvButton(
                onClick = { if (!active) onTabSelected(index) },
                modifier = Modifier.heightIn(min = 44.dp)
                    .then(if (active) Modifier.focusRequester(selectedTabFocus) else Modifier)
                    .semantics {
                    selected = active
                    role = Role.Tab
                },
                shape = ButtonDefaults.shape(shape),
                border = ButtonDefaults.border(
                    border = if (active) Border(BorderStroke(1.dp, BrowseColors.Brand.copy(alpha = 0.7f)), shape = shape) else Border.None,
                    focusedBorder = Border(BorderStroke(BrowseFocusSpec.BorderWidth, BrowseColors.Focus), shape = shape)
                ),
                colors = ButtonDefaults.colors(
                    containerColor = if (active) BrowseColors.BrandMuted else BrowseColors.Surface,
                    contentColor = if (active) BrowseColors.TextPrimary else BrowseColors.TextSecondary,
                    focusedContainerColor = if (active) BrowseColors.BrandMuted else BrowseColors.SurfaceEmphasis,
                    focusedContentColor = BrowseColors.TextPrimary
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
