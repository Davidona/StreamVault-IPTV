package com.streamvault.feature.settings

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SettingsModuleBoundaryTest {
    @Test
    fun settingsModuleDeclaresExpectedBoundary() {
        val buildFile = File("build.gradle.kts")
        assertThat(buildFile.isFile).isTrue()
        val text = buildFile.readText()
        assertThat(text).contains("verifyFeatureSettingsBoundary")
        assertThat(text).contains(":core:navigation")
        assertThat(text).doesNotContain("project(\":app\")")
        assertThat(text).doesNotContain("project(\":data\")")
        assertThat(text).contains("com.streamvault.data")
    }
}
