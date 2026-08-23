package com.streamvault.core.ui.device

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TelevisionDeviceTest {
    @Test
    fun leanbackFeatureIdentifiesTelevision() {
        assertThat(
            classifyTelevisionDevice(
                hasLeanback = true,
                hasLeanbackOnly = false,
                hasTelevision = false,
                hasFireTv = false,
                uiModeType = null,
                screenWidthDp = 0,
                hasTouchscreen = true
            )
        ).isTrue()
    }

    @Test
    fun touchDeviceBelowTelevisionFallbackIsNotTelevision() {
        assertThat(
            classifyTelevisionDevice(
                hasLeanback = false,
                hasLeanbackOnly = false,
                hasTelevision = false,
                hasFireTv = false,
                uiModeType = null,
                screenWidthDp = 600,
                hasTouchscreen = true
            )
        ).isFalse()
    }
}
