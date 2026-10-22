package com.streamvault.data.sync

import com.streamvault.domain.model.ProviderType

/** Stalker guide work is owned by its unique worker and guide mutex, independently of catalog leases. */
internal suspend fun executeBackgroundEpg(
    providerType: ProviderType?,
    workflow: suspend (suspend () -> ProviderWorkflowOutcome) -> ProviderWorkflowDisposition,
    sync: suspend () -> ProviderWorkflowOutcome
): ProviderWorkflowDisposition = if (providerType == ProviderType.STALKER_PORTAL) {
    when (val outcome = sync()) {
        is ProviderWorkflowOutcome.Success -> ProviderWorkflowDisposition.SUCCEEDED
        is ProviderWorkflowOutcome.Failure -> if (outcome.retryable) ProviderWorkflowDisposition.RETRY
            else ProviderWorkflowDisposition.FAILED
    }
} else {
    workflow(sync)
}
