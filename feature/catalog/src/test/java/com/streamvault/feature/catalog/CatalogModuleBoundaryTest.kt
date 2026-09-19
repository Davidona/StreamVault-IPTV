package com.streamvault.feature.catalog

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class CatalogModuleBoundaryTest {
    @Test
    fun catalogModuleDeclaresOnlyApprovedProjectDependencies() {
        val buildFile = File("build.gradle.kts")
        assertThat(buildFile.isFile).isTrue()
        val text = buildFile.readText()

        assertThat(text).contains("verifyFeatureCatalogBoundary")
        assertThat(text).contains("implementation(project(\":core:navigation\"))")
        assertThat(text).contains("implementation(project(\":core:ui\"))")
        assertThat(text).contains("implementation(project(\":domain\"))")
        assertThat(text).contains("implementation(project(\":data\"))")
        assertThat(text).doesNotContain("project(\":app\")")
        assertThat(text).doesNotContain("project(\":player\")")
        assertThat(text).doesNotContain("project(\":feature:")
    }

    @Test
    fun catalogBoundaryReportHasNoMainSourceViolations() {
        val report = File("build/reports/feature-catalog-boundary/report.txt")
        assertThat(report.isFile).isTrue()
        val lines = report.readLines()
        assertThat(lines).contains("projectDependencies=:core:navigation,:core:ui,:data,:domain")
        assertThat(lines).contains("mainSourceViolations=")
        assertThat(lines.first { it.startsWith("mainSourceViolations=") })
            .isEqualTo("mainSourceViolations=")
    }

    @Test
    fun catalogBoundaryReportDetectsEveryFixtureCategory() {
        val report = File("build/reports/feature-catalog-boundary/report.txt")
        val fixtureLine = report.readLines().first { it.startsWith("fixtureViolations=") }

        assertThat(fixtureLine).contains("AppPackageImport.kt:3: import com.streamvault.app")
        assertThat(fixtureLine).contains("FullyQualifiedAppReference.kt:3: com.streamvault.app")
        assertThat(fixtureLine).contains("MainActivityReference.java:4: MainActivity")
        assertThat(fixtureLine).contains("RootNavigation.kt:3: NavHostController")
        assertThat(fixtureLine).contains("RootNavigation.java:4: NavController")
        assertThat(fixtureLine).contains("PlaybackFeatureImport.kt:3: import com.streamvault.feature.playback")
        assertThat(fixtureLine).contains("ProviderFeatureImport.kt:3: import com.streamvault.feature.provider")
        assertThat(fixtureLine).contains("SettingsFeatureImport.java:4: com.streamvault.feature.settings")
        assertThat(fixtureLine).contains("LiveFeatureImport.kt:3: import com.streamvault.feature.live")
        assertThat(fixtureLine).contains("PlayerModuleImport.java:4: com.streamvault.player")
    }
}
