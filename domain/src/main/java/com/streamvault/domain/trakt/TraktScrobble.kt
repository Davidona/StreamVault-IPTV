package com.streamvault.domain.trakt

enum class TraktScrobbleAction { START, PAUSE, STOP }
data class TraktScrobbleEvent(
    val media: TraktMediaIdentity,
    val action: TraktScrobbleAction,
    val progress: Double,
) {
    init { require(progress.isFinite() && progress in 0.0..100.0) }
}
enum class TraktScrobbleOutcome { SENT, DISCONNECTED, UNMATCHED, DUPLICATE, FAILED }
interface TraktScrobbler {
    suspend fun send(event: TraktScrobbleEvent): TraktScrobbleOutcome
}

enum class TraktPlaybackPhase { PLAYING, PAUSED, BUFFERING, STOPPED, ENDED }

/** Session numbers must increase when media changes, including replaying the same title. */
class TraktScrobblePlanner {
    private var session = Long.MIN_VALUE
    private var media: TraktMediaIdentity? = null
    private var lastAction: TraktScrobbleAction? = null
    private var progress = 0.0
    private var closed = false

    fun observe(sessionId: Long, identity: TraktMediaIdentity?, phase: TraktPlaybackPhase,
        positionMs: Long, durationMs: Long): List<TraktScrobbleEvent> {
        if (sessionId < session) return emptyList()
        val events = mutableListOf<TraktScrobbleEvent>()
        if (sessionId > session) {
            if (!closed && lastAction != null) media?.let {
                events += TraktScrobbleEvent(it, TraktScrobbleAction.STOP, progress)
            }
            session = sessionId
            media = identity
            lastAction = null
            progress = 0.0
            closed = false
        }
        if (media == null && lastAction == null && identity != null) media = identity
        if (closed || identity == null || media != identity) return events
        if (durationMs > 0) progress = (positionMs.coerceAtLeast(0).toDouble() / durationMs * 100).coerceIn(0.0, 100.0)
        val action = when (phase) {
            TraktPlaybackPhase.PLAYING -> if (durationMs > 0) TraktScrobbleAction.START else null
            TraktPlaybackPhase.PAUSED -> if (lastAction != null) TraktScrobbleAction.PAUSE else null
            TraktPlaybackPhase.BUFFERING -> null
            TraktPlaybackPhase.STOPPED, TraktPlaybackPhase.ENDED -> {
                closed = true
                if (lastAction != null) TraktScrobbleAction.STOP else null
            }
        }
        if (action != null && action != lastAction) {
            events += TraktScrobbleEvent(identity, action, progress)
            lastAction = action
        }
        return events
    }
}
