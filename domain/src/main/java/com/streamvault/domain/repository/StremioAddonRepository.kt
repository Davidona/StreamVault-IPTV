package com.streamvault.domain.repository

import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioAddon
import com.streamvault.domain.model.stremio.StremioCatalogPage
import com.streamvault.domain.model.stremio.StremioCatalogQuery
import com.streamvault.domain.model.stremio.StremioCatalogRef
import com.streamvault.domain.model.stremio.StremioManifest
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaResolution
import com.streamvault.domain.model.stremio.StremioResourceResolution
import com.streamvault.domain.model.stremio.StremioStream
import com.streamvault.domain.model.stremio.StremioSubtitle
import kotlinx.coroutines.flow.Flow

interface StremioAddonRepository {
    fun observeAddons(): Flow<List<StremioAddon>>

    suspend fun getAddon(id: Long): StremioAddon?

    suspend fun getManifest(addonId: Long): StremioManifest?

    suspend fun fetchCatalog(
        addonId: Long,
        catalog: StremioCatalogRef,
        query: StremioCatalogQuery
    ): Result<StremioCatalogPage>

    suspend fun resolveMeta(
        catalogOriginAddonId: Long,
        type: StremioMediaType,
        exactId: String
    ): StremioMetaResolution

    suspend fun resolveStreams(
        type: StremioMediaType,
        exactId: String,
        addonIds: Set<Long>? = null
    ): StremioResourceResolution<StremioStream>

    suspend fun resolveSubtitles(
        type: StremioMediaType,
        exactVideoId: String,
        videoHash: String? = null,
        videoSize: Long? = null,
        filename: String? = null,
        addonIds: Set<Long>? = null
    ): StremioResourceResolution<StremioSubtitle>

    suspend fun installAddon(installUrl: String): Result<StremioAddon>

    suspend fun removeAddon(id: Long): Result<Unit>

    suspend fun setAddonEnabled(id: Long, enabled: Boolean): Result<Unit>

    suspend fun moveAddon(id: Long, offset: Int): Result<Unit>

    suspend fun setCatalogEnabled(addonId: Long, catalogKey: String, enabled: Boolean): Result<Unit>
}
