package com.streamvault.app.ui.screens.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.navigation.Routes
import com.streamvault.app.plugins.InstalledStreamVaultPlugin
import com.streamvault.app.plugins.PluginConfigurationAction
import com.streamvault.app.plugins.PluginConfigurationField
import com.streamvault.app.plugins.PluginConfigurationSection
import com.streamvault.app.plugins.owner
import com.streamvault.app.plugins.toBundleSafeKey
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.design.FocusSpec
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.Primary

@Composable
fun PluginsScreen(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PluginsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showInstallUrlDialog by remember { mutableStateOf(false) }
    val apkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.installFromLocalUri(uri)
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearMessage()
        }
    }

    AppScreenScaffold(
        currentRoute = currentRoute,
        onNavigate = onNavigate,
        title = "Plugins",
        subtitle = "Install companion APKs, activate capabilities, and sync plugin providers.",
        modifier = modifier,
        navigationChrome = AppNavigationChrome.TopBar,
        compactHeader = true
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val configuration = uiState.configuration
            if (configuration != null) {
                PluginConfigurationPanel(
                    configuration = configuration,
                    onBack = viewModel::closePluginConfiguration,
                    onRefresh = viewModel::refreshPluginConfiguration,
                    onSave = viewModel::savePluginConfiguration,
                    onValueChange = viewModel::updateConfigurationValue,
                    onRunAction = viewModel::runConfigurationAction
                )
            } else {
                PluginInstallPanel(
                    isInstalling = uiState.isInstalling,
                    onInstallFromUrl = { showInstallUrlDialog = true },
                    onInstallFromFile = {
                        apkPicker.launch(
                            arrayOf(
                                "application/vnd.android.package-archive",
                                "application/octet-stream",
                                "*/*"
                            )
                        )
                    },
                    onRefresh = viewModel::refreshPlugins
                )

                if (showInstallUrlDialog) {
                    PluginInstallUrlDialog(
                        installUrl = uiState.installUrl,
                        isInstalling = uiState.isInstalling,
                        onInstallUrlChange = viewModel::updateInstallUrl,
                        onDismiss = { showInstallUrlDialog = false },
                        onInstall = {
                            showInstallUrlDialog = false
                            viewModel.installFromUrl()
                        }
                    )
                }

                uiState.syncProgress?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Brand
                    )
                }

                if (uiState.providerSources.isNotEmpty()) {
                    Text(
                        text = "${uiState.providerSources.size} plugin provider source(s) available",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary
                    )
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (uiState.plugins.isEmpty() && !uiState.isLoading) {
                        item {
                            Text(
                                text = "No compatible StreamVault plugins are installed.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AppColors.TextSecondary
                            )
                        }
                    }
                    items(uiState.plugins, key = { it.owner.toBundleSafeKey() }) { plugin ->
                        PluginCard(
                            plugin = plugin,
                            busy = uiState.activePluginOwner == plugin.owner,
                            onEnabledChange = { enabled -> viewModel.setPluginEnabled(plugin, enabled) },
                            onOpenConfiguration = { viewModel.openPluginConfiguration(plugin) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(20.dp)) }
                }
            }
        }
        SnackbarHost(hostState = snackbarHostState)
    }
}

private enum class PluginButtonTone {
    Primary,
    Secondary
}

@Composable
private fun PluginButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tone: PluginButtonTone = PluginButtonTone.Secondary,
    content: @Composable RowScope.() -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    val colors = when (tone) {
        PluginButtonTone.Primary -> ButtonDefaults.colors(
            containerColor = Primary,
            contentColor = Color.White,
            focusedContainerColor = Primary.copy(alpha = 0.88f),
            focusedContentColor = Color.White,
            disabledContainerColor = AppColors.SurfaceEmphasis.copy(alpha = 0.56f),
            disabledContentColor = AppColors.TextDisabled
        )
        PluginButtonTone.Secondary -> ButtonDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = OnSurface,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            focusedContentColor = OnSurface,
            disabledContainerColor = AppColors.SurfaceEmphasis.copy(alpha = 0.56f),
            disabledContentColor = AppColors.TextDisabled
        )
    }

    TvButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 44.dp),
        enabled = enabled,
        shape = ButtonDefaults.shape(shape),
        colors = colors,
        border = ButtonDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(FocusSpec.BorderWidth, FocusBorder),
                shape = shape
            )
        ),
        scale = ButtonDefaults.scale(focusedScale = FocusSpec.FocusedScale),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 20.dp,
            vertical = 7.dp
        )
    ) {
        content()
    }
}

@Composable
private fun PluginInstallPanel(
    isInstalling: Boolean,
    onInstallFromUrl: () -> Unit,
    onInstallFromFile: () -> Unit,
    onRefresh: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PluginButton(
                enabled = !isInstalling,
                onClick = onInstallFromUrl,
                tone = PluginButtonTone.Primary
            ) {
                Text("Install URL")
            }
            PluginButton(
                enabled = !isInstalling,
                onClick = onInstallFromFile
            ) {
                Text("Install file")
            }
            PluginButton(onClick = onRefresh) {
                Text("Refresh")
            }
        }
        Text(
            text = "Manual installs are detected when this screen refreshes. Compatible plugins expose the StreamVault plugin service.",
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextTertiary
        )
    }
}

@Composable
private fun PluginInstallUrlDialog(
    installUrl: String,
    isInstalling: Boolean,
    onInstallUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onInstall: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    val normalizedUrl = installUrl.trim()

    fun submitUrl() {
        if (!isInstalling && normalizedUrl.isNotBlank()) {
            keyboardController?.hide()
            onInstall()
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
        keyboardController?.show()
    }

    PremiumDialog(
        title = "Install plugin from URL",
        subtitle = "Enter the direct APK URL. StreamVault will download it and open the installer.",
        onDismissRequest = {
            keyboardController?.hide()
            if (!isInstalling) onDismiss()
        },
        widthFraction = 0.5f,
        heightFraction = null,
        content = {
            OutlinedTextField(
                value = installUrl,
                onValueChange = onInstallUrlChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                enabled = !isInstalling,
                singleLine = true,
                label = { androidx.compose.material3.Text("Plugin APK URL") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(onDone = { submitUrl() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = AppColors.TextPrimary,
                    unfocusedTextColor = AppColors.TextPrimary,
                    disabledTextColor = AppColors.TextSecondary,
                    focusedBorderColor = AppColors.Brand,
                    unfocusedBorderColor = AppColors.Outline,
                    disabledBorderColor = AppColors.Outline.copy(alpha = 0.4f),
                    focusedLabelColor = AppColors.Brand,
                    unfocusedLabelColor = AppColors.TextSecondary,
                    disabledLabelColor = AppColors.TextTertiary,
                    cursorColor = AppColors.Brand
                )
            )
        },
        footer = {
            PremiumDialogFooterButton(
                label = "Cancel",
                onClick = {
                    keyboardController?.hide()
                    onDismiss()
                },
                enabled = !isInstalling
            )
            PremiumDialogFooterButton(
                label = if (isInstalling) "Installing..." else "Download and install",
                onClick = ::submitUrl,
                enabled = !isInstalling && normalizedUrl.isNotBlank(),
                emphasized = true
            )
        }
    )
}

@Composable
private fun PluginCard(
    plugin: InstalledStreamVaultPlugin,
    busy: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onOpenConfiguration: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = plugin.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = AppColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    StatusPill(
                        label = if (plugin.enabled) "Enabled" else "Disabled",
                        containerColor = if (plugin.enabled) AppColors.BrandMuted else AppColors.SurfaceEmphasis,
                        contentColor = if (plugin.enabled) AppColors.Brand else AppColors.TextSecondary
                    )
                }
                Text(
                    text = plugin.manifest.description.ifBlank { plugin.packageName },
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val version = plugin.manifest.versionName.ifBlank { "unknown" }
                Text(
                    text = "${plugin.packageName} · v$version",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (plugin.statusLabel.isNotBlank() || plugin.lastMessage.isNotBlank()) {
                    Text(
                        text = listOf(plugin.statusLabel, plugin.lastMessage)
                            .filter { it.isNotBlank() }
                            .joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PluginButton(
                    enabled = !busy && plugin.manifest.canConfigure,
                    onClick = onOpenConfiguration
                ) {
                    Text("Configure")
                }
                Switch(
                    checked = plugin.enabled,
                    enabled = !busy,
                    onCheckedChange = onEnabledChange
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            plugin.manifest.capabilities.forEach { capability ->
                StatusPill(
                    label = capability,
                    containerColor = Color.White.copy(alpha = 0.08f),
                    contentColor = AppColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun PluginConfigurationPanel(
    configuration: ActivePluginConfiguration,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSave: () -> Unit,
    onValueChange: (String, String) -> Unit,
    onRunAction: (PluginConfigurationAction) -> Unit
) {
    val busy = configuration.isSaving || configuration.runningActionId != null
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        PluginConfigurationHeader(
            configuration = configuration,
            busy = busy,
            onBack = onBack,
            onRefresh = onRefresh,
            onSave = onSave
        )

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            configuration.schema.sections.forEach { section ->
                item(key = section.id) {
                    PluginConfigurationSectionCard(
                        section = section,
                        configuration = configuration,
                        busy = busy,
                        onValueChange = onValueChange
                    )
                }
            }

            if (configuration.schema.actions.isNotEmpty()) {
                item {
                    PluginConfigurationActions(
                        actions = configuration.schema.actions,
                        runningActionId = configuration.runningActionId,
                        busy = configuration.isSaving,
                        onRunAction = onRunAction
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun PluginConfigurationHeader(
    configuration: ActivePluginConfiguration,
    busy: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onSave: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface.copy(alpha = 0.72f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = configuration.schema.title.ifBlank { configuration.plugin.displayName },
                    style = MaterialTheme.typography.titleMedium,
                    color = AppColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val description = configuration.schema.description
                    .ifBlank { configuration.plugin.manifest.description }
                if (description.isNotBlank()) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PluginButton(enabled = !busy, onClick = onBack) { Text("Back") }
                PluginButton(enabled = !busy, onClick = onRefresh) { Text("Refresh") }
                PluginButton(
                    enabled = !busy && configuration.isDirty,
                    onClick = onSave,
                    tone = PluginButtonTone.Primary
                ) { Text("Save") }
            }
        }
        if (configuration.validationErrors.isNotEmpty()) {
            Text(
                text = "Some plugin settings need attention before saving.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.Live
            )
        }
    }
}

@Composable
private fun PluginConfigurationSectionCard(
    section: PluginConfigurationSection,
    configuration: ActivePluginConfiguration,
    busy: Boolean,
    onValueChange: (String, String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleSmall,
                color = AppColors.TextPrimary
            )
            if (section.description.isNotBlank()) {
                Text(
                    text = section.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSecondary
                )
            }
        }

        section.fields.forEach { field ->
            PluginConfigurationFieldRow(
                field = field,
                value = configuration.draftValues[field.key].orEmpty(),
                error = configuration.validationErrors[field.key],
                busy = busy,
                onValueChange = { onValueChange(field.key, it) }
            )
        }
    }
}

@Composable
private fun PluginConfigurationFieldRow(
    field: PluginConfigurationField,
    value: String,
    error: String?,
    busy: Boolean,
    onValueChange: (String) -> Unit
) {
    when (field.type) {
        PluginConfigurationField.TYPE_INFO -> PluginConfigurationInfoField(field, value)
        PluginConfigurationField.TYPE_BOOLEAN -> PluginConfigurationBooleanField(field, value, busy, onValueChange)
        PluginConfigurationField.TYPE_SELECT -> PluginConfigurationSelectField(field, value, busy, onValueChange)
        else -> PluginConfigurationTextField(field, value, error, busy, onValueChange)
    }
}

@Composable
private fun PluginConfigurationInfoField(field: PluginConfigurationField, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.bodySmall,
            color = AppColors.TextTertiary
        )
        Text(
            text = value.ifBlank { field.placeholder.ifBlank { "-" } },
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextPrimary
        )
        if (field.description.isNotBlank()) {
            Text(
                text = field.description,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )
        }
    }
}

@Composable
private fun PluginConfigurationBooleanField(
    field: PluginConfigurationField,
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface.copy(alpha = 0.38f), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = field.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (field.readOnly) AppColors.TextSecondary else AppColors.TextPrimary
            )
            if (field.description.isNotBlank()) {
                Text(
                    text = field.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextTertiary
                )
            }
        }
        Switch(
            checked = value.equals("true", ignoreCase = true),
            enabled = !busy && !field.readOnly,
            onCheckedChange = { onValueChange(it.toString()) }
        )
    }
}

@Composable
private fun PluginConfigurationSelectField(
    field: PluginConfigurationField,
    value: String,
    busy: Boolean,
    onValueChange: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.bodyMedium,
            color = AppColors.TextPrimary
        )
        if (field.description.isNotBlank()) {
            Text(
                text = field.description,
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSecondary
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            field.options.forEach { option ->
                PluginButton(
                    enabled = !busy && !field.readOnly,
                    onClick = { onValueChange(option.value) }
                ) {
                    Text(
                        text = if (option.value == value) "${option.label} ✓" else option.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginConfigurationTextField(
    field: PluginConfigurationField,
    value: String,
    error: String?,
    busy: Boolean,
    onValueChange: (String) -> Unit
) {
    val singleLine = field.type != PluginConfigurationField.TYPE_TEXTAREA
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (singleLine) Modifier else Modifier.heightIn(min = 96.dp)),
        enabled = !busy && !field.readOnly,
        readOnly = field.readOnly,
        singleLine = singleLine,
        maxLines = if (singleLine) 1 else 5,
        label = { androidx.compose.material3.Text(field.label) },
        placeholder = {
            if (field.placeholder.isNotBlank()) {
                androidx.compose.material3.Text(field.placeholder)
            }
        },
        isError = error != null,
        supportingText = {
            val helper = error ?: field.description.takeIf { it.isNotBlank() }
            if (helper != null) {
                androidx.compose.material3.Text(helper)
            }
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = when (field.type) {
                PluginConfigurationField.TYPE_NUMBER -> KeyboardType.Number
                PluginConfigurationField.TYPE_URL -> KeyboardType.Uri
                else -> KeyboardType.Text
            }
        ),
        visualTransformation = if (field.secret || field.type == PluginConfigurationField.TYPE_PASSWORD) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = AppColors.TextPrimary,
            unfocusedTextColor = AppColors.TextPrimary,
            disabledTextColor = AppColors.TextSecondary,
            focusedBorderColor = AppColors.Brand,
            unfocusedBorderColor = AppColors.Outline,
            disabledBorderColor = AppColors.Outline.copy(alpha = 0.4f),
            errorBorderColor = AppColors.Live,
            focusedLabelColor = AppColors.Brand,
            unfocusedLabelColor = AppColors.TextSecondary,
            disabledLabelColor = AppColors.TextTertiary,
            cursorColor = AppColors.Brand
        )
    )
}

@Composable
private fun PluginConfigurationActions(
    actions: List<PluginConfigurationAction>,
    runningActionId: String?,
    busy: Boolean,
    onRunAction: (PluginConfigurationAction) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.Surface.copy(alpha = 0.55f), RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "Actions",
            style = MaterialTheme.typography.titleSmall,
            color = AppColors.TextPrimary
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            actions.forEach { action ->
                PluginButton(
                    enabled = !busy && runningActionId == null,
                    onClick = { onRunAction(action) },
                    modifier = Modifier.widthIn(min = 120.dp)
                ) {
                    Text(
                        text = if (runningActionId == action.id) "${action.label}..." else action.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
