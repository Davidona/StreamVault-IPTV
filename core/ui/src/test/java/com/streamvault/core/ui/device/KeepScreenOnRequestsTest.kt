package com.streamvault.core.ui.device

import android.view.View
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KeepScreenOnRequestsTest {

    private val view = View(RuntimeEnvironment.getApplication())

    @Test
    fun `a request keeps the screen on until it is released`() {
        KeepScreenOnRequests.acquire(view)
        assertThat(view.keepScreenOn).isTrue()

        KeepScreenOnRequests.release(view)
        assertThat(view.keepScreenOn).isFalse()
    }

    @Test
    fun `overlapping requests do not switch each other off`() {
        // e.g. Home and Guide previews both composed during a navigation transition.
        KeepScreenOnRequests.acquire(view)
        KeepScreenOnRequests.acquire(view)

        KeepScreenOnRequests.release(view)
        assertThat(view.keepScreenOn).isTrue()
        assertThat(KeepScreenOnRequests.activeCount(view)).isEqualTo(1)

        KeepScreenOnRequests.release(view)
        assertThat(view.keepScreenOn).isFalse()
        assertThat(KeepScreenOnRequests.activeCount(view)).isEqualTo(0)
    }

    @Test
    fun `an extra release never goes negative or turns a later request off`() {
        KeepScreenOnRequests.release(view)
        KeepScreenOnRequests.acquire(view)

        assertThat(view.keepScreenOn).isTrue()
        assertThat(KeepScreenOnRequests.activeCount(view)).isEqualTo(1)
        KeepScreenOnRequests.release(view)
    }
}
