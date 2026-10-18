package com.streamvault.feature.playback.player

import com.streamvault.domain.trakt.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.kotlin.verify

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlayerTraktCoordinatorTest {
    private val movie = TraktMediaIdentity.MovieId(603)
    private val next = TraktMediaIdentity.EpisodeId(1396, 2, 3)
    private val account = mock<TraktAccount>()
    private val state = MutableStateFlow(TraktAccountState(configured = true, connected = true))
    init { whenever(account.state).thenReturn(state) }

    @Test fun `failed start cannot create watched history on completion`() = runTest {
        val events = mutableListOf<TraktScrobbleEvent>()
        val sender = object : TraktScrobbler {
            override suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome {
                events += event
                return TraktScrobbleOutcome.FAILED
            }
        }
        val coordinator = PlayerTraktCoordinator(account, sender, backgroundScope)
        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 900, 1000)
        runCurrent()
        coordinator.report(1, movie, TraktPlaybackPhase.ENDED, 1000, 1000)
        runCurrent()
        coordinator.close()
        runCurrent()
        assertEquals(listOf(TraktScrobbleAction.START), events.map { it.action })
    }

    @Test fun `turning off scrobbling pauses an active Trakt session without stopping it`() = runTest {
        val scrobbling = MutableStateFlow(true)
        val preferences = object : TraktFeaturePreferences {
            override val scrobblingEnabled = scrobbling
            override suspend fun setScrobblingEnabled(enabled: Boolean) { scrobbling.value = enabled }
            override val historySyncEnabled = MutableStateFlow(true)
            override suspend fun setHistorySyncEnabled(enabled: Boolean) = Unit
        }
        val events = mutableListOf<TraktScrobbleEvent>()
        val sender = object : TraktScrobbler {
            override suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome {
                events += event
                return TraktScrobbleOutcome.SENT
            }
        }
        val coordinator = PlayerTraktCoordinator(account, sender, preferences, backgroundScope)
        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 500, 1000)
        runCurrent()
        scrobbling.value = false
        runCurrent()

        assertEquals(listOf(TraktScrobbleAction.START, TraktScrobbleAction.PAUSE), events.map { it.action })
        assertEquals(PlayerTraktTrackingStatus.HIDDEN, coordinator.status.value)
        coordinator.close()
        runCurrent()
    }

    @Test fun `saved account loads without first opening Trakt settings`() = runTest {
        state.value = state.value.copy(connected = false)
        whenever(account.load()).thenAnswer {
            state.value = state.value.copy(connected = true)
            Unit
        }
        val events = mutableListOf<TraktScrobbleEvent>()
        val sender = object : TraktScrobbler {
            override suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome {
                events += event
                return TraktScrobbleOutcome.SENT
            }
        }
        val coordinator = PlayerTraktCoordinator(account, sender, backgroundScope)
        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 100, 1000)
        runCurrent()
        verify(account).load()
        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 100, 1000)
        runCurrent()
        assertEquals(TraktScrobbleAction.START, events.single().action)
        coordinator.close()
    }

    @Test fun `unexpected tracking failure cannot kill worker or cause a retry loop`() = runTest {
        val events = mutableListOf<TraktScrobbleEvent>()
        val sender = object : TraktScrobbler {
            override suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome {
                events += event
                if (events.size == 1) throw java.io.IOException("synthetic failure")
                return TraktScrobbleOutcome.SENT
            }
        }
        val coordinator = PlayerTraktCoordinator(account, sender, backgroundScope)
        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 100, 1000)
        runCurrent()
        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 200, 1000)
        runCurrent()
        assertEquals(1, events.size)
        coordinator.report(2, next, TraktPlaybackPhase.PLAYING, 300, 1000)
        runCurrent()
        assertEquals(2, events.size)
        assertEquals(next, events.last().media)
        assertEquals(TraktScrobbleAction.START, events.last().action)
        coordinator.close()
    }

    @Test fun `ticks do not queue writes and switch stops at last observed progress`() = runTest {
        val events = mutableListOf<TraktScrobbleEvent>()
        val sender = object : TraktScrobbler {
            override suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome {
                events += event
                return TraktScrobbleOutcome.SENT
            }
        }
        val coordinator = PlayerTraktCoordinator(account, sender, backgroundScope)
        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 10, 1000)
        runCurrent()
        repeat(500) { coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 900, 1000) }
        coordinator.report(2, next, TraktPlaybackPhase.PLAYING, 5, 1000)
        runCurrent()
        assertEquals(listOf(TraktScrobbleAction.START, TraktScrobbleAction.STOP, TraktScrobbleAction.START), events.map { it.action })
        assertEquals(90.0, events[1].progress, .001)
        coordinator.close()
        runCurrent()
        assertEquals(TraktScrobbleAction.STOP, events.last().action)
    }

    @Test fun `in flight request does not lose completion or start a stale intermediate item`() = runTest {
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<TraktScrobbleEvent>()
        val sender = object : TraktScrobbler {
            override suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome {
                events += event
                if (events.size == 1) release.await()
                return TraktScrobbleOutcome.SENT
            }
        }
        val coordinator = PlayerTraktCoordinator(account, sender, backgroundScope)
        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 100, 1000)
        runCurrent()
        coordinator.report(1, movie, TraktPlaybackPhase.ENDED, 1000, 1000)
        coordinator.report(2, next, TraktPlaybackPhase.PLAYING, 900, 1000)
        coordinator.report(3, movie, TraktPlaybackPhase.PLAYING, 0, 1000)
        release.complete(Unit)
        runCurrent()
        assertEquals(100.0, events.first { it.action == TraktScrobbleAction.STOP }.progress, .001)
        assertFalse("Skipped start must not produce a stop/history write", events.any { it.media == next })
        assertEquals(movie, events.last().media)
        assertEquals(TraktScrobbleAction.START, events.last().action)
        coordinator.close()
    }

    @Test fun `account connection during playback starts fresh while stale session is rejected`() = runTest {
        state.value = state.value.copy(connected = false)
        val events = mutableListOf<TraktScrobbleEvent>()
        val sender = object : TraktScrobbler {
            override suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome {
                events += event
                return TraktScrobbleOutcome.SENT
            }
        }
        val coordinator = PlayerTraktCoordinator(account, sender, backgroundScope)
        coordinator.report(10, movie, TraktPlaybackPhase.PLAYING, 100, 1000)
        runCurrent()
        assertTrue(events.isEmpty())
        state.value = state.value.copy(connected = true)
        coordinator.report(10, movie, TraktPlaybackPhase.PLAYING, 200, 1000)
        runCurrent()
        assertEquals(1, events.size)
        coordinator.report(9, next, TraktPlaybackPhase.PLAYING, 100, 1000)
        runCurrent()
        assertEquals(1, events.size)
        coordinator.close()
    }

    @Test fun `visible status confirms accepted start and pause events`() = runTest {
        val sender = object : TraktScrobbler {
            override suspend fun send(event: TraktScrobbleEvent) = TraktScrobbleOutcome.SENT
        }
        val coordinator = PlayerTraktCoordinator(account, sender, backgroundScope)

        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 100, 1000)
        runCurrent()
        assertEquals(PlayerTraktTrackingStatus.TRACKING, coordinator.status.value)

        coordinator.report(1, movie, TraktPlaybackPhase.PAUSED, 400, 1000)
        runCurrent()
        assertEquals(PlayerTraktTrackingStatus.PAUSED, coordinator.status.value)
        coordinator.close()
    }

    @Test fun `failed status stays visible and does not retry on position ticks`() = runTest {
        var sends = 0
        val sender = object : TraktScrobbler {
            override suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome {
                sends++
                return TraktScrobbleOutcome.FAILED
            }
        }
        val coordinator = PlayerTraktCoordinator(account, sender, backgroundScope)

        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 100, 1000)
        runCurrent()
        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 500, 1000)
        runCurrent()

        assertEquals(1, sends)
        assertEquals(PlayerTraktTrackingStatus.FAILED, coordinator.status.value)
        coordinator.close()
    }

    @Test fun `live playback explains unsupported tracking without sending a scrobble`() = runTest {
        val events = mutableListOf<TraktScrobbleEvent>()
        val sender = object : TraktScrobbler {
            override suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome {
                events += event
                return TraktScrobbleOutcome.SENT
            }
        }
        val coordinator = PlayerTraktCoordinator(account, sender, backgroundScope)

        coordinator.report(
            1,
            movie,
            TraktPlaybackPhase.PLAYING,
            100,
            1000,
            eligibility = TraktPlaybackEligibility.LIVE,
        )
        runCurrent()

        assertTrue(events.isEmpty())
        assertEquals(PlayerTraktTrackingStatus.NOT_SUPPORTED, coordinator.status.value)
        coordinator.close()
    }

    @Test fun `start is sent when duration becomes available after playback starts`() = runTest {
        val events = mutableListOf<TraktScrobbleEvent>()
        val sender = object : TraktScrobbler {
            override suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome {
                events += event
                return TraktScrobbleOutcome.SENT
            }
        }
        val coordinator = PlayerTraktCoordinator(account, sender, backgroundScope)

        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 0, 0)
        runCurrent()
        coordinator.report(1, movie, TraktPlaybackPhase.PLAYING, 100, 1000)
        runCurrent()

        assertEquals(listOf(TraktScrobbleAction.START), events.map { it.action })
        assertEquals(PlayerTraktTrackingStatus.TRACKING, coordinator.status.value)
        coordinator.close()
    }
}
