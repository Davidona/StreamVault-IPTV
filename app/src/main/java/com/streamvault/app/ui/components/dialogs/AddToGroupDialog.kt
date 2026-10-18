package com.streamvault.app.ui.components.dialogs

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.*
import com.streamvault.app.R
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.core.ui.design.BrowseColors as AppColors
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.Channel
import kotlinx.coroutines.delay

private val menuShape = RoundedCornerShape(20.dp)
private val actionShape = RoundedCornerShape(12.dp)

@Composable
fun AddToGroupDialog(
    contentTitle: String,
    channel: Channel? = null,
    groups: List<Category>,
    isFavorite: Boolean,
    memberOfGroups: List<Long>,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onAddToGroup: (Category) -> Unit,
    onRemoveFromGroup: (Category) -> Unit,
    onCreateGroup: ((String) -> Unit)? = null,
    isQueuedForSplitScreen: Boolean = false,
    onOpenSplitScreenPlanner: (() -> Unit)? = null,
    onRemoveFromRecent: (() -> Unit)? = null,
    onHideChannel: (() -> Unit)? = null,
    onMoveToMovies: (() -> Unit)? = null,
    onMoveToSeries: (() -> Unit)? = null,
    onMoveBackToLive: (() -> Unit)? = null
) {
    var showCreateGroup by remember { mutableStateOf(false) }
    var canInteract by remember { mutableStateOf(false) }
    val firstActionFocus = remember { FocusRequester() }
    val isTelevisionDevice = rememberIsTelevisionDevice()

    LaunchedEffect(showCreateGroup) {
        if (!showCreateGroup) {
            canInteract = false
            runCatching { firstActionFocus.requestFocus() }
            delay(500)
            canInteract = true
        }
    }
    val blockOpenGesture: (KeyEvent) -> Boolean = { event ->
        !canInteract && event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_UP &&
            event.nativeKeyEvent.keyCode in listOf(
                AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER,
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER, AndroidKeyEvent.KEYCODE_BUTTON_A
            )
    }

    if (showCreateGroup && onCreateGroup != null) {
        CreateGroupDialog(
            onDismissRequest = { showCreateGroup = false },
            onConfirm = { name ->
                onCreateGroup(name)
                showCreateGroup = false
            }
        )
    } else {
        Dialog(
            onDismissRequest = { if (canInteract) onDismiss() },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                val width = when {
                    isTelevisionDevice -> Modifier.width(420.dp)
                    maxWidth < 700.dp -> Modifier.fillMaxWidth(0.92f)
                    maxWidth < 1280.dp -> Modifier.fillMaxWidth(0.58f)
                    else -> Modifier.width(420.dp)
                }
                val maxListHeight = maxHeight * 0.68f
                Surface(
                    shape = menuShape,
                    colors = SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated),
                    modifier = width.padding(12.dp)
                        .border(1.dp, AppColors.Outline, menuShape)
                        .onPreviewKeyEvent(blockOpenGesture)
                ) {
                    Column(
                        Modifier.background(
                            Brush.verticalGradient(
                                listOf(AppColors.BrandMuted.copy(alpha = 0.16f),
                                    AppColors.SurfaceElevated, AppColors.Surface)
                            )
                        ).padding(22.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                stringResource(R.string.add_group_manage_title, contentTitle),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = AppColors.TextPrimary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            MenuFocusSurface(
                                onClick = { if (canInteract) onDismiss() },
                                modifier = Modifier.size(38.dp),
                                containerColor = AppColors.SurfaceAccent
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Close,
                                        contentDescription = stringResource(R.string.add_group_close_cd),
                                        tint = AppColors.TextSecondary,
                                        modifier = Modifier.size(20.dp))
                                }
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.heightIn(max = maxListHeight),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            item {
                                MenuAction(
                                    label = stringResource(
                                        if (isFavorite) R.string.add_group_remove_favorites
                                        else R.string.add_group_add_favorites),
                                    icon = Icons.Default.Star,
                                    accent = AppColors.Brand,
                                    selected = isFavorite,
                                    onClick = { if (canInteract) onToggleFavorite() },
                                    modifier = Modifier.focusRequester(firstActionFocus)
                                )
                            }
                            if (onMoveToMovies != null) item {
                                MenuAction(stringResource(R.string.m3u_move_to_movies), Icons.Default.Add,
                                    AppColors.Brand, onClick = { if (canInteract) onMoveToMovies() })
                            }
                            if (onMoveToSeries != null) item {
                                MenuAction(stringResource(R.string.m3u_move_to_series), Icons.Default.Add,
                                    AppColors.Success, onClick = { if (canInteract) onMoveToSeries() })
                            }
                            if (onMoveBackToLive != null) item {
                                MenuAction(stringResource(R.string.m3u_move_back_to_live), Icons.Default.Add,
                                    AppColors.Brand, onClick = { if (canInteract) onMoveBackToLive() })
                            }
                            if (channel != null && onOpenSplitScreenPlanner != null) item {
                                MenuAction(
                                    label = stringResource(
                                        if (isQueuedForSplitScreen) R.string.multiview_queued
                                        else R.string.multiview_add_to_split),
                                    icon = Icons.Default.Add,
                                    accent = AppColors.Success,
                                    selected = isQueuedForSplitScreen,
                                    onClick = { if (canInteract) onOpenSplitScreenPlanner() }
                                )
                            }
                            if (channel != null && onHideChannel != null) item {
                                MenuAction(stringResource(R.string.channel_action_hide), Icons.Default.Lock,
                                    AppColors.Warning, onClick = { if (canInteract) onHideChannel() })
                            }
                            if (onRemoveFromRecent != null) item {
                                MenuAction(stringResource(R.string.channel_options_remove_from_recent),
                                    Icons.Default.Close, AppColors.Warning,
                                    onClick = { if (canInteract) onRemoveFromRecent() })
                            }
                            item {
                                Spacer(Modifier.height(4.dp))
                                Box(Modifier.fillMaxWidth().height(1.dp).background(AppColors.Outline))
                                Text(stringResource(R.string.add_group_custom_groups_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppColors.TextSecondary,
                                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                            }
                            items(groups, key = { it.id }) { group ->
                                val isMember = group.id in memberOfGroups
                                MenuAction(
                                    label = group.name,
                                    icon = Icons.Default.Add,
                                    accent = AppColors.Brand,
                                    selected = isMember,
                                    trailing = stringResource(
                                        if (isMember) R.string.add_group_remove_btn
                                        else R.string.add_group_add_btn),
                                    onClick = {
                                        if (canInteract) {
                                            if (isMember) onRemoveFromGroup(group) else onAddToGroup(group)
                                        }
                                    }
                                )
                            }
                            if (onCreateGroup != null) item {
                                MenuAction(stringResource(R.string.add_group_create_new_btn),
                                    Icons.Default.Add, AppColors.Brand,
                                    onClick = { if (canInteract) showCreateGroup = true })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuAction(
    label: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    trailing: String? = null
) {
    MenuFocusSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        containerColor = if (selected) accent.copy(alpha = 0.17f) else AppColors.SurfaceAccent
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                Modifier.size(34.dp).background(accent.copy(alpha = 0.17f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
            }
            Text(label, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, color = AppColors.TextPrimary,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
            if (trailing != null) {
                Text(trailing, style = MaterialTheme.typography.labelMedium, color = accent)
            }
        }
    }
}

@Composable
private fun MenuFocusSurface(
    onClick: () -> Unit,
    modifier: Modifier,
    containerColor: Color,
    content: @Composable BoxScope.() -> Unit
) {
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(actionShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = containerColor,
            focusedContainerColor = containerColor,
            contentColor = AppColors.TextPrimary,
            focusedContentColor = AppColors.TextPrimary
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, AppColors.Outline), shape = actionShape),
            focusedBorder = Border(border = BorderStroke(2.dp, AppColors.Focus), shape = actionShape)
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        content = content
    )
}
