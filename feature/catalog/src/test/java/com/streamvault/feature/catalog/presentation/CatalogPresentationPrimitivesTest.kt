package com.streamvault.feature.catalog.presentation

import com.google.common.truth.Truth.assertThat
import com.streamvault.feature.catalog.presentation.components.catalogShouldLoadMore
import com.streamvault.feature.catalog.presentation.components.catalogStableSemanticKey
import com.streamvault.feature.catalog.presentation.components.catalogVodSelectionChipKey
import com.streamvault.feature.catalog.presentation.components.channelProgressFraction
import com.streamvault.feature.catalog.presentation.time.formatCatalogPositionMs
import org.junit.Test

class CatalogPresentationPrimitivesTest {
    @Test
    fun channelProgressFraction_handlesMissingOrInvalidPrograms() {
        assertThat(channelProgressFraction(nowMs = 1_500L, startTimeMs = 1_000L, endTimeMs = 2_000L))
            .isWithin(0.0001f).of(0.5f)
        assertThat(channelProgressFraction(nowMs = 500L, startTimeMs = 1_000L, endTimeMs = 2_000L))
            .isEqualTo(0f)
        assertThat(channelProgressFraction(nowMs = 3_000L, startTimeMs = 1_000L, endTimeMs = 2_000L))
            .isEqualTo(1f)
        assertThat(channelProgressFraction(nowMs = 1_000L, startTimeMs = 1_000L, endTimeMs = 1_000L))
            .isEqualTo(0f)
    }

    @Test
    fun stableSemanticKeys_areProviderAndContentScoped() {
        assertThat(catalogStableSemanticKey("movie", "provider-a", "42"))
            .isEqualTo("movie:provider-a:42")
        assertThat(catalogStableSemanticKey("movie", "provider-a", "42"))
            .isNotEqualTo(catalogStableSemanticKey("series", "provider-a", "42"))
        assertThat(catalogStableSemanticKey("movie", "provider-a", "42"))
            .isNotEqualTo(catalogStableSemanticKey("movie", "provider-b", "42"))
    }

    @Test
    fun vodSelectionChipKey_isStableForMixedProviderIds() {
        assertThat(catalogVodSelectionChipKey(providerId = "provider-a", categoryId = 7L))
            .isEqualTo("provider-a:7")
        assertThat(catalogVodSelectionChipKey(providerId = "provider-a", categoryId = 7L))
            .isNotEqualTo(catalogVodSelectionChipKey(providerId = "provider-b", categoryId = 7L))
    }

    @Test
    fun durationFormatting_clampsNegativePositions_andUsesHoursWhenNeeded() {
        assertThat(formatCatalogPositionMs(-1L)).isEqualTo("0:00")
        assertThat(formatCatalogPositionMs(5_000L)).isEqualTo("0:05")
        assertThat(formatCatalogPositionMs(143_000L)).isEqualTo("2:23")
        assertThat(formatCatalogPositionMs(3_723_000L)).isEqualTo("1:02:03")
    }

    @Test
    fun infiniteScrollThreshold_requiresVisibleTail_andHonorsLoadingStateInputs() {
        assertThat(catalogShouldLoadMore(lastVisibleIndex = null, totalItemCount = 10, prefetchDistance = 3))
            .isFalse()
        assertThat(catalogShouldLoadMore(lastVisibleIndex = 5, totalItemCount = 10, prefetchDistance = 3))
            .isFalse()
        assertThat(catalogShouldLoadMore(lastVisibleIndex = 6, totalItemCount = 10, prefetchDistance = 3))
            .isTrue()
        assertThat(catalogShouldLoadMore(lastVisibleIndex = 0, totalItemCount = 0, prefetchDistance = 3))
            .isFalse()
    }
}
