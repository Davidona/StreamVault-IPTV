package com.streamvault.core.ui.components.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import com.streamvault.core.ui.R
import com.streamvault.core.ui.design.BrowseColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PortraitNavigation(
    currentDestinationId: String,
    destinations: List<UiDestination>,
    onDestinationSelected: (String) -> Unit,
    actions: (@Composable RowScope.() -> Unit)?
) {
    var menuVisible by rememberSaveable { mutableStateOf(false) }
    val primary = destinations.take(4)
    val active = destinations.filter { currentDestinationId.startsWith(it.id) }.maxByOrNull { it.id.length }
    val menuLabel = stringResource(R.string.navigation_menu)
    NavigationBar(containerColor = BrowseColors.Surface, tonalElevation = 0.dp, windowInsets = WindowInsets(0)) {
        primary.forEach { destination ->
            NavigationBarItem(
                selected = destination == active,
                onClick = { if (destination != active) onDestinationSelected(destination.id) },
                icon = { Icon(destination.icon, contentDescription = destination.label) },
                colors = portraitNavigationColors()
            )
        }
        NavigationBarItem(
            selected = active != null && active !in primary,
            onClick = { menuVisible = true },
            icon = { Icon(Icons.Default.Menu, contentDescription = menuLabel) },
            colors = portraitNavigationColors()
        )
    }
    if (menuVisible) {
        ModalBottomSheet(
            onDismissRequest = { menuVisible = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = BrowseColors.Surface,
            contentColor = BrowseColors.TextPrimary
        ) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
                Text(menuLabel, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
                destinations.forEach { destination ->
                    val isSelected = destination == active
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .background(if (isSelected) BrowseColors.Brand.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                            .semantics { selected = isSelected }
                            .clickable(role = Role.Tab) {
                                menuVisible = false
                                if (!isSelected) onDestinationSelected(destination.id)
                            }
                            .padding(horizontal = 24.dp, vertical = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(destination.icon, contentDescription = null, tint = if (isSelected) BrowseColors.Brand else BrowseColors.TextSecondary)
                        Text(destination.label)
                    }
                }
                if (actions != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.End, content = actions)
                }
            }
        }
    }
}

@Composable
private fun portraitNavigationColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = BrowseColors.Brand,
    unselectedIconColor = BrowseColors.TextSecondary,
    indicatorColor = BrowseColors.Brand.copy(alpha = 0.16f)
)
