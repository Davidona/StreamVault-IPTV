package com.streamvault.feature.playback.player

import com.streamvault.domain.trakt.*
import com.streamvault.player.PlaybackState
import org.junit.Assert.*
import org.junit.Test

class PlayerTraktSupportTest {
    @Test fun `engine recovery preserves session and allows playback to resume`() {
        val planner = TraktScrobblePlanner()
        val movie = TraktMediaIdentity.MovieId(603)
        fun observe(state: PlaybackState, playing: Boolean) = planner.observe(
            1, movie, traktPlaybackPhase(state, playing), 500, 1000)
        assertEquals(TraktScrobbleAction.START, observe(PlaybackState.READY, true).single().action)
        assertTrue(observe(PlaybackState.IDLE, false).isEmpty())
        assertTrue(observe(PlaybackState.BUFFERING, false).isEmpty())
        assertEquals(TraktScrobbleAction.PAUSE, observe(PlaybackState.ERROR, false).single().action)
        assertEquals(TraktScrobbleAction.START, observe(PlaybackState.READY, true).single().action)
        assertEquals(TraktScrobbleAction.STOP, observe(PlaybackState.ENDED, false).single().action)
    }
}
