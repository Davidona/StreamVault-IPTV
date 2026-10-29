package com.streamvault.data.sync

import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

internal fun canYieldInflightGuide(providerType: ProviderType?, userInitiated: Boolean): Boolean =
    // M3U XMLTV has an atomic staged swap. Xtream job metadata and Stalker incremental
    // writes need separate interruption coverage before enabling this for those paths.
    !userInitiated && providerType == ProviderType.M3U

/** Cancel only the import child, allowing the workflow owner to release its lease normally. */
internal suspend fun runGuideWithPlaybackYield(
    enabled: Boolean,
    shouldDefer: Flow<Boolean>,
    sync: suspend () -> ProviderWorkflowOutcome
): ProviderWorkflowOutcome {
    if (!enabled) return sync()
    return supervisorScope {
        val viewing = async(start = CoroutineStart.UNDISPATCHED) {
            if (shouldDefer.firstOrNull { it } != true) awaitCancellation()
        }
        val importing = async { sync() }
        try {
            select {
                importing.onAwait { it }
                viewing.onAwait {
                    importing.cancelAndJoin()
                    ProviderWorkflowOutcome.Deferred
                }
            }
        } finally {
            withContext(NonCancellable) {
                viewing.cancelAndJoin()
                importing.cancelAndJoin()
            }
        }
    }
}

/** Freshness overrides do not imply user intent: background repair still yields to viewing. */
internal fun shouldDeferBackgroundEpgForPlayback(
    userInitiated: Boolean,
    deferWhileBusy: Boolean,
    playbackActive: Boolean
): Boolean = !userInitiated && deferWhileBusy && playbackActive

/** Stalker guide work is owned by its unique worker and guide mutex, independently of catalog leases. */
internal suspend fun executeBackgroundEpg(
    providerType: ProviderType?,
    workflow: suspend (suspend () -> ProviderWorkflowOutcome) -> ProviderWorkflowDisposition,
    sync: suspend () -> ProviderWorkflowOutcome
): ProviderWorkflowDisposition = if (providerType == ProviderType.STALKER_PORTAL) {
    when (val outcome = sync()) {
        ProviderWorkflowOutcome.Deferred -> ProviderWorkflowDisposition.RETRY
        is ProviderWorkflowOutcome.Success -> ProviderWorkflowDisposition.SUCCEEDED
        is ProviderWorkflowOutcome.Failure -> if (outcome.retryable) ProviderWorkflowDisposition.RETRY
            else ProviderWorkflowDisposition.FAILED
    }
} else {
    workflow(sync)
}
