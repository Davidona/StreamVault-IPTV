package com.streamvault.domain.model.stremio

import com.google.common.truth.Truth.assertThat
import java.time.Instant
import org.junit.Test

class StremioCompatibilityTest {
    @Test fun customTypesAndLibraryKeysRoundTripWithoutLosingIds() {
        val type = StremioMediaType.fromTypeName("anime")
        val id = "provider:one/part 2?x=3,4"
        assertThat(type.typeName).isEqualTo("anime")
        assertThat(decodeStremioLibraryKey(stremioLibraryKey(type, id))).isEqualTo(type to id)
        assertThat(decodeStremioLibraryKey("__library:not base64!")).isNull()
        assertThat(decodeStremioLibraryKey(stremioWatchedKey(type, id))).isNull()
        assertThat(StremioMediaType.fromTypeName("\n")).isEqualTo(StremioMediaType.UNKNOWN)
    }

    @Test fun externalPlaybackAcceptsMediaLinksAndRejectsExecutableOrLocalUrls() {
        assertThat(StremioStream(ytId = "abcdefghijk").externalLaunchUrl())
            .isEqualTo("https://www.youtube.com/watch?v=abcdefghijk")
        assertThat(StremioStream(externalUrl = "javascript:alert(1)").externalLaunchUrl()).isNull()
        assertThat(StremioStream(externalUrl = "file:///private/data").externalLaunchUrl()).isNull()
        assertThat(StremioStream(url = "rtsp://media.example/live").externalLaunchUrl())
            .isEqualTo("rtsp://media.example/live")
        val stream = StremioStream(externalUrl = "https://example.test/movie", androidTvUrl = "netflix://movie/123")
        assertThat(stream.externalLaunchUrl(true)).isEqualTo("netflix://movie/123")
        assertThat(stream.externalLaunchUrl(false)).isEqualTo("https://example.test/movie")
    }

    @Test fun playbackEventsTrackTransitionsAndSeeksWithoutUploadingEverySample() {
        val tracker = StremioPlaybackEventTracker()
        val first = StremioPlaybackIdentity(StremioMediaType.MOVIE, "tt1", "a")
        val second = first.copy(id = "tt2")
        fun sample(id: StremioPlaybackIdentity?, position: Long, playing: Boolean, now: Long) =
            tracker.update(id, position, 100_000, playing, true, false, now)
        assertThat(sample(first, 0, true, 0).map { it.action }).containsExactly("start")
        assertThat(sample(first, 500, true, 500)).isEmpty()
        assertThat(sample(first, 600, false, 600).map { it.action }).containsExactly("pause")
        assertThat(sample(first, 10_000, false, 700).map { it.action }).containsExactly("pause")
        assertThat(sample(first, 10_000, true, 800).map { it.action }).containsExactly("start")
        assertThat(sample(second, 0, true, 900).map { it.action }).containsExactly("stop", "start").inOrder()
        assertThat(tracker.close().single().identity).isEqualTo(second)
        assertThat(tracker.close()).isEmpty()
    }

    @Test fun bufferingAndPlaybackSpeedDoNotGenerateFalsePauseOrSeekEvents() {
        val tracker = StremioPlaybackEventTracker()
        val id = StremioPlaybackIdentity(StremioMediaType.SERIES, "episode", "a")
        tracker.update(id, 0, 100_000, true, true, false, 0)
        assertThat(tracker.update(id, 4_000, 100_000, true, true, false, 500, 8f)).isEmpty()
        assertThat(tracker.update(id, 4_000, 100_000, false, false, false, 1_000)).isEmpty()
        assertThat(tracker.update(id, 4_500, 100_000, true, true, false, 1_500)).isEmpty()
        val stop = tracker.update(id, 100_000, 100_000, false, false, true, 2_000).single()
        assertThat(stop.action).isEqualTo("stop")
        assertThat(stop.positionMs).isEqualTo(100_000)
    }

    @Test fun episodeContinuityUsesExactIdsAndStopsBeforeUnreleasedVideos() {
        val now = Instant.parse("2026-11-01T00:00:00Z")
        val meta = StremioMetaDetail("series", StremioMediaType.SERIES, "Series", videos = listOf(
            StremioVideo("custom:2", season = 1, episode = 2),
            StremioVideo("custom:1", season = 1, episode = 1),
            StremioVideo("custom:3", season = 1, episode = 3, released = "2027-01-01T00:00:00Z")
        ))
        assertThat(meta.nextVideoAfter("custom:1", now)?.id).isEqualTo("custom:2")
        assertThat(meta.nextVideoAfter("custom:2", now)).isNull()
        assertThat(meta.nextVideoAfter("invented-id", now)).isNull()
        assertThat(meta.copy(hasScheduledVideos = true).nextVideoAfter("custom:1", now)).isNull()
    }
}
