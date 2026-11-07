package com.streamvault.domain.repository

import com.streamvault.domain.model.LiveChannelOrder
import com.streamvault.domain.model.LiveChannelOrderScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

interface LiveChannelOrderRepository {
    /** Atomically copy and redirect a legacy source; safe to retry. */
    suspend fun migrateSourceIdentity(oldSource: String, newSource: String): Unit = throw UnsupportedOperationException()
    fun observe(scope: LiveChannelOrderScope): Flow<LiveChannelOrder>
    /** Distinguishes an absent legacy scope from an explicit Use default override. */
    suspend fun hasSavedOrder(scope: LiveChannelOrderScope): Boolean = false
    /** Atomically installs an initial migrated scope only when another writer has not saved one. */
    suspend fun initializeIfAbsent(scope: LiveChannelOrderScope, order: LiveChannelOrder): LiveChannelOrder =
        observe(scope).first()

    /** Atomic compare-and-save. Unchanged saves do not increment the revision. */
    suspend fun save(scope: LiveChannelOrderScope, expectedRevision: Long, order: LiveChannelOrder): LiveChannelOrderSaveResult

    /** Reconcile only from a successful complete provider catalog snapshot. */
    suspend fun reconcileCompleteCatalog(sourceKey: String, completeMembershipByCategory: Map<String, List<String>>)
}

sealed interface LiveChannelOrderSaveResult {
    data class Saved(val order: LiveChannelOrder) : LiveChannelOrderSaveResult
    data class Conflict(val current: LiveChannelOrder) : LiveChannelOrderSaveResult
}
