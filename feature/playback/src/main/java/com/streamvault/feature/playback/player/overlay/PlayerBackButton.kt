package com.streamvault.feature.playback.player.overlay

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.design.AppColors
import com.streamvault.domain.model.PlayerBackButtonVisibility
import com.streamvault.feature.playback.R

internal enum class PlayerBackButtonPlacement {
    HIDDEN,
    STANDALONE,
    CONTROLS_TOP_BAR,
    CHANNEL_INFO_OVERLAY
}

internal fun playerBackButtonPlacement(
    mode: PlayerBackButtonVisibility,
    controlsVisible: Boolean,
    hasBlockingOverlay: Boolean,
    isInPictureInPictureMode: Boolean,
    channelInfoOverlayVisible: Boolean = false
): PlayerBackButtonPlacement {
    if (hasBlockingOverlay || isInPictureInPictureMode || mode == PlayerBackButtonVisibility.HIDDEN) {
        return PlayerBackButtonPlacement.HIDDEN
    }
    if (channelInfoOverlayVisible) return PlayerBackButtonPlacement.CHANNEL_INFO_OVERLAY
    if (controlsVisible) return PlayerBackButtonPlacement.CONTROLS_TOP_BAR
    return if (mode == PlayerBackButtonVisibility.ALWAYS) {
        PlayerBackButtonPlacement.STANDALONE
    } else {
        PlayerBackButtonPlacement.HIDDEN
    }
}

@Composable
internal fun PlayerBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backButtonDescription = stringResource(R.string.player_back_to_menu)
    val clickAction = onClick
    TvClickableSurface(
        onClick = onClick,
        modifier = modifier
            .size(44.dp)
            .testTag("player_back_button")
            .semantics {
                contentDescription = backButtonDescription
                onClick(action = {
                    clickAction()
                    true
                })
            },
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Black.copy(alpha = 0.36f),
            contentColor = Color.White,
            focusedContainerColor = Color.White,
            focusedContentColor = Color(0xFF10151D)
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border.None,
            focusedBorder = Border(
                border = BorderStroke(2.dp, AppColors.Focus),
                shape = CircleShape
            )
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow.None)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = null,
                tint = LocalContentColor.current,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
