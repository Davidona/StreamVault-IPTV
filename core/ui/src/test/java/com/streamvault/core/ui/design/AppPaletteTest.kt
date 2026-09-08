package com.streamvault.core.ui.design

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppPaletteTest {

    @Test
    fun `classic blue preserves the existing palette`() {
        val palette = AppPalette.forTheme("classic_blue")

        assertThat(palette.brand).isEqualTo(Color(0xFF69A8FF))
        assertThat(palette.canvas).isEqualTo(Color(0xFF07111B))
        assertThat(palette.surface).isEqualTo(Color(0xFF0F1B29))
        assertThat(palette.onPrimary).isEqualTo(Color.White)
    }

    @Test
    fun `m3 purple uses a distinct primary and neutral surface palette`() {
        val palette = AppPalette.forTheme("m3_purple")

        assertThat(palette.brand).isEqualTo(Color(0xFFD0BCFF))
        assertThat(palette.canvas).isEqualTo(Color(0xFF0A0A0D))
        assertThat(palette.surface).isEqualTo(Color(0xFF141419))
        assertThat(palette.onPrimary).isEqualTo(Color(0xFF381E72))
    }

    @Test
    fun `unknown theme IDs resolve to classic blue`() {
        assertThat(AppPalette.forTheme("unknown")).isEqualTo(AppPalette.forTheme("classic_blue"))
        assertThat(AppPalette.forTheme(null)).isEqualTo(AppPalette.forTheme("classic_blue"))
    }
}
