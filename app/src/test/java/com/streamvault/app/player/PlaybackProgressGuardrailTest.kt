package com.streamvault.app.player

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test

class PlaybackProgressGuardrailTest {

    @Test
    fun `app player and playback feature sources do not call direct repository progress apis`() {
        val projectRoot = resolveProjectRoot()
        val violations = buildList {
            addAll(findViolations(projectRoot, "app/src/main", ".updateWatchProgress("))
            addAll(findViolations(projectRoot, "app/src/main", ".updateEpisodeWatchProgress("))
            addAll(findViolations(projectRoot, "player/src/main", ".updateWatchProgress("))
            addAll(findViolations(projectRoot, "player/src/main", ".updateEpisodeWatchProgress("))
            addAll(findViolations(projectRoot, "feature/playback/src/main", ".updateWatchProgress("))
            addAll(findViolations(projectRoot, "feature/playback/src/main", ".updateEpisodeWatchProgress("))
        }

        assertThat(violations).isEmpty()
    }

    private fun resolveProjectRoot(): Path {
        val cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        if (Files.exists(cwd.resolve("app/src/main"))) {
            return cwd
        }
        val parent = cwd.parent
        check(parent != null && Files.exists(parent.resolve("app/src/main"))) {
            "Could not resolve project root from $cwd"
        }
        return parent
    }

    private fun findViolations(projectRoot: Path, relativeSourceDir: String, token: String): List<String> {
        val sourceRoot = projectRoot.resolve(relativeSourceDir)
        if (!Files.exists(sourceRoot)) {
            return emptyList()
        }

        val violations = mutableListOf<String>()
        Files.walk(sourceRoot).use { paths ->
            paths
                .filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".kt") || it.toString().endsWith(".java") }
                .forEach { path ->
                    Files.readAllLines(path).forEachIndexed { index, line ->
                        if (line.contains(token)) {
                            violations += "${projectRoot.relativize(path)}:${index + 1}: ${line.trim()}"
                        }
                    }
                }
        }
        return violations
    }
}
