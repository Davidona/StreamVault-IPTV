# Phase 7 player capability acceptance

Validation date: 2026-09-06
Build: `app-debug.apk` from commit `d6b5f273`
Device: `Television_1080p` AOSP TV emulator, `emulator-5554`
Capture method: binary-safe `cmd.exe` redirection from `adb exec-out screencap -p`
Capture loop: 2-second sleep interval between captures
Window: 61 screenshots per channel; measured wall-clock windows were 188 seconds for channel 1 and 201 seconds for channel 2 because each capture also includes ADB overhead.

## Channel 1 — 00s Replay

- Frames: 61/61 captured.
- Unique SHA-256 frame hashes: 61.
- Media session: `PLAYING(3)`, `error=null`.
- Media metadata: `00s Replay`.
- Log evidence: HLS prepare and first-frame success; no fatal error, stuck-player timeout, or MPEG-TS fallback in the sanitized capture log.
- Result: PASS.

## Channel 2 — 3ABN Dare To Dream Network

- Frames: 61/61 captured.
- Unique SHA-256 frame hashes: 61.
- Media session: `PLAYING(3)`, `error=null`.
- Media metadata: `3ABN Dare To Dream Network`.
- Log evidence: fresh startup produced HLS prepare and first-frame success, followed by a full-playback media session in `PLAYING(3)` with `error=null`; no fatal error, stuck-player timeout, or MPEG-TS fallback appeared in the captured log.
- Result: PASS.

## Overall result

The required multi-channel long-duration player acceptance gate passed on two
channels. Screenshots continued changing through both full capture windows,
both media sessions remained healthy, and no unintended MPEG-TS fallback was
observed.

Raw screenshots and unsanitized logcat were kept outside the repository under
the local temporary directory and are not committed. URLs, credentials, query
strings, and provider-specific identifiers were removed from the committed log
excerpt.

## Structural follow-up

The first PlayerScreen decomposition slice is recorded in commit `28e0cbee`:
`PlayerVideoSurface` now owns the existing platform-backed render-view call,
while preserving its original first-child position, arguments, modifier, and
`PlayerRenderView` lifecycle.

Scoped verification on the same `Television_1080p` emulator passed:

- `:feature:playback:compileDebugKotlin`
- `:feature:playback:testDebugUnitTest`
- `:app:assembleDebug`
- `:feature:playback:connectedDebugAndroidTest` — 7/7 tests

Manual smoke also confirmed launch into live playback, visible video, the
Local DVR controls surface, and media-session `PLAYING(3)` with `error=null`.
The direct rewind interaction was attempted, but no unambiguous behind-live
state transition was observed, so the plan's manual seeking checkbox remains
open. The three pre-existing `PlayerSmokeTest` focus/track failures were not
changed by this structural slice.
