package com.streamvault.app.ui.components.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Category
import androidx.compose.ui.res.stringResource
import com.streamvault.app.R
import com.streamvault.core.ui.design.AppColors
import androidx.compose.ui.input.key.*
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.shape.CircleShape
import kotlinx.coroutines.delay
import androidx.tv.material3.*
import com.streamvault.app.device.rememberIsTelevisionDevice
import com.streamvault.app.ui.interaction.mouseClickable
import android.view.KeyEvent as AndroidKeyEvent

@Composable
fun AddToGroupDialog(
    contentTitle: String,
    channel: Channel? = null,          // the channel being managed (for split screen)
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
    val isTelevisionDevice = rememberIsTelevisionDevice()
    val contentFocusRequester = remember { FocusRequester() }  // focuses first interactive item, not close button

    // Ghost-click debounce
    var canInteract by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { contentFocusRequester.requestFocus() }
        delay(500)
        canInteract = true
    }

    val blockOpenGesture: (KeyEvent) -> Boolean = { event ->
        !canInteract &&
            event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_UP &&
            when (event.nativeKeyEvent.keyCode) {
                AndroidKeyEvent.KEYCODE_DPAD_CENTER,
                AndroidKeyEvent.KEYCODE_ENTER,
                AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                AndroidKeyEvent.KEYCODE_BUTTON_A -> true
                else -> false
            }
    }

    val safeDismiss = { if (canInteract) onDismiss() }
    val safeToggleFavorite = { if (canInteract) onToggleFavorite() }
    val safeOpenSplitScreenPlanner = { if (canInteract) onOpenSplitScreenPlanner?.invoke() }
    val safeRemoveFromRecent = { if (canInteract) onRemoveFromRecent?.invoke() }
    val safeHideChannel = { if (canInteract) onHideChannel?.invoke() }
    val safeMoveToMovies = { if (canInteract) onMoveToMovies?.invoke() }
    val safeMoveToSeries = { if (canInteract) onMoveToSeries?.invoke() }
    val safeMoveBackToLive = { if (canInteract) onMoveBackToLive?.invoke() }
    val safeCreateGroup = { if (canInteract && onCreateGroup != null) showCreateGroup = true }
    val safeAddToGroup: (Category) -> Unit = { group -> if (canInteract) onAddToGroup(group) }
    val safeRemoveFromGroup: (Category) -> Unit = { group -> if (canInteract) onRemoveFromGroup(group) }

    if (showCreateGroup && onCreateGroup != null) {
        CreateGroupDialog(
            onDismissRequest = { showCreateGroup = false },
            onConfirm = { name ->
                onCreateGroup.invoke(name)
                showCreateGroup = false
            }
        )
    }

    if (!showCreateGroup) {
        Dialog(
            onDismissRequest = safeDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val dialogModifier = if (isTelevisionDevice) {
                Modifier.width(400.dp)
            } else {
                Modifier
            }

            val dialogContent: @Composable (Modifier) -> Unit = { resolvedModifier ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    colors = SurfaceDefaults.colors(containerColor = AppColors.SurfaceElevated),
                    modifier = resolvedModifier
                        .padding(16.dp)
                        .onPreviewKeyEvent(blockOpenGesture)
                ) {
                    Column(modifier = Modifier.padding(24.dp)) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.add_group_manage_title, contentTitle),
                                style = MaterialTheme.typography.titleLarge,
                                color = AppColors.TextPrimary,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = safeDismiss,
                                modifier = Modifier.mouseClickable(onClick = safeDismiss)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.add_group_close_cd))
                            }
                        }

                        LazyColumn(
                            modifier = Modifier.weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                        // ── Favorites ────────────────────────────────
                        item {
                            var isFocused by remember { mutableStateOf(false) }
                            Button(
                                onClick = safeToggleFavorite,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(contentFocusRequester)
                                    .onFocusChanged { isFocused = it.isFocused }
                                    .background(
                                        color = if (isFocused) AppColors.Focus else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .border(
                                        if (isFocused) 3.dp else 0.dp,
                                        if (isFocused) AppColors.Focus else Color.Transparent,
                                        CircleShape
                                    )
                                    .mouseClickable(onClick = safeToggleFavorite),
                                colors = ButtonDefaults.colors(
                                    containerColor = when {
                                        isFocused -> AppColors.Focus
                                        isFavorite -> AppColors.Warning
                                        else -> AppColors.Brand
                                    },
                                    contentColor = Color.Black
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = if (isFavorite) stringResource(R.string.add_group_remove_favorites)
                                        else stringResource(R.string.add_group_add_favorites),
                                    tint = Color.Black
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isFavorite) stringResource(R.string.add_group_remove_favorites)
                                    else stringResource(R.string.add_group_add_favorites),
                                    color = Color.Black
                                )
                            }
                        }

                        if (onMoveToMovies != null) {
                            item {
                                Button(
                                    onClick = safeMoveToMovies,
                                    modifier = Modifier.fillMaxWidth().mouseClickable(onClick = safeMoveToMovies),
                                    colors = ButtonDefaults.colors(
                                        containerColor = AppColors.Brand,
                                        contentColor = Color.Black
                                    )
                                ) { Text(stringResource(R.string.m3u_move_to_movies)) }
                            }
                        }

                        if (onMoveToSeries != null) {
                            item {
                                Button(
                                    onClick = safeMoveToSeries,
                                    modifier = Modifier.fillMaxWidth().mouseClickable(onClick = safeMoveToSeries),
                                    colors = ButtonDefaults.colors(
                                        containerColor = AppColors.Success,
                                        contentColor = Color.Black
                                    )
                                ) { Text(stringResource(R.string.m3u_move_to_series)) }
                            }
                        }

                        if (onMoveBackToLive != null) {
                            item {
                                Button(
                                    onClick = safeMoveBackToLive,
                                    modifier = Modifier.fillMaxWidth().mouseClickable(onClick = safeMoveBackToLive),
                                    colors = ButtonDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.errorContainer,
                                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) { Text(stringResource(R.string.m3u_move_back_to_live)) }
                            }
                        }

                        // ── Split Screen ─────────────────────────────
                        if (channel != null && onOpenSplitScreenPlanner != null) {
                            item {
                                var isFocused by remember { mutableStateOf(false) }
                                Button(
                                    onClick = safeOpenSplitScreenPlanner,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { isFocused = it.isFocused }
                                        .background(
                                            color = if (isFocused) AppColors.Focus else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .border(
                                            if (isFocused) 3.dp else 0.dp,
                                            if (isFocused) AppColors.Focus else Color.Transparent,
                                            CircleShape
                                        )
                                        .mouseClickable(onClick = safeOpenSplitScreenPlanner),
                                    colors = ButtonDefaults.colors(
                                    containerColor = when {
                                        isFocused -> AppColors.Focus
                                        isQueuedForSplitScreen -> AppColors.Success.copy(alpha = 0.8f)
                                        else -> AppColors.Success
                                    }
                                )
                            ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = stringResource(R.string.multiview_add_to_split),
                                        tint = if (isFocused) Color.Black else Color.White
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (isQueuedForSplitScreen) stringResource(R.string.multiview_queued)
                                        else stringResource(R.string.multiview_add_to_split),
                                        color = if (isFocused) Color.Black else Color.White
                                    )
                                }
                            }
                        }

                        // ── Hide channel ─────────────────────────────
                        if (channel != null && onHideChannel != null) {
                            item {
                                var isFocused by remember { mutableStateOf(false) }
                                Button(
                                    onClick = safeHideChannel,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { isFocused = it.isFocused }
                                        .background(
                                            color = if (isFocused) AppColors.Focus else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .border(
                                            if (isFocused) 3.dp else 0.dp,
                                            if (isFocused) AppColors.Focus else Color.Transparent,
                                            CircleShape
                                        )
                                        .mouseClickable(onClick = safeHideChannel),
                                    colors = ButtonDefaults.colors(
                                        containerColor = if (isFocused) AppColors.Focus else AppColors.SurfaceAccent,
                                        contentColor = if (isFocused) Color.Black else AppColors.TextPrimary
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Lock,
                                        contentDescription = stringResource(R.string.channel_action_hide),
                                        tint = if (isFocused) Color.Black else AppColors.TextPrimary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.channel_action_hide),
                                        color = if (isFocused) Color.Black else AppColors.TextPrimary
                                    )
                                }
                            }
                        }

                        // ── Remove from Recent ───────────────────────
                        if (onRemoveFromRecent != null) {
                            item {
                                var isFocused by remember { mutableStateOf(false) }
                                Button(
                                    onClick = safeRemoveFromRecent,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { isFocused = it.isFocused }
                                        .background(
                                            color = if (isFocused) AppColors.Focus else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .border(
                                            if (isFocused) 3.dp else 0.dp,
                                            if (isFocused) AppColors.Focus else Color.Transparent,
                                            CircleShape
                                        )
                                        .mouseClickable(onClick = safeRemoveFromRecent),
                                    colors = ButtonDefaults.colors(
                                        containerColor = when {
                                            isFocused -> AppColors.Focus
                                            else -> MaterialTheme.colorScheme.errorContainer
                                        },
                                        contentColor = if (isFocused) Color.Black else MaterialTheme.colorScheme.onErrorContainer
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = stringResource(R.string.channel_options_remove_from_recent),
                                        tint = if (isFocused) Color.Black else MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = stringResource(R.string.channel_options_remove_from_recent),
                                        color = if (isFocused) Color.Black else MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                            }
                        }

                        // ── Custom Groups Section ─────────────────────
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .height(1.dp)
                                    .background(AppColors.SurfaceAccent.copy(alpha = 0.7f))
                            )
                            Text(
                                text = stringResource(R.string.add_group_custom_groups_title),
                                style = MaterialTheme.typography.titleMedium,
                                color = AppColors.TextPrimary,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }

                        items(
                            items = groups,
                            key = { it.id },
                            contentType = { "category" }
                        ) { group ->
                            val isMember = memberOfGroups.contains(group.id)
                            var isFocused by remember { mutableStateOf(false) }
                            val groupAction = { if (isMember) safeRemoveFromGroup(group) else safeAddToGroup(group) }

                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = group.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = AppColors.TextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Button(
                                    onClick = groupAction,
                                    modifier = Modifier
                                        .background(
                                            color = if (isFocused) AppColors.Focus else Color.Transparent,
                                            shape = CircleShape
                                        )
                                        .onFocusChanged { isFocused = it.isFocused }
                                        .border(
                                            if (isFocused) 3.dp else 0.dp,
                                            if (isFocused) AppColors.Focus else Color.Transparent,
                                            CircleShape
                                        )
                                        .mouseClickable(onClick = groupAction),
                                    colors = ButtonDefaults.colors(
                                        containerColor = when {
                                            isFocused -> AppColors.Focus
                                            isMember -> AppColors.Live.copy(alpha = 0.2f)
                                            else -> AppColors.BrandMuted.copy(alpha = 0.4f)
                                        },
                                        contentColor = if (isFocused) Color.Black else AppColors.TextPrimary
                                    )
                                ) {
                                    Text(
                                        if (isMember) stringResource(R.string.add_group_remove_btn)
                                        else stringResource(R.string.add_group_add_btn)
                                    )
                                }
                            }
                        }

                    if (onCreateGroup != null) item {
                        var isFocused by remember { mutableStateOf(false) }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = safeCreateGroup,
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { isFocused = it.isFocused }
                                .background(
                                    color = if (isFocused) AppColors.Focus else Color.Transparent,
                                    shape = CircleShape
                                )
                                .border(
                                    if (isFocused) 3.dp else 1.dp,
                                    if (isFocused) AppColors.Focus else AppColors.BrandMuted.copy(alpha = 0.55f),
                                    CircleShape
                                )
                                .mouseClickable(onClick = safeCreateGroup),
                            colors = ButtonDefaults.colors(
                                containerColor = if (isFocused) AppColors.Focus else Color.Transparent,
                                contentColor = if (isFocused) Color.Black else AppColors.TextPrimary
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_group_create_new_btn))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.add_group_create_new_btn))
                        }
                    }
                        }
                    }
                }
            }

            if (isTelevisionDevice) {
                dialogContent(dialogModifier)
            } else {
                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    val responsiveModifier = when {
                        maxWidth < 700.dp -> Modifier.fillMaxWidth(0.92f)
                        maxWidth < 1280.dp -> Modifier.fillMaxWidth(0.58f)
                        else -> Modifier.width(400.dp)
                    }
                    dialogContent(responsiveModifier)
                }
            }
        }
    }
}
