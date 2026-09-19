package com.streamvault.feature.playback.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the lifetime of one player preparation/session.
 *
 * Session work must be launched through this coordinator. Starting a new session cancels the
 * previous session's child scope, while the id remains available for guarding callbacks that
 * complete outside of the coroutine hierarchy.
 */
internal class PlaybackSessionCoordinator(
    private val parentScope: CoroutineScope
) {
    class Session internal constructor(
        val id: Long,
        internal val scope: CoroutineScope
    )

    private val nextId = AtomicLong()

    @Volatile
    private var activeSession: Session? = null

    val currentId: Long
        get() = activeSession?.id ?: 0L

    @Synchronized
    fun begin(): Session {
        activeSession?.scope?.cancel()
        val sessionJob = SupervisorJob(parentScope.coroutineContext[Job])
        val scopedSession = Session(
            id = nextId.incrementAndGet(),
            scope = CoroutineScope(parentScope.coroutineContext + sessionJob)
        )
        activeSession = scopedSession
        return scopedSession
    }

    fun isCurrent(id: Long): Boolean {
        val session = activeSession
        return session?.id == id && session.scope.isActive
    }

    fun scope(id: Long): CoroutineScope? {
        val session = activeSession
        if (session?.id != id || !session.scope.isActive) return null
        return session.scope
    }

    /**
     * Launches work in the current session scope. A stale session returns no job and cannot
     * accidentally restart work after a newer session has replaced it.
     */
    fun launch(id: Long, block: suspend () -> Unit): Job? {
        val session = activeSession ?: return null
        if (session.id != id || !session.scope.isActive) return null
        return session.scope.launch { block() }
    }

    @Synchronized
    fun invalidate() {
        activeSession?.scope?.cancel()
        activeSession = null
        nextId.incrementAndGet()
    }
}
