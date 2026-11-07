package com.streamvault.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.*
import java.io.File
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
class OrderingReviewProbeTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun `provider refresh must preserve favorite and group arrangements`() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(folder.root, "probe.preferences_pb") }
        val repository = LiveChannelOrderRepositoryImpl(store)
        val favorite = LiveChannelOrderScope("provider:one", "live:favorites")
        val group = favorite.copy(categoryKey = "live:group:stable-uuid")
        val keys = listOf("stream:3", "stream:1", "stream:2")
        for (scope in listOf(favorite, group)) repository.initializeIfAbsent(scope, LiveChannelOrder(LiveChannelOrderMode.MANUAL, keys))
        repository.reconcileCompleteCatalog(favorite.sourceKey, mapOf("live:-1" to listOf("stream:1", "stream:2", "stream:3"), "live:12" to listOf("stream:1", "stream:2", "stream:3")))
        assertThat(listOf(favorite, group).map { repository.observe(it).first().manualKeys }).containsExactly(keys, keys)
    }
}
