package com.streamvault.feature.playback.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.feature.playback.R

internal data class VodSettingsAction(
    val id: String,
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit
)

@Composable
internal fun VodPlaybackSettingsSheet(
    state: VodOverlayState,
    onOpenSubtitleTracks: () -> Unit,
    onOpenAudioTracks: () -> Unit,
    onOpenVideoTracks: () -> Unit,
    onOpenPlaybackSpeed: () -> Unit,
    onOpenStopPlaybackTimer: () -> Unit,
    onOpenIdleStandbyTimer: () -> Unit,
    onOpenAudioVideoSync: () -> Unit,
    onToggleAspectRatio: () -> Unit,
    onToggleMute: () -> Unit,
    onEnterPictureInPicture: () -> Unit,
    onOpenExternalPlayer: () -> Unit,
    onOpenEpisodes: () -> Unit,
    onOpenSplitScreen: () -> Unit,
    onCast: () -> Unit,
    onStopCasting: () -> Unit,
    isMuted: Boolean,
    isCastConnected: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val closeLabel = stringResource(R.string.player_close_playback_settings)
    val firstActionFocusRequester = remember { FocusRequester() }
    val actions = buildList {
        if (state.showSubtitleAction) add(VodSettingsAction("subtitles", stringResource(R.string.player_subs), onClick = onOpenSubtitleTracks))
        if (state.showAudioAction) add(VodSettingsAction("audio", stringResource(R.string.player_audio), onClick = onOpenAudioTracks))
        if (state.showVideoQualityAction) add(VodSettingsAction("quality", stringResource(R.string.player_video_quality), onClick = onOpenVideoTracks))
        add(VodSettingsAction("speed", stringResource(R.string.player_playback_speed_title), onClick = onOpenPlaybackSpeed))
        add(VodSettingsAction("stop_timer", stringResource(R.string.player_stop_playback_after), onClick = onOpenStopPlaybackTimer))
        add(VodSettingsAction("idle_timer", stringResource(R.string.player_idle_standby_after), onClick = onOpenIdleStandbyTimer))
        add(VodSettingsAction("aspect", stringResource(R.string.player_aspect_ratio), onClick = onToggleAspectRatio))
        add(VodSettingsAction("mute", stringResource(if (isMuted) R.string.player_unmute else R.string.player_mute), onClick = onToggleMute))
        add(VodSettingsAction("pip", stringResource(R.string.player_picture_in_picture), onClick = onEnterPictureInPicture))
        if (state.showEpisodesAction) add(VodSettingsAction("episodes", stringResource(R.string.player_episodes), onClick = onOpenEpisodes))
        if (state.showExternalPlayerAction) {
            add(VodSettingsAction("external", stringResource(R.string.player_open_in_external_player), onClick = onOpenExternalPlayer))
        }
        add(VodSettingsAction("split_screen", stringResource(R.string.multiview_nav), onClick = onOpenSplitScreen))
        add(
            VodSettingsAction(
                id = "cast",
                label = stringResource(if (isCastConnected) R.string.player_stop_casting else R.string.player_cast),
                onClick = if (isCastConnected) onStopCasting else onCast
            )
        )
        if (state.showAudioVideoSyncAction) {
            add(VodSettingsAction("av_sync", stringResource(R.string.player_av_sync_short), onClick = onOpenAudioVideoSync))
        }
    }
    LaunchedEffect(actions.firstOrNull()?.id) {
        if (actions.isNotEmpty()) firstActionFocusRequester.requestFocus()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.32f))
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(360.dp),
            colors = SurfaceDefaults.colors(containerColor = Color(0xFF10151D))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.player_playback_settings),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    VodControlButton(
                        onClick = onDismiss,
                        modifier = Modifier.semantics { contentDescription = closeLabel }
                    ) {
                        Icon(imageVector = Icons.Default.Close, contentDescription = null)
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(actions, key = VodSettingsAction::id) { action ->
                        TvClickableSurface(
                            onClick = action.onClick,
                            enabled = action.enabled,
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
                            colors = ClickableSurfaceDefaults.colors(
                                containerColor = Color.White.copy(alpha = 0.035f),
                                contentColor = Color(0xFFDCE3ED),
                                focusedContainerColor = Color(0xFFDFEBFA),
                                focusedContentColor = Color(0xFF10151D)
                            ),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .then(
                                    if (action.id == actions.firstOrNull()?.id) {
                                        Modifier.focusRequester(firstActionFocusRequester)
                                    } else {
                                        Modifier
                                    }
                                )
                                .semantics { contentDescription = action.label }
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Icon(
                                    imageVector = vodSettingsIcon(action.id, isMuted),
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = LocalContentColor.current.copy(alpha = 0.8f)
                                )
                                Text(
                                    text = action.label,
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LocalContentColor.current,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = LocalContentColor.current.copy(alpha = 0.45f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun vodSettingsIcon(id: String, isMuted: Boolean): ImageVector = when (id) {
    "subtitles" -> Icons.Default.ClosedCaption
    "audio" -> Icons.Default.Audiotrack
    "quality" -> Icons.Default.HighQuality
    "speed" -> Icons.Default.Speed
    "stop_timer" -> Icons.Default.Timer
    "idle_timer" -> Icons.Default.Bedtime
    "aspect" -> Icons.Default.AspectRatio
    "mute" -> if (isMuted) Icons.Default.VolumeOff else Icons.Default.VolumeUp
    "pip" -> Icons.Default.PictureInPictureAlt
    "episodes" -> Icons.Default.VideoLibrary
    "external" -> Icons.Default.OpenInNew
    "split_screen" -> Icons.Default.ViewWeek
    "cast" -> Icons.Default.Cast
    "av_sync" -> Icons.Default.Sync
    else -> Icons.Default.Settings
}
