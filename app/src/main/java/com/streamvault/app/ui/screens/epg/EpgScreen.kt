package com.streamvault.app.ui.screens.epg

import android.view.inputmethod.InputMethodManager
import com.streamvault.domain.playback.ArchiveReplayMechanism
import com.streamvault.domain.playback.archivePlaybackCapability
import com.streamvault.domain.playback.isArchivePlayable
import com.streamvault.feature.live.presentation.model.guideLookupKey
import com.streamvault.feature.live.presentation.epg.LiveGuideNowProvider
import com.streamvault.feature.live.presentation.epg.currentLiveGuideNow
import com.streamvault.feature.live.presentation.epg.LiveGuideSearchOverlay
import com.streamvault.feature.live.presentation.epg.LiveGuideSearchOverlayLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideCategoryPickerDialog
import com.streamvault.feature.live.presentation.epg.LiveGuideCategoryPickerLabels
import com.streamvault.feature.live.presentation.epg.LiveCompactGuideProgramDialog
import com.streamvault.feature.live.presentation.epg.LiveCompactGuideProgramLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideControlLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideDayLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideDensityLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideFavoritesLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideModeLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideTimeControlLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideViewOptionsLabels
import com.streamvault.feature.live.presentation.epg.LiveGuidePreviewLabels
import com.streamvault.feature.live.presentation.epg.LiveGuidePreviewPane
import com.streamvault.feature.live.presentation.epg.LiveGuideToolbarLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideToolbarRow
import com.streamvault.feature.live.presentation.epg.LiveGuideMessageState
import com.streamvault.feature.live.presentation.epg.LiveGuideOptionsLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideOptionsOverlay
import com.streamvault.feature.live.presentation.epg.liveGuideOptionsDayStart
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.core.ui.image.ChannelLogoBadge
import com.streamvault.app.navigation.Routes
import com.streamvault.core.ui.platform.rememberNotificationPermissionGate
import com.streamvault.app.ui.components.SelectionChip
import com.streamvault.app.ui.components.SelectionChipRow
import kotlinx.coroutines.launch
import com.streamvault.core.ui.components.dialogs.PinDialog
import com.streamvault.app.ui.components.shell.AppNavigationChrome
import com.streamvault.app.ui.components.shell.AppScreenScaffold
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnBackground
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.core.ui.theme.TextPrimary
import com.streamvault.core.ui.theme.TextSecondary
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.EpgMatchType
import com.streamvault.domain.model.EpgOverrideCandidate
import com.streamvault.domain.model.EpgSourceType
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.VirtualCategoryIds
import com.streamvault.domain.repository.ChannelRepository
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvIconButton

private sealed interface LockedGuideAction {
    data class SelectCategory(val category: Category) : LockedGuideAction
    data class OpenProgram(val channel: Channel, val program: Program) : LockedGuideAction
    data class PlayChannel(val channel: Channel, val returnRoute: String) : LockedGuideAction
    data class PlayArchive(val channel: Channel, val program: Program, val returnRoute: String) : LockedGuideAction
}

@Composable
fun FullEpgScreen(
    currentRoute: String,
    initialCategoryId: Long? = null,
    initialAnchorTime: Long? = null,
    initialFavoritesOnly: Boolean = false,
    onPlayChannel: (Channel, Long, Boolean, Long?, String) -> Unit,
    onPlayArchive: (Channel, Program, Long, Boolean, Long?, String) -> Unit,
    onNavigate: (String) -> Unit,
    viewModel: EpgViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val overrideUiState by viewModel.overrideUiState.collectAsStateWithLifecycle()
    val programReminderUiState by viewModel.programReminderUiState.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        viewModel.reconcileProgramReminders()
    }
    var selectedProgram by remember { mutableStateOf<Pair<Channel, Program>?>(null) }
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    var focusedProgram by remember { mutableStateOf<Program?>(null) }
    var topNavVisible by rememberSaveable { mutableStateOf(true) }
    var showCategoryPicker by rememberSaveable { mutableStateOf(false) }
    var showGuideOptions by rememberSaveable { mutableStateOf(false) }
    var showSearchOverlay by rememberSaveable { mutableStateOf(false) }
    var showPinDialog by rememberSaveable { mutableStateOf(false) }
    var pinError by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingLockedAction by remember { mutableStateOf<LockedGuideAction?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val notificationPermissionGate = rememberNotificationPermissionGate(
        onNotificationsBlocked = { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
        },
        reminderBlockedMessage = stringResource(R.string.notification_permission_reminder_required),
        recordingBlockedMessage = stringResource(R.string.notification_permission_recording_alert_required)
    )
    val returnRoute = remember(uiState.selectedCategoryId, uiState.guideAnchorTime, uiState.showFavoritesOnly) {
        Routes.epg(
            categoryId = uiState.selectedCategoryId.takeIf { it != ChannelRepository.ALL_CHANNELS_ID },
            anchorTime = uiState.guideAnchorTime,
            favoritesOnly = uiState.showFavoritesOnly
        )
    }
    val categoriesById = remember(uiState.categories) {
        uiState.categories.associateBy { it.id }
    }
    val playerCategoryId = remember(uiState.selectedCategoryId, uiState.showFavoritesOnly) {
        if (uiState.showFavoritesOnly && uiState.selectedCategoryId == ChannelRepository.ALL_CHANNELS_ID) {
            VirtualCategoryIds.FAVORITES
        } else {
            uiState.selectedCategoryId
        }
    }
    val playerIsVirtualCategory = playerCategoryId == VirtualCategoryIds.FAVORITES ||
        playerCategoryId == VirtualCategoryIds.RECENT ||
        playerCategoryId < 0L

    fun executeLockedGuideAction(action: LockedGuideAction) {
        when (action) {
            is LockedGuideAction.SelectCategory -> viewModel.selectCategory(action.category.id)
            is LockedGuideAction.OpenProgram -> selectedProgram = action.channel to action.program
            is LockedGuideAction.PlayChannel -> {
                viewModel.handoffOrClearForFullscreen(action.channel)
                onPlayChannel(
                    action.channel,
                    playerCategoryId,
                    playerIsVirtualCategory,
                    uiState.combinedProfileId,
                    action.returnRoute
                )
            }
            is LockedGuideAction.PlayArchive -> {
                viewModel.clearPreview()
                onPlayArchive(
                    action.channel,
                    action.program,
                    playerCategoryId,
                    playerIsVirtualCategory,
                    uiState.combinedProfileId,
                    action.returnRoute
                )
            }
        }
    }

    fun requestLockedGuideAction(action: LockedGuideAction) {
        pendingLockedAction = action
        pinError = null
        showPinDialog = true
    }

    DisposableEffect(viewModel) {
        onDispose { viewModel.clearPreview() }
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

    LaunchedEffect(initialCategoryId, initialAnchorTime, initialFavoritesOnly) {
        viewModel.applyNavigationContext(
            categoryId = initialCategoryId,
            anchorTime = initialAnchorTime,
            favoritesOnly = initialFavoritesOnly
        )
    }

    uiState.recordingMessage?.let { message ->
        LaunchedEffect(message) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
            viewModel.clearRecordingMessage()
        }
    }
    uiState.reminderMessage?.let { message ->
        LaunchedEffect(message) {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
            viewModel.clearReminderMessage()
        }
    }

    uiState.pendingRecordingConflict?.let { conflict ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissRecordingConflict() },
            title = {
                androidx.compose.material3.Text(
                    text = stringResource(R.string.epg_recording_conflict_title),
                    color = com.streamvault.core.ui.theme.OnSurface
                )
            },
            text = {
                val conflictNames = conflict.conflictingItems.joinToString(", ") {
                    it.programTitle ?: it.channelName
                }
                androidx.compose.material3.Text(
                    text = stringResource(R.string.epg_recording_conflict_body, conflict.programTitle, conflictNames),
                    color = com.streamvault.core.ui.theme.TextSecondary
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        notificationPermissionGate.runRecordingAction {
                            viewModel.forceScheduleRecording()
                        }
                    }
                ) {
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.epg_recording_conflict_replace),
                        color = com.streamvault.core.ui.theme.Primary
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.dismissRecordingConflict() }) {
                    androidx.compose.material3.Text(
                        text = stringResource(R.string.epg_recording_conflict_cancel),
                        color = com.streamvault.core.ui.theme.OnSurface
                    )
                }
            },
            containerColor = com.streamvault.core.ui.theme.SurfaceElevated,
            titleContentColor = com.streamvault.core.ui.theme.OnSurface,
            textContentColor = com.streamvault.core.ui.theme.TextSecondary
        )
    }

    LaunchedEffect(uiState.channels, uiState.programsByChannel) {
        if (uiState.channels.isEmpty()) {
            focusedChannel = null
            focusedProgram = null
            return@LaunchedEffect
        }
        val resolvedChannel = focusedChannel?.let { current ->
            uiState.channels.firstOrNull { it.id == current.id }
        } ?: uiState.channels.firstOrNull()
        focusedChannel = resolvedChannel
        val resolvedPrograms = resolvedChannel?.let { channel ->
            channel.guideLookupKey()?.let { lookupKey ->
                uiState.programsByChannel[lookupKey].orEmpty()
            }.orEmpty()
        }.orEmpty()
        focusedProgram = focusedProgram?.let { focused ->
            resolvedPrograms.firstOrNull {
                it.startTime == focused.startTime &&
                    it.endTime == focused.endTime &&
                    it.title == focused.title
            }
        } ?: resolvedPrograms.firstOrNull {
            System.currentTimeMillis() in it.startTime until it.endTime
        }
    }

    AppScreenScaffold(
        currentRoute = currentRoute,
        onNavigate = onNavigate,
        title = stringResource(R.string.nav_epg),
        subtitle = stringResource(R.string.guide_shell_subtitle),
        navigationChrome = AppNavigationChrome.TopBar,
        topBarVisible = topNavVisible,
        compactHeader = true,
        showScreenHeader = false
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when {
                uiState.isInitialLoading && uiState.channels.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.epg_loading), color = OnBackground)
                    }
                }

                uiState.error != null -> {
                    LiveGuideMessageState(
                        modifier = Modifier.weight(1f),
                        title = when (uiState.error) {
                            EpgViewModel.NO_ACTIVE_PROVIDER -> stringResource(R.string.epg_no_provider)
                            else -> stringResource(R.string.epg_error)
                        },
                        subtitle = when (uiState.error) {
                            EpgViewModel.NO_ACTIVE_PROVIDER -> null
                            else -> stringResource(R.string.epg_retry_hint)
                        },
                        actionLabel = if (uiState.error == EpgViewModel.NO_ACTIVE_PROVIDER) null else stringResource(R.string.epg_retry),
                        onAction = if (uiState.error == EpgViewModel.NO_ACTIVE_PROVIDER) null else viewModel::refresh
                    )
                }

                uiState.channels.isEmpty() -> {
                    LiveGuideMessageState(
                        modifier = Modifier.weight(1f),
                        title = when {
                            uiState.programSearchQuery.isNotBlank() ->
                                stringResource(R.string.epg_no_search_results)
                            uiState.totalChannelCount == 0 && uiState.selectedCategoryId != ChannelRepository.ALL_CHANNELS_ID ->
                                stringResource(R.string.epg_no_channels_in_category)
                            uiState.totalChannelCount == 0 ->
                                stringResource(R.string.epg_no_data)
                            else ->
                                stringResource(R.string.epg_no_scheduled_channels)
                        },
                        subtitle = when {
                            uiState.programSearchQuery.isNotBlank() ->
                                stringResource(R.string.epg_search_empty_hint)
                            uiState.totalChannelCount == 0 ->
                                stringResource(R.string.epg_filter_hint)
                            uiState.showScheduledOnly ->
                                stringResource(R.string.epg_scheduled_only_hint)
                            else ->
                                stringResource(R.string.epg_stale_warning)
                        },
                        actionLabel = if (uiState.programSearchQuery.isNotBlank()) {
                            stringResource(R.string.epg_clear_search)
                        } else {
                            stringResource(R.string.epg_retry)
                        },
                        onAction = if (uiState.programSearchQuery.isNotBlank()) {
                            viewModel::clearProgramSearch
                        } else {
                            viewModel::refresh
                        }
                    )
                }

                else -> {
                    LiveGuideNowProvider {
                        LiveGuidePreviewPane(
                            previewPlayerEngine = uiState.previewPlayerEngine,
                            isPreviewLoading = uiState.isPreviewLoading,
                            focusedChannel = focusedChannel,
                            focusedProgram = focusedProgram,
                            labels = LiveGuidePreviewLabels(
                                title = stringResource(R.string.epg_title),
                                placeholderTitle = stringResource(R.string.live_preview_placeholder_title),
                                noSchedule = stringResource(R.string.epg_no_schedule)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                        )
                    }
                    LiveGuideToolbarRow(
                        selectedCategoryName = uiState.categories
                            .firstOrNull { it.id == uiState.selectedCategoryId }
                            ?.name
                            ?: stringResource(R.string.epg_filter_short),
                        labels = LiveGuideToolbarLabels(
                            jumpNow = stringResource(R.string.epg_jump_now),
                            search = stringResource(R.string.epg_search_label),
                            options = stringResource(R.string.epg_options_short)
                        ),
                        onOpenCategoryPicker = {
                            showCategoryPicker = true
                        },
                        onJumpToNow = {
                            viewModel.jumpToNow()
                        },
                        onOpenSearch = {
                            showSearchOverlay = true
                        },
                        onOpenOptions = {
                            showGuideOptions = true
                        },
                        onGuideInteract = { topNavVisible = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp)
                    )
                    if (uiState.isRefreshing) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp)
                                .height(3.dp),
                            color = Primary,
                            trackColor = SurfaceHighlight
                        )
                    }
                    LiveGuideNowProvider {
                        EpgGrid(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            channels = uiState.channels,
                            favoriteChannelIds = uiState.favoriteChannelIds,
                            programsByChannel = uiState.programsByChannel,
                            guideWindowStart = uiState.guideWindowStart,
                            guideWindowEnd = uiState.guideWindowEnd,
                            density = uiState.selectedDensity,
                            onChannelClick = { channel ->
                                if (isGuideChannelLocked(channel, categoriesById, uiState.parentalControlLevel)) {
                                    requestLockedGuideAction(LockedGuideAction.PlayChannel(channel, returnRoute))
                                } else if (uiState.previewChannelId == channel.id) {
                                    viewModel.handoffOrClearForFullscreen(channel)
                                    onPlayChannel(
                                        channel,
                                        playerCategoryId,
                                        playerIsVirtualCategory,
                                        uiState.combinedProfileId,
                                        returnRoute
                                    )
                                } else {
                                    viewModel.previewChannel(channel)
                                }
                            },
                            onChannelLongClick = { channel, currentProgram ->
                                topNavVisible = false
                                if (isGuideChannelLocked(channel, categoriesById, uiState.parentalControlLevel)) {
                                    requestLockedGuideAction(LockedGuideAction.PlayChannel(channel, returnRoute))
                                } else {
                                    val program = currentProgram ?: run {
                                        // No EPG match ג€” synthesize a 1-hour "now" program so
                                        // the dialog still shows Record / Record Daily / Weekly.
                                        val now = System.currentTimeMillis()
                                        Program(
                                            channelId = channel.id.toString(),
                                            title = channel.name,
                                            startTime = now,
                                            endTime = now + 60L * 60L * 1000L
                                        )
                                    }
                                    // Delay so the long-press key-release lands on the
                                    // channel cell, not on an auto-focused button inside
                                    // the dialog (which would auto-fire "Watch Live").
                                    scope.launch {
                                        kotlinx.coroutines.delay(350)
                                        selectedProgram = channel to program
                                    }
                                }
                            },
                            onProgramClick = { channel, program ->
                                topNavVisible = false
                                if (isGuideChannelLocked(channel, categoriesById, uiState.parentalControlLevel)) {
                                    requestLockedGuideAction(LockedGuideAction.OpenProgram(channel, program))
                                } else {
                                    selectedProgram = channel to program
                                }
                            },
                            onChannelFocused = { channel, currentProgram, isFirstRow ->
                                topNavVisible = isFirstRow
                                focusedChannel = channel
                                focusedProgram = currentProgram
                            },
                            onProgramFocused = { channel, program, isFirstRow ->
                                topNavVisible = isFirstRow
                                focusedChannel = channel
                                focusedProgram = program
                            },
                            onRequestMoreChannels = viewModel::requestMoreChannels
                        )
                    }
                }
            }
        }
    }

    if (showCategoryPicker) {
        LiveGuideCategoryPickerDialog(
            categories = uiState.categories,
            selectedCategoryId = uiState.selectedCategoryId,
            labels = LiveGuideCategoryPickerLabels(
                title = stringResource(R.string.epg_filter_label),
                cancel = stringResource(R.string.settings_cancel),
                searchLabel = stringResource(R.string.epg_search_label),
                searchPlaceholder = stringResource(R.string.epg_search_placeholder),
                clearSearch = stringResource(R.string.epg_clear_search),
                parentalControl = stringResource(R.string.settings_parental_control),
                matchesCount = { count -> "$count matches" },
                channelCount = { count -> if (count == 1) "1 channel" else "$count channels" },
                jumpNow = stringResource(R.string.epg_jump_now)
            ),
            isCategoryLocked = { category -> isGuideCategoryLocked(category, uiState.parentalControlLevel) },
            onDismiss = { showCategoryPicker = false },
            onCategorySelected = { category ->
                showCategoryPicker = false
                if (isGuideCategoryLocked(category, uiState.parentalControlLevel)) {
                    requestLockedGuideAction(LockedGuideAction.SelectCategory(category))
                } else {
                    viewModel.selectCategory(category.id)
                }
            }
        )
    }

    if (showSearchOverlay) {
        LiveGuideSearchOverlay(
            query = uiState.programSearchQuery,
            labels = LiveGuideSearchOverlayLabels(
                title = stringResource(R.string.epg_search_label),
                apply = stringResource(R.string.epg_search_apply),
                clearAndClose = stringResource(R.string.epg_clear_search_close),
                searchPlaceholder = stringResource(R.string.epg_search_placeholder),
                clear = stringResource(R.string.epg_clear_search)
            ),
            onQueryChange = viewModel::updateProgramSearchQuery,
            onClear = viewModel::clearProgramSearch,
            onDismiss = {
                showSearchOverlay = false
            }
        )
    }

    if (showGuideOptions) {
        LiveGuideOptionsOverlay(
            selectedDayStart = liveGuideOptionsDayStart(uiState.guideWindowStart, EpgViewModel.LOOKBACK_MS),
            selectedMode = uiState.selectedChannelMode,
            selectedDensity = uiState.selectedDensity,
            showScheduledOnly = uiState.showScheduledOnly,
            showFavoritesOnly = uiState.showFavoritesOnly,
            controlLabels = LiveGuideControlLabels(
                time = LiveGuideTimeControlLabels(
                    section = stringResource(R.string.epg_time_controls),
                    previousDay = stringResource(R.string.epg_previous_day),
                    pageBack = stringResource(R.string.epg_page_back),
                    jumpBackHalfHour = stringResource(R.string.epg_jump_back_half_hour),
                    jumpBack = stringResource(R.string.epg_jump_back),
                    jumpNow = stringResource(R.string.epg_jump_now),
                    jumpForwardHalfHour = stringResource(R.string.epg_jump_forward_half_hour),
                    jumpForward = stringResource(R.string.epg_jump_forward),
                    pageForward = stringResource(R.string.epg_page_forward),
                    jumpPrimeTime = stringResource(R.string.epg_jump_prime_time),
                    jumpTomorrow = stringResource(R.string.epg_jump_tomorrow),
                    nextDay = stringResource(R.string.epg_next_day)
                ),
                day = LiveGuideDayLabels(
                    section = stringResource(R.string.epg_day_selector_label),
                    yesterday = stringResource(R.string.epg_day_yesterday),
                    today = stringResource(R.string.epg_day_today),
                    tomorrow = stringResource(R.string.epg_day_tomorrow)
                ),
                mode = LiveGuideModeLabels(
                    title = stringResource(R.string.epg_mode_label),
                    subtitle = stringResource(R.string.epg_mode_subtitle),
                    all = stringResource(R.string.epg_mode_all),
                    allHint = stringResource(R.string.epg_mode_all_hint),
                    anchored = stringResource(R.string.epg_mode_anchored),
                    anchoredHint = stringResource(R.string.epg_mode_anchored_hint),
                    archiveReady = stringResource(R.string.epg_mode_archive),
                    archiveReadyHint = stringResource(R.string.epg_mode_archive_hint)
                ),
                density = LiveGuideDensityLabels(
                    title = stringResource(R.string.epg_density_label),
                    subtitle = stringResource(R.string.epg_density_subtitle),
                    compact = stringResource(R.string.epg_density_compact),
                    compactHint = stringResource(R.string.epg_density_compact_hint),
                    comfortable = stringResource(R.string.epg_density_comfortable),
                    comfortableHint = stringResource(R.string.epg_density_comfortable_hint),
                    cinematic = stringResource(R.string.epg_density_cinematic),
                    cinematicHint = stringResource(R.string.epg_density_cinematic_hint)
                ),
                viewOptions = LiveGuideViewOptionsLabels(
                    title = stringResource(R.string.epg_view_options_label),
                    scheduledOnlyOn = stringResource(R.string.epg_view_scheduled_only_on),
                    scheduledOnlyOff = stringResource(R.string.epg_view_scheduled_only_off),
                    scheduledOnlyHint = stringResource(R.string.epg_view_scheduled_only_hint)
                ),
                favorites = LiveGuideFavoritesLabels(
                    title = stringResource(R.string.epg_favorites_filter_title),
                    subtitle = stringResource(R.string.epg_favorites_filter_subtitle),
                    all = stringResource(R.string.epg_favorites_filter_all),
                    allHint = stringResource(R.string.epg_favorites_filter_all_hint),
                    favorites = stringResource(R.string.epg_favorites_filter_favorites),
                    favoritesHint = stringResource(R.string.epg_favorites_filter_favorites_hint)
                )
            ),
            labels = LiveGuideOptionsLabels(
                title = stringResource(R.string.epg_options_short),
                showAppNavigation = stringResource(R.string.epg_show_app_navigation),
                cancel = stringResource(R.string.settings_cancel),
                manageEpgMatch = stringResource(R.string.epg_override_manage),
                refreshGuide = stringResource(R.string.epg_refresh_guide)
            ),
            onDismiss = { showGuideOptions = false },
            onShowAppNavigation = {
                topNavVisible = true
                showGuideOptions = false
            },
            onJumpToPreviousDay = viewModel::jumpToPreviousDay,
            onPageBackward = viewModel::pageBackward,
            onJumpBackwardHalfHour = viewModel::jumpBackwardHalfHour,
            onJumpBackward = viewModel::jumpBackward,
            onJumpToNow = viewModel::jumpToNow,
            onJumpForwardHalfHour = viewModel::jumpForwardHalfHour,
            onJumpForward = viewModel::jumpForward,
            onPageForward = viewModel::pageForward,
            onJumpToPrimeTime = viewModel::jumpToPrimeTime,
            onJumpToTomorrow = viewModel::jumpToTomorrow,
            onJumpToNextDay = viewModel::jumpToNextDay,
            onDaySelected = viewModel::jumpToDay,
            onModeSelected = viewModel::selectChannelMode,
            onDensitySelected = viewModel::selectDensity,
            onToggleScheduledOnly = viewModel::toggleScheduledOnly,
            onToggleFavoritesOnly = viewModel::toggleFavoritesOnly,
            onRefresh = viewModel::refresh,
            onManageEpgMatch = focusedChannel?.takeIf { it.providerId > 0L }?.let { ch ->
                {
                    showGuideOptions = false
                    viewModel.openEpgOverride(ch)
                }
            }
        )
    }

    if (showPinDialog) {
        PinDialog(
            title = stringResource(R.string.pin_dialog_title),
            cancelLabel = stringResource(R.string.pin_dialog_cancel),
            onDismissRequest = {
                showPinDialog = false
                pinError = null
                pendingLockedAction = null
            },
            onPinEntered = { pin ->
                scope.launch {
                    if (viewModel.verifyPin(pin)) {
                        val action = pendingLockedAction
                        val lockedCategoryId = when (action) {
                            is LockedGuideAction.SelectCategory -> action.category.id
                            is LockedGuideAction.OpenProgram -> action.channel.categoryId
                            is LockedGuideAction.PlayChannel -> action.channel.categoryId
                            is LockedGuideAction.PlayArchive -> action.channel.categoryId
                            null -> null
                        }
                        lockedCategoryId?.let(viewModel::unlockCategory)
                        showPinDialog = false
                        pinError = null
                        pendingLockedAction = null
                        action?.let(::executeLockedGuideAction)
                    } else {
                        pinError = context.getString(R.string.home_incorrect_pin)
                    }
                }
            },
            error = pinError
        )
    }

    val dialogState = selectedProgram
    if (dialogState != null) {
        LiveGuideNowProvider {
            val (channel, program) = dialogState
            val reminderProviderId = program.providerId.takeIf { it > 0L } ?: channel.providerId
            LaunchedEffect(channel.id, reminderProviderId, program.channelId, program.title, program.startTime) {
                viewModel.loadProgramReminderState(channel, program)
            }
            val reminderStateMatches = programReminderUiState.matches(
                providerId = reminderProviderId,
                channelId = program.channelId,
                programTitle = program.title,
                programStartTime = program.startTime
            )
            val reminderButtonLabel = if (
                reminderProviderId > 0L &&
                program.channelId.isNotBlank() &&
                program.startTime > currentLiveGuideNow() + 60_000L
            ) {
                when {
                    reminderStateMatches && programReminderUiState.isLoading ->
                        stringResource(R.string.epg_program_reminder_loading)
                    reminderStateMatches && programReminderUiState.isScheduled ->
                        stringResource(R.string.epg_program_reminder_cancel)
                    else -> stringResource(R.string.epg_program_reminder_set)
                }
            } else {
                null
            }
            val canWatchArchive = channel.isArchivePlayable(program, currentLiveGuideNow())
            LiveCompactGuideProgramDialog(
                channel = channel,
                program = program,
                providerLabel = uiState.providerSourceLabel,
                now = currentLiveGuideNow(),
                labels = LiveCompactGuideProgramLabels(
                    noInfo = stringResource(R.string.epg_no_info),
                    watchLive = stringResource(R.string.epg_watch_live),
                    watchArchive = stringResource(R.string.epg_watch_archive),
                    scheduleRecording = stringResource(R.string.epg_schedule_recording),
                    scheduleDailyRecording = stringResource(R.string.epg_schedule_daily_recording),
                    scheduleWeeklyRecording = stringResource(R.string.epg_schedule_weekly_recording),
                    detailsShow = stringResource(R.string.epg_program_details_show),
                    detailsHide = stringResource(R.string.epg_program_details_hide),
                    cancel = stringResource(R.string.settings_cancel)
                ),
                onDismiss = { selectedProgram = null },
                onWatchLive = {
                    selectedProgram = null
                    if (isGuideChannelLocked(channel, categoriesById, uiState.parentalControlLevel)) {
                        requestLockedGuideAction(LockedGuideAction.PlayChannel(channel, returnRoute))
                    } else {
                        viewModel.handoffOrClearForFullscreen(channel)
                        onPlayChannel(
                            channel,
                            playerCategoryId,
                            playerIsVirtualCategory,
                            uiState.combinedProfileId,
                            returnRoute
                        )
                    }
                },
                onWatchArchive = if (canWatchArchive) {
                    {
                        selectedProgram = null
                        if (isGuideChannelLocked(channel, categoriesById, uiState.parentalControlLevel)) {
                            requestLockedGuideAction(LockedGuideAction.PlayArchive(channel, program, returnRoute))
                        } else {
                            viewModel.clearPreview()
                            onPlayArchive(
                                channel,
                                program,
                                playerCategoryId,
                                playerIsVirtualCategory,
                                uiState.combinedProfileId,
                                returnRoute
                            )
                        }
                    }
                } else {
                    null
                },
                reminderButtonLabel = reminderButtonLabel,
                onToggleReminder = reminderButtonLabel?.let {
                    {
                        if (reminderStateMatches && programReminderUiState.isScheduled) {
                            viewModel.toggleProgramReminder(channel, program)
                        } else {
                            notificationPermissionGate.runReminderAction {
                                viewModel.toggleProgramReminder(channel, program)
                            }
                        }
                    }
                },
                onScheduleRecording = if (channel.streamUrl.isNotBlank() && program.endTime > currentLiveGuideNow()) {
                    {
                        notificationPermissionGate.runRecordingAction {
                            viewModel.scheduleRecording(channel, program)
                        }
                    }
                } else {
                    null
                },
                onScheduleDailyRecording = if (channel.streamUrl.isNotBlank() && program.endTime > currentLiveGuideNow()) {
                    {
                        notificationPermissionGate.runRecordingAction {
                            viewModel.scheduleRecording(channel, program, com.streamvault.domain.model.RecordingRecurrence.DAILY)
                        }
                    }
                } else {
                    null
                },
                onScheduleWeeklyRecording = if (channel.streamUrl.isNotBlank() && program.endTime > currentLiveGuideNow()) {
                    {
                        notificationPermissionGate.runRecordingAction {
                            viewModel.scheduleRecording(channel, program, com.streamvault.domain.model.RecordingRecurrence.WEEKLY)
                        }
                    }
                } else {
                    null
                }
            )
        }
    }

    if (overrideUiState.channel != null) {
        EpgOverrideDialog(
            state = overrideUiState,
            onDismiss = viewModel::dismissEpgOverride,
            onQueryChange = viewModel::updateEpgOverrideSearch,
            onCandidateSelected = viewModel::applyEpgOverride,
            onClearOverride = viewModel::clearEpgOverride
        )
    }
}

@Composable
private fun GuideProgramMetadataRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = OnSurfaceDim
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun GuideStatusCard(
    isArchiveReady: Boolean,
    providerCatchUpSupported: Boolean,
    isGuideStale: Boolean
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceHighlight)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.epg_program_status_title),
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface
            )
            Text(
                text = when {
                    isArchiveReady -> stringResource(R.string.epg_archive_ready_hint)
                    providerCatchUpSupported -> stringResource(R.string.epg_archive_provider_hint)
                    else -> stringResource(R.string.epg_archive_unavailable_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (isArchiveReady) Primary else OnSurfaceDim
            )
            if (isGuideStale) {
                Text(
                    text = stringResource(R.string.epg_archive_stale_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

@Composable
private fun GuideProviderTroubleshootingCard(
    summary: String,
    channel: Channel,
    program: Program,
    isGuideStale: Boolean
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceHighlight.copy(alpha = 0.85f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.epg_provider_troubleshooting_title),
                style = MaterialTheme.typography.titleSmall,
                color = OnSurface
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = OnSurface
            )
            val archiveCapability = channel.archivePlaybackCapability()
            val channelReason = when {
                !archiveCapability.advertisedByProvider && !program.hasArchive ->
                    stringResource(R.string.epg_provider_troubleshooting_no_archive)
                !archiveCapability.canBuildReplayCandidate ->
                    stringResource(R.string.epg_provider_troubleshooting_incomplete_metadata)
                archiveCapability.mechanism == ArchiveReplayMechanism.XTREAM_STREAM_ID ->
                    stringResource(R.string.epg_provider_troubleshooting_xtream_ready)
                archiveCapability.mechanism == ArchiveReplayMechanism.STALKER_ARCHIVE_TOKEN ->
                    stringResource(R.string.epg_provider_troubleshooting_stalker_ready)
                archiveCapability.mechanism == ArchiveReplayMechanism.M3U_TEMPLATE ->
                    stringResource(R.string.epg_provider_troubleshooting_m3u_best_effort)
                else ->
                    stringResource(R.string.epg_provider_troubleshooting_ready)
            }
            Text(
                text = channelReason,
                style = MaterialTheme.typography.bodySmall,
                color = if (archiveCapability.canBuildReplayCandidate) Primary else OnSurfaceDim
            )
            if (isGuideStale) {
                Text(
                    text = stringResource(R.string.epg_provider_troubleshooting_stale),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )
            }
        }
    }
}

internal fun isGuideCategoryLocked(category: Category, parentalControlLevel: Int): Boolean =
    parentalControlLevel in 1..2 && (category.isAdult || category.isUserProtected)

private fun isGuideChannelLocked(
    channel: Channel,
    categoriesById: Map<Long, Category>,
    parentalControlLevel: Int
): Boolean {
    if (parentalControlLevel !in 1..2) {
        return false
    }
    val categoryLocked = channel.categoryId?.let(categoriesById::get)?.let { category ->
        category.isAdult || category.isUserProtected
    } ?: false
    return channel.isAdult || channel.isUserProtected || categoryLocked
}


