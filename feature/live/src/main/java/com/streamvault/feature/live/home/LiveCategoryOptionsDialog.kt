package com.streamvault.feature.live.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.components.dialogs.rememberDialogOpenGestureBlocker
import com.streamvault.core.ui.interaction.mouseClickable
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.domain.model.Category
import kotlinx.coroutines.delay

data class LiveCategoryOptionsDialogLabels(
    val hint: String,
    val setDefault: String,
    val pin: String,
    val unpin: String,
    val rename: String,
    val hide: String,
    val hideFromLiveTv: String,
    val clearRecent: String,
    val reorder: String,
    val organizeM3u: String,
    val lock: String,
    val unlock: String,
    val delete: String,
    val cancel: String
)

@Composable
fun LiveCategoryOptionsDialog(
    category: Category,
    labels: LiveCategoryOptionsDialogLabels,
    onDismissRequest: () -> Unit,
    onSetAsDefault: (() -> Unit)? = null,
    isPinned: Boolean = false,
    onTogglePinned: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onHide: (() -> Unit)? = null,
    onHideFromLiveTV: (() -> Unit)? = null,
    onClearAll: (() -> Unit)? = null,
    onToggleLock: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    onReorderChannels: (() -> Unit)? = null,
    onOrganizeM3u: (() -> Unit)? = null
) {
    var canInteract by remember { mutableStateOf(false) }
    val blockOpenGesture = rememberDialogOpenGestureBlocker(canInteract)
    LaunchedEffect(Unit) {
        delay(500)
        canInteract = true
    }

    val safeDismiss = { if (canInteract) onDismissRequest() }

    Dialog(
        onDismissRequest = safeDismiss,
        properties = DialogProperties(
            dismissOnBackPress = canInteract,
            dismissOnClickOutside = canInteract,
            usePlatformDefaultWidth = false
        )
    ) {
        BoxWithConstraints(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val dialogWidth = when {
                maxWidth < 700.dp -> 0.9f
                maxWidth < 1280.dp -> 0.56f
                else -> 0.38f
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth(dialogWidth)
                    .onPreviewKeyEvent(blockOpenGesture),
                shape = RoundedCornerShape(24.dp),
                colors = SurfaceDefaults.colors(containerColor = SurfaceElevated)
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Primary.copy(alpha = 0.08f),
                                    Color.Transparent
                                )
                            )
                        )
                        .padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = category.name,
                        style = MaterialTheme.typography.headlineMedium,
                        color = OnSurface
                    )
                    Text(
                        text = labels.hint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OnSurfaceDim
                    )

                    if (onSetAsDefault != null) {
                        LiveCategoryOptionsAction(
                            label = labels.setDefault,
                            onClick = { if (canInteract) onSetAsDefault() }
                        )
                    }

                    if (onTogglePinned != null) {
                        LiveCategoryOptionsAction(
                            label = if (isPinned) labels.unpin else labels.pin,
                            onClick = { if (canInteract) onTogglePinned() }
                        )
                    }

                    if (onRename != null) {
                        LiveCategoryOptionsAction(
                            label = labels.rename,
                            onClick = { if (canInteract) onRename() }
                        )
                    }

                    if (onHide != null) {
                        LiveCategoryOptionsAction(
                            label = labels.hide,
                            onClick = {
                                if (canInteract) {
                                    onHide()
                                    onDismissRequest()
                                }
                            }
                        )
                    }

                    if (onHideFromLiveTV != null) {
                        LiveCategoryOptionsAction(
                            label = labels.hideFromLiveTv,
                            onClick = { if (canInteract) onHideFromLiveTV() }
                        )
                    }

                    if (onClearAll != null) {
                        LiveCategoryOptionsAction(
                            label = labels.clearRecent,
                            onClick = { if (canInteract) onClearAll() },
                            destructive = true
                        )
                    }

                    if (onReorderChannels != null) {
                        LiveCategoryOptionsAction(
                            label = labels.reorder,
                            onClick = {
                                if (canInteract) {
                                    onReorderChannels()
                                    onDismissRequest()
                                }
                            }
                        )
                    }

                    if (onOrganizeM3u != null) {
                        LiveCategoryOptionsAction(
                            label = labels.organizeM3u,
                            onClick = { if (canInteract) onOrganizeM3u() }
                        )
                    }

                    if (onToggleLock != null) {
                        LiveCategoryOptionsAction(
                            label = if (category.isUserProtected) labels.unlock else labels.lock,
                            onClick = { if (canInteract) onToggleLock() }
                        )
                    }

                    if (onDelete != null) {
                        LiveCategoryOptionsAction(
                            label = labels.delete,
                            onClick = { if (canInteract) onDelete() },
                            destructive = true
                        )
                    }

                    Button(
                        onClick = safeDismiss,
                        modifier = Modifier.mouseClickable(onClick = safeDismiss),
                        colors = ButtonDefaults.colors(
                            containerColor = Color.White.copy(alpha = 0.08f),
                            contentColor = OnSurface
                        )
                    ) {
                        Text(labels.cancel)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveCategoryOptionsAction(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().mouseClickable(onClick = onClick),
        colors = ButtonDefaults.colors(
            containerColor = if (destructive) {
                MaterialTheme.colorScheme.errorContainer
            } else {
                Color.White.copy(alpha = 0.08f)
            },
            contentColor = if (destructive) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                OnSurface
            }
        )
    ) {
        Text(label)
    }
}
