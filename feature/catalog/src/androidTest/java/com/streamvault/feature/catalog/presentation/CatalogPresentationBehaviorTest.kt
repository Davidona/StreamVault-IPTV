package com.streamvault.feature.catalog.presentation

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.theme.StreamVaultTheme
import com.streamvault.domain.model.Movie
import com.streamvault.domain.model.Series
import com.streamvault.feature.catalog.presentation.components.MovieCard
import com.streamvault.feature.catalog.presentation.components.SeriesCard
import com.streamvault.feature.catalog.presentation.components.SelectionChip
import com.streamvault.feature.catalog.presentation.components.SelectionChipRow
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals

class CatalogPresentationBehaviorTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun selectionChipRow_exposesSelection_andDispatchesClickOnce() {
        var selectedKey: String? = null
        composeRule.setContent {
            MaterialTheme {
                SelectionChipRow(
                    title = "Catalog",
                    chips = listOf(
                        SelectionChip(key = "all", label = "All"),
                        SelectionChip(key = "movies", label = "Movies")
                    ),
                    selectedKey = "all",
                    onChipSelected = { selectedKey = it }
                )
            }
        }

        composeRule.onNodeWithText("All").assertIsSelected()
        composeRule.onNodeWithText("Movies").performSemanticsAction(SemanticsActions.OnClick)
        composeRule.runOnIdle { assertEquals("movies", selectedKey) }
    }

    @Test
    fun catalogCards_keepAccessibleSemantics_inRtlAndLargeFont() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl,
                LocalDensity provides Density(density = 1f, fontScale = 1.5f)
            ) {
                StreamVaultTheme {
                    MaterialTheme {
                        Row(
                            modifier = androidx.compose.ui.Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            MovieCard(
                                movie = Movie(
                                    id = 1L,
                                    name = "The Night Shift",
                                    year = "2026",
                                    isFavorite = true
                                ),
                                onClick = {}
                            )
                            SeriesCard(
                                series = Series(
                                    id = 2L,
                                    name = "The Long Road",
                                    genre = "Drama",
                                    isFavorite = true
                                ),
                                onClick = {}
                            )
                        }
                    }
                }
            }
        }

        val movieNode = composeRule.onNodeWithContentDescription(
            "The Night Shift. 2026. Favorite"
        )
        val seriesNode = composeRule.onNodeWithContentDescription(
            "The Long Road. Drama. Favorite"
        )
        movieNode.assertIsDisplayed().assertHasClickAction()
        seriesNode.assertIsDisplayed().assertHasClickAction()

        val movieBounds = movieNode.fetchSemanticsNode().boundsInRoot
        val seriesBounds = seriesNode.fetchSemanticsNode().boundsInRoot
        assertThat(movieBounds.left).isGreaterThan(seriesBounds.left)
    }

    @Test
    fun movieCard_keepsTextTypeBadgeByDefault() {
        composeRule.setContent {
            StreamVaultTheme {
                MaterialTheme {
                    MovieCard(
                        movie = Movie(id = 1L, name = "The Night Shift"),
                        onClick = {},
                        showTypeBadge = true
                    )
                }
            }
        }

        composeRule.onNodeWithText("MOVIE").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("MOVIE").assertDoesNotExist()
    }

    @Test
    fun movieCard_usesIconTypeBadgeWhenEnabled() {
        composeRule.setContent {
            StreamVaultTheme {
                MaterialTheme {
                    MovieCard(
                        movie = Movie(id = 1L, name = "The Night Shift"),
                        onClick = {},
                        showTypeBadge = true,
                        useTypeBadgeIcon = true
                    )
                }
            }
        }

        composeRule.onNodeWithContentDescription("MOVIE").assertIsDisplayed()
        composeRule.onNodeWithText("MOVIE").assertDoesNotExist()
    }
}
