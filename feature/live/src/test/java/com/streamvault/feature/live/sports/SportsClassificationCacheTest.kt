package com.streamvault.feature.live.sports

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Channel
import com.streamvault.domain.model.Program
import org.junit.Test

class SportsClassificationCacheTest {
    private val program = Program(
        id = 1L, channelId = "1", title = "Arsenal vs Chelsea",
        description = "Premier League football", startTime = 100L, endTime = 200L,
    )
    private val context = SportsProgrammeClassifier.prepareChannel(Channel(1L, "Sports One"))

    @Test
    fun `content is reused across programme records and schedule windows`() {
        val cache = SportsClassificationCache()
        val first = cache.classify(program, context)
        val rebroadcast = program.copy(id = 99L, channelId = "2", startTime = 300L, endTime = 400L)

        assertThat(cache.classify(rebroadcast, context)).isSameInstanceAs(first)
        assertThat(cache.stats().hits).isEqualTo(1L)
        assertThat(cache.stats().misses).isEqualTo(1L)
    }

    @Test
    fun `edited title description category and genre are reclassified`() {
        val cache = SportsClassificationCache()
        cache.classify(program, context)
        listOf(
            program.copy(title = "Arsenal vs Liverpool"),
            program.copy(description = "Match replay highlights"),
            program.copy(category = "Sports news"),
            program.copy(genre = "Preview"),
        ).forEach { cache.classify(it, context) }

        assertThat(cache.stats().misses).isEqualTo(5L)
        assertThat(cache.stats().hits).isEqualTo(0L)
    }

    @Test
    fun `channel classification context and category labels are part of cache identity`() {
        val cache = SportsClassificationCache()
        val unknown = program.copy(description = "")
        val news = SportsProgrammeClassifier.prepareChannel(Channel(1L, "News"))
        val football = SportsProgrammeClassifier.prepareChannel(Channel(1L, "Football"))
        val sports = SportsProgrammeClassifier.prepareChannel(Channel(1L, "Sports One"))
        val grouped = SportsProgrammeClassifier.prepareChannel(Channel(1L, "News", groupTitle = "Football"))

        assertThat(cache.classify(unknown, news).isCompetitiveEvent).isFalse()
        assertThat(cache.classify(unknown, football).sport).isEqualTo(SportType.FOOTBALL)
        assertThat(cache.classify(unknown, sports).isCompetitiveEvent).isTrue()
        assertThat(cache.classify(unknown, grouped).categoryLabel).isEqualTo("Football")
        assertThat(cache.stats().misses).isEqualTo(4L)
    }

    @Test
    fun `negative results are cached and least recently used entries are evicted`() {
        val cache = SportsClassificationCache(maxEntries = 2)
        val first = program.copy(title = "Evening news", description = "")
        val second = program.copy(title = "Weather", description = "")
        val third = program.copy(title = "Documentary", description = "")
        assertThat(cache.classify(first, context).isCompetitiveEvent).isFalse()
        cache.classify(second, context)
        cache.classify(first, context)
        cache.classify(third, context)
        cache.classify(second, context)

        assertThat(cache.stats().entries).isEqualTo(2)
        assertThat(cache.stats().hits).isEqualTo(1L)
        assertThat(cache.stats().misses).isEqualTo(4L)
    }

    @Test
    fun `text budget stays bounded and oversized programmes do not flush useful entries`() {
        val budget = 16_384L
        val cache = SportsClassificationCache(maxEntries = 100, maxEstimatedBytes = budget)
        repeat(40) { index -> cache.classify(program.copy(title = "Fixture $index"), context) }
        val before = cache.stats()
        assertThat(before.entries).isLessThan(40)
        assertThat(before.estimatedBytes).isAtMost(budget)

        cache.classify(program.copy(description = "x".repeat(30_000)), context)
        assertThat(cache.stats().entries).isEqualTo(before.entries)
        assertThat(cache.stats().estimatedBytes).isEqualTo(before.estimatedBytes)
        cache.clear()
        assertThat(cache.stats().entries).isEqualTo(0)
        assertThat(cache.stats().estimatedBytes).isEqualTo(0L)
    }
}
