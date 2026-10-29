package com.streamvault.data.local

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProviderWorkflowTicket
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowPhaseState
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.data.local.entity.ProviderWorkflowState
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProviderWorkflowDaoTest {
    private lateinit var database: StreamVaultDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            StreamVaultDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `deferral is lease fenced and preserves the last checkpoint`() = runTest {
        insertProvider()
        val dao = database.providerWorkflowDao()
        val ticket = dao.request(PROVIDER_ID, ProviderWorkflowPhase.EPG, ProviderWorkflowReason.PERIODIC, 100L)
        val lease = checkNotNull(dao.claim(ticket, "guide", 101L, 1000L, 0L))
        dao.updateRunningCheckpoint(PROVIDER_ID, ticket.generation, ticket.phase, lease.token, "stage=5000", 102L)
        assertThat(dao.defer(lease.copy(token = "stale"), 103L)).isFalse()
        assertThat(dao.getWorkflow(PROVIDER_ID)!!.state).isEqualTo(ProviderWorkflowState.RUNNING)
        assertThat(dao.defer(lease, 104L)).isTrue()
        assertThat(dao.getCheckpoint(PROVIDER_ID, ticket.generation, ticket.phase)).isEqualTo("stage=5000")
        assertThat(dao.getWorkflow(PROVIDER_ID)!!.lastErrorCode).isNull()
        assertThat(dao.claim(ticket, "resumed", 105L, 1000L, 0L)).isNotNull()
    }

    @Test
    fun `concurrent schedulers grant exactly one workflow lease`() = runTest {
        insertProvider()
        val dao = database.providerWorkflowDao()
        val ticket = dao.request(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.PRIMARY_CATALOG,
            reason = ProviderWorkflowReason.PERIODIC,
            now = 100L
        )

        val claims = withContext(Dispatchers.Default) {
            listOf("owner-a", "owner-b").map { token ->
                async {
                    dao.claim(
                        ticket = ticket,
                        token = token,
                        now = 101L,
                        leaseDurationMs = 1_000L,
                        staleHeartbeatBefore = 0L
                    )
                }
            }.awaitAll()
        }

        assertThat(claims.count { it != null }).isEqualTo(1)
        val workflow = dao.getWorkflow(PROVIDER_ID)!!
        assertThat(workflow.state).isEqualTo(ProviderWorkflowState.RUNNING)
        assertThat(workflow.leaseToken).isAnyOf("owner-a", "owner-b")
        assertThat(workflow.generation).isEqualTo(1L)
    }

    @Test
    fun `running checkpoint survives retryable worker loss and is fenced by lease token`() = runTest {
        insertProvider()
        val dao = database.providerWorkflowDao()
        val ticket = dao.request(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.PRIMARY_CATALOG,
            reason = ProviderWorkflowReason.PERIODIC,
            now = 100L
        )
        val firstLease = checkNotNull(
            dao.claim(ticket, "first-process", 101L, 1_000L, staleHeartbeatBefore = 0L)
        )

        assertThat(
            dao.updateRunningCheckpoint(
                PROVIDER_ID,
                ticket.generation,
                ticket.phase,
                firstLease.token,
                "jellyfin-v1|101|202|MOVIES|100|300|0|-1",
                now = 102L
            )
        ).isEqualTo(1)
        assertThat(
            dao.updateRunningCheckpoint(
                PROVIDER_ID,
                ticket.generation,
                ticket.phase,
                "wrong-owner",
                "corrupt",
                now = 103L
            )
        ).isEqualTo(0)
        assertThat(dao.fail(firstLease, 104L, "IO", "process stopped", retryable = true)).isTrue()

        val resumedLease = checkNotNull(
            dao.claim(ticket, "second-process", 105L, 1_000L, staleHeartbeatBefore = 0L)
        )

        assertThat(resumedLease.generation).isEqualTo(firstLease.generation)
        assertThat(dao.getCheckpoint(PROVIDER_ID, ticket.generation, ticket.phase))
            .isEqualTo("jellyfin-v1|101|202|MOVIES|100|300|0|-1")
    }

    @Test
    fun `superseding request fences the old owner and records a new generation`() = runTest {
        insertProvider()
        val dao = database.providerWorkflowDao()
        val oldTicket = dao.request(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.PRIMARY_CATALOG,
            reason = ProviderWorkflowReason.PERIODIC,
            now = 100L
        )
        val oldLease = checkNotNull(
            dao.claim(oldTicket, "old-owner", 101L, 1_000L, staleHeartbeatBefore = 0L)
        )

        val newTicket = dao.request(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.PREPARE,
            reason = ProviderWorkflowReason.CONFIG_CHANGE,
            now = 200L,
            supersede = true,
            priority = 100,
            force = true
        )

        assertThat(newTicket.generation).isEqualTo(oldTicket.generation + 1L)
        assertThat(
            dao.renewLease(
                providerId = oldLease.providerId,
                generation = oldLease.generation,
                phase = oldLease.phase,
                token = oldLease.token,
                now = 201L,
                expiresAt = 1_201L
            )
        ).isEqualTo(0)
        assertThat(dao.complete(oldLease, now = 202L)).isFalse()
        assertThat(dao.getPhases(PROVIDER_ID, oldTicket.generation).single().state)
            .isEqualTo(ProviderWorkflowPhaseState.SUPERSEDED)

        val workflow = dao.getWorkflow(PROVIDER_ID)!!
        assertThat(workflow.generation).isEqualTo(newTicket.generation)
        assertThat(workflow.reason).isEqualTo(ProviderWorkflowReason.CONFIG_CHANGE)
        assertThat(workflow.priority).isEqualTo(100)
        assertThat(workflow.force).isTrue()
        assertThat(workflow.leaseToken).isNull()
    }

    @Test
    fun `ordinary requests join the current generation and serialize added phases`() = runTest {
        insertProvider()
        val dao = database.providerWorkflowDao()
        val catalog = dao.request(
            PROVIDER_ID,
            ProviderWorkflowPhase.PRIMARY_CATALOG,
            ProviderWorkflowReason.STARTUP,
            now = 100L
        )
        val epg = dao.request(
            PROVIDER_ID,
            ProviderWorkflowPhase.EPG,
            ProviderWorkflowReason.MANUAL,
            now = 101L,
            priority = 50
        )

        assertThat(epg.generation).isEqualTo(catalog.generation)
        assertThat(dao.getPhases(PROVIDER_ID, catalog.generation).map { it.phase })
            .containsExactly(ProviderWorkflowPhase.PRIMARY_CATALOG, ProviderWorkflowPhase.EPG)

        val catalogLease = checkNotNull(
            dao.claim(catalog, "catalog", 102L, 1_000L, staleHeartbeatBefore = 0L)
        )
        assertThat(dao.claim(epg, "epg-early", 103L, 1_000L, staleHeartbeatBefore = 0L))
            .isNull()
        assertThat(dao.complete(catalogLease, now = 104L)).isTrue()
        assertThat(dao.getWorkflow(PROVIDER_ID)!!.state).isEqualTo(ProviderWorkflowState.PENDING)

        val epgLease = checkNotNull(
            dao.claim(epg, "epg", 105L, 1_000L, staleHeartbeatBefore = 0L)
        )
        assertThat(dao.complete(epgLease, now = 106L)).isTrue()
        assertThat(dao.getWorkflow(PROVIDER_ID)!!.state).isEqualTo(ProviderWorkflowState.SUCCEEDED)
    }

    @Test
    fun `recovery candidates include expired stale invalid and future heartbeats`() = runTest {
        val dao = database.providerWorkflowDao()
        val now = 10_000L
        val staleBefore = 9_000L
        val heartbeatByProvider = mapOf(
            1L to 10_000L,
            2L to staleBefore,
            3L to 0L,
            4L to 10_001L
        )

        heartbeatByProvider.forEach { (providerId, heartbeat) ->
            insertProvider(providerId)
            val ticket = dao.request(
                providerId,
                ProviderWorkflowPhase.PRIMARY_CATALOG,
                ProviderWorkflowReason.PERIODIC,
                now = heartbeat
            )
            checkNotNull(
                dao.claim(
                    ticket,
                    "owner-$providerId",
                    heartbeat,
                    leaseDurationMs = if (providerId == 3L) 1L else 10_000L,
                    staleHeartbeatBefore = Long.MIN_VALUE
                )
            )
        }

        val candidates = dao.getRecoveryCandidates(now, staleBefore)

        assertThat(candidates.map { it.providerId }).containsExactly(2L, 3L, 4L)
        assertThat(candidates.map { it.providerId }).doesNotContain(1L)
    }

    @Test
    fun `retryable failure releases ownership and can be claimed again`() = runTest {
        insertProvider()
        val dao = database.providerWorkflowDao()
        val ticket: ProviderWorkflowTicket = dao.request(
            PROVIDER_ID,
            ProviderWorkflowPhase.MOVIE_INDEX,
            ProviderWorkflowReason.REPAIR,
            now = 100L
        )
        val firstLease = checkNotNull(
            dao.claim(ticket, "first", 101L, 1_000L, staleHeartbeatBefore = 0L)
        )

        assertThat(
            dao.fail(
                lease = firstLease,
                now = 102L,
                errorCode = "NETWORK",
                errorMessage = "temporary",
                retryable = true,
                checkpoint = "page=4"
            )
        ).isTrue()

        val retriedLease = dao.claim(ticket, "second", 103L, 1_000L, staleHeartbeatBefore = 0L)
        assertThat(retriedLease).isNotNull()
        assertThat(dao.getPhases(PROVIDER_ID, ticket.generation).single().attemptCount)
            .isEqualTo(2)
    }

    @Test
    fun `restarted request reclaims stale phase but does not disturb live owner`() = runTest {
        insertProvider()
        val dao = database.providerWorkflowDao()
        val original = dao.request(
            PROVIDER_ID,
            ProviderWorkflowPhase.EPG,
            ProviderWorkflowReason.PERIODIC,
            now = 100L
        )
        checkNotNull(
            dao.claim(original, "old-process", 101L, 100L, staleHeartbeatBefore = 0L)
        )

        val liveJoin = dao.request(
            PROVIDER_ID,
            ProviderWorkflowPhase.EPG,
            ProviderWorkflowReason.RECOVERY,
            now = 150L,
            staleHeartbeatBefore = 0L
        )
        assertThat(dao.claim(liveJoin, "too-early", 150L, 100L, staleHeartbeatBefore = 0L))
            .isNull()
        assertThat(dao.getPhases(PROVIDER_ID, original.generation).single().state)
            .isEqualTo(ProviderWorkflowPhaseState.RUNNING)

        val restarted = dao.request(
            PROVIDER_ID,
            ProviderWorkflowPhase.EPG,
            ProviderWorkflowReason.RECOVERY,
            now = 202L,
            staleHeartbeatBefore = 150L
        )
        val recoveredLease = dao.claim(
            restarted,
            "new-process",
            now = 202L,
            leaseDurationMs = 100L,
            staleHeartbeatBefore = 150L
        )

        assertThat(recoveredLease).isNotNull()
        assertThat(dao.getPhases(PROVIDER_ID, original.generation).single().attemptCount)
            .isEqualTo(2)
    }

    @Test
    fun `lower priority manual request cannot supersede configuration workflow`() = runTest {
        insertProvider()
        val dao = database.providerWorkflowDao()
        val config = dao.request(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.PREPARE,
            reason = ProviderWorkflowReason.CONFIG_CHANGE,
            now = 100L,
            supersede = true,
            priority = 100,
            force = true
        )

        val manual = dao.request(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.PRIMARY_CATALOG,
            reason = ProviderWorkflowReason.MANUAL,
            now = 101L,
            supersede = true,
            priority = 50,
            force = true
        )

        assertThat(manual.admitted).isFalse()
        assertThat(manual.generation).isEqualTo(config.generation)
        assertThat(dao.claim(manual, "manual", 102L, 1_000L, staleHeartbeatBefore = 0L))
            .isNull()
        val workflow = dao.getWorkflow(PROVIDER_ID)!!
        assertThat(workflow.reason).isEqualTo(ProviderWorkflowReason.CONFIG_CHANGE)
        assertThat(workflow.priority).isEqualTo(100)
        assertThat(dao.getPhases(PROVIDER_ID, config.generation).map { it.phase })
            .containsExactly(ProviderWorkflowPhase.PREPARE)
    }

    private suspend fun insertProvider(providerId: Long = PROVIDER_ID) {
        database.providerDao().insert(
            ProviderEntity(
                id = providerId,
                name = "Provider $providerId",
                type = ProviderType.M3U,
                serverUrl = "https://example.com/$providerId"
            )
        )
    }

    private companion object {
        const val PROVIDER_ID = 1L
    }
}
