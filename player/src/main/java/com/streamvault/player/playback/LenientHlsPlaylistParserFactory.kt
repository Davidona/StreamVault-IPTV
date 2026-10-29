@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.util.UnstableApi
import androidx.media3.common.C
import androidx.media3.exoplayer.hls.playlist.DefaultHlsPlaylistParserFactory
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParserFactory
import androidx.media3.exoplayer.upstream.ParsingLoadable
import java.io.ByteArrayInputStream

/**
 * Works around HLS master playlists that omit the required NAME attribute on EXT-X-MEDIA.
 * Media3 rejects those playlists, while other HLS clients commonly accept them.
 */
internal class LenientHlsPlaylistParserFactory(
    private val stabilityTargetOffsetMs: Long? = null
) : HlsPlaylistParserFactory {
    private val delegateFactory = DefaultHlsPlaylistParserFactory()

    override fun createPlaylistParser(): ParsingLoadable.Parser<HlsPlaylist> =
        wrap(delegateFactory.createPlaylistParser())

    override fun createPlaylistParser(
        multivariantPlaylist: HlsMultivariantPlaylist,
        previousMediaPlaylist: HlsMediaPlaylist?
    ): ParsingLoadable.Parser<HlsPlaylist> =
        wrap(delegateFactory.createPlaylistParser(multivariantPlaylist, previousMediaPlaylist))

    private fun wrap(delegate: ParsingLoadable.Parser<HlsPlaylist>) =
        ParsingLoadable.Parser<HlsPlaylist> { uri, inputStream ->
            val manifest = inputStream.readBytes().toString(Charsets.UTF_8)
            val compatibleManifest = addMissingMediaNames(manifest)
            val playlist = delegate.parse(uri, ByteArrayInputStream(compatibleManifest.toByteArray(Charsets.UTF_8)))
            if (playlist is HlsMediaPlaylist) applyStabilityTarget(playlist) else playlist
        }

    private fun applyStabilityTarget(playlist: HlsMediaPlaylist): HlsMediaPlaylist {
        val targetMs = stabilityTargetOffsetMs ?: return playlist
        if (targetMs <= 0 || targetMs > Long.MAX_VALUE / 1_000L) return playlist
        val targetUs = targetMs * 1_000L
        // Preserve finite/event intent, explicit provider starts and short windows. A
        // full target-duration segment must remain before the requested start position.
        if (playlist.hasEndTag || playlist.playlistType != HlsMediaPlaylist.PLAYLIST_TYPE_UNKNOWN ||
            playlist.startOffsetUs != C.TIME_UNSET || playlist.targetDurationUs <= 0L ||
            playlist.durationUs - targetUs < playlist.targetDurationUs
        ) return playlist
        val control = playlist.serverControl
        val sourceTargetUs = when {
            control.partHoldBackUs != C.TIME_UNSET && playlist.partTargetDurationUs != C.TIME_UNSET ->
                control.partHoldBackUs
            control.holdBackUs != C.TIME_UNSET -> control.holdBackUs
            else -> 3L * playlist.targetDurationUs
        }
        if (sourceTargetUs >= targetUs) return playlist

        // Client-side equivalent of EXT-X-START:TIME-OFFSET=-target. Retaining the
        // original server control leaves Media3's speed-adjustment gate untouched.
        // Copy every unrelated field, including keys/maps, parts and interstitials.
        return HlsMediaPlaylist(
            playlist.playlistType, playlist.baseUri, playlist.tags, -targetUs, false,
            playlist.startTimeUs, playlist.hasDiscontinuitySequence, playlist.discontinuitySequence,
            playlist.mediaSequence, playlist.version, playlist.targetDurationUs, playlist.partTargetDurationUs,
            playlist.hasIndependentSegments, playlist.hasEndTag, playlist.hasProgramDateTime,
            playlist.protectionSchemes, playlist.segments, playlist.trailingParts, playlist.serverControl,
            playlist.renditionReports, playlist.interstitials, playlist.lastSeenInitSegment
        )
    }

    private fun addMissingMediaNames(manifest: String): String {
        var fallbackNameIndex = 0
        return manifest.lineSequence().joinToString("\n") { line ->
            val trimmedLine = line.trimStart()
            if (!trimmedLine.startsWith(MEDIA_TAG, ignoreCase = true)) {
                line
            } else {
                val attributes = trimmedLine.substringAfter(':', missingDelimiterValue = "")
                if (NAME_ATTRIBUTE.containsMatchIn(attributes)) {
                    line
                } else {
                    fallbackNameIndex += 1
                    "${line.trimEnd()},NAME=\"streamvault-rendition-$fallbackNameIndex\""
                }
            }
        }
    }

    private companion object {
        const val MEDIA_TAG = "#EXT-X-MEDIA:"
        val NAME_ATTRIBUTE = Regex("(?:^|,)\\s*NAME\\s*=", RegexOption.IGNORE_CASE)
    }
}
