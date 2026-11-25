package com.streamvault.feature.playback.player

import com.streamvault.player.PlayerTrack

/** One entry of the subtitle panel's main page. [key] is stable, so the list can be scrolled to a row by key. */
internal sealed interface VodSubtitleRow {
    val key: String

    data object Off : VodSubtitleRow {
        override val key = "off"
    }

    data class Track(val track: PlayerTrack) : VodSubtitleRow {
        override val key = "track:${track.id}"
    }

    data object StremioHeading : VodSubtitleRow {
        override val key = "stremio:heading"
    }

    data object StremioLoading : VodSubtitleRow {
        override val key = "stremio:loading"
    }

    data object StremioRetry : VodSubtitleRow {
        override val key = "stremio:retry"
    }

    data object StremioEmpty : VodSubtitleRow {
        override val key = "stremio:empty"
    }

    data class StremioOption(val option: StremioPlayerSubtitleOption) : VodSubtitleRow {
        override val key = "stremio:option:${option.key}"
    }

    data object SearchOnline : VodSubtitleRow {
        override val key = "online"
    }

    data object LoadFromDevice : VodSubtitleRow {
        override val key = "device"
    }

    data object Appearance : VodSubtitleRow {
        override val key = "appearance"
    }
}

/** Off and the tracks first, so the check is visible on open, then the add-on block, then the actions. */
internal fun buildVodSubtitleRows(
    tracks: List<PlayerTrack>,
    stremio: StremioSubtitlePickerState,
): List<VodSubtitleRow> = buildList {
    add(VodSubtitleRow.Off)
    tracks.forEach { add(VodSubtitleRow.Track(it)) }
    if (stremio.enabled) {
        add(VodSubtitleRow.StremioHeading)
        if (stremio.loading) add(VodSubtitleRow.StremioLoading)
        if (stremio.failed) add(VodSubtitleRow.StremioRetry)
        if (!stremio.loading && !stremio.failed && stremio.options.isEmpty()) add(VodSubtitleRow.StremioEmpty)
        stremio.options.forEach { add(VodSubtitleRow.StremioOption(it)) }
    }
    add(VodSubtitleRow.SearchOnline)
    add(VodSubtitleRow.LoadFromDevice)
    add(VodSubtitleRow.Appearance)
}

/** The selected track, or Off when none is selected. */
internal fun vodSubtitleSelectedKey(tracks: List<PlayerTrack>): String =
    tracks.firstOrNull { it.isSelected }?.let { VodSubtitleRow.Track(it).key } ?: VodSubtitleRow.Off.key
