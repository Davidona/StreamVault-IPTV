package com.streamvault.domain.policy

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LiveWatchTarget
import org.junit.Test

class LiveWatchAccumulatorTest {
    private val a = LiveWatchTarget(7, 1, 101, "news")
    private val b = LiveWatchTarget(7, 2, 102, "sport")
    private val model = LiveWatchAccumulator { "session" }
    private fun sample(ms: Long, target: LiveWatchTarget? = a, playing: Boolean = true, incognito: Boolean = false) =
        model.sample(target, playing, incognito, ms, 1_000_000L + ms)

    @Test fun `brief surfing is discarded and qualification records actual time only`() {
        sample(0)
        assertThat(sample(5_000, b)).isEmpty()
        assertThat(sample(10_000, b)).isEmpty()
        val result = sample(15_000, b).single()
        assertThat(result.target).isEqualTo(b)
        assertThat(result.watchedMs).isEqualTo(10_000)
        assertThat(result.lastWatchedAtMs).isEqualTo(1_015_000)
    }

    @Test fun `buffering and pause do not add time but do not restart qualification`() {
        sample(0)
        sample(5_000, playing = false)
        sample(65_000, playing = true)
        assertThat(sample(70_000).single().watchedMs).isEqualTo(10_000)
        assertThat(sample(72_000, playing = false).single().watchedMs).isEqualTo(2_000)
        assertThat(sample(90_000, playing = false)).isEmpty()
    }

    @Test fun `quality switches share qualification without duplicating physical stream time`() {
        val hd = a.copy(contentId = 3, streamId = 103)
        sample(0)
        sample(6_000, hd)
        val result = sample(10_000, hd)
        assertThat(result.map { it.target.streamId to it.watchedMs }).containsExactly(101L to 6_000L, 103L to 4_000L).inOrder()
        assertThat(result.map { it.sequence }).containsExactly(1L, 2L).inOrder()
        assertThat(sample(10_000, hd)).isEmpty()
    }

    @Test fun `private watching cannot qualify a subsequent public watch`() {
        sample(0)
        sample(8_000, incognito = true)
        sample(60_000, incognito = true)
        sample(65_000, incognito = false)
        assertThat(sample(70_000)).isEmpty()
        assertThat(sample(75_000).single().watchedMs).isEqualTo(10_000)
    }

    @Test fun `stop flushes final qualified increment and clock rollback cannot add time`() {
        sample(0)
        sample(10_000)
        assertThat(sample(12_000, target = null, playing = false).single().watchedMs).isEqualTo(2_000)
        sample(20_000)
        assertThat(sample(19_000)).isEmpty()
        assertThat(sample(24_000)).isEmpty()
        assertThat(sample(29_000).single().watchedMs).isEqualTo(10_000)
    }

    @Test fun `sampler stalls are bounded instead of fabricating playback minutes`() {
        sample(0)
        assertThat(sample(600_000).single().watchedMs).isEqualTo(10_000)
    }

    @Test fun `qualification crossing midnight keeps time in its actual UTC day`() {
        val start = 4 * 86_400_000L - 6_000
        model.sample(a, true, false, 0, start)
        val result = model.sample(a, true, false, 10_000, start + 10_000)
        assertThat(result.map { it.watchedDayUtc to it.watchedMs }).containsExactly(3L to 6_000L, 4L to 4_000L).inOrder()
    }
}
