package com.streamvault.data.repository

import com.google.common.truth.Truth.assertThat
import com.streamvault.data.local.dao.ProviderDao
import com.streamvault.data.local.entity.ProviderEntity
import com.streamvault.domain.model.Category
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.ProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChannelCategoryFlowCacheTest {

    private val providerDao: ProviderDao = mock()
    private val traceReporter: CategoryFlowTraceReporter = mock()

    @Test
    fun `same provider reuses one shared flow`() = runTest {
        whenever(providerDao.getAll()).thenReturn(flowOf(listOf(provider(7L))))
        val cache = ChannelCategoryFlowCache(providerDao, backgroundScope, traceReporter)
        var factoryCalls = 0

        val first = cache.getOrCreate(7L) {
            factoryCalls++
            flowOf(listOf(category(1L, "News")))
        }
        val second = cache.getOrCreate(7L) {
            factoryCalls++
            flowOf(listOf(category(2L, "Sports")))
        }

        assertThat(second === first).isTrue()
        assertThat(factoryCalls).isEqualTo(1)
    }

    @Test
    fun `different providers receive independent shared flows`() = runTest {
        whenever(providerDao.getAll()).thenReturn(flowOf(listOf(provider(7L), provider(8L))))
        val cache = ChannelCategoryFlowCache(providerDao, backgroundScope, traceReporter)

        val first = cache.getOrCreate(7L) { flowOf(listOf(category(1L, "News"))) }
        val second = cache.getOrCreate(8L) { flowOf(listOf(category(2L, "Sports"))) }

        assertThat(first === second).isFalse()
        assertThat(first.first()).containsExactly(category(1L, "News"))
        assertThat(second.first()).containsExactly(category(2L, "Sports"))
    }

    @Test
    fun `active subscribers receive upstream refreshes and equivalent values are suppressed`() = runTest {
        whenever(providerDao.getAll()).thenReturn(flowOf(listOf(provider(7L))))
        val cacheScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val cache = ChannelCategoryFlowCache(providerDao, cacheScope, traceReporter)
        val upstream = MutableSharedFlow<List<Category>>(extraBufferCapacity = 1)
        val observed = mutableListOf<List<Category>>()
        val job = launch {
            cache.getOrCreate(7L) { upstream }
                .take(2)
                .toList(observed)
        }
        advanceUntilIdle()

        upstream.emit(listOf(category(1L, "News")))
        advanceUntilIdle()
        upstream.emit(listOf(category(1L, "News")))
        advanceUntilIdle()
        assertThat(observed).containsExactlyElementsIn(listOf(listOf(category(1L, "News"))))

        upstream.emit(listOf(category(2L, "Sports")))
        job.join()

        assertThat(observed).containsExactlyElementsIn(
            listOf(
                listOf(category(1L, "News")),
                listOf(category(2L, "Sports"))
            )
        ).inOrder()
        cacheScope.cancel()
    }

    @Test
    fun `second subscriber receives the replayed result without another upstream emission`() = runTest {
        whenever(providerDao.getAll()).thenReturn(flowOf(listOf(provider(7L))))
        val cacheScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val cache = ChannelCategoryFlowCache(providerDao, cacheScope, traceReporter)
        val upstream = MutableSharedFlow<List<Category>>(extraBufferCapacity = 1)
        val cached = cache.getOrCreate(7L) { upstream }

        val firstResult = async { cached.first() }
        advanceUntilIdle()
        upstream.emit(listOf(category(1L, "News")))
        assertThat(firstResult.await()).containsExactly(category(1L, "News"))

        assertThat(cached.first()).containsExactly(category(1L, "News"))
        cacheScope.cancel()
    }

    @Test
    fun `provider removal cancels old flow and does not cache while provider is absent`() = runTest {
        val providers = MutableStateFlow(listOf(provider(7L)))
        whenever(providerDao.getAll()).thenReturn(providers)
        val cacheScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val cache = ChannelCategoryFlowCache(providerDao, cacheScope, traceReporter)
        val upstreamStarted = CompletableDeferred<Unit>()
        val upstreamCancelled = CompletableDeferred<Unit>()
        val original = cache.getOrCreate(7L) {
            flow {
                emit(listOf(category(1L, "Old")))
                upstreamStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    upstreamCancelled.complete(Unit)
                }
            }
        }
        val subscription = launch { original.collect() }
        advanceUntilIdle()
        assertThat(upstreamStarted.isCompleted).isTrue()

        providers.value = emptyList()
        advanceUntilIdle()
        assertThat(upstreamCancelled.isCompleted).isTrue()

        var absentFactoryCalls = 0
        cache.getOrCreate(7L) {
            absentFactoryCalls++
            flowOf(listOf(category(2L, "Absent")))
        }
        cache.getOrCreate(7L) {
            absentFactoryCalls++
            flowOf(listOf(category(3L, "Still absent")))
        }
        assertThat(absentFactoryCalls).isEqualTo(2)

        providers.value = listOf(provider(7L))
        advanceUntilIdle()
        var recreatedFactoryCalls = 0
        val recreated = cache.getOrCreate(7L) {
            recreatedFactoryCalls++
            flowOf(listOf(category(4L, "New")))
        }
        val recreatedAgain = cache.getOrCreate(7L) {
            recreatedFactoryCalls++
            flowOf(listOf(category(5L, "Unexpected second flow")))
        }
        assertThat(recreatedAgain === recreated).isTrue()
        assertThat(recreatedFactoryCalls).isEqualTo(1)
        assertThat(recreated.first()).containsExactly(category(4L, "New"))
        subscription.cancel()
        cacheScope.cancel()
    }

    @Test
    fun `cached upstream start is reported when the shared flow begins collection`() = runTest {
        whenever(providerDao.getAll()).thenReturn(flowOf(listOf(provider(7L))))
        val cacheScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val cache = ChannelCategoryFlowCache(providerDao, cacheScope, traceReporter)
        val upstream = MutableSharedFlow<List<Category>>(extraBufferCapacity = 1)
        val cached = cache.getOrCreate(7L) { upstream }

        val firstResult = async { cached.first() }
        advanceUntilIdle()
        upstream.emit(listOf(category(1L, "News")))
        assertThat(firstResult.await()).containsExactly(category(1L, "News"))

        verify(traceReporter, times(1)).onUpstreamStart(7L)
        cacheScope.cancel()
    }

    @Test
    fun `cached upstream stop is reported when the shared flow is cancelled`() = runTest {
        whenever(providerDao.getAll()).thenReturn(flowOf(listOf(provider(7L))))
        val cacheScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val cache = ChannelCategoryFlowCache(providerDao, cacheScope, traceReporter)
        val upstream = flow<List<Category>> { awaitCancellation() }
        val subscription = launch { cache.getOrCreate(7L) { upstream }.collect() }
        advanceUntilIdle()

        subscription.cancel()
        advanceUntilIdle()

        verify(traceReporter, times(1)).onUpstreamStop(7L)
        cacheScope.cancel()
    }

    @Test
    fun `quick subscriber reentry keeps the shared upstream alive`() = runTest {
        whenever(providerDao.getAll()).thenReturn(flowOf(listOf(provider(7L))))
        val cacheScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val cache = ChannelCategoryFlowCache(providerDao, cacheScope, traceReporter)
        val upstream = flow<List<Category>> { awaitCancellation() }
        val cached = cache.getOrCreate(7L) { upstream }

        val firstSubscription = launch { cached.collect() }
        advanceUntilIdle()
        firstSubscription.cancel()
        advanceTimeBy(ChannelCategoryFlowCache.UPSTREAM_STOP_TIMEOUT_MILLIS - 1L)

        val secondSubscription = launch { cached.collect() }
        advanceUntilIdle()

        verify(traceReporter, times(1)).onUpstreamStart(7L)
        verify(traceReporter, times(0)).onUpstreamStop(7L)
        secondSubscription.cancel()
        cacheScope.cancel()
    }

    private fun provider(id: Long) = ProviderEntity(
        id = id,
        name = "Provider $id",
        type = ProviderType.M3U
    )

    private fun category(id: Long, name: String) = Category(
        id = id,
        name = name,
        type = ContentType.LIVE
    )
}
