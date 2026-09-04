package com.streamvault.app.ui.screens.player.overlay

import androidx.activity.compose.BackHandler
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.app.ui.design.AppColors
import com.streamvault.app.ui.model.archivePlaybackCapability
import com.streamvault.app.ui.screens.player.PlayerDiagnosticsUiState
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.createTimeFormat
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import com.streamvault.player.PlayerStats
import java.util.Date
import kotlinx.coroutines.launch
import com.streamvault.app.ui.design.AppColors.Brand as Primary
import com.streamvault.app.ui.design.AppColors.SurfaceElevated as SurfaceVariant
import com.streamvault.app.ui.design.AppColors.TextSecondary as TextSecondary
import com.streamvault.app.ui.design.AppColors.TextTertiary as OnSurfaceDim

@Composable
fun ChannelListOverlay(
    channels: List<Channel>,
    recentChannels: List<Channel>,
    currentChannelId: Long,
    overlayFocusRequester: FocusRequester = remember { FocusRequester() },
    lastVisitedCategoryName: String? = null,
    onOpenLastGroup: () -> Unit = {},
    onOpenCategories: () -> Unit = {},
    onSelectChannel: (Long) -> Unit,
    onDismiss: () -> Unit,
    onOverlayInteracted: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(channels, currentChannelId) {
        channels.indexOfFirst { it.id == currentChannelId }.coerceAtLeast(0)
    }
    val channelNumbersById = remember(channels) {
        channels.mapIndexed { index, channel ->
            channel.id to (channel.number.takeIf { it > 0 } ?: (index + 1))
        }.toMap()
    }
    val canScrollUp by remember { derivedStateOf { listState.canScrollBackward } }
    val canScrollDown by remember { derivedStateOf { listState.canScrollForward } }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val headerItemCount = remember(lastVisitedCategoryName, recentChannels) {
        var count = 2
        if (!lastVisitedCategoryName.isNullOrBlank()) count++
        if (recentChannels.isNotEmpty()) count++
        count
    }

    LaunchedEffect(channels, currentIndex, headerItemCount) {
        if (channels.isNotEmpty()) {
            listState.scrollToItem(headerItemCount + currentIndex)
        }
    }

    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f))
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTelevisionDevice = rememberIsTelevisionDevice()
            val panelModifier = if (maxWidth < 700.dp) {
                Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight()
                    .padding(20.dp)
            } else if (!isTelevisionDevice && maxWidth < 1280.dp) {
                Modifier
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight()
                    .padding(20.dp)
            } else {
                Modifier
                    .width(540.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(20.dp)
            }

            Box(modifier = panelModifier) {
                PlayerOverlayPanel(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 10.dp, bottom = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.player_channel_list_title, channels.size),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Primary
                                )
                                if (!lastVisitedCategoryName.isNullOrBlank()) {
                                    TvClickableSurface(
                                        onClick = {
                                            onOverlayInteracted()
                                            onOpenLastGroup()
                                        },
                                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                                        colors = ClickableSurfaceDefaults.colors(
                                            containerColor = AppColors.SurfaceEmphasis,
                                            focusedContainerColor = Primary
                                        ),
                                        modifier = Modifier.onFocusChanged {
                                            if (it.isFocused) onOverlayInteracted()
                                        }
                                    ) {
                                        Text(
                                            text = stringResource(R.string.player_last_group_label, lastVisitedCategoryName),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            }
                        }
                        if (!lastVisitedCategoryName.isNullOrBlank()) {
                            item {
                                Text(
                                    text = stringResource(R.string.player_last_group_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceDim,
                                    modifier = Modifier.padding(horizontal = 8.dp)
                                )
                            }
                        }
                        item {
                            Text(
                                text = stringResource(R.string.player_channel_list_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceDim,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        if (recentChannels.isNotEmpty()) {
                            item {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.player_recent_channels),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = OnSurfaceDim,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
                                    )
                                    LazyRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                    ) {
                                        itemsIndexed(
                                            recentChannels,
                                            key = { index, channel ->
                                                "recent:${channel.id}:${channel.streamId}:${channel.epgChannelId.orEmpty()}:${index}"
                                            }
                                        ) { index, channel ->
                                            TvClickableSurface(
                                                onClick = {
                                                    onOverlayInteracted()
                                                    onSelectChannel(channel.id)
                                                    onDismiss()
                                                },
                                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                                                colors = ClickableSurfaceDefaults.colors(
                                                    containerColor = AppColors.SurfaceEmphasis,
                                                    focusedContainerColor = Primary
                                                ),
                                                modifier = Modifier.onFocusChanged {
                                                    if (it.isFocused) onOverlayInteracted()
                                                }
                                            ) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    val recentNumber = channelNumbersById[channel.id]
                                                        ?.toString()
                                                        ?.padStart(2, '0')
                                                        ?: channel.number
                                                            .takeIf { it > 0 }
                                                            ?.toString()
                                                            ?.padStart(2, '0')
                                                        ?: "--"
                                                    Text(
                                                        text = recentNumber,
                                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
                                                        color = Color.White.copy(alpha = 0.75f)
                                                    )
                                                    Text(
                                                        text = channel.name,
                                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                                                        color = Color.White,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        items(channels.size) { index ->
                            val channel = channels[index]
                            val isSelected = channel.id == currentChannelId
                            val shouldRequestFocus = isSelected
                            val channelNumber = channel.number.takeIf { it > 0 } ?: (index + 1)
                            var isFocused by remember { mutableStateOf(false) }
                            val bgColor = when {
                                isFocused -> Primary
                                isSelected -> Primary.copy(alpha = 0.20f)
                                else -> AppColors.Surface.copy(alpha = 0.68f)
                            }

                            TvClickableSurface(
                                onClick = {
                                    onSelectChannel(channel.id)
                                    onDismiss()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .onFocusChanged { focusState ->
                                        isFocused = focusState.isFocused
                                        if (focusState.isFocused) {
                                            onOverlayInteracted()
                                        }
                                    }
                                    .then(
                                        if (shouldRequestFocus) Modifier.focusRequester(overlayFocusRequester)
                                        else Modifier
                                    ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(16.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = bgColor,
                                    focusedContainerColor = bgColor
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(
                                        text = channelNumber.toString().padStart(2, '0'),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 13.sp),
                                        color = Color.White.copy(alpha = 0.72f),
                                        textAlign = TextAlign.Start,
                                        modifier = Modifier.width(32.dp)
                                    )
                                    Text(
                                        text = channel.name,
                                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 17.sp),
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = if (isFocused) TextOverflow.Clip else TextOverflow.Ellipsis,
                                        modifier = Modifier
                                            .weight(1f)
                                            .then(
                                                if (isFocused) {
                                                    Modifier.basicMarquee(
                                                        iterations = Int.MAX_VALUE,
                                                        initialDelayMillis = 600,
                                                        repeatDelayMillis = 900,
                                                        velocity = 20.dp
                                                    )
                                                } else {
                                                    Modifier
                                                }
                                            )
                                    )
                                    if (isSelected) {
                                        StatusPill(
                                            label = stringResource(R.string.player_channel_selected),
                                            containerColor = AppColors.BrandMuted
                                        )
                                    }
                                    if (channel.archivePlaybackCapability().canBuildReplayCandidate) {
                                        StatusPill(
                                            label = stringResource(R.string.player_archive_badge),
                                            containerColor = AppColors.Warning,
                                            contentColor = Color.Black
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = canScrollUp,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(AppColors.Canvas.copy(alpha = 0.9f), Color.Transparent)
                            ),
                            RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
                        ),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Text(
                        text = "\u25b2",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = canScrollDown,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .background(
                            Brush.verticalGradient(
                                colors = listOf(Color.Transparent, AppColors.Canvas.copy(alpha = 0.9f))
                            ),
                            RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp)
                        ),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text(
                        text = "\u25bc",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
            }

            TvClickableSurface(
                onClick = {
                    onOverlayInteracted()
                    onOpenCategories()
                },
                modifier = Modifier
                    .align(if (isRtl) Alignment.CenterEnd else Alignment.CenterStart)
                    .offset(x = if (isRtl) 28.dp else (-28).dp)
                    .onFocusChanged { if (it.isFocused) onOverlayInteracted() },
                shape = ClickableSurfaceDefaults.shape(
                    if (isRtl) RoundedCornerShape(topEnd = 10.dp, bottomEnd = 10.dp)
                    else RoundedCornerShape(topStart = 10.dp, bottomStart = 10.dp)
                ),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = AppColors.SurfaceEmphasis.copy(alpha = 0.92f),
                    focusedContainerColor = Primary
                )
            ) {
                Column(
                    modifier = Modifier
                        .width(28.dp)
                        .height(96.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = if (isRtl) "\u25ba" else "\u25c4",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

@Composable
fun EpgOverlay(
    currentChannel: Channel?,
    displayChannelNumber: Int,
    currentProgram: Program?,
    nextProgram: Program?,
    upcomingPrograms: List<Program>,
    onDismiss: () -> Unit,
    overlayFocusRequester: FocusRequester = remember { FocusRequester() },
    onOpenFullGuide: (() -> Unit)? = null,
    onOpenArchiveBrowser: (() -> Unit)? = null,
    onOverlayInteracted: () -> Unit = {}
) {
    val appTimeFormat = LocalAppTimeFormat.current
    val timeFormat = remember(appTimeFormat) { appTimeFormat.createTimeFormat() }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val layoutDirection = LocalLayoutDirection.current
    val openFullGuideKeyCode = if (layoutDirection == LayoutDirection.Rtl) {
        KeyEvent.KEYCODE_DPAD_LEFT
    } else {
        KeyEvent.KEYCODE_DPAD_RIGHT
    }
    val filteredUpcoming = remember(upcomingPrograms, currentProgram, nextProgram) {
        upcomingPrograms.filter { it.id != currentProgram?.id && it.id != nextProgram?.id }
    }
    val displayPrograms = remember(filteredUpcoming, nextProgram) {
        if (nextProgram != null) listOf(nextProgram) + filteredUpcoming else filteredUpcoming
    }

    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(overlayFocusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                    return@onPreviewKeyEvent false
                }
                if (event.nativeKeyEvent.keyCode == openFullGuideKeyCode && onOpenFullGuide != null) {
                    onOverlayInteracted()
                    onOpenFullGuide()
                    true
                } else {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            onOverlayInteracted()
                            coroutineScope.launch {
                                val nextIndex = (listState.firstVisibleItemIndex + 1)
                                    .coerceAtMost(listState.layoutInfo.totalItemsCount - 1)
                                if (nextIndex >= 0) {
                                    listState.animateScrollToItem(nextIndex, listState.firstVisibleItemScrollOffset)
                                }
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            onOverlayInteracted()
                            coroutineScope.launch {
                                val previousIndex = (listState.firstVisibleItemIndex - 1).coerceAtLeast(0)
                                listState.animateScrollToItem(previousIndex, listState.firstVisibleItemScrollOffset)
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
            .background(Color.Black.copy(alpha = 0.18f))
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .align(Alignment.CenterEnd)
        ) {
            val isTelevisionDevice = rememberIsTelevisionDevice()
            val panelModifier = if (maxWidth < 700.dp) {
                Modifier
                    .fillMaxWidth(0.9f)
                    .padding(24.dp)
            } else if (!isTelevisionDevice && maxWidth < 1280.dp) {
                Modifier
                    .fillMaxWidth(0.54f)
                    .padding(24.dp)
            } else {
                Modifier
                    .width(520.dp)
                    .padding(24.dp)
            }

            PlayerOverlayPanel(modifier = panelModifier) {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        Text(
                            text = stringResource(R.string.epg_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = Primary,
                            fontWeight = FontWeight.Bold
                        )
                        if (currentChannel != null) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.channel_number_name_format, displayChannelNumber, currentChannel.name),
                                style = MaterialTheme.typography.headlineSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            val archiveCapability = currentChannel.archivePlaybackCapability()
                            if (archiveCapability.canBuildReplayCandidate) {
                                val catchUpLabel = archiveCapability.windowDays?.let { days ->
                                    stringResource(R.string.epg_catchup_available, days)
                                } ?: stringResource(R.string.epg_catchup_available_unknown)
                                Spacer(Modifier.height(8.dp))
                                if (onOpenArchiveBrowser != null) {
                                    QuickActionButton(
                                        icon = stringResource(R.string.player_catchup_badge),
                                        label = catchUpLabel,
                                        onClick = {
                                            onOverlayInteracted()
                                            onOpenArchiveBrowser()
                                        },
                                        onInteraction = onOverlayInteracted
                                    )
                                } else {
                                    StatusPill(
                                        label = catchUpLabel,
                                        containerColor = AppColors.BrandMuted
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.player_epg_overlay_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                    }

                    item {
                        androidx.compose.material3.HorizontalDivider(color = SurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.epg_now_playing),
                            style = MaterialTheme.typography.labelMedium,
                            color = Primary,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(8.dp))
                        if (currentProgram != null) {
                            Text(
                                currentProgram.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.time_range_format, timeFormat.format(Date(currentProgram.startTime)), timeFormat.format(Date(currentProgram.endTime))),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextSecondary
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    text = stringResource(R.string.label_duration_min, currentProgram.durationMinutes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceDim
                                )
                                if (currentProgram.lang.isNotEmpty()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = currentProgram.lang.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Primary.copy(alpha = 0.7f)
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            val now = System.currentTimeMillis()
                            val start = currentProgram.startTime
                            val end = currentProgram.endTime
                            if (start in 1..<end) {
                                val progress = (now - start).toFloat() / (end - start)
                                val remainingMin = ((end - now) / 60000).toInt().coerceAtLeast(0)
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { progress.coerceIn(0f, 1f) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp),
                                    color = Primary,
                                    trackColor = AppColors.SurfaceEmphasis
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = stringResource(R.string.player_minutes_remaining, remainingMin),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = OnSurfaceDim
                                )
                            }
                            if (!currentProgram.description.isNullOrEmpty()) {
                                val description = currentProgram.description.orEmpty()
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White.copy(alpha = 0.7f),
                                    maxLines = 6,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        } else {
                            Text(stringResource(R.string.epg_no_info), color = OnSurfaceDim)
                        }
                    }

                    if (upcomingPrograms.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.HorizontalDivider(color = SurfaceVariant)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.epg_upcoming_schedule),
                                style = MaterialTheme.typography.labelMedium,
                                color = Primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        items(displayPrograms.size) { index ->
                            val program = displayPrograms[index]
                            val isNext = index == 0 && nextProgram != null

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(
                                        if (isNext) Primary.copy(alpha = 0.08f) else Color.Transparent
                                    )
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    if (isNext) {
                                        Text(
                                            text = stringResource(R.string.epg_up_next),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Primary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(Modifier.height(4.dp))
                                    }
                                    Text(
                                        text = program.title,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isNext) Color.White else Color.White.copy(alpha = 0.8f),
                                        fontWeight = if (isNext) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(2.dp))
                                    Row {
                                        Text(
                                            text = stringResource(R.string.time_range_format, timeFormat.format(Date(program.startTime)), timeFormat.format(Date(program.endTime))),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = TextSecondary
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.label_duration_min, program.durationMinutes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OnSurfaceDim
                                        )
                                        if (program.hasArchive) {
                                            Spacer(Modifier.width(8.dp))
                                            StatusPill(
                                                label = stringResource(R.string.player_archive_badge),
                                                containerColor = AppColors.Warning,
                                                contentColor = Color.Black
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (onOpenFullGuide != null) {
            Box(
                modifier = Modifier
                    .align(if (layoutDirection == LayoutDirection.Rtl) Alignment.CenterStart else Alignment.CenterEnd)
                    .padding(horizontal = 8.dp)
                    .width(24.dp)
                    .height(72.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Primary.copy(alpha = 0.58f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (layoutDirection == LayoutDirection.Rtl) "<" else ">",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.92f),
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun DiagnosticsOverlay(
    stats: PlayerStats,
    diagnostics: PlayerDiagnosticsUiState,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    val canScrollUp by remember { derivedStateOf { scrollState.value > 0 } }
    val canScrollDown by remember { derivedStateOf { scrollState.value < scrollState.maxValue } }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    PlayerOverlayPanel(modifier = modifier.width(680.dp)) {
        Box(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) {
                            return@onPreviewKeyEvent false
                        }
                        when (event.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                coroutineScope.launch {
                                    scrollState.animateScrollTo((scrollState.value - 120).coerceAtLeast(0))
                                }
                                true
                            }
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                coroutineScope.launch {
                                    scrollState.animateScrollTo((scrollState.value + 120).coerceAtMost(scrollState.maxValue))
                                }
                                true
                            }
                            else -> false
                        }
                    }
                    .verticalScroll(scrollState)
                    .padding(top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_diagnostics_title),
                    color = Primary,
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                    fontWeight = FontWeight.Bold
                )
                val avSyncPathLabel = when {
                    !diagnostics.audioVideoSyncEnabled -> stringResource(R.string.player_diagnostics_av_sync_stock)
                    diagnostics.audioVideoSyncSinkActive -> stringResource(R.string.player_diagnostics_av_sync_custom)
                    else -> stringResource(R.string.player_diagnostics_av_sync_waiting)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(0.48f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        PlayerOverlaySectionLabel(stringResource(R.string.player_diagnostics_section_source))
                        if (diagnostics.providerName.isNotBlank()) {
                            PlayerMetaRow(stringResource(R.string.player_diagnostics_provider), diagnostics.providerName)
                        }
                        if (diagnostics.providerSourceLabel.isNotBlank()) {
                            PlayerMetaRow(stringResource(R.string.player_diagnostics_source), diagnostics.providerSourceLabel)
                        }
                        PlayerOverlaySectionLabel(stringResource(R.string.player_diagnostics_section_playback))
                        PlayerMetaRow(
                            stringResource(R.string.player_diagnostics_audio_decoder_mode),
                            diagnostics.audioDecoderMode.name
                        )
                        PlayerMetaRow(
                            stringResource(R.string.player_diagnostics_video_decoder_mode),
                            diagnostics.videoDecoderMode.name
                        )
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_active_decoder), diagnostics.activeDecoderName)
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_surface), diagnostics.renderSurfaceType)
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_stream_class), diagnostics.streamClassLabel)
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_playback_state), diagnostics.playbackStateLabel)
                        if (diagnostics.archiveSupportLabel.isNotBlank()) {
                            PlayerMetaRow(stringResource(R.string.player_diagnostics_archive), diagnostics.archiveSupportLabel)
                        }
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_alternates), diagnostics.alternativeStreamCount.toString())
                        if (diagnostics.channelErrorCount > 0) {
                            PlayerMetaRow(stringResource(R.string.player_diagnostics_channel_errors), diagnostics.channelErrorCount.toString())
                        }
                        PlayerOverlaySectionLabel(stringResource(R.string.player_diagnostics_section_video))
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_resolution), "${stats.width}x${stats.height}")
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_video_codec), stats.videoCodec)
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_frame_rate), formatDiagnosticsFrameRateLabel(stats))
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_stream_bitrate), formatBitsPerSecondLabel(stats.measuredBitrate))
                        if (stats.videoBitrate > 0) {
                            PlayerMetaRow(stringResource(R.string.player_diagnostics_video_bitrate), "${stats.videoBitrate / 1000} kbps")
                        }
                        if (stats.bandwidthEstimate > 0L) {
                            PlayerMetaRow(stringResource(R.string.player_diagnostics_bandwidth_estimate), formatBitsPerSecondLabel(stats.bandwidthEstimate))
                        }
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_dropped_frames), stats.droppedFrames.toString())
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_video_stalls), diagnostics.videoStallCount.toString())
                        if (diagnostics.lastVideoFrameAgoMs > 0L) {
                            PlayerMetaRow(stringResource(R.string.player_diagnostics_last_frame), "${diagnostics.lastVideoFrameAgoMs} ms")
                        }
                        if (stats.ttffMs > 0L) {
                            PlayerMetaRow("TTFF", "${stats.ttffMs} ms")
                        }
                    }

                    Column(
                        modifier = Modifier.fillMaxWidth(0.48f),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        PlayerOverlaySectionLabel(stringResource(R.string.player_diagnostics_section_audio))
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_audio_codec), stats.audioCodec)
                        PlayerMetaRow(
                            stringResource(R.string.player_diagnostics_audio_decoder),
                            diagnostics.activeAudioDecoderName
                        )
                        PlayerMetaRow(
                            stringResource(R.string.player_diagnostics_audio_output_path),
                            diagnostics.audioOutputPath
                        )
                        PlayerMetaRow(
                            stringResource(R.string.player_diagnostics_ffmpeg),
                            if (diagnostics.ffmpegAvailable) {
                                diagnostics.ffmpegVersion?.let { "Available ($it)" } ?: "Available"
                            } else {
                                "Unavailable"
                            }
                        )
                        PlayerMetaRow(
                            stringResource(R.string.player_diagnostics_compatibility_source),
                            diagnostics.compatibilityDecisionSource
                        )
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_av_sync), avSyncPathLabel)
                        PlayerMetaRow(stringResource(R.string.player_diagnostics_av_offset), formatOffsetLabel(diagnostics.audioVideoOffsetMs))
                        if (diagnostics.compatibilityDecisionSource != "DEFAULT") {
                            PlayerMetaRow(
                                stringResource(R.string.player_diagnostics_compatibility_note),
                                stringResource(R.string.player_diagnostics_compatibility_note_value),
                                maxLines = 3
                            )
                        }
                    }
                }

                PlayerOverlaySectionLabel(stringResource(R.string.player_diagnostics_section_recovery))
                diagnostics.lastFailureReason?.let { reason ->
                    PlayerMetaRow(stringResource(R.string.player_diagnostics_last_failure), reason, maxLines = 3)
                }
                if (diagnostics.recentRecoveryActions.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.player_diagnostics_recovery_actions),
                        color = Primary,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Bold
                    )
                    diagnostics.recentRecoveryActions.forEach { action ->
                        Text(
                            text = action,
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (diagnostics.troubleshootingHints.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.player_diagnostics_troubleshooting),
                        color = Primary,
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                        fontWeight = FontWeight.Bold
                    )
                    diagnostics.troubleshootingHints.forEach { hint ->
                        Text(
                            text = hint,
                            color = AppColors.TextSecondary,
                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = canScrollUp,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                DiagnosticsScrollCue(label = "^")
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = canScrollDown,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                DiagnosticsScrollCue(label = "v")
            }
        }
    }
}

@Composable
private fun DiagnosticsScrollCue(label: String) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(AppColors.Canvas.copy(alpha = 0.78f))
            .padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
        Text(
            text = label,
            color = AppColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Measured rendered frame rate, with the declared container value in brackets when the two are
 * both known. Falls back to the declared value alone, then "Unknown". Many live MPEG-TS streams
 * declare no frame rate at all, which is why the measured value comes first.
 */
private fun formatDiagnosticsFrameRateLabel(stats: PlayerStats): String {
    val measured = com.streamvault.domain.model.formatFrameRateLabel(stats.measuredFrameRate)
    val declared = com.streamvault.domain.model.formatFrameRateLabel(stats.frameRate)
    return when {
        measured != null && declared != null && measured != declared -> "$measured fps (stream $declared)"
        measured != null -> "$measured fps"
        declared != null -> "$declared fps"
        else -> "Unknown"
    }
}

/** Bits-per-second as "8.4 Mbps" / "640 kbps", or "Unknown" for 0. */
private fun formatBitsPerSecondLabel(bps: Long): String = when {
    bps <= 0L -> "Unknown"
    bps >= 1_000_000L -> String.format(java.util.Locale.US, "%.1f Mbps", bps / 1_000_000.0)
    else -> "${bps / 1000} kbps"
}

private fun formatOffsetLabel(offsetMs: Int): String = when {
    offsetMs > 0 -> "+$offsetMs ms"
    offsetMs < 0 -> "$offsetMs ms"
    else -> "0 ms"
}

@Composable
fun CategoryListOverlay(
    categories: List<com.streamvault.domain.model.Category>,
    currentCategoryId: Long,
    overlayFocusRequester: FocusRequester = remember { FocusRequester() },
    isCategoryLocked: (com.streamvault.domain.model.Category) -> Boolean = { false },
    onSelectCategory: (com.streamvault.domain.model.Category) -> Unit,
    onDismiss: () -> Unit,
    onOverlayInteracted: () -> Unit = {}
) {
    val listState = rememberLazyListState()
    val currentIndex = remember(categories, currentCategoryId) {
        categories.indexOfFirst { it.id == currentCategoryId }.coerceAtLeast(0)
    }

    LaunchedEffect(categories, currentIndex) {
        if (categories.isNotEmpty()) {
            listState.scrollToItem(currentIndex)
        }
    }

    BackHandler { onDismiss() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f))
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isTelevisionDevice = rememberIsTelevisionDevice()
            val panelModifier = if (maxWidth < 700.dp) {
                Modifier
                    .fillMaxWidth(0.9f)
                    .fillMaxHeight()
                    .padding(20.dp)
            } else if (!isTelevisionDevice && maxWidth < 1280.dp) {
                Modifier
                    .fillMaxWidth(0.5f)
                    .fillMaxHeight()
                    .padding(20.dp)
            } else {
                Modifier
                    .width(500.dp)
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(20.dp)
            }

            Box(modifier = panelModifier) {
                PlayerOverlayPanel(modifier = Modifier.fillMaxSize()) {
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        item {
                            Text(
                                text = stringResource(R.string.label_categories),
                                style = MaterialTheme.typography.titleMedium,
                                color = Primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
                            )
                        }
                        items(categories.size) { index ->
                            val category = categories[index]
                            val isSelected = category.id == currentCategoryId
                            val isLocked = isCategoryLocked(category)
                            var isFocused by remember { mutableStateOf(false) }
                            val shouldRequestFocus = isSelected
                            val bgColor = when {
                                isFocused -> Primary
                                isSelected -> Primary.copy(alpha = 0.20f)
                                else -> AppColors.Surface.copy(alpha = 0.68f)
                            }

                            TvClickableSurface(
                                onClick = {
                                    onSelectCategory(category)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .onFocusChanged { focusState ->
                                        isFocused = focusState.isFocused
                                        if (focusState.isFocused) {
                                            onOverlayInteracted()
                                        }
                                    }
                                    .then(
                                        if (shouldRequestFocus) Modifier.focusRequester(overlayFocusRequester)
                                        else Modifier
                                    ),
                                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = bgColor,
                                    focusedContainerColor = bgColor
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = category.name,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    if (isLocked) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = stringResource(R.string.home_locked_short),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.68f)
                                        )
                                    }
                                    if (isSelected) {
                                        Text(
                                            text = "●",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    } else if (category.count > 0) {
                                        Text(
                                            text = category.count.toString(),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color.White.copy(alpha = 0.45f),
                                            modifier = Modifier.padding(start = 8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
