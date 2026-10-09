package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Episode
import com.streamvault.domain.model.Series
import org.junit.Test

class PlayerEpisodeSourceStateTest {
    @Test fun failedAttemptIsNotRefunded() {
        val state = PlayerEpisodeSourceState()
        val initial = state.reset(1)
        val reserved = state.reserveFallback(initial, 60000, 180000)!!
        assertThat(state.isCurrent(initial)).isFalse()
        assertThat(state.reserveFallback(reserved, 0, 180000)).isNull()
        assertThat(state.reserveFallback(state.reset(2), 60000, 180000)).isNotNull()
    }

    @Test fun resetInvalidatesOldGeneration() {
        val state = PlayerEpisodeSourceState()
        val old = state.reserveFallback(state.reset(1), 60000, 180000)!!
        state.reset(2)
        assertThat(state.isCurrent(old)).isFalse()
        assertThat(state.stage(old, Series(20, "Drama"), Episode(201, "S01E02", 2, 1))).isFalse()
        assertThat(state.progressSnapshot(old, 0, 0)).isNull()
    }

    @Test fun transientZeroKeepsCapturedProgress() {
        val state = PlayerEpisodeSourceState()
        val token = state.reserveFallback(state.reset(1), 60000, 180000)!!
        assertThat(state.progressSnapshot(token, 0, 0)).isEqualTo(60000L to 180000L)
        assertThat(state.progressSnapshot(token, 1000, 180000)).isEqualTo(60000L to 180000L)
    }

    @Test fun confirmationRequiresCapturedSeek() {
        val state = PlayerEpisodeSourceState()
        val token = state.reserveFallback(state.reset(1), 60000, 180000)!!
        assertThat(state.confirmResume(token, 60000)).isFalse() // no replacement staged
        state.stage(token, Series(20, "Drama"), Episode(201, "S01E02", 2, 1))
        assertThat(state.confirmResume(token, 0)).isFalse()
        assertThat(state.confirmResume(token, 57999)).isFalse()
        assertThat(state.confirmResume(token, 58000)).isTrue()
        assertThat(state.progressSnapshot(token, 62000, 180000)).isEqualTo(62000L to 180000L)
    }

    @Test fun manualTrackKeepsPhysicalSource() {
        val state = PlayerEpisodeSourceState()
        val token = state.reserveFallback(state.reset(1), 60000, 180000)!!
        val series = Series(20, "Drama")
        val episode = Episode(201, "S01E02", 2, 1)
        assertThat(state.stage(token, series, episode)).isTrue()
        assertThat(state.source(token)).isEqualTo(PhysicalEpisodeSource(token, series, episode))
        // Reading/staging state is independent of track preference; only reset clears it.
        assertThat(state.source(token)).isNotNull()
        state.reset(1)
        assertThat(state.source(token)).isNull()
    }

    @Test fun retryPhysicalKeepsCandidateAndConsumedBudget() {
        val state = PlayerEpisodeSourceState()
        val token = state.reserveFallback(state.reset(1), 60000, 180000)!!
        val episode = Episode(201, "S01E02 AVC", 2, 1)
        state.stage(token, Series(20, "Drama 720p"), episode)
        state.markFailed(token)
        val retry = state.retryPhysical(65000, 180000)
        assertThat(retry).isNotNull()
        assertThat(state.source(retry!!)?.episode).isEqualTo(episode)
        assertThat(state.isCurrent(token)).isFalse()
        assertThat(state.attempted).isTrue()
        assertThat(state.failed).isFalse()
        assertThat(state.reserveFallback(retry, 65000, 180000)).isNull()
    }
}
