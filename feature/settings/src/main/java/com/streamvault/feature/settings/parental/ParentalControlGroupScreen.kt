package com.streamvault.feature.settings.parental

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.feature.settings.R
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.core.ui.components.dialogs.PinDialog
import com.streamvault.core.ui.components.shell.UiDestination
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.Category
import com.streamvault.core.ui.interaction.TvClickableSurface
import kotlinx.coroutines.launch
import com.streamvault.feature.settings.presentation.SettingsDesignTokens
import com.streamvault.feature.settings.presentation.SettingsLocalHeader
import com.streamvault.feature.settings.presentation.SettingsFocusCoordinator
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.LocalAppSpacing

private enum class CategoryControlsMode {
    PROTECTION,
    VISIBILITY,
    CHANNEL_ORDER
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
    navigationDestinations: List<UiDestination> = emptyList(),
    viewModel: ParentalControlGroupViewModel = hiltViewModel(),
    channelOrderContent: (@Composable (Long, Category, Boolean, () -> Unit) -> Unit)? = null,
    channelOrderSummaryContent: (@Composable (Long, Category, () -> Unit, () -> Unit) -> Unit)? = null,
    categoryOrderContent: (@Composable (Long, () -> Unit) -> Unit)? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val searchWidth = if (screenWidth < 700.dp) {
        (screenWidth * 0.56f).coerceIn(180.dp, 260.dp)
    } else {
        420.dp
    }
    val firstContentFocusRequester = remember { FocusRequester() }
    val headerBackFocusRequester = remember { FocusRequester() }
    val focusCoordinator = remember { SettingsFocusCoordinator() }
    var firstContentPlaced by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var currentMode by rememberSaveable { mutableStateOf(CategoryControlsMode.PROTECTION) }
    var selectedType by rememberSaveable { mutableStateOf(ContentType.LIVE) }
    var categoryToReorder by remember { mutableStateOf<Category?>(null) }
    var openModePickerOnStart by remember { mutableStateOf(false) }
    var showCategoryOrder by remember { mutableStateOf(false) }
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var pinAction by rememberSaveable { mutableStateOf<CategoryPinAction?>(null) }
    var pinError by rememberSaveable { mutableStateOf<String?>(null) }
    val incorrectPinMessage = stringResource(R.string.home_incorrect_pin)
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

    BackHandler(enabled = categoryToReorder == null && !showCategoryOrder && !showPinDialog, onBack = onBack)

    LaunchedEffect(uiState.isLoading, firstContentPlaced) {
        if (!uiState.isLoading && firstContentPlaced) {
            val targetId = "parental.mode.protection"
            val intent = focusCoordinator.next(targetId)
            withFrameNanos { }
            if (focusCoordinator.canApply(intent, targetId)) {
                firstContentFocusRequester.requestFocus()
            }
        }
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }

    Box(
        modifier = Modifier.fillMaxSize().background(AppColors.Canvas),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = if (screenWidth < 700.dp) SettingsDesignTokens.compactInset else SettingsDesignTokens.tvHorizontalInset,
                    vertical = SettingsDesignTokens.tvVerticalInset,
                ),
            verticalArrangement = Arrangement.spacedBy(SettingsDesignTokens.space12),
        ) {
            SettingsLocalHeader(
                title = stringResource(R.string.settings_provider_category_controls_title),
                description = stringResource(R.string.settings_provider_category_controls_subtitle),
                parentTitle = stringResource(R.string.settings_privacy),
                onBack = onBack,
                backModifier = Modifier
                    .focusRequester(headerBackFocusRequester)
                    .focusProperties { down = firstContentFocusRequester },
            )
            val typeSelector: @Composable () -> Unit = {
                ContentTypeSelectorRow(selectedType = selectedType, onTypeSelected = {
                    selectedType = it
                    if (it != ContentType.LIVE && currentMode == CategoryControlsMode.CHANNEL_ORDER) {
                        currentMode = CategoryControlsMode.PROTECTION
                    }
                })
            }
            val search: @Composable () -> Unit = {
                SearchInput(value = uiState.searchQuery, onValueChange = viewModel::onSearchQueryChange,
                    placeholder = stringResource(R.string.settings_provider_category_controls_search),
                    onSearch = {}, modifier = Modifier.width(searchWidth))
            }
            if (screenWidth >= 700.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    typeSelector()
                    search()
                }
            } else {
                typeSelector()
                search()
            }
            if (uiState.isLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.chipGap),
                    verticalAlignment = Alignment.CenterVertically) {
                    ModeSelectorRow(
                        showChannelOrder = selectedType == ContentType.LIVE && channelOrderContent != null,
                        selectedMode = currentMode,
                        onModeSelected = { currentMode = it },
                        firstFocusModifier = Modifier.focusRequester(firstContentFocusRequester)
                            .focusProperties { up = headerBackFocusRequester }
                            .onGloballyPositioned { firstContentPlaced = true },
                    )
                    if (selectedType == ContentType.LIVE && categoryOrderContent != null) {
                        SettingsActionButton(label = stringResource(R.string.settings_category_reorder_categories),
                            enabled = true, emphasized = false, onClick = { showCategoryOrder = true })
                    }
                }
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(bottom = LocalAppSpacing.current.lg),
                    verticalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.xs),
                ) {
                item {
                    when (currentMode) {
                        CategoryControlsMode.PROTECTION -> ProtectionSummaryCard(
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
                            hiddenCount = hiddenCount,
                            visibleCount = visibleCount,
                            onHideAll = { viewModel.hideAllCategories(selectedType) },
                            onUnhideAll = { viewModel.unhideAllCategories(selectedType) }
                        )
                        CategoryControlsMode.CHANNEL_ORDER -> Unit
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
                            CategoryControlsMode.CHANNEL_ORDER -> {
                                if (channelOrderSummaryContent != null) {
                                    channelOrderSummaryContent.invoke(
                                        uiState.providerId,
                                        item.category,
                                        {
                                            categoryToReorder = item.category
                                            openModePickerOnStart = true
                                        },
                                        {
                                            categoryToReorder = item.category
                                            openModePickerOnStart = false
                                        }
                                    )
                                } else {
                                    CategoryModeChip(
                                        label = item.category.name,
                                        selected = false,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = {
                                            categoryToReorder = item.category
                                            openModePickerOnStart = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = SettingsDesignTokens.space16),
        )
    }

    categoryToReorder?.let { category ->
        channelOrderContent?.invoke(uiState.providerId, category, openModePickerOnStart) {
            categoryToReorder = null
            openModePickerOnStart = false
        }
    }
    if (showCategoryOrder) categoryOrderContent?.invoke(uiState.providerId) { showCategoryOrder = false }

    if (showPinDialog && pinAction != null) {
        PinDialog(
            cancelLabel = stringResource(R.string.pin_dialog_cancel),
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
                                pinError = incorrectPinMessage
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
    val spacing = LocalAppSpacing.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(spacing.chipGap)) {
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
    onModeSelected: (CategoryControlsMode) -> Unit,
    firstFocusModifier: Modifier = Modifier,
    showChannelOrder: Boolean = false,
) {
    val spacing = LocalAppSpacing.current
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.chipGap)) {
        CategoryModeChip(
            label = stringResource(R.string.settings_category_mode_protection),
            selected = selectedMode == CategoryControlsMode.PROTECTION,
            onClick = { onModeSelected(CategoryControlsMode.PROTECTION) },
            modifier = firstFocusModifier,
        )
        CategoryModeChip(
            label = stringResource(R.string.settings_category_mode_visibility),
            selected = selectedMode == CategoryControlsMode.VISIBILITY,
            onClick = { onModeSelected(CategoryControlsMode.VISIBILITY) }
        )
        if (showChannelOrder) CategoryModeChip(
            label = stringResource(R.string.settings_category_reorder_channels),
            selected = selectedMode == CategoryControlsMode.CHANNEL_ORDER,
            onClick = { onModeSelected(CategoryControlsMode.CHANNEL_ORDER) }
        )

    }
}

@Composable
private fun CategoryModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    val spacing = LocalAppSpacing.current
    var focused by remember { mutableStateOf(false) }
    val chipShape = RoundedCornerShape(SettingsDesignTokens.groupRadius)
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier.semantics { this.selected = selected }.onFocusChanged { focused = it.isFocused }
            .border(SettingsDesignTokens.focusStroke, if (focused) AppColors.Focus else Color.Transparent, chipShape),
        shape = ClickableSurfaceDefaults.shape(chipShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) colors.focusedSurface else colors.groupSurface,
            focusedContainerColor = colors.focusedSurface,
        ),
        border = ClickableSurfaceDefaults.border(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(Modifier.padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
            if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = colors.primaryText)
            Text(text = label, style = MaterialTheme.typography.labelLarge, color = colors.primaryText,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ProtectionSummaryCard(
    hasChanges: Boolean, changeCount: Int, hasParentalPin: Boolean,
    onSave: () -> Unit, onReset: () -> Unit
) {
    val saveLabel = if (hasChanges) stringResource(R.string.settings_category_protection_save, changeCount)
        else stringResource(R.string.settings_category_protection_save_idle)
    CategorySummaryPanel(
        title = stringResource(R.string.settings_category_protection_summary_title),
        body = stringResource(if (hasParentalPin) R.string.settings_category_protection_summary_body
            else R.string.settings_category_protection_summary_body_no_pin),
        actions = {
            SettingsActionButton(label = saveLabel, enabled = hasChanges, emphasized = true, onClick = onSave)
            SettingsActionButton(label = stringResource(R.string.settings_category_protection_reset),
                enabled = hasChanges, emphasized = false, onClick = onReset)
        }
    )
}

@Composable
private fun VisibilitySummaryCard(
    hiddenCount: Int, visibleCount: Int,
    onHideAll: () -> Unit, onUnhideAll: () -> Unit
) {
    CategorySummaryPanel(
        title = stringResource(R.string.settings_category_visibility_summary_title),
        body = stringResource(R.string.settings_category_visibility_summary_body, hiddenCount),
        actions = {
            SettingsActionButton(label = stringResource(R.string.settings_hide_all_categories),
                enabled = visibleCount > 0, emphasized = true, onClick = onHideAll)
            SettingsActionButton(label = stringResource(R.string.settings_unhide_all_categories),
                enabled = hiddenCount > 0, emphasized = false, onClick = onUnhideAll)
        }
    )
}

@Composable
private fun CategorySummaryPanel(title: String, body: String, actions: @Composable () -> Unit) {
    val spacing = LocalAppSpacing.current
    val colors = SettingsDesignTokens.colors(AppColors.current)
    val wide = LocalConfiguration.current.screenWidthDp >= 900
    Column(Modifier.fillMaxWidth().background(colors.groupSurface, RoundedCornerShape(SettingsDesignTokens.groupRadius))
        .padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.sm)) {
        if (wide) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(spacing.md)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, color = colors.primaryText,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText,
                        maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(spacing.xs)) { actions() }
            }
        } else {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.primaryText,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.secondaryText,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(spacing.xs)) { actions() }
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
    val colors = SettingsDesignTokens.colors(AppColors.current)
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(SettingsDesignTokens.groupRadius)
    TvClickableSurface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.onFocusChanged { focused = it.isFocused }
            .border(SettingsDesignTokens.focusStroke, if (focused) AppColors.Focus else Color.Transparent, shape),
        shape = ClickableSurfaceDefaults.shape(shape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = when {
                !enabled -> colors.groupSurface.copy(alpha = 0.55f)
                emphasized -> AppColors.SurfaceElevated
                else -> AppColors.SurfaceElevated
            },
            focusedContainerColor = if (enabled) {
                colors.focusedSurface
            } else {
                colors.groupSurface.copy(alpha = 0.55f)
            }
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = when {
                !enabled -> colors.disabledText
                emphasized -> colors.primaryText
                else -> colors.primaryText
            },
            modifier = Modifier.padding(horizontal = SettingsDesignTokens.space16, vertical = SettingsDesignTokens.space8)
        )
    }
}

@Composable
private fun CategoryProtectionCard(
    item: CategoryControlItem,
    onToggle: () -> Unit
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = if (isFocused) colors.focusOutline else Color.Transparent

    TvClickableSurface(
        onClick = onToggle,
        enabled = !item.category.isAdult,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .border(SettingsDesignTokens.focusStroke, borderColor, RoundedCornerShape(SettingsDesignTokens.groupRadius)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(SettingsDesignTokens.groupRadius)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.groupSurface,
            focusedContainerColor = colors.focusedSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsDesignTokens.space16, vertical = SettingsDesignTokens.space8),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primaryText,
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
                color = colors.primaryText,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CategoryVisibilityCard(
    item: CategoryControlItem,
    onToggleHidden: () -> Unit
) {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    var isFocused by remember { mutableStateOf(false) }
    val borderColor = if (isFocused) colors.focusOutline else Color.Transparent

    TvClickableSurface(
        onClick = onToggleHidden,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused }
            .border(SettingsDesignTokens.focusStroke, borderColor, RoundedCornerShape(SettingsDesignTokens.groupRadius)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(SettingsDesignTokens.groupRadius)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = colors.groupSurface,
            focusedContainerColor = colors.focusedSurface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SettingsDesignTokens.space16, vertical = SettingsDesignTokens.space8),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = item.category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.primaryText,
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
                color = colors.primaryText,
                maxLines = 2, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EmptyCategoryMessage() {
    val colors = SettingsDesignTokens.colors(AppColors.current)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.groupSurface, RoundedCornerShape(SettingsDesignTokens.groupRadius))
            .padding(SettingsDesignTokens.space16)
    ) {
        Text(
            text = stringResource(R.string.settings_category_controls_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.secondaryText
        )
    }
}
