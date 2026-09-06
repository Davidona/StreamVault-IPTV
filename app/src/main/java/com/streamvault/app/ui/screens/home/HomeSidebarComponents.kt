package com.streamvault.app.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.components.FocusedMarqueeText
import com.streamvault.app.ui.components.PlayerRenderView
import com.streamvault.app.ui.interaction.TvButton
import com.streamvault.app.ui.interaction.TvClickableSurface
import com.streamvault.app.ui.theme.FocusBorder
import com.streamvault.app.ui.theme.OnBackground
import com.streamvault.app.ui.theme.OnSurface
import com.streamvault.app.ui.theme.OnSurfaceDim
import com.streamvault.app.ui.theme.Primary
import com.streamvault.app.ui.theme.PrimaryLight
import com.streamvault.app.ui.theme.SurfaceElevated
import com.streamvault.app.ui.theme.SurfaceHighlight
import com.streamvault.app.ui.time.LocalAppTimeFormat
import com.streamvault.app.ui.time.createTimeFormat
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerRenderSurfaceType
import com.streamvault.player.PlayerSurfaceResizeMode
import java.util.Date

@Composable
internal fun CompactSplitLauncherButton(
    slotCount: Int,
    slotLimit: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val displaySlotCount = slotCount.coerceAtMost(slotLimit)
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier
            .widthIn(min = 112.dp)
            .height(34.dp),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Primary.copy(alpha = 0.18f),
            focusedContainerColor = Primary.copy(alpha = 0.3f),
            contentColor = OnBackground
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, Primary.copy(alpha = 0.55f))
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.action_split),
                style = MaterialTheme.typography.labelSmall,
                color = OnBackground,
                maxLines = 1
            )
            Text(
                text = stringResource(R.string.label_slots_count_dynamic, displaySlotCount, slotLimit),
                style = MaterialTheme.typography.labelSmall,
                color = PrimaryLight,
                maxLines = 1
            )
        }
    }
}

@Composable
internal fun LivePreviewPane(
    channel: Channel?,
    playerEngine: PlayerEngine?,
    isLoading: Boolean,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    val renderSurfaceType by (playerEngine?.renderSurfaceType)?.collectAsStateWithLifecycle(
        initialValue = PlayerRenderSurfaceType.SURFACE_VIEW
    ) ?: remember { mutableStateOf(PlayerRenderSurfaceType.SURFACE_VIEW) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = SurfaceDefaults.colors(containerColor = SurfaceElevated.copy(alpha = 0.72f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.live_preview_title),
                style = MaterialTheme.typography.titleSmall,
                color = Primary
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(Color.Black, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (playerEngine != null && errorMessage == null) {
                    PlayerRenderView(
                        playerEngine = playerEngine,
                        resizeMode = PlayerSurfaceResizeMode.FIT,
                        surfaceType = renderSurfaceType,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 20.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.live_preview_placeholder_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = OnBackground,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = errorMessage ?: stringResource(R.string.live_preview_placeholder_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                if (isLoading && channel != null) {
                    Row(
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            color = Primary,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(R.string.live_preview_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }
                }
            }

            if (channel != null) {
                Text(
                    text = channel.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = OnBackground
                )
                channel.currentProgram?.let { program ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = program.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = OnBackground
                        )
                        val appTimeFormat = LocalAppTimeFormat.current
                        val timeFormat = remember(appTimeFormat) { appTimeFormat.createTimeFormat() }
                        Text(
                            text = stringResource(
                                R.string.time_range_format,
                                timeFormat.format(Date(program.startTime)),
                                timeFormat.format(Date(program.endTime))
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = OnSurfaceDim
                        )
                        LinearProgressIndicator(
                            progress = { program.progressPercent() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(4.dp),
                            color = Primary,
                            trackColor = SurfaceHighlight
                        )
                        if (program.description.isNotBlank()) {
                            Text(
                                text = program.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurface,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                } ?: Text(
                    text = stringResource(R.string.live_preview_no_epg),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceDim
                )

                Text(
                    text = stringResource(R.string.live_preview_open_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = Primary
                )
            }
        }
    }
}

@Composable
internal fun CategoryItem(
    category: Category,
    isSelected: Boolean,
    isLocked: Boolean = false,
    isPinned: Boolean = false,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onJumpToSearch: () -> Boolean,
    onJumpToContent: () -> Boolean,
    onFocused: () -> Unit,
    onFocusChanged: (Boolean) -> Unit = {}
) {
    var isFocused by remember { mutableStateOf(false) }

    TvClickableSurface(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                onFocusChanged(it.isFocused)
                if (it.isFocused) onFocused()
            }
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                    when (event.nativeKeyEvent.keyCode) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> onJumpToSearch()
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> onJumpToContent()
                        else -> false
                    }
                } else false
            },
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (isSelected) Primary.copy(alpha = 0.15f) else Color.Transparent,
            focusedContainerColor = SurfaceHighlight.copy(alpha = 0.82f),
            contentColor = if (isSelected) Primary else OnSurface
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, Primary.copy(alpha = 0.85f)),
                shape = RoundedCornerShape(10.dp)
            )
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (isPinned) {
                PinnedCategoryGlyph(
                    tint = if (isFocused) OnBackground else if (isSelected) Primary else OnSurfaceDim,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            FocusedMarqueeText(
                text = category.name,
                isFocused = isFocused,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                color = if (isFocused) OnBackground else if (isSelected) Primary else OnSurface,
                modifier = Modifier.weight(1f)
            )

            Text(
                text = category.count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (isFocused) OnBackground else OnSurfaceDim,
                modifier = Modifier.padding(start = 10.dp)
            )

            if (isLocked) {
                Text(
                    text = stringResource(R.string.home_locked_short),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused) OnBackground else OnSurfaceDim,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun PinnedCategoryGlyph(
    tint: Color,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(12.dp)) {
        val headRadius = size.minDimension * 0.18f
        val centerX = size.width * 0.45f
        val headCenterY = size.height * 0.26f
        drawCircle(
            color = tint,
            radius = headRadius,
            center = androidx.compose.ui.geometry.Offset(centerX, headCenterY)
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(centerX, headCenterY + headRadius * 0.6f),
            end = androidx.compose.ui.geometry.Offset(centerX, size.height * 0.8f),
            strokeWidth = size.minDimension * 0.12f
        )
        drawLine(
            color = tint,
            start = androidx.compose.ui.geometry.Offset(size.width * 0.18f, size.height * 0.38f),
            end = androidx.compose.ui.geometry.Offset(size.width * 0.72f, size.height * 0.38f),
            strokeWidth = size.minDimension * 0.14f
        )
    }
}

@Composable
internal fun ReorderSidePanel(
    channels: List<Channel>,
    onMoveUp: (Channel) -> Unit,
    onMoveDown: (Channel) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val panelWidth = if (screenWidth < 900.dp) {
        (screenWidth * 0.36f).coerceIn(188.dp, 220.dp)
    } else if (!isTelevisionDevice && screenWidth < 1280.dp) {
        (screenWidth * 0.28f).coerceIn(220.dp, 252.dp)
    } else {
        272.dp
    }
    var draggingChannel by remember { mutableStateOf<Channel?>(null) }
    val panelFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        panelFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(panelWidth)
            .background(SurfaceElevated)
            .padding(16.dp)
            .focusRequester(panelFocusRequester)
            .focusGroup()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.home_reorder_channels),
                style = MaterialTheme.typography.titleMedium,
                color = Primary,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
            ) {
                TvButton(
                    onClick = onSave,
                    colors = ButtonDefaults.colors(
                        containerColor = Primary,
                        contentColor = Color.Black
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.action_save), maxLines = 1) }

                TvButton(
                    onClick = onCancel,
                    colors = ButtonDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = OnSurface
                    ),
                    modifier = Modifier.weight(1f)
                ) { Text(stringResource(R.string.action_cancel), maxLines = 1) }
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                    var isFocused by remember { mutableStateOf(false) }
                    val isDraggingThis = draggingChannel == channel

                    TvClickableSurface(
                        onClick = {
                            draggingChannel = if (isDraggingThis) null else channel
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .onFocusChanged { isFocused = it.isFocused }
                            .onKeyEvent { event ->
                                if (event.nativeKeyEvent.action == android.view.KeyEvent.ACTION_DOWN) {
                                    if (isDraggingThis) {
                                        when (event.nativeKeyEvent.keyCode) {
                                            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                                                onMoveUp(channel)
                                                true
                                            }
                                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                                                onMoveDown(channel)
                                                true
                                            }
                                            else -> false
                                        }
                                    } else if (
                                        event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
                                        event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT
                                    ) {
                                        true
                                    } else {
                                        false
                                    }
                                } else false
                            },
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                        colors = ClickableSurfaceDefaults.colors(
                            focusedContainerColor = if (isDraggingThis) Primary else Primary.copy(alpha = 0.2f),
                            containerColor = if (isDraggingThis) Primary.copy(alpha = 0.5f) else Color.Transparent
                        ),
                        border = ClickableSurfaceDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, FocusBorder),
                                shape = RoundedCornerShape(8.dp)
                            )
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(12.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isDraggingThis) {
                                Text(
                                    stringResource(R.string.action_move),
                                    color = Color.White,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                            }
                            Text(
                                stringResource(R.string.channel_number_name_format, index + 1, channel.name),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isDraggingThis) Color.White else if (isFocused) OnBackground else OnSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            Text(
                if (draggingChannel != null) "UP/DOWN to move.\nOK to drop." else "OK to grab channel.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceDim,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}
