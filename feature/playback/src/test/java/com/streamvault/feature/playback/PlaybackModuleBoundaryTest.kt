package com.streamvault.feature.playback

import com.google.common.truth.Truth.assertThat
import java.io.File
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
    fun `module build declares exactly the approved project dependencies`() {
        val buildScript = File("build.gradle.kts")
        assertThat(buildScript.isFile).isTrue()

        val declaredProjectDependencies = Regex(
            """implementation\(project\(\"([^\"]+)\"\)\)"""
        ).findAll(buildScript.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertThat(declaredProjectDependencies).containsExactlyElementsIn(allowedProjectDependencies)
    }

    @Test
    fun `main source does not reference app packages or root navigation controllers`() {
        val sourceRoot = File("src/main")
        assertThat(sourceRoot.isDirectory).isTrue()

        val forbiddenTokens = listOf(
            "import com.streamvault.app",
            "NavHostController",
            "NavController"
        )
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { source ->
                source.readLines().flatMapIndexed { index, line ->
                    forbiddenTokens.filter(line::contains).map { token ->
                        "${source.relativeTo(sourceRoot)}:${index + 1}: $token"
                    }
                }
            }
            .toList()

        assertThat(violations).isEmpty()
    }
}
