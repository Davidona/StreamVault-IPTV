package com.streamvault.feature.playback.player.overlay

import com.google.common.truth.Truth.assertThat
import com.streamvault.core.ui.design.AppColors
import org.junit.Test

class PlayerOverlayColorsTest {

    @Test
    fun `focused quick action content uses the global on primary color`() {
        assertThat(quickActionContentColor(isFocused = true, isIcon = true))
            .isEqualTo(AppColors.OnPrimary)
        assertThat(quickActionContentColor(isFocused = true, isIcon = false))
            .isEqualTo(AppColors.OnPrimary)
    }

    @Test
    fun `unfocused quick action content keeps the global role colors`() {
        assertThat(quickActionContentColor(isFocused = false, isIcon = true))
            .isEqualTo(AppColors.BrandStrong)
        assertThat(quickActionContentColor(isFocused = false, isIcon = false))
            .isEqualTo(AppColors.TextPrimary)
    }
}
