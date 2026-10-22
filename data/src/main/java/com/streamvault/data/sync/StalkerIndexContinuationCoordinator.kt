package com.streamvault.data.sync

import com.streamvault.data.local.entity.CategoryEntity
import com.streamvault.data.remote.stalker.StalkerProvider
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.LegacyProvider as Provider
import com.streamvault.domain.model.ProviderEpgSyncMode
import com.streamvault.domain.model.ProviderType
import com.streamvault.domain.model.StalkerCatalogMode
import com.streamvault.domain.model.StalkerIndexState
import com.streamvault.data.remote.stalker.StalkerAdvancedOptionsCodec

/**
 * Owns durable Stalker index continuation decisions around the section executor.
 *
 * The section executor commits pages; this collaborator decides whether a section is runnable,
 * which successor should be scheduled, when retry delay should be applied, and how startup
 * reconciles persisted Stalker work with the configured catalog mode.
 */
internal class StalkerIndexContinuationCoordinator(
    private val stalkerIndexJobStore: StalkerIndexJobStore,
    private val loadCategories: suspend (Long, ContentType) -> List<CategoryEntity>,
    private val loadHydration: suspend (Long, ContentType, Long) -> StalkerHydrationSnapshot?,
    private val visibleCategories: suspend (
        Long,
        ContentType,
        List<CategoryEntity>,
        StalkerProvider
    ) -> List<CategoryEntity>,
    private val loadProvider: suspend (Long) -> Provider?,
    private val loadStalkerProviders: suspend () -> List<Long>,
    private val deleteLegacyJobs: suspend (Long) -> Unit,
    private val cancelIndex: (Long) -> Unit,
    private val scheduleIndex: (Long, Long, Boolean) -> Unit,
    private val scheduleBackgroundEpg: (Long) -> Unit,
    private val log: (String) -> Unit = {},
    private val guideRowCount: suspend (Long) -> Int = { 1 },
    private val recoverBackgroundEpg: (Long) -> Unit = scheduleBackgroundEpg
) {
    suspend fun chooseNextSection(
        provider: Provider,
        api: StalkerProvider,
        requestedSection: ContentType?,
        force: Boolean,
        now: Long
    ): StalkerCatalogDecision {
        val movie = sectionState(provider, api, ContentType.MOVIE, force, now)
        val series = if (provider.catalogLayout == com.streamvault.domain.model.CatalogLayout.UNIFIED_VOD) {
            val job = stalkerIndexJobStore.get(provider.id, ContentType.SERIES)
            StalkerCatalogSectionState(
                contentType = ContentType.SERIES,
                runnable = false,
                retryDelaySeconds = 0L,
                pending = false,
                jobState = job?.state?.let(stalkerIndexJobStore::toLegacyState),
                updatedAt = job?.updatedAt ?: 0L
            )
        } else {
            sectionState(provider, api, ContentType.SERIES, force, now)
        }
        val order = StalkerAdvancedOptionsCodec.decode(provider.stalkerAdvancedOptionsJson).catalogLoadOrder
        return StalkerIndexPolicy.chooseNextSection(
            layout = provider.catalogLayout,
            requestedSection = requestedSection,
            movie = movie,
            series = series,
            order = order
        )
    }

    suspend fun isSectionPending(provider: Provider, api: StalkerProvider, type: ContentType): Boolean =
        sectionState(provider, api, type, false, System.currentTimeMillis()).pending

    suspend fun scheduleNextSection(provider: Provider, api: StalkerProvider) {
        val decision = chooseNextSection(
            provider = provider,
            api = api,
            requestedSection = null,
            force = false,
            now = System.currentTimeMillis()
        )
        when {
            decision.contentType != null -> {
                log("Scheduling next Stalker catalog step for provider ${provider.id}: ${decision.contentType.name}")
                scheduleIndex(provider.id, 0L, true)
            }
            decision.retryDelaySeconds > 0L -> {
                log("Scheduling Stalker catalog retry for provider ${provider.id} in ${decision.retryDelaySeconds}s")
                scheduleIndex(provider.id, decision.retryDelaySeconds, true)
            }
        }
    }

    fun scheduleEpgIfCatalogIdle(provider: Provider) {
        // Automatic guide freshness is owned by the shared planner, independently of indexing.
        if (AutomaticSyncExecution.automatic.get() == true) return
        if (
            provider.type != ProviderType.STALKER_PORTAL ||
            provider.epgSyncMode != ProviderEpgSyncMode.BACKGROUND
        ) return
        log("Scheduling Stalker EPG independently of catalog indexing for provider ${provider.id}.")
        scheduleBackgroundEpg(provider.id)
    }

    suspend fun reconcileAtStartup() {
        loadStalkerProviders().forEach { providerId ->
            val provider = loadProvider(providerId) ?: return@forEach
            // v62 and older reused Xtream rows. They are never valid Stalker owners now.
            deleteLegacyJobs(provider.id)
            val sections = if (provider.catalogLayout == com.streamvault.domain.model.CatalogLayout.UNIFIED_VOD) {
                listOf(ContentType.MOVIE)
            } else {
                listOf(ContentType.MOVIE, ContentType.SERIES)
            }
            val hasPendingOneTime = sections.any { section ->
                stalkerIndexJobStore.get(provider.id, section)?.state in setOf(
                    StalkerIndexState.QUEUED,
                    StalkerIndexState.RUNNING,
                    StalkerIndexState.RETRY_WAIT,
                    StalkerIndexState.PARTIAL
                )
            }
            if (provider.stalkerCatalogMode == StalkerCatalogMode.ON_DEMAND && !hasPendingOneTime) {
                cancelIndex(provider.id)
            } else if (provider.stalkerCatalogMode == StalkerCatalogMode.BACKGROUND_INDEX || hasPendingOneTime) {
                scheduleIndex(provider.id, 0L, false)
            }
            if (hasPendingOneTime && provider.epgSyncMode != ProviderEpgSyncMode.SKIP && guideRowCount(provider.id) == 0) {
                // Replace retries backed off by the old shared catalog lease on upgrade/startup.
                recoverBackgroundEpg(provider.id)
            }
        }
    }

    private suspend fun sectionState(
        provider: Provider,
        api: StalkerProvider,
        contentType: ContentType,
        force: Boolean,
        now: Long
    ): StalkerCatalogSectionState {
        val automaticPolicy = AutomaticSyncExecution.policy.get()
        if (AutomaticSyncExecution.automatic.get() == true && automaticPolicy != null &&
            (!automaticPolicy.enabled || (contentType == ContentType.MOVIE && !automaticPolicy.movies) ||
                (contentType == ContentType.SERIES && !automaticPolicy.series))) {
            return StalkerCatalogSectionState(contentType, false, 0L, false, "DISABLED", now)
        }
        val job = stalkerIndexJobStore.get(provider.id, contentType)
        if (!force && !stalkerIndexJobStore.shouldRunSummary(job)) {
            return StalkerCatalogSectionState(
                contentType = contentType,
                runnable = false,
                retryDelaySeconds = 0L,
                pending = false,
                jobState = job?.state?.let(stalkerIndexJobStore::toLegacyState),
                updatedAt = job?.updatedAt ?: 0L
            )
        }

        val categories = loadCategories(provider.id, contentType)
        val visible = visibleCategories(provider.id, contentType, categories, api)
        if (visible.isEmpty()) {
            return StalkerCatalogSectionState(
                contentType = contentType,
                runnable = true,
                retryDelaySeconds = 0L,
                pending = true,
                jobState = job?.state?.let(stalkerIndexJobStore::toLegacyState),
                updatedAt = job?.updatedAt ?: 0L
            )
        }

        val hydrations = visible.map { category ->
            loadHydration(provider.id, contentType, category.categoryId)
        }
        val hasAttemptableWork = hydrations.any { hydration ->
            StalkerIndexPolicy.canAttempt(hydration, now)
        }
        val retryDelaySeconds = StalkerIndexPolicy.nextRetryDelaySeconds(hydrations, now)
        val hasUnresolvedWork = hydrations.any { hydration ->
            hydration == null ||
                (!hydration.isComplete && !hydration.isTruncated && !hydration.isTerminalFailure && hydration.retryBudgetRemaining > 0)
        }
        val needsStateReconciliation = job?.state in setOf(
            StalkerIndexState.QUEUED,
            StalkerIndexState.RUNNING,
            StalkerIndexState.RETRY_WAIT
        ) && !hasUnresolvedWork
        return StalkerCatalogSectionState(
            contentType = contentType,
            runnable = hasAttemptableWork || needsStateReconciliation,
            retryDelaySeconds = retryDelaySeconds,
            pending = hasUnresolvedWork || needsStateReconciliation,
            jobState = job?.state?.let(stalkerIndexJobStore::toLegacyState),
            updatedAt = job?.updatedAt ?: 0L
        )
    }
}
