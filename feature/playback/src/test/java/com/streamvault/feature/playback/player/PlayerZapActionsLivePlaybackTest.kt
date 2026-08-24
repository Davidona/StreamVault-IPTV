package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.PlaybackHistory
import com.streamvault.domain.model.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerZapActionsLivePlaybackTest {

    @Test
    fun `failed live history write is retryable and success is deduplicated`() = runTest {
        val results = ArrayDeque<Result<Unit>>().apply {
            add(Result.error("disk unavailable"))
            add(Result.success(Unit))
        }
        var writes = 0
        val coordinator = LivePlaybackRecordCoordinator {
            writes++
            results.removeFirst()
        }
        val candidate = liveCandidate(channelId = 42L)

        assertThat(coordinator.execute(coordinator.begin(candidate)!!)).isInstanceOf(Result.Error::class.java)
        assertThat(coordinator.execute(coordinator.begin(candidate)!!)).isInstanceOf(Result.Success::class.java)

        assertThat(coordinator.begin(candidate)).isNull()
        assertThat(writes).isEqualTo(2)
    }

    @Test
    fun `A to B to A retries latest A after first A fails and drops queued B`() = runTest {
        val firstWriteStarted = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()
        val writes = mutableListOf<Long>()
        val coordinator = LivePlaybackRecordCoordinator { history ->
            writes += history.contentId
            if (writes.size == 1) {
                firstWriteStarted.complete(Unit)
                releaseFirstWrite.await()
                Result.error("first A failed")
            } else {
                Result.success(Unit)
            }
        }
        val channelA = liveCandidate(channelId = 42L)
        val channelB = liveCandidate(channelId = 43L)

        launch { coordinator.execute(coordinator.begin(channelA)!!) }
        firstWriteStarted.await()
        val queuedChannelB = coordinator.begin(channelB)!!
        launch { coordinator.execute(queuedChannelB) }
        val returnedChannelA = coordinator.begin(channelA)
        assertThat(returnedChannelA).isNotNull()
        launch { coordinator.execute(returnedChannelA!!) }
        releaseFirstWrite.complete(Unit)
        advanceUntilIdle()

        assertThat(writes).containsExactly(42L, 42L).inOrder()
        assertThat(coordinator.begin(channelA)).isNull()
        assertThat(coordinator.begin(channelB)).isNotNull()
    }

    @Test
    fun `concurrent duplicate events launch one repository write`() = runTest {
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        var writes = 0
        val coordinator = LivePlaybackRecordCoordinator {
            writes++
            writeStarted.complete(Unit)
            releaseWrite.await()
            Result.success(Unit)
        }
        val candidate = liveCandidate(channelId = 42L)
        val attempt = coordinator.begin(candidate)!!

        launch { coordinator.execute(attempt) }
        writeStarted.await()

        assertThat(coordinator.begin(candidate)).isNull()
        releaseWrite.complete(Unit)
        advanceUntilIdle()
        assertThat(writes).isEqualTo(1)
    }

    @Test
    fun `cancelled repository write clears in flight marker and permits retry`() = runTest {
        val firstWriteStarted = CompletableDeferred<Unit>()
        var writes = 0
        val coordinator = LivePlaybackRecordCoordinator {
            writes++
            if (writes == 1) {
                firstWriteStarted.complete(Unit)
                awaitCancellation()
            }
            Result.success(Unit)
        }
        val candidate = liveCandidate(channelId = 42L)
        val cancelledWrite = launch {
            coordinator.execute(coordinator.begin(candidate)!!)
        }
        firstWriteStarted.await()

        cancelledWrite.cancelAndJoin()
        val retryResult = coordinator.execute(coordinator.begin(candidate)!!)

        assertThat(retryResult).isInstanceOf(Result.Success::class.java)
        assertThat(writes).isEqualTo(2)
    }

    @Test
    fun `stale completion is followed by latest channel write`() = runTest {
        val staleWriteStarted = CompletableDeferred<Unit>()
        val releaseStaleWrite = CompletableDeferred<Unit>()
        val writes = mutableListOf<Long>()
        val coordinator = LivePlaybackRecordCoordinator { history ->
            writes += history.contentId
            if (writes.size == 2) {
                staleWriteStarted.complete(Unit)
                releaseStaleWrite.await()
            }
            Result.success(Unit)
        }
        val channelA = liveCandidate(channelId = 42L)
        val channelB = liveCandidate(channelId = 43L)

        coordinator.execute(coordinator.begin(channelA)!!)
        val staleChannelB = coordinator.begin(channelB)!!
        launch { coordinator.execute(staleChannelB) }
        staleWriteStarted.await()
        val restoreLatest = coordinator.begin(channelA)
        assertThat(restoreLatest).isNotNull()
        launch { coordinator.execute(restoreLatest!!) }
        runCurrent()
        releaseStaleWrite.complete(Unit)
        advanceUntilIdle()

        assertThat(writes).containsExactly(42L, 43L, 42L).inOrder()
        assertThat(coordinator.begin(channelA)).isNull()
    }

    @Test
    fun `buildLivePlaybackRecordCandidate uses channel metadata when available`() {
        val channel = Channel(
            id = 42L,
            name = "News HD",
            streamUrl = "https://example.com/live/news.m3u8",
            providerId = 9L
        )

        val candidate = buildLivePlaybackRecordCandidate(
            currentProviderId = 9L,
            currentContentType = ContentType.LIVE,
            currentContentId = 7L,
            currentTitle = "Fallback title",
            currentResolvedPlaybackUrl = "https://example.com/fallback",
            currentStreamUrl = "https://example.com/fallback-raw",
            channel = channel
        )

        assertThat(candidate).isNotNull()
        assertThat(candidate!!.playbackKey).isEqualTo(9L to 42L)
        assertThat(candidate.history.contentId).isEqualTo(42L)
        assertThat(candidate.history.title).isEqualTo("News HD")
        assertThat(candidate.history.streamUrl).isEqualTo("https://example.com/live/news.m3u8")
    }

    @Test
    fun `buildLivePlaybackRecordCandidate falls back to active playback session`() {
        val candidate = buildLivePlaybackRecordCandidate(
            currentProviderId = 11L,
            currentContentType = ContentType.LIVE,
            currentContentId = 77L,
            currentTitle = "Channel 77",
            currentResolvedPlaybackUrl = "https://example.com/resolved",
            currentStreamUrl = "https://example.com/raw",
            channel = null
        )

        assertThat(candidate).isNotNull()
        assertThat(candidate!!.playbackKey).isEqualTo(11L to 77L)
        assertThat(candidate.history.contentId).isEqualTo(77L)
        assertThat(candidate.history.title).isEqualTo("Channel 77")
        assertThat(candidate.history.streamUrl).isEqualTo("https://example.com/resolved")
    }

    @Test
    fun `buildLivePlaybackRecordCandidate rejects non live sessions`() {
        val candidate = buildLivePlaybackRecordCandidate(
            currentProviderId = 11L,
            currentContentType = ContentType.MOVIE,
            currentContentId = 77L,
            currentTitle = "Movie",
            currentResolvedPlaybackUrl = "https://example.com/resolved",
            currentStreamUrl = "https://example.com/raw",
            channel = null
        )

        assertThat(candidate).isNull()
    }

    private fun liveCandidate(channelId: Long): LivePlaybackRecordCandidate =
        LivePlaybackRecordCandidate(
            playbackKey = 9L to channelId,
            history = PlaybackHistory(
                contentId = channelId,
                contentType = ContentType.LIVE,
                providerId = 9L,
                title = "Channel $channelId",
                streamUrl = "https://example.com/live/$channelId.m3u8"
            )
        )
}
