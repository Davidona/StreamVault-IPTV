package com.streamvault.data.remote.stalker

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.ContentType
import com.streamvault.domain.model.CatalogLayout
import com.streamvault.domain.model.StalkerConfig
import com.streamvault.domain.model.StalkerDeviceIdentity
import org.junit.After
import org.junit.Test

class StalkerResolvedCategoryHandoffTest {
    @After fun clear() = StalkerResolvedCategoryHandoff.clear()

    @Test fun scopesIncludeProviderGenerationAndCompleteConfiguration() {
        val config = StalkerConfig("https://portal.example/c/", StalkerDeviceIdentity(macAddress = "00:1A:79:12:34:56"))
        val key = StalkerResolvedCategoryHandoff.key(7L, 1L, config)
        assertThat(key).isNotEqualTo(StalkerResolvedCategoryHandoff.key(8L, 1L, config))
        assertThat(key).isNotEqualTo(StalkerResolvedCategoryHandoff.key(7L, 2L, config))
        assertThat(key).isNotEqualTo(StalkerResolvedCategoryHandoff.key(7L, 1L, config.copy(httpHeaders = "changed")))
    }

    @Test fun snapshotIsOneShotAndRetainsSuccessfulEmptyCategories() {
        val session = StalkerSession("https://portal.example/load.php", "https://portal.example/c/", "token")
        StalkerResolvedCategoryHandoff.publish("7/1/config", StalkerResolvedCategoryHandoff.Snapshot(session, mapOf(ContentType.SERIES to emptyList())))
        assertThat(StalkerResolvedCategoryHandoff.take("7/1/config", session)?.categories)
            .containsEntry(ContentType.SERIES, emptyList<StalkerCategoryRecord>())
        assertThat(StalkerResolvedCategoryHandoff.take("7/1/config", session)).isNull()
    }

    @Test fun replacedSessionAndExpiredSnapshotsAreRejected() {
        val session = StalkerSession("https://portal.example/load.php", "https://portal.example/c/", "token", authEpoch = 1L)
        val snapshot = StalkerResolvedCategoryHandoff.Snapshot(session, emptyMap())
        assertThat(snapshot.matches(session.copy(token = "replacement"))).isFalse()
        assertThat(snapshot.matches(session.copy(authEpoch = 2L))).isFalse()
        assertThat(snapshot.matches(session.copy(loadUrl = "https://other.example/load.php"))).isFalse()
        assertThat(snapshot.matches(session, snapshot.createdAt + 60_000L)).isFalse()
        StalkerResolvedCategoryHandoff.publish("7/1/config", snapshot)
        assertThat(StalkerResolvedCategoryHandoff.take("7/1/config", session.copy(authEpoch = 2L))).isNull()
        assertThat(StalkerResolvedCategoryHandoff.take("7/1/config", session)).isNull()
    }

    @Test fun incompatibleLayoutAndDeletionDiscardHandoff() {
        val session = StalkerSession("https://portal.example/load.php", "https://portal.example/c/", "token")
        val snapshot = StalkerResolvedCategoryHandoff.Snapshot(session, emptyMap(), layout = CatalogLayout.SPLIT)
        StalkerResolvedCategoryHandoff.publish("7/1/config", snapshot)
        assertThat(StalkerResolvedCategoryHandoff.take("7/1/config", session, CatalogLayout.UNIFIED_VOD)).isNull()
        StalkerResolvedCategoryHandoff.publish("7/1/config", snapshot)
        StalkerResolvedCategoryHandoff.publish("8/1/config", snapshot)
        StalkerResolvedCategoryHandoff.clearProvider(7L)
        assertThat(StalkerResolvedCategoryHandoff.take("7/1/config", session)).isNull()
        assertThat(StalkerResolvedCategoryHandoff.take("8/1/config", session)).isNotNull()
    }
}
