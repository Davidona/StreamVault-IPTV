package com.streamvault.app.ui.screens.epg

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.domain.playback.isArchivePlayable
import com.streamvault.app.ui.screens.epg.CompactGuideProgramDialog
import com.streamvault.app.ui.screens.epg.EpgGrid
import com.streamvault.app.ui.screens.epg.EpgUiState
import com.streamvault.app.ui.screens.epg.EpgViewModel
import com.streamvault.app.ui.screens.epg.GuideMessageState
import com.streamvault.feature.live.presentation.epg.LiveGuideNowProvider
import com.streamvault.feature.live.presentation.epg.LiveGuideSearchOverlay
import com.streamvault.feature.live.presentation.epg.LiveGuideSearchOverlayLabels
import com.streamvault.feature.live.presentation.epg.LiveGuideCategoryPickerDialog
import com.streamvault.feature.live.presentation.epg.LiveGuideCategoryPickerLabels
import com.streamvault.app.ui.screens.epg.GuideToolbarButton
import com.streamvault.feature.live.presentation.epg.currentLiveGuideNow
import com.streamvault.app.ui.screens.epg.isGuideCategoryLocked
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.VirtualCategoryIds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PlayerTransparentGuideOverlay(
    uiState: EpgUiState,
    currentPlayerChannelId: Long,
    onDismiss: () -> Unit,
    onJumpToNow: () -> Unit,
    onSelectCategory: (Category) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onWatchChannel: (Channel) -> Unit,
    onWatchArchive: (Channel, Program) -> Unit,
    onRequestMoreChannels: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focusedChannel by remember(uiState.channels, currentPlayerChannelId) {
        mutableStateOf(uiState.channels.firstOrNull { it.id == currentPlayerChannelId } ?: uiState.channels.firstOrNull())
    }
    var focusedProgram by remember { mutableStateOf<Program?>(null) }
    var selectedProgram by remember { mutableStateOf<Pair<Channel, Program>?>(null) }
    var showCategoryPicker by remember { mutableStateOf(false) }
    var showSearchOverlay by remember { mutableStateOf(false) }

    BackHandler(onBack = onDismiss)

    LiveGuideNowProvider {
        val now = currentLiveGuideNow()
        val headerDateFormat = remember { SimpleDateFormat("EEE, MMM d, h:mm a", Locale.getDefault()) }
        val headerTitle = focusedProgram?.title ?: focusedChannel?.name ?: stringResource(R.string.epg_title)
        val favoritesLabel = stringResource(R.string.epg_favorites_filter_favorites)
        val selectedPickerCategoryId = if (uiState.showFavoritesOnly) {
            VirtualCategoryIds.FAVORITES
        } else {
            uiState.selectedCategoryId
        }
        val selectedCategoryName = remember(
            uiState.categories,
            uiState.selectedCategoryId,
            uiState.showFavoritesOnly,
            favoritesLabel
        ) {
            if (uiState.showFavoritesOnly) {
                favoritesLabel
            } else {
                uiState.categories
                    .firstOrNull { it.id == uiState.selectedCategoryId }
                    ?.name
                    .orEmpty()
            }
        }
        val contextLabel = remember(
            uiState.providerSourceLabel,
            selectedCategoryName
        ) {
            listOf(uiState.providerSourceLabel, selectedCategoryName)
                .filter { it.isNotBlank() }
                .joinToString("  |  ")
        }

        Box(
            modifier = modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.30f),
                            Color.Black.copy(alpha = 0.12f),
                            Color.Black.copy(alpha = 0.22f)
                        )
                    )
                )
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(start = 18.dp, top = 16.dp, end = 18.dp),
                shape = RoundedCornerShape(18.dp),
                colors = SurfaceDefaults.colors(
                    containerColor = Color.Black.copy(alpha = 0.32f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = headerDateFormat.format(Date(now)),
                            style = MaterialTheme.typography.labelMedium,
                            color = Primary
                        )
                        Text(
                            text = headerTitle,
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (contextLabel.isNotBlank()) {
                            Text(
                                text = contextLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GuideToolbarButton(
                            label = selectedCategoryName.ifBlank { stringResource(R.string.epg_filter_short) },
                            modifier = Modifier.widthIn(min = 180.dp, max = 280.dp),
                            onClick = { showCategoryPicker = true },
                            onFocused = {}
                        )
                        GuideToolbarButton(
                            label = stringResource(R.string.epg_jump_now),
                            onClick = onJumpToNow,
                            onFocused = {}
                        )
                        GuideToolbarButton(
                            label = stringResource(R.string.epg_search_label),
                            onClick = { showSearchOverlay = true },
                            onFocused = {}
                        )
                        GuideToolbarButton(
                            label = stringResource(R.string.settings_cancel),
                            onClick = onDismiss,
                            onFocused = {}
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 18.dp, top = 112.dp, end = 18.dp, bottom = 16.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Color.Black.copy(alpha = 0.16f))
            ) {
                when {
                    uiState.isInitialLoading -> {
                        GuideMessageState(
                            title = stringResource(R.string.epg_loading),
                            subtitle = contextLabel.takeIf { it.isNotBlank() },
                            actionLabel = null,
                            onAction = null
                        )
                    }
                    uiState.channels.isEmpty() -> {
                        GuideMessageState(
                            title = stringResource(R.string.epg_title),
                            subtitle = uiState.error
                                ?.takeUnless { it == EpgViewModel.NO_ACTIVE_PROVIDER }
                                ?: stringResource(R.string.epg_no_schedule),
                            actionLabel = null,
                            onAction = null
                        )
                    }
                    else -> {
                        EpgGrid(
                            channels = uiState.channels,
                            favoriteChannelIds = uiState.favoriteChannelIds,
                            programsByChannel = uiState.programsByChannel,
                            guideWindowStart = uiState.guideWindowStart,
                            guideWindowEnd = uiState.guideWindowEnd,
                            density = uiState.selectedDensity,
                            transparentOverlay = true,
                            initialFocusedChannelId = currentPlayerChannelId.takeIf { it > 0L },
                            onChannelClick = { channel ->
                                onWatchChannel(channel)
                                onDismiss()
                            },
                            onProgramClick = { channel, program ->
                                selectedProgram = channel to program
                            },
                            onChannelFocused = { channel, program, _ ->
                                focusedChannel = channel
                                focusedProgram = program
                            },
                            onProgramFocused = { channel, program, _ ->
                                focusedChannel = channel
                                focusedProgram = program
                            },
                            onRequestMoreChannels = onRequestMoreChannels,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        if (showCategoryPicker) {
            LiveGuideCategoryPickerDialog(
                categories = uiState.categories,
                selectedCategoryId = selectedPickerCategoryId,
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
                    if (!isGuideCategoryLocked(category, uiState.parentalControlLevel)) {
                        showCategoryPicker = false
                        onSelectCategory(category)
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
                onQueryChange = onSearchQueryChange,
                onClear = onClearSearch,
                onDismiss = { showSearchOverlay = false }
            )
        }

        selectedProgram?.let { (channel, program) ->
            val canWatchArchive = channel.id == currentPlayerChannelId && channel.isArchivePlayable(program, now)
            CompactGuideProgramDialog(
                channel = channel,
                program = program,
                providerLabel = uiState.providerSourceLabel,
                now = now,
                onDismiss = { selectedProgram = null },
                onWatchLive = {
                    selectedProgram = null
                    onWatchChannel(channel)
                    onDismiss()
                },
                onWatchArchive = if (canWatchArchive) {
                    {
                        selectedProgram = null
                        onWatchArchive(channel, program)
                        onDismiss()
                    }
                } else {
                    null
                },
                reminderButtonLabel = null,
                onToggleReminder = null,
                onScheduleRecording = null,
                onScheduleDailyRecording = null,
                onScheduleWeeklyRecording = null
            )
        }
    }
}
