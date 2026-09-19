# Phase 5 Task 10 Validation Metadata

Date: 2026-08-26
Device: `Television_1080p(AVD) - 16` / API 36 / `emulator-5554`
APK: `app/build/outputs/apk/debug/app-debug.apk`
APK SHA-256: `0B346848DE643483914E54FC3EA98471C61F13DBB46922FAF25DEE2042E05DB1`

## Connected runs

| Run | Result |
|---|---|
| `:feature:playback:connectedDebugAndroidTest` overlay golden class | 7/7 passed |
| `:app:connectedDebugAndroidTest` | 22/27 passed; 5 existing fixture/provider/focus failures |
| Fresh APK install and explicit `MainActivity` launch | passed |
| ADB UI hierarchy startup check | `streamvault.destination:home`; horizontal top navbar present |
| `:benchmark:compileBenchmarkKotlin` | passed |
| Focused `BaselineProfileGenerator#criticalJourneys` connected run | 1/1 passed after MENU-based control-toggle fix |
| Full `:app:generateBaselineProfile` connected run | 10/10 profile tests passed; 8 unrelated macrobenchmark tests skipped; profiles merged/copied/installed |
| `verifyBaselineProfileSources` | passed (`baseline=48,641`, `startup=30,116` rules) |
| `:app:assembleBeta`, `:app:assembleRelease`, `:app:bundleRelease` | passed after profile generation |

The five app-connected failures and their exact causes are recorded in
`docs/COMPOSE_REDUCTION_PHASE5_PLAYBACK_REPORT.md`. They are not counted as
successful playback coverage.

## Provider-dependent gates

The debug APK was rebuilt with the configured public M3U seed and synced 1,459
channels. Two-channel live validation is now attempted and recorded locally.
The follow-up runs used an on-device capture loop to preserve the intended
two-second cadence while avoiding per-frame Windows-side ADB startup:

| Channel | Frames | Unique hashes | Final session | HLS prepares / first frames | Retries / `state=ERROR` | Result |
|---|---:|---:|---|---:|---:|---|
| 3ABN English | 61 | 61 | `PLAYING`, `error=null` | 8 / 6 | 5 / 35 | Not accepted |
| 3ABN French | 61 | 61 | `PLAYING`, `error=null` | 4 / 4 | 3 / 21 | Not accepted |
| 00s Replay (Pluto upstream) | 61 | 61 | `PLAYING`, `error=null` | continuous playlist fetches | 0 known fatal/stuck/live-window/MPEG-TS markers | Stability pass |
| 3ABN Dare To Dream Network (3ABN upstream) | 61 | 61 | `PLAYING`, `error=null` | 3 / 2 | 1 live-window retry / 7 `state=ERROR` / 9 source-error | Not accepted |

Both streams rendered video but repeatedly hit recoverable HLS
`BehindLiveWindowException`/`Source error` transitions during the capture
window. The cross-upstream follow-up produced one clean Pluto stability run,
but the 3ABN run reproduced live-window retries and source-error transitions.
Neither failing log contained fatal-error, stuck-player, MPEG-TS fallback, or
malformed-HLS fallback markers. The focused and full release-like profile
journeys passed after correcting the benchmark's live control-toggle key. The
full generation/merge/install task completed successfully; the runtime
stability gate and physical-device/profile-performance validation remain open.

The focused `PlayerSmokeTest` rerun reproduced the three previously recorded
fixture/focus failures (controls play-button focus, category-rail search-field
focus, and audio-track fixture setup); its mute-action test passed. The
playback Macrobenchmark journeys each completed five of five iterations on the
same emulator. Current diagnostic metrics were:

| Journey | Frame runs | Median | CPU P50/P90 | Overrun P50/P90 |
|---|---|---:|---:|---:|
| `liveTvCategoryAndChannelNavigation` | 30, 31, 30, 30, 31 | 30 | 606.8 / 939.8 ms | 922.4 / 1,278.7 ms |
| `playerControlsOpenAndNavigate` | 21, 31, 22, 32, 27 | 27 | 804.7 / 2,476.7 ms | 1,313.2 / 4,890.4 ms |

The runs are structural/diagnostic evidence only: live HLS preparation and
decoder work dominate this emulator result, so the macrobenchmark performance
comparison remains open until a controlled paired baseline is available. The
host was under heavy load during this capture, so these values are not a
performance conclusion; repeat the paired run on an idle host.

A manual seeded remote-input pass also completed Home → Live TV → All Channels
→ `00s Replay` preview → fullscreen playback. MENU exposed the player controls
chrome and the controls auto-hid back to unobstructed video; BACK returned to
Live TV with the preview present. Evidence is in
`manual-validation/20260826/`. This closes only the basic launch/controls/Back
journey; seeking, numeric/zapping, track and quality dialogs, PiP, Cast,
MultiView, touch/mouse, RTL, and reduced-motion coverage remain open.

The focused app unit rerun for the navigation hardening slice passed 8/8 tests
(`AppNavigationCoordinatorTest` 7/7 and `PlaybackProgressGuardrailTest` 1/1).

## ADB artifact

`adb-smoke/task10-home-top-navbar.png` is a valid PNG captured from the fresh
APK. The corresponding UI dump identifies the top horizontal navigation and
the Home destination. The artifact is a startup/layout check only.

## Isolated two-channel rerun (2026-08-27)

Each channel was isolated with `adb logcat -c` before launch and captured at
the requested two-second cadence for 61 screenshots. Raw screenshots were
transient and removed after hashing; no credentials, tokens, or other private
payloads were retained.

| Channel | Screenshots | Unique hashes | Final media session | Log findings | Result |
|---|---:|---:|---|---|---|
| 3ABN Dare To Dream Network | 61 | 61 | `PLAYING`, `error=null` | HLS prepare/first-frame; recoverable video-stall/reprepare and release-timeout retry; no fatal error, `BehindLiveWindowException`, MPEG-TS fallback, or stuck-player marker | Stability pass for this window |
| 3ABN French | 61 | 56 | `ERROR`, `error=Source error` | Video stalls selected `XTREAM_TS_FALLBACK`; subsequent `MPEG_TS_LIVE` prepares targeted a malformed `1/live/...` URL and ended in `fatal-error` | Not accepted |

The Dare-to-Dream run satisfies the screenshot, final-session, and no-fatal/
no-fallback criteria for its isolated window. The French run does not: it
reproduces the source/recovery failure and loses frame progression before the
window ends. The two-channel Phase 5 stability gate therefore remains open;
the recovery-policy implementation was not changed in this validation slice.
