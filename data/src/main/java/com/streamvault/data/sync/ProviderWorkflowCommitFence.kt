package com.streamvault.data.sync

import com.streamvault.data.local.dao.ProviderWorkflowDao
import com.streamvault.data.local.dao.ProviderWorkflowLease
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException

/**
 * Lease propagated through the sync coroutine so catalog commits can prove they still belong to
 * the generation that fetched and staged the data.
 */
class ProviderWorkflowExecutionContext(
    val lease: ProviderWorkflowLease
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProviderWorkflowExecutionContext>
}

class ProviderWorkflowSupersededException(
    providerId: Long,
    generation: Long
) : CancellationException(
    "Provider $providerId workflow generation $generation was superseded before catalog commit."
)

/**
 * Checks workflow ownership from inside the same Room transaction that applies staged catalog
 * rows. If supersession commits first, this check rejects the old apply. If this transaction
 * begins first, Room serialization makes supersession wait until its coherent commit completes.
 */
@Singleton
class ProviderWorkflowCommitFence @Inject constructor(
    private val workflowDao: ProviderWorkflowDao?
) {
    internal constructor() : this(null)

    suspend fun assertCanCommit(providerId: Long) {
        val execution = coroutineContext[ProviderWorkflowExecutionContext] ?: return
        val lease = execution.lease
        if (
            lease.providerId != providerId ||
            workflowDao?.isCurrentOwner(
                providerId = providerId,
                generation = lease.generation,
                phase = lease.phase,
                token = lease.token
            ) != 1
        ) {
            throw ProviderWorkflowSupersededException(providerId, lease.generation)
        }
    }
}
