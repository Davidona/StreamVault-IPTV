package com.streamvault.app.ui.screens.multiview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.streamvault.app.R
import com.streamvault.app.ui.components.dialogs.PremiumDialog
import com.streamvault.app.ui.components.dialogs.PremiumDialogFooterButton
import com.streamvault.app.ui.components.shell.AppMessageState
import com.streamvault.app.ui.components.shell.StatusPill
import com.streamvault.core.ui.design.AppColors
import com.streamvault.core.ui.design.FocusSpec
import com.streamvault.domain.model.Channel
import kotlinx.coroutines.delay
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.core.ui.interaction.TvIconButton

@Composable
fun MultiViewPlannerDialog(
    pendingChannel: Channel? = null,
    onDismiss: () -> Unit,
    onLaunch: () -> Unit,
    viewModel: MultiViewViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val slots by viewModel.slotsFlow.collectAsStateWithLifecycle()
    val centeredTwoSlotLayout = uiState.centerTwoSlotLayout
    val isPickerMode = pendingChannel != null
    val hasAny = slots.any { it != null }
    val channelPlaced = pendingChannel != null && slots.any { it?.id == pendingChannel.id }
    val firstSlotFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        delay(150)
        runCatching { firstSlotFocusRequester.requestFocus() }
    }

    LaunchedEffect(centeredTwoSlotLayout) {
        if (centeredTwoSlotLayout) {
            viewModel.normalizeCenteredTwoSlotLayoutIfNeeded()
        }
    }

    val subtitle = when {
        isPickerMode && !channelPlaced ->
            stringResource(R.string.multiview_planner_pick_slot, pendingChannel!!.name)
        channelPlaced ->
            stringResource(R.string.multiview_planner_added, pendingChannel!!.name)
        hasAny ->
            stringResource(R.string.multiview_planner_ready_subtitle)
        else ->
            stringResource(R.string.multiview_planner_empty)
    }

    PremiumDialog(
        title = stringResource(R.string.multiview_planner_title),
        subtitle = subtitle,
        onDismissRequest = onDismiss,
        widthFraction = 0.58f,
        content = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusPill(
                    label = stringResource(R.string.multiview_policy_balanced),
                    containerColor = AppColors.BrandMuted
                )
                StatusPill(
                    label = stringResource(R.string.multiview_policy_summary, stringResource(R.string.multiview_policy_auto), slots.count { it != null }),
                    containerColor = AppColors.SurfaceEmphasis
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val plannerRows = if (centeredTwoSlotLayout) {
                    listOf(listOf(0, 1))
                } else {
                    listOf(listOf(0, 1), listOf(2, 3))
                }
                plannerRows.forEachIndexed { rowIndex, rowSlots ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        rowSlots.forEachIndexed { colIndex, slotIndex ->
                            val occupant = slots.getOrNull(slotIndex)
                            MultiViewSlotCard(
                                slotIndex = slotIndex,
                                occupant = occupant,
                                isPickerMode = isPickerMode && !channelPlaced,
                                modifier = Modifier
                                    .weight(1f)
                                    .then(
                                        if (rowIndex == 0 && colIndex == 0) Modifier.focusRequester(firstSlotFocusRequester)
                                        else Modifier
                                    ),
                                onSlotClick = {
                                    val channelToPlace = pendingChannel ?: return@MultiViewSlotCard
                                    if (isPickerMode && !channelPlaced) {
                                        viewModel.assignChannelToSlot(slotIndex, channelToPlace)
                                    }
                                },
                                onClearSlot = { viewModel.clearSlot(slotIndex) }
                            )
                        }
                    }
                }
            }

            if (!hasAny && !isPickerMode) {
                AppMessageState(
                    title = stringResource(R.string.multiview_empty_slot),
                    subtitle = stringResource(R.string.multiview_planner_empty)
                )
            }
        },
        footer = {
            if (!isPickerMode || channelPlaced) {
                PremiumDialogFooterButton(
                    label = stringResource(R.string.multiview_planner_clear),
                    onClick = {
                        viewModel.clearAll()
                        onDismiss()
                    },
                    destructive = true
                )
            }
            PremiumDialogFooterButton(
                label = if (isPickerMode && !channelPlaced) {
                    stringResource(R.string.settings_cancel)
                } else {
                    stringResource(R.string.category_options_cancel)
                },
                onClick = onDismiss
            )
            if (!isPickerMode || channelPlaced) {
                PremiumDialogFooterButton(
                    label = if (hasAny) {
                        stringResource(R.string.multiview_planner_launch)
                    } else {
                        stringResource(R.string.multiview_planner_launch_disabled)
                    },
                    onClick = onLaunch,
                    enabled = hasAny,
                    emphasized = true
                )
            }
        }
    )
}

@Composable
private fun MultiViewSlotCard(
    slotIndex: Int,
    occupant: Channel?,
    isPickerMode: Boolean,
    modifier: Modifier = Modifier,
    onSlotClick: () -> Unit,
    onClearSlot: () -> Unit
) {
    val isEmpty = occupant == null
    val accent = when {
        isPickerMode && !isEmpty -> AppColors.Warning
        isPickerMode -> AppColors.Success
        isEmpty -> AppColors.TextTertiary
        else -> AppColors.Brand
    }

    TvClickableSurface(
        onClick = { if (isPickerMode) onSlotClick() else onClearSlot() },
        modifier = modifier.aspectRatio(16f / 9f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(18.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AppColors.SurfaceElevated,
            focusedContainerColor = AppColors.SurfaceEmphasis,
            pressedContainerColor = AppColors.SurfaceEmphasis
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(border = BorderStroke(1.dp, accent.copy(alpha = 0.35f))),
            focusedBorder = Border(border = BorderStroke(FocusSpec.BorderWidth, AppColors.Focus))
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = FocusSpec.FocusedScale)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            AppColors.SurfaceElevated,
                            AppColors.Surface
                        )
                    )
                )
                .padding(16.dp)
        ) {
            if (isEmpty) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    StatusPill(
                        label = stringResource(R.string.multiview_empty_slot),
                        containerColor = AppColors.SurfaceEmphasis
                    )
                    Text(
                        text = if (isPickerMode) {
                            stringResource(R.string.multiview_add_to_split)
                        } else {
                            stringResource(R.string.multiview_empty_slot)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = AppColors.TextPrimary
                    )
                    Text(
                        text = if (isPickerMode) {
                            stringResource(R.string.multiview_planner_slot_hint)
                        } else {
                            stringResource(R.string.multiview_replace_empty)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusPill(
                            label = stringResource(R.string.multiview_slot_label, slotIndex + 1),
                            containerColor = AppColors.BrandMuted
                        )
                        Text(
                            text = occupant.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = AppColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        occupant.categoryName?.takeIf { it.isNotBlank() }?.let { categoryName ->
                            Text(
                                text = categoryName,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Text(
                        text = if (isPickerMode) {
                            stringResource(R.string.multiview_replace_slot)
                        } else {
                            stringResource(R.string.multiview_remove_slot)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = accent,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
