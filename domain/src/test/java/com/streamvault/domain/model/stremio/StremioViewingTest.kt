package com.streamvault.domain.model.stremio

import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

class StremioViewingTest {
    private val now = Instant.parse("2026-11-01T00:00:00Z")
    private fun history(id: String, at: Long, origin: String = "a".repeat(64)) = StremioPlaybackHistory(
        "stremio:global:series-episode:$id", "series", id, "tt123", id,
        lastWatchedAt = at, catalogOriginKey = origin)
    @Test fun releaseTimesAcceptOffsetsAndDateOnlyValues() {
        assertEquals(Instant.parse("2026-11-01T21:00:00Z"), StremioVideo("offset", released="2026-11-01T23:00:00+02:00").releaseInstant())
        assertEquals(Instant.parse("2026-11-01T00:00:00Z"), StremioVideo("date", released="2026-11-01").releaseInstant())
    }

    @Test fun groupsGlobalSeriesAcrossProvidersAndKeepsNewestEpisode() {
        val rows = latestStremioItems(listOf(history("tt123:1:1",1),history("tt123:1:2",2,"b".repeat(64))))
        assertEquals(1,rows.size); assertEquals("tt123:1:2",rows.single().exactVideoId)
    }
    @Test fun customSeriesFromDifferentProvidersStaySeparate() {
        val rows = listOf(history("a",1),history("b",2,"b".repeat(64))).map { it.copy(contentKey="stremio:addon:"+it.exactVideoId) }
        assertEquals(2,latestStremioItems(rows).size)
    }
    @Test fun skipsWatchedEpisodesButNeverSkipsAnUpcomingUnwatchedEpisode() {
        val meta = StremioMetaDetail("tt123",StremioMediaType.SERIES,"Series",videos = listOf(
            StremioVideo("a",season=1,episode=1), StremioVideo("b",season=1,episode=2),
            StremioVideo("c",season=1,episode=3,released="2026-10-31"),
            StremioVideo("d",season=1,episode=4,released="2026-11-02T00:00:00Z")))
        assertEquals("c",nextUnwatchedStremioVideo(meta,"a",setOf("a","b"),now)?.id)
        assertNull(nextUnwatchedStremioVideo(meta,"c",setOf("a","b","c"),now))
        assertNull(nextUnwatchedStremioVideo(meta,"missing",emptySet(),now))
    }
    @Test fun followDateAndDisabledNotificationsAreIndependentOfSavedMembership() {
        val type=StremioMediaType.SERIES
        val settings=mapOf(stremioLibraryKey(type,"id") to true,stremioFollowedKey(type,"id",123L) to true,
            stremioNotificationsKey(type,"id") to false)
        assertEquals(123L,stremioFollowedAt(settings,type,"id"))
        assertTrue(settings[stremioLibraryKey(type,"id")] == true)
    }
}
