package com.streamvault.feature.playback.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.design.LocalAppSpacing
import com.streamvault.core.ui.time.LocalUiTimeFormat
import com.streamvault.core.ui.time.createTimeFormat
import com.streamvault.domain.model.Episode
import com.streamvault.feature.playback.R
import com.streamvault.player.PlayerChapter
import java.util.Date
import kotlinx.coroutines.delay

/** Season and episode of the playing series episode, shown as chips under the title. */
internal data class VodEpisodeHeader(
    val seasonNumber: Int,
    val episodeNumber: Int,
    val episodeTitle: String
)

/**
 * Providers often name the episode with the full series title, so the title line already says it.
 * The episode name is kept only when it adds something the title does not contain.
 */
internal fun buildVodEpisodeHeader(episode: Episode, displayTitle: String): VodEpisodeHeader {
    val name = episode.title.trim()
    val shownTitle = displayTitle.trim()
    val isRepeat = name.isEmpty() ||
        (shownTitle.isNotEmpty() &&
            (shownTitle.contains(name, ignoreCase = true) || name.contains(shownTitle, ignoreCase = true)))
    return VodEpisodeHeader(
        seasonNumber = episode.seasonNumber,
        episodeNumber = episode.episodeNumber,
        episodeTitle = if (isRepeat) "" else name
    )
}

/**
 * Removes the "S01E02" marker (and one adjacent separator) that matches [seasonNumber] and
 * [episodeNumber], for when the season and episode chips already say it. A marker with other
 * numbers is left alone.
 */
internal fun stripEpisodeMarker(title: String, seasonNumber: Int, episodeNumber: Int): String {
    val marker = Regex(
        """\s*[-–—:|]?\s*\bS0*$seasonNumber\s?E0*$episodeNumber\b""",
        RegexOption.IGNORE_CASE
    )
    return title.replace(marker, "").trim()
}

// The chips sit on the top gradient, which over a bright frame is mid gray and over a dark one is
// near black. Dark theme containers vanish in one or the other, so the chips are light: opaque, 14.9:1
// against black, 4.1:1 against that mid gray, and black text on them is 14.9:1. Theme independent
// because it only ever sits on video.
private val VodChipContainer = Color.White.copy(alpha = 0.85f).compositeOver(Color.Black)

// The line sits above the bottom scrim's dark zone, so shadows keep it readable on a bright frame.
// One shadow is too faint on white, so the text is drawn twice: a tight shadow for the glyph edge
// and a wide one for the halo.
private val EndsAtTightShadow = Shadow(color = Color.Black, offset = Offset(0f, 1.5f), blurRadius = 3f)
private val EndsAtWideShadow = Shadow(color = Color.Black, offset = Offset(0f, 2f), blurRadius = 12f)

private const val ENDS_AT_REFRESH_MS = 30_000L
private const val MS_PER_MINUTE = 60_000L

/** Top-start block: title, season and episode chips, episode name, current chapter. Sits on the overlay's top gradient. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun VodHeader(
    title: String,
    episode: VodEpisodeHeader?,
    currentChapter: PlayerChapter?,
    modifier: Modifier = Modifier
) {
    val spacing = LocalAppSpacing.current
    Column(
        modifier = modifier.padding(
            start = VOD_OVERLAY_HORIZONTAL_PADDING,
            top = VOD_OVERLAY_VERTICAL_PADDING,
            end = spacing.md
        )
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (episode != null) {
            FlowRow(
                modifier = Modifier.padding(top = spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(spacing.chipGap),
                verticalArrangement = Arrangement.spacedBy(spacing.chipGap)
            ) {
                if (episode.seasonNumber > 0) {
                    VodChip(stringResource(R.string.player_season_label, episode.seasonNumber))
                }
                if (episode.episodeNumber > 0) {
                    VodChip(stringResource(R.string.player_episode_label, episode.episodeNumber))
                }
            }
            if (episode.episodeTitle.isNotBlank()) {
                Text(
                    text = episode.episodeTitle,
                    modifier = Modifier.padding(top = spacing.xs),
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        currentChapter?.let { chapter ->
            Text(
                text = stringResource(R.string.player_chapter_title, chapter.index, chapter.title),
                modifier = Modifier.padding(top = spacing.xs),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private const val START_BADGES_HOLD_MS = 4_000L
private const val START_BADGES_FADE_MS = 500

// The engine measures the frame rate from rendered frames when the container does not declare one,
// which needs about 4 s of timing plus a stats tick. Streams that declare it show it at once; for the
// rest the chips flash after this wait without it, and the frame rate chip joins when it resolves.
internal const val START_BADGES_FRAME_RATE_WAIT_MS = 3_000L

/**
 * How long to wait before flashing the start-up badges, or null when they should not show yet:
 * nothing is shown while buffering or before the decoded format yields any chip, and the frame rate
 * gets [START_BADGES_FRAME_RATE_WAIT_MS] to resolve before the chips show without it. A chip list
 * change after that (the frame rate arriving) restarts the hold, so the new chip is seen.
 */
internal fun startBadgesWaitMs(isReady: Boolean, hasBadges: Boolean, frameRateKnown: Boolean): Long? = when {
    !isReady || !hasBadges -> null
    frameRateKnown -> 0L
    else -> START_BADGES_FRAME_RATE_WAIT_MS
}

/**
 * Flashes the stream badges once per stream, when playback is ready, so quality, frame rate and
 * codecs are known without opening the overlay. Same chips and same spot as the overlay's own,
 * hidden while the overlay is up so nothing jumps.
 */
@Composable
internal fun VodStartBadges(
    badges: List<String>,
    streamUrl: String,
    isReady: Boolean,
    frameRateKnown: Boolean,
    controlsVisible: Boolean,
    modifier: Modifier = Modifier
) {
    var holding by remember(streamUrl) { mutableStateOf(false) }
    var finished by remember(streamUrl) { mutableStateOf(false) }
    LaunchedEffect(badges, streamUrl, isReady, frameRateKnown) {
        if (finished) return@LaunchedEffect
        val wait = startBadgesWaitMs(isReady, badges.isNotEmpty(), frameRateKnown)
        if (wait == null) {
            holding = false
            return@LaunchedEffect
        }
        delay(wait)
        holding = true
        delay(START_BADGES_HOLD_MS)
        holding = false
        finished = true
    }
    AnimatedVisibility(
        visible = holding && !controlsVisible && badges.isNotEmpty(),
        enter = fadeIn(tween(START_BADGES_FADE_MS)),
        exit = fadeOut(tween(START_BADGES_FADE_MS)),
        modifier = modifier
    ) {
        VodBadges(
            badges = badges,
            modifier = Modifier.padding(top = VOD_OVERLAY_VERTICAL_PADDING, end = VOD_OVERLAY_HORIZONTAL_PADDING)
        )
    }
}

/** Top-end chips read from the decoded format. They carry their own fill, so no scrim. Empty draws nothing. */
@Composable
internal fun VodBadges(badges: List<String>, modifier: Modifier = Modifier) {
    if (badges.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(LocalAppSpacing.current.chipGap)
    ) {
        badges.forEach { VodChip(it) }
    }
}

@Composable
private fun VodChip(text: String) {
    val spacing = LocalAppSpacing.current
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(spacing.xs))
            .background(VodChipContainer)
            .padding(horizontal = spacing.sm, vertical = spacing.xs / 2),
        style = MaterialTheme.typography.bodyMedium,
        color = Color.Black,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1
    )
}

/** Elapsed at the start of the bar, remaining at its end. Leaf so a position tick recomposes only this row. */
@Composable
internal fun VodTimeRow(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(VOD_TIME_ROW_HEIGHT)
            .padding(horizontal = VOD_TIMELINE_TRACK_INSET),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatVodDuration(positionMs),
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "-${formatVodDuration((durationMs - positionMs).coerceAtLeast(0L))}",
            style = MaterialTheme.typography.titleSmall,
            color = Color.White
        )
    }
}

/** Wall-clock end time, right-aligned over the end of the bar. Draws nothing when the duration is unknown. */
@Composable
internal fun VodEndsAtRow(positionMs: Long, durationMs: Long, playbackSpeed: Float, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = VOD_TIMELINE_TRACK_INSET),
        horizontalArrangement = Arrangement.End
    ) {
        VodEndsAt(positionMs, durationMs, playbackSpeed)
    }
}

@Composable
private fun VodEndsAt(positionMs: Long, durationMs: Long, playbackSpeed: Float) {
    // While paused the position does not change, so the wall clock needs its own tick.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ENDS_AT_REFRESH_MS)
            nowMs = System.currentTimeMillis()
        }
    }
    val endsAtMs = vodEndsAtMs(nowMs, positionMs, durationMs, playbackSpeed) ?: return
    val appTimeFormat = LocalUiTimeFormat.current
    val timeFormat = remember(appTimeFormat) { appTimeFormat.createTimeFormat() }
    // Minute resolution: the position ticks far more often than the shown time changes.
    val endsAtText = remember(endsAtMs / MS_PER_MINUTE, timeFormat) { timeFormat.format(Date(endsAtMs)) }
    val text = stringResource(R.string.player_vod_ends_at, endsAtText)
    val style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
    Box {
        Text(
            text = text,
            style = style.copy(shadow = EndsAtWideShadow),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = text,
            style = style.copy(shadow = EndsAtTightShadow),
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
