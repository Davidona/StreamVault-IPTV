package com.streamvault.feature.catalog.presentation

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.tv.material3.MaterialTheme
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
}
