package com.streamvault.player.ui

import android.app.Activity
import android.view.Display
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import com.streamvault.player.PlayerSurfaceResizeMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDisplayManager
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "television")
class DisplayRefreshRateControllerTest {
    @Test fun `rebinding the same rendering view reapplies a suspended fullscreen vote`() = withDisplay { activity, view, target ->
        val player = ExoPlayer.Builder(activity).build()
        val binder = PlayerViewBinder(SubtitleStyleController())
        try {
            binder.setDisplayFrameRate(25f)
            binder.bind(view, player, PlayerSurfaceResizeMode.FIT)
            assertEquals(target, activity.window.attributes.preferredDisplayModeId)
            binder.attachPlayer(null)
            assertEquals(0, activity.window.attributes.preferredDisplayModeId)
            binder.bind(view, player, PlayerSurfaceResizeMode.FIT)
            assertEquals(target, activity.window.attributes.preferredDisplayModeId)
        } finally {
            binder.clear()
            player.release()
        }
    }

    @Test fun `legacy TV matches and restores on off and disposal`() = withDisplay { activity, view, target ->
        val matcher = DisplayRefreshRateController(view)
        matcher.update(25f)
        assertEquals(target, activity.window.attributes.preferredDisplayModeId)
        matcher.update(null)
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
        matcher.update(25f)
        matcher.close()
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
    }

    @Test fun `restores the prior explicit window mode rather than always using zero`() = withDisplay { activity, view, target ->
        val original = view.display.mode.modeId
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = original }
        val matcher = DisplayRefreshRateController(view)
        matcher.update(25f)
        assertEquals(target, activity.window.attributes.preferredDisplayModeId)
        matcher.close()
        assertEquals(original, activity.window.attributes.preferredDisplayModeId)
    }

    @Test fun `unsupported cadence releases a previous matching request`() = withDisplay { activity, view, _ ->
        val matcher = DisplayRefreshRateController(view)
        matcher.update(25f)
        matcher.update(23.976f)
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
        matcher.close()
    }

    @Test fun `cleanup does not overwrite a newer window request`() = withDisplay { activity, view, _ ->
        val matcher = DisplayRefreshRateController(view)
        matcher.update(25f)
        activity.window.attributes = activity.window.attributes.apply { preferredDisplayModeId = 99 }
        matcher.close()
        assertEquals(99, activity.window.attributes.preferredDisplayModeId)
    }

    @Test fun `detaching the rendering view releases the display request`() = withDisplay { activity, view, _ ->
        val matcher = DisplayRefreshRateController(view)
        matcher.update(25f)
        activity.setContentView(android.view.View(activity))
        assertEquals(0, activity.window.attributes.preferredDisplayModeId)
        matcher.close()
    }

    private fun withDisplay(block: (Activity, PlayerView, Int) -> Unit) {
        val activityController = Robolectric.buildActivity(Activity::class.java).setup().visible()
        try {
            val activity = activityController.get()
            val view = PlayerView(activity)
            activity.setContentView(view)
            val display = view.display
            val current = display.mode
            val target = current.modeId + 1
            val fifty = ReflectionHelpers.callConstructor(Display.Mode::class.java,
                ClassParameter.from(Int::class.javaPrimitiveType, target),
                ClassParameter.from(Int::class.javaPrimitiveType, current.physicalWidth),
                ClassParameter.from(Int::class.javaPrimitiveType, current.physicalHeight),
                ClassParameter.from(Float::class.javaPrimitiveType, 50f))
            ShadowDisplayManager.setSupportedModes(display.displayId, current, fifty)
            block(activity, view, target)
        } finally {
            activityController.pause().stop().destroy()
        }
    }
}
