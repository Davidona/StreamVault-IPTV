package com.streamvault.data.preferences

import com.google.common.truth.Truth.assertThat
import com.streamvault.domain.model.AppTheme
import org.junit.Test

class PreferencesRepositoryThemeTest {

    @Test
    fun `parseAppThemePreference defaults to classic blue`() {
        assertThat(parseAppThemePreference(null)).isEqualTo(AppTheme.CLASSIC_BLUE)
        assertThat(parseAppThemePreference("invalid")).isEqualTo(AppTheme.CLASSIC_BLUE)
    }

    @Test
    fun `parseAppThemePreference restores a stored theme`() {
        assertThat(parseAppThemePreference("m3_purple")).isEqualTo(AppTheme.M3_PURPLE)
    }
}
