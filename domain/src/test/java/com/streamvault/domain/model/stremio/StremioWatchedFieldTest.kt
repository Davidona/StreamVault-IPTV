package com.streamvault.domain.model.stremio

import org.junit.Assert.*
import org.junit.Test

class StremioWatchedFieldTest {
    @Test fun decodesTheOfficialExampleWithoutLosingColonSeparatedIds() {
        val ids=(1..8).map { "tt2934286:1:$it" }
        assertEquals(ids.take(5).toSet(),StremioWatchedField.decode("tt2934286:1:5:5:eJyTZwAAAEAAIA==",ids))
    }
    @Test fun highWatchedBitsRoundTripAndUseTheActualLastWatchedAnchor() {
        val ids=(1..25).map { "tt123:1:$it" }
        val watched=setOf(ids[0],ids[15],ids[24])
        val value=StremioWatchedField.encode(ids,watched)
        assertTrue(value.startsWith("tt123:1:25:25:"))
        assertEquals(watched,StremioWatchedField.decode(value,ids))
    }
    @Test fun insertedEpisodesShiftTheBitmapUsingTheAnchor() {
        val value=StremioWatchedField.encode(listOf("a","b","c"),setOf("b","c"))
        assertEquals(setOf("b","c"),StremioWatchedField.decode(value,listOf("new","a","b","c")))
    }
    @Test fun unknownAnchorsAndInvalidCompressionNeverGuessWatchedEpisodes() {
        assertEquals(emptySet<String>(),StremioWatchedField.decode(StremioWatchedField.encode(listOf("a"),setOf("a")),listOf("different")))
        assertNull(StremioWatchedField.decode("a:1:not-base64",listOf("a")))
        assertNull(StremioWatchedField.decode("a:999999999:abc",listOf("a")))
    }
}
