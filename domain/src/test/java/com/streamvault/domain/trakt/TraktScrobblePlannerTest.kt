package com.streamvault.domain.trakt

import org.junit.Assert.*
import org.junit.Test

class TraktScrobblePlannerTest {
    private val movie = TraktMediaIdentity.MovieId(603)
    private val planner = TraktScrobblePlanner()
    private fun observe(phase: TraktPlaybackPhase, session: Long = 1, position: Long = 100,
        duration: Long = 1000, identity: TraktMediaIdentity? = movie) =
        planner.observe(session, identity, phase, position, duration)

    @Test fun repeatedPositionTicksAndBufferingDoNotSpamStartsOrPauses() {
        assertEquals(TraktScrobbleAction.START, observe(TraktPlaybackPhase.PLAYING).single().action)
        assertTrue(observe(TraktPlaybackPhase.PLAYING, position = 200).isEmpty())
        assertTrue(observe(TraktPlaybackPhase.BUFFERING).isEmpty())
        assertTrue(observe(TraktPlaybackPhase.PLAYING).isEmpty())
        assertEquals(TraktScrobbleAction.PAUSE, observe(TraktPlaybackPhase.PAUSED).single().action)
        assertTrue(observe(TraktPlaybackPhase.PAUSED).isEmpty())
        assertEquals(TraktScrobbleAction.START, observe(TraktPlaybackPhase.PLAYING).single().action)
    }

    @Test fun completionStopsOnceAndCannotRestartWithoutNewSession() {
        observe(TraktPlaybackPhase.PLAYING)
        assertEquals(100.0, observe(TraktPlaybackPhase.ENDED, position = 1000).single().progress, 0.001)
        assertTrue(observe(TraktPlaybackPhase.ENDED).isEmpty())
        assertTrue(observe(TraktPlaybackPhase.PLAYING).isEmpty())
        assertEquals(TraktScrobbleAction.START, observe(TraktPlaybackPhase.PLAYING, session = 2).single().action)
    }

    @Test fun switchingStopsPreviousIdentityBeforeStartingNextAndRejectsStaleSession() {
        observe(TraktPlaybackPhase.PLAYING, position = 350)
        val next = TraktMediaIdentity.EpisodeId(1396, 2, 3)
        val events = observe(TraktPlaybackPhase.PLAYING, session = 2, position = 20, identity = next)
        assertEquals(listOf(TraktScrobbleAction.STOP, TraktScrobbleAction.START), events.map { it.action })
        assertEquals(movie, events[0].media)
        assertEquals(35.0, events[0].progress, .001)
        assertEquals(next, events[1].media)
        assertTrue(observe(TraktPlaybackPhase.ENDED, session = 1).isEmpty())
        assertTrue(observe(TraktPlaybackPhase.PAUSED, session = 2, identity = movie).isEmpty())
    }

    @Test fun noStartForUnknownDurationOrUnidentifiedMediaButLateMetadataCanStart() {
        assertTrue(observe(TraktPlaybackPhase.PLAYING, duration = 0, identity = null).isEmpty())
        assertTrue(observe(TraktPlaybackPhase.PLAYING, duration = 0).isEmpty())
        assertEquals(0.0, observe(TraktPlaybackPhase.PLAYING, position = -10).single().progress, .001)
    }

    @Test fun switchingToLiveOrUnknownContentOnlyStopsOldItem() {
        assertTrue(observe(TraktPlaybackPhase.PAUSED).isEmpty())
        observe(TraktPlaybackPhase.PLAYING)
        assertEquals(TraktScrobbleAction.STOP,
            observe(TraktPlaybackPhase.PLAYING, session = 2, identity = null).single().action)
        assertTrue(observe(TraktPlaybackPhase.PLAYING, session = 2, identity = null).isEmpty())
    }
}
