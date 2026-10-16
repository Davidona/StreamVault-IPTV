package com.streamvault.feature.stremio.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.navigation.StremioSubtitlePlaybackSource
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaPreview
import com.streamvault.domain.model.stremio.StremioStream
import com.streamvault.domain.model.stremio.StremioStreamBehaviorHints
import com.streamvault.domain.model.stremio.StremioProxyHeaders
import com.streamvault.domain.model.stremio.StremioSubtitle
import org.junit.Test

class StremioPlaybackHandoffTest {
    @Test
    fun `metadata failure does not prevent a resolved torrent from reaching the player`() {
        val state = StremioItemDetailState(
            preview = StremioMetaPreview("catalog:item:7", StremioMediaType.SERIES, "Catalog title"),
            catalogOriginAddonId = 11L,
            catalogOriginKey = "catalog-origin",
            metadataLoading = false,
            metadataError = "Metadata request failed"
        )

        val handoff = requireNotNull(buildHandoff(
            state, "stream-origin", StremioStream(infoHash = "a".repeat(40)), "catalog:item:7"
        ))

        assertThat(java.net.URLDecoder.decode(handoff.streamUrl, "UTF-8"))
            .startsWith("magnet:?xt=urn:btih:" + "a".repeat(40))
        assertThat(handoff.reference.exactVideoId).isEqualTo("catalog:item:7")
        assertThat(handoff.reference.metaOriginKey).isNull()
    }

    @Test
    fun `player handoff retains scoped headers and inline subtitles`() {
        val subtitles = listOf(
            StremioSubtitle("https://subs.example/en.vtt", "eng", "english"),
            StremioSubtitle("https://subs.example/es.vtt", "spa", "spanish")
        )
        val state = StremioItemDetailState(
            preview = StremioMetaPreview(
                id = "tt123",
                type = StremioMediaType.MOVIE,
                name = "Example Movie"
            ),
            catalogOriginAddonId = 11L,
            catalogOriginKey = "catalog-origin"
        )
        val stream = StremioStream(
            url = "https://media.example/movie.mp4",
            subtitles = subtitles,
            behaviorHints = StremioStreamBehaviorHints(
                proxyHeaders = StremioProxyHeaders(request = mapOf("Authorization" to "Bearer token"))
            )
        )

        val handoff = requireNotNull(buildHandoff(state, "stream-origin", stream, "tt123"))

        assertThat(handoff.mediaRequestHeaders).containsEntry("Authorization", "Bearer token")
        assertThat(handoff.inlineSubtitles).containsExactly(
            StremioSubtitlePlaybackSource("https://subs.example/en.vtt", "eng", "english"),
            StremioSubtitlePlaybackSource("https://subs.example/es.vtt", "spa", "spanish")
        ).inOrder()
        assertThat(handoff.reference.exactVideoId).isEqualTo("tt123")
        assertThat(handoff.reference.streamOriginKey).isEqualTo("stream-origin")
    }

    @Test
    fun `series player handoff preserves the exact episode identity`() {
        val state = StremioItemDetailState(
            preview = StremioMetaPreview(
                id = "series-id",
                type = StremioMediaType.SERIES,
                name = "Example Series"
            ),
            catalogOriginAddonId = 11L,
            catalogOriginKey = "catalog-origin"
        )

        val handoff = requireNotNull(buildHandoff(
            state,
            "stream-origin",
            StremioStream(url = "https://media.example/episode.mp4"),
            "episode:vendor-id:season-02:episode-04"
        ))

        assertThat(handoff.reference.exactVideoId).isEqualTo("episode:vendor-id:season-02:episode-04")
        assertThat(handoff.reference.parentSeriesId).isEqualTo("series-id")
    }
}
