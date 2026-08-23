package com.streamvault.data.repository

import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.domain.model.Category
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Application-lifetime cache for the live category observation flow of each provider.
 *
 * Room remains the source of truth. The cache only shares the active upstream work and
 * retains the latest result for screen re-entry.
 */
@Singleton
class ChannelCategoryFlowCache @Inject constructor(
    providerDao: ProviderDao,
    private val repositoryScope: CoroutineScope,
    private val traceReporter: CategoryFlowTraceReporter
) {
    private data class CacheEntry(
        val flow: Flow<List<Category>>,
        val scope: CoroutineScope
    )

    private val flows = ConcurrentHashMap<Long, CacheEntry>()
    private val cacheLock = Any()
    private var providerSetKnown = false
    private var activeProviderIds = emptySet<Long>()

    init {
        providerDao.getAll()
            .map { providers -> providers.asSequence().map { it.id }.toSet() }
            .distinctUntilChanged()
            .onEach { activeProviderIds ->
                synchronized(cacheLock) {
                    this@ChannelCategoryFlowCache.activeProviderIds = activeProviderIds
                    providerSetKnown = true
                    flows.entries.removeIf { (providerId, entry) ->
                        if (providerId !in activeProviderIds) {
                            entry.scope.cancel()
                            true
                        } else {
                            false
                        }
                    }
                }
            }
            .launchIn(repositoryScope)
    }

    fun getOrCreate(providerId: Long, builder: () -> Flow<List<Category>>): Flow<List<Category>> =
        synchronized(cacheLock) {
            if (providerSetKnown && providerId !in activeProviderIds) {
                return@synchronized builder()
            }

            flows[providerId]?.flow ?: createEntry(providerId, builder).also { entry ->
                flows[providerId] = entry
            }.flow
        }

    private fun createEntry(providerId: Long, builder: () -> Flow<List<Category>>): CacheEntry {
        val entryScope = CoroutineScope(
            repositoryScope.coroutineContext.minusKey(Job) +
                SupervisorJob(repositoryScope.coroutineContext[Job])
        )
        return CacheEntry(
            flow = builder()
                .onStart { traceReporter.onUpstreamStart(providerId) }
                .onCompletion { traceReporter.onUpstreamStop(providerId) }
                .distinctUntilChanged()
                .shareIn(
                    scope = entryScope,
                    started = SharingStarted.WhileSubscribed(
                        stopTimeoutMillis = UPSTREAM_STOP_TIMEOUT_MILLIS
                    ),
                    replay = 1
                ),
            scope = entryScope
        )
    }

    companion object {
        /**
         * Keeps Room observation alive across the short Activity/navigation gaps used by TV
         * re-entry while still releasing an idle provider flow promptly.
         */
        const val UPSTREAM_STOP_TIMEOUT_MILLIS = 30_000L
    }
}
