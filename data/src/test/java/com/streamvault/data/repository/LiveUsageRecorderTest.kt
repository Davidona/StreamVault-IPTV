package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import com.streamvault.domain.repository.LiveWatchUsageRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveUsageRecorderTest {
    private val privacy = MutableStateFlow(false)
    private val repository = FakeUsage()
    private val target = LiveWatchTarget(7, 1, 101, "news")
    private fun sample(at: Long, owner: String = "primary", row: LiveWatchTarget? = target, playing: Boolean = row != null) =
        LiveWatchSample(owner, row, playing, at, 1_000_000 + at)

    @Test fun `stop flushes qualified time and independent owners cannot qualify one another`() = runTest {
        val recorder = LiveUsageRecorder(repository, privacy, privacy, backgroundScope)
        recorder.sample(sample(0))
        recorder.sample(sample(0, "multiview", target.copy(contentId = 2, streamId = 102, logicalKey = "sport")))
        recorder.sample(sample(10_000))
        recorder.sample(sample(5_000, "multiview", target.copy(contentId = 2, streamId = 102, logicalKey = "sport")))
        runCurrent()
        assertThat(repository.deltas.map { it.target.streamId to it.watchedMs }).containsExactly(101L to 10_000L)
        recorder.sample(sample(12_000, row = null))
        runCurrent()
        assertThat(repository.deltas.sumOf { it.watchedMs }).isEqualTo(12_000)
    }

    @Test fun `history clear wins over queued old samples and ongoing playback starts a fresh qualification`() = runTest {
        val recorder = LiveUsageRecorder(repository, privacy, privacy, backgroundScope)
        val gate = CompletableDeferred<Unit>()
        repository.gate = gate
        recorder.sample(sample(0))
        recorder.sample(sample(10_000))
        runCurrent()
        val clearing = launch { recorder.clearAll() }
        runCurrent()
        recorder.sample(sample(20_000))
        gate.complete(Unit)
        runCurrent()
        clearing.join()
        assertThat(repository.deltas).isEmpty()
        recorder.sample(sample(30_000))
        recorder.sample(sample(40_000))
        runCurrent()
        assertThat(repository.deltas.sumOf { it.watchedMs }).isEqualTo(10_000)
    }

    @Test fun `privacy changes discard pending public qualification and exclude private samples`() = runTest {
        val recorder = LiveUsageRecorder(repository, privacy, privacy, backgroundScope)
        recorder.sample(sample(0))
        recorder.sample(sample(8_000))
        runCurrent()
        privacy.value = true
        runCurrent()
        recorder.sample(sample(20_000))
        runCurrent()
        privacy.value = false
        runCurrent()
        recorder.sample(sample(30_000))
        recorder.sample(sample(35_000))
        runCurrent()
        assertThat(repository.deltas).isEmpty()
        recorder.sample(sample(40_000))
        runCurrent()
        assertThat(repository.deltas.sumOf { it.watchedMs }).isEqualTo(10_000)
    }

    @Test fun `actual privacy is checked even when the eager cache has not updated`() = runTest {
        val actual = MutableStateFlow(true)
        val recorder = LiveUsageRecorder(repository, privacy, actual, backgroundScope)
        recorder.sample(sample(0))
        recorder.sample(sample(10_000))
        runCurrent()
        assertThat(repository.deltas).isEmpty()
        actual.value = false
        recorder.sample(sample(20_000))
        recorder.sample(sample(30_000))
        runCurrent()
        assertThat(repository.deltas.sumOf { it.watchedMs }).isEqualTo(10_000)
    }

    private class FakeUsage : LiveWatchUsageRepository {
        val deltas = mutableListOf<LiveWatchDelta>()
        var gate: CompletableDeferred<Unit>? = null
        override fun observe(providerId: Long): Flow<Map<String, LiveWatchUsage>> = flowOf(emptyMap())
        override suspend fun record(delta: LiveWatchDelta) { gate?.await(); deltas += delta }
        override suspend fun clearAll() { deltas.clear() }
        override suspend fun clearProvider(providerId: Long) { deltas.removeAll { it.target.providerId == providerId } }
        override suspend fun clearContent(providerId: Long, contentId: Long) { deltas.removeAll { it.target.providerId == providerId && it.target.contentId == contentId } }
    }
}
