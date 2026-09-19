package com.streamvault.feature.live.presentation.epg

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.streamvault.core.ui.interaction.TvClickableSurface
import com.streamvault.core.ui.theme.FocusBorder
import com.streamvault.core.ui.theme.OnSurface
import com.streamvault.core.ui.theme.OnSurfaceDim
import com.streamvault.core.ui.theme.Primary
import com.streamvault.core.ui.theme.SurfaceElevated
import com.streamvault.core.ui.theme.SurfaceHighlight
import com.streamvault.feature.live.presentation.components.LiveSelectionChip
import com.streamvault.feature.live.presentation.components.LiveSelectionChipRow
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale

data class LiveGuideTimeControlLabels(
    val section: String,
    val previousDay: String,
    val pageBack: String,
    val jumpBackHalfHour: String,
    val jumpBack: String,
    val jumpNow: String,
    val jumpForwardHalfHour: String,
    val jumpForward: String,
    val pageForward: String,
    val jumpPrimeTime: String,
    val jumpTomorrow: String,
    val nextDay: String
)

data class LiveGuideDayLabels(
    val section: String,
    val yesterday: String,
    val today: String,
    val tomorrow: String
)

data class LiveGuideModeLabels(
    val title: String,
    val subtitle: String,
    val all: String,
    val allHint: String,
    val anchored: String,
    val anchoredHint: String,
    val archiveReady: String,
    val archiveReadyHint: String
)

data class LiveGuideDensityLabels(
    val title: String,
    val subtitle: String,
    val compact: String,
    val compactHint: String,
    val comfortable: String,
    val comfortableHint: String,
    val cinematic: String,
    val cinematicHint: String
)

data class LiveGuideViewOptionsLabels(
    val title: String,
    val scheduledOnlyOn: String,
    val scheduledOnlyOff: String,
    val scheduledOnlyHint: String
)

data class LiveGuideFavoritesLabels(
    val title: String,
    val subtitle: String,
    val all: String,
    val allHint: String,
    val favorites: String,
    val favoritesHint: String
)

data class LiveGuideControlLabels(
    val time: LiveGuideTimeControlLabels,
    val day: LiveGuideDayLabels,
    val mode: LiveGuideModeLabels,
    val density: LiveGuideDensityLabels,
    val viewOptions: LiveGuideViewOptionsLabels,
    val favorites: LiveGuideFavoritesLabels
)

@Composable
fun LiveGuideDensityRow(
    selectedDensity: GuideDensity,
    labels: LiveGuideDensityLabels,
    onDensitySelected: (GuideDensity) -> Unit
) {
    LiveSelectionChipRow(
        title = labels.title,
        subtitle = labels.subtitle,
        chips = listOf(
            LiveSelectionChip(GuideDensity.COMPACT.name, labels.compact, labels.compactHint),
            LiveSelectionChip(GuideDensity.COMFORTABLE.name, labels.comfortable, labels.comfortableHint),
            LiveSelectionChip(GuideDensity.CINEMATIC.name, labels.cinematic, labels.cinematicHint)
        ),
        selectedKey = selectedDensity.name,
        onChipSelected = { key -> GuideDensity.entries.firstOrNull { it.name == key }?.let(onDensitySelected) }
    )
}

@Composable
fun LiveGuideModeRow(
    selectedMode: GuideChannelMode,
    labels: LiveGuideModeLabels,
    onModeSelected: (GuideChannelMode) -> Unit
) {
    LiveSelectionChipRow(
        title = labels.title,
        subtitle = labels.subtitle,
        chips = listOf(
            LiveSelectionChip(GuideChannelMode.ALL.name, labels.all, labels.allHint),
            LiveSelectionChip(GuideChannelMode.ANCHORED.name, labels.anchored, labels.anchoredHint),
            LiveSelectionChip(GuideChannelMode.ARCHIVE_READY.name, labels.archiveReady, labels.archiveReadyHint)
        ),
        selectedKey = selectedMode.name,
        onChipSelected = { key -> GuideChannelMode.entries.firstOrNull { it.name == key }?.let(onModeSelected) }
    )
}

@Composable
fun LiveGuideTimeControlsRow(
    labels: LiveGuideTimeControlLabels,
    onJumpToPreviousDay: () -> Unit,
    onPageBackward: () -> Unit,
    onJumpBackwardHalfHour: () -> Unit,
    onJumpBackward: () -> Unit,
    onJumpToNow: () -> Unit,
    onJumpForwardHalfHour: () -> Unit,
    onJumpForward: () -> Unit,
    onPageForward: () -> Unit,
    onJumpToPrimeTime: () -> Unit,
    onJumpToTomorrow: () -> Unit,
    onJumpToNextDay: () -> Unit,
    firstChipFocusRequester: FocusRequester? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    showLabel: Boolean = true
) {
    Column(modifier = Modifier.fillMaxWidth().padding(contentPadding)) {
        if (showLabel) {
            Text(text = labels.section, style = MaterialTheme.typography.labelMedium, color = OnSurfaceDim)
            Spacer(modifier = Modifier.height(8.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item { LiveGuideShortcutChip(modifier = firstChipFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier, label = labels.previousDay, onClick = onJumpToPreviousDay) }
            item { LiveGuideShortcutChip(label = labels.pageBack, onClick = onPageBackward) }
            item { LiveGuideShortcutChip(label = labels.jumpBackHalfHour, onClick = onJumpBackwardHalfHour) }
            item { LiveGuideShortcutChip(label = labels.jumpBack, onClick = onJumpBackward) }
            item { LiveGuideShortcutChip(label = labels.jumpNow, onClick = onJumpToNow) }
            item { LiveGuideShortcutChip(label = labels.jumpForwardHalfHour, onClick = onJumpForwardHalfHour) }
            item { LiveGuideShortcutChip(label = labels.jumpForward, onClick = onJumpForward) }
            item { LiveGuideShortcutChip(label = labels.pageForward, onClick = onPageForward) }
            item { LiveGuideShortcutChip(label = labels.jumpPrimeTime, onClick = onJumpToPrimeTime) }
            item { LiveGuideShortcutChip(label = labels.jumpTomorrow, onClick = onJumpToTomorrow) }
            item { LiveGuideShortcutChip(label = labels.nextDay, onClick = onJumpToNextDay) }
        }
    }
}

@Composable
fun LiveGuideDayRow(
    selectedDayStart: Long,
    labels: LiveGuideDayLabels,
    onDaySelected: (Long) -> Unit,
    contentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
    showLabel: Boolean = true
) {
    val dayFormat = remember { SimpleDateFormat("EEE d MMM", Locale.getDefault()) }
    val dayAnchors = remember(selectedDayStart) { (-1L..3L).map { shiftGuideDayStart(selectedDayStart, it) } }
    Column(modifier = Modifier.fillMaxWidth().padding(contentPadding)) {
        if (showLabel) {
            Text(text = labels.section, style = MaterialTheme.typography.labelMedium, color = OnSurfaceDim)
            Spacer(modifier = Modifier.height(8.dp))
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(dayAnchors, key = { it }) { dayStart ->
                val isSelected = dayStart == selectedDayStart
                TvClickableSurface(
                    onClick = { onDaySelected(dayStart) },
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (isSelected) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight,
                        contentColor = if (isSelected) Primary else OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(999.dp)
                        )
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = dayRelativeLabel(dayStart, labels), style = MaterialTheme.typography.labelLarge)
                        Text(text = dayFormat.format(Date(dayStart)), style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                    }
                }
            }
        }
    }
}

@Composable
fun LiveGuideViewOptionsRow(
    showScheduledOnly: Boolean,
    labels: LiveGuideViewOptionsLabels,
    onToggleScheduledOnly: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
        Text(text = labels.title, style = MaterialTheme.typography.labelMedium, color = OnSurfaceDim)
        Spacer(modifier = Modifier.height(8.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                TvClickableSurface(
                    onClick = onToggleScheduledOnly,
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (showScheduledOnly) Primary.copy(alpha = 0.18f) else SurfaceElevated,
                        focusedContainerColor = SurfaceHighlight,
                        contentColor = if (showScheduledOnly) Primary else OnSurface,
                        focusedContentColor = OnSurface
                    ),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(999.dp)),
                    border = ClickableSurfaceDefaults.border(
                        focusedBorder = Border(
                            border = BorderStroke(2.dp, FocusBorder),
                            shape = RoundedCornerShape(999.dp)
                        )
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text = if (showScheduledOnly) labels.scheduledOnlyOn else labels.scheduledOnlyOff, style = MaterialTheme.typography.labelLarge)
                        Text(text = labels.scheduledOnlyHint, style = MaterialTheme.typography.bodySmall, color = OnSurfaceDim)
                    }
                }
            }
        }
    }
}

@Composable
fun LiveGuideFavoritesRow(
    showFavoritesOnly: Boolean,
    labels: LiveGuideFavoritesLabels,
    onToggleFavoritesOnly: () -> Unit
) {
    LiveSelectionChipRow(
        title = labels.title,
        subtitle = labels.subtitle,
        chips = listOf(
            LiveSelectionChip("all", labels.all, labels.allHint),
            LiveSelectionChip("favorites", labels.favorites, labels.favoritesHint)
        ),
        selectedKey = if (showFavoritesOnly) "favorites" else "all",
        onChipSelected = { key ->
            val shouldShowFavorites = key == "favorites"
            if (shouldShowFavorites != showFavoritesOnly) onToggleFavoritesOnly()
        },
        contentPadding = PaddingValues(horizontal = 24.dp)
    )
}

@Composable
private fun dayRelativeLabel(dayStart: Long, labels: LiveGuideDayLabels): String {
    val zoneId = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zoneId) }
    return when (dayRelativeOffset(dayStart, today, zoneId)) {
        -1L -> labels.yesterday
        0L -> labels.today
        1L -> labels.tomorrow
        else -> remember { SimpleDateFormat("EEE", Locale.getDefault()) }.format(Date(dayStart))
    }
}
