package com.streamvault.app.ui.screens.home

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.streamvault.app.ui.components.SearchInput
import com.streamvault.app.ui.components.SelectionChip
import com.streamvault.app.ui.components.SelectionChipRow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.Icons
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import com.streamvault.app.device.rememberIsTelevisionDevice
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.streamvault.app.ui.components.CategoryRow
import com.streamvault.app.ui.components.ChannelCard
import com.streamvault.app.ui.components.LiveSourceSwitcher
import com.streamvault.app.ui.components.shell.ContentMetadataStrip
import com.streamvault.app.ui.components.shell.LiveChannelRowSurface
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.components.TvEmptyState
import com.streamvault.app.ui.components.dialogs.CategoryOptionsDialog
import com.streamvault.app.ui.components.dialogs.HiddenCategoriesDialog
import com.streamvault.app.ui.components.dialogs.HiddenChannelsDialog
import com.streamvault.app.ui.components.dialogs.PinDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.components.dialogs.RenameGroupDialog
import com.streamvault.app.ui.components.ReorderTopBar
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.app.ui.design.FocusRestoreHost
import com.streamvault.app.ui.design.requestFocusSafely
import androidx.activity.compose.BackHandler
import com.streamvault.app.ui.model.LiveTvQuickFilterVisibilityMode
import com.streamvault.app.ui.theme.*
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LegacyProvider as Provider
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import com.streamvault.app.ui.model.LiveTvChannelMode
import com.streamvault.app.ui.screens.multiview.MultiViewViewModel
import com.streamvault.app.ui.screens.multiview.MultiViewPlannerDialog
import com.streamvault.app.navigation.Routes
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import androidx.compose.ui.viewinterop.AndroidView
import com.streamvault.player.PlayerSurfaceResizeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvIconButton
import com.streamvault.app.ui.remote.LiveBrowseRemoteShortcutHandler
import com.streamvault.app.ui.remote.dispatchLiveBrowseRemoteShortcut
import com.streamvault.app.ui.remote.remoteColorButtonForKeyCode
import com.streamvault.domain.model.RemoteShortcutProfile

private enum class FocusRestoreTarget {
    CATEGORY,
    CHANNEL
}

private sealed interface FocusedRemoteShortcutTarget {
    data class CategoryTarget(val category: Category) : FocusedRemoteShortcutTarget
    data class ChannelTarget(val channel: Channel) : FocusedRemoteShortcutTarget
}

private const val HOME_ALL_FILTER_KEY = "__all_categories__"

@Composable
private fun HomeLoadingPane(
    message: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = Color.White)
            Text(
                text = message,
                color = Color.White.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}


// ── Screen ─────────────────────────────────────────────────────────




// ── Screen ─────────────────────────────────────────────────────────

@Composable
fun HomeScreen(
    onChannelClick: (Channel, Category?, Provider?, Long?, Long?) -> Unit,
    onNavigate: (String) -> Unit,
    currentRoute: String,
    initialCategoryId: Long? = null,
    viewModel: HomeViewModel = hiltViewModel(),
    multiViewViewModel: MultiViewViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val remoteShortcutPreferences by viewModel.remoteShortcutPreferences.collectAsStateWithLifecycle()
    val providerNameById = remember(uiState.allProviders) {
        uiState.allProviders.associateBy({ it.id }, { it.name })
    }
    val resolveProviderForChannel: (Channel) -> Provider? = remember(uiState.allProviders, uiState.provider) {
        { channel -> uiState.allProviders.firstOrNull { it.id == channel.providerId } ?: uiState.provider }
    }
    val shouldShowLiveSourceSwitcher = uiState.showLiveSourceSwitcher && uiState.liveSourceOptions.isNotEmpty()
    val isReorderMode = uiState.isChannelReorderMode
    val isProMode = uiState.liveTvChannelMode == LiveTvChannelMode.PRO
    val isDenseMode = uiState.liveTvChannelMode != LiveTvChannelMode.COMFORTABLE
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val sidebarWidth = if (screenWidth < 900.dp) {
        (screenWidth * 0.36f).coerceIn(188.dp, 220.dp)
    } else if (!isTelevisionDevice && screenWidth < 1280.dp) {
        (screenWidth * 0.28f).coerceIn(220.dp, 252.dp)
    } else {
        272.dp
    }
    val channelSearchWidth = if (screenWidth < 900.dp) {
        (screenWidth * 0.34f).coerceIn(170.dp, 220.dp)
    } else if (!isTelevisionDevice && screenWidth < 1280.dp) {
        (screenWidth * 0.28f).coerceIn(220.dp, 280.dp)
    } else if (isProMode) {
        320.dp
    } else if (isDenseMode) {
        300.dp
    } else {
        340.dp
    }
    val channelRowHeight = when (uiState.liveTvChannelMode) {
        LiveTvChannelMode.COMFORTABLE -> 92.dp
        LiveTvChannelMode.COMPACT -> 54.dp
        LiveTvChannelMode.PRO -> 52.dp
    }
    val channelListSpacing = when (uiState.liveTvChannelMode) {
        LiveTvChannelMode.COMFORTABLE -> 8.dp
        LiveTvChannelMode.COMPACT -> 2.dp
        LiveTvChannelMode.PRO -> 2.dp
    }
    val previewChannel = remember(uiState.filteredChannels, uiState.previewChannelId) {
        uiState.filteredChannels.firstOrNull { it.id == uiState.previewChannelId }
    }
    val snackbarHostState = remember { SnackbarHostState() }

    // Split screen state
    val hasSplitChannels = uiState.multiviewChannelCount > 0
    var showSplitManagerDialog by rememberSaveable { mutableStateOf(false) }
    var pendingSplitPlannerChannel by remember { mutableStateOf<Channel?>(null) }
    var showAddQuickFilterDialog by rememberSaveable { mutableStateOf(false) }
    var showHiddenCategoriesDialog by rememberSaveable { mutableStateOf(false) }
    var showHiddenChannelsDialog by rememberSaveable { mutableStateOf(false) }
    var isCategorySidebarHidden by rememberSaveable { mutableStateOf(false) }
    var revealSidebarNonce by remember { mutableStateOf(0) }

    // Parental Control State
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var pinError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUnlockCategory by remember { mutableStateOf<Category?>(null) }
    var pendingUnlockChannel by remember { mutableStateOf<Channel?>(null) }
    var pendingLockToggleCategory by remember { mutableStateOf<Category?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(initialCategoryId) {
        viewModel.setPreferredInitialCategory(initialCategoryId)
    }

    LaunchedEffect(uiState.userMessage) {
        uiState.userMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.userMessageShown()
        }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        viewModel.clearPreview()
    }

    DisposableEffect(viewModel) {
        onDispose {
            viewModel.clearPreview()
        }
    }

    val hasActivePreview = uiState.previewPlayerEngine != null
    DisposableEffect(hasActivePreview) {
        val window = (context as? android.app.Activity)?.window
        if (hasActivePreview) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            if (hasActivePreview) {
                window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
    }

        val hasOverlay = showPinDialog || showSplitManagerDialog || pendingSplitPlannerChannel != null ||
        showAddQuickFilterDialog || showHiddenCategoriesDialog || showHiddenChannelsDialog ||
        uiState.showDialog || uiState.showDeleteGroupDialog ||
        uiState.showRenameGroupDialog || uiState.selectedCategoryForOptions != null ||
        isReorderMode

    BackHandler(enabled = hasOverlay) {
        when {
            showAddQuickFilterDialog -> showAddQuickFilterDialog = false
            showHiddenCategoriesDialog -> showHiddenCategoriesDialog = false
            showHiddenChannelsDialog -> showHiddenChannelsDialog = false
            showPinDialog -> {
                showPinDialog = false
                pinError = null
                pendingUnlockCategory = null
                pendingUnlockChannel = null
            }
            pendingSplitPlannerChannel != null -> pendingSplitPlannerChannel = null
            uiState.showDeleteGroupDialog -> viewModel.cancelDeleteGroup()
            uiState.showRenameGroupDialog -> viewModel.cancelRenameGroup()
            uiState.selectedCategoryForOptions != null -> viewModel.dismissCategoryOptions()
            uiState.showDialog -> viewModel.onDismissDialog()
            showSplitManagerDialog -> showSplitManagerDialog = false
            isReorderMode -> viewModel.exitChannelReorderMode()
        }
    }

    // Reveal the categories sidebar again on Back (overlays keep their own handler).
    BackHandler(enabled = isCategorySidebarHidden && !hasOverlay) {
        isCategorySidebarHidden = false
        revealSidebarNonce++
    }

    HomeDialogsHost(
        uiState = uiState,
        multiViewViewModel = multiViewViewModel,
        viewModel = viewModel,
        showPinDialog = showPinDialog,
        pinError = pinError,
        pendingUnlockCategory = pendingUnlockCategory,
        pendingUnlockChannel = pendingUnlockChannel,
        pendingLockToggleCategory = pendingLockToggleCategory,
        showAddQuickFilterDialog = showAddQuickFilterDialog,
        showSplitManagerDialog = showSplitManagerDialog,
        pendingSplitPlannerChannel = pendingSplitPlannerChannel,
        onShowPinDialogChange = { showPinDialog = it },
        onPinErrorChange = { pinError = it },
        onPendingUnlockCategoryChange = { pendingUnlockCategory = it },
        onPendingUnlockChannelChange = { pendingUnlockChannel = it },
        onPendingLockToggleCategoryChange = { pendingLockToggleCategory = it },
        onShowAddQuickFilterDialogChange = { showAddQuickFilterDialog = it },
        onShowSplitManagerDialogChange = { showSplitManagerDialog = it },
        onPendingSplitPlannerChannelChange = { pendingSplitPlannerChannel = it },
        onChannelClick = onChannelClick,
        resolveProviderForChannel = resolveProviderForChannel,
        onNavigate = onNavigate,
        scope = scope
    )

    LaunchedEffect(uiState.hiddenLiveCategories.isEmpty()) {
        if (showHiddenCategoriesDialog && uiState.hiddenLiveCategories.isEmpty()) {
            showHiddenCategoriesDialog = false
        }
    }

    if (showHiddenCategoriesDialog) {
        HiddenCategoriesDialog(
            hiddenCategories = uiState.hiddenLiveCategories,
            onUnhide = { viewModel.unhideCategory(it) },
            onUnhideAll = {
                viewModel.unhideAllLiveCategories()
                showHiddenCategoriesDialog = false
            },
            onDismiss = { showHiddenCategoriesDialog = false }
        )
    }

    val hiddenChannelsLiveTv by viewModel.hiddenChannelsLiveTv.collectAsStateWithLifecycle()

    LaunchedEffect(hiddenChannelsLiveTv.isEmpty()) {
        if (showHiddenChannelsDialog && hiddenChannelsLiveTv.isEmpty()) {
            showHiddenChannelsDialog = false
        }
    }

    if (showHiddenChannelsDialog) {
        HiddenChannelsDialog(
            hiddenChannels = hiddenChannelsLiveTv,
            onUnhide = { viewModel.unhideChannel(it) },
            onUnhideAll = {
                viewModel.unhideAllChannels()
                showHiddenChannelsDialog = false
            },
            onDismiss = { showHiddenChannelsDialog = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AppScreenScaffold(
            currentRoute = currentRoute,
            onNavigate = onNavigate,
            title = stringResource(R.string.nav_live_tv),
            subtitle = uiState.activeLiveSourceTitle.ifBlank { uiState.provider?.name },
            navigationChrome = AppNavigationChrome.TopBar,
            compactHeader = true,
            showScreenHeader = false
        ) {
            if (isReorderMode) {
                ReorderTopBar(
                    categoryName = uiState.reorderCategory?.name ?: uiState.selectedCategory?.name ?: "Channels",
                    onSave = { viewModel.saveChannelReorder() },
                    onCancel = { viewModel.exitChannelReorderMode() },
                    subtitle = stringResource(R.string.live_reorder_subtitle)
                )
            }

            if (uiState.allProviders.isEmpty() && !uiState.isCategoriesLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    TvEmptyState(
                        title = stringResource(R.string.home_add_first_provider),
                        subtitle = stringResource(R.string.home_add_first_provider_subtitle)
                    )
                }
            } else if (uiState.isCategoriesLoading && uiState.categories.isEmpty()) {
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .width(sidebarWidth)
                            .fillMaxHeight()
                            .background(SurfaceElevated)
                            .padding(horizontal = 16.dp, vertical = 24.dp)
                    ) {
                        HomeLoadingPane(
                            message = stringResource(R.string.home_loading_categories)
                        )
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(24.dp)
                    ) {
                        HomeLoadingPane(
                            message = stringResource(R.string.home_loading_channels)
                        )
                    }
                }
            } else {
                val channelSearchFocusRequester = remember { FocusRequester() }
                val categoryFocusRequesters = remember { mutableMapOf<Long, FocusRequester>() }
                val channelFocusRequesters = remember { mutableMapOf<Long, FocusRequester>() }
                val visibleCategories = remember(uiState.categories, uiState.categorySearchQuery) {
                    uiState.categories
                        .asSequence()
                        .filter {
                            uiState.categorySearchQuery.isEmpty() ||
                                it.name.contains(uiState.categorySearchQuery, ignoreCase = true)
                        }
                        .toList()
                }
                val isCategoryLocked: (Category) -> Boolean = remember(uiState.parentalControlLevel, uiState.unlockedCategoryIds) {
                    { category ->
                        (category.isAdult || category.isUserProtected) &&
                            uiState.parentalControlLevel in 1..2 &&
                            kotlin.math.abs(category.id) !in uiState.unlockedCategoryIds
                    }
                }
                val isChannelLocked: (Channel) -> Boolean = remember(
                    uiState.parentalControlLevel,
                    uiState.unlockedCategoryIds,
                    uiState.categories,
                    uiState.selectedCategory?.id,
                    uiState.selectedCategory?.isAdult,
                    uiState.selectedCategory?.isUserProtected
                ) {
                    { channel ->
                        val selectedCategory = uiState.selectedCategory
                        val channelCategoryId = channel.categoryId
                        val sourceCategory = uiState.categories.firstOrNull { it.id == channelCategoryId }
                        val unlockedByChannelCategory =
                            channelCategoryId != null && kotlin.math.abs(channelCategoryId) in uiState.unlockedCategoryIds
                        val unlockedBySelectedCategory =
                            selectedCategory != null && kotlin.math.abs(selectedCategory.id) in uiState.unlockedCategoryIds
                        val unlocked = unlockedByChannelCategory || unlockedBySelectedCategory
                        (
                            channel.isAdult ||
                                channel.isUserProtected ||
                                (selectedCategory?.isAdult == true) ||
                                (selectedCategory?.isUserProtected == true) ||
                                (sourceCategory?.isAdult == true) ||
                                (sourceCategory?.isUserProtected == true)
                            ) &&
                            uiState.parentalControlLevel in 1..2 &&
                            !unlocked
                    }
                }
                val unlockedVisibleCategories = remember(
                    visibleCategories,
                    uiState.parentalControlLevel,
                    uiState.unlockedCategoryIds
                ) {
                    visibleCategories.filterNot(isCategoryLocked)
                }
                var lastFocusedCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
                var lastFocusedChannelId by rememberSaveable { mutableStateOf<Long?>(null) }
                var preferredRestoreTarget by rememberSaveable { mutableStateOf(FocusRestoreTarget.CHANNEL.name) }
                var pendingRestoreTarget by remember { mutableStateOf<FocusRestoreTarget?>(null) }
                var focusRestoreNonce by rememberSaveable { mutableStateOf(0) }
                var pendingCategoryContentJumpCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
                var focusedRemoteShortcutTarget by remember { mutableStateOf<FocusedRemoteShortcutTarget?>(null) }

                fun requestChannelFocus(channelId: Long?): Boolean {
                    val resolvedChannelId = channelId ?: return false
                    return channelFocusRequesters[resolvedChannelId]
                        ?.requestFocusSafely(tag = "HomeScreen", target = "Channel $resolvedChannelId")
                        ?: false
                }

                fun requestCategoryFocus(categoryId: Long?): Boolean {
                    val resolvedCategoryId = categoryId ?: return false
                    return categoryFocusRequesters[resolvedCategoryId]
                        ?.requestFocusSafely(tag = "HomeScreen", target = "Category $resolvedCategoryId")
                        ?: false
                }

                fun requestChannelFocusFromCategory(): Boolean {
                    if (uiState.filteredChannels.isEmpty()) return false
                    val preferredChannelId = lastFocusedChannelId
                        ?.takeIf { channelId -> uiState.filteredChannels.any { it.id == channelId } }
                        ?: uiState.filteredChannels.first().id
                    lastFocusedChannelId = preferredChannelId
                    preferredRestoreTarget = FocusRestoreTarget.CHANNEL.name
                    pendingRestoreTarget = FocusRestoreTarget.CHANNEL
                    focusRestoreNonce++
                    val focusedImmediately = requestChannelFocus(preferredChannelId)
                    scope.launch {
                        kotlinx.coroutines.delay(60)
                        val focused = requestChannelFocus(preferredChannelId)
                        if (!focused) {
                            pendingRestoreTarget = FocusRestoreTarget.CHANNEL
                            focusRestoreNonce++
                        }
                    }
                    return focusedImmediately
                }

                val displayedCategory = uiState.selectedCategory?.takeIf { !isCategoryLocked(it) }
                val hasBlockedCategorySearch =
                    uiState.categorySearchQuery.isNotBlank() &&
                        visibleCategories.isNotEmpty() &&
                        unlockedVisibleCategories.isEmpty()

                LaunchedEffect(uiState.categories) {
                    val validIds = uiState.categories.mapTo(mutableSetOf()) { it.id }
                    categoryFocusRequesters.keys.retainAll(validIds)
                }

                LaunchedEffect(uiState.filteredChannels) {
                    val validIds = uiState.filteredChannels.mapTo(mutableSetOf()) { it.id }
                    channelFocusRequesters.keys.retainAll(validIds)
                }

                LaunchedEffect(
                    uiState.isLoading,
                    uiState.filteredChannels,
                    uiState.selectedCategory?.id,
                    pendingCategoryContentJumpCategoryId
                ) {
                    val pendingCategoryId = pendingCategoryContentJumpCategoryId ?: return@LaunchedEffect
                    if (uiState.selectedCategory?.id != pendingCategoryId) {
                        pendingCategoryContentJumpCategoryId = null
                        return@LaunchedEffect
                    }
                    if (uiState.isLoading) return@LaunchedEffect

                    if (uiState.filteredChannels.isNotEmpty()) {
                        requestChannelFocusFromCategory()
                    }
                    pendingCategoryContentJumpCategoryId = null
                }

                // Re-focus the last category when the sidebar is revealed with Back.
                LaunchedEffect(revealSidebarNonce) {
                    if (revealSidebarNonce == 0) return@LaunchedEffect
                    preferredRestoreTarget = FocusRestoreTarget.CATEGORY.name
                    kotlinx.coroutines.delay(120)
                    var restored = requestCategoryFocus(lastFocusedCategoryId)
                    if (!restored) {
                        kotlinx.coroutines.delay(150)
                        restored = requestCategoryFocus(lastFocusedCategoryId)
                    }
                    if (!restored) {
                        val fallbackCategoryId =
                            (unlockedVisibleCategories.firstOrNull() ?: visibleCategories.firstOrNull())?.id
                        requestCategoryFocus(fallbackCategoryId)
                    }
                }

                LaunchedEffect(uiState.categories, uiState.selectedCategory?.id, uiState.parentalControlLevel, isReorderMode) {
                    if (isReorderMode) return@LaunchedEffect
                    val selectedCategory = uiState.selectedCategory ?: return@LaunchedEffect
                    if (!isCategoryLocked(selectedCategory)) {
                        return@LaunchedEffect
                    }
                    val fallbackCategory = uiState.categories.firstOrNull { !isCategoryLocked(it) } ?: return@LaunchedEffect
                    if (fallbackCategory.id != selectedCategory.id) {
                        viewModel.selectCategory(fallbackCategory)
                    }
                }

                LaunchedEffect(
                    uiState.shouldAutoFocusFirstChannelOnEntry,
                    uiState.selectedCategory?.id,
                    uiState.isLoading,
                    uiState.filteredChannels,
                    hasOverlay,
                    isReorderMode
                ) {
                    if (!uiState.shouldAutoFocusFirstChannelOnEntry || hasOverlay || isReorderMode) {
                        return@LaunchedEffect
                    }
                    if (uiState.isLoading) return@LaunchedEffect

                    val firstChannelId = uiState.filteredChannels.firstOrNull()?.id
                    if (firstChannelId != null) {
                        lastFocusedChannelId = firstChannelId
                        preferredRestoreTarget = FocusRestoreTarget.CHANNEL.name
                        pendingRestoreTarget = FocusRestoreTarget.CHANNEL
                        focusRestoreNonce++
                    }
                    viewModel.consumeInitialChannelFocusRequest()
                }

                LaunchedEffect(
                    uiState.showDialog,
                    showPinDialog,
                    showAddQuickFilterDialog,
                    uiState.showDeleteGroupDialog,
                    uiState.selectedCategoryForOptions,
                    uiState.showRenameGroupDialog,
                    showSplitManagerDialog,
                    pendingSplitPlannerChannel,
                    isReorderMode
                ) {
                    val modalClosed =
                        !uiState.showDialog &&
                        !showPinDialog &&
                        !showAddQuickFilterDialog &&
                        !uiState.showDeleteGroupDialog &&
                        !uiState.showRenameGroupDialog &&
                        !showSplitManagerDialog &&
                        pendingSplitPlannerChannel == null &&
                        uiState.selectedCategoryForOptions == null &&
                        !isReorderMode

                    if (!modalClosed) return@LaunchedEffect

                    val canRestoreChannel = lastFocusedChannelId != null &&
                        uiState.filteredChannels.any { it.id == lastFocusedChannelId }
                    val canRestoreCategory = lastFocusedCategoryId != null &&
                        visibleCategories.any { it.id == lastFocusedCategoryId }
                    val restoreTarget = runCatching {
                        FocusRestoreTarget.valueOf(preferredRestoreTarget)
                    }.getOrDefault(FocusRestoreTarget.CHANNEL)

                    pendingRestoreTarget = when {
                        isCategorySidebarHidden && canRestoreChannel -> FocusRestoreTarget.CHANNEL
                        isCategorySidebarHidden -> null
                        restoreTarget == FocusRestoreTarget.CATEGORY && canRestoreCategory -> FocusRestoreTarget.CATEGORY
                        canRestoreChannel -> FocusRestoreTarget.CHANNEL
                        canRestoreCategory -> FocusRestoreTarget.CATEGORY
                        uiState.filteredChannels.isNotEmpty() -> FocusRestoreTarget.CHANNEL
                        visibleCategories.isNotEmpty() -> FocusRestoreTarget.CATEGORY
                        else -> null
                    }
                    if (pendingRestoreTarget != null) {
                        focusRestoreNonce++
                    }
                }

                LaunchedEffect(focusRestoreNonce, uiState.categories, uiState.filteredChannels) {
                    val restoreTarget = pendingRestoreTarget ?: return@LaunchedEffect
                    kotlinx.coroutines.delay(80)
                    val restored = when (restoreTarget) {
                        FocusRestoreTarget.CHANNEL -> requestChannelFocus(lastFocusedChannelId)
                        FocusRestoreTarget.CATEGORY -> requestCategoryFocus(lastFocusedCategoryId)
                    }
                    if (!restored) {
                        when (restoreTarget) {
                            FocusRestoreTarget.CHANNEL -> {
                                val fallbackChannelId = uiState.filteredChannels.firstOrNull()?.id
                                if (fallbackChannelId != null) {
                                    requestChannelFocus(fallbackChannelId)
                                } else {
                                    val fallbackCategoryId = (unlockedVisibleCategories.firstOrNull() ?: visibleCategories.firstOrNull())?.id
                                    requestCategoryFocus(fallbackCategoryId)
                                }
                            }
                            FocusRestoreTarget.CATEGORY -> {
                                val fallbackCategoryId = (unlockedVisibleCategories.firstOrNull() ?: visibleCategories.firstOrNull())?.id
                                requestCategoryFocus(fallbackCategoryId)
                            }
                        }
                    }
                    pendingRestoreTarget = null
                }

                FocusRestoreHost(
                    enabled = !hasOverlay && !uiState.isLoading && uiState.categories.isNotEmpty(),
                    onRestore = {
                        val restoreTarget = runCatching {
                            FocusRestoreTarget.valueOf(preferredRestoreTarget)
                        }.getOrDefault(FocusRestoreTarget.CHANNEL)

                        val canRestoreChannel = lastFocusedChannelId != null &&
                            uiState.filteredChannels.any { it.id == lastFocusedChannelId }
                        val canRestoreCategory = lastFocusedCategoryId != null &&
                            visibleCategories.any { it.id == lastFocusedCategoryId }

                        pendingRestoreTarget = when {
                            isCategorySidebarHidden && canRestoreChannel -> FocusRestoreTarget.CHANNEL
                            isCategorySidebarHidden -> null
                            restoreTarget == FocusRestoreTarget.CATEGORY && canRestoreCategory -> FocusRestoreTarget.CATEGORY
                            canRestoreChannel -> FocusRestoreTarget.CHANNEL
                            canRestoreCategory -> FocusRestoreTarget.CATEGORY
                            uiState.filteredChannels.isNotEmpty() -> FocusRestoreTarget.CHANNEL
                            visibleCategories.isNotEmpty() -> FocusRestoreTarget.CATEGORY
                            else -> null
                        }
                        if (pendingRestoreTarget != null) {
                            focusRestoreNonce++
                        }
                    }
                ) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            if (hasOverlay || isReorderMode) return@onPreviewKeyEvent false
                            val button = remoteColorButtonForKeyCode(event.nativeKeyEvent.keyCode) ?: return@onPreviewKeyEvent false
                            val action = remoteShortcutPreferences.resolvedAction(RemoteShortcutProfile.BROWSE, button)
                            val handler = when (val target = focusedRemoteShortcutTarget) {
                                is FocusedRemoteShortcutTarget.ChannelTarget -> LiveBrowseRemoteShortcutHandler.Channel(
                                    onToggleFavorite = {
                                        if (target.channel.isFavorite) viewModel.removeFavorite(target.channel)
                                        else viewModel.addFavorite(target.channel)
                                    },
                                    onPlayChannel = {
                                        onChannelClick(
                                            target.channel,
                                            uiState.selectedCategory,
                                            resolveProviderForChannel(target.channel),
                                            (uiState.activeLiveSource as? ActiveLiveSource.CombinedM3uSource)?.profileId,
                                            uiState.selectedCombinedSourceProviderId
                                        )
                                    },
                                    onAddToSplitScreen = { pendingSplitPlannerChannel = target.channel },
                                    onOpenGuide = { onNavigate(Routes.EPG) }
                                )
                                is FocusedRemoteShortcutTarget.CategoryTarget -> {
                                    val category = target.category
                                    val isLockedCategory = isCategoryLocked(category)
                                    if (isLockedCategory && action in setOf(
                                            com.streamvault.domain.model.RemoteShortcutAction.PIN_CATEGORY,
                                            com.streamvault.domain.model.RemoteShortcutAction.HIDE_CATEGORY
                                        )
                                    ) {
                                        null
                                    } else {
                                        LiveBrowseRemoteShortcutHandler.Category(
                                            onPinCategory = { viewModel.toggleCategoryPinned(category) },
                                            onToggleCategoryLock = {
                                                pendingLockToggleCategory = category
                                                showPinDialog = true
                                            },
                                            onHideCategory = { viewModel.hideCategory(category) },
                                            onOpenGuide = { onNavigate(Routes.EPG) }
                                        )
                                    }
                                }
                                null -> null
                            } ?: return@onPreviewKeyEvent false
                            dispatchLiveBrowseRemoteShortcut(action, handler)
                        }
                ) {
                    // Sidebar - Categories (hidden after picking a category; Back brings it back)
                    val categorySearchFocusRequester = remember { FocusRequester() }
                    val focusManager = LocalFocusManager.current
                    val categoryListState = rememberLazyListState()

                    AnimatedVisibility(
                        visible = !isCategorySidebarHidden,
                        enter = fadeIn(animationSpec = tween(200)) +
                            expandHorizontally(animationSpec = tween(200), expandFrom = Alignment.Start),
                        exit = fadeOut(animationSpec = tween(200)) +
                            shrinkHorizontally(animationSpec = tween(200), shrinkTowards = Alignment.Start)
                    ) {
                    Column(
                        modifier = Modifier
                            .width(sidebarWidth)
                            .fillMaxHeight()
                            .background(SurfaceElevated.copy(alpha = 0.88f), RoundedCornerShape(20.dp))
                            .padding(top = 10.dp)
                            .focusGroup()
                    ) {
                        // Sticky Header Part
                        Column(modifier = Modifier.padding(horizontal = 10.dp)) {
                            var showQuickFiltersDrawer by rememberSaveable(uiState.savedCategoryFilters) {
                                mutableStateOf(false)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp, start = 4.dp, end = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.home_categories_title),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = OnSurface,
                                    maxLines = 1,
                                    modifier = Modifier.weight(1f)
                                )
                                if (shouldShowLiveSourceSwitcher) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    LiveSourceSwitcher(
                                        currentSource = uiState.activeLiveSource,
                                        options = uiState.liveSourceOptions,
                                        onSourceSelected = viewModel::switchLiveSource,
                                        compact = true
                                    )
                                }
                            }
                            SearchInput(
                                value = uiState.categorySearchQuery,
                                onValueChange = {
                                    if (!isReorderMode) {
                                        viewModel.updateCategorySearchQuery(it)
                                    }
                                },
                                placeholder = stringResource(R.string.home_search_categories),
                                focusRequester = categorySearchFocusRequester,
                                modifier = Modifier.padding(bottom = 10.dp),
                                enabled = !isReorderMode
                            )
                            val shouldShowQuickFiltersControl = when (uiState.liveTvQuickFilterVisibilityMode) {
                                LiveTvQuickFilterVisibilityMode.HIDE -> false
                                LiveTvQuickFilterVisibilityMode.SHOW_WHEN_FILTERS_AVAILABLE -> uiState.savedCategoryFilters.isNotEmpty()
                                LiveTvQuickFilterVisibilityMode.ALWAYS_VISIBLE -> true
                            }
                            if (shouldShowQuickFiltersControl) {
                                val activeSavedFilter = uiState.activeCategoryFilter
                                val filterSubtitle = when {
                                    uiState.categorySearchQuery.isBlank() -> {
                                        stringResource(R.string.home_quick_filters_showing_all)
                                    }
                                    activeSavedFilter != null -> {
                                        stringResource(
                                            R.string.home_quick_filters_active,
                                            activeSavedFilter
                                        )
                                    }
                                    else -> {
                                        stringResource(
                                            R.string.home_quick_filters_manual_search,
                                            uiState.categorySearchQuery
                                        )
                                    }
                                }
                                TvClickableSurface(
                                    onClick = { if (!isReorderMode) showQuickFiltersDrawer = !showQuickFiltersDrawer },
                                    enabled = !isReorderMode,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp),
                                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                    colors = ClickableSurfaceDefaults.colors(
                                        containerColor = SurfaceElevated,
                                        focusedContainerColor = SurfaceHighlight.copy(alpha = 0.9f)
                                    ),
                                    border = ClickableSurfaceDefaults.border(
                                        focusedBorder = Border(
                                            border = BorderStroke(2.dp, Primary.copy(alpha = 0.85f)),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                    ),
                                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = stringResource(R.string.home_quick_filters_button),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = OnSurface
                                            )
                                            Text(
                                                text = if (showQuickFiltersDrawer) {
                                                    stringResource(R.string.home_quick_filters_hide)
                                                } else {
                                                    stringResource(R.string.home_quick_filters_show)
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                color = Primary
                                            )
                                        }
                                        Text(
                                            text = filterSubtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim
                                        )
                                    }
                                }
                                if (showQuickFiltersDrawer) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (uiState.hiddenLiveCategories.isNotEmpty()) {
                                            TvButton(
                                                onClick = { showHiddenCategoriesDialog = true },
                                                enabled = !isReorderMode,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = stringResource(
                                                        R.string.live_quick_filter_hidden_categories,
                                                        uiState.hiddenLiveCategories.size
                                                    )
                                                )
                                            }
                                        }
                                        if (hiddenChannelsLiveTv.isNotEmpty()) {
                                            TvButton(
                                                onClick = { showHiddenChannelsDialog = true },
                                                enabled = !isReorderMode,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = stringResource(
                                                        R.string.live_quick_filter_hidden_channels,
                                                        hiddenChannelsLiveTv.size
                                                    )
                                                )
                                            }
                                        }
                                        TvButton(
                                            onClick = { showAddQuickFilterDialog = true },
                                            enabled = !isReorderMode,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(stringResource(R.string.home_quick_filters_add_chip))
                                        }
                                    }
                                    if (uiState.savedCategoryFilters.isNotEmpty() || uiState.categorySearchQuery.isNotBlank()) {
                                        SelectionChipRow(
                                            title = stringResource(R.string.home_quick_filters_title),
                                            chips = buildList {
                                                add(
                                                    SelectionChip(
                                                        key = HOME_ALL_FILTER_KEY,
                                                        label = stringResource(R.string.home_quick_filters_all)
                                                    )
                                                )
                                                addAll(
                                                    uiState.savedCategoryFilters.map { filter ->
                                                        SelectionChip(key = filter, label = filter)
                                                    }
                                                )
                                            },
                                            selectedKey = uiState.activeCategoryFilter ?: HOME_ALL_FILTER_KEY.takeIf {
                                                uiState.categorySearchQuery.isBlank()
                                            },
                                            onChipSelected = { filter ->
                                                if (!isReorderMode) {
                                                    if (filter == HOME_ALL_FILTER_KEY) {
                                                        viewModel.clearCategorySearchQuery()
                                                    } else {
                                                        viewModel.applySavedCategoryFilter(filter)
                                                    }
                                                    showQuickFiltersDrawer = false
                                                }
                                            },
                                            modifier = Modifier.padding(bottom = 10.dp),
                                            contentPadding = PaddingValues(horizontal = 0.dp)
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(R.string.home_quick_filters_empty),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim,
                                            modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
                                        )
                                    }
                                }
                            }
                        }

                        LazyColumn(
                            state = categoryListState,
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(bottom = 16.dp)
                        ) {

                        items(
                            items = visibleCategories,
                            key = { it.id }
                        ) { category ->
                            val isLocked = isCategoryLocked(category)
                            val categoryFocusRequester = categoryFocusRequesters.getOrPut(category.id) { FocusRequester() }

                            CategoryItem(
                                category = category,
                                isSelected = category.id == uiState.selectedCategory?.id,
                                isLocked = isLocked,
                                isPinned = category.id in uiState.pinnedCategoryIds,
                                focusRequester = categoryFocusRequester,
                                onClick = {
                                    if (isReorderMode) return@CategoryItem
                                    if (isLocked) {
                                        pendingUnlockCategory = category
                                        showPinDialog = true
                                    } else {
                                        viewModel.selectCategory(category)
                                        // Hide the categories sidebar; Back reveals it again.
                                        if (!isCategorySidebarHidden) {
                                            isCategorySidebarHidden = true
                                            pendingCategoryContentJumpCategoryId = category.id
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (isReorderMode || isLocked) return@CategoryItem
                                    preferredRestoreTarget = FocusRestoreTarget.CATEGORY.name
                                    viewModel.showCategoryOptions(category)
                                },
                                onJumpToSearch = {
                                    runCatching { categorySearchFocusRequester.requestFocus() }.isSuccess
                                },
                                onJumpToContent = {
                                    if (isLocked) {
                                        pendingCategoryContentJumpCategoryId = null
                                        false
                                    } else if (uiState.selectedCategory?.id != category.id) {
                                        pendingCategoryContentJumpCategoryId = category.id
                                        viewModel.selectCategory(category)
                                        true
                                    } else if (uiState.isLoading) {
                                        pendingCategoryContentJumpCategoryId = category.id
                                        true
                                    } else if (uiState.filteredChannels.isNotEmpty()) {
                                        pendingCategoryContentJumpCategoryId = null
                                        requestChannelFocusFromCategory()
                                    } else {
                                        pendingCategoryContentJumpCategoryId = null
                                        false
                                    }
                                },
                                onFocused = { lastFocusedCategoryId = category.id },
                                onFocusChanged = { isFocused ->
                                    focusedRemoteShortcutTarget = if (isFocused) {
                                        FocusedRemoteShortcutTarget.CategoryTarget(category)
                                    } else if (focusedRemoteShortcutTarget is FocusedRemoteShortcutTarget.CategoryTarget &&
                                        (focusedRemoteShortcutTarget as FocusedRemoteShortcutTarget.CategoryTarget).category.id == category.id
                                    ) {
                                        null
                                    } else {
                                        focusedRemoteShortcutTarget
                                    }
                                }
                            )
                        }
                    }
                }
                    } // AnimatedVisibility — categories sidebar

                // Content - Channel Grid / Pro Preview
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalArrangement = Arrangement.spacedBy(if (isProMode) 12.dp else 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(if (isProMode) 1.08f else 1f)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, top = 2.dp, bottom = if (isDenseMode) 4.dp else 6.dp, end = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(if (isDenseMode) 2.dp else 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = displayedCategory?.name ?: if (hasBlockedCategorySearch) {
                                        stringResource(R.string.home_locked_short)
                                    } else {
                                        stringResource(R.string.home_all_channels)
                                    },
                                    style = if (isDenseMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                                    color = OnBackground,
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 8.dp)
                                        .basicMarquee(
                                            iterations = Int.MAX_VALUE,
                                            initialDelayMillis = 900,
                                            repeatDelayMillis = 1200,
                                            velocity = 24.dp
                                        )
                                )
                                if (hasSplitChannels) {
                                    CompactSplitLauncherButton(
                                        slotCount = uiState.multiviewChannelCount,
                                        slotLimit = uiState.multiviewSlotCapacity,
                                        onClick = { showSplitManagerDialog = true },
                                        modifier = Modifier.padding(start = 12.dp)
                                    )
                                }
                            }
                            if (isDenseMode) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.live_channel_results, uiState.filteredChannels.size),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = OnSurfaceDim,
                                        maxLines = 1
                                    )
                                }
                            } else {
                                ContentMetadataStrip(
                                    values = buildList {
                                        add(stringResource(R.string.live_channel_results, uiState.filteredChannels.size))
                                        uiState.lastVisitedCategory?.name?.let {
                                            add(stringResource(R.string.label_colon_value_format, stringResource(R.string.live_shell_last_group), it))
                                        }
                                    }
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Mirrors the hardware Back handler: restores the categories
                                // sidebar hidden by the last category selection.
                                AnimatedVisibility(
                                    visible = isCategorySidebarHidden,
                                    enter = fadeIn(animationSpec = tween(200)) +
                                        expandHorizontally(animationSpec = tween(200), expandFrom = Alignment.Start),
                                    exit = fadeOut(animationSpec = tween(200)) +
                                        shrinkHorizontally(animationSpec = tween(200), shrinkTowards = Alignment.Start)
                                ) {
                                    TvIconButton(
                                        onClick = {
                                            isCategorySidebarHidden = false
                                            revealSidebarNonce++
                                        },
                                        enabled = !isReorderMode,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(R.string.home_show_categories)
                                        )
                                    }
                                }
                                SearchInput(
                                    value = uiState.channelSearchQuery,
                                    onValueChange = {
                                        if (!isReorderMode) {
                                            viewModel.updateChannelSearchQuery(it)
                                        }
                                    },
                                    placeholder = stringResource(R.string.home_search_channels),
                                    onSearch = {},
                                    focusRequester = channelSearchFocusRequester,
                                    modifier = Modifier.width(channelSearchWidth),
                                    enabled = !isReorderMode
                                )
                            }
                        }

                        Crossfade(
                            targetState = uiState.selectedCategory?.id,
                            animationSpec = tween(durationMillis = 200),
                            label = "category_content_transition"
                        ) { _ ->
                        if (uiState.isLoading) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    CircularProgressIndicator(color = Color.White)
                                    Text(
                                        text = stringResource(R.string.home_loading_channels),
                                        color = Color.White.copy(alpha = 0.7f),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        } else if (uiState.errorMessage != null) {
                            val errorMsg = uiState.errorMessage
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = errorMsg ?: "",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = OnBackground
                                    )
                                }
                            }
                        } else if (!uiState.hasChannels) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (hasBlockedCategorySearch) {
                                        Icon(
                                            imageVector = Icons.Filled.Lock,
                                            contentDescription = null,
                                            tint = OnBackground,
                                            modifier = Modifier.size(34.dp)
                                        )
                                        Text(
                                            text = stringResource(R.string.home_locked_short),
                                            style = MaterialTheme.typography.titleLarge,
                                            color = OnBackground
                                        )
                                        Text(
                                            text = stringResource(R.string.home_no_channels_found_subtitle),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim
                                        )
                                    } else {
                                        Text(
                                            text = stringResource(R.string.home_no_channels_found),
                                            style = MaterialTheme.typography.titleLarge,
                                            color = OnBackground
                                        )
                                        Text(
                                            text = stringResource(R.string.home_no_channels_found_subtitle),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim
                                        )
                                        val selectedCategory = uiState.selectedCategory
                                        if (selectedCategory?.isVirtual == true && selectedCategory.id == VirtualCategoryIds.FAVORITES) {
                                            Text(
                                                text = stringResource(R.string.home_add_favorites_hint),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = OnSurfaceDim
                                            )
                                        } else if (selectedCategory?.isVirtual == true && selectedCategory.id == VirtualCategoryIds.RECENT) {
                                            Text(
                                                text = stringResource(R.string.home_recent_channels_hint),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = OnSurfaceDim
                                            )
                                        }
                                    }
                                }
                            }
                        } else {
                            var ignoreNextClick by remember { mutableStateOf(false) }
                            val channelListState = rememberLazyListState()

                            LaunchedEffect(ignoreNextClick) {
                                if (ignoreNextClick) {
                                    kotlinx.coroutines.delay(1000)
                                    ignoreNextClick = false
                                }
                            }

                            var draggingChannel by remember { mutableStateOf<Channel?>(null) }

                            LaunchedEffect(isReorderMode) {
                                if (!isReorderMode) {
                                    draggingChannel = null
                                }
                            }

                            LaunchedEffect(isReorderMode, draggingChannel?.id, uiState.filteredChannels) {
                                if (!isReorderMode) return@LaunchedEffect
                                val draggingChannelId = draggingChannel?.id ?: return@LaunchedEffect
                                val draggedIndex = uiState.filteredChannels.indexOfFirst { it.id == draggingChannelId }
                                if (draggedIndex < 0) return@LaunchedEffect

                                val visibleItems = channelListState.layoutInfo.visibleItemsInfo
                                val firstVisibleIndex = visibleItems.firstOrNull()?.index
                                val lastVisibleIndex = visibleItems.lastOrNull()?.index

                                if (
                                    firstVisibleIndex != null &&
                                    lastVisibleIndex != null &&
                                    (draggedIndex <= firstVisibleIndex || draggedIndex >= lastVisibleIndex)
                                ) {
                                    channelListState.scrollToItem(draggedIndex)
                                }

                                runCatching { channelFocusRequesters[draggingChannelId]?.requestFocus() }
                            }

                            LaunchedEffect(channelListState, uiState.filteredChannels, lastFocusedChannelId) {
                                snapshotFlow {
                                    channelListState.layoutInfo.visibleItemsInfo.mapNotNull { item ->
                                        uiState.filteredChannels.getOrNull(item.index)?.id
                                    } to lastFocusedChannelId
                                }
                                    .distinctUntilChanged()
                                    .collect { (visibleIds, focusedId) ->
                                        viewModel.updateVisibleChannelWindow(visibleIds, focusedId)
                                    }
                            }

                            // Load more channels when the user scrolls near the end of the list
                            LaunchedEffect(channelListState) {
                                snapshotFlow {
                                    val info = channelListState.layoutInfo
                                    val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                                    val total = info.totalItemsCount
                                    uiState.hasMoreChannels && total > 0 && lastVisible >= total - 5
                                }
                                    .distinctUntilChanged()
                                    .filter { it }
                                    .collect { viewModel.loadMoreChannels() }
                            }

                            DisposableEffect(Unit) {
                                onDispose {
                                    viewModel.updateVisibleChannelWindow(emptyList(), null)
                                }
                            }

                            LazyColumn(
                                state = channelListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onPreviewKeyEvent { event ->
                                        if (uiState.isChannelReorderMode && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                            if (event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK) {
                                                if (draggingChannel != null) {
                                                    draggingChannel = null
                                                    true
                                                } else {
                                                    viewModel.exitChannelReorderMode()
                                                    true
                                                }
                                            } else false
                                        } else false
                                    },
                                contentPadding = PaddingValues(
                                    start = 10.dp,
                                    end = 10.dp,
                                    bottom = if (isDenseMode) 8.dp else 12.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(channelListSpacing)
                            ) {
                                items(
                                    items = uiState.filteredChannels,
                                    key = { it.id }
                                ) { channel ->
                                    val isLocked = isChannelLocked(channel)
                                    val isDraggingThis = draggingChannel == channel
                                    val channelFocusRequester = channelFocusRequesters.getOrPut(channel.id) { FocusRequester() }

                                    LiveChannelRowSurface(
                                        channel = channel,
                                        sourceBadgeLabel = uiState.currentCombinedProfileMembers
                                            .firstOrNull { it.providerId == channel.providerId }
                                            ?.providerName
                                            ?.ifBlank { providerNameById[channel.providerId] }
                                            ?.takeIf { uiState.isCombinedLiveSource },
                                        isLocked = isLocked,
                                        isReorderMode = uiState.isChannelReorderMode,
                                        isDragging = isDraggingThis,
                                        rowHeight = channelRowHeight,
                                        onClick = {
                                            if (isReorderMode) {
                                                draggingChannel = if (isDraggingThis) null else channel
                                            } else if (ignoreNextClick) {
                                                ignoreNextClick = false
                                            } else if (!uiState.showDialog) {
                                                if (isLocked) {
                                                    pendingUnlockChannel = channel
                                                    showPinDialog = true
                                                } else if (isProMode) {
                                                    if (uiState.previewChannelId == channel.id) {
                                                        val handedOff = viewModel.beginPreviewHandoff(channel)
                                                        if (!handedOff) {
                                                            viewModel.clearPreview()
                                                        }
                                                        onChannelClick(
                                                            channel,
                                                            uiState.selectedCategory,
                                                            resolveProviderForChannel(channel),
                                                            (uiState.activeLiveSource as? ActiveLiveSource.CombinedM3uSource)?.profileId,
                                                            uiState.selectedCombinedSourceProviderId
                                                        )
                                                    } else {
                                                        viewModel.previewChannel(channel)
                                                    }
                                                } else {
                                                    onChannelClick(
                                                        channel,
                                                        uiState.selectedCategory,
                                                        resolveProviderForChannel(channel),
                                                        (uiState.activeLiveSource as? ActiveLiveSource.CombinedM3uSource)?.profileId,
                                                        uiState.selectedCombinedSourceProviderId
                                                    )
                                                }
                                            }
                                        },
                                        onLongClick = {
                                            if (!isReorderMode) {
                                                ignoreNextClick = true
                                                preferredRestoreTarget = FocusRestoreTarget.CHANNEL.name
                                                viewModel.onShowDialog(channel)
                                            }
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .focusRequester(channelFocusRequester)
                                            .onFocusChanged { focusState ->
                                                if (focusState.isFocused) {
                                                    lastFocusedChannelId = channel.id
                                                    focusedRemoteShortcutTarget = FocusedRemoteShortcutTarget.ChannelTarget(channel)
                                                } else if (focusedRemoteShortcutTarget is FocusedRemoteShortcutTarget.ChannelTarget &&
                                                    (focusedRemoteShortcutTarget as FocusedRemoteShortcutTarget.ChannelTarget).channel.id == channel.id
                                                ) {
                                                    focusedRemoteShortcutTarget = null
                                                }
                                            }
                                            .onPreviewKeyEvent { event ->
                                                if (uiState.isChannelReorderMode && isDraggingThis && event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                                    when (event.nativeKeyEvent.keyCode) {
                                                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                            viewModel.moveChannelUp(channel); true
                                                        }
                                                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                                            viewModel.moveChannelUp(channel); true
                                                        }
                                                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                            viewModel.moveChannelDown(channel); true
                                                        }
                                                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                            viewModel.moveChannelDown(channel); true
                                                        }
                                                        else -> false
                                                    }
                                                } else false
                                            }
                                    )
                                }
                            }
                        }
                        } // Crossfade
                    }

                    if (isProMode) {
                        LivePreviewPane(
                            channel = previewChannel,
                            playerEngine = uiState.previewPlayerEngine,
                            isLoading = uiState.isPreviewLoading,
                            errorMessage = uiState.previewErrorMessage,
                            modifier = Modifier
                                .weight(0.92f)
                                .fillMaxHeight()
                        )
                    }
                }
                }
            }
        }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }

}

// SearchInput moved to its own component file
