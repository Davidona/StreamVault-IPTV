package com.streamvault.data.sync

import androidx.room.Room
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.streamvault.data.local.StreamVaultDatabase
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.data.local.entity.ProviderWorkflowPhase
import com.streamvault.data.local.entity.ProviderWorkflowPhaseState
import com.streamvault.data.local.entity.ProviderWorkflowReason
import com.streamvault.data.local.entity.ProviderWorkflowState
import com.streamvault.data.remote.jellyfin.JellyfinCatalogLimitException
import com.streamvault.data.remote.jellyfin.JellyfinItemLimitException
import com.streamvault.data.remote.jellyfin.JellyfinPaginationException
import com.streamvault.data.remote.jellyfin.JellyfinResponseTooLargeException
import com.streamvault.domain.model.ProviderType
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProviderWorkflowRunnerTest {
    private lateinit var database: StreamVaultDatabase
    private lateinit var runner: ProviderWorkflowRunner

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            StreamVaultDatabase::class.java
        ).allowMainThreadQueries().build()
        runner = ProviderWorkflowRunner(database.providerWorkflowDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `cooperative deferral clears the lease without publishing a failure and can immediately resume`() = runTest {
        insertProvider()
        val disposition = runner.execute(PROVIDER_ID, ProviderWorkflowPhase.EPG, ProviderWorkflowReason.PERIODIC) {
            ProviderWorkflowOutcome.Deferred
        }
        assertThat(disposition).isEqualTo(ProviderWorkflowDisposition.RETRY)
        val workflow = database.providerWorkflowDao().getWorkflow(PROVIDER_ID)!!
        assertThat(workflow.state).isEqualTo(ProviderWorkflowState.PENDING)
        assertThat(workflow.leaseToken).isNull()
        assertThat(workflow.lastErrorCode).isNull()
        assertThat(database.providerWorkflowDao().getPhases(PROVIDER_ID, workflow.generation).single().state)
            .isEqualTo(ProviderWorkflowPhaseState.PENDING)
        assertThat(runner.execute(PROVIDER_ID, ProviderWorkflowPhase.EPG, ProviderWorkflowReason.PERIODIC) {
            ProviderWorkflowOutcome.Success()
        }).isEqualTo(ProviderWorkflowDisposition.SUCCEEDED)
    }

    @Test
    fun `successful execution publishes the terminal phase and workflow`() = runTest {
        insertProvider()

        val disposition = runner.execute(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.EPG,
            reason = ProviderWorkflowReason.PERIODIC
        ) {
            ProviderWorkflowOutcome.Success(checkpoint = "window=complete")
        }

        assertThat(disposition).isEqualTo(ProviderWorkflowDisposition.SUCCEEDED)
        val workflow = database.providerWorkflowDao().getWorkflow(PROVIDER_ID)!!
        assertThat(workflow.state).isEqualTo(ProviderWorkflowState.SUCCEEDED)
        val phase = database.providerWorkflowDao()
            .getPhases(PROVIDER_ID, workflow.generation)
            .single()
        assertThat(phase.state).isEqualTo(ProviderWorkflowPhaseState.SUCCEEDED)
        assertThat(phase.checkpoint).isEqualTo("window=complete")
    }

    @Test
    fun `retryable execution publishes durable retry state`() = runTest {
        insertProvider()

        val disposition = runner.execute(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.CONTENT_INDEX,
            reason = ProviderWorkflowReason.PERIODIC
        ) {
            ProviderWorkflowOutcome.Failure(
                code = "NETWORK",
                message = "offline",
                cause = IOException("offline")
            )
        }

        assertThat(disposition).isEqualTo(ProviderWorkflowDisposition.RETRY)
        val workflow = database.providerWorkflowDao().getWorkflow(PROVIDER_ID)!!
        assertThat(workflow.state).isEqualTo(ProviderWorkflowState.PENDING)
        assertThat(workflow.lastErrorCode).isEqualTo("NETWORK")
        assertThat(
            database.providerWorkflowDao()
                .getPhases(PROVIDER_ID, workflow.generation)
                .single()
                .state
        ).isEqualTo(ProviderWorkflowPhaseState.FAILED_RETRYABLE)
    }

    @Test
    fun `jellyfin bounded input violations are permanent while transport IO remains retryable`() {
        assertThat(ProviderWorkFailureClassifier.isRetryable(IOException("offline"))).isTrue()
        assertThat(ProviderWorkFailureClassifier.isRetryable(JellyfinPaginationException("unstable"))).isFalse()
        assertThat(ProviderWorkFailureClassifier.isRetryable(JellyfinCatalogLimitException("too many"))).isFalse()
        assertThat(ProviderWorkFailureClassifier.isRetryable(JellyfinResponseTooLargeException(5, 4))).isFalse()
        assertThat(ProviderWorkFailureClassifier.isRetryable(JellyfinItemLimitException("item too large"))).isFalse()
    }

    @Test
    fun `supersession during execution prevents old completion publication`() = runTest {
        insertProvider()
        val dao = database.providerWorkflowDao()

        val disposition = runner.execute(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.PRIMARY_CATALOG,
            reason = ProviderWorkflowReason.PERIODIC
        ) {
            dao.request(
                providerId = PROVIDER_ID,
                phase = ProviderWorkflowPhase.PREPARE,
                reason = ProviderWorkflowReason.CONFIG_CHANGE,
                now = System.currentTimeMillis(),
                supersede = true,
                force = true
            )
            ProviderWorkflowOutcome.Success()
        }

        assertThat(disposition).isEqualTo(ProviderWorkflowDisposition.SUPERSEDED)
        val workflow = dao.getWorkflow(PROVIDER_ID)!!
        assertThat(workflow.generation).isEqualTo(2L)
        assertThat(workflow.state).isEqualTo(ProviderWorkflowState.PENDING)
    }

    @Test
    fun `superseded commit fence exits without publishing a retry or failure`() = runTest {
        insertProvider()
        val dao = database.providerWorkflowDao()
        val disposition = runner.execute(PROVIDER_ID, ProviderWorkflowPhase.PRIMARY_CATALOG, ProviderWorkflowReason.PERIODIC) {
            dao.request(providerId = PROVIDER_ID, phase = ProviderWorkflowPhase.PREPARE,
                reason = ProviderWorkflowReason.CONFIG_CHANGE, now = System.currentTimeMillis(), supersede = true, force = true)
            throw ProviderWorkflowSupersededException(PROVIDER_ID, 1)
        }
        assertThat(disposition).isEqualTo(ProviderWorkflowDisposition.SUPERSEDED)
        val workflow = dao.getWorkflow(PROVIDER_ID)!!
        assertThat(workflow.generation).isEqualTo(2L)
        assertThat(workflow.state).isEqualTo(ProviderWorkflowState.PENDING)
        assertThat(workflow.lastErrorCode).isNull()
    }

    @Test
    fun `overlapping execution is told to retry while current owner retains lease`() = runTest {
        insertProvider()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            runner.execute(
                providerId = PROVIDER_ID,
                phase = ProviderWorkflowPhase.EPG,
                reason = ProviderWorkflowReason.PERIODIC
            ) {
                started.complete(Unit)
                release.await()
                ProviderWorkflowOutcome.Success()
            }
        }
        started.await()

        val overlapping = runner.execute(
            providerId = PROVIDER_ID,
            phase = ProviderWorkflowPhase.EPG,
            reason = ProviderWorkflowReason.MANUAL,
            priority = 50
        ) {
            ProviderWorkflowOutcome.Success()
        }

        assertThat(overlapping).isEqualTo(ProviderWorkflowDisposition.BUSY)
        release.complete(Unit)
        assertThat(first.await()).isEqualTo(ProviderWorkflowDisposition.SUCCEEDED)
    }

    @Test
    fun `cancellation is rethrown without publishing workflow failure`() = runTest {
        insertProvider()
        val entered = CompletableDeferred<Unit>()
        var cancellation: CancellationException? = null
        val worker = launch {
            try {
                runner.execute(
                    providerId = PROVIDER_ID,
                    phase = ProviderWorkflowPhase.EPG,
                    reason = ProviderWorkflowReason.PERIODIC
                ) {
                    entered.complete(Unit)
                    kotlinx.coroutines.awaitCancellation()
                }
            } catch (error: CancellationException) {
                cancellation = error
                throw error
            }
        }

        entered.await()
        worker.cancelAndJoin()

        assertThat(cancellation).isNotNull()
        val workflow = database.providerWorkflowDao().getWorkflow(PROVIDER_ID)!!
        assertThat(workflow.state).isEqualTo(ProviderWorkflowState.RUNNING)
        assertThat(workflow.lastErrorCode).isNull()
        assertThat(workflow.lastErrorMessage).isNull()
        assertThat(
            database.providerWorkflowDao()
                .getPhases(PROVIDER_ID, workflow.generation)
                .single()
                .state
        ).isEqualTo(ProviderWorkflowPhaseState.RUNNING)
    }

    @Test
    fun `provider worker cancellation matrix publishes no retry or error for any provider phase`() = runTest {
        val workerPhases = listOf(
            "background EPG" to ProviderWorkflowPhase.EPG,
            "provider catalog" to ProviderWorkflowPhase.PRIMARY_CATALOG,
            "Stalker index" to ProviderWorkflowPhase.CONTENT_INDEX,
            "Xtream index" to ProviderWorkflowPhase.MOVIE_INDEX
        )

        workerPhases.forEachIndexed { index, (owner, phase) ->
            val providerId = PROVIDER_ID + index + 1
            insertProvider(providerId)
            val entered = CompletableDeferred<Unit>()
            var cancellation: CancellationException? = null
            val worker = launch {
                try {
                    runner.execute(
                        providerId = providerId,
                        phase = phase,
                        reason = ProviderWorkflowReason.PERIODIC
                    ) {
                        entered.complete(Unit)
                        kotlinx.coroutines.awaitCancellation()
                    }
                } catch (error: CancellationException) {
                    cancellation = error
                    throw error
                }
            }

            entered.await()
            worker.cancelAndJoin()

            assertThat(cancellation).isNotNull()
            val workflow = database.providerWorkflowDao().getWorkflow(providerId)!!
            assertWithMessage("$owner cancellation must leave the lease running")
                .that(workflow.state)
                .isEqualTo(ProviderWorkflowState.RUNNING)
            assertThat(workflow.lastErrorCode).isNull()
            assertThat(workflow.lastErrorMessage).isNull()
            assertThat(
                database.providerWorkflowDao()
                    .getPhases(providerId, workflow.generation)
                    .single()
                    .state
            ).isEqualTo(ProviderWorkflowPhaseState.RUNNING)
        }
    }

    private suspend fun insertProvider(providerId: Long = PROVIDER_ID) {
        database.providerDao().insert(
            ProviderEntity(
                id = providerId,
                name = "Provider",
                type = ProviderType.M3U,
                serverUrl = "https://example.com"
            )
        )
    }

    private companion object {
        const val PROVIDER_ID = 1L
    }
}
