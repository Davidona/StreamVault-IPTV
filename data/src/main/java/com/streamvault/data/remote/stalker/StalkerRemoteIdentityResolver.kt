package com.streamvault.data.remote.stalker

import android.database.sqlite.SQLiteConstraintException
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.StalkerRemoteIdentityDao
import com.streamvault.data.local.dao.CategoryDao
import com.streamvault.data.local.entity.StalkerRemoteIdentityEntity
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.util.KeyedMutexRegistry
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** Collision-safe, persistent translation between portal IDs and local Long IDs. */
@Singleton
class StalkerRemoteIdentityResolver private constructor(
    private val dao: StalkerRemoteIdentityDao,
    private val transactionRunner: DatabaseTransactionRunner,
    private val categoryDao: CategoryDao?,
    @Suppress("UNUSED_PARAMETER") constructorMarker: Unit
) {
    @Inject
    constructor(
        dao: StalkerRemoteIdentityDao,
        transactionRunner: DatabaseTransactionRunner,
        categoryDao: CategoryDao
    ) : this(dao, transactionRunner, categoryDao, Unit)

    internal constructor(
        dao: StalkerRemoteIdentityDao,
        transactionRunner: DatabaseTransactionRunner
    ) : this(dao, transactionRunner, null, Unit)

    private val locks = KeyedMutexRegistry<String>()

    suspend fun resolveAll(
        providerId: Long,
        contentType: ContentType,
        rawIds: Iterable<String>
    ): Map<String, Long> {
        val normalizedIds = rawIds.map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        if (normalizedIds.isEmpty()) return emptyMap()
        val key = "$providerId/${contentType.name}"
        return locks.withLock(key) {
            transactionRunner.inTransaction {
                resolveBatchLocked(providerId, contentType, normalizedIds)
            }
        }
    }

    suspend fun reverse(providerId: Long, contentType: ContentType, surrogateId: Long): String? =
        dao.getBySurrogateId(providerId, contentType.name, surrogateId)?.rawId

    suspend fun resolveCategories(
        providerId: Long,
        contentType: ContentType,
        categories: Iterable<Pair<String, String>>
    ): Map<String, Long> {
        val normalized = categories
            .map { (rawId, name) -> rawId.trim() to name.trim() }
            .filter { (rawId, _) -> rawId.isNotEmpty() }
            .distinctBy { it.first }
            .sortedBy { it.first }
        if (normalized.isEmpty()) return emptyMap()
        val existingByName = categoryDao
            ?.getByProviderAndTypeSync(providerId, contentType.name)
            ?.associateBy { category -> category.name.trim().lowercase(Locale.ROOT) }
            .orEmpty()
        val preferred = normalized.associate { (rawId, name) ->
            rawId to existingByName[name.lowercase(Locale.ROOT)]?.categoryId
        }
        val key = "$providerId/${contentType.name}"
        return locks.withLock(key) {
            transactionRunner.inTransaction {
                resolveBatchLocked(providerId, contentType, normalized.map { it.first }, preferred)
            }
        }
    }

    private suspend fun resolveBatchLocked(
        providerId: Long,
        contentType: ContentType,
        rawIds: List<String>,
        preferred: Map<String, Long?> = emptyMap()
    ): Map<String, Long> {
        val resolved = LinkedHashMap<String, Long>(rawIds.size)
        rawIds.chunked(SQL_BATCH_SIZE).forEach { chunk ->
            dao.getByRawIds(providerId, contentType.name, chunk).forEach {
                resolved[it.rawId] = it.surrogateId
            }
        }
        // Chunk candidates and pending inserts as well as SQL parameters. The caller's input
        // remains the identity batch; no provider-wide identity snapshot is retained.
        var highestAllocated: Long? = null
        rawIds.filterNot(resolved::containsKey).chunked(SQL_BATCH_SIZE).forEach { chunk ->
            val candidates = chunk.associateWith { rawId ->
                listOfNotNull(
                    preferred[rawId]?.takeIf { it > 0L },
                    rawId.toLongOrNull()?.takeIf { it > 0L },
                    legacyId(providerId, contentType, rawId)
                )
            }
            val occupied = HashSet<Long>()
            candidates.values.flatten().distinct().chunked(SQL_BATCH_SIZE).forEach { ids ->
                dao.getBySurrogateIds(providerId, contentType.name, ids).forEach {
                    occupied += it.surrogateId
                }
            }
            val pending = chunk.map { rawId ->
                val candidate = candidates.getValue(rawId).firstOrNull { it !in occupied }
                    ?: run {
                        if (highestAllocated == null) {
                            highestAllocated = dao.maxAllocatedSurrogate(
                                providerId, contentType.name, HIGH_SURROGATE_FLOOR
                            ) ?: (HIGH_SURROGATE_FLOOR - 1L)
                        }
                        (requireNotNull(highestAllocated) + 1L).coerceAtMost(Long.MAX_VALUE - 1L)
                    }
                occupied += candidate
                if (candidate >= HIGH_SURROGATE_FLOOR) {
                    // Include identities assigned earlier in this pending batch, just as the
                    // previous sequential INSERT path did when allocating its next high ID.
                    if (highestAllocated == null) {
                        highestAllocated = dao.maxAllocatedSurrogate(
                            providerId, contentType.name, HIGH_SURROGATE_FLOOR
                        ) ?: (HIGH_SURROGATE_FLOOR - 1L)
                    }
                    highestAllocated = maxOf(requireNotNull(highestAllocated), candidate)
                }
                val now = System.currentTimeMillis()
                StalkerRemoteIdentityEntity(providerId, contentType, rawId, candidate, now, now)
            }
            val inserted = dao.insertAll(pending)
            pending.zip(inserted).forEach { (entity, rowId) ->
                resolved[entity.rawId] = if (rowId != -1L) entity.surrogateId else {
                    // A competing mapping must never be overwritten. Resolve ignored rows
                    // through the established collision recovery logic.
                    highestAllocated = null
                    resolveLocked(providerId, contentType, entity.rawId, preferred[entity.rawId])
                }
            }
        }
        return rawIds.associateWith { resolved.getValue(it) }
    }

    private suspend fun resolveLocked(
        providerId: Long,
        contentType: ContentType,
        rawId: String,
        preferredSurrogate: Long? = null
    ): Long {
        dao.getByRawId(providerId, contentType.name, rawId)?.let { return it.surrogateId }
        val numericCandidate = rawId.toLongOrNull()?.takeIf { it > 0L }
        val legacyCandidate = legacyId(providerId, contentType, rawId)
        var candidate = listOfNotNull(preferredSurrogate?.takeIf { it > 0L }, numericCandidate, legacyCandidate)
            .firstOrNull { proposed ->
                dao.getBySurrogateId(providerId, contentType.name, proposed) == null
            }
            ?: nextHighSurrogate(providerId, contentType)
        while (true) {
            val now = System.currentTimeMillis()
            try {
                dao.insert(
                    StalkerRemoteIdentityEntity(
                        providerId = providerId,
                        contentType = contentType,
                        rawId = rawId,
                        surrogateId = candidate,
                        createdAt = now,
                        updatedAt = now
                    )
                )
                return candidate
            } catch (_: SQLiteConstraintException) {
                dao.getByRawId(providerId, contentType.name, rawId)?.let { return it.surrogateId }
                candidate = nextHighSurrogate(providerId, contentType)
            }
        }
    }

    private suspend fun nextHighSurrogate(providerId: Long, contentType: ContentType): Long =
        ((dao.maxAllocatedSurrogate(providerId, contentType.name, HIGH_SURROGATE_FLOOR)
            ?: (HIGH_SURROGATE_FLOOR - 1L)) + 1L).coerceAtMost(Long.MAX_VALUE - 1L)

    private fun legacyId(providerId: Long, contentType: ContentType, rawId: String): Long {
        return stalkerStableHashId(providerId, contentType, rawId)
    }

    private companion object {
        const val SQL_BATCH_SIZE = 400
        const val HIGH_SURROGATE_FLOOR = 4_000_000_000L
    }
}
