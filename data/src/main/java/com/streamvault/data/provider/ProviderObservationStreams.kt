package com.streamvault.data.provider

import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.entity.ProviderConfigEntity
import com.streamvault.data.local.entity.ProviderAccountRuntimeEntity
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.domain.model.LegacyProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

internal class ProviderObservationStreams(
    providerDao: ProviderDao,
    providerSnapshotDao: ProviderSnapshotDao,
    private val projection: ProviderPublicProjection,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private data class CachedProjection(
        val source: ProviderConfigEntity,
        val value: RedactedProviderConfigurationProjection
    )

    private val cacheLock = Any()
    private val projectionCache = mutableMapOf<Long, CachedProjection>()

    private val publicConfigurations: Flow<Map<Long, RedactedProviderConfigurationProjection>> =
        providerSnapshotDao.observeConfigs()
            .distinctUntilChanged()
            .map(::resolveConfigurationRows)

    val providers: Flow<List<LegacyProvider>> = combine(
        providerDao.getAll(),
        publicConfigurations,
        providerSnapshotDao.observeRuntimes(),
        providerSnapshotDao.observeStalkerPortalStates()
    ) { identities, configurations, runtimes, portalStates ->
        val runtimeByProvider = runtimes.associateBy(ProviderAccountRuntimeEntity::providerId)
        val portalStateByProvider = portalStates.associateBy(StalkerPortalStateEntity::providerId)
        identities.map { identity ->
            projection.assemble(
                identity = identity,
                configuration = configurations[identity.id],
                runtime = runtimeByProvider[identity.id],
                portalState = portalStateByProvider[identity.id]
            )
        }
    }.flowOn(workDispatcher)

    val activeProvider: Flow<LegacyProvider?> = providers
        .map { list -> list.firstOrNull(LegacyProvider::isActive) }
        .distinctUntilChanged()

    private fun resolveConfigurationRows(
        rows: List<ProviderConfigEntity>
    ): Map<Long, RedactedProviderConfigurationProjection> = synchronized(cacheLock) {
        val currentProviderIds = rows.asSequence().map(ProviderConfigEntity::providerId).toSet()
        projectionCache.keys.retainAll(currentProviderIds)
        val resolved = rows.associate { row ->
            val cached = projectionCache[row.providerId]
            val value = if (cached != null && cached.source == row) {
                cached.value
            } else {
                projection.decode(row).also { decoded ->
                    projectionCache[row.providerId] = CachedProjection(row, decoded)
                }
            }
            row.providerId to value
        }
        resolved
    }
}
