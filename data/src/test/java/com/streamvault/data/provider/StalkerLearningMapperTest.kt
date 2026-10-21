package com.streamvault.data.provider

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.streamvault.data.local.entity.StalkerPortalStateEntity
import com.streamvault.domain.model.CapabilityState
import com.streamvault.domain.model.StalkerObservation
import com.streamvault.domain.model.StalkerObservationSource
import com.streamvault.domain.model.StalkerPortalLearning
import org.junit.Test

class StalkerLearningMapperTest {
    @Test fun repairsPartialPersistedLearningWithoutOptionalCollections() {
        val state = StalkerPortalStateEntity(providerId = 1, configurationGeneration = 2,
            seriesWildcardSupported = false, learningJson = """{"configurationGeneration":2,"capabilities":{"series":{"value":"UNSUPPORTED","configurationGeneration":2,"source":"CATALOG","observedAt":1}}}""")
        val learning = requireNotNull(state.toGenerationValidLearning(Gson(), 2))
        assertThat(learning.capabilities).doesNotContainKey("series")
        assertThat(learning.discoveryEvidence).isEmpty()
    }
    @Test fun rejectedWildcardDoesNotDisablePagedCatalogs() {
        val state = StalkerPortalStateEntity(providerId = 1, configurationGeneration = 2,
            movieWildcardSupported = false, seriesWildcardSupported = false)
        val learning = requireNotNull(state.toGenerationValidLearning(Gson(), 2))
        assertThat(learning.capabilities).doesNotContainKey("vod")
        assertThat(learning.capabilities).doesNotContainKey("series")
        assertThat(learning.capabilities["series_wildcard"]?.value).isEqualTo(CapabilityState.UNSUPPORTED)
    }

    @Test fun repairsLegacyWildcardCapabilityWithoutRemovingActualEndpointRestriction() {
        val gson = Gson()
        val old = StalkerPortalLearning(configurationGeneration = 2, capabilities = mapOf(
            "series" to StalkerObservation(CapabilityState.UNSUPPORTED, 2, StalkerObservationSource.CATALOG, 1),
            "vod" to StalkerObservation(CapabilityState.RESTRICTED, 2, StalkerObservationSource.DISCOVERY, 1)
        ))
        val state = StalkerPortalStateEntity(providerId = 1, configurationGeneration = 2,
            movieWildcardSupported = false, seriesWildcardSupported = false, learningJson = gson.toJson(old))
        val learning = requireNotNull(state.toGenerationValidLearning(gson, 2))
        assertThat(learning.capabilities).doesNotContainKey("series")
        assertThat(learning.capabilities["vod"]?.value).isEqualTo(CapabilityState.RESTRICTED)
    }
}
