package com.streamvault.domain.repository

import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioAddon
import com.streamvault.domain.model.stremio.StremioManifest
import kotlinx.coroutines.flow.Flow

interface StremioAddonRepository {
    fun observeAddons(): Flow<List<StremioAddon>>

    suspend fun getAddon(id: Long): StremioAddon?

    suspend fun getManifest(addonId: Long): StremioManifest?

    suspend fun installAddon(installUrl: String): Result<StremioAddon>

    suspend fun removeAddon(id: Long): Result<Unit>

    suspend fun setAddonEnabled(id: Long, enabled: Boolean): Result<Unit>

    suspend fun moveAddon(id: Long, offset: Int): Result<Unit>

    suspend fun setCatalogEnabled(addonId: Long, catalogKey: String, enabled: Boolean): Result<Unit>
}
