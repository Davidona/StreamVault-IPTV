package com.streamvault.domain.policy

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import com.streamvault.domain.repository.LiveChannelOrderRepository
import com.streamvault.domain.repository.ProviderRepository
import com.streamvault.domain.repository.LiveWatchUsageRepository
import com.streamvault.domain.settings.SettingsPreferences
import com.streamvault.domain.usecase.ObserveLiveChannelOrdering
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.launch
import org.junit.Test
import java.lang.reflect.Proxy

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ObserveLiveChannelOrderingTest {
    @Test fun `shared catalog follows revisions without changing channel identity numbers or membership`() = runTest {
        val source = LegacyProvider(id = 7, name = "Provider", type = ProviderType.M3U, serverUrl = "https://example.test", createdAt = 123)
        val saved = MutableStateFlow(LiveChannelOrder())
        val defaultMode = MutableStateFlow(LiveChannelOrderMode.TITLE_ASC)
        var requestedScope: LiveChannelOrderScope? = null
        val providers = stub(ProviderRepository::class.java) { method, _ ->
            check(method == "getProvider")
            source
        }
        val orders = stub(LiveChannelOrderRepository::class.java) { method, args ->
            check(method == "observe")
            requestedScope = args!![0] as LiveChannelOrderScope
            saved
        }
        val preferences = stub(SettingsPreferences::class.java) { method, _ ->
            check(method == "getDefaultLiveChannelOrder")
            defaultMode
        }
        val rows = listOf(Channel(1, "Zulu", streamId = 11, number = 101), Channel(2, "Alpha", streamId = 22, number = 202))
        val catalog = ObserveLiveChannelOrdering(providers, orders, preferences)(7, 12, flowOf(rows))
        assertThat(catalog.first()).containsExactly(rows[1], rows[0]).inOrder()
        saved.value = LiveChannelOrder(LiveChannelOrderMode.MANUAL, listOf("stream:11", "stream:22"), 1)
        defaultMode.value = LiveChannelOrderMode.TITLE_DESC
        assertThat(catalog.first()).containsExactlyElementsIn(rows).inOrder()
        assertThat(requestedScope).isEqualTo(liveChannelOrderScope(source, Category(12, name = "")))
    }

    @Test fun `usage ticks stay frozen until reentry reset or explicit privacy and mode changes`() = runTest {
        val source = LegacyProvider(7, "Provider", ProviderType.M3U, serverUrl = "https://example.test", createdAt = 123)
        val rows = listOf(Channel(1, "One", streamId = 1), Channel(2, "Two", streamId = 2))
        val saved = MutableStateFlow(LiveChannelOrder())
        val privacy = MutableStateFlow(false)
        val providers = stub(ProviderRepository::class.java) { _, _ -> source }
        val orders = stub(LiveChannelOrderRepository::class.java) { _, _ -> saved }
        val preferences = stub(SettingsPreferences::class.java) { method, _ -> when (method) {
            "getDefaultLiveChannelOrder" -> flowOf(LiveChannelOrderMode.MOST_WATCHED)
            "isIncognitoMode", "getIsIncognitoMode" -> privacy
            else -> error(method)
        } }
        val stats = SnapshotUsage()
        stats.values.value = mapOf("stream:2" to LiveWatchUsage(100, 1000))
        val catalog = ObserveLiveChannelOrdering(providers, orders, preferences, stats)(7, 12, flowOf(rows))
        var current = emptyList<Channel>()
        backgroundScope.launch { catalog.collect { current = it } }
        runCurrent()
        assertThat(current.first().id).isEqualTo(2L)
        stats.values.value = mapOf("stream:1" to LiveWatchUsage(200, 2000))
        runCurrent()
        assertThat(current.first().id).isEqualTo(2L)
        assertThat(catalog.first().first().id).isEqualTo(1L)
        stats.version.value++
        runCurrent()
        assertThat(current.first().id).isEqualTo(1L)
        stats.values.value = mapOf("stream:2" to LiveWatchUsage(300, 3000))
        stats.version.value++
        runCurrent()
        assertThat(current.first().id).isEqualTo(2L)
        privacy.value = true
        runCurrent()
        assertThat(current).isEqualTo(rows)
        privacy.value = false
        runCurrent()
        assertThat(current.first().id).isEqualTo(2L)
        stats.clearAll()
        runCurrent()
        assertThat(current).isEqualTo(rows)
        saved.value = LiveChannelOrder(LiveChannelOrderMode.MANUAL, listOf("stream:2", "stream:1"), 1)
        runCurrent()
        privacy.value = true
        runCurrent()
        assertThat(current.map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    private class SnapshotUsage : LiveWatchUsageRepository {
        val values = MutableStateFlow<Map<String, LiveWatchUsage>>(emptyMap())
        val version = MutableStateFlow(0L)
        override val resetVersion: Flow<Long> get() = version
        override fun observe(providerId: Long): Flow<Map<String, LiveWatchUsage>> = values
        override suspend fun record(delta: LiveWatchDelta) = Unit
        override suspend fun clearAll() { values.value = emptyMap(); version.value++ }
        override suspend fun clearProvider(providerId: Long) = clearAll()
        override suspend fun clearContent(providerId: Long, contentId: Long) = clearAll()
    }
    @Suppress("UNCHECKED_CAST")
    private fun <T> stub(type: Class<T>, invoke: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args -> invoke(method.name, args) } as T
}
