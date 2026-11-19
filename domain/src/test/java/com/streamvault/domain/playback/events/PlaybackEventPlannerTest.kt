package com.streamvault.domain.playback.events

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class PlaybackEventPlannerTest {
    private val movie = PlaybackMedia(PlaybackMediaType.MOVIE, "Movie", ids = PlaybackExternalIds(tmdb = 603))
    private val planner = PlaybackEventPlanner()
    private fun sample(time: Long = 0, position: Long = time, phase: PlaybackEventPhase = PlaybackEventPhase.PLAYING,
        generation: Long = 1, media: PlaybackMedia = movie, duration: Long? = 100_000, seek: Boolean = false) =
        PlaybackObservation(generation, media, phase, position, duration, time,
            Instant.EPOCH.plusMillis(time), seek)

    @Test fun `buffering and repeated ticks do not create transitions`() {
        assertTrue(planner.observe(sample(phase = PlaybackEventPhase.BUFFERING)).isEmpty())
        assertEquals(PlaybackEventType.START, planner.observe(sample(1)).single().event)
        assertTrue(planner.observe(sample(1000)).isEmpty())
        assertTrue(planner.observe(sample(2000, phase = PlaybackEventPhase.BUFFERING)).isEmpty())
        assertTrue(planner.observe(sample(3000)).isEmpty())
        assertEquals(PlaybackEventType.PAUSE, planner.observe(sample(4000, phase = PlaybackEventPhase.PAUSED)).single().event)
        assertTrue(planner.observe(sample(5000, phase = PlaybackEventPhase.PAUSED)).isEmpty())
        assertEquals(PlaybackEventType.RESUME, planner.observe(sample(6000)).single().event)
    }

    @Test fun `progress has stable session ordered unique events and no catchup burst`() {
        val start = planner.observe(sample()).single()
        assertTrue(planner.observe(sample(29_999)).isEmpty())
        val progress = planner.observe(sample(30_000)).single()
        assertEquals(PlaybackEventType.PROGRESS, progress.event)
        assertEquals(start.sessionId, progress.sessionId)
        assertNotEquals(start.eventId, progress.eventId)
        assertEquals(start.sequence + 1, progress.sequence)
        assertEquals(1, planner.observe(sample(80_000)).size)
        assertTrue(planner.observe(sample(80_001)).isEmpty())
    }

    @Test fun `seek and opening above threshold do not immediately mark watched`() {
        assertFalse(planner.observe(sample(position = 95_000)).single().watched)
        assertTrue(planner.observe(sample(1000, position = 99_000, seek = true)).isEmpty())
        val watched = planner.observe(sample(2000, position = 99_500)).single()
        assertEquals(PlaybackEventType.WATCHED, watched.event)
        assertEquals(1000L, watched.playedMs)
        assertTrue(planner.observe(sample(3000, position = 5000, seek = true)).isEmpty())
        assertTrue(planner.observe(sample(4000, position = 99_999)).isEmpty())
        assertTrue(planner.observe(sample(5000, phase = PlaybackEventPhase.STOPPED)).single().watched)
    }

    @Test fun `completion emits watched before stop once and remains closed`() {
        planner.observe(sample())
        val events = planner.observe(sample(1000, position = 100_000, phase = PlaybackEventPhase.ENDED))
        assertEquals(listOf(PlaybackEventType.WATCHED, PlaybackEventType.STOP), events.map { it.event })
        assertEquals(PlaybackStopReason.COMPLETED, events.last().stopReason)
        assertTrue(events.last().watched)
        assertTrue(planner.observe(sample(2000)).isEmpty())
        assertTrue(planner.observe(sample(3000, phase = PlaybackEventPhase.ENDED)).isEmpty())
    }

    @Test fun `replacement preserves previous snapshot and stale callbacks are ignored`() {
        val start = planner.observe(sample(position = 20_000)).single()
        val episode = PlaybackMedia(PlaybackMediaType.EPISODE, "Pilot", "Show", 0, 1,
            seriesIds = PlaybackExternalIds(tmdb = 1396))
        val events = planner.observe(sample(1000, position = 0, generation = 2, media = episode))
        assertEquals(listOf(PlaybackEventType.STOP, PlaybackEventType.START), events.map { it.event })
        assertEquals(movie, events.first().media)
        assertEquals(20_000L, events.first().positionMs)
        assertEquals(start.sessionId, events.first().sessionId)
        assertEquals(PlaybackStopReason.REPLACED, events.first().stopReason)
        assertNotEquals(start.sessionId, events.last().sessionId)
        assertNull(events.last().media.ids.tmdb)
        assertEquals(1396L, events.last().media.seriesIds.tmdb)
        assertTrue(planner.observe(sample(2000, phase = PlaybackEventPhase.STOPPED)).isEmpty())
    }

    @Test fun `unknown metadata and duration do not block start and enrichment does not restart`() {
        val unidentified = movie.copy(ids = PlaybackExternalIds())
        assertEquals(PlaybackEventType.START, planner.observe(sample(media = unidentified, duration = null)).single().event)
        assertTrue(planner.observe(sample(1000, duration = null)).isEmpty())
        assertTrue(planner.observe(sample(2000)).isEmpty())
        val stop = planner.observe(sample(3000, phase = PlaybackEventPhase.STOPPED)).single()
        assertEquals(603L, stop.media.ids.tmdb)
        assertFalse(stop.watched)
    }

    @Test fun `live ignores reported duration and never emits watched`() {
        val live = PlaybackMedia(PlaybackMediaType.LIVE, "Channel")
        val start = planner.observe(sample(media = live, position = 99_000)).single()
        assertNull(start.durationMs)
        assertNull(start.progressPercent)
        assertEquals(0L, start.positionMs)
        val progress = planner.observe(sample(30_000, position = 130_000, media = live)).single()
        assertEquals(PlaybackEventType.PROGRESS, progress.event)
        assertEquals(30_000L, progress.positionMs)
        assertFalse(progress.watched)
    }

    @Test fun `played time excludes paused buffering and seek intervals`() {
        planner.observe(sample())
        planner.observe(sample(1000, phase = PlaybackEventPhase.PAUSED))
        planner.observe(sample(11_000))
        planner.observe(sample(12_000, phase = PlaybackEventPhase.BUFFERING))
        planner.observe(sample(22_000))
        planner.observe(sample(23_000, position = 70_000, seek = true))
        val stop = planner.observe(sample(24_000, position = 71_000, phase = PlaybackEventPhase.STOPPED)).single()
        assertEquals(3000L, stop.playedMs)
    }

    @Test fun `failed preparation never creates a stop and replay starts fresh`() {
        assertTrue(planner.observe(sample(phase = PlaybackEventPhase.FAILED)).isEmpty())
        assertTrue(planner.observe(sample(1000)).isEmpty())
        assertEquals(PlaybackEventType.START, planner.observe(sample(2000, generation = 2)).single().event)
        assertEquals(PlaybackStopReason.ERROR,
            planner.observe(sample(3000, generation = 2, phase = PlaybackEventPhase.FAILED)).single().stopReason)
    }

    @Test fun `progress can be disabled and watched threshold configured`() {
        val custom = PlaybackEventPlanner(PlaybackEventPolicy(0, 95))
        custom.observe(sample())
        assertTrue(custom.observe(sample(90_000)).isEmpty())
        assertEquals(PlaybackEventType.WATCHED, custom.observe(sample(95_000)).single().event)
    }

    @Test fun `clock regression cannot corrupt current snapshot`() {
        planner.observe(sample(1000))
        assertTrue(planner.observe(sample(0, phase = PlaybackEventPhase.STOPPED)).isEmpty())
        assertEquals(PlaybackEventType.PAUSE,
            planner.observe(sample(2000, phase = PlaybackEventPhase.PAUSED)).single().event)
    }

    @Test fun `final advancement on user exit is included in completion`() {
        planner.observe(sample(position = 89_000))
        val events = planner.observe(sample(1000, position = 90_000, phase = PlaybackEventPhase.STOPPED))
        assertEquals(listOf(PlaybackEventType.WATCHED, PlaybackEventType.STOP), events.map { it.event })
        assertTrue(events.last().watched)
        assertEquals(1000L, events.last().playedMs)
    }
}
