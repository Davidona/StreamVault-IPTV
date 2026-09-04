package com.streamvault.feature.system

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SystemModuleBoundaryTest {
    @Test
    fun systemModuleDeclaresOnlyApprovedProjectDependencies() {
        val workingDirectory = File(System.getProperty("user.dir") ?: error("user.dir is unavailable"))
        val buildFile = sequence {
            var directory: File? = workingDirectory
            while (directory != null) {
                yield(directory.resolve("feature/system/build.gradle.kts"))
                directory = directory.parentFile
            }
        }
            .firstOrNull(File::isFile)
            ?: File("feature/system/build.gradle.kts")
        assertThat(buildFile.isFile).isTrue()
        val text = buildFile.readText()
        assertThat(text).contains("verifyFeatureSystemBoundary")
        assertThat(text).contains("implementation(project(\":core:navigation\"))")
        assertThat(text).contains("implementation(project(\":core:ui\"))")
        assertThat(text).contains("implementation(project(\":domain\"))")
        assertThat(text).doesNotContain("project(\":app\")")
        assertThat(text).doesNotContain("project(\":data\")")
        assertThat(text).doesNotContain("project(\":player\")")
        assertThat(text).doesNotContain("project(\":feature:playback\")")
        assertThat(text).doesNotContain("project(\":feature:provider\")")
        assertThat(text).doesNotContain("project(\":feature:settings\")")
        assertThat(text).doesNotContain("project(\":feature:live\")")
        assertThat(text).doesNotContain("project(\":feature:catalog\")")
    }
}
