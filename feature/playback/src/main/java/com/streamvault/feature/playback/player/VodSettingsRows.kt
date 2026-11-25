package com.streamvault.feature.playback.player

import androidx.annotation.StringRes
import com.streamvault.feature.playback.R

/** One entry of the settings panel's main page. [key] is stable, so the list can be scrolled to a row by key. */
internal enum class VodSettingsRow(val key: String) {
    QUALITY("quality"),
    SPEED("speed"),
    STOP_TIMER("stop_timer"),
    IDLE_TIMER("idle_timer"),
    ASPECT("aspect"),
    MUTE("mute"),
    PICTURE_IN_PICTURE("pip"),
    EXTERNAL_PLAYER("external"),
    SPLIT_SCREEN("split_screen"),
    CAST("cast"),
    AV_SYNC("av_sync"),
}

/** The rows that open an in-frame page instead of an action or a separate dialog. */
internal enum class VodSettingsPage(val row: VodSettingsRow) {
    SPEED(VodSettingsRow.SPEED),
    STOP_TIMER(VodSettingsRow.STOP_TIMER),
    IDLE_TIMER(VodSettingsRow.IDLE_TIMER),
    ASPECT(VodSettingsRow.ASPECT),
}

internal fun vodSettingsPageFor(row: VodSettingsRow): VodSettingsPage? =
    VodSettingsPage.entries.firstOrNull { it.row == row }

/**
 * Same order as the old drawer, without audio, subtitles and episodes: those have their own buttons on the overlay.
 * Rows the title cannot use are left out, so there are no disabled rows.
 */
internal fun buildVodSettingsRows(state: VodOverlayState): List<VodSettingsRow> = buildList {
    if (state.showVideoQualityAction) add(VodSettingsRow.QUALITY)
    add(VodSettingsRow.SPEED)
    add(VodSettingsRow.STOP_TIMER)
    add(VodSettingsRow.IDLE_TIMER)
    add(VodSettingsRow.ASPECT)
    add(VodSettingsRow.MUTE)
    add(VodSettingsRow.PICTURE_IN_PICTURE)
    if (state.showExternalPlayerAction) add(VodSettingsRow.EXTERNAL_PLAYER)
    add(VodSettingsRow.SPLIT_SCREEN)
    add(VodSettingsRow.CAST)
    if (state.showAudioVideoSyncAction) add(VodSettingsRow.AV_SYNC)
}

/** Row to focus when the main page opens: the row that opened the page just closed, else the first one. */
internal fun vodSettingsFocusRow(rows: List<VodSettingsRow>, returnRow: VodSettingsRow?): VodSettingsRow? =
    returnRow?.takeIf { it in rows } ?: rows.firstOrNull()

/** Option shown on a sub-page, in the order the user sees it. */
internal fun vodSettingsTimerOptions(): List<Int> = PlayerViewModel.PLAYBACK_TIMER_PRESETS_MINUTES.sorted()

internal fun vodSettingsSpeedOptions(): List<Float> = PlayerViewModel.PLAYBACK_SPEED_OPTIONS

@StringRes
internal fun AspectRatio.labelRes(): Int = when (this) {
    AspectRatio.FIT -> R.string.player_aspect_fit
    AspectRatio.FILL -> R.string.player_aspect_stretch
    AspectRatio.ZOOM -> R.string.player_aspect_zoom
}

private const val ROWS_SHOWN_ABOVE_TARGET = 3

/**
 * First row to compose for a page whose focus target is at [targetIndex]. Starting at the target would hide the rows
 * above it behind the edge fade, so the page starts a few rows earlier and a short list opens at its top.
 */
internal fun vodSettingsFirstVisibleIndex(targetIndex: Int): Int =
    (targetIndex - ROWS_SHOWN_ABOVE_TARGET).coerceAtLeast(0)
