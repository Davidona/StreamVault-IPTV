package com.streamvault.feature.live.home

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ButtonColors
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.BrowseColors
import com.streamvault.core.ui.design.LocalBrowseVisuals
import com.streamvault.core.ui.design.browse.Primary
import com.streamvault.core.ui.design.contentOnColor
import com.streamvault.core.ui.design.requestFocusSafely
import com.streamvault.core.ui.interaction.TvButton
import com.streamvault.domain.model.Channel
import com.streamvault.feature.live.R
import com.streamvault.feature.live.presentation.time.LocalLiveTimeFormat
import com.streamvault.feature.live.presentation.time.createLiveTimeFormat
import com.streamvault.player.PlayerEngine
import com.streamvault.player.PlayerSurfaceResizeMode
import com.streamvault.player.PlayerTrack
import com.streamvault.player.ui.PlayerRenderView
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * Inline fullscreen for the Pro-mode live preview.
 *
 * When true, pressing OK on the channel that is already previewing expands the preview video to
 * fullscreen inside Live TV instead of navigating to the Player route. Set to false to restore the
 * previous behaviour (engine handoff + navigation).
 */
internal const val INLINE_PREVIEW_FULLSCREEN_ENABLED = true

private const val CHROME_TIMEOUT_MS = 5_000L
private const val SEEK_FEEDBACK_MS = 900L
private const val TRANSLATION_MESSAGE_MS = 4_000L
private const val FOCUS_TAG = "InlinePreviewVideo"

/** Dark panel behind the info banner and control buttons, so both stay readable over bright video. */
private val InlineChromeBackground = Color.Black.copy(alpha = 0.72f)

private const val CONTROL_REWIND = 0
private const val CONTROL_PLAY_PAUSE = 1
private const val CONTROL_FORWARD = 2
private const val CONTROL_SUBTITLES = 3
private const val CONTROL_FULL_PLAYER = 4
private const val CONTROL_COUNT = 5

/**
 * Screen-level host for the live preview video.
 *
 * The video lives in ONE fixed place in the composition (a child of the Live TV root Box) and is
 * only ever re-laid-out: in preview mode it is placed exactly over the preview pane's video slot,
 * in fullscreen it fills the screen. Because the AndroidView/SurfaceView is never removed,
 * re-parented or re-keyed, the decoder keeps rendering to the same Surface and playback continues
 * uninterrupted instead of switching to a new player screen.
 *
 * In fullscreen it also offers the everyday player controls (play/pause, ⏪/⏩ 10 s, subtitles)
 * so the full player is only needed for advanced options. Keys follow [decideInlineFullscreenKey].
 *
 * @param slotBounds the preview slot in this layer's coordinate space.
 * @param modifier should make this layer cover the whole screen (e.g. `Modifier.matchParentSize()`).
 */
@Composable
internal fun InlinePreviewVideoLayer(
    playerEngine: PlayerEngine,
    slotBounds: IntRect,
    isFullscreen: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    channel: Channel?,
    onExitFullscreen: () -> Unit,
    onZap: (step: Int) -> Unit,
    onOpenFullPlayer: () -> Unit,
    onSelectSubtitle: (trackId: String?) -> Unit,
    modifier: Modifier = Modifier,
    liveTranslationAvailable: Boolean = false,
    liveTranslationActive: Boolean = false,
    translationMessage: String? = null,
    onSelectTranslation: () -> Unit = {}
) {
    val renderSurfaceType by playerEngine.renderSurfaceType.collectAsStateWithLifecycle()
    val isPlaying by playerEngine.isPlaying.collectAsStateWithLifecycle()
    val subtitleTracks by playerEngine.availableSubtitleTracks.collectAsStateWithLifecycle()

    val rootFocus = remember { FocusRequester() }
    val controlFocus = remember { List(CONTROL_COUNT) { FocusRequester() } }
    // Options: "Off", then "Real-time translation" (when available), then one per subtitle track.
    val translationOptions = if (liveTranslationAvailable) 1 else 0
    val firstTrackOption = 1 + translationOptions
    val subtitleFocus = remember(subtitleTracks.size, translationOptions) {
        List(firstTrackOption + subtitleTracks.size) { FocusRequester() }
    }
    var visibleTranslationMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(translationMessage) {
        visibleTranslationMessage = translationMessage
        if (translationMessage != null) {
            delay(TRANSLATION_MESSAGE_MS)
            visibleTranslationMessage = null
        }
    }

    var mode by remember { mutableStateOf(InlineFullscreenMode.WATCHING) }
    var chromeVisible by remember { mutableStateOf(false) }
    var interactionNonce by remember { mutableIntStateOf(0) }
    var controlIndex by remember { mutableIntStateOf(CONTROL_PLAY_PAUSE) }
    var subtitleIndex by remember { mutableIntStateOf(0) }
    var pausedByUser by remember { mutableStateOf(false) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    var seekFeedbackNonce by remember { mutableIntStateOf(0) }

    fun showChrome() {
        chromeVisible = true
        interactionNonce++
    }

    fun setPaused(paused: Boolean) {
        if (paused) playerEngine.pause() else playerEngine.play()
        pausedByUser = paused
        showChrome()
    }

    fun seek(backward: Boolean) {
        if (backward) playerEngine.seekBackward() else playerEngine.seekForward()
        seekFeedback = if (backward) "−10 s" else "+10 s"
        seekFeedbackNonce++
        showChrome()
    }

    fun openSubtitles() {
        val selected = subtitleTracks.indexOfFirst { it.isSelected }
        subtitleIndex = when {
            liveTranslationActive && liveTranslationAvailable -> 1
            selected >= 0 -> selected + firstTrackOption
            else -> 0
        }
        mode = InlineFullscreenMode.SUBTITLES
        showChrome()
    }

    LaunchedEffect(isFullscreen) {
        mode = InlineFullscreenMode.WATCHING
        controlIndex = CONTROL_PLAY_PAUSE
        if (isFullscreen) showChrome() else chromeVisible = false
    }

    // Focus follows the mode/selection. Waiting a frame lets newly shown buttons attach first.
    LaunchedEffect(isFullscreen, mode, controlIndex, subtitleIndex, subtitleFocus) {
        if (!isFullscreen) return@LaunchedEffect
        withFrameNanos { }
        val target = when (mode) {
            InlineFullscreenMode.WATCHING -> rootFocus
            InlineFullscreenMode.CONTROLS -> controlFocus[controlIndex]
            InlineFullscreenMode.SUBTITLES -> subtitleFocus[subtitleIndex.coerceIn(0, subtitleFocus.lastIndex)]
        }
        target.requestFocusSafely(tag = FOCUS_TAG, target = "Inline fullscreen ${mode.name}")
    }

    // Auto-hide the banner/controls after inactivity (never while picking a subtitle).
    LaunchedEffect(interactionNonce, mode, chromeVisible) {
        if (!chromeVisible || mode == InlineFullscreenMode.SUBTITLES) return@LaunchedEffect
        delay(CHROME_TIMEOUT_MS)
        chromeVisible = false
        if (mode == InlineFullscreenMode.CONTROLS) mode = InlineFullscreenMode.WATCHING
    }

    LaunchedEffect(seekFeedbackNonce) {
        if (seekFeedback == null) return@LaunchedEffect
        delay(SEEK_FEEDBACK_MS)
        seekFeedback = null
    }

    fun handleFullscreenKey(event: KeyEvent): Boolean {
        val native = event.nativeKeyEvent
        val isDown = event.type == KeyEventType.KeyDown
        if (isDown) interactionNonce++
        return when (val action = decideInlineFullscreenKey(mode, native.keyCode, isDown, native.repeatCount)) {
            InlineFullscreenAction.Pass -> false
            InlineFullscreenAction.Consume -> true
            InlineFullscreenAction.ExitFullscreen -> { onExitFullscreen(); true }
            InlineFullscreenAction.ShowControls -> {
                controlIndex = CONTROL_PLAY_PAUSE
                mode = InlineFullscreenMode.CONTROLS
                showChrome()
                true
            }
            InlineFullscreenAction.HideControls -> {
                mode = InlineFullscreenMode.WATCHING
                chromeVisible = false
                true
            }
            InlineFullscreenAction.CloseSubtitles -> {
                controlIndex = CONTROL_SUBTITLES
                mode = InlineFullscreenMode.CONTROLS
                showChrome()
                true
            }
            is InlineFullscreenAction.MoveFocus -> {
                if (mode == InlineFullscreenMode.CONTROLS) {
                    controlIndex = (controlIndex + action.delta).coerceIn(0, CONTROL_COUNT - 1)
                } else {
                    subtitleIndex = (subtitleIndex + action.delta).coerceIn(0, subtitleFocus.lastIndex)
                }
                true
            }
            is InlineFullscreenAction.Zap -> {
                pausedByUser = false
                onZap(action.step)
                showChrome()
                true
            }
            InlineFullscreenAction.TogglePlayPause -> { setPaused(isPlaying); true }
            InlineFullscreenAction.Play -> { setPaused(false); true }
            InlineFullscreenAction.Pause -> { setPaused(true); true }
            InlineFullscreenAction.SeekBackward -> { seek(backward = true); true }
            InlineFullscreenAction.SeekForward -> { seek(backward = false); true }
            InlineFullscreenAction.OpenFullPlayer -> { onOpenFullPlayer(); true }
        }
    }

    Box(
        modifier = modifier
            .layout { measurable, constraints ->
                val target = if (isFullscreen) {
                    IntRect(0, 0, constraints.maxWidth, constraints.maxHeight)
                } else {
                    slotBounds
                }
                val placeable = measurable.measure(
                    Constraints.fixed(target.width.coerceAtLeast(0), target.height.coerceAtLeast(0))
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.place(target.left, target.top)
                }
            }
            .background(Color.Black)
            .then(
                if (isFullscreen) {
                    Modifier
                        .focusRequester(rootFocus)
                        .onPreviewKeyEvent(::handleFullscreenKey)
                        .focusable()
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Must stay the first child so the video view is never recreated by later siblings.
        if (errorMessage == null) {
            PlayerRenderView(
                playerEngine = playerEngine,
                resizeMode = PlayerSurfaceResizeMode.FIT,
                surfaceType = renderSurfaceType,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        if (isLoading && channel != null) {
            Row(
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.62f), RoundedCornerShape(10.dp))
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

        if (isFullscreen) {
            if (pausedByUser && !isPlaying && !chromeVisible) {
                OverlayBadge(
                    text = stringResource(R.string.live_inline_paused),
                    icon = Icons.Filled.Pause,
                    modifier = Modifier.align(Alignment.TopStart).padding(32.dp)
                )
            }
            seekFeedback?.let { feedback ->
                OverlayBadge(
                    text = feedback,
                    icon = if (feedback.startsWith("−")) Icons.Filled.FastRewind else Icons.Filled.FastForward,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            if (chromeVisible) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    channel?.let { InlineFullscreenInfoBanner(channel = it, modifier = Modifier.fillMaxWidth()) }
                    InlineControlRow(
                        isPlaying = isPlaying,
                        focusRequesters = controlFocus,
                        onRewind = { controlIndex = CONTROL_REWIND; seek(backward = true) },
                        onPlayPause = { controlIndex = CONTROL_PLAY_PAUSE; setPaused(isPlaying) },
                        onForward = { controlIndex = CONTROL_FORWARD; seek(backward = false) },
                        onSubtitles = { controlIndex = CONTROL_SUBTITLES; openSubtitles() },
                        onFullPlayer = onOpenFullPlayer
                    )
                }
            }
            visibleTranslationMessage?.let { message ->
                OverlayBadge(
                    text = message,
                    icon = Icons.Filled.Translate,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 32.dp)
                )
            }
            if (mode == InlineFullscreenMode.SUBTITLES) {
                fun backToControls() {
                    controlIndex = CONTROL_SUBTITLES
                    mode = InlineFullscreenMode.CONTROLS
                    showChrome()
                }
                InlineSubtitlePanel(
                    tracks = subtitleTracks,
                    focusRequesters = subtitleFocus,
                    translationAvailable = liveTranslationAvailable,
                    translationActive = liveTranslationActive,
                    onSelectTranslation = {
                        onSelectTranslation()
                        backToControls()
                    },
                    onSelect = { trackId ->
                        onSelectSubtitle(trackId)
                        backToControls()
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 48.dp)
                )
            }
        }
    }
}

@Composable
private fun InlineControlRow(
    isPlaying: Boolean,
    focusRequesters: List<FocusRequester>,
    onRewind: () -> Unit,
    onPlayPause: () -> Unit,
    onForward: () -> Unit,
    onSubtitles: () -> Unit,
    onFullPlayer: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ControlButton(Icons.Filled.FastRewind, stringResource(R.string.live_inline_rewind),
            focusRequesters[CONTROL_REWIND], onRewind)
        ControlButton(
            if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            stringResource(if (isPlaying) R.string.live_inline_pause else R.string.live_inline_play),
            focusRequesters[CONTROL_PLAY_PAUSE], onPlayPause
        )
        ControlButton(Icons.Filled.FastForward, stringResource(R.string.live_inline_forward),
            focusRequesters[CONTROL_FORWARD], onForward)
        // Always enabled (a disabled button can't take focus, which would desync controlIndex);
        // the panel says so when the channel has no subtitle tracks.
        ControlButton(Icons.Filled.ClosedCaption, stringResource(R.string.live_inline_subtitles),
            focusRequesters[CONTROL_SUBTITLES], onSubtitles)
        ControlButton(Icons.Filled.Fullscreen, stringResource(R.string.live_inline_full_player),
            focusRequesters[CONTROL_FULL_PLAYER], onFullPlayer)
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    TvButton(
        onClick = onClick,
        enabled = enabled,
        colors = inlineControlColors(),
        modifier = Modifier.focusRequester(focusRequester)
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(text = label, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
    }
}

/**
 * The info banner's dark panel and white text when unfocused (TvButton's default container is
 * nearly transparent, which disappears over video); the usual TvButton highlight when focused.
 */
@Composable
private fun inlineControlColors(): ButtonColors =
    if (LocalBrowseVisuals.current) {
        ButtonDefaults.colors(
            containerColor = InlineChromeBackground,
            contentColor = Color.White,
            focusedContainerColor = BrowseColors.BrandStrong,
            focusedContentColor = contentOnColor(BrowseColors.BrandStrong)
        )
    } else {
        ButtonDefaults.colors(
            containerColor = InlineChromeBackground,
            contentColor = Color.White
        )
    }

@Composable
private fun InlineSubtitlePanel(
    tracks: List<PlayerTrack>,
    focusRequesters: List<FocusRequester>,
    translationAvailable: Boolean,
    translationActive: Boolean,
    onSelectTranslation: () -> Unit,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val noneSelected = tracks.none { it.isSelected } && !translationActive
    val firstTrackOption = if (translationAvailable) 2 else 1
    Column(
        modifier = modifier
            .width(320.dp)
            .background(Color.Black.copy(alpha = 0.85f), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.live_inline_subtitles),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White
        )
        SubtitleOption(
            label = stringResource(R.string.live_inline_subtitles_off),
            selected = noneSelected,
            focusRequester = focusRequesters[0],
            onClick = { onSelect(null) }
        )
        if (translationAvailable) {
            SubtitleOption(
                label = stringResource(R.string.live_inline_realtime_translation),
                selected = translationActive,
                focusRequester = focusRequesters[1],
                onClick = onSelectTranslation
            )
        }
        if (tracks.isEmpty()) {
            Text(
                text = stringResource(R.string.live_inline_no_subtitles),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
        tracks.forEachIndexed { index, track ->
            SubtitleOption(
                label = track.displayLabel(index),
                selected = track.isSelected && !translationActive,
                focusRequester = focusRequesters[index + firstTrackOption],
                onClick = { onSelect(track.id) }
            )
        }
    }
}

@Composable
private fun SubtitleOption(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    TvButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
    ) {
        Box(modifier = Modifier.size(20.dp)) {
            if (selected) Icon(imageVector = Icons.Filled.Check, contentDescription = null)
        }
        Text(
            text = label,
            modifier = Modifier.padding(start = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlayerTrack.displayLabel(index: Int): String {
    val languageName = language
        ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
        ?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()) }
        ?.takeIf { it.isNotBlank() }
    return name.takeIf { it.isNotBlank() }
        ?: languageName
        ?: stringResource(R.string.live_inline_subtitle_track_fallback, index + 1)
}

@Composable
private fun OverlayBadge(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        Text(text = text, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

@Composable
private fun InlineFullscreenInfoBanner(
    channel: Channel,
    modifier: Modifier = Modifier
) {
    val appTimeFormat = LocalLiveTimeFormat.current
    val timeFormat = remember(appTimeFormat) { appTimeFormat.createLiveTimeFormat() }
    Column(
        modifier = modifier
            .background(InlineChromeBackground, RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = channel.number.takeIf { it > 0 }?.let { "$it  ${channel.name}" } ?: channel.name,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        channel.currentProgram?.let { program ->
            Text(
                text = program.title,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = stringResource(
                    R.string.time_range_format,
                    timeFormat.format(Date(program.startTime)),
                    timeFormat.format(Date(program.endTime))
                ),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
        Text(
            text = stringResource(R.string.live_inline_fullscreen_hint),
            style = MaterialTheme.typography.labelSmall,
            color = Primary
        )
    }
}
