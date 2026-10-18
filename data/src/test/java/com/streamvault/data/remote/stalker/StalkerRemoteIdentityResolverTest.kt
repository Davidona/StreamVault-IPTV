package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.DatabaseTransactionRunner
import com.streamvault.data.local.dao.StalkerRemoteIdentityDao
import com.streamvault.data.local.entity.StalkerRemoteIdentityEntity
import com.streamvault.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Test

class StalkerRemoteIdentityResolverTest {
    @Test
    fun stableHashDoesNotCollapseKnownJavaHashCollision() {
        assertThat(stalkerStableHashId(7L, ContentType.MOVIE, "Aa"))
            .isNotEqualTo(stalkerStableHashId(7L, ContentType.MOVIE, "BB"))
    }

    @Test
    fun collidingLegacyHashesRemainDistinctAndReversible() = runTest {
        val dao = FakeIdentityDao()
        val resolver = StalkerRemoteIdentityResolver(dao, DirectTransactionRunner)

        // "ba" and "a\u0080" have the same Java String hash and remain unchanged by lowercase().
        val resolved = resolver.resolveAll(7L, ContentType.MOVIE, listOf("ba", "a\u0080"))

        assertThat(resolved.values.toSet()).hasSize(2)
        resolved.forEach { (rawId, surrogateId) ->
            assertThat(resolver.reverse(7L, ContentType.MOVIE, surrogateId)).isEqualTo(rawId)
        }
    }

    private object DirectTransactionRunner : DatabaseTransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    }

    @Test
    fun batchedResolutionMatchesSequentialIdentityRulesAcrossSqlChunks() = runTest {
        val dao = FakeIdentityDao()
        val providerId = 7L
        val type = ContentType.LIVE
        dao.insert(StalkerRemoteIdentityEntity(providerId, type, "existing", 23L, 0L, 0L))
        dao.insert(StalkerRemoteIdentityEntity(providerId, type, "occupied-number", 12L, 0L, 0L))
        dao.insert(StalkerRemoteIdentityEntity(providerId, type, "occupied-hash", stalkerStableHashId(providerId, type, "12"), 0L, 0L))
        val ids = (listOf(" existing ", "12", "012", "12", "", "nonnumeric") +
            (1..1200).map { "row-$it" }).map(String::trim).filter(String::isNotEmpty).distinct().sorted()
        val expected = linkedMapOf("existing" to 23L, "occupied-number" to 12L,
            "occupied-hash" to stalkerStableHashId(providerId, type, "12"))
        ids.forEach { rawId ->
            if (rawId !in expected) {
                expected[rawId] = listOfNotNull(rawId.toLongOrNull()?.takeIf { it > 0L }, stalkerStableHashId(providerId, type, rawId))
                    .firstOrNull { it !in expected.values }
                    ?: ((expected.values.filter { it >= 4_000_000_000L }.maxOrNull() ?: 3_999_999_999L) + 1L)
            }
        }
        val resolver = StalkerRemoteIdentityResolver(dao, DirectTransactionRunner)
        val result = resolver.resolveAll(providerId, type, ids)
        assertThat(result).isEqualTo(ids.associateWith(expected::getValue))
        assertThat(result.values.toSet()).hasSize(ids.size)
        assertThat(dao.maxQueryParameters).isAtMost(400)
        assertThat(dao.singleRawLookups).isEqualTo(0)
        assertThat(dao.singleSurrogateLookups).isEqualTo(0)
        val initialBatches = dao.insertBatches
        assertThat(resolver.resolveAll(providerId, type, ids)).isEqualTo(result)
        assertThat(dao.insertBatches).isEqualTo(initialBatches)
        assertThat(resolver.resolveAll(8L, type, listOf("existing"))["existing"]).isEqualTo(stalkerStableHashId(8L, type, "existing"))
    }

    @Test
    fun ignoredBatchConflictReusesWinningIdentity() = runTest {
        val dao = FakeIdentityDao()
        dao.beforeBatchInsert = { dao.insert(StalkerRemoteIdentityEntity(7L, ContentType.LIVE, "12", 99L, 0L, 0L)) }
        val resolver = StalkerRemoteIdentityResolver(dao, DirectTransactionRunner)
        assertThat(resolver.resolveAll(7L, ContentType.LIVE, listOf("12", "13")))
            .containsExactly("12", 99L, "13", 13L)
        assertThat(resolver.reverse(7L, ContentType.LIVE, 99L)).isEqualTo("12")
    }

    private class FakeIdentityDao : StalkerRemoteIdentityDao {
        private val rows = mutableListOf<StalkerRemoteIdentityEntity>()
        var maxQueryParameters = 0
        var singleRawLookups = 0
        var singleSurrogateLookups = 0
        var insertBatches = 0
        var beforeBatchInsert: (suspend () -> Unit)? = null

        override suspend fun getByRawIds(providerId: Long, contentType: String, rawIds: List<String>): List<StalkerRemoteIdentityEntity> {
            maxQueryParameters = maxOf(maxQueryParameters, rawIds.size)
            return rows.filter { it.providerId == providerId && it.contentType.name == contentType && it.rawId in rawIds }
        }

        override suspend fun getBySurrogateIds(providerId: Long, contentType: String, surrogateIds: List<Long>): List<StalkerRemoteIdentityEntity> {
            maxQueryParameters = maxOf(maxQueryParameters, surrogateIds.size)
            return rows.filter { it.providerId == providerId && it.contentType.name == contentType && it.surrogateId in surrogateIds }
        }

        override suspend fun insertAll(entities: List<StalkerRemoteIdentityEntity>): List<Long> {
            insertBatches++
            beforeBatchInsert?.also { beforeBatchInsert = null }?.invoke()
            return entities.map { entity ->
                if (rows.any { it.providerId == entity.providerId && it.contentType == entity.contentType &&
                        (it.rawId == entity.rawId || it.surrogateId == entity.surrogateId) }) -1L
                else { insert(entity); rows.size.toLong() }
            }
        }

        override suspend fun getByRawId(providerId: Long, contentType: String, rawId: String): StalkerRemoteIdentityEntity? {
            singleRawLookups++
            return rows.firstOrNull { it.providerId == providerId && it.contentType.name == contentType && it.rawId == rawId }
        }

        override suspend fun getBySurrogateId(providerId: Long, contentType: String, surrogateId: Long): StalkerRemoteIdentityEntity? {
            singleSurrogateLookups++
            return rows.firstOrNull { it.providerId == providerId && it.contentType.name == contentType && it.surrogateId == surrogateId }
        }

        override suspend fun maxAllocatedSurrogate(providerId: Long, contentType: String, floor: Long): Long? =
            rows.filter { it.providerId == providerId && it.contentType.name == contentType && it.surrogateId >= floor }
                .maxOfOrNull { it.surrogateId }

        override suspend fun insert(entity: StalkerRemoteIdentityEntity) {
            check(rows.none { row ->
                row.providerId == entity.providerId && row.contentType == entity.contentType &&
                    (row.rawId == entity.rawId || row.surrogateId == entity.surrogateId)
            })
            rows += entity
        }
    }
}
