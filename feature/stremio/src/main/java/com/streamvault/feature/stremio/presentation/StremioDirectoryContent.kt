package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.domain.model.stremio.stremioConfigureUrl
import com.streamvault.feature.stremio.R

@Composable
internal fun StremioDirectoryContent(
    onBack: () -> Unit,
    onInstall: (String) -> Unit,
    viewModel: StremioDirectoryViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val uriHandler = LocalUriHandler.current
    var openFailed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(onClick = onBack) { Text(stringResource(R.string.stremio_browse)) }
            TvButton(onClick = viewModel::load) { Text(stringResource(R.string.stremio_refresh)) }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.sources, key = { it.key }) { source ->
                TvButton(onClick = { viewModel.select(source.key) }) { Text(source.name) }
            }
        }
        if (state.loading) CircularProgressIndicator()
        if (state.failed || openFailed) Text(stringResource(R.string.stremio_error_generic))
        if (!state.loading && state.entries.isEmpty()) Text(stringResource(R.string.stremio_empty_catalog))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.entries, key = { it.transportUrl }) { entry ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.manifest.name)
                    entry.manifest.description?.let { Text(it, maxLines = 4) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val installed = entry.transportUrl in state.installedUrls
                        TvButton(onClick = { onInstall(entry.transportUrl) },
                            enabled = !installed && !entry.manifest.behaviorHints.configurationRequired) {
                            Text(stringResource(if (installed) R.string.stremio_status_installed else R.string.stremio_add))
                        }
                        if (entry.manifest.behaviorHints.configurable || entry.manifest.behaviorHints.configurationRequired) {
                            TvButton(onClick = {
                                val url = stremioConfigureUrl(entry.transportUrl)
                                openFailed = url == null || runCatching { uriHandler.openUri(url) }.isFailure
                            }) { Text(stringResource(R.string.stremio_configure)) }
                        }
                    }
                }
            }
        }
    }
}
