package com.streamvault.app.ui.screens.settings.parental

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import com.streamvault.app.ui.design.AppColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.SearchInput
import com.streamvault.app.ui.components.dialogs.PinDialog
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.domain.model.ContentType
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.TvIconButton
import kotlinx.coroutines.launch

private enum class CategoryControlsMode {
    PROTECTION,
    VISIBILITY
}

private enum class CategoryPinAction {
    VERIFY_EXISTING,
    SET_NEW
}

@Composable
fun ParentalControlGroupScreen(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onBack: () -> Unit,
    viewModel: ParentalControlGroupViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val searchWidth = if (screenWidth < 700.dp) {
        (screenWidth * 0.56f).coerceIn(180.dp, 260.dp)
    } else {
        420.dp
    }
    val backButtonFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var currentMode by rememberSaveable { mutableStateOf(CategoryControlsMode.PROTECTION) }
    var selectedType by rememberSaveable { mutableStateOf(ContentType.LIVE) }
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var pinAction by rememberSaveable { mutableStateOf<CategoryPinAction?>(null) }
    var pinError by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedTypeLabel = contentTypeTabLabel(selectedType)
    val filteredCategories = uiState.categories.filter { item ->
        item.category.type == selectedType &&
            (uiState.searchQuery.isBlank() || item.category.name.contains(uiState.searchQuery, ignoreCase = true))
    }
    val hiddenCount = uiState.categories.count { item ->
        item.category.type == selectedType && item.isHidden
    }
    val visibleCount = uiState.categories.count { item ->
        item.category.type == selectedType && !item.isHidden
    }

    LaunchedEffect(Unit) {
        backButtonFocusRequester.requestFocus()
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }

    AppScreenScaffold(
        currentRoute = currentRoute,
        onNavigate = onNavigate,
        title = stringResource(R.string.settings_provider_category_controls_title),
        subtitle = stringResource(R.string.settings_provider_category_controls_subtitle),
        navigationChrome = AppNavigationChrome.TopBar,
        compactHeader = true,
        showScreenHeader = false,
        header = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TvIconButton(
                    onClick = onBack,
                    modifier = Modifier.focusRequester(backButtonFocusRequester)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.parental_group_back)
                    )
                }
                SearchInput(
                    value = uiState.searchQuery,
                    onValueChange = viewModel::onSearchQueryChange,
                    placeholder = stringResource(R.string.settings_provider_category_controls_search),
                    onSearch = {},
                    modifier = Modifier.width(searchWidth)
                )
            }
        }
    ) {
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    ModeSelectorRow(
                        selectedMode = currentMode,
                        onModeSelected = { currentMode = it }
                    )
                }

                item {
                    ContentTypeSelectorRow(
                        selectedType = selectedType,
                        onTypeSelected = { selectedType = it }
                    )
                }

                item {
                    when (currentMode) {
                        CategoryControlsMode.PROTECTION -> ProtectionSummaryCard(
                            selectedTypeLabel = selectedTypeLabel,
                            hasChanges = uiState.hasPendingProtectionChanges,
                            changeCount = uiState.pendingProtectionChangeCount,
                            hasParentalPin = uiState.hasParentalPin,
                            onSave = {
                                pinError = null
                                pinAction = if (uiState.hasParentalPin) {
                                    CategoryPinAction.VERIFY_EXISTING
                                } else {
                                    CategoryPinAction.SET_NEW
                                }
                                showPinDialog = true
                            },
                            onReset = viewModel::resetProtectionChanges
                        )
                        CategoryControlsMode.VISIBILITY -> VisibilitySummaryCard(
                            selectedTypeLabel = selectedTypeLabel,
                            hiddenCount = hiddenCount,
                            visibleCount = visibleCount,
                            onHideAll = { viewModel.hideAllCategories(selectedType) },
                            onUnhideAll = { viewModel.unhideAllCategories(selectedType) }
                        )
                    }
                }

                if (filteredCategories.isEmpty()) {
                    item {
                        EmptyCategoryMessage()
                    }
                } else {
                    items(filteredCategories, key = { it.key }) { item ->
                        when (currentMode) {
                            CategoryControlsMode.PROTECTION -> CategoryProtectionCard(
                                item = item,
                                onToggle = { viewModel.toggleCategoryProtection(item.category) }
                            )
                            CategoryControlsMode.VISIBILITY -> CategoryVisibilityCard(
                                item = item,
                                onToggleHidden = { viewModel.toggleCategoryHidden(item) }
                            )
                        }
                    }
                }
            }
        }
    }

    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.padding(bottom = 16.dp)
    )

    if (showPinDialog && pinAction != null) {
        PinDialog(
            onDismissRequest = {
                showPinDialog = false
                pinAction = null
                pinError = null
            },
            onPinEntered = { pin ->
                scope.launch {
                    when (pinAction) {
                        CategoryPinAction.SET_NEW -> {
                            viewModel.setParentalPin(pin)
                            viewModel.saveProtectionChanges()
                            showPinDialog = false
                            pinAction = null
                            pinError = null
                        }
                        CategoryPinAction.VERIFY_EXISTING -> {
                            if (viewModel.verifyPin(pin)) {
                                viewModel.saveProtectionChanges()
                                showPinDialog = false
                                pinAction = null
                                pinError = null
                            } else {
                                pinError = context.getString(R.string.home_incorrect_pin)
                            }
                        }
                        null -> Unit
                    }
                }
            },
            title = if (pinAction == CategoryPinAction.SET_NEW) {
                stringResource(R.string.settings_enter_new_pin)
            } else {
                stringResource(R.string.settings_enter_pin)
            },
            error = pinError
        )
    }
}

@Composable
private fun ContentTypeSelectorRow(
    selectedType: ContentType,
    onTypeSelected: (ContentType) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(ContentType.LIVE, ContentType.MOVIE, ContentType.SERIES).forEach { type ->
            CategoryModeChip(
                label = contentTypeTabLabel(type),
                selected = selectedType == type,
                onClick = { onTypeSelected(type) }
            )
        }
    }
}

@Composable
private fun contentTypeTabLabel(type: ContentType): String = when (type) {
    ContentType.LIVE -> stringResource(R.string.nav_live_tv)
    ContentType.MOVIE -> stringResource(R.string.nav_movies)
    ContentType.VOD -> stringResource(R.string.nav_vod)
    ContentType.SERIES,
    ContentType.SERIES_EPISODE -> stringResource(R.string.nav_series)
}

@Composable
private fun ModeSelectorRow(
    selectedMode: CategoryControlsMode,
    onModeSelected: (CategoryControlsMode) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CategoryModeChip(
            label = stringResource(R.string.settings_category_mode_protection),
            selected = selectedMode == CategoryControlsMode.PROTECTION,
            onClick = { onModeSelected(CategoryControlsMode.PROTECTION) }
        )
        CategoryModeChip(
            label = stringResource(R.string.settings_category_mode_visibility),
            selected = selectedMode == CategoryControlsMode.VISIBILITY,
            onClick = { onModeSelected(CategoryControlsMode.VISIBILITY) }
        )
    }
}

@Composable
private fun CategoryModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) AppColors.Brand.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = AppColors.Brand.copy(alpha = 0.28f)
        ),
        border = ClickableSurfaceDefaults.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) AppColors.Brand else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun ProtectionSummaryCard(
    selectedTypeLabel: String,
    hasChanges: Boolean,
    changeCount: Int,
    hasParentalPin: Boolean,
    onSave: () -> Unit,
    onReset: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = selectedTypeLabel,
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.Brand
        )
        Text(
            text = stringResource(R.string.settings_category_protection_summary_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = if (hasParentalPin) {
                stringResource(R.string.settings_category_protection_summary_body)
            } else {
                stringResource(R.string.settings_category_protection_summary_body_no_pin)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsActionButton(
                label = if (hasChanges) {
                    stringResource(R.string.settings_category_protection_save, changeCount)
                } else {
                    stringResource(R.string.settings_category_protection_save_idle)
                },
                enabled = hasChanges,
                emphasized = true,
                onClick = onSave
            )
            SettingsActionButton(
                label = stringResource(R.string.settings_category_protection_reset),
                enabled = hasChanges,
                emphasized = false,
                onClick = onReset
            )
        }
    }
}

@Composable
private fun VisibilitySummaryCard(
    selectedTypeLabel: String,
    hiddenCount: Int,
    visibleCount: Int,
    onHideAll: () -> Unit,
    onUnhideAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = selectedTypeLabel,
            style = MaterialTheme.typography.labelMedium,
            color = AppColors.Brand
        )
        Text(
            text = stringResource(R.string.settings_category_visibility_summary_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = stringResource(R.string.settings_category_visibility_summary_body, hiddenCount),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SettingsActionButton(
                label = stringResource(R.string.settings_hide_all_categories),
                enabled = visibleCount > 0,
                emphasized = true,
                onClick = onHideAll
            )
            SettingsActionButton(
                label = stringResource(R.string.settings_unhide_all_categories),
                enabled = hiddenCount > 0,
                emphasized = false,
                onClick = onUnhideAll
            )
        }
    }
}

@Composable
private fun SettingsActionButton(
    label: String,
    enabled: Boolean,
    emphasized: Boolean,
    onClick: () -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                !enabled -> MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
                emphasized -> AppColors.Brand.copy(alpha = 0.18f)
                else -> MaterialTheme.colorScheme.surface
            },
            focusedContainerColor = if (enabled) {
                if (emphasized) AppColors.Brand.copy(alpha = 0.28f) else MaterialTheme.colorScheme.surface
            } else {
                MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)
            }
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                emphasized -> AppColors.Brand
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun CategoryProtectionCard(
    item: CategoryControlItem,
    onToggle: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent

    TvClickableSurface(
        onClick = onToggle,
        enabled = !item.category.isAdult,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .border(2.dp, borderColor, RoundedCornerShape(18.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TypeBadge(type = item.category.type)
                    if (item.category.isAdult) {
                        Text(
                            text = stringResource(R.string.parental_group_auto_protected),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Text(
                    text = item.category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = when {
                    item.category.isAdult -> stringResource(R.string.settings_category_status_auto_locked)
                    item.isProtected -> stringResource(R.string.settings_category_status_locked)
                    else -> stringResource(R.string.settings_category_status_unlocked)
                },
                style = MaterialTheme.typography.labelLarge,
                color = when {
                    item.category.isAdult -> MaterialTheme.colorScheme.error
                    item.isProtected -> AppColors.Brand
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

@Composable
private fun CategoryVisibilityCard(
    item: CategoryControlItem,
    onToggleHidden: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = if (isFocused) MaterialTheme.colorScheme.primary else Color.Transparent

    TvClickableSurface(
        onClick = onToggleHidden,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .border(2.dp, borderColor, RoundedCornerShape(18.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TypeBadge(type = item.category.type)
                    if (item.isHidden) {
                        Text(
                            text = stringResource(R.string.settings_category_status_hidden),
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.Brand
                        )
                    }
                }
                Text(
                    text = item.category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Text(
                text = if (item.isHidden) {
                    stringResource(R.string.settings_unhide_category)
                } else {
                    stringResource(R.string.settings_hide_category)
                },
                style = MaterialTheme.typography.labelLarge,
                color = if (item.isHidden) AppColors.Brand else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun EmptyCategoryMessage() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Text(
            text = stringResource(R.string.settings_category_controls_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TypeBadge(type: ContentType) {
    val (label, background, contentColor) = when (type) {
        ContentType.LIVE -> Triple("LIVE", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        ContentType.MOVIE -> Triple("MOVIE", MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
        ContentType.VOD -> Triple("VOD", MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.onSecondary)
        ContentType.SERIES,
        ContentType.SERIES_EPISODE -> Triple("SERIES", MaterialTheme.colorScheme.tertiary, MaterialTheme.colorScheme.onTertiary)
    }

    Box(
        modifier = Modifier.background(background, RoundedCornerShape(8.dp))
    ) {
        Text(
            text = label,
            color = contentColor,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
