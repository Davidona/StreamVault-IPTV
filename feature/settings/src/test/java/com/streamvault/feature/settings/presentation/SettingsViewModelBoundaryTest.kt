package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SettingsViewModelBoundaryTest {
    @Test
    fun viewModelUsesPortsInsteadOfAppImplementations() {
        val source = File("src/main/java/com/streamvault/feature/settings/presentation/SettingsViewModel.kt")
        assertThat(source.isFile).isTrue()
        val text = source.readText()
        assertThat(text).contains("SettingsSurfaceRefreshPort")
        assertThat(text).contains("SettingsDiagnosticsPort")
        assertThat(text).contains("SettingsAppUpdatePort")
        assertThat(text).doesNotContain("com.streamvault.app")
    }
}
