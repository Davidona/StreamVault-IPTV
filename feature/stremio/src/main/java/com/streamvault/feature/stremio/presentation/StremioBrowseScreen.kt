package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.streamvault.core.navigation.AppDestination
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.feature.stremio.R
import com.streamvault.feature.stremio.api.StremioScaffoldContent
import com.streamvault.domain.model.stremio.StremioPlaybackHistory
import com.streamvault.domain.model.stremio.StremioMetaPreview
import com.streamvault.domain.model.stremio.installFingerprint

@Composable
internal fun StremioBrowseScreen(
    scaffold: StremioScaffoldContent,
    showContinueWatching: Boolean,
    addonsViewModel: AddonsViewModel,
    browseViewModel: StremioCatalogBrowseViewModel,
    onManageAddons: () -> Unit,
    onOpenItem: (Long, StremioMetaPreview) -> Unit,
    onOpenHistory: (StremioPlaybackHistory, Long) -> Unit
) {
    val addonsState = addonsViewModel.state.collectAsStateWithLifecycle().value
    var showContinueWatchingDialog by rememberSaveable { mutableStateOf(false) }
    scaffold(
        AppDestination.Addons,
        stringResource(R.string.stremio_browse_title),
        null,
        true,
        false,
        false
    ) {
        StremioCatalogBrowseContent(
            viewModel = browseViewModel,
            onOpenItem = onOpenItem,
            onManageAddons = onManageAddons,
            onContinueWatching = if (showContinueWatching && addonsState.continueWatching.isNotEmpty()) {
                { showContinueWatchingDialog = true }
            } else null
        )
    }

    if (showContinueWatchingDialog && addonsState.continueWatching.isNotEmpty()) {
        PremiumDialog(
            title = stringResource(R.string.stremio_continue_watching),
            onDismissRequest = { showContinueWatchingDialog = false },
            heightFraction = null,
            content = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 460.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(addonsState.continueWatching, key = { it.contentKey }) { item ->
                        val originAddonId = addonsState.addons.firstOrNull {
                            it.addon.installFingerprint() == item.catalogOriginKey
                        }?.addon?.id ?: 0L
                        TvButton(
                            onClick = {
                                showContinueWatchingDialog = false
                                onOpenHistory(item, originAddonId)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(item.title, maxLines = 1)
                        }
                    }
                }
            },
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.stremio_cancel),
                    onClick = { showContinueWatchingDialog = false }
                )
            }
        )
    }
}
