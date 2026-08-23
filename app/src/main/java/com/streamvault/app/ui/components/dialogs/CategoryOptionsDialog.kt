package com.streamvault.app.ui.components.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.interaction.mouseClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import com.streamvault.domain.model.Category
import kotlinx.coroutines.delay

@Composable
fun CategoryOptionsDialog(
    category: Category,
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
                    text = stringResource(R.string.library_saved_manage_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceDim
                )

                if (onSetAsDefault != null) {
                    PremiumDialogAction(
                        label = stringResource(R.string.category_options_set_default),
                        onClick = { if (canInteract) onSetAsDefault() }
                    )
                }

                if (onTogglePinned != null) {
                    PremiumDialogAction(
                        label = stringResource(
                            if (isPinned) R.string.category_options_unpin else R.string.category_options_pin
                        ),
                        onClick = {
                            if (canInteract) {
                                onTogglePinned()
                            }
                        }
                    )
                }

                if (onRename != null) {
                    PremiumDialogAction(
                        label = stringResource(R.string.category_options_rename),
                        onClick = { if (canInteract) onRename() }
                    )
                }

                if (onHide != null) {
                    PremiumDialogAction(
                        label = stringResource(R.string.category_options_hide),
                        onClick = {
                            if (canInteract) {
                                onHide()
                                onDismissRequest()
                            }
                        }
                    )
                }

                if (onHideFromLiveTV != null) {
                    PremiumDialogAction(
                        label = stringResource(R.string.category_options_hide_from_live_tv),
                        onClick = { if (canInteract) onHideFromLiveTV() }
                    )
                }

                if (onClearAll != null) {
                    PremiumDialogAction(
                        label = stringResource(R.string.category_options_clear_recent),
                        onClick = { if (canInteract) onClearAll() },
                        destructive = true
                    )
                }

                if (onReorderChannels != null) {
                    PremiumDialogAction(
                        label = stringResource(R.string.category_options_reorder),
                        onClick = {
                            if (canInteract) {
                                onReorderChannels()
                                onDismissRequest()
                            }
                        }
                    )
                }

                if (onOrganizeM3u != null) {
                    PremiumDialogAction(
                        label = stringResource(R.string.m3u_organize_items),
                        onClick = { if (canInteract) onOrganizeM3u() }
                    )
                }

                if (onToggleLock != null) {
                    PremiumDialogAction(
                        label = if (category.isUserProtected) {
                            stringResource(R.string.category_options_unlock)
                        } else {
                            stringResource(R.string.category_options_lock)
                        },
                        onClick = { if (canInteract) onToggleLock() }
                    )
                }

                if (onDelete != null) {
                    PremiumDialogAction(
                        label = stringResource(R.string.category_options_delete),
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
                    Text(stringResource(R.string.category_options_cancel))
                }
            }
        }
        }
    }
}

@Composable
private fun PremiumDialogAction(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().mouseClickable(onClick = onClick),
        colors = ButtonDefaults.colors(
            containerColor = if (destructive) MaterialTheme.colorScheme.errorContainer else Color.White.copy(alpha = 0.08f),
            contentColor = if (destructive) MaterialTheme.colorScheme.onErrorContainer else OnSurface
        )
    ) {
        Text(label)
    }
}
