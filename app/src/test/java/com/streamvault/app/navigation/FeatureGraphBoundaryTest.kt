package com.streamvault.app.navigation

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class FeatureGraphBoundaryTest {
    @Test
    fun featureGraphsDoNotReferenceRootController() {
        val graphRoot = File("src/main/java/com/streamvault/app/navigation/graph")
        val files = graphRoot.walkTopDown()
            .filter { it.extension == "kt" }
            .toList()
        assertThat(files.map { it.name }).containsAtLeast(
            "WelcomeGraph.kt",
            "ProviderGraph.kt",
            "HomeGraph.kt",
            "LiveGraph.kt",
            "CatalogGraph.kt",
            "PlayerGraph.kt",
            "SystemGraph.kt"
        )
        val violations = files
            .filter { "NavHostController" in it.readText() || "NavController" in it.readText() }
            .map { it.name }
            .toList()
        assertThat(violations).isEmpty()
    }
}
