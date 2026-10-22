package com.streamvault.player.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.media3.ui.PlayerView
import com.streamvault.player.playback.DisplayRefreshRatePolicy
import com.streamvault.player.playback.RefreshRateDisplayMode

/** One fullscreen owner's display vote. Preview and multiview players never acquire a vote. */
internal class DisplayRefreshRateController(private val view: PlayerView) :
    View.OnAttachStateChangeListener, SurfaceHolder.Callback, DisplayManager.DisplayListener {
    private val displayManager = view.context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val window = view.context.activity()?.window
    private val surfaceView = view.videoSurfaceView as? SurfaceView
    private val television = view.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK ==
        Configuration.UI_MODE_TYPE_TELEVISION ||
        view.context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    private var rate: Float? = null
    private var votedSurface: Surface? = null
    private var votedRate: Float? = null
    private var surfaceRequestsUnsupported = false
    private var originalModeId: Int? = null
    private var ownedModeId: Int? = null
    private var listening = false

    init {
        view.addOnAttachStateChangeListener(this)
        surfaceView?.holder?.addCallback(this)
    }

    fun update(frameRate: Float?) {
        rate = frameRate?.takeIf(DisplayRefreshRatePolicy::validRate)
        if (rate != null && view.isAttachedToWindow && !listening) {
            runCatching {
                displayManager?.registerDisplayListener(this, Handler(Looper.getMainLooper()))
                listening = displayManager != null
            }.onFailure { Log.w(TAG, "refresh-match display-listener unavailable", it) }
        }
        apply()
        if (rate == null) stopListening()
    }

    private fun apply() {
        val fps = rate
        if (fps == null || !view.isAttachedToWindow) {
            clearVotes()
            return
        }
        // Surface votes preserve output resolution and honor Android's Match content frame rate
        // preference. ALWAYS permits an HDMI resync only when the OS/user allows it (Android 12+).
        if (Build.VERSION.SDK_INT >= 30 && surfaceView != null && !surfaceRequestsUnsupported &&
            (Build.VERSION.SDK_INT >= 31 || !television)) {
            restoreWindowMode()
            val surface = surfaceView.holder.surface
            if (surface.isValid && (votedSurface !== surface || votedRate != fps)) {
                val requested = runCatching {
                    setSurfaceRate(surface, fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                    votedSurface = surface
                    votedRate = fps
                    Log.i(TAG, "refresh-match request fps=$fps via=surface display=${view.display?.displayId}")
                }.onFailure { Log.w(TAG, "refresh-match surface-request unavailable", it) }.isSuccess
                if (requested) return
                // Do not alternate between restoring and reacquiring a working window fallback
                // on every display callback when a vendor's Surface API consistently throws.
                surfaceRequestsUnsupported = true
            } else {
                return
            }
        }
        // Older HDMI devices and TextureView outputs need a window mode request. Restrict this
        // fallback to televisions; do not introduce disruptive display switches on phones/tablets.
        if (!television || window == null) return
        runCatching {
            val targetWindow = window ?: return@runCatching
            val display = view.display ?: return@runCatching
            val current = display.mode
            val preference = if (Build.VERSION.SDK_INT >= 31) {
                displayManager?.matchContentFrameRateUserPreference ?: DisplayManager.MATCH_CONTENT_FRAMERATE_UNKNOWN
            } else -1
            if (Build.VERSION.SDK_INT >= 31 && preference == DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER) {
                restoreWindowMode()
                return@runCatching
            }
            val modes = display.supportedModes.filter { mode ->
                Build.VERSION.SDK_INT < 31 || preference != DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY ||
                    mode.modeId == current.modeId || current.alternativeRefreshRates.any { absRate(it, mode.refreshRate) }
            }
            val target = DisplayRefreshRatePolicy.select(fps, current.snapshot(), modes.map { it.snapshot() })
            if (target == null) {
                Log.i(TAG, "refresh-match no-compatible-mode fps=$fps display=${display.displayId}")
                restoreWindowMode()
                return@runCatching
            }
            if (ownedModeId == null && target.id == current.modeId) return@runCatching
            val attributes = targetWindow.attributes
            if (attributes.preferredDisplayModeId == target.id) return@runCatching
            if (originalModeId == null) originalModeId = attributes.preferredDisplayModeId
            ownedModeId = target.id
            attributes.preferredDisplayModeId = target.id
            targetWindow.attributes = attributes
            Log.i(TAG, "refresh-match request fps=$fps hz=${target.hz} mode=${target.id} via=window display=${display.displayId}")
        }.onFailure { Log.w(TAG, "refresh-match window-request unavailable", it) }
    }

    private fun clearVotes() {
        if (Build.VERSION.SDK_INT >= 30) {
            votedSurface?.takeIf { it.isValid }?.let { surface ->
                runCatching {
                    setSurfaceRate(surface, 0f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT)
                }
                    .onFailure { Log.w(TAG, "refresh-match surface-clear unavailable", it) }
            }
        }
        votedSurface = null
        votedRate = null
        restoreWindowMode()
    }

    private fun restoreWindowMode() {
        val original = originalModeId ?: return
        runCatching {
            val targetWindow = window ?: return@runCatching
            val attributes = targetWindow.attributes
            // Do not overwrite a newer request made by another window owner.
            if (attributes.preferredDisplayModeId == ownedModeId) {
                attributes.preferredDisplayModeId = original.takeIf { id ->
                    val display = view.display
                    id == 0 || display?.supportedModes?.any {
                        it.modeId == id && it.physicalWidth == display.mode.physicalWidth &&
                            it.physicalHeight == display.mode.physicalHeight
                    } == true
                } ?: 0 // HDMI hotplug may invalidate the saved mode id.
                targetWindow.attributes = attributes
                Log.i(TAG, "refresh-match restored mode=${attributes.preferredDisplayModeId}")
            }
        }.onFailure { Log.w(TAG, "refresh-match restore unavailable", it) }
        originalModeId = null
        ownedModeId = null
    }

    fun close() {
        update(null)
        view.removeOnAttachStateChangeListener(this)
        surfaceView?.holder?.removeCallback(this)
    }

    private fun stopListening() {
        if (listening) runCatching { displayManager?.unregisterDisplayListener(this) }
        listening = false
    }

    override fun onViewAttachedToWindow(v: View) = update(rate)
    override fun onViewDetachedFromWindow(v: View) { clearVotes(); stopListening() }
    override fun surfaceCreated(holder: SurfaceHolder) = apply()
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = apply()
    override fun surfaceDestroyed(holder: SurfaceHolder) { votedSurface = null; votedRate = null }
    override fun onDisplayAdded(displayId: Int) = apply()
    override fun onDisplayRemoved(displayId: Int) = apply()
    override fun onDisplayChanged(displayId: Int) {
        if (view.display?.displayId == displayId) {
            if (rate != null) Log.i(TAG, "refresh-match observed hz=${view.display?.mode?.refreshRate} display=$displayId")
            apply()
        }
    }

    private fun Display.Mode.snapshot() = RefreshRateDisplayMode(modeId, physicalWidth, physicalHeight, refreshRate)

    @androidx.annotation.RequiresApi(30)
    private fun setSurfaceRate(surface: Surface, fps: Float, compatibility: Int) {
        if (Build.VERSION.SDK_INT >= 31) {
            surface.setFrameRate(fps, compatibility, Surface.CHANGE_FRAME_RATE_ALWAYS)
        } else {
            surface.setFrameRate(fps, compatibility)
        }
    }

    companion object {
        private const val TAG = "DisplayRefreshRate"
        private fun absRate(a: Float, b: Float) = kotlin.math.abs(a - b) < 0.01f
        private tailrec fun Context.activity(): Activity? = when (this) {
            is Activity -> this
            is ContextWrapper -> if (baseContext !== this) baseContext.activity() else null
            else -> null
        }
    }
}
