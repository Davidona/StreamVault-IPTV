package com.streamvault.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LiveWatchDelta
import com.streamvault.domain.model.LiveWatchTarget
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LiveWatchUsageRepositoryImplTest {
    @get:Rule val folder = TemporaryFolder()
    private val target = LiveWatchTarget(7, 1, 101, "news")
    private val incognito = MutableStateFlow(false)
    private val dayMs = 86_400_000L
    private var today = 100L
    private fun delta(seq: Long, ms: Long = 10_000, day: Long = today, row: LiveWatchTarget = target) =
        LiveWatchDelta(row, "session", seq, ms, day * dayMs + 12_000, day)

    @Test fun `privacy source isolation and clearing one logical channel includes quality aliases`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "usage.preferences_pb") }
        val repository = LiveWatchUsageRepositoryImpl(store, incognito) { today * dayMs + 30_000 }
        repository.record(delta(1))
        repository.record(delta(2, 5_000, row = target.copy(contentId = 2, streamId = 102)))
        repository.record(delta(1, 3_000, row = target.copy(providerId = 8)))
        assertThat(repository.observe(7).first().values.sumOf { it.watchedMs }).isEqualTo(15_000)
        incognito.value = true
        repository.record(delta(3))
        assertThat(repository.observe(7).first()).isEmpty()
        incognito.value = false
        assertThat(repository.observe(7).first().values.sumOf { it.watchedMs }).isEqualTo(15_000)
        repository.clearContent(7, 1)
        assertThat(repository.observe(7).first()).isEmpty()
        assertThat(repository.observe(8).first()["stream:101"]?.watchedMs).isEqualTo(3_000)
        repository.clearAll()
        assertThat(repository.observe(8).first()).isEmpty()
    }

    @Test fun `thirty day totals age out while qualified recency survives until history is cleared`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "window.preferences_pb") }
        val repository = LiveWatchUsageRepositoryImpl(store, incognito) { today * dayMs + 30_000 }
        repository.record(delta(1, 1_000, day = 71))
        repository.record(delta(2, 99_000, day = 70))
        repository.record(delta(3, 2_000))
        assertThat(repository.observe(7).first()["stream:101"]?.watchedMs).isEqualTo(3_000)
        today = 101
        assertThat(repository.observe(7).first()["stream:101"]?.watchedMs).isEqualTo(2_000)
        today = 131
        val old = repository.observe(7).first().getValue("stream:101")
        assertThat(old.watchedMs).isEqualTo(0)
        assertThat(old.lastWatchedAtMs).isEqualTo(100 * dayMs + 12_000)
        repository.clearProvider(7)
        assertThat(repository.observe(7).first()).isEmpty()
    }

    @Test fun `persisted sequence guards ignore duplicate callbacks across repository recreation`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "dedupe.preferences_pb") }
        val repository = LiveWatchUsageRepositoryImpl(store, incognito) { today * dayMs + 30_000 }
        val first = async { repository.record(delta(1)) }
        val duplicate = async { repository.record(delta(1)) }
        first.await(); duplicate.await()
        val recreated = LiveWatchUsageRepositoryImpl(store, incognito) { today * dayMs + 30_000 }
        recreated.record(delta(1))
        assertThat(recreated.observe(7).first()["stream:101"]?.watchedMs).isEqualTo(10_000)
        recreated.record(delta(2, 5_000))
        assertThat(recreated.observe(7).first()["stream:101"]?.watchedMs).isEqualTo(15_000)
    }

    @Test fun `future schemas are hidden from browsing and are not overwritten by recording`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "future.preferences_pb") }
        val key = stringPreferencesKey("provider:7")
        val future = "{\"version\":2}"
        store.edit { it[key] = future }
        val repository = LiveWatchUsageRepositoryImpl(store, incognito) { today * dayMs + 30_000 }
        assertThat(repository.observe(7).first()).isEmpty()
        assertThat(runCatching { repository.record(delta(1)) }.exceptionOrNull()).isNotNull()
        assertThat(store.data.first()[key]).isEqualTo(future)
    }

    @Test fun `only explicit history clearing changes the browsing reset version`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "resets.preferences_pb") }
        val repository = LiveWatchUsageRepositoryImpl(store, incognito) { today * dayMs + 30_000 }
        assertThat(repository.resetVersion.first()).isEqualTo(0)
        repository.record(delta(1))
        assertThat(repository.resetVersion.first()).isEqualTo(0)
        repository.clearProvider(7)
        assertThat(repository.resetVersion.first()).isEqualTo(1)
        repository.clearContent(7, 1)
        assertThat(repository.resetVersion.first()).isEqualTo(2)
        repository.clearAll()
        assertThat(repository.resetVersion.first()).isEqualTo(3)
    }
}
