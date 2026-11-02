package com.streamvault.feature.stremio.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.Switch
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.domain.model.stremio.StremioMetaPreview
import com.streamvault.feature.stremio.R

@Composable
internal fun StremioLibraryContent(onBack: () -> Unit, onOpenItem: (Long, StremioMetaPreview) -> Unit,
    viewModel: StremioLibraryViewModel = hiltViewModel()
) {
    BackHandler(onBack = onBack)
    val rows by viewModel.state.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    var sort by remember { mutableIntStateOf(0) }
    val context=androidx.compose.ui.platform.LocalContext.current
    fun permitted()=android.os.Build.VERSION.SDK_INT<33 || androidx.core.content.ContextCompat.checkSelfPermission(
        context,android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED
    var notificationsGranted by remember { mutableStateOf(permitted()) }
    val permission=androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { notificationsGranted=it }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { notificationsGranted=permitted() }
    val sorted = remember(rows, sort) {
        when (sort) {
            1 -> rows.sortedBy { it.preview.name.lowercase(java.util.Locale.getDefault()) }
            2 -> rows.sortedByDescending { it.watched }
            3 -> rows.sortedBy { it.watched }
            else -> rows.sortedByDescending { it.lastWatchedAt }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TvButton(onClick = onBack) { Text(stringResource(R.string.stremio_browse)) }
        if(!notificationsGranted) TvButton(onClick={ permission.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) {
            Text(stringResource(R.string.stremio_notifications))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(listOf(R.string.stremio_last_watched, R.string.stremio_sort_title, R.string.stremio_status_watched, R.string.stremio_status_unwatched).withIndex().toList()) { (index,key) ->
                TvButton(onClick = { sort = index }) { Text(stringResource(key)) }
            }
        }
        if (loading) CircularProgressIndicator()
        if (!loading && rows.isEmpty()) Text(stringResource(R.string.stremio_empty_catalog))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sorted, key = { "${it.addonId}:${it.preview.type.typeName}:${it.preview.id}" }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvButton(onClick = { onOpenItem(row.addonId, row.preview) }, modifier = Modifier.weight(1f)) {
                        Column {
                            Text(row.preview.name)
                            if (row.watched) Text(stringResource(R.string.stremio_status_watched))
                        }
                    }
                    if (row.preview.type == com.streamvault.domain.model.stremio.StremioMediaType.SERIES) {
                        TvButton(onClick = { viewModel.toggleNotifications(row) }) {
                            Text(stringResource(R.string.stremio_notifications))
                            Switch(checked = row.notifications, onCheckedChange = null)
                        }
                    }
                }
            }
        }
    }
}
