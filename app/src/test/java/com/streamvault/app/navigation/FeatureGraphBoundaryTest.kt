package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class FeatureGraphBoundaryTest {
    @Test
    fun featureGraphsDoNotReferenceRootController() {
        val graphRoots = listOf(
            File("src/main/java/com/streamvault/app/navigation/graph"),
            File("../feature/provider/src/main/java/com/streamvault/feature/provider/navigation"),
            File("../feature/catalog/src/main/java/com/streamvault/feature/catalog/navigation")
        )
        val files = graphRoots
            .flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
        assertThat(files.map { it.name }).containsAtLeast(
            "WelcomeGraph.kt",
            "ProviderGraph.kt",
            "LiveGraph.kt",
            "CatalogGraph.kt",
            "SystemGraph.kt"
        )
        assertThat(files.map { it.name }).doesNotContain("PlayerGraph.kt")
        val violations = files
            .filter { "NavHostController" in it.readText() || "NavController" in it.readText() }
            .map { it.name }
            .toList()
        assertThat(violations).isEmpty()
    }
}
