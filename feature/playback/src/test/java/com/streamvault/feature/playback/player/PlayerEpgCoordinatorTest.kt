package com.streamvault.feature.playback.player

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.Program
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.EpgRepository
import com.streamvault.domain.repository.ProviderRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerEpgCoordinatorTest {

    @Test
    fun `request publishes local programs and cancellation stops refresh`() = runTest {
        val epgRepository = mock<EpgRepository>()
        val providerRepository = mock<ProviderRepository>()
        val program = Program(channelId = "epg-1", title = "Now", startTime = 1L, endTime = 2L)
        whenever(
            epgRepository.getResolvedProgramsForPlaybackChannel(
                providerId = eq(7L),
                internalChannelId = eq(42L),
                epgChannelId = eq("epg-1"),
                streamId = eq(99L),
                startTime = org.mockito.kotlin.any(),
                endTime = org.mockito.kotlin.any()
            )
        ).thenReturn(listOf(program))
        val coordinator = PlayerEpgCoordinator(epgRepository, providerRepository)
        val received = mutableListOf<List<Program>>()

        coordinator.request(
            scope = this,
            sessionId = 1L,
            requestKey = EpgRequestKey(7L, 42L, "epg-1", 99L),
            onPrograms = { programs, _ -> received += programs },
            onClear = {}
        )
        runCurrent()
        coordinator.cancel()

        assertThat(received).containsExactly(listOf(program))
    }

    @Test
    fun `empty local guide falls back to provider-native programs`() = runTest {
        val epgRepository = mock<EpgRepository>()
        val providerRepository = mock<ProviderRepository>()
        val program = Program(channelId = "epg-1", title = "Remote", startTime = 3L, endTime = 4L)
        whenever(
            epgRepository.getResolvedProgramsForPlaybackChannel(
                providerId = eq(7L),
                internalChannelId = eq(42L),
                epgChannelId = eq("epg-1"),
                streamId = eq(99L),
                startTime = org.mockito.kotlin.any(),
                endTime = org.mockito.kotlin.any()
            )
        ).thenReturn(emptyList())
        whenever(
            providerRepository.getProgramsForLiveStream(
                providerId = 7L,
                streamId = 99L,
                epgChannelId = "epg-1",
                limit = 12
            )
        ).thenReturn(Result.Success(listOf(program)))
        val coordinator = PlayerEpgCoordinator(epgRepository, providerRepository)
        val received = mutableListOf<List<Program>>()

        coordinator.request(
            scope = this,
            sessionId = 1L,
            requestKey = EpgRequestKey(7L, 42L, "epg-1", 99L),
            onPrograms = { programs, _ -> received += programs },
            onClear = {}
        )
        runCurrent()
        coordinator.cancel()

        assertThat(received).containsExactly(listOf(program))
    }

    @Test
    fun `same request key from a newer session rejects stale results`() = runTest {
        val firstRequestStarted = CompletableDeferred<Unit>()
        val releaseFirstRequest = CompletableDeferred<Unit>()
        val providerRepository = mock<ProviderRepository>()
        val staleProgram = Program(channelId = "epg-1", title = "Stale", startTime = 1L, endTime = 2L)
        val currentProgram = Program(channelId = "epg-1", title = "Current", startTime = 3L, endTime = 4L)
        val epgRepository = object : EpgRepository {
            private var requestCount = 0

            override suspend fun getResolvedProgramsForPlaybackChannel(
                providerId: Long,
                internalChannelId: Long,
                epgChannelId: String?,
                streamId: Long,
                startTime: Long,
                endTime: Long
            ): List<Program> {
                return if (requestCount++ == 0) {
                    firstRequestStarted.complete(Unit)
                    withContext(NonCancellable) { releaseFirstRequest.await() }
                    listOf(staleProgram)
                } else {
                    listOf(currentProgram)
                }
            }

            override fun getProgramsForChannel(
                providerId: Long,
                channelId: String,
                startTime: Long,
                endTime: Long
            ): Flow<List<Program>> = emptyFlow()

            override fun getProgramsForChannels(
                providerId: Long,
                channelIds: List<String>,
                startTime: Long,
                endTime: Long
            ): Flow<Map<String, List<Program>>> = emptyFlow()

            override suspend fun getProgramsForChannelsSnapshot(
                providerId: Long,
                channelIds: List<String>,
                startTime: Long,
                endTime: Long
            ): Map<String, List<Program>> = emptyMap()

            override fun getProgramsByCategory(
                providerId: Long,
                categoryId: Long,
                startTime: Long,
                endTime: Long
            ): Flow<List<Program>> = emptyFlow()

            override fun searchPrograms(
                providerId: Long,
                query: String,
                startTime: Long,
                endTime: Long,
                categoryId: Long?,
                limit: Int
            ): Flow<List<Program>> = emptyFlow()

            override fun getNowPlaying(providerId: Long, channelId: String): Flow<Program?> = flowOf(null)

            override fun getNowPlayingForChannels(
                providerId: Long,
                channelIds: List<String>
            ): Flow<Map<String, Program?>> = flowOf(emptyMap())

            override suspend fun getNowPlayingForChannelsSnapshot(
                providerId: Long,
                channelIds: List<String>
            ): Map<String, Program?> = emptyMap()

            override fun getNowAndNext(providerId: Long, channelId: String): Flow<Pair<Program?, Program?>> =
                flowOf(null to null)

            override suspend fun refreshEpg(providerId: Long, epgUrl: String): Result<Unit> = Result.Success(Unit)

            override suspend fun clearOldPrograms(beforeTime: Long) = Unit

            override fun onProviderDeleted(providerId: Long) = Unit

            override suspend fun getResolvedProgramsForChannels(
                providerId: Long,
                channelIds: List<Long>,
                startTime: Long,
                endTime: Long
            ): Map<String, List<Program>> = emptyMap()
        }
        val coordinator = PlayerEpgCoordinator(epgRepository, providerRepository)
        val received = mutableListOf<List<Program>>()
        val requestKey = EpgRequestKey(7L, 42L, "epg-1", 99L)

        coordinator.request(
            scope = this,
            sessionId = 1L,
            requestKey = requestKey,
            onPrograms = { programs, _ -> received += programs },
            onClear = {}
        )
        runCurrent()
        firstRequestStarted.await()

        coordinator.request(
            scope = this,
            sessionId = 2L,
            requestKey = requestKey,
            onPrograms = { programs, _ -> received += programs },
            onClear = {}
        )
        runCurrent()
        releaseFirstRequest.complete(Unit)
        runCurrent()
        coordinator.cancel()

        assertThat(received).containsExactly(listOf(currentProgram))
    }
}
