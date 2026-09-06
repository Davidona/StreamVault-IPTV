package com.streamvault.app.ui.screens.settings

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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.domain.model.EpgSource
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
    onDelete: () -> Unit
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.06f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(source.name, style = MaterialTheme.typography.titleSmall, color = Color.White)
                    Text(displayableEpgUrl(source.url), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim, maxLines = 1)
                    Text(
                        text = when (source.timezonePolicy) {
                            XmltvTimezonePolicy.REQUIRE_OFFSET -> "Timezone: XMLTV offset required"
                            XmltvTimezonePolicy.UTC -> "Timezone: UTC"
                            XmltvTimezonePolicy.EXPLICIT_ZONE -> "Timezone: ${source.timezoneId}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = OnSurfaceDim
                    )
                    if (editingTimezone) {
                        EpgSourceTextField(
                            value = timezoneInput,
                            onValueChange = { timezoneInput = it },
                            placeholder = "IANA timezone, UTC, or blank to require XMLTV offsets"
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
                                    if (savingTimezone) "Saving..." else "Save timezone",
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
                                    containerColor = Color.White.copy(alpha = 0.08f),
                                    focusedContainerColor = Color.White.copy(alpha = 0.15f)
                                ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                            ) {
                                Text(
                                    "Cancel",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = OnSurfaceDim
                                )
                            }
                        }
                    }
                    if (source.lastError != null) {
                        Text("Error: ${source.lastError}", style = MaterialTheme.typography.bodySmall, color = Color(0xFFEF5350))
                    }
                    if (source.lastSuccessAt > 0L) {
                        val ago = (System.currentTimeMillis() - source.lastSuccessAt) / 60000
                        Text("Last synced: ${ago}m ago", style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                    }
                }
                val sourceActionShape = RoundedCornerShape(8.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TvClickableSurface(
                        onClick = { editingTimezone = !editingTimezone },
                        shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            focusedContainerColor = Color.White.copy(alpha = 0.15f)
                        ),
                        border = epgActionBorder(sourceActionShape),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                    ) {
                        Text(
                            "Timezone",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = OnSurfaceDim
                        )
                    }
                    TvClickableSurface(
                        onClick = { onToggleEnabled(!source.enabled) },
                        shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                        colors = ClickableSurfaceDefaults.colors(
                            containerColor = if (source.enabled) Color(0xFF66BB6A).copy(alpha = 0.2f) else Color.White.copy(alpha = 0.08f),
                            focusedContainerColor = if (source.enabled) Color(0xFF66BB6A).copy(alpha = 0.4f) else Color.White.copy(alpha = 0.15f)
                        ),
                        border = epgActionBorder(sourceActionShape),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                    ) {
                        Text(
                            if (source.enabled) "ON" else "OFF",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (source.enabled) Color(0xFF66BB6A) else OnSurfaceDim
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
                            if (isRefreshing) "..." else "Refresh",
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
                                containerColor = Color.White.copy(alpha = 0.08f),
                                focusedContainerColor = Color.White.copy(alpha = 0.15f)
                            ),
                            border = epgActionBorder(sourceActionShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Text("Cancel", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = OnSurfaceDim)
                        }
                        TvClickableSurface(
                            onClick = onDelete,
                            shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFFEF5350).copy(alpha = 0.25f),
                                focusedContainerColor = Color(0xFFEF5350).copy(alpha = 0.45f)
                            ),
                            border = epgActionBorder(sourceActionShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Text("Confirm Delete", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF5350))
                        }
                    } else {
                        TvClickableSurface(
                            onClick = { onSetPendingDelete(true) },
                            shape = ClickableSurfaceDefaults.shape(sourceActionShape),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color(0xFFEF5350).copy(alpha = 0.12f),
                                focusedContainerColor = Color(0xFFEF5350).copy(alpha = 0.25f)
                            ),
                            border = epgActionBorder(sourceActionShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                        ) {
                            Text("Delete", modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF5350))
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun AddEpgSourceCard(viewModel: SettingsViewModel) {
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
            .background(Color.White.copy(alpha = 0.06f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add EPG Source", style = MaterialTheme.typography.titleSmall, color = Color.White)
            EpgSourceTextField(value = newName, onValueChange = { newName = it }, placeholder = "Source name")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    EpgSourceTextField(
                        value = newUrl,
                        onValueChange = { newUrl = it },
                        placeholder = "XMLTV URL (HTTP/HTTPS) or browse file"
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
                    Text("Browse", modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium, color = Primary)
                }
            }
            EpgSourceTextField(
                value = newTimezoneId,
                onValueChange = { newTimezoneId = it },
                placeholder = "Timezone for offset-less dates (e.g. Europe/Amsterdam); blank requires XMLTV offsets"
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
                    containerColor = Color(0xFF66BB6A).copy(alpha = 0.2f),
                    focusedContainerColor = Color(0xFF66BB6A).copy(alpha = 0.4f)
                ),
                border = epgActionBorder(addSourceShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
            ) {
                Text("Add Source", modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Color(0xFF66BB6A), fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
internal fun epgActionBorder(shape: RoundedCornerShape, enabled: Boolean = true) =
    ClickableSurfaceDefaults.border(
        border = Border(
            border = BorderStroke(1.dp, Color.White.copy(alpha = if (enabled) 0.08f else 0.04f)),
            shape = shape
        ),
        focusedBorder = Border(
            border = BorderStroke(FocusSpec.BorderWidth, FocusBorder),
            shape = shape
        )
    )

private fun displayableEpgUrl(url: String): String = when {
    url.startsWith("content://") -> {
        val lastSegment = try { android.net.Uri.parse(url).lastPathSegment } catch (_: Exception) { null }
        val decoded = lastSegment?.let { android.net.Uri.decode(it) }?.substringAfterLast("/")?.substringAfterLast("\\")
        if (!decoded.isNullOrBlank() && decoded.length < 60) "local: $decoded" else "local file"
    }
    else -> url
}
