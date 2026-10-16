package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppThemeTest {

    @Test
    fun `storage values are stable and distinct`() {
        assertThat(AppTheme.CLASSIC_BLUE.storageValue).isEqualTo("classic_blue")
        assertThat(AppTheme.M3_PURPLE.storageValue).isEqualTo("m3_purple")
        assertThat(AppTheme.LIGHT.storageValue).isEqualTo("light")
        assertThat(AppTheme.FOREST.storageValue).isEqualTo("forest")
        assertThat(AppTheme.SUNSET.storageValue).isEqualTo("sunset")
        assertThat(AppTheme.OCEAN.storageValue).isEqualTo("ocean")
        assertThat(AppTheme.ROSE.storageValue).isEqualTo("rose")
        assertThat(AppTheme.AMOLED_BLACK.storageValue).isEqualTo("amoled_black")
        assertThat(AppTheme.VINTAGE.storageValue).isEqualTo("vintage")
        assertThat(AppTheme.entries.map(AppTheme::storageValue)).containsExactly(
            "classic_blue",
            "m3_purple",
            "light",
            "forest",
            "sunset",
            "ocean",
            "rose",
            "amoled_black",
            "vintage"
        ).inOrder()
    }

    @Test
    fun `fromStorage accepts case and surrounding whitespace`() {
        assertThat(AppTheme.fromStorage("  M3_PURPLE ")).isEqualTo(AppTheme.M3_PURPLE)
        assertThat(AppTheme.fromStorage(" LIGHT ")).isEqualTo(AppTheme.LIGHT)
    }

    @Test
    fun `fromStorage falls back to classic blue for missing or unknown values`() {
        assertThat(AppTheme.DEFAULT).isEqualTo(AppTheme.CLASSIC_BLUE)
        assertThat(AppTheme.fromStorage(null)).isEqualTo(AppTheme.CLASSIC_BLUE)
        assertThat(AppTheme.fromStorage(" ")).isEqualTo(AppTheme.CLASSIC_BLUE)
        assertThat(AppTheme.fromStorage("old_theme")).isEqualTo(AppTheme.CLASSIC_BLUE)
    }
}
