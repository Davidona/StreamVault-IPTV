# Phase 5 Task 10 Validation Metadata

Date: 2026-08-25
Device: `Television_1080p(AVD) - 16` / API 36 / `emulator-5554`
APK: `app/build/outputs/apk/debug/app-debug.apk`
APK SHA-256: `0B346848DE643483914E54FC3EA98471C61F13DBB46922FAF25DEE2042E05DB1`

## Connected runs

| Run | Result |
|---|---|
| `:feature:playback:connectedDebugAndroidTest` overlay golden class | 6/6 passed |
| `:app:connectedDebugAndroidTest` | 22/27 passed; 5 existing fixture/provider/focus failures |
| Fresh APK install and explicit `MainActivity` launch | passed |
| ADB UI hierarchy startup check | `streamvault.destination:home`; horizontal top navbar present |
| `:benchmark:compileBenchmarkKotlin` | passed |

The five app-connected failures and their exact causes are recorded in
`docs/COMPOSE_REDUCTION_PHASE5_PLAYBACK_REPORT.md`. They are not counted as
successful playback coverage.

## Provider-dependent gates

The debug APK was rebuilt with the configured public M3U seed and synced 1,459
channels. Two-channel live validation is now attempted and recorded locally:

| Channel | Frames | Unique hashes | Final session | HLS prepares / first frames | Retries / `state=ERROR` | Result |
|---|---:|---:|---|---:|---:|---|
| 3ABN English | 61 | 61 | `PLAYING`, `error=null` | 8 / 6 | 5 / 35 | Not accepted |
| 3ABN French | 61 | 61 | `PLAYING`, `error=null` | 4 / 4 | 3 / 21 | Not accepted |

Both streams rendered video but repeatedly hit recoverable HLS
`BehindLiveWindowException`/`Source error` transitions during the capture
window. Neither log contained fatal-error, stuck-player, MPEG-TS fallback, or
malformed-HLS fallback markers. Baseline-profile generation remains open until
the runtime stability gate is resolved.

## ADB artifact

`adb-smoke/task10-home-top-navbar.png` is a valid PNG captured from the fresh
APK. The corresponding UI dump identifies the top horizontal navigation and
the Home destination. The artifact is a startup/layout check only.
