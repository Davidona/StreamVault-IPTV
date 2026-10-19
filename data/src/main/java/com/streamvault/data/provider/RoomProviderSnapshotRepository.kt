package com.streamvault.data.provider

import android.util.Log
import com.streamvault.domain.model.Provider as StableProvider

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.dao.ProviderSnapshotDao
import com.streamvault.data.local.dao.StalkerPortalStateDao
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.domain.model.*
import com.streamvault.domain.repository.ProviderSnapshotRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RoomProviderSnapshotRepository internal constructor(
    private val providerDao: ProviderDao,
    private val snapshotDao: ProviderSnapshotDao,
    private val stalkerPortalStateDao: StalkerPortalStateDao,
    private val codec: ProviderConfigurationCodec,
    private val gson: Gson,
    private val workDispatcher: CoroutineDispatcher
) : ProviderSnapshotRepository {
    @Inject
    constructor(
        providerDao: ProviderDao,
        snapshotDao: ProviderSnapshotDao,
        stalkerPortalStateDao: StalkerPortalStateDao,
        codec: ProviderConfigurationCodec,
        gson: Gson
    ) : this(
        providerDao = providerDao,
        snapshotDao = snapshotDao,
        stalkerPortalStateDao = stalkerPortalStateDao,
        codec = codec,
        gson = gson,
        workDispatcher = Dispatchers.IO
    )

    private val stringListType = object : TypeToken<List<String>>() {}.type

    override suspend fun getSnapshot(providerId: Long): ProviderSnapshot? = withContext(workDispatcher) {
        val provider = providerDao.getById(providerId) ?: return@withContext null
        val storedConfig = snapshotDao.getConfig(providerId) ?: return@withContext null
        if (storedConfig.type != provider.type) {
            throw IllegalStateException("Provider/configuration type mismatch for $providerId")
        }
        val runtime = snapshotDao.getRuntime(providerId)
        val learning = if (provider.type == ProviderType.STALKER_PORTAL) {
            stalkerPortalStateDao.get(providerId)?.toGenerationValidLearning(
                gson,
                storedConfig.configurationGeneration
            )
        } else null
        val decodedConfiguration = codec.decodeForKeyMigration(
            storedConfig.type,
            storedConfig.encryptedConfigJson
        )
        if (decodedConfiguration.requiresReencryption) {
            runCatching {
                val replacement = codec.encodeForKeyMigration(decodedConfiguration.configuration)
                if (replacement.usesPreferredKey &&
                    replacement.payload != storedConfig.encryptedConfigJson
                ) {
                    snapshotDao.rekeyConfigIfUnchanged(
                        providerId = providerId,
                        configurationGeneration = storedConfig.configurationGeneration,
                        expectedJson = storedConfig.encryptedConfigJson,
                        replacementJson = replacement.payload,
                        updatedAt = System.currentTimeMillis()
                    )
                }
            }.onFailure { error ->
                Log.w(TAG, "Could not migrate provider credential encryption for $providerId.", error)
            }
        }
        ProviderSnapshot(
            provider = StableProvider(
                id = provider.id,
                name = provider.name,
                type = provider.type,
                isActive = provider.isActive,
                status = provider.status,
                lastSyncedAt = provider.lastSyncedAt,
                createdAt = provider.createdAt
            ),
            configuration = decodedConfiguration.configuration,
            configurationGeneration = storedConfig.configurationGeneration,
            accountRuntime = runtime?.let {
                ProviderAccountRuntime(
                    maxConnections = it.maxConnections,
                    expirationDate = it.expirationDate,
                    apiVersion = it.apiVersion,
                    allowedOutputFormats = gson.fromJson(it.allowedOutputFormatsJson, stringListType),
                    catalogLayout = it.catalogLayout,
                    catalogLayoutDetectionVersion = it.catalogLayoutDetectionVersion,
                    observedAt = it.observedAt
                )
            } ?: ProviderAccountRuntime(),
            stalkerLearning = learning
        )
    }

    override suspend fun compareAndSetStalkerLearning(
        providerId: Long,
        learning: StalkerPortalLearning
    ): Boolean {
        val existing = stalkerPortalStateDao.get(providerId)
        val observedAt = learning.latestObservedAtForSnapshot()
        return snapshotDao.compareAndSetStalkerLearning(
            (existing ?: StalkerPortalStateEntity(providerId)).copy(
                configurationGeneration = learning.configurationGeneration,
                learningJson = gson.toJson(learning),
                observationSource = learning.latestSourceForSnapshot().name,
                observedAt = observedAt,
                validatedAt = maxOf(existing?.validatedAt ?: 0L, observedAt)
            )
        )
    }

    override suspend fun updateCatalogLayout(providerId: Long, layout: CatalogLayout, detectionVersion: Int) {
        snapshotDao.updateCatalogLayout(providerId, layout, detectionVersion)
    }

    private companion object {
        const val TAG = "ProviderSnapshotRepository"
    }
}
