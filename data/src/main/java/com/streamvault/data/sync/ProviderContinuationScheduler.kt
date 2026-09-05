package com.streamvault.data.sync

import com.streamvault.domain.model.ProviderSnapshot
import com.streamvault.domain.model.ProviderType

/** Maps provider-neutral continuation receipts onto the durable WorkManager scheduler. */
internal class ProviderContinuationScheduler(
    private val workScheduler: ProviderSyncWorkScheduler
) : SyncContinuationScheduler {
    override suspend fun schedule(snapshot: ProviderSnapshot, work: List<SyncContinuation>) {
        val providerId = snapshot.provider.id
        if (work.any { it.operation == SyncContinuationOperation.REFRESH_GUIDE }) {
            workScheduler.scheduleBackgroundEpg(providerId)
        }

        // A bootstrap-scoped initial sync hands the remainder (full live catalog, EPG) to a
        // durable full re-sync. With the live success timestamp left stale by the bootstrap,
        // the resume re-fetches the complete live catalog and EPG per the provider's mode.
        if (work.any { it.operation == SyncContinuationOperation.FULL_CATALOG }) {
            workScheduler.scheduleProviderResume(providerId)
        }

        val indexWork = work.filter { it.operation == SyncContinuationOperation.INDEX_CATALOG }
        when (snapshot.provider.type) {
            ProviderType.XTREAM_CODES -> indexWork
                .distinctBy { it.section }
                .forEach { continuation ->
                    workScheduler.scheduleXtreamIndex(
                        providerId = providerId,
                        section = continuation.section,
                        force = continuation.force
                    )
                }
            ProviderType.STALKER_PORTAL -> if (indexWork.isNotEmpty()) {
                workScheduler.scheduleStalkerIndex(
                    providerId = providerId,
                    force = indexWork.any { it.force }
                )
            }
            ProviderType.M3U,
            ProviderType.JELLYFIN -> check(indexWork.isEmpty()) {
                "${snapshot.provider.type} declared unsupported catalog index continuation work"
            }
        }
    }
}
