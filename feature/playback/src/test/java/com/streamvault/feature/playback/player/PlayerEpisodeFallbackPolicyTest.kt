package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import org.junit.Test

class PlayerEpisodeFallbackPolicyTest {
    private val sourceEpisode = Episode(101, "Drama S01E02 1080p HEVC", 2, 1,
        durationSeconds = 180, seriesId = 10, providerId = 7)
    private val candidateEpisode = sourceEpisode.copy(id = 201, seriesId = 20,
        title = "Drama S01E02 720p AVC")
    private fun series(id: Long, name: String, episodes: List<Episode>) = Series(id, name,
        providerId = 7, tmdbId = 5000, seasons = listOf(Season(1, episodes = episodes)))
    private val original = series(10, "Drama 1080p", listOf(sourceEpisode))
    private val candidate = series(20, "Drama 720p", listOf(candidateEpisode))
    private fun source() = EpisodeFallbackSource(original, sourceEpisode,
        StreamInfo("http://fixture.invalid/101.mp4", streamType = StreamType.PROGRESSIVE,
            playbackContentKind = PlaybackContentKind.ON_DEMAND), "video/hevc", 1080, 60000, 180000)

    @Test fun matchingExplicitIdentitySelectsAvc() {
        assertThat(selectEpisodeFallbackSeries(source(), listOf(original, candidate))).isEqualTo(candidate)
        assertThat(verifyEpisodeFallback(source(), candidate)).isEqualTo(candidateEpisode)
    }

    @Test fun ambiguousOrDerivedIdentityRejected() {
        for (title in listOf("Drama 720p AVC", "Drama S01E03 720p AVC", "Drama S00E02 720p AVC",
            "Drama S01E02-E03 720p AVC", "Drama S01E02E03 720p AVC", "Drama S01E02 E03 720p AVC",
            "Drama S01E02-S01E03 720p AVC", "Other S01E02 720p AVC")) {
            assertThat(verifyEpisodeFallback(source(), candidate.copy(seasons = listOf(
                Season(1, episodes = listOf(candidateEpisode.copy(title = title))))))).isNull()
        }
        assertThat(verifyEpisodeFallback(source(), candidate.copy(seasons = listOf(
            Season(1, episodes = listOf(candidateEpisode, candidateEpisode.copy(id = 202))))))).isNull()
        assertThat(verifyEpisodeFallback(source().copy(series = original.copy(seasons = listOf(
            Season(1, episodes = listOf(sourceEpisode, sourceEpisode.copy(id = 102)))))), candidate)).isNull()
        assertThat(verifyEpisodeFallback(source(), candidate.copy(seasons = listOf(
            Season(1, episodes = listOf(candidateEpisode.copy(episodeNumber = 3))))))).isNull()
    }

    @Test fun rawMembershipAndTmdbRequired() {
        for (wrong in listOf(candidate.copy(providerId = 8), candidate.copy(tmdbId = null),
            candidate.copy(tmdbId = 6000), candidate.copy(name = "Other 720p"))) {
            assertThat(selectEpisodeFallbackSeries(source(), listOf(original, wrong))).isNull()
            assertThat(verifyEpisodeFallback(source(), wrong)).isNull()
        }
        assertThat(selectEpisodeFallbackSeries(source(), listOf(candidate))).isNull()
        assertThat(verifyEpisodeFallback(source().copy(series = original.copy(tmdbId = null)), candidate)).isNull()
        assertThat(verifyEpisodeFallback(source(), candidate.copy(seasons = listOf(
            Season(1, episodes = listOf(candidateEpisode.copy(providerId = 8))))))).isNull()
    }

    @Test fun measuredSourceFormatWins() {
        assertThat(selectEpisodeFallbackSeries(source().copy(codecHint = "video/av01"), listOf(original, candidate))).isNull()
        assertThat(selectEpisodeFallbackSeries(source().copy(codecHint = "video/avc"), listOf(original, candidate))).isNull()
        assertThat(selectEpisodeFallbackSeries(source().copy(heightHint = 480), listOf(original, candidate))).isNull()
        assertThat(verifyEpisodeFallback(source().copy(codecHint = null, heightHint = null), candidate)).isNotNull()
        for (title in listOf("Drama S01E02 720p", "Drama S01E02 720p AVC HEVC",
            "Drama S01E02 720p 1080p AVC", "Drama S01E02 2160p AVC")) {
            assertThat(verifyEpisodeFallback(source(), candidate.copy(seasons = listOf(
                Season(1, episodes = listOf(candidateEpisode.copy(title = title))))))).isNull()
        }
    }

    @Test fun candidateOrderingIsDeterministic() {
        val sameHeight = candidate.copy(id = 19)
        val lower = candidate.copy(id = 21, name = "Drama 480p")
        val higher = candidate.copy(id = 22, name = "Drama 2160p")
        assertThat(selectEpisodeFallbackSeries(source(), listOf(original, candidate, lower, higher, sameHeight))).isEqualTo(sameHeight)
    }

    @Test fun codecOnlyInEpisodeIsVerifiedAfterHydration() {
        assertThat(selectEpisodeFallbackSeries(source(), listOf(original, candidate.copy(seasons = emptyList())))).isNotNull()
        assertThat(verifyEpisodeFallback(source(), candidate.copy(seasons = emptyList()))).isNull()
        assertThat(verifyEpisodeFallback(source(), candidate)).isNotNull()
    }

    @Test fun invalidSourceIdentityRejectedBeforeCandidateLookup() {
        assertThat(selectEpisodeFallbackSeries(source().copy(episode = sourceEpisode.copy(title = "Drama HEVC")), listOf(original, candidate))).isNull()
        assertThat(selectEpisodeFallbackSeries(source().copy(series = original.copy(seasons = listOf(
            Season(1, episodes = listOf(sourceEpisode, sourceEpisode.copy(id = 102)))))), listOf(original, candidate))).isNull()
        for (shorthand in listOf("S01E02/03", "S01E02 & 03", "S01E02 + 03", "S01E02,03")) {
            val episode = sourceEpisode.copy(title = "Drama $shorthand 1080p HEVC")
            val source = source().copy(episode = episode, series = original.copy(seasons = listOf(Season(1, episodes = listOf(episode)))))
            val details = candidate.copy(seasons = listOf(Season(1, episodes = listOf(candidateEpisode.copy(title = "Drama $shorthand 720p AVC")))))
            assertThat(selectEpisodeFallbackSeries(source, listOf(original, candidate))).isNull()
            assertThat(verifyEpisodeFallback(source, details)).isNull()
        }
    }

    @Test fun durationAndEndGuardBoundaries() {
        for (seconds in listOf(178, 182)) assertThat(verifyEpisodeFallback(source(), candidate.copy(seasons = listOf(
            Season(1, episodes = listOf(candidateEpisode.copy(durationSeconds = seconds))))))).isNotNull()
        for (seconds in listOf(0, 177, 183)) assertThat(verifyEpisodeFallback(source(), candidate.copy(seasons = listOf(
            Season(1, episodes = listOf(candidateEpisode.copy(durationSeconds = seconds))))))).isNull()
        assertThat(verifyEpisodeFallback(source().copy(positionMs = 165000), candidate)).isNotNull()
        for (position in listOf(-1L, 165001L)) assertThat(verifyEpisodeFallback(source().copy(positionMs = position), candidate)).isNull()
        assertThat(verifyEpisodeFallback(source().copy(durationMs = 0), candidate)).isNull()
        for (kind in listOf(PlaybackContentKind.LIVE, PlaybackContentKind.CATCH_UP, PlaybackContentKind.UNKNOWN)) {
            assertThat(selectEpisodeFallbackSeries(source().let { it.copy(streamInfo = it.streamInfo.copy(playbackContentKind = kind)) }, listOf(original, candidate))).isNull()
        }
        for (type in listOf(StreamType.HLS, StreamType.DASH, StreamType.MPEG_TS, StreamType.RTSP)) {
            assertThat(selectEpisodeFallbackSeries(source().let { it.copy(streamInfo = it.streamInfo.copy(streamType = type)) }, listOf(original, candidate))).isNull()
        }
    }
}
