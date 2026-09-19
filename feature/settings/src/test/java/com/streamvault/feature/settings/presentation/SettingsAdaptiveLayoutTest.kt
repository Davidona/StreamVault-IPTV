package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsAdaptiveLayoutTest {
    @Test
    fun `narrow screens use root then detail navigation`() {
        assertThat(settingsLayoutMode(320)).isEqualTo(SettingsLayoutMode.COMPACT)
        assertThat(settingsLayoutMode(599)).isEqualTo(SettingsLayoutMode.COMPACT)
    }

    @Test
    fun `wide screens keep the category rail and content visible`() {
        assertThat(settingsLayoutMode(600)).isEqualTo(SettingsLayoutMode.TV)
        assertThat(settingsLayoutMode(960)).isEqualTo(SettingsLayoutMode.TV)
    }
}
