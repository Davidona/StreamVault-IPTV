package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlayerTrack
import com.streamvault.player.TrackType
import org.junit.Test

class VodSubtitleRowsTest {
    private fun track(id: String, selected: Boolean = false) =
        PlayerTrack(id, "Subtitle $id", null, TrackType.TEXT, selected)

    private fun option(key: String) = StremioPlayerSubtitleOption(key, "https://example.test/$key", "en", "Add-on $key")

    @Test
    fun mainPageIsOffThenTracksThenActions() {
        val rows = buildVodSubtitleRows(listOf(track("1"), track("2")), StremioSubtitlePickerState())

        assertThat(rows.map { it.key }).containsExactly(
            "off", "track:1", "track:2", "online", "device", "appearance",
        ).inOrder()
    }

    @Test
    fun stremioBlockIsHiddenWhenDisabled() {
        val rows = buildVodSubtitleRows(emptyList(), StremioSubtitlePickerState(enabled = false, loading = true))

        assertThat(rows.map { it.key }).containsExactly("off", "online", "device", "appearance").inOrder()
    }

    @Test
    fun stremioLoadingShowsHeadingAndLoadingOnly() {
        val rows = buildVodSubtitleRows(emptyList(), StremioSubtitlePickerState(enabled = true, loading = true))

        assertThat(rows.map { it.key }).containsExactly(
            "off", "stremio:heading", "stremio:loading", "online", "device", "appearance",
        ).inOrder()
    }

    @Test
    fun stremioFailureOffersRetryWithoutEmptyHint() {
        val rows = buildVodSubtitleRows(emptyList(), StremioSubtitlePickerState(enabled = true, failed = true))

        assertThat(rows.map { it.key }).contains("stremio:retry")
        assertThat(rows.map { it.key }).doesNotContain("stremio:empty")
    }

    @Test
    fun stremioWithNoOptionsShowsEmptyHint() {
        val rows = buildVodSubtitleRows(emptyList(), StremioSubtitlePickerState(enabled = true))

        assertThat(rows.map { it.key }).contains("stremio:empty")
    }

    @Test
    fun stremioOptionsFollowTheTracksAndPrecedeTheActions() {
        val rows = buildVodSubtitleRows(
            listOf(track("1")),
            StremioSubtitlePickerState(enabled = true, options = listOf(option("a"), option("b"))),
        )

        assertThat(rows.map { it.key }).containsExactly(
            "off", "track:1", "stremio:heading", "stremio:option:a", "stremio:option:b",
            "online", "device", "appearance",
        ).inOrder()
    }

    @Test
    fun selectedKeyIsTheSelectedTrackOrOff() {
        assertThat(vodSubtitleSelectedKey(listOf(track("1"), track("2", selected = true)))).isEqualTo("track:2")
        assertThat(vodSubtitleSelectedKey(listOf(track("1")))).isEqualTo("off")
        assertThat(vodSubtitleSelectedKey(emptyList())).isEqualTo("off")
    }
}
