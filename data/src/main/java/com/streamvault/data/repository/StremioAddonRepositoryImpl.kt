package com.streamvault.data.repository

import com.streamvault.data.local.dao.StremioAddonDao
import com.streamvault.data.local.entity.StremioAddonEntity
import com.streamvault.data.remote.stremio.StremioAddonClient
import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioAddon
import com.streamvault.domain.model.stremio.StremioManifest
import com.streamvault.domain.repository.StremioAddonRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class StremioAddonRepositoryImpl @Inject constructor(
    private val dao: StremioAddonDao,
    private val client: StremioAddonClient
) : StremioAddonRepository {

    override fun observeAddons(): Flow<List<StremioAddon>> =
        dao.observeAll().map { entities -> entities.map(StremioAddonEntity::toDomain) }

    override suspend fun getAddon(id: Long): StremioAddon? = dao.getById(id)?.toDomain()

    override suspend fun getManifest(addonId: Long): StremioManifest? =
        dao.getById(addonId)?.let { entity -> client.parseManifest(entity.manifestJson) }

    override suspend fun installAddon(installUrl: String): Result<StremioAddon> {
        val snapshotResult = client.fetchManifestSnapshot(installUrl)
        val snapshot = snapshotResult.getOrNull() ?: return Result.error(
            snapshotResult.errorMessageOrNull() ?: "Addon could not be installed",
            (snapshotResult as? Result.Error)?.exception
        )
        if (snapshot.manifest.behaviorHints.configurationRequired) {
            return Result.error(
                "This addon requires configuration. Configure it in your browser, then paste its configured manifest URL."
            )
        }
        if (dao.getByInstallUrl(snapshot.manifestUrl) != null) {
            return Result.error("This addon is already installed")
        }
        val now = System.currentTimeMillis()
        val entity = StremioAddonEntity(
            installUrl = snapshot.manifestUrl,
            manifestId = snapshot.manifest.id,
            name = snapshot.manifest.name,
            version = snapshot.manifest.version,
            manifestJson = snapshot.manifestJson,
            enabled = true,
            sortOrder = dao.maxSortOrder() + 1,
            catalogSettingsJson = "",
            createdAt = now,
            updatedAt = now
        )
        val id = dao.insert(entity)
        return Result.success(entity.copy(id = id).toDomain())
    }

    override suspend fun removeAddon(id: Long): Result<Unit> {
        dao.delete(id)
        return Result.success(Unit)
    }

    override suspend fun setAddonEnabled(id: Long, enabled: Boolean): Result<Unit> {
        dao.setEnabled(id, enabled, System.currentTimeMillis())
        return Result.success(Unit)
    }

    override suspend fun moveAddon(id: Long, offset: Int): Result<Unit> {
        if (offset == 0) return Result.success(Unit)
        val addons = dao.getAll()
        val index = addons.indexOfFirst { it.id == id }
        if (index < 0) return Result.error("Addon is not installed")
        val target = (index + offset).coerceIn(0, addons.lastIndex)
        if (target == index) return Result.success(Unit)
        val reordered = addons.toMutableList().apply { add(target, removeAt(index)) }
        dao.applyOrder(
            reordered.mapIndexed { position, addon -> addon.id to position },
            System.currentTimeMillis()
        )
        return Result.success(Unit)
    }

    override suspend fun setCatalogEnabled(
        addonId: Long,
        catalogKey: String,
        enabled: Boolean
    ): Result<Unit> {
        val entity = dao.getById(addonId) ?: return Result.error("Addon is not installed")
        val settings = StremioCatalogSettingsCodec.decode(entity.catalogSettingsJson).toMutableMap()
        settings[catalogKey] = enabled
        dao.setCatalogSettings(
            addonId,
            StremioCatalogSettingsCodec.encode(settings),
            System.currentTimeMillis()
        )
        return Result.success(Unit)
    }
}

private fun StremioAddonEntity.toDomain(): StremioAddon = StremioAddon(
    id = id,
    installUrl = installUrl,
    manifestId = manifestId,
    name = name,
    version = version,
    manifestJson = manifestJson,
    enabled = enabled,
    sortOrder = sortOrder,
    catalogSettings = StremioCatalogSettingsCodec.decode(catalogSettingsJson),
    createdAt = createdAt,
    updatedAt = updatedAt
)
