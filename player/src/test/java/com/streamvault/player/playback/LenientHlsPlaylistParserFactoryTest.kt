@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LenientHlsPlaylistParserFactoryTest {
    @Test
    fun `manual floor is added only when source target and window leave segment safety margin`() {
        val twelveSecondPlaylist = mediaPlaylist(
            targetDurationSeconds = 2,
            segmentCount = 6,
            serverControl = "CAN-BLOCK-RELOAD=YES,HOLD-BACK=6.0"
        )

        val playlist = parse(twelveSecondPlaylist, stabilityTargetOffsetMs = 10_000L)

        assertThat(playlist.startOffsetUs).isEqualTo(2_000_000L)
        assertThat(playlist.hasPositiveStartOffset).isFalse()
        assertThat(playlist.preciseStart).isFalse()
        assertThat(playlist.serverControl.holdBackUs).isEqualTo(6_000_000L)
        assertThat(playlist.durationUs).isEqualTo(12_000_000L)
    }

    @Test
    fun `short published window remains unchanged`() {
        val playlist = parse(
            mediaPlaylist(targetDurationSeconds = 2, segmentCount = 3, serverControl = "HOLD-BACK=6.0"),
            stabilityTargetOffsetMs = 10_000L
        )

        assertThat(playlist.startOffsetUs).isEqualTo(C.TIME_UNSET)
        assertThat(playlist.serverControl.holdBackUs).isEqualTo(6_000_000L)
    }

    @Test
    fun `larger normal three target duration reserve is not lowered`() {
        val playlist = parse(
            mediaPlaylist(targetDurationSeconds = 6, segmentCount = 6),
            stabilityTargetOffsetMs = 10_000L
        )

        assertThat(playlist.startOffsetUs).isEqualTo(C.TIME_UNSET)
        assertThat(playlist.durationUs).isEqualTo(36_000_000L)
    }

    @Test
    fun `larger explicit hold back is not lowered`() {
        val playlist = parse(
            mediaPlaylist(targetDurationSeconds = 2, segmentCount = 12, serverControl = "HOLD-BACK=18.0"),
            stabilityTargetOffsetMs = 10_000L
        )

        assertThat(playlist.startOffsetUs).isEqualTo(C.TIME_UNSET)
        assertThat(playlist.serverControl.holdBackUs).isEqualTo(18_000_000L)
    }

    @Test
    fun `explicit provider start offsets are preserved`() {
        for (offset in listOf("5.0", "-5.0")) {
            val input = mediaPlaylist(
                targetDurationSeconds = 2,
                segmentCount = 12,
                startOffsetSeconds = offset,
                serverControl = "HOLD-BACK=6.0"
            )
            val parsedProviderStart = parse(input, stabilityTargetOffsetMs = null).startOffsetUs
            val playlist = parse(
                input,
                stabilityTargetOffsetMs = 10_000L
            )

            assertThat(playlist.startOffsetUs).isEqualTo(parsedProviderStart)
        }
    }

    @Test
    fun `finite vod and event playlists remain unchanged`() {
        val vod = parse(
            mediaPlaylist(
                targetDurationSeconds = 2,
                segmentCount = 12,
                playlistType = "VOD",
                endList = true
            ),
            stabilityTargetOffsetMs = 10_000L
        )
        val event = parse(
            mediaPlaylist(targetDurationSeconds = 2, segmentCount = 12, playlistType = "EVENT"),
            stabilityTargetOffsetMs = 10_000L
        )

        assertThat(vod.startOffsetUs).isEqualTo(C.TIME_UNSET)
        assertThat(vod.hasEndTag).isTrue()
        assertThat(event.startOffsetUs).isEqualTo(C.TIME_UNSET)
        assertThat(event.playlistType).isEqualTo(HlsMediaPlaylist.PLAYLIST_TYPE_EVENT)
    }

    @Test
    fun `plain live playlist gets floor while absent server control remains absent`() {
        val playlist = parse(
            mediaPlaylist(targetDurationSeconds = 2, segmentCount = 6),
            stabilityTargetOffsetMs = 10_000L
        )

        assertThat(playlist.startOffsetUs).isEqualTo(2_000_000L)
        assertThat(playlist.serverControl.holdBackUs).isEqualTo(C.TIME_UNSET)
    }

    @Test
    fun `part hold back remains intact and controls the source target when valid`() {
        val playlist = parse(
            mediaPlaylist(
                targetDurationSeconds = 2,
                segmentCount = 6,
                partTargetSeconds = 0.5,
                serverControl = "HOLD-BACK=18.0,PART-HOLD-BACK=4.0"
            ),
            stabilityTargetOffsetMs = 10_000L
        )

        assertThat(playlist.startOffsetUs).isEqualTo(2_000_000L)
        assertThat(playlist.serverControl.holdBackUs).isEqualTo(18_000_000L)
        assertThat(playlist.serverControl.partHoldBackUs).isEqualTo(4_000_000L)
    }

    @Test
    fun `floor clone preserves playlist timeline and segment metadata`() {
        val originalText = mediaPlaylist(
            targetDurationSeconds = 2,
            segmentCount = 6,
            serverControl = "CAN-BLOCK-RELOAD=YES,HOLD-BACK=6.0",
            discontinuitySequence = 7,
            mediaSequence = 44
        )
        val original = parse(originalText, stabilityTargetOffsetMs = null)
        val floored = parse(originalText, stabilityTargetOffsetMs = 10_000L)

        assertThat(floored.startOffsetUs).isEqualTo(2_000_000L)
        assertThat(floored.baseUri).isEqualTo(original.baseUri)
        assertThat(floored.tags).containsExactlyElementsIn(original.tags)
        assertThat(floored.mediaSequence).isEqualTo(original.mediaSequence)
        assertThat(floored.discontinuitySequence).isEqualTo(original.discontinuitySequence)
        assertThat(floored.hasDiscontinuitySequence).isEqualTo(original.hasDiscontinuitySequence)
        assertThat(floored.durationUs).isEqualTo(original.durationUs)
        assertThat(floored.targetDurationUs).isEqualTo(original.targetDurationUs)
        assertThat(floored.serverControl.holdBackUs).isEqualTo(original.serverControl.holdBackUs)
        assertThat(floored.segments.map { it.url }).containsExactlyElementsIn(original.segments.map { it.url })
        assertThat(floored.segments.map { it.durationUs })
            .containsExactlyElementsIn(original.segments.map { it.durationUs })
        assertThat(floored.segments.map { it.relativeStartTimeUs })
            .containsExactlyElementsIn(original.segments.map { it.relativeStartTimeUs })
    }

    private fun parse(text: String, stabilityTargetOffsetMs: Long?): HlsMediaPlaylist {
        val parser = LenientHlsPlaylistParserFactory(stabilityTargetOffsetMs).createPlaylistParser()
        return parser.parse(
            Uri.parse("https://example.test/live.m3u8"),
            ByteArrayInputStream(text.toByteArray(Charsets.UTF_8))
        ) as HlsMediaPlaylist
    }

    private fun mediaPlaylist(
        targetDurationSeconds: Int,
        segmentCount: Int,
        serverControl: String? = null,
        playlistType: String? = null,
        startOffsetSeconds: String? = null,
        partTargetSeconds: Double? = null,
        endList: Boolean = false,
        discontinuitySequence: Int = 0,
        mediaSequence: Int = 0
    ): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:6")
        appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
        appendLine("#EXT-X-MEDIA-SEQUENCE:$mediaSequence")
        if (discontinuitySequence != 0) appendLine("#EXT-X-DISCONTINUITY-SEQUENCE:$discontinuitySequence")
        playlistType?.let { appendLine("#EXT-X-PLAYLIST-TYPE:$it") }
        startOffsetSeconds?.let { appendLine("#EXT-X-START:TIME-OFFSET=$it,PRECISE=YES") }
        partTargetSeconds?.let { appendLine("#EXT-X-PART-INF:PART-TARGET=$it") }
        serverControl?.let { appendLine("#EXT-X-SERVER-CONTROL:$it") }
        repeat(segmentCount) { index ->
            appendLine("#EXTINF:$targetDurationSeconds.0,")
            appendLine("segment${mediaSequence + index}.ts")
        }
        if (endList) appendLine("#EXT-X-ENDLIST")
    }
}
