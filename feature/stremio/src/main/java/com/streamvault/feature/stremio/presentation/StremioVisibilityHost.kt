package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.ui.components.shell.ExtensionsEmptyState
import com.streamvault.core.ui.components.shell.ExtensionsSectionTabs
import com.streamvault.core.ui.interaction.TvButton
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.streamvault.feature.stremio.R
import com.streamvault.feature.stremio.api.StremioScaffoldContent

@Composable
internal fun StremioVisibilityHost(
    scaffold: StremioScaffoldContent,
    title: String,
    showExtensionsTabs: Boolean = false,
    onNavigateToPlugins: () -> Unit = {},
    onOpenSettings: () -> Unit,
    content: @Composable (StremioVisibilityState) -> Unit,
    viewModel: StremioVisibilityViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.canAccessContent) {
        content(state)
    } else {
        scaffold(AppDestination.Addons, title, null, false, true, true) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                if (showExtensionsTabs) {
                    ExtensionsSectionTabs(
                        selectedIndex = 1,
                        pluginsLabel = stringResource(R.string.extensions_tab_plugins),
                        stremioAddonsLabel = stringResource(R.string.extensions_tab_stremio_addons),
                        onTabSelected = { if (it == 0) onNavigateToPlugins() }
                    )
                }
                if (state.hidden == null) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    ExtensionsEmptyState(
                        title = stringResource(R.string.stremio_hidden_title),
                        description = stringResource(R.string.stremio_hidden_message),
                        modifier = Modifier.weight(1f),
                        action = {
                            TvButton(
                                onClick = onOpenSettings,
                                scale = ButtonDefaults.scale(focusedScale = 1f, pressedScale = 1f)
                            ) { Text(stringResource(R.string.stremio_open_settings)) }
                        }
                    )
                }
            }
        }
    }
}
