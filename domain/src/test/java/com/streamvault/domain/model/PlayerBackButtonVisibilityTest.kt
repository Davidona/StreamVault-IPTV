package com.streamvault.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerBackButtonVisibilityTest {
    @Test
    fun `missing and invalid values default to with controls`() {
        assertThat(PlayerBackButtonVisibility.fromStorage(null))
            .isEqualTo(PlayerBackButtonVisibility.WITH_CONTROLS)
        assertThat(PlayerBackButtonVisibility.fromStorage("unexpected"))
            .isEqualTo(PlayerBackButtonVisibility.WITH_CONTROLS)
    }

    @Test
    fun `stable storage values decode case insensitively`() {
        assertThat(PlayerBackButtonVisibility.fromStorage("ALWAYS"))
            .isEqualTo(PlayerBackButtonVisibility.ALWAYS)
        assertThat(PlayerBackButtonVisibility.fromStorage("with_controls"))
            .isEqualTo(PlayerBackButtonVisibility.WITH_CONTROLS)
        assertThat(PlayerBackButtonVisibility.fromStorage("hidden"))
            .isEqualTo(PlayerBackButtonVisibility.HIDDEN)
    }
}
