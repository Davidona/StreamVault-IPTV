package com.streamvault.data.repository

import com.streamvault.domain.model.LiveWatchSample
import com.streamvault.domain.policy.LiveWatchAccumulator
import com.streamvault.domain.repository.LiveWatchUsageRepository
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One bounded IO actor keeps player callbacks nonblocking and history clearing authoritative. */
internal class LiveUsageRecorder(
    private val repository: LiveWatchUsageRepository,
    private val privacy: StateFlow<Boolean>,
    private val actualPrivacy: Flow<Boolean>,
    scope: CoroutineScope,
    private val onFailure: (Throwable) -> Unit = {}
) {
    private data class Envelope(val sample: LiveWatchSample, val generation: Long, val isPrivate: Boolean)
    private val generation = AtomicLong()
    private val mutex = Mutex()
    private val owners = mutableMapOf<String, LiveWatchAccumulator>()
    private val samples = Channel<Envelope>(64, BufferOverflow.DROP_OLDEST)

    init {
        scope.launch {
            var previous = privacy.value
            privacy.collect { current ->
                if (current != previous) {
                    previous = current
                    generation.incrementAndGet()
                    mutex.withLock { owners.clear() }
                }
            }
        }
        scope.launch {
            for (envelope in samples) {
                try {
                    mutex.withLock {
                        if (envelope.generation != generation.get()) return@withLock
                        val sample = envelope.sample
                        if (sample.target == null && sample.owner !in owners) return@withLock
                        if (envelope.isPrivate || actualPrivacy.first()) {
                            generation.incrementAndGet()
                            owners.clear()
                            return@withLock
                        }
                        val accumulator = owners.getOrPut(sample.owner) { LiveWatchAccumulator() }
                        accumulator.sample(sample.target, sample.isPlaying, false, sample.elapsedMs, sample.wallTimeMs)
                            .forEach { repository.record(it) }
                        if (sample.target == null) owners.remove(sample.owner)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { onFailure(error) }
            }
        }
    }

    fun sample(sample: LiveWatchSample) {
        samples.trySend(Envelope(sample, generation.get(), privacy.value))
    }

    suspend fun clearAll() = clear { repository.clearAll() }
    suspend fun clearProvider(providerId: Long) = clear { repository.clearProvider(providerId) }
    suspend fun clearContent(providerId: Long, contentId: Long) = clear { repository.clearContent(providerId, contentId) }

    private suspend fun clear(block: suspend () -> Unit) = mutex.withLock {
        generation.incrementAndGet()
        owners.clear()
        block()
    }
}
