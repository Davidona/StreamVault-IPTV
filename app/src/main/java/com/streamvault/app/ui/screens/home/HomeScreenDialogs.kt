package com.streamvault.app.ui.screens.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import com.streamvault.feature.live.presentation.home.HomeUiState
import com.streamvault.feature.live.home.HomeViewModel
import com.streamvault.app.navigation.Routes
import com.streamvault.core.ui.components.SearchInput
import com.streamvault.app.ui.components.dialogs.AddToGroupDialog
import com.streamvault.app.ui.components.dialogs.CategoryOptionsDialog
import com.streamvault.core.ui.components.dialogs.PinDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.components.dialogs.RenameGroupDialog
import com.streamvault.app.ui.components.dialogs.M3uCategoryOrganizerDialog
import com.streamvault.app.ui.components.dialogs.M3uCategorySeriesAssignmentDialog
import com.streamvault.app.ui.components.dialogs.M3uSeriesAssignmentDialog
import com.streamvault.feature.playback.multiview.MultiViewPlannerDialog
import com.streamvault.feature.playback.multiview.MultiViewViewModel
import com.streamvault.domain.model.ActiveLiveSource
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.repository.M3uCategoryItem
import com.streamvault.domain.repository.M3uClassificationTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun HomeDialogsHost(
    uiState: HomeUiState,
    multiViewViewModel: MultiViewViewModel,
    viewModel: HomeViewModel,
    showPinDialog: Boolean,
    pinError: String?,
    pendingUnlockCategory: Category?,
    pendingUnlockChannel: Channel?,
    pendingLockToggleCategory: Category?,
    showAddQuickFilterDialog: Boolean,
    showSplitManagerDialog: Boolean,
    pendingSplitPlannerChannel: Channel?,
    onShowPinDialogChange: (Boolean) -> Unit,
    onPinErrorChange: (String?) -> Unit,
    onPendingUnlockCategoryChange: (Category?) -> Unit,
    onPendingUnlockChannelChange: (Channel?) -> Unit,
    onPendingLockToggleCategoryChange: (Category?) -> Unit,
    onShowAddQuickFilterDialogChange: (Boolean) -> Unit,
    onShowSplitManagerDialogChange: (Boolean) -> Unit,
    onPendingSplitPlannerChannelChange: (Channel?) -> Unit,
    onChannelClick: (Channel, Category?, Provider?, Long?, Long?) -> Unit,
    resolveProviderForChannel: (Channel) -> Provider?,
    onNavigate: (String) -> Unit,
    scope: CoroutineScope
) {
    val context = LocalContext.current
    var pendingM3uSeriesChannel by remember { mutableStateOf<Channel?>(null) }
    var pendingM3uCategory by remember { mutableStateOf<Category?>(null) }
    var pendingM3uSeriesCategory by remember { mutableStateOf<Category?>(null) }
    var pendingM3uSeriesItems by remember { mutableStateOf<List<M3uCategoryItem>?>(null) }

    if (showPinDialog) {
        PinDialog(
            title = stringResource(R.string.pin_dialog_title),
            cancelLabel = stringResource(R.string.pin_dialog_cancel),
            onDismissRequest = {
                onShowPinDialogChange(false)
                onPinErrorChange(null)
                onPendingUnlockCategoryChange(null)
                onPendingUnlockChannelChange(null)
            },
            onPinEntered = { pin ->
                scope.launch {
                    pendingUnlockCategory?.let { category ->
                        when (val unlockResult = viewModel.unlockCategoryWithPin(category, pin)) {
                            is Result.Success -> {
                                onShowPinDialogChange(false)
                                onPinErrorChange(null)
                                onPendingUnlockCategoryChange(null)
                                onPendingUnlockChannelChange(null)
                            }
                            is Result.Error -> {
                                onPinErrorChange(
                                    if (unlockResult.message == "Incorrect PIN") {
                                        context.getString(R.string.home_incorrect_pin)
                                    } else {
                                        unlockResult.message
                                    }
                                )
                            }
                            Result.Loading -> Unit
                        }
                        return@launch
                    }

                    if (viewModel.verifyPin(pin)) {
                        onShowPinDialogChange(false)
                        onPinErrorChange(null)

                        pendingUnlockChannel?.let { channel ->
                            viewModel.clearPreview()
                            val combinedProfileId = (uiState.activeLiveSource as? ActiveLiveSource.CombinedM3uSource)?.profileId
                            onChannelClick(channel, uiState.selectedCategory, resolveProviderForChannel(channel), combinedProfileId, uiState.selectedCombinedSourceProviderId)
                            onPendingUnlockChannelChange(null)
                        }

                        pendingLockToggleCategory?.let { category ->
                            viewModel.toggleCategoryLock(category)
                            onPendingLockToggleCategoryChange(null)
                        }

                        onPendingUnlockCategoryChange(null)
                        onPendingUnlockChannelChange(null)
                    } else {
                        onPinErrorChange(context.getString(R.string.home_incorrect_pin))
                    }
                }
            },
            error = pinError
        )
    }

    val selectedCategoryForOptions = uiState.selectedCategoryForOptions
    if (selectedCategoryForOptions != null) {
        val category = selectedCategoryForOptions
        val isCategoryLocked =
            (category.isAdult || category.isUserProtected) &&
                uiState.parentalControlLevel in 1..2 &&
                kotlin.math.abs(category.id) !in uiState.unlockedCategoryIds
        CategoryOptionsDialog(
            category = category,
            onDismissRequest = { viewModel.dismissCategoryOptions() },
            onSetAsDefault = if (isCategoryLocked) null else {
                {
                    viewModel.setDefaultCategory(category)
                    viewModel.dismissCategoryOptions()
                }
            },
            isPinned = category.id in uiState.pinnedCategoryIds,
            onTogglePinned = if (!isCategoryLocked && !category.isVirtual && category.id != ChannelRepository.ALL_CHANNELS_ID) {
                { viewModel.toggleCategoryPinned(category) }
            } else null,
            onHide = if (!isCategoryLocked && !category.isVirtual && category.id != ChannelRepository.ALL_CHANNELS_ID) {
                { viewModel.hideCategory(category) }
            } else null,
            onHideFromLiveTV = if (!isCategoryLocked && category.id in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT, ChannelRepository.ALL_CHANNELS_ID)) {
                {
                    when (category.id) {
                        VirtualCategoryIds.FAVORITES -> viewModel.setShowFavoritesCategory(false)
                        VirtualCategoryIds.RECENT -> viewModel.setShowRecentChannelsCategory(false)
                        ChannelRepository.ALL_CHANNELS_ID -> viewModel.setShowAllChannelsCategory(false)
                    }
                    viewModel.dismissCategoryOptions()
                }
            } else null,
            onClearAll = if (!isCategoryLocked && category.id == VirtualCategoryIds.RECENT) {
                {
                    viewModel.clearRecentChannels()
                    viewModel.dismissCategoryOptions()
                }
            } else null,
            onRename = if (!isCategoryLocked && category.isVirtual && category.id !in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)) {
                { viewModel.requestRenameGroup(category) }
            } else null,
            onToggleLock = {
                viewModel.dismissCategoryOptions()
                onPendingLockToggleCategoryChange(category)
                onShowPinDialogChange(true)
            },
            onDelete = if (!isCategoryLocked && category.isVirtual && category.id !in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)) {
                { viewModel.requestDeleteGroup(category) }
            } else null,
            onReorderChannels = if (!isCategoryLocked && category.isVirtual && category.id != VirtualCategoryIds.RECENT) {
                { viewModel.enterChannelReorderMode(category) }
            } else null,
            onOrganizeM3u = if (
                !isCategoryLocked &&
                    !uiState.isCombinedLiveSource &&
                    uiState.provider?.type == ProviderType.M3U &&
                    category.type == com.streamvault.domain.model.ContentType.LIVE &&
                    !category.isVirtual
            ) {
                {
                    pendingM3uCategory = category
                    viewModel.dismissCategoryOptions()
                }
            } else null
        )
    }

    if (showAddQuickFilterDialog) {
        var pendingFilter by rememberSaveable { mutableStateOf("") }
        PremiumDialog(
            title = stringResource(R.string.home_quick_filters_add_title),
            subtitle = stringResource(R.string.home_quick_filters_add_subtitle),
            onDismissRequest = { onShowAddQuickFilterDialogChange(false) },
            widthFraction = 0.42f,
            content = {
                SearchInput(
                    value = pendingFilter,
                    onValueChange = { pendingFilter = it },
                    placeholder = stringResource(R.string.home_quick_filters_add_placeholder),
                    modifier = Modifier.fillMaxWidth()
                )
                PremiumDialogActionButton(
                    label = stringResource(R.string.home_quick_filters_add_action),
                    enabled = pendingFilter.isNotBlank(),
                    onClick = {
                        val normalized = pendingFilter.trim()
                        val isDuplicate = uiState.savedCategoryFilters.any {
                            it.equals(normalized, ignoreCase = true)
                        }
                        viewModel.addLiveTvCategoryFilter(pendingFilter)
                        if (normalized.isNotBlank() && !isDuplicate) {
                            onShowAddQuickFilterDialogChange(false)
                        }
                    }
                )
            },
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.settings_cancel),
                    onClick = { onShowAddQuickFilterDialogChange(false) }
                )
            }
        )
    }

    if (pendingSplitPlannerChannel != null) {
        MultiViewPlannerDialog(
            pendingChannel = pendingSplitPlannerChannel,
            onDismiss = { onPendingSplitPlannerChannelChange(null) },
            onLaunch = {
                onPendingSplitPlannerChannelChange(null)
                viewModel.onDismissDialog()
                viewModel.clearPreview()
                onNavigate(Routes.MULTI_VIEW)
            },
            viewModel = multiViewViewModel
        )
    }

    val selectedChannelForDialog = uiState.selectedChannelForDialog
    if (uiState.showDialog && selectedChannelForDialog != null && pendingSplitPlannerChannel == null) {
        val channel = selectedChannelForDialog
        AddToGroupDialog(
            contentTitle = channel.name,
            channel = channel,
            groups = uiState.categories.filter {
                it.isVirtual && it.id !in setOf(VirtualCategoryIds.FAVORITES, VirtualCategoryIds.RECENT)
            },
            isFavorite = channel.isFavorite,
            memberOfGroups = uiState.dialogGroupMemberships,
            onDismiss = { viewModel.onDismissDialog() },
            onToggleFavorite = {
                if (channel.isFavorite) viewModel.removeFavorite(channel) else viewModel.addFavorite(channel)
            },
            onAddToGroup = { group -> viewModel.addToGroup(channel, group) },
            onRemoveFromGroup = { group -> viewModel.removeFromGroup(channel, group) },
            onCreateGroup = if (
                uiState.isCombinedLiveSource &&
                uiState.selectedCombinedSourceProviderId == null &&
                uiState.currentCombinedProfileMembers.count { it.enabled } > 1
            ) null else { name -> viewModel.createCustomGroup(name) },
            isQueuedForSplitScreen = multiViewViewModel.isQueued(channel.id),
            onOpenSplitScreenPlanner = { onPendingSplitPlannerChannelChange(channel) },
            onRemoveFromRecent = if (uiState.selectedCategory?.id == VirtualCategoryIds.RECENT) {
                {
                    viewModel.removeChannelFromRecent(channel)
                    viewModel.onDismissDialog()
                }
            } else null,
            onHideChannel = { viewModel.hideChannel(channel) },
            onMoveToMovies = if (!uiState.isCombinedLiveSource && uiState.provider?.type == ProviderType.M3U) {
                { viewModel.moveM3uChannelToMovies(channel) }
            } else null,
            onMoveToSeries = if (!uiState.isCombinedLiveSource && uiState.provider?.type == ProviderType.M3U) {
                {
                    pendingM3uSeriesChannel = channel
                    viewModel.onDismissDialog()
                }
            } else null
        )
    }

    pendingM3uSeriesChannel?.let { channel ->
        M3uSeriesAssignmentDialog(
            initialTitle = channel.name,
            onDismiss = { pendingM3uSeriesChannel = null },
            onConfirm = { assignment ->
                pendingM3uSeriesChannel = null
                viewModel.moveM3uChannelToSeries(channel, assignment)
            }
        )
    }

    pendingM3uCategory?.let { category ->
        M3uCategoryOrganizerDialog(
            categoryName = category.name,
            onDismiss = { pendingM3uCategory = null },
            onTargetSelected = { target ->
                if (target == M3uClassificationTarget.SERIES) {
                    pendingM3uCategory = null
                    viewModel.loadM3uCategoryItems(category) { items ->
                        pendingM3uSeriesCategory = category
                        pendingM3uSeriesItems = items
                    }
                } else {
                    pendingM3uCategory = null
                    viewModel.organizeM3uCategory(category, target)
                }
            }
        )
    }

    if (pendingM3uSeriesCategory != null && pendingM3uSeriesItems != null) {
        val category = pendingM3uSeriesCategory!!
        M3uCategorySeriesAssignmentDialog(
            categoryName = category.name,
            items = pendingM3uSeriesItems!!,
            onDismiss = {
                pendingM3uSeriesCategory = null
                pendingM3uSeriesItems = null
            },
            onConfirm = { assignments ->
                pendingM3uSeriesCategory = null
                pendingM3uSeriesItems = null
                viewModel.organizeM3uCategory(
                    category = category,
                    target = M3uClassificationTarget.SERIES,
                    seriesAssignments = assignments
                )
            }
        )
    }

    val groupToRename = uiState.groupToRename
    if (uiState.showRenameGroupDialog && groupToRename != null) {
        RenameGroupDialog(
            initialName = groupToRename.name,
            errorMessage = uiState.renameGroupError,
            onDismissRequest = { viewModel.cancelRenameGroup() },
            onConfirm = { name -> viewModel.confirmRenameGroup(name) }
        )
    }

    if (showSplitManagerDialog) {
        MultiViewPlannerDialog(
            pendingChannel = null,
            onDismiss = { onShowSplitManagerDialogChange(false) },
            onLaunch = {
                onShowSplitManagerDialogChange(false)
                viewModel.clearPreview()
                onNavigate(Routes.MULTI_VIEW)
            },
            viewModel = multiViewViewModel
        )
    }

    val groupToDelete = uiState.groupToDelete
    if (uiState.showDeleteGroupDialog && groupToDelete != null) {
        val group = groupToDelete
        var canInteract by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            delay(500)
            canInteract = true
        }

        val safeDismiss = {
            if (canInteract) viewModel.cancelDeleteGroup()
        }

        PremiumDialog(
            title = stringResource(R.string.home_delete_group_title),
            subtitle = stringResource(R.string.home_delete_group_body, group.name),
            onDismissRequest = safeDismiss,
            widthFraction = 0.36f,
            content = {},
            footer = {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.home_delete_group_cancel),
                    onClick = safeDismiss
                )
                PremiumDialogFooterButton(
                    label = stringResource(R.string.home_delete_group_confirm),
                    onClick = { if (canInteract) viewModel.confirmDeleteGroup() },
                    destructive = true
                )
            }
        )
    }
}
