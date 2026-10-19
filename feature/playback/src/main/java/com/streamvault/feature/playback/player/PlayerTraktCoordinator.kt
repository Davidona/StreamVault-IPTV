package com.streamvault.feature.playback.player

import com.streamvault.domain.trakt.*
import com.streamvault.domain.trakt.TraktFeaturePreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class TraktPlaybackEligibility { SUPPORTED, LIVE, CATCH_UP, OTHER }

enum class PlayerTraktTrackingStatus {
    HIDDEN,
    NOT_SUPPORTED,
    NO_MATCH,
    STARTING,
    TRACKING,
    PAUSED,
    FAILED,
    SENT,
}

/** One worker, a bounded transition queue, and no queued position ticks. */
class PlayerTraktCoordinator @Inject constructor(
    private val account: TraktAccount,
    private val scrobbler: TraktScrobbler,
    private val featurePreferences: TraktFeaturePreferences,
    scope: CoroutineScope,
) {
    constructor(account: TraktAccount, scrobbler: TraktScrobbler, scope: CoroutineScope) : this(
        account,
        scrobbler,
        object : TraktFeaturePreferences {
            override val scrobblingEnabled = flowOf(true)
            override suspend fun setScrobblingEnabled(enabled: Boolean) = Unit
            override val historySyncEnabled = flowOf(true)
            override suspend fun setHistorySyncEnabled(enabled: Boolean) = Unit
        },
        scope,
    )

    private data class Observation(val generation: Long, val identity: TraktMediaIdentity?,
        val phase: TraktPlaybackPhase, val position: Long, val duration: Long)
    private val observations = Channel<Observation>(32, BufferOverflow.DROP_OLDEST)
    private var sourceSession = Long.MIN_VALUE
    private var connected = false
    @Volatile private var scrobblingEnabled = true
    private var generation = 0L
    @Volatile private var latest: Observation? = null
    @Volatile private var remoteMedia: TraktMediaIdentity? = null
    private val mutableStatus = MutableStateFlow(PlayerTraktTrackingStatus.HIDDEN)
    val status: StateFlow<PlayerTraktTrackingStatus> = mutableStatus.asStateFlow()
    private val planner = TraktScrobblePlanner()
    private var terminalStatus: PlayerTraktTrackingStatus? = null
    private val preferenceJob: Job

    init {
        preferenceJob = scope.launch {
            featurePreferences.scrobblingEnabled.collect { enabled ->
                val previous = scrobblingEnabled
                scrobblingEnabled = enabled
                if (previous && !enabled) synchronized(this@PlayerTraktCoordinator) {
                    latest?.let { active ->
                        val paused = active.copy(phase = TraktPlaybackPhase.STOPPED)
                        latest = paused
                        observations.trySend(paused)
                        mutableStatus.value = PlayerTraktTrackingStatus.HIDDEN
                    }
                }
            }
        }
        scope.launch {
            try {
                account.load()
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
            }
            for (observation in observations) {
                val events = planner.observe(observation.generation, observation.identity,
                    observation.phase, observation.position, observation.duration)
                for (event in events) {
                    if (event.action != TraktScrobbleAction.STOP && latest?.generation != observation.generation) continue
                    // A queued start may still be waiting behind another request when the user
                    // disables scrobbling. Do not begin a new remote session after that choice.
                    if (!scrobblingEnabled && event.action == TraktScrobbleAction.START) continue
                    // A skipped/failed start must not later create watched history via stop.
                    if (event.action != TraktScrobbleAction.START && remoteMedia != event.media) continue
                    // Turning tracking off ends an active remote session without making a stop
                    // request that could turn a high-progress title into watched history.
                    val outgoing = if (!scrobblingEnabled && event.action == TraktScrobbleAction.STOP) {
                        event.copy(action = TraktScrobbleAction.PAUSE)
                    } else event
                    val outcome = try {
                        scrobbler.send(outgoing)
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        // Optional tracking must never crash playback or retry an ambiguous write.
                        TraktScrobbleOutcome.FAILED
                    }
                    if (event.action == TraktScrobbleAction.START && outcome == TraktScrobbleOutcome.SENT) {
                        remoteMedia = event.media
                    }
                    if (event.action == TraktScrobbleAction.STOP) remoteMedia = null
                    publishOutcome(observation.generation, event.action, outcome)
                }
            }
        }
    }

    @Synchronized
    fun report(session: Long, identity: TraktMediaIdentity?, phase: TraktPlaybackPhase,
        positionMs: Long, durationMs: Long,
        eligibility: TraktPlaybackEligibility = TraktPlaybackEligibility.SUPPORTED) {
        if (session < sourceSession) return
        val isConnected = account.state.value.connected
        val isTrackingEnabled = scrobblingEnabled
        val previous = latest
        val changedSession = session != sourceSession || connected != isConnected
        if (changedSession) {
            // Preserve the most recent old position before accepting a new item's position.
            previous?.copy(phase = if (previous.phase == TraktPlaybackPhase.ENDED)
                TraktPlaybackPhase.ENDED else TraktPlaybackPhase.STOPPED)?.let { observations.trySend(it) }
            generation++
            terminalStatus = null
        }
        sourceSession = session
        connected = isConnected
        val observation = Observation(
            generation,
            identity.takeIf { isConnected && isTrackingEnabled && eligibility == TraktPlaybackEligibility.SUPPORTED },
            phase,
            positionMs,
            durationMs,
        )
        latest = observation
        updateVisibleStatus(identity, phase, durationMs, isConnected, eligibility, isTrackingEnabled)
        val durationBecameAvailable = (previous?.duration ?: 0L) <= 0L && durationMs > 0L
        if (changedSession || previous?.identity != observation.identity || previous?.phase != phase || durationBecameAvailable) {
            observations.trySend(observation)
        }
    }

    @Synchronized
    private fun updateVisibleStatus(
        identity: TraktMediaIdentity?,
        phase: TraktPlaybackPhase,
        durationMs: Long,
        isConnected: Boolean,
        eligibility: TraktPlaybackEligibility,
        isTrackingEnabled: Boolean,
    ) {
        val next = when {
            // Trakt is optional; account setup belongs in settings, never over playback.
            !isTrackingEnabled || !isConnected -> PlayerTraktTrackingStatus.HIDDEN
            eligibility != TraktPlaybackEligibility.SUPPORTED -> PlayerTraktTrackingStatus.NOT_SUPPORTED
            identity == null -> PlayerTraktTrackingStatus.NO_MATCH
            terminalStatus != null -> terminalStatus!!
            phase == TraktPlaybackPhase.STOPPED || phase == TraktPlaybackPhase.ENDED -> mutableStatus.value
            remoteMedia == identity && phase == TraktPlaybackPhase.PAUSED -> PlayerTraktTrackingStatus.PAUSED
            remoteMedia == identity -> PlayerTraktTrackingStatus.TRACKING
            phase == TraktPlaybackPhase.PLAYING && durationMs > 0L -> PlayerTraktTrackingStatus.STARTING
            phase == TraktPlaybackPhase.BUFFERING -> PlayerTraktTrackingStatus.STARTING
            else -> PlayerTraktTrackingStatus.HIDDEN
        }
        mutableStatus.value = next
    }

    @Synchronized
    private fun publishOutcome(
        eventGeneration: Long,
        action: TraktScrobbleAction,
        outcome: TraktScrobbleOutcome,
    ) {
        if (generation != eventGeneration) return
        if (!scrobblingEnabled || !account.state.value.connected) {
            mutableStatus.value = PlayerTraktTrackingStatus.HIDDEN
            return
        }
        val next = when (outcome) {
            TraktScrobbleOutcome.SENT -> when (action) {
                TraktScrobbleAction.START -> PlayerTraktTrackingStatus.TRACKING
                TraktScrobbleAction.PAUSE -> PlayerTraktTrackingStatus.PAUSED
                TraktScrobbleAction.STOP -> PlayerTraktTrackingStatus.SENT
            }
            TraktScrobbleOutcome.DUPLICATE -> PlayerTraktTrackingStatus.SENT
            TraktScrobbleOutcome.DISCONNECTED -> PlayerTraktTrackingStatus.HIDDEN
            TraktScrobbleOutcome.UNMATCHED -> PlayerTraktTrackingStatus.NO_MATCH
            TraktScrobbleOutcome.FAILED -> PlayerTraktTrackingStatus.FAILED
        }
        if (next == PlayerTraktTrackingStatus.FAILED ||
            next == PlayerTraktTrackingStatus.NO_MATCH ||
            next == PlayerTraktTrackingStatus.HIDDEN) {
            terminalStatus = next
        }
        mutableStatus.value = next
    }

    @Synchronized
    fun close() {
        preferenceJob.cancel()
        latest?.copy(phase = TraktPlaybackPhase.STOPPED)?.let { observations.trySend(it) }
        observations.close()
    }
}
