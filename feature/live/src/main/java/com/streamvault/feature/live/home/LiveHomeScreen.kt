package com.streamvault.feature.live.home

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TextButton
import com.streamvault.core.ui.device.rememberIsTelevisionDevice
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.streamvault.feature.live.presentation.components.LiveChannelProgressTicker
import com.streamvault.feature.live.home.HomeViewModel
import com.streamvault.feature.live.home.CategoryItem
import com.streamvault.feature.live.home.HomeLoadingPane
import com.streamvault.feature.live.home.HomePreviewHost
import com.streamvault.feature.live.home.LiveChannelResultsHeader
import com.streamvault.feature.live.home.LiveCategorySidebarHeader
import com.streamvault.feature.live.home.LiveCategoryListHost
import com.streamvault.feature.live.home.LiveChannelContentHost
import com.streamvault.feature.live.home.LiveChannelListHost
import com.streamvault.feature.live.home.LiveHiddenCategoriesDialog
import com.streamvault.feature.live.home.LiveHiddenChannelsDialog
import com.streamvault.feature.live.home.LiveHomeDialogsHost
import com.streamvault.feature.live.presentation.components.LiveChannelRowSurface
import com.streamvault.core.ui.components.TvEmptyState
import com.streamvault.feature.live.presentation.components.LiveReorderTopBar
import com.streamvault.feature.live.presentation.home.liveHomeLayoutMetrics
import com.streamvault.feature.live.presentation.home.isLiveHomeCategoryLocked
import com.streamvault.feature.live.presentation.home.isLiveHomeChannelLocked
import com.streamvault.feature.live.presentation.home.shouldCollapseLiveCategorySidebar
import com.streamvault.core.ui.design.FocusRestoreHost
import com.streamvault.core.ui.design.requestFocusSafely
import androidx.activity.compose.BackHandler
import com.streamvault.core.ui.theme.*
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LegacyProvider as Provider
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import com.streamvault.feature.live.R
import com.streamvault.domain.model.LiveTvChannelMode
import com.streamvault.feature.live.navigation.LiveRoutePatterns
import com.streamvault.feature.live.api.LiveMultiViewPlannerContent
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.playback.archivePlaybackCapability
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.model.RemoteShortcutProfile
import com.streamvault.feature.live.presentation.remote.LiveBrowseRemoteShortcutHandler
import com.streamvault.feature.live.presentation.remote.dispatchLiveBrowseRemoteShortcut
import com.streamvault.feature.live.presentation.remote.remoteColorButtonForKeyCode
import com.streamvault.feature.live.api.LiveHomeScaffoldContent
import com.streamvault.feature.live.api.LiveAddToGroupContent

private enum class FocusRestoreTarget {
    CATEGORY,
    CHANNEL
}

private sealed interface FocusedRemoteShortcutTarget {
    data class CategoryTarget(val category: Category) : FocusedRemoteShortcutTarget
    data class ChannelTarget(val channel: Channel) : FocusedRemoteShortcutTarget
}

// ׳’ג€ג‚¬׳’ג€ג‚¬ Screen ׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬




// ׳’ג€ג‚¬׳’ג€ג‚¬ Screen ׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬׳’ג€ג‚¬

@Composable
fun LiveHomeScreen(
    onChannelClick: (Channel, Category?, Provider?, Long?, Long?) -> Unit,
    onNavigate: (String) -> Unit,
    onOpenMultiView: () -> Unit,
    scaffold: LiveHomeScaffoldContent,
    multiViewPlanner: LiveMultiViewPlannerContent,
    addToGroupContent: LiveAddToGroupContent,
    isChannelQueuedForMultiView: (Long) -> Boolean,
    currentRoute: String,
    initialCategoryId: Long? = null,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val remoteShortcutPreferences by viewModel.remoteShortcutPreferences.collectAsStateWithLifecycle()
    val nowMs by LiveChannelProgressTicker.nowMs.collectAsStateWithLifecycle()
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
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val layoutMetrics = liveHomeLayoutMetrics(
        screenWidthDp = LocalConfiguration.current.screenWidthDp,
        isTelevisionDevice = isTelevisionDevice,
        channelMode = uiState.liveTvChannelMode
    )
    val sidebarWidth = layoutMetrics.sidebarWidth
    val channelSearchWidth = layoutMetrics.channelSearchWidth
    val channelRowHeight = layoutMetrics.channelRowHeight
    val channelListSpacing = layoutMetrics.channelListSpacing
    val snackbarHostState = remember { SnackbarHostState() }

    // Split screen state
    val hasSplitChannels = uiState.multiviewChannelCount > 0
    var showSplitManagerDialog by rememberSaveable { mutableStateOf(false) }
    var pendingSplitPlannerChannel by remember { mutableStateOf<Channel?>(null) }
    var showAddQuickFilterDialog by rememberSaveable { mutableStateOf(false) }
    var showHiddenCategoriesDialog by rememberSaveable { mutableStateOf(false) }
    var showHiddenChannelsDialog by rememberSaveable { mutableStateOf(false) }
    var isCategorySidebarHidden by rememberSaveable { mutableStateOf(false) }
    var sidebarRevealNonce by remember { mutableStateOf(0) }
    var pendingCategoryContentJumpCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }

    // Parental Control State
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var pinError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingUnlockCategory by remember { mutableStateOf<Category?>(null) }
    var pendingUnlockChannel by remember { mutableStateOf<Channel?>(null) }
    var pendingLockToggleCategory by remember { mutableStateOf<Category?>(null) }
    val scope = rememberCoroutineScope()

    fun revealCategorySidebar() {
        isCategorySidebarHidden = false
        pendingCategoryContentJumpCategoryId = null
        sidebarRevealNonce++
    }

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

    BackHandler(enabled = isCategorySidebarHidden && !hasOverlay) {
        revealCategorySidebar()
    }

    LaunchedEffect(uiState.liveTvAutoHideCategories) {
        if (!uiState.liveTvAutoHideCategories && isCategorySidebarHidden) {
            revealCategorySidebar()
        }
    }

    LiveHomeDialogsHost(
        uiState = uiState,
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
        onOpenMultiView = onOpenMultiView,
        multiViewPlanner = multiViewPlanner,
        addToGroupContent = addToGroupContent,
        isChannelQueuedForMultiView = isChannelQueuedForMultiView,
        resolveProviderForChannel = resolveProviderForChannel,
        scope = scope
    )

    LaunchedEffect(uiState.hiddenLiveCategories.isEmpty()) {
        if (showHiddenCategoriesDialog && uiState.hiddenLiveCategories.isEmpty()) {
            showHiddenCategoriesDialog = false
        }
    }

    if (showHiddenCategoriesDialog) {
        LiveHiddenCategoriesDialog(
            hiddenCategories = uiState.hiddenLiveCategories,
            title = stringResource(R.string.hidden_categories_dialog_title),
            subtitle = stringResource(R.string.hidden_categories_dialog_subtitle),
            unhideAllLabel = stringResource(R.string.hidden_categories_dialog_unhide_all),
            closeLabel = stringResource(R.string.hidden_categories_dialog_close),
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
        LiveHiddenChannelsDialog(
            hiddenChannels = hiddenChannelsLiveTv,
            title = stringResource(R.string.hidden_channels_dialog_title),
            subtitle = stringResource(R.string.hidden_channels_dialog_subtitle),
            unhideAllLabel = stringResource(R.string.hidden_channels_dialog_unhide_all),
            closeLabel = stringResource(R.string.hidden_channels_dialog_close),
            onUnhide = { viewModel.unhideChannel(it) },
            onUnhideAll = {
                viewModel.unhideAllChannels()
                showHiddenChannelsDialog = false
            },
            onDismiss = { showHiddenChannelsDialog = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
        scaffold(
            currentRoute,
            stringResource(R.string.nav_live_tv),
            uiState.activeLiveSourceTitle.ifBlank { uiState.provider?.name },
        ) {
            if (isReorderMode) {
                LiveReorderTopBar(
                    categoryName = uiState.reorderCategory?.name ?: uiState.selectedCategory?.name ?: "Channels",
                    onSave = { viewModel.saveChannelReorder() },
                    onCancel = { viewModel.exitChannelReorderMode() },
                    subtitle = stringResource(R.string.live_reorder_subtitle),
                    titleFormat = { categoryName -> stringResource(R.string.label_reordering, categoryName) },
                    cancelLabel = stringResource(R.string.action_cancel),
                    saveLabel = stringResource(R.string.action_save_order)
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
                val categoryListState = rememberLazyListState()
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
                    { category -> isLiveHomeCategoryLocked(category, uiState.parentalControlLevel, uiState.unlockedCategoryIds) }
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
                        isLiveHomeChannelLocked(
                            channel = channel,
                            categories = uiState.categories,
                            selectedCategory = uiState.selectedCategory,
                            parentalControlLevel = uiState.parentalControlLevel,
                            unlockedCategoryIds = uiState.unlockedCategoryIds
                        )
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

                fun collapseCategorySidebarForSelection(isLocked: Boolean = false): Boolean {
                    val shouldCollapse = shouldCollapseLiveCategorySidebar(
                        autoHideCategories = uiState.liveTvAutoHideCategories,
                        isLocked = isLocked,
                        isReorderMode = isReorderMode
                    )
                    if (shouldCollapse) {
                        isCategorySidebarHidden = true
                    }
                    return shouldCollapse
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

                LaunchedEffect(sidebarRevealNonce, visibleCategories) {
                    if (sidebarRevealNonce == 0 || isCategorySidebarHidden) return@LaunchedEffect

                    val targetCategoryId = lastFocusedCategoryId
                        ?.takeIf { categoryId -> visibleCategories.any { it.id == categoryId } }
                        ?: (unlockedVisibleCategories.firstOrNull() ?: visibleCategories.firstOrNull())?.id
                    val targetIndex = targetCategoryId?.let { categoryId ->
                        visibleCategories.indexOfFirst { it.id == categoryId }
                    } ?: -1
                    if (targetIndex >= 0) {
                        kotlinx.coroutines.delay(80)
                        categoryListState.animateScrollToItem(targetIndex)
                    }

                    var restored = requestCategoryFocus(targetCategoryId)
                    if (!restored) {
                        kotlinx.coroutines.delay(100)
                        restored = requestCategoryFocus(targetCategoryId)
                    }
                    if (!restored) {
                        val fallbackCategoryId = (unlockedVisibleCategories.firstOrNull() ?: visibleCategories.firstOrNull())?.id
                        if (fallbackCategoryId != targetCategoryId) {
                            val fallbackIndex = visibleCategories.indexOfFirst { it.id == fallbackCategoryId }
                            if (fallbackIndex >= 0) {
                                categoryListState.animateScrollToItem(fallbackIndex)
                            }
                            requestCategoryFocus(fallbackCategoryId)
                        }
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
                                    onOpenGuide = { onNavigate(LiveRoutePatterns.EPG) }
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
                                            onOpenGuide = { onNavigate(LiveRoutePatterns.EPG) }
                                        )
                                    }
                                }
                                null -> null
                            } ?: return@onPreviewKeyEvent false
                            dispatchLiveBrowseRemoteShortcut(action, handler)
                        }
                ) {
                    // Sidebar - Categories
                    val categorySearchFocusRequester = remember { FocusRequester() }
                    val focusManager = LocalFocusManager.current
                    
                    AnimatedVisibility(
                        visible = !isCategorySidebarHidden,
                        enter = fadeIn(tween(150)) + expandHorizontally(),
                        exit = fadeOut(tween(100)) + shrinkHorizontally()
                    ) {
                        Column(
                            modifier = Modifier
                                .width(sidebarWidth)
                                .fillMaxHeight()
                                .background(SurfaceElevated.copy(alpha = 0.88f), RoundedCornerShape(20.dp))
                                .padding(top = 10.dp)
                                .focusGroup()
                        ) {
                        LiveCategorySidebarHeader(
                            title = stringResource(R.string.home_categories_title),
                            currentSource = uiState.activeLiveSource,
                            sourceOptions = uiState.liveSourceOptions,
                            showSourceSwitcher = shouldShowLiveSourceSwitcher,
                            onSourceSelected = viewModel::switchLiveSource,
                            categorySearchQuery = uiState.categorySearchQuery,
                            onCategorySearchQueryChanged = viewModel::updateCategorySearchQuery,
                            categorySearchFocusRequester = categorySearchFocusRequester,
                            categorySearchPlaceholder = stringResource(R.string.home_search_categories),
                            quickFilterVisibilityMode = uiState.liveTvQuickFilterVisibilityMode,
                            savedCategoryFilters = uiState.savedCategoryFilters,
                            activeCategoryFilter = uiState.activeCategoryFilter,
                            hiddenCategoriesButtonLabel = uiState.hiddenLiveCategories
                                .takeIf { it.isNotEmpty() }
                                ?.let { stringResource(R.string.live_quick_filter_hidden_categories, it.size) },
                            hiddenChannelsButtonLabel = hiddenChannelsLiveTv
                                .takeIf { it.isNotEmpty() }
                                ?.let { stringResource(R.string.live_quick_filter_hidden_channels, it.size) },
                            quickFiltersButtonTitle = stringResource(R.string.home_quick_filters_button),
                            quickFiltersTitle = stringResource(R.string.home_quick_filters_title),
                            quickFiltersShowLabel = stringResource(R.string.home_quick_filters_show),
                            quickFiltersHideLabel = stringResource(R.string.home_quick_filters_hide),
                            quickFiltersShowingAllLabel = stringResource(R.string.home_quick_filters_showing_all),
                            quickFiltersActiveLabel = uiState.activeCategoryFilter?.let { filter ->
                                stringResource(R.string.home_quick_filters_active, filter)
                            } ?: "",
                            quickFiltersManualSearchLabel = stringResource(
                                R.string.home_quick_filters_manual_search,
                                uiState.categorySearchQuery
                            ),
                            quickFiltersAllLabel = stringResource(R.string.home_quick_filters_all),
                            quickFiltersEmptyLabel = stringResource(R.string.home_quick_filters_empty),
                            quickFiltersAddChipLabel = stringResource(R.string.home_quick_filters_add_chip),
                            noSourceLabel = stringResource(R.string.playlist_no_provider),
                            selectedLabel = stringResource(R.string.label_selected),
                            unavailableLabel = stringResource(R.string.live_source_unavailable_short),
                            isReorderMode = isReorderMode,
                            onShowHiddenCategories = { showHiddenCategoriesDialog = true },
                            onShowHiddenChannels = { showHiddenChannelsDialog = true },
                            onAddFilter = { showAddQuickFilterDialog = true },
                            onAllSelected = viewModel::clearCategorySearchQuery,
                            onSavedFilterSelected = viewModel::applySavedCategoryFilter
                        )

                        LiveCategoryListHost(
                            categories = visibleCategories,
                            categoryFocusRequesters = categoryFocusRequesters,
                            state = categoryListState,
                            itemContent = { category, categoryFocusRequester ->
                            val isLocked = isCategoryLocked(category)

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
                                        val collapsed = collapseCategorySidebarForSelection()
                                        viewModel.selectCategory(category)
                                        pendingCategoryContentJumpCategoryId = category.id.takeIf { collapsed }
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
                                        val collapsed = collapseCategorySidebarForSelection()
                                        pendingCategoryContentJumpCategoryId = category.id.takeIf { collapsed }
                                        viewModel.selectCategory(category)
                                        true
                                    } else if (uiState.isLoading) {
                                        val collapsed = collapseCategorySidebarForSelection()
                                        pendingCategoryContentJumpCategoryId = category.id.takeIf { collapsed }
                                        true
                                    } else if (uiState.filteredChannels.isNotEmpty()) {
                                        pendingCategoryContentJumpCategoryId = null
                                        collapseCategorySidebarForSelection()
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
                        )
                        }
                    }

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
                        LiveChannelResultsHeader(
                            heading = displayedCategory?.name ?: if (hasBlockedCategorySearch) {
                                stringResource(R.string.home_locked_short)
                            } else {
                                stringResource(R.string.home_all_channels)
                            },
                            resultCountLabel = stringResource(
                                R.string.live_channel_results,
                                uiState.filteredChannels.size
                            ),
                            metadataValues = buildList {
                                add(stringResource(R.string.live_channel_results, uiState.filteredChannels.size))
                                uiState.lastVisitedCategory?.name?.let {
                                    add(
                                        stringResource(
                                            R.string.label_colon_value_format,
                                            stringResource(R.string.live_shell_last_group),
                                            it
                                        )
                                    )
                                }
                            },
                            isDenseMode = isDenseMode,
                            hasSplitChannels = hasSplitChannels,
                            slotCount = uiState.multiviewChannelCount,
                            slotLimit = uiState.multiviewSlotCapacity,
                            onOpenSplit = { showSplitManagerDialog = true },
                            channelSearchQuery = uiState.channelSearchQuery,
                            onChannelSearchQueryChanged = viewModel::updateChannelSearchQuery,
                            searchPlaceholder = stringResource(R.string.home_search_channels),
                            channelSearchFocusRequester = channelSearchFocusRequester,
                            channelSearchWidth = channelSearchWidth,
                            isReorderMode = isReorderMode,
                            showCategoriesButton = isCategorySidebarHidden,
                            onShowCategories = ::revealCategorySidebar,
                            showCategoriesContentDescription = stringResource(R.string.home_show_categories)
                        )

                        Crossfade(
                            targetState = uiState.selectedCategory,
                            animationSpec = tween(durationMillis = 200),
                            label = "category_content_transition"
                        ) { selectedCategory ->
                        var ignoreNextClick by remember { mutableStateOf(false) }
                        var draggingChannel by remember { mutableStateOf<Channel?>(null) }

                        LaunchedEffect(ignoreNextClick) {
                            if (ignoreNextClick) {
                                kotlinx.coroutines.delay(1000)
                                ignoreNextClick = false
                            }
                        }

                        LaunchedEffect(isReorderMode) {
                            if (!isReorderMode) {
                                draggingChannel = null
                            }
                        }

                        LiveChannelContentHost(
                            isLoading = uiState.isLoading,
                            errorMessage = uiState.errorMessage,
                            hasChannels = uiState.hasChannels,
                            isBlockedCategorySearch = hasBlockedCategorySearch,
                            loadingLabel = stringResource(R.string.home_loading_channels),
                            lockedLabel = stringResource(R.string.home_locked_short),
                            noChannelsLabel = stringResource(R.string.home_no_channels_found),
                            noChannelsSubtitle = stringResource(R.string.home_no_channels_found_subtitle),
                            additionalEmptyHint = when {
                                selectedCategory?.isVirtual == true && selectedCategory.id == VirtualCategoryIds.FAVORITES ->
                                    stringResource(R.string.home_add_favorites_hint)
                                selectedCategory?.isVirtual == true && selectedCategory.id == VirtualCategoryIds.RECENT ->
                                    stringResource(R.string.home_recent_channels_hint)
                                else -> null
                            }
                        ) {
                            LiveChannelListHost(
                                channels = uiState.filteredChannels,
                                hasMoreChannels = uiState.hasMoreChannels,
                                isReorderMode = isReorderMode,
                                draggingChannel = draggingChannel,
                                focusedChannelId = lastFocusedChannelId,
                                channelFocusRequesters = channelFocusRequesters,
                                contentPaddingBottom = if (isDenseMode) 8.dp else 12.dp,
                                channelListSpacing = channelListSpacing,
                                onDraggingChannelChange = { draggingChannel = it },
                                onExitReorderMode = viewModel::exitChannelReorderMode,
                                onMoveChannelUp = viewModel::moveChannelUp,
                                onMoveChannelDown = viewModel::moveChannelDown,
                                onVisibleChannelWindowChanged = viewModel::updateVisibleChannelWindow,
                                onLoadMore = viewModel::loadMoreChannels,
                                itemContent = { channel, channelFocusRequester, isDraggingThis ->
                                    val isLocked = isChannelLocked(channel)
                                    LiveChannelRowSurface(
                                        channel = channel,
                                        nowMs = nowMs,
                                        sourceBadgeLabel = uiState.currentCombinedProfileMembers
                                            .firstOrNull { it.providerId == channel.providerId }
                                            ?.providerName
                                            ?.ifBlank { providerNameById[channel.providerId] }
                                            ?.takeIf { uiState.isCombinedLiveSource },
                                        isLocked = isLocked,
                                        isReorderMode = uiState.isChannelReorderMode,
                                        isDragging = isDraggingThis,
                                        rowHeight = channelRowHeight,
                                        liveLabel = stringResource(R.string.card_live_badge),
                                        noScheduleLabel = stringResource(R.string.label_no_schedule),
                                        lockedLabel = stringResource(R.string.a11y_locked),
                                        movingLabel = stringResource(R.string.badge_moving),
                                        savedLabel = stringResource(R.string.badge_saved),
                                        catchUpLabel = stringResource(R.string.badge_catch_up),
                                        accessibilityDescription = buildString {
                                            val hasUsableArchive = channel.archivePlaybackCapability().canBuildReplayCandidate
                                            append(
                                                channel.number.takeIf { it > 0 }?.let {
                                                    stringResource(R.string.a11y_channel_with_number, it, channel.name)
                                                } ?: channel.name
                                            )
                                            channel.currentProgram?.title?.takeIf { it.isNotBlank() }?.let {
                                                append(". ")
                                                append(stringResource(R.string.a11y_now_playing, it))
                                            }
                                            if (channel.isFavorite) {
                                                append(". ")
                                                append(stringResource(R.string.a11y_favorite))
                                            }
                                            if (hasUsableArchive) {
                                                append(". ")
                                                append(stringResource(R.string.a11y_catch_up_available))
                                            }
                                        },
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
                                                    if (viewModel.isPreviewing(channel.id)) {
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
                                    )
                                }
                            )
                        }
                        } // Crossfade
                    }

                    if (isProMode) {
                        HomePreviewHost(
                            viewModel = viewModel,
                            channels = uiState.filteredChannels,
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
