package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import org.junit.Test

class SubtitleSearchQueriesTest {
    @Test
    fun `movie searches by id then cleaned provider title and year`() {
        val queries = subtitleSearchQueries(
            ContentType.MOVIE, "10 - the lord of the rings the fellowship of the ring - 2001 [Multi Sub]",
            2001, 120, null, null, null, "HR",
        )
        assertThat(queries.map { it.toQueryParams() }).containsExactly(
            mapOf("languages" to "hr", "order_by" to "download_count", "type" to "movie", "tmdb_id" to "120"),
            mapOf("languages" to "hr", "order_by" to "download_count", "type" to "movie", "query" to "the lord of the rings the fellowship of the ring", "year" to "2001"),
            mapOf("languages" to "hr", "order_by" to "download_count", "type" to "movie", "query" to "the lord of the rings the fellowship of the ring"),
        ).inOrder()
    }

    @Test
    fun `episode uses parent id and episode filters in both searches`() {
        val queries = subtitleSearchQueries(ContentType.SERIES_EPISODE, "Show [Multi Sub]", null, null, 42, 2, 3, "all")
        assertThat(queries[0].toQueryParams()).containsExactly(
            "parent_tmdb_id", "42", "season_number", "2", "episode_number", "3", "order_by", "download_count", "type", "episode",
        )
        assertThat(queries[1].toQueryParams()).containsExactly(
            "query", "Show", "season_number", "2", "episode_number", "3", "order_by", "download_count", "type", "episode",
        )
    }

    @Test
    fun `missing id uses title and retains numeric movie names`() {
        val queries = subtitleSearchQueries(ContentType.MOVIE, "1917", null, null, null, null, null, "en")
        assertThat(queries.single().query).isEqualTo("1917")
        val numbered = subtitleSearchQueries(ContentType.MOVIE, "Blade Runner 2049", null, null, null, null, null, "en")
        assertThat(numbered.single().query).isEqualTo("Blade Runner 2049")
    }
}
