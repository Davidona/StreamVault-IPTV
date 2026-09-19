package com.streamvault.feature.playback.multiview

import android.view.View
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import com.streamvault.feature.playback.R
import com.streamvault.player.ui.PlayerRenderView
import com.streamvault.core.ui.components.dialogs.PinEntryDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialog
import com.streamvault.core.ui.components.dialogs.PremiumDialogActionButton
import com.streamvault.core.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.core.ui.theme.Primary
import com.streamvault.player.PlayerRenderSurfaceType
import com.streamvault.player.PlayerSurfaceResizeMode
import kotlinx.coroutines.launch
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvIconButton

@Composable
fun MultiViewScreen(
    onBack: () -> Unit,
    viewModel: MultiViewViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val firstSlotFocusRequester = remember { FocusRequester() }
    val firstControlFocusRequester = remember { FocusRequester() }
    var showReplacementPicker by remember { mutableStateOf(false) }
    var showControls by rememberSaveable { mutableStateOf(false) }
    val occupiedSlots = remember(uiState.slots) { uiState.slots.filterNot { it.isEmpty } }
    val useCenteredCompactLayout = uiState.centerTwoSlotLayout && occupiedSlots.size in 1..2
    val visibleSlots = if (useCenteredCompactLayout) occupiedSlots else uiState.slots
    val firstVisibleSlotIndex = visibleSlots.firstOrNull()?.index ?: 0

    BackHandler(enabled = showReplacementPicker) {
        if (uiState.pickerState.selectedCategory != null) {
            viewModel.backToPickerCategories()
        } else {
            showReplacementPicker = false
            viewModel.resetPicker()
        }
    }
    BackHandler(enabled = showControls) { showControls = false }
    BackHandler(enabled = !showReplacementPicker && !showControls) { onBack() }

    // initSlots() is driven by the ON_START lifecycle observer below; do not call it here.
    LaunchedEffect(Unit) {
        try {
            kotlinx.coroutines.delay(100)
            firstSlotFocusRequester.requestFocus()
        } catch (_: Exception) {
            // No-op: focus request can fail during composition transitions.
        }
    }

    LaunchedEffect(showControls) {
        if (showControls) {
            try {
                kotlinx.coroutines.delay(60)
                firstControlFocusRequester.requestFocus()
            } catch (_: Exception) {
                // No-op: focus handoff can fail during composition transitions.
            }
        }
    }

    LaunchedEffect(useCenteredCompactLayout, firstVisibleSlotIndex, uiState.focusedSlotIndex) {
        if (useCenteredCompactLayout && visibleSlots.none { it.index == uiState.focusedSlotIndex }) {
            viewModel.setFocus(firstVisibleSlotIndex)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.releasePlayersForBackground()
        }
    }

    // Prevent screen from sleeping while watching multiview
    val multiViewWindow = LocalActivity.current?.window
    DisposableEffect(Unit) {
        multiViewWindow?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { multiViewWindow?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.initSlots()
                Lifecycle.Event.ON_STOP -> viewModel.releasePlayersForBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(firstSlotFocusRequester)
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_MENU,
                    KeyEvent.KEYCODE_INFO -> {
                        showControls = !showControls
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> {
                        when {
                            showReplacementPicker -> {
                                showReplacementPicker = false
                                true
                            }
                            showControls -> {
                                showControls = false
                                true
                            }
                            else -> false
                        }
                    }
                    else -> false
                }
            }
    ) {
        if (showReplacementPicker) {
            ReplaceSlotDialog(
                pickerState = uiState.pickerState,
                parentalControlLevel = uiState.parentalControlLevel,
                onDismiss = {
                    showReplacementPicker = false
                    viewModel.resetPicker()
                },
                onSelectCategory = viewModel::selectPickerCategory,
                onBackToCategories = viewModel::backToPickerCategories,
                onUpdateSearch = viewModel::updatePickerSearch,
                onVerifyPin = viewModel::verifyPin,
                onUnlockCategory = viewModel::unlockPickerCategory,
                onReplace = { channel ->
                    viewModel.replaceFocusedSlot(channel)
                    showReplacementPicker = false
                    viewModel.resetPicker()
                }
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            if (useCenteredCompactLayout) {
                CenteredCompactLayout(
                    slots = visibleSlots,
                    focusedSlotIndex = uiState.focusedSlotIndex,
                    showSelectionBorder = uiState.showSelectionBorder,
                    showControls = showControls,
                    firstSlotFocusRequester = firstSlotFocusRequester,
                    firstControlFocusRequester = firstControlFocusRequester,
                    onSlotClick = { showControls = !showControls },
                    onSlotFocused = viewModel::setFocus
                )
            } else {
                StandardMultiViewGrid(
                    slots = uiState.slots,
                    focusedSlotIndex = uiState.focusedSlotIndex,
                    showSelectionBorder = uiState.showSelectionBorder,
                    showControls = showControls,
                    firstSlotFocusRequester = firstSlotFocusRequester,
                    firstControlFocusRequester = firstControlFocusRequester,
                    onSlotClick = { showControls = !showControls },
                    onSlotFocused = viewModel::setFocus
                )
            }
        }

        val focused = uiState.slots.getOrNull(uiState.focusedSlotIndex)
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(140)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.26f))
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EnhancedMultiViewControlHud(
                        focused = focused,
                        uiState = uiState,
                        firstControlFocusRequester = firstControlFocusRequester,
                        onShowReplacementPicker = {
                            viewModel.openReplacementPicker()
                            showReplacementPicker = true
                        },
                        onRemoveFocusedSlot = viewModel::removeFocusedSlot,
                        onClearPinnedAudio = viewModel::clearPinnedAudio,
                        onPinAudioToFocusedSlot = viewModel::pinAudioToFocusedSlot,
                        onLoadPreset = viewModel::loadPreset,
                        onSavePreset = viewModel::saveCurrentAsPreset
                    )
                }
            }
        }
    }
}

@Composable
private fun StandardMultiViewGrid(
    slots: List<MultiViewSlot>,
    focusedSlotIndex: Int,
    showSelectionBorder: Boolean,
    showControls: Boolean,
    firstSlotFocusRequester: FocusRequester,
    firstControlFocusRequester: FocusRequester,
    onSlotClick: () -> Unit,
    onSlotFocused: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.weight(1f)) {
            GridPlayerCell(
                slot = slots.getOrNull(0),
                slotIndex = 0,
                focusedSlotIndex = focusedSlotIndex,
                showSelectionBorder = showSelectionBorder,
                showControls = showControls,
                onClick = onSlotClick,
                onFocused = onSlotFocused,
                firstSlotFocusRequester = firstSlotFocusRequester,
                firstControlFocusRequester = firstControlFocusRequester,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            GridPlayerCell(
                slot = slots.getOrNull(1),
                slotIndex = 1,
                focusedSlotIndex = focusedSlotIndex,
                showSelectionBorder = showSelectionBorder,
                showControls = showControls,
                onClick = onSlotClick,
                onFocused = onSlotFocused,
                firstSlotFocusRequester = null,
                firstControlFocusRequester = firstControlFocusRequester,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
        Row(modifier = Modifier.weight(1f)) {
            GridPlayerCell(
                slot = slots.getOrNull(2),
                slotIndex = 2,
                focusedSlotIndex = focusedSlotIndex,
                showSelectionBorder = showSelectionBorder,
                showControls = showControls,
                onClick = onSlotClick,
                onFocused = onSlotFocused,
                firstSlotFocusRequester = null,
                firstControlFocusRequester = firstControlFocusRequester,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            GridPlayerCell(
                slot = slots.getOrNull(3),
                slotIndex = 3,
                focusedSlotIndex = focusedSlotIndex,
                showSelectionBorder = showSelectionBorder,
                showControls = showControls,
                onClick = onSlotClick,
                onFocused = onSlotFocused,
                firstSlotFocusRequester = null,
                firstControlFocusRequester = firstControlFocusRequester,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun CenteredCompactLayout(
    slots: List<MultiViewSlot>,
    focusedSlotIndex: Int,
    showSelectionBorder: Boolean,
    showControls: Boolean,
    firstSlotFocusRequester: FocusRequester,
    firstControlFocusRequester: FocusRequester,
    onSlotClick: () -> Unit,
    onSlotFocused: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        if (slots.size == 1) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 72.dp),
                contentAlignment = Alignment.Center
            ) {
                GridPlayerCell(
                    slot = slots.first(),
                    slotIndex = slots.first().index,
                    focusedSlotIndex = focusedSlotIndex,
                    showSelectionBorder = showSelectionBorder,
                    showControls = showControls,
                    onClick = onSlotClick,
                    onFocused = onSlotFocused,
                    firstSlotFocusRequester = firstSlotFocusRequester,
                    firstControlFocusRequester = firstControlFocusRequester,
                    modifier = Modifier
                        .fillMaxWidth(0.5f)
                        .aspectRatio(16f / 9f)
                )
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 72.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                slots.forEachIndexed { index, slot ->
                    GridPlayerCell(
                        slot = slot,
                        slotIndex = slot.index,
                        focusedSlotIndex = focusedSlotIndex,
                        showSelectionBorder = showSelectionBorder,
                        showControls = showControls,
                        onClick = onSlotClick,
                        onFocused = onSlotFocused,
                        firstSlotFocusRequester = if (index == 0) firstSlotFocusRequester else null,
                        firstControlFocusRequester = firstControlFocusRequester,
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(16f / 9f)
                    )
                }
            }
        }
    }
}

@Composable
private fun GridPlayerCell(
    slot: MultiViewSlot?,
    slotIndex: Int,
    focusedSlotIndex: Int,
    showSelectionBorder: Boolean,
    showControls: Boolean,
    onClick: () -> Unit,
    onFocused: (Int) -> Unit,
    firstSlotFocusRequester: FocusRequester?,
    firstControlFocusRequester: FocusRequester,
    modifier: Modifier
) {
    PlayerCell(
        slot = slot,
        isFocused = focusedSlotIndex == slotIndex,
        showSelectionBorder = showSelectionBorder,
        onClick = onClick,
        modifier = modifier
            .then(if (firstSlotFocusRequester != null) Modifier.focusRequester(firstSlotFocusRequester) else Modifier)
            .focusProperties {
                canFocus = !showControls
                if (showControls) down = firstControlFocusRequester
            },
        onFocused = { onFocused(slotIndex) }
    )
}

@Composable
private fun PlayerCell(
    slot: MultiViewSlot?,
    isFocused: Boolean,
    showSelectionBorder: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    onFocused: () -> Unit
) {
    val showBorder = isFocused && showSelectionBorder

    TvClickableSurface(
        onClick = onClick,
        modifier = modifier
            .padding(2.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = androidx.compose.foundation.BorderStroke(
                    width = if (showBorder) 4.dp else 0.dp,
                    color = if (showBorder) Color.White else Color.Transparent
                )
            ),
            focusedBorder = Border.None,
            pressedBorder = Border.None
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color(0xFF111111),
            contentColor = Color.White,
            focusedContainerColor = Color(0xFF111111)
        ),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(4.dp))
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            when {
                slot == null || slot.isEmpty -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("+", color = Color(0xFF555555), fontSize = 32.sp)
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.multiview_empty_slot),
                            color = Color(0xFF555555),
                            fontSize = 12.sp
                        )
                    }
                }

                slot.isLoading -> {
                    CircularProgressIndicator(
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.size(32.dp)
                    )
                }

                slot.hasError -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("!", color = Color(0xFFFF5252), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text(
                            text = androidx.compose.ui.res.stringResource(R.string.multiview_stream_error),
                            color = Color(0xFFFF5252),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                        if (!slot.errorMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = slot.errorMessage,
                                color = Color(0xFFFFB3B3),
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 12.dp),
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                !slot.performanceBlockedReason.isNullOrBlank() -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = stringResource(R.string.multiview_policy_blocked),
                            color = Color(0xFFFFC107),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text(
                            text = slot.performanceBlockedReason.orEmpty(),
                            color = Color(0xFFFFE082),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }
                }

                else -> {
                    val engine = slot.playerEngine
                    if (engine != null) {
                        PlayerRenderView(
                            playerEngine = engine,
                            resizeMode = PlayerSurfaceResizeMode.FILL,
                            surfaceType = PlayerRenderSurfaceType.SURFACE_VIEW,
                            modifier = Modifier.fillMaxSize(),
                            configureView = {
                                isFocusable = false
                                isFocusableInTouchMode = false
                                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                            }
                        )
                    }

                    // Buffering indicator for active streams
                    val playbackState = slot.playerEngine?.playbackState?.collectAsStateWithLifecycle()
                    if (playbackState?.value == com.streamvault.player.PlaybackState.BUFFERING) {
                        CircularProgressIndicator(
                            color = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.TopStart)
                                .padding(6.dp),
                            strokeWidth = 2.dp
                        )
                    }

                    if (isFocused && !slot.isEmpty) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(12.dp)
                                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (slot.isAudioPinned) {
                                    stringResource(R.string.multiview_audio_pinned_badge)
                                } else {
                                    stringResource(R.string.multiview_audio_badge)
                                },
                                color = Primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(4.dp)
                    ) {
                        Text(
                            text = slot.title,
                            color = Color.White,
                            fontSize = 10.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiViewControlHud(
    focused: MultiViewSlot?,
    uiState: MultiViewUiState,
    firstControlFocusRequester: FocusRequester,
    onShowReplacementPicker: () -> Unit,
    onRemoveFocusedSlot: () -> Unit,
    onClearPinnedAudio: () -> Unit,
    onPinAudioToFocusedSlot: () -> Unit,
    onLoadPreset: (Int) -> Unit,
    onSavePreset: (Int) -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.58f), RoundedCornerShape(18.dp))
            .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(
                    R.string.multiview_policy_summary,
                    uiState.performancePolicy.tier.name.lowercase().replaceFirstChar { it.uppercase() },
                    uiState.performancePolicy.maxActiveSlots
                ),
                color = Color.White.copy(alpha = 0.86f),
                style = MaterialTheme.typography.labelMedium
            )
            Text(text = "•", color = Color.White.copy(alpha = 0.42f))
            Text(
                text = stringResource(
                    R.string.multiview_telemetry_snapshot,
                    uiState.telemetry.activeSlots,
                    uiState.telemetry.standbySlots,
                    uiState.telemetry.bufferingSlots,
                    uiState.telemetry.errorSlots
                ),
                color = Color.White.copy(alpha = 0.78f),
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (focused != null && focused.title.isNotBlank()) {
            Text(
                text = stringResource(R.string.multiview_focused_prefix, focused.title),
                color = Primary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TvButton(
                onClick = onShowReplacementPicker,
                enabled = uiState.replacementCandidates.isNotEmpty(),
                modifier = Modifier.focusRequester(firstControlFocusRequester)
            ) {
                Text(stringResource(R.string.multiview_replace_slot))
            }
            TvButton(
                onClick = onRemoveFocusedSlot,
                enabled = focused != null && !focused.isEmpty
            ) {
                Text(stringResource(R.string.multiview_remove_slot))
            }
            if (uiState.pinnedAudioSlotIndex == uiState.focusedSlotIndex) {
                TvButton(onClick = onClearPinnedAudio) {
                    Text(stringResource(R.string.multiview_audio_follow_focus))
                }
            } else {
                TvButton(
                    onClick = onPinAudioToFocusedSlot,
                    enabled = focused != null && !focused.isEmpty
                ) {
                    Text(stringResource(R.string.multiview_pin_audio))
                }
            }
        }

        if (uiState.presets.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                uiState.presets.forEach { preset ->
                    val presetLabel = stringResource(R.string.multiview_preset_label, preset.index + 1)
                    TvButton(onClick = { onLoadPreset(preset.index) }) {
                        Text(
                            text = if (preset.isPopulated) {
                                "$presetLabel (${preset.channelCount})"
                            } else {
                                presetLabel
                            }
                        )
                    }
                    TvButton(onClick = { onSavePreset(preset.index) }) {
                        Text(text = stringResource(R.string.multiview_preset_save, preset.index + 1))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplaceSlotDialog(
    pickerState: MultiViewPickerState,
    parentalControlLevel: Int,
    onDismiss: () -> Unit,
    onSelectCategory: (com.streamvault.domain.model.Category) -> Unit,
    onBackToCategories: () -> Unit,
    onUpdateSearch: (String) -> Unit,
    onVerifyPin: suspend (String) -> Boolean,
    onUnlockCategory: (Long) -> Unit,
    onReplace: (com.streamvault.domain.model.Channel) -> Unit
) {
    var catSearch by remember { mutableStateOf("") }
    val selectedCategory = pickerState.selectedCategory
    val searchQuery = if (selectedCategory == null) catSearch else pickerState.searchQuery
    val onSearchChange: (String) -> Unit = if (selectedCategory == null) {
        { catSearch = it }
    } else {
        onUpdateSearch
    }

    // Parental PIN state
    val scope = rememberCoroutineScope()
    val incorrectPinMessage = stringResource(R.string.home_incorrect_pin)
    var showPinDialog by remember { mutableStateOf(false) }
    var pinError by remember { mutableStateOf<String?>(null) }
    var pendingLockedCategory by remember { mutableStateOf<com.streamvault.domain.model.Category?>(null) }

    fun isCategoryLocked(cat: com.streamvault.domain.model.Category): Boolean =
        parentalControlLevel in 1..2 && (cat.isAdult || cat.isUserProtected)

    PremiumDialog(
        title = if (selectedCategory == null) stringResource(R.string.multiview_replace_title) else selectedCategory.name,
        subtitle = if (selectedCategory == null) "Choose a category, then pick a channel" else null,
        onDismissRequest = onDismiss,
        widthFraction = 0.62f,
        content = {
            // Search bar
            BasicTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White.copy(alpha = 0.07f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
                singleLine = true,
                cursorBrush = SolidColor(Primary),
                decorationBox = { innerTextField ->
                    Box {
                        if (searchQuery.isEmpty()) {
                            Text(
                                text = if (selectedCategory == null) "Search categories\u2026" else "Search channels\u2026",
                                color = Color.White.copy(alpha = 0.38f),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        innerTextField()
                    }
                }
            )

            when {
                pickerState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(240.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = Primary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                selectedCategory == null -> {
                    val filtered = if (catSearch.isBlank()) pickerState.categories
                        else pickerState.categories.filter { it.name.contains(catSearch, ignoreCase = true) }
                    if (filtered.isEmpty()) {
                        Text(
                            text = "No categories found",
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filtered, key = { it.id }) { category ->
                                val locked = isCategoryLocked(category)
                                PremiumDialogActionButton(
                                    label = if (locked) "\uD83D\uDD12 ${category.name}" else category.name,
                                    onClick = {
                                        if (locked) {
                                            pendingLockedCategory = category
                                            pinError = null
                                            showPinDialog = true
                                        } else {
                                            onSelectCategory(category)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
                else -> {
                    val filtered = pickerState.filteredChannels
                    if (filtered.isEmpty() && !pickerState.isLoading) {
                        Text(
                            text = "No channels found",
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.height(320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filtered, key = { it.id }) { channel ->
                                PremiumDialogActionButton(
                                    label = channel.name,
                                    onClick = { onReplace(channel) }
                                )
                            }
                        }
                    }
                }
            }
        },
        footer = {
            if (selectedCategory != null) {
                PremiumDialogFooterButton(
                    label = "\u2190 Back",
                    onClick = onBackToCategories
                )
            }
            PremiumDialogFooterButton(
                label = stringResource(R.string.settings_cancel),
                onClick = onDismiss
            )
        }
    )

    if (showPinDialog) {
        PinEntryDialog(
            title = stringResource(R.string.pin_dialog_title),
            cancelLabel = stringResource(R.string.pin_dialog_cancel),
            onDismissRequest = {
                showPinDialog = false
                pinError = null
                pendingLockedCategory = null
            },
            onPinEntered = { pin ->
                scope.launch {
                    if (onVerifyPin(pin)) {
                        val cat = pendingLockedCategory
                        if (cat != null) {
                            onUnlockCategory(cat.id)
                            showPinDialog = false
                            pinError = null
                            pendingLockedCategory = null
                            onSelectCategory(cat)
                        }
                    } else {
                        pinError = incorrectPinMessage
                    }
                }
            },
            error = pinError
        )
    }
}
