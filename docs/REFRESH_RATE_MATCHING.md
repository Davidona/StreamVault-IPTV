# Automatic refresh matching (issue #257)

## Plan and behavior

1. Persist a global playback toggle, enabled by default, with settings search and backup/restore.
2. Let only the visible fullscreen player request the content presentation rate. Use declared FPS,
   falling back to the existing timestamp detector when metadata omits it. Account for user playback
   speed, but not temporary live-latency speed adjustments.
3. Use `Surface.setFrameRate` with fixed-source compatibility on Android 12+ SurfaceView outputs,
   permitting a nonseamless switch when Android's system setting allows it. Android 11 phones use
   the seamless API. Disable the independent renderer vote so Off, preview, and multiview behave
   consistently and two owners cannot compete.
4. On older TVs (and TV TextureView outputs), select only supported modes at the current output
   resolution. Require an integer refresh/content ratio within 0.05%; keep fractional and integer
   broadcast rates distinct. Keep an already compatible mode, otherwise prefer 50 Hz for 25 fps,
   59.94 Hz for 29.97 fps, 60 Hz for 30 fps, and the content rate for other cadences.
5. Debounce rate changes for one second. Retain the previous vote briefly during zapping, clear it
   after five seconds without a valid rate, and release it on Off, end, error, background, PiP,
   casting, surface detach, engine replacement, and screen disposal. Pausing and buffering keep the
   vote to avoid unnecessary HDMI resyncs. Surface recreation reapplies the request.
6. Reevaluate on display changes, never force unsupported modes, and restore only a window request
   still owned by this controller. Invalid saved mode ids after HDMI hotplug restore to the system
   default. TextureView fallback on Android 12+ respects Never/Seamless/Always system preferences.

## Why an enabled-by-default toggle

An integer multiple is an exact match for motion cadence: 25 fps at 50 Hz shows each frame twice.
HDMI mode switches can briefly blank the screen, and vendor implementations differ, so users need
an opt-out. The feature makes requests; the OS/display may decline them. It does not transcode the
video, change stream quality, switch output resolution, pause playback, or retry rejected modes.

## Validation scope

Unit coverage targets fractional rates, cadence multiples, resolution preservation, unsupported
modes, stable selection, and request lifecycle. Emulator checks can confirm requests and playback
stability; an HDMI-capable physical TV is required to confirm actual nonseamless display switching.

References:
- https://github.com/Davidona/StreamVault-IPTV/issues/257
- https://developer.android.com/media/optimize/performance/frame-rate
- https://developer.android.com/reference/android/hardware/display/DisplayManager#getMatchContentFrameRateUserPreference()
- https://developer.android.com/reference/androidx/media3/exoplayer/ExoPlayer#setVideoChangeFrameRateStrategy(int)
