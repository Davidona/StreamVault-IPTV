package com.streamvault.domain.trakt

import org.junit.Assert.*
import org.junit.Test

class TraktHistoryIndexTest {
    @Test fun contains_compares_local_completion_at_trakt_minute_precision() {
        val movie = TraktMediaIdentity.MovieId(603)
        val index = TraktHistoryIndex(listOf(TraktHistoryEntry(1, movie, 120_000)))

        assertTrue(index.contains(movie, 120_001))
        assertTrue(index.contains(movie, 179_999))
        assertFalse(index.contains(movie, 180_000))
        assertFalse(index.contains(TraktMediaIdentity.MovieId(604), 120_001))
    }
}
