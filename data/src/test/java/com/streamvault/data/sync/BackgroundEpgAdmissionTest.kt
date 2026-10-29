package com.streamvault.data.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BackgroundEpgAdmissionTest {
    @Test fun `background guide work yields only when requested and playback is owned`() {
        assertThat(shouldDeferBackgroundEpgForPlayback(false, true, true)).isTrue()
        assertThat(shouldDeferBackgroundEpgForPlayback(false, true, false)).isFalse()
        assertThat(shouldDeferBackgroundEpgForPlayback(false, false, true)).isFalse()
    }

    @Test fun `explicit user refresh remains admitted with the default busy policy`() {
        assertThat(shouldDeferBackgroundEpgForPlayback(true, true, true)).isFalse()
    }
}
