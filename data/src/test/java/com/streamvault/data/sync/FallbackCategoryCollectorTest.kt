package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import org.junit.Test

class FallbackCategoryCollectorTest {
    @Test
    fun `repeated names retain ordering and allow adult promotion`() {
        val collector = FallbackCategoryCollector(7, ContentType.LIVE)
        collector.record(12, "News", false)
        collector.record(13, "Sports", false)
        repeat(500) { collector.record(12, " News ", false) }
        collector.record(12, "News", true)
        collector.record(12, "News", false)
        assertThat(collector.entities().map { it.categoryId }).containsExactly(12L, 13L).inOrder()
        assertThat(collector.entities().first().isAdult).isTrue()
    }

    @Test
    fun `placeholder can be replaced and alternate names still strengthen adult classification`() {
        val collector = FallbackCategoryCollector(7, ContentType.LIVE)
        collector.record(12, null, false)
        collector.record(12, "News", false)
        collector.record(12, "XXX", false)
        val category = collector.entities().single()
        assertThat(category.name).isEqualTo("News")
        assertThat(category.isAdult).isTrue()
    }
}
