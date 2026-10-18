package com.streamvault.player.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AdaptiveDecodePressurePolicyTest {
    private val ladder = listOf(DecodePressureRendition(720, 3_000_000), DecodePressureRendition(1080, 9_000_000), DecodePressureRendition(2160, 41_000_000))
    private fun sample(t: Long, drops: Int = (t * 50).toInt(), rendered: Int = (t * 10).toInt(),
        buffer: Long = 5_000, eligible: Boolean = true, playing: Boolean = true,
        height: Int = 2160, group: Any = "ladder", renditions: List<DecodePressureRendition> = ladder
    ) = DecodePressureSample(t * 1_000, group, eligible, playing, buffer, rendered, drops,
        height, ladder.firstOrNull { it.height == height }?.bitrate ?: 41_000_000, renditions)

    private fun firstChange(
        policy: AdaptiveDecodePressurePolicy,
        samples: LongRange,
        height: Int,
        pressure: Boolean
    ): Pair<Long, DecodePressureChange>? {
        for (time in samples) {
            val observation = if (pressure) {
                sample(time, drops = (100_000 + (time - samples.first) * 50).toInt(),
                    rendered = (200_000 + (time - samples.first) * 10).toInt(), height = height)
            } else {
                sample(time, drops = 100_000,
                    rendered = (200_000 + (time - samples.first) * 60).toInt(), height = height)
            }
            policy.observe(observation)?.let { return time to it }
        }
        return null
    }

    private fun policyAfterRapidRecurrence(): Pair<AdaptiveDecodePressurePolicy, Long> {
        val policy = AdaptiveDecodePressurePolicy()
        val down = firstChange(policy, 0L..40L, height = 2160, pressure = true)!!
        val restored = firstChange(policy, down.first + 1..down.first + 150,
            height = 1080, pressure = false)!!
        val rapidDown = firstChange(policy, restored.first + 1..restored.first + 70,
            height = 2160, pressure = true)!!
        assertThat(rapidDown.second.maxHeight).isEqualTo(1080)
        return policy to rapidDown.first
    }

    @Test fun `sustained pressure narrows one supported step without repeated poll actions`() {
        val p = AdaptiveDecodePressurePolicy()
        val changes = (0L..29).mapNotNull { p.observe(sample(it)) }
        assertThat(changes.map { it.maxHeight }).containsExactly(1080)
        assertThat(changes.single().maxBitrate).isEqualTo(9_000_000)
    }

    @Test fun `restoration bitrate belongs to the next height not an expensive smaller track`() {
        val p = AdaptiveDecodePressurePolicy()
        val odd = ladder + DecodePressureRendition(360, 20_000_000)
        (0L..20).forEach { p.observe(sample(it, height = 1080, renditions = odd)) }
        val changes = (21L..105).mapNotNull {
            p.observe(sample(it, drops = 1000, rendered = (200 + (it - 20) * 60).toInt(), height = 720, renditions = odd))
        }
        assertThat(changes.single().maxHeight).isEqualTo(1080)
        assertThat(changes.single().maxBitrate).isEqualTo(9_000_000)
    }

    @Test fun `a single burst is insufficient`() {
        val p = AdaptiveDecodePressurePolicy()
        val changes = (0L..30).mapNotNull { p.observe(sample(it, if (it >= 12) 100 else 0, (it * 60).toInt())) }
        assertThat(changes).isEmpty()
    }

    @Test fun `buffer drain and ineligible or inactive playback cannot constrain`() {
        for (mode in 0..3) {
            val p = AdaptiveDecodePressurePolicy()
            assertThat((0L..30).mapNotNull { p.observe(sample(it,
                buffer = if (mode == 0) 500 else 5_000,
                eligible = mode != 1, playing = mode !in 2..3)) }).isEmpty()
        }
    }

    @Test fun `single rendition and unknown or higher bitrate alternatives cannot constrain`() {
        for (r in listOf(listOf(ladder.last()), listOf(ladder.last(), DecodePressureRendition(1080, 0)),
            listOf(ladder.last(), DecodePressureRendition(1080, 50_000_000)))) {
            val p = AdaptiveDecodePressurePolicy()
            assertThat((0L..35).mapNotNull { p.observe(sample(it, renditions = r)) }).isEmpty()
        }
    }

    @Test fun `new session discards pressure history and pending restoration`() {
        val p = AdaptiveDecodePressurePolicy()
        (0L..20).forEach { p.observe(sample(it)) }
        p.reset()
        assertThat((21L..30).mapNotNull { p.observe(sample(it, group = "new")) }).isEmpty()
    }

    @Test fun `seek counter reset and long observation gap cannot join pressure windows`() {
        for (mode in 0..2) {
            val p = AdaptiveDecodePressurePolicy()
            (0L..15).forEach { p.observe(sample(it)) }
            if (mode == 0) p.discontinuity()
            val changes = (16L..24).mapNotNull {
                p.observe(when (mode) {
                    1 -> sample(it, drops = (it - 16).toInt() * 50, rendered = (it - 16).toInt() * 10)
                    2 -> sample(it + 10)
                    else -> sample(it)
                })
            }
            assertThat(changes).isEmpty()
        }
    }

    @Test fun `manual selection or group replacement clears only the active policy constraint`() {
        for (mode in 0..1) {
            val p = AdaptiveDecodePressurePolicy()
            (0L..20).forEach { p.observe(sample(it)) }
            assertThat(p.observe(sample(21, eligible = mode != 0, group = if (mode == 1) "other" else "ladder")))
                .isEqualTo(DecodePressureChange(null))
        }
    }

    @Test fun `stable playback waits for cooldown before restoring unconstrained Auto`() {
        val p = AdaptiveDecodePressurePolicy()
        (0L..20).forEach { p.observe(sample(it)) }
        val changes = (21L..105).mapNotNull {
            p.observe(sample(it, drops = 1_000, rendered = (200 + (it - 20) * 60).toInt(), height = 1080))
                ?.let { change -> it to change.maxHeight }
        }
        assertThat(changes).containsExactly(91L to null)
    }

    @Test fun `restoration climbs one available step rather than jumping from lowest to highest`() {
        val p = AdaptiveDecodePressurePolicy()
        (0L..20).forEach { p.observe(sample(it, height = 1080)) }
        val changes = (21L..105).mapNotNull {
            p.observe(sample(it, drops = 1_000, rendered = (200 + (it - 20) * 60).toInt(), height = 720))
                ?.let { change -> change.maxHeight }
        }
        assertThat(changes).containsExactly(1080)
    }

    @Test fun `paused or buffering time does not count toward stable restoration`() {
        val p = AdaptiveDecodePressurePolicy()
        (0L..20).forEach { p.observe(sample(it)) }
        (21L..120).forEach { assertThat(p.observe(sample(it, playing = false))).isNull() }
        assertThat((121L..150).mapNotNull {
            p.observe(sample(it, drops = 1_000, rendered = (it * 60).toInt(), height = 1080))
        }).isEmpty()
    }

    @Test fun `rapid restoration recurrence lengthens the stable wait then returns to baseline`() {
        val p = AdaptiveDecodePressurePolicy()
        val firstDown = firstChange(p, 0L..40L, height = 2160, pressure = true)!!
        assertThat(firstDown.second.maxHeight).isEqualTo(1080)

        val firstRestore = firstChange(p, firstDown.first + 1..firstDown.first + 150,
            height = 1080, pressure = false)!!
        assertThat(firstRestore.second.maxHeight).isNull()
        assertThat(firstRestore.second.restorationStableMs).isEqualTo(60_000L)
        assertThat(firstRestore.first - firstDown.first).isAtLeast(60)

        // A rendition transition can briefly buffer; it must not count as stable time or
        // erase the source's recent restoration history.
        (firstRestore.first + 1..firstRestore.first + 3).forEach {
            p.observe(sample(it, playing = false))
        }
        val rapidDown = firstChange(p, firstRestore.first + 4..firstRestore.first + 70,
            height = 2160, pressure = true)!!
        assertThat(rapidDown.second.maxHeight).isEqualTo(1080)
        assertThat(rapidDown.first - firstRestore.first).isAtMost(60)

        val backedOffRestore = firstChange(p, rapidDown.first + 1..rapidDown.first + 160,
            height = 1080, pressure = false)!!
        assertThat(backedOffRestore.second.maxHeight).isNull()
        assertThat(backedOffRestore.second.restorationStableMs).isEqualTo(90_000L)
        assertThat(backedOffRestore.first - rapidDown.first).isAtLeast(90)
        assertThat(backedOffRestore.first - rapidDown.first).isLessThan(130)

        val repeatedRapidDown = firstChange(p, backedOffRestore.first + 1..backedOffRestore.first + 70,
            height = 2160, pressure = true)!!
        val repeatedBackedOffRestore = firstChange(p,
            repeatedRapidDown.first + 1..repeatedRapidDown.first + 160,
            height = 1080, pressure = false)!!
        assertThat(repeatedBackedOffRestore.second.maxHeight).isNull()
        assertThat(repeatedBackedOffRestore.second.restorationStableMs).isEqualTo(90_000L)
        assertThat(repeatedBackedOffRestore.first - repeatedRapidDown.first).isAtLeast(90)

        // A full minute of healthy restored playback clears the recurrence history.
        for (time in repeatedBackedOffRestore.first + 1..repeatedBackedOffRestore.first + 65) {
            p.observe(sample(time, drops = 100_000, rendered = 300_000 + (time * 60).toInt(), height = 2160))
        }
        val laterDown = firstChange(p, repeatedBackedOffRestore.first + 66..repeatedBackedOffRestore.first + 130,
            height = 2160, pressure = true)!!
        val baselineRestore = firstChange(p, laterDown.first + 1..laterDown.first + 130,
            height = 1080, pressure = false)!!
        assertThat(baselineRestore.second.maxHeight).isNull()
        assertThat(baselineRestore.second.restorationStableMs).isEqualTo(60_000L)
        assertThat(baselineRestore.first - laterDown.first).isAtLeast(60)
        assertThat(baselineRestore.first - laterDown.first).isLessThan(90)
    }

    @Test fun `seek reset and manual selection clear restoration backoff`() {
        for (boundary in 0..2) {
            val (p, lastTime) = policyAfterRapidRecurrence()
            if (boundary == 0) {
                p.discontinuity()
                val restore = firstChange(p, lastTime + 1..lastTime + 130,
                    height = 1080, pressure = false)!!
                assertThat(restore.second.restorationStableMs).isEqualTo(60_000L)
            } else {
                if (boundary == 1) {
                    assertThat(p.observe(sample(lastTime + 1, eligible = false)))
                        .isEqualTo(DecodePressureChange(null))
                } else {
                    p.reset()
                }
                val down = firstChange(p, lastTime + 2..lastTime + 50,
                    height = 2160, pressure = true)!!
                val restore = firstChange(p, down.first + 1..down.first + 130,
                    height = 1080, pressure = false)!!
                assertThat(restore.second.restorationStableMs).isEqualTo(60_000L)
            }
        }
    }
}
