package com.streamvault.feature.playback.translation

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Behavior tests for [runCaptionDisplayLoop], the caption pacing/coalescing logic
 * extracted from [LiveTranslationSession]. These run on virtual time so the dwell
 * windows are exercised deterministically.
 */
class LiveTranslationCaptionPacingTest {

    private val lingerMs = 20_000L
    private val finalDwellMs = 2_200L
    private val partialDwellMs = 1_500L

    @Test
    fun rendersEachCaptionAndHoldsItForItsDwellWindow() = runTest {
        val rendered = mutableListOf<String>()
        val channel = Channel<CaptionTick>(Channel.CONFLATED)

        channel.trySend(CaptionTick("Hello", isFinal = false, chunkId = 1))
        val job = launch {
            runCaptionDisplayLoop(
                captionUpdates = channel,
                lingerMs = lingerMs,
                finalDwellMs = finalDwellMs,
                partialDwellMs = partialDwellMs,
                render = { rendered += it },
                clear = {}
            )
        }

        // First partial renders immediately, then dwells for partialDwellMs.
        runCurrent()
        assertThat(rendered).containsExactly("Hello")

        // A second caption sent before the dwell elapses must not render yet.
        channel.trySend(CaptionTick("Hello there", isFinal = true, chunkId = 2))
        advanceTimeBy(partialDwellMs - 1)
        runCurrent()
        assertThat(rendered).containsExactly("Hello")

        // Once the dwell elapses the newer caption is shown, in order.
        advanceTimeBy(1)
        runCurrent()
        assertThat(rendered).containsExactly("Hello", "Hello there").inOrder()

        job.cancel()
    }

    @Test
    fun finalizedPhraseIsSkippedWhenANewerPartialArrivesDuringTheDwell() = runTest {
        // Documents the intentional tradeoff: the conflated channel only keeps the
        // newest pending caption, so a finalized phrase can be dropped if a newer
        // partial supersedes it before the previous line's dwell elapses.
        val rendered = mutableListOf<String>()
        val channel = Channel<CaptionTick>(Channel.CONFLATED)

        channel.trySend(CaptionTick("Bonjour", isFinal = false, chunkId = 1))
        val job = launch {
            runCaptionDisplayLoop(
                captionUpdates = channel,
                lingerMs = lingerMs,
                finalDwellMs = finalDwellMs,
                partialDwellMs = partialDwellMs,
                render = { rendered += it },
                clear = {}
            )
        }
        runCurrent()
        assertThat(rendered).containsExactly("Bonjour")

        // While "Bonjour" is dwelling, a finalized phrase arrives and is then
        // immediately superseded by a newer partial. Conflation keeps only the last.
        channel.trySend(CaptionTick("Bonjour le monde.", isFinal = true, chunkId = 2))
        channel.trySend(CaptionTick("Bonjour le monde, comment", isFinal = false, chunkId = 3))

        advanceTimeBy(partialDwellMs)
        runCurrent()

        // The finalized "Bonjour le monde." is never rendered; we jump to the latest.
        assertThat(rendered).containsExactly("Bonjour", "Bonjour le monde, comment").inOrder()
        assertThat(rendered).doesNotContain("Bonjour le monde.")

        job.cancel()
    }

    @Test
    fun clearsCaptionAfterLingerTimeoutWithNoNewText() = runTest {
        val rendered = mutableListOf<String>()
        var clears = 0
        val channel = Channel<CaptionTick>(Channel.CONFLATED)

        channel.trySend(CaptionTick("Final line.", isFinal = true, chunkId = 1))
        val job = launch {
            runCaptionDisplayLoop(
                captionUpdates = channel,
                lingerMs = lingerMs,
                finalDwellMs = finalDwellMs,
                partialDwellMs = partialDwellMs,
                render = { rendered += it },
                clear = { clears++ }
            )
        }
        runCurrent()
        assertThat(rendered).containsExactly("Final line.")

        // No further updates: after the dwell and a full linger window with nothing
        // received, the caption is cleared exactly once.
        advanceTimeBy(finalDwellMs + lingerMs)
        runCurrent()
        assertThat(clears).isEqualTo(1)

        // Idle thereafter must not clear again (nothing visible to clear).
        advanceTimeBy(lingerMs * 2)
        runCurrent()
        assertThat(clears).isEqualTo(1)

        job.cancel()
    }

    @Test
    fun stopsWhenChannelIsClosed() = runTest {
        val rendered = mutableListOf<String>()
        val channel = Channel<CaptionTick>(Channel.CONFLATED)

        val job = launch {
            runCaptionDisplayLoop(
                captionUpdates = channel,
                lingerMs = lingerMs,
                finalDwellMs = finalDwellMs,
                partialDwellMs = partialDwellMs,
                render = { rendered += it },
                clear = {}
            )
        }

        channel.close()
        advanceUntilIdle()

        assertThat(job.isActive).isFalse()
        assertThat(rendered).isEmpty()
    }
}
