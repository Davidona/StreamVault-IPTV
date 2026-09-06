package com.streamvault.feature.playback

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackModuleBoundaryTest {

    private val allowedProjectDependencies = setOf(
        ":core:navigation",
        ":core:ui",
        ":domain",
        ":player",
        ":data"
    )

    @Test
    fun `boundary guard records exactly the approved Gradle project dependencies`() {
        val actualDependencies = boundaryReportValue("projectDependencies")
            .split(',')
            .filter(String::isNotEmpty)
            .toSet()

        assertThat(actualDependencies).containsExactlyElementsIn(allowedProjectDependencies)
    }

    @Test
    fun `boundary guard rejects Kotlin and Java app and root navigation fixtures`() {
        val fixtureViolations = boundaryReportValue("fixtureViolations")

        assertThat(fixtureViolations).contains("AppPackageImport.kt:3: import com.streamvault.app")
        assertThat(fixtureViolations).contains("FullyQualifiedAppReference.kt:3: com.streamvault.app")
        assertThat(fixtureViolations).contains("MainActivityReference.java:4: MainActivity")
        assertThat(fixtureViolations).contains("RootNavigation.kt:3: NavHostController")
        assertThat(fixtureViolations).contains("RootNavigation.java:4: NavController")
        assertThat(fixtureViolations)
            .contains("Media3EngineImport.kt:3: Media3PlayerEngine")
        assertThat(fixtureViolations)
            .contains("Media3FullyQualifiedReference.java:4: androidx.media3")
    }

    @Test
    fun `playback feature does not declare Media3 directly`() {
        assertThat(boundaryReportValue("directMedia3Dependencies")).isEmpty()
    }

    @Test
    fun `boundary guard reports no violations in the empty production source root`() {
        assertThat(boundaryReportValue("mainSourceViolations")).isEmpty()
    }

    private fun boundaryReportValue(key: String): String {
        val report = java.io.File("build/reports/feature-playback-boundary/report.txt")
        assertThat(report.isFile).isTrue()
        return report.readLines()
            .single { it.startsWith("$key=") }
            .removePrefix("$key=")
    }
}
