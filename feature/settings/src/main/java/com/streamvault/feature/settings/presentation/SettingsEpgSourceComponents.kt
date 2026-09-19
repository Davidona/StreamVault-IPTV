package com.streamvault.feature.settings.presentation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.FocusSpec
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.ErrorColor
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.domain.model.EpgSource
import com.streamvault.feature.settings.R
import com.streamvault.domain.model.XmltvTimezonePolicy

@Composable
internal fun EpgSourceCard(
    source: EpgSource,
    isRefreshing: Boolean,
    pendingDelete: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onUpdateTimezone: (String?, () -> Unit, () -> Unit) -> Unit,
    onSetPendingDelete: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var editingTimezone by remember(source.id) { mutableStateOf(false) }
    var timezoneInput by remember(source.id, source.timezonePolicy, source.timezoneId) {
        mutableStateOf(
            when (source.timezonePolicy) {
                XmltvTimezonePolicy.REQUIRE_OFFSET -> ""
                XmltvTimezonePolicy.UTC -> "UTC"
                XmltvTimezonePolicy.EXPLICIT_ZONE -> source.timezoneId.orEmpty()
            }
        )
    }
    var savingTimezone by remember(source.id) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleSmall, color = OnSurface)
                    Text(displayableEpgUrl(source.url), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim, maxLines = 1)
                    Text(
                        text = when (source.timezonePolicy) {
                            XmltvTimezonePolicy.REQUIRE_OFFSET -> stringResource(R.string.settings_epg_timezone_xmltv)
                            XmltvTimezonePolicy.UTC -> stringResource(R.string.settings_epg_timezone_value, "UTC")
                            XmltvTimezonePolicy.EXPLICIT_ZONE -> stringResource(
                                R.string.settings_epg_timezone_value,
                                source.timezoneId.orEmpty(),
                            )
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                    if (editingTimezone) {
                        EpgSourceTextField(
                            value = timezoneInput,
                            onValueChange = { timezoneInput = it },
                                    placeholder = stringResource(R.string.settings_epg_timezone_hint)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvClickableSurface(
                                onClick = {
                                    savingTimezone = true
                                    onUpdateTimezone(
                                        timezoneInput.trim().takeIf(String::isNotEmpty),
                                        {
                                            savingTimezone = false
                                            editingTimezone = false
                                        },
                                        { savingTimezone = false }
                                    )
                                },
                                enabled = !savingTimezone,
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = Primary.copy(alpha = 0.15f),
                                    focusedContainerColor = Primary.copy(alpha = 0.3f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                            ) {
                                Text(
                                    stringResource(
                                        if (savingTimezone) R.string.settings_epg_timezone_saving
                                        else R.string.settings_epg_timezone_save
                                    ),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Primary
                                )
                            }
                            TvClickableSurface(
                                onClick = { editingTimezone = false },
                                enabled = !savingTimezone,
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = SurfaceElevated,
                                    focusedContainerColor = SurfaceHighlight
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                            ) {
                                Text(
                                    stringResource(R.string.settings_cancel),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnSurfaceDim
                                )
                            }
                        }
                    }
                    if (source.lastError != null) {
                        Text(
                            stringResource(R.string.settings_epg_source_error, source.lastError.orEmpty()),
                            style = MaterialTheme.typography.bodySmall,
                            color = ErrorColor,
                        )
                    }
                    if (source.lastSuccessAt > 0L) {
                        val ago = (System.currentTimeMillis() - source.lastSuccessAt) / 60000
                        Text(
                            stringResource(R.string.settings_epg_last_synced_minutes, ago),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim,
                        )
                    }
                }
                val sourceActionShape = RoundedCornerShape(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvClickableSurface(
                        onClick = { editingTimezone = !editingTimezone },
                        shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = SurfaceElevated,
                            focusedContainerColor = SurfaceHighlight
                        ),
                        border = epgActionBorder(sourceActionShape),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                    ) {
                        Text(
                            stringResource(R.string.settings_epg_timezone_action),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceDim
                        )
                    }
                    TvClickableSurface(
                        onClick = { onToggleEnabled(!source.enabled) },
                        shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (source.enabled) Primary.copy(alpha = 0.2f) else SurfaceElevated,
                            focusedContainerColor = if (source.enabled) Primary.copy(alpha = 0.4f) else SurfaceHighlight
                        ),
                        border = epgActionBorder(sourceActionShape),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                    ) {
                        Text(
                            stringResource(
                                if (source.enabled) R.string.settings_enabled else R.string.settings_disabled
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (source.enabled) Primary else OnSurfaceDim
                        )
                    }
                    TvClickableSurface(
                        onClick = onRefresh,
                        shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Primary.copy(alpha = 0.15f),
                            focusedContainerColor = Primary.copy(alpha = 0.3f)
                        ),
                        border = epgActionBorder(sourceActionShape, enabled = !isRefreshing),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        enabled = !isRefreshing
                    ) {
                        Text(
                            stringResource(
                                if (isRefreshing) R.string.settings_epg_refreshing else R.string.settings_epg_refresh
                            ),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary
                        )
                    }
                    if (pendingDelete) {
                        TvClickableSurface(
                            onClick = { onSetPendingDelete(false) },
                            shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = SurfaceElevated,
                                focusedContainerColor = SurfaceHighlight
                            ),
                            border = epgActionBorder(sourceActionShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Text(stringResource(R.string.settings_cancel), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
                        }
                        TvClickableSurface(
                            onClick = onDelete,
                            shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = ErrorColor.copy(alpha = 0.25f),
                                focusedContainerColor = ErrorColor.copy(alpha = 0.45f)
                            ),
                            border = epgActionBorder(sourceActionShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Text(stringResource(R.string.settings_epg_confirm_delete), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = ErrorColor)
                        }
                    } else {
                        TvClickableSurface(
                            onClick = { onSetPendingDelete(true) },
                            shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = ErrorColor.copy(alpha = 0.12f),
                                focusedContainerColor = ErrorColor.copy(alpha = 0.25f)
                            ),
                            border = epgActionBorder(sourceActionShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Text(stringResource(R.string.settings_delete), modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = ErrorColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AddEpgSourceCard(
    viewModel: SettingsViewModel,
    firstFocusModifier: Modifier = Modifier,
) {
    var newName by remember { mutableStateOf("") }
    var newUrl by remember { mutableStateOf("") }
    var newTimezoneId by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            newUrl = uri.toString()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceElevated)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.settings_epg_add_source), style = MaterialTheme.typography.titleSmall, color = OnSurface)
            EpgSourceTextField(
                value = newName,
                onValueChange = { newName = it },
                placeholder = stringResource(R.string.settings_epg_source_name_hint),
                modifier = firstFocusModifier,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    EpgSourceTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                    placeholder = stringResource(R.string.settings_epg_source_url_hint)
                    )
                }
                val addActionShape = RoundedCornerShape(8.dp)
                TvClickableSurface(
                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    shape = ClickableSurfaceDefaults.shape(addActionShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = Primary.copy(alpha = 0.15f),
                        focusedContainerColor = Primary.copy(alpha = 0.3f)
                    ),
                    border = epgActionBorder(addActionShape),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                ) {
                    Text(stringResource(R.string.settings_epg_browse), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = Primary)
                }
            }
            EpgSourceTextField(
                value = newTimezoneId,
                onValueChange = { newTimezoneId = it },
                placeholder = stringResource(R.string.settings_epg_source_timezone_hint)
            )
            val addSourceShape = RoundedCornerShape(8.dp)
            TvClickableSurface(
                onClick = {
                    if (!isSubmitting && newName.isNotBlank() && newUrl.isNotBlank()) {
                        val nameToSubmit = newName.trim()
                        val urlToSubmit = newUrl.trim()
                        val timezoneToSubmit = newTimezoneId.trim().takeIf(String::isNotEmpty)
                        isSubmitting = true
                        viewModel.addEpgSource(nameToSubmit, urlToSubmit, timezoneToSubmit,
                            onSuccess = {
                                newName = ""
                                newUrl = ""
                                newTimezoneId = ""
                                isSubmitting = false
                            },
                            onError = { isSubmitting = false }
                        )
                    }
                },
                enabled = newName.isNotBlank() && newUrl.isNotBlank() && !isSubmitting,
                shape = ClickableSurfaceDefaults.shape(addSourceShape),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Primary.copy(alpha = 0.2f),
                    focusedContainerColor = Primary.copy(alpha = 0.4f)
                ),
                border = epgActionBorder(addSourceShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Text(stringResource(R.string.settings_epg_add_source_action), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Primary, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun epgActionBorder(shape: RoundedCornerShape, enabled: Boolean = true) =
    ClickableSurfaceDefaults.border(
        border = Border(
            border = BorderStroke(1.dp, OnSurface.copy(alpha = if (enabled) 0.08f else 0.04f)),
            shape = shape
        ),
        focusedBorder = Border(
            border = BorderStroke(FocusSpec.BorderWidth, com.streamvault.core.ui.theme.FocusBorder),
            shape = shape
        )
    )

@Composable
private fun displayableEpgUrl(url: String): String = when {
    url.startsWith("content://") -> {
        val lastSegment = try { android.net.Uri.parse(url).lastPathSegment } catch (_: Exception) { null }
        val decoded = lastSegment?.let { android.net.Uri.decode(it) }?.substringAfterLast("/")?.substringAfterLast("\\")
        if (!decoded.isNullOrBlank() && decoded.length < 60) {
            stringResource(R.string.settings_epg_local_source, decoded)
        } else {
            stringResource(R.string.settings_epg_local_file)
        }
    }
    else -> url
}
