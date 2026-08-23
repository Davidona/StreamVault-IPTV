package com.streamvault.app.ui.components.shell

import com.google.common.truth.Truth.assertThat
import com.streamvault.app.navigation.Routes
import com.streamvault.domain.model.AppTopLevelDestination
import com.streamvault.domain.model.CatalogLayout
import org.junit.Test

class AppShellNavigationTest {

    @Test
    fun splitCatalogPreservesConfiguredMovieAndSeriesDestinations() {
        val result = buildDestinationItems(
            configured = listOf(AppTopLevelDestination.MOVIES, AppTopLevelDestination.SERIES),
            layout = CatalogLayout.SPLIT
        )

        assertThat(result.map { it.route })
            .containsExactly(Routes.MOVIES, Routes.SERIES)
            .inOrder()
    }

    @Test
    fun unifiedCatalogReplacesMovieAndSeriesWithOneVodDestination() {
        val result = buildDestinationItems(
            configured = listOf(
                AppTopLevelDestination.HOME,
                AppTopLevelDestination.MOVIES,
                AppTopLevelDestination.SERIES
            ),
            layout = CatalogLayout.UNIFIED_VOD
        )

        assertThat(result.map { it.route })
            .containsExactly(Routes.HOME, Routes.VOD)
            .inOrder()
    }
}
