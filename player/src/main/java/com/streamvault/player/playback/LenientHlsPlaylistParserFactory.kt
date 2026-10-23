@file:androidx.media3.common.util.UnstableApi

package com.streamvault.player.playback

import androidx.media3.common.util.UnstableApi
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
internal class LenientHlsPlaylistParserFactory : HlsPlaylistParserFactory {
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
            delegate.parse(uri, ByteArrayInputStream(compatibleManifest.toByteArray(Charsets.UTF_8)))
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
