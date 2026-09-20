package com.streamvault.core.ui.interaction

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvComponentsTest {

    @Test
    fun enterDownWithoutLongClickIsConsumed() {
        assertThat(
            remoteActivationHandling(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_ENTER,
                action = KeyEvent.ACTION_DOWN,
                hasLongClick = false,
            )
        ).isEqualTo(RemoteActivationHandling.Consume)
    }

    @Test
    fun enterUpWithoutLongClickActivates() {
        assertThat(
            remoteActivationHandling(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_ENTER,
                action = KeyEvent.ACTION_UP,
                hasLongClick = false,
            )
        ).isEqualTo(RemoteActivationHandling.Activate)
    }

    @Test
    fun enterDownWithLongClickIsLeftForNativeTvSurface() {
        assertThat(
            remoteActivationHandling(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_ENTER,
                action = KeyEvent.ACTION_DOWN,
                hasLongClick = true,
            )
        ).isEqualTo(RemoteActivationHandling.Ignore)
    }

    @Test
    fun disabledOrUnrelatedKeysAreIgnored() {
        assertThat(
            remoteActivationHandling(
                enabled = false,
                keyCode = KeyEvent.KEYCODE_ENTER,
                action = KeyEvent.ACTION_UP,
                hasLongClick = false,
            )
        ).isEqualTo(RemoteActivationHandling.Ignore)
        assertThat(
            remoteActivationHandling(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_DPAD_LEFT,
                action = KeyEvent.ACTION_UP,
                hasLongClick = false,
            )
        ).isEqualTo(RemoteActivationHandling.Ignore)
    }

    @Test
    fun trackerActivationRequiresTheMatchingDown() {
        val tracker = RemoteKeyActivationTracker()

        assertThat(
            tracker.handlingFor(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_UP,
                hasLongClick = false,
            )
        ).isNotEqualTo(RemoteActivationHandling.Activate)
    }

    @Test
    fun trackerActivatesOnceAfterDownThenUp() {
        val tracker = RemoteKeyActivationTracker()

        assertThat(
            tracker.handlingFor(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_DOWN,
                hasLongClick = false,
            )
        ).isEqualTo(RemoteActivationHandling.Consume)

        assertThat(
            tracker.handlingFor(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_DPAD_CENTER,
                action = KeyEvent.ACTION_UP,
                hasLongClick = false,
            )
        ).isEqualTo(RemoteActivationHandling.Activate)
    }

    @Test
    fun trackerDoesNotActivateOnRepeatedOrphanUps() {
        val tracker = RemoteKeyActivationTracker()

        tracker.handlingFor(
            enabled = true,
            keyCode = KeyEvent.KEYCODE_ENTER,
            action = KeyEvent.ACTION_DOWN,
            hasLongClick = false,
        )
        assertThat(
            tracker.handlingFor(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_ENTER,
                action = KeyEvent.ACTION_UP,
                hasLongClick = false,
            )
        ).isEqualTo(RemoteActivationHandling.Activate)

        assertThat(
            tracker.handlingFor(
                enabled = true,
                keyCode = KeyEvent.KEYCODE_ENTER,
                action = KeyEvent.ACTION_UP,
                hasLongClick = false,
            )
        ).isNotEqualTo(RemoteActivationHandling.Activate)
    }
}
