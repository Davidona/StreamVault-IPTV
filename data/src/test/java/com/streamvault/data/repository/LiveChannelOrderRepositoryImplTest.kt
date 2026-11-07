package com.streamvault.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.LiveChannelOrder
import com.streamvault.domain.model.LiveChannelOrderMode
import com.streamvault.domain.model.LiveChannelOrderScope
import com.streamvault.domain.repository.LiveChannelOrderSaveResult
import java.io.File
import kotlinx.coroutines.async
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
class LiveChannelOrderRepositoryImplTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun `identity migration copy preserves every scope and is retryable`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "identity-copy.preferences_pb") }
        val repository = LiveChannelOrderRepositoryImpl(store)
        val old = LiveChannelOrderScope("combined:legacy-combined-1", "live:all")
        val target = old.copy(sourceKey = "combined:new-uuid")
        repository.reconcileCompleteCatalog(old.sourceKey, mapOf(old.categoryKey to listOf("stream:1", "stream:2")))
        val initial = repository.observe(old).first()
        repository.save(old, initial.revision, initial.copy(mode = LiveChannelOrderMode.MANUAL, manualKeys = listOf("stream:2", "stream:1")))
        repository.initializeIfAbsent(old.copy(categoryKey = "live:favorites"), LiveChannelOrder(LiveChannelOrderMode.MANUAL, listOf("stream:1")))
        repository.migrateSourceIdentity(old.sourceKey, target.sourceKey)
        repository.migrateSourceIdentity(old.sourceKey, target.sourceKey)
        assertThat(repository.observe(target).first()).isEqualTo(repository.observe(old).first())
        assertThat(repository.observe(target.copy(categoryKey = "live:favorites")).first())
            .isEqualTo(repository.observe(old.copy(categoryKey = "live:favorites")).first())
        val afterCopy = repository.observe(old).first()
        val moved = afterCopy.copy(manualKeys = listOf("stream:1", "stream:2"))
        assertThat(repository.save(old, afterCopy.revision, moved)).isInstanceOf(LiveChannelOrderSaveResult.Saved::class.java)
        repository.migrateSourceIdentity(old.sourceKey, target.sourceKey)
        assertThat(repository.observe(target).first().manualKeys).containsExactly("stream:1", "stream:2").inOrder()
    }

    @Test fun `identity migration refuses to overwrite a destination order`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "identity-conflict.preferences_pb") }
        val repository = LiveChannelOrderRepositoryImpl(store)
        val old = LiveChannelOrderScope("combined:legacy-combined-1", "live:all")
        val target = old.copy(sourceKey = "combined:existing")
        repository.initializeIfAbsent(old, LiveChannelOrder(LiveChannelOrderMode.MANUAL, listOf("stream:1")))
        repository.initializeIfAbsent(target, LiveChannelOrder(LiveChannelOrderMode.MANUAL, listOf("stream:2")))
        assertThat(runCatching { repository.migrateSourceIdentity(old.sourceKey, target.sourceKey) }.isFailure).isTrue()
        assertThat(repository.observe(old).first().manualKeys).containsExactly("stream:1")
        assertThat(repository.observe(target).first().manualKeys).containsExactly("stream:2")
    }

    @Test fun `mode sequence and revision persist together and scopes stay isolated`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "orders.preferences_pb") }
        val repository = LiveChannelOrderRepositoryImpl(store)
        val scope = LiveChannelOrderScope("provider:one", "category:news")
        val manual = LiveChannelOrder(LiveChannelOrderMode.MANUAL, listOf("remote:3", "remote:1"))
        assertThat(repository.save(scope, 0, manual)).isEqualTo(LiveChannelOrderSaveResult.Saved(manual.copy(revision = 1)))
        val reloaded = LiveChannelOrderRepositoryImpl(store).observe(scope).first()
        assertThat(reloaded).isEqualTo(manual.copy(revision = 1))
        assertThat(repository.observe(scope.copy(sourceKey = "provider:two")).first()).isEqualTo(LiveChannelOrder())
        assertThat(repository.observe(scope.copy(categoryKey = "category:sports")).first()).isEqualTo(LiveChannelOrder())
        repository.save(scope, 1, reloaded.copy(mode = LiveChannelOrderMode.USE_DEFAULT))
        assertThat(repository.observe(scope).first().manualKeys).isEqualTo(manual.manualKeys)
    }

    @Test fun `concurrent editors cannot silently overwrite and unchanged saves do not bump revision`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "race.preferences_pb") }
        val repository = LiveChannelOrderRepositoryImpl(store)
        val scope = LiveChannelOrderScope("source", "category")
        assertThat(repository.save(scope, 0, LiveChannelOrder())).isEqualTo(LiveChannelOrderSaveResult.Saved(LiveChannelOrder()))
        val first = async { repository.save(scope, 0, LiveChannelOrder(LiveChannelOrderMode.MANUAL, listOf("a", "b"))) }
        val second = async { repository.save(scope, 0, LiveChannelOrder(LiveChannelOrderMode.MANUAL, listOf("b", "a"))) }
        val results = listOf(first.await(), second.await())
        assertThat(results.filterIsInstance<LiveChannelOrderSaveResult.Saved>()).hasSize(1)
        assertThat(results.filterIsInstance<LiveChannelOrderSaveResult.Conflict>()).hasSize(1)
        assertThat(repository.observe(scope).first().revision).isEqualTo(1)
    }

    @Test fun `complete catalog reconciliation prunes removals and appends new members after saved slots`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "reconcile.preferences_pb") }
        val repository = LiveChannelOrderRepositoryImpl(store)
        val scope = LiveChannelOrderScope("provider:one", "live:news")
        repository.reconcileCompleteCatalog(scope.sourceKey, mapOf(scope.categoryKey to listOf("stream:1", "stream:2", "stream:3")))

        val beforeSave = repository.observe(scope).first()
        assertThat(beforeSave.catalogRevision).isNotNull()
        val saved = repository.save(
            scope,
            beforeSave.revision,
            beforeSave.copy(mode = LiveChannelOrderMode.MANUAL, manualKeys = listOf("stream:3", "stream:1", "stream:2"))
        ) as LiveChannelOrderSaveResult.Saved
        repository.reconcileCompleteCatalog(scope.sourceKey, mapOf(scope.categoryKey to listOf("stream:1", "stream:3", "stream:4")))

        val reconciled = repository.observe(scope).first()
        assertThat(reconciled.manualKeys).containsExactly("stream:3", "stream:1", "stream:4").inOrder()
        assertThat(reconciled.revision).isEqualTo(saved.order.revision + 1)
        assertThat(reconciled.catalogRevision).isNotEqualTo(saved.order.catalogRevision)

        val staleSave = repository.save(scope, saved.order.revision, saved.order.copy(manualKeys = listOf("stream:2", "stream:1")))
        assertThat(staleSave).isInstanceOf(LiveChannelOrderSaveResult.Conflict::class.java)

        repository.reconcileCompleteCatalog(scope.sourceKey, mapOf(scope.categoryKey to listOf("stream:2", "stream:1", "stream:3", "stream:4")))
        assertThat(repository.observe(scope).first().manualKeys)
            .containsExactly("stream:3", "stream:1", "stream:4", "stream:2").inOrder()
    }

    @Test fun `catalog revision rejects stale drafts even when the saved sequence did not change`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "catalog-conflict.preferences_pb") }
        val repository = LiveChannelOrderRepositoryImpl(store)
        val scope = LiveChannelOrderScope("provider:two", "live:all")
        val staleDraft = repository.observe(scope).first()
        repository.reconcileCompleteCatalog(scope.sourceKey, mapOf(scope.categoryKey to listOf("stream:7", "stream:8")))

        val result = repository.save(
            scope,
            staleDraft.revision,
            staleDraft.copy(mode = LiveChannelOrderMode.MANUAL, manualKeys = listOf("stream:8", "stream:7"))
        )

        assertThat(result).isInstanceOf(LiveChannelOrderSaveResult.Conflict::class.java)
        assertThat((result as LiveChannelOrderSaveResult.Conflict).current.catalogRevision).isNotNull()
    }
}
