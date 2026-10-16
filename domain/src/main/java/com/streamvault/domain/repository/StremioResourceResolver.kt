package com.streamvault.domain.repository

import com.streamvault.domain.model.Result
import com.streamvault.domain.model.stremio.StremioAddon
import com.streamvault.domain.model.stremio.StremioAddonResourceFailure
import com.streamvault.domain.model.stremio.StremioAddonSource
import com.streamvault.domain.model.stremio.StremioAttributedResource
import com.streamvault.domain.model.stremio.StremioInstalledAddon
import com.streamvault.domain.model.stremio.installFingerprint
import com.streamvault.domain.model.stremio.StremioMediaType
import com.streamvault.domain.model.stremio.StremioMetaDetail
import com.streamvault.domain.model.stremio.StremioMetaResolution
import com.streamvault.domain.model.stremio.StremioResourceKind
import com.streamvault.domain.model.stremio.StremioResourceResolution
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Selects eligible addon resources and coordinates deterministic, bounded requests. */
class StremioResourceResolver @Inject constructor() {

    fun candidates(
        addons: List<StremioInstalledAddon>,
        kind: StremioResourceKind,
        type: StremioMediaType,
        exactId: String,
        addonIds: Set<Long>? = null,
        preferredAddonId: Long? = null
    ): List<StremioInstalledAddon> {
        if (exactId.isBlank() || type == StremioMediaType.UNKNOWN) return emptyList()
        val eligible = addons.filter { installed ->
            installed.addon.enabled &&
                (addonIds == null || installed.addon.id in addonIds) &&
                if (kind == StremioResourceKind.CATALOG) {
                    // Catalog IDs come from manifest.catalogs; prefixes never route catalogs.
                    installed.manifest.catalogs.any { it.type == type && it.id == exactId }
                } else {
                    installed.manifest.supportsResource(kind, type, exactId)
                }
        }
        return if (preferredAddonId == null) {
            eligible
        } else {
            eligible.sortedBy { if (it.addon.id == preferredAddonId) 0 else 1 }
        }
    }

    suspend fun resolveMeta(
        candidates: List<StremioInstalledAddon>,
        requestedType: StremioMediaType,
        exactId: String,
        request: suspend (StremioAddon) -> Result<StremioMetaDetail>
    ): StremioMetaResolution {
        val failures = mutableListOf<StremioAddonResourceFailure>()
        for (candidate in candidates) {
            val result = try {
                request(candidate.addon)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                Result.error("Addon request failed")
            }
            when (result) {
                is Result.Success -> {
                    val meta = result.data
                    if (meta.id == exactId && (meta.type == requestedType || meta.type == StremioMediaType.UNKNOWN)) {
                        val normalized = if (meta.type == StremioMediaType.UNKNOWN) {
                            meta.copy(type = requestedType)
                        } else {
                            meta
                        }
                        return StremioMetaResolution(
                            result = StremioAttributedResource(candidate.addon.toSource(), normalized),
                            failures = failures,
                            candidateCount = candidates.size
                        )
                    }
                    failures += failure(candidate.addon, "Addon returned metadata for a different item")
                }
                is Result.Error -> failures += failure(candidate.addon, result.message)
                Result.Loading -> failures += failure(candidate.addon, "Addon request did not complete")
            }
        }
        return StremioMetaResolution(failures = failures, candidateCount = candidates.size)
    }

    suspend fun <T> resolveMany(
        candidates: List<StremioInstalledAddon>,
        request: suspend (StremioAddon) -> Result<List<T>>
    ): StremioResourceResolution<T> {
        if (candidates.isEmpty()) return StremioResourceResolution()
        val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
        val outcomes = coroutineScope {
            candidates.map { candidate ->
                async {
                    semaphore.withPermit {
                        val result = try {
                            request(candidate.addon)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            Result.error("Addon request failed")
                        }
                        candidate to result
                    }
                }
            }.awaitAll()
        }
        val results = mutableListOf<StremioAttributedResource<T>>()
        val failures = mutableListOf<StremioAddonResourceFailure>()
        outcomes.forEach { (candidate, result) ->
            when (result) {
                is Result.Success -> result.data.forEach { resource ->
                    results += StremioAttributedResource(candidate.addon.toSource(), resource)
                }
                is Result.Error -> failures += failure(candidate.addon, result.message)
                Result.Loading -> failures += failure(candidate.addon, "Addon request did not complete")
            }
        }
        return StremioResourceResolution(results, failures, candidates.size)
    }

    private fun failure(addon: StremioAddon, rawMessage: String): StremioAddonResourceFailure {
        val sanitized = URL_PATTERN.replace(rawMessage, "addon endpoint")
            .replace(addon.installUrl, "addon endpoint")
            .trim()
            .take(MAX_FAILURE_MESSAGE_LENGTH)
            .ifBlank { "Addon request failed" }
        return StremioAddonResourceFailure(addon.toSource(), sanitized)
    }

    private fun StremioAddon.toSource() = StremioAddonSource(
        addonId = id,
        addonName = name,
        manifestId = manifestId,
        installFingerprint = installFingerprint()
    )

    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 4
        const val MAX_FAILURE_MESSAGE_LENGTH = 180
        val URL_PATTERN = Regex("(?i)(?:https?|magnet)://[^\\s\\\"']+")
    }
}
