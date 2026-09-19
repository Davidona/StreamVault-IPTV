package com.streamvault.feature.settings.presentation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SettingsEpgSourceFieldBehaviorTest {
    @Test
    fun `back is consumed while a touch field owns input focus`() {
        assertThat(
            shouldConsumeEpgSourceFieldBack(
                isTelevisionDevice = false,
                hasInputFocus = true,
                pendingInputActivation = false,
                acceptsInput = true,
            )
        ).isTrue()
    }

    @Test
    fun `back is consumed during TV edit activation`() {
        assertThat(
            shouldConsumeEpgSourceFieldBack(
                isTelevisionDevice = true,
                hasInputFocus = false,
                pendingInputActivation = true,
                acceptsInput = true,
            )
        ).isTrue()
    }

    @Test
    fun `back is left to navigation after editing ends`() {
        assertThat(
            shouldConsumeEpgSourceFieldBack(
                isTelevisionDevice = false,
                hasInputFocus = false,
                pendingInputActivation = false,
                acceptsInput = false,
            )
        ).isFalse()
    }
}
