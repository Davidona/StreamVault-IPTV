package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppTextSizeTest {
    @Test fun `default ignores device font scale while system mode follows it`() {
        assertThat(AppTextSize().resolvedScale(2f)).isEqualTo(1f)
        assertThat(AppTextSize(AppTextSizeMode.SYSTEM).resolvedScale(2f)).isEqualTo(2f)
        assertThat(AppTextSize(AppTextSizeMode.CUSTOM, 1.2f).resolvedScale(2f)).isEqualTo(1.2f)
    }

    @Test fun `invalid stored settings recover safely and custom scales stay in range`() {
        assertThat(AppTextSize.fromStorage(null)).isEqualTo(AppTextSize())
        assertThat(AppTextSize.fromStorage("unknown:2")).isEqualTo(AppTextSize())
        assertThat(AppTextSize.fromStorage("CUSTOM:NaN").customScale).isEqualTo(1f)
        assertThat(AppTextSize.fromStorage("CUSTOM:Infinity").customScale).isEqualTo(1f)
        assertThat(AppTextSize.fromStorage("CUSTOM:9").customScale).isEqualTo(1.5f)
        assertThat(AppTextSize.fromStorage("CUSTOM:0.1").customScale).isEqualTo(0.8f)
        val custom = AppTextSize(AppTextSizeMode.CUSTOM, 1.25f)
        assertThat(AppTextSize.fromStorage(custom.toStorage())).isEqualTo(custom)
    }
}
