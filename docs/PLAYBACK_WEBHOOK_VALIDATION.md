# Playback webhook validation

Branch: `codex/playback-webhooks`, based on local `develop` at `1588c6406`.
Validation dates: 2026-11-12–13.

## Automated checks

- Domain suite: 347 tests, including 12 webhook planner cases.
- Playback: 368 tests; settings: 1,127 tests; app: 204 tests. All pass.
- Targeted data tests: 12 pass. They cover the HTTP boundary, encrypted configuration/outbox recreation, destination isolation, cancellation, stable retry identities, and receiver serialization. An earlier run under host memory pressure timed out in two HTTP cases; the complete targeted rerun passes.
- `python tools/verify_webhook_locales.py`: 47 keys in all 38 advertised locales, including formatting contracts.
- Debug application and instrumentation APKs compile. Release shrinking is untested.

## Computed contrast

`PlaybackWebhookContrastTest` computes WCAG relative luminance ratios for every palette. Text needs 4.5:1; meaningful borders, cursor, and selection handles need 3:1. The new controls retain the same surface/text tokens when resting, pressed, focused, and disabled. Focus adds a 2dp `AppColors.Focus` border; text selection uses `focus` at 22% over `surfaceElevated`.

| Palette | textPrimary / surfaceElevated | textPrimary / canvas | focus / surfaceElevated | focus / canvas | textPrimary / selection | focus / selection |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| classic_blue | 14.704 | 17.719 | 14.807 | 17.843 | 7.364 | 7.416 |
| m3_purple | 15.979 | 18.434 | 5.004 | 5.772 | 11.687 | 3.660 |
| light | 14.288 | 15.244 | 9.785 | 10.439 | 9.653 | 6.611 |
| forest | 12.614 | 17.439 | 12.966 | 17.926 | 6.363 | 6.541 |
| sunset | 13.816 | 17.546 | 13.330 | 16.928 | 7.070 | 6.821 |
| ocean | 12.441 | 17.488 | 12.639 | 17.766 | 6.292 | 6.392 |
| rose | 13.562 | 17.678 | 13.044 | 17.003 | 6.952 | 6.686 |
| amoled_black | 17.357 | 19.456 | 18.734 | 21.000 | 8.745 | 9.439 |
| vintage | 11.181 | 12.355 | 8.764 | 9.685 | 7.724 | 6.055 |

These pairs cover action labels/values, help/errors/diagnostics/preview text, input text/labels, cursor, selection, and focus borders in the changed settings components. No new color or typography roles and no video overlays were added.

## Emulator acceptance

The dedicated Android 36 TV emulator uses synthetic local media and a local authenticated receiver. It contains no user provider credentials. `tools/playback_webhook_fixture.py` serves MP4 and rolling HLS fixtures and records native/Jellyfin/Yamtrack requests independently.

`PlaybackWebhookJourneyTest`: **1 passed**, 444.847 seconds. `PlaybackWebhookSettingsJourneyTest`: **3 passed**, 33.792 seconds.

The app journey delivered movie start/pause/resume/progress/watched/stop with TMDB 603, and episode events with series TMDB 1396, season 0, episode 1. The native payload keeps series and episode identities separate. The local receiver also accepted Jellyfin movie start/stop and episode stop, plus Yamtrack movie start/stop. Episode identity unavailable at the initial observation can be enriched on later events; unsupported Yamtrack episodes are skipped. This is a local payload/delivery check, not a real third-party history insertion test.

All playback ran with four destinations enabled, including an unreachable port, demonstrating delivery isolation. Real-time coalescing suppressed the previous live channel's pending stop when newer state arrived; use History delivery when every transition is required.

| Live channel | Screenshots | Cadence | Unique SHA-256 | Unique center-crop SHA-256 | Final media session | Log result |
| --- | ---: | --- | ---: | ---: | --- | --- |
| Webhook News | 61 | 2s sleep plus capture overhead; over 2 minutes | 61 | 61 | PLAYING(3), error=null | One HLS prepare and first frame; no fatal/stuck/MPEG-TS fallback |
| Webhook Sports | 61 | 2s sleep plus capture overhead; over 2 minutes | 61 | 61 | PLAYING(3), error=null | One HLS prepare and first frame; no fatal/stuck/MPEG-TS fallback |

Early/middle/late screenshots were inspected: the moving image pattern and embedded media time advance through both windows. Center-crop hashes exclude the player chrome and corner clock. The final continuous source shows no wrap artifacts. Earlier acceptance-harness HTTP snapshot and Android state-format failures were corrected; the final journey passes end to end.

Captured **52 component screenshots**: destination list, editor, validation error, options, event filters, and preview in `classic_blue`, `light`, and `amoled_black`, default and focused; plus list/editor default/focused in Arabic, Persian, Hebrew, and German. The longest help translations (Filipino and Greek) also pass a 320dp layout-overflow assertion. Captured **18 additional production screenshots** of the App & Remote overview, webhook page, and editor in the three required themes, including actual TV keyboard/focus behavior. Remote Enter opens the editor and Back returns to the list. Other screen/state/locale combinations are untested.

Local evidence (ignored build artifacts):

- `build/webhook-validation/visuals/webhook-visuals/`: 52 component PNGs.
- `build/webhook-validation/playback/`: production PNGs, 122 live PNGs, sanitized per-channel playback logs, media-session snapshots, and receiver JSON.
- `build/webhook-validation/frame-progression.json`: full and center-crop hash counts.
- `build/webhook-validation/live-sheet.png`: early/middle/late visual comparison.

### Reproducing the opt-in journey

Use a fresh dedicated Android TV emulator; the journey refuses a device with existing providers. Generate a 40-second `movie.mp4`, a 10-second `episode.mp4`, and 300 two-second `segment000.ts` … `segment299.ts` HLS segments in an ignored local directory, then start the fixture. The ten-minute source avoids a fixture loop/discontinuity during this journey; a shorter preliminary fixture showed artifacts at its wrap boundary.

For example, generate continuous HLS with FFmpeg (after creating the output directory):

```powershell
ffmpeg -f lavfi -i testsrc2=size=640x360:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 600 -c:v libx264 -preset ultrafast -crf 30 -g 50 -keyint_min 50 -sc_threshold 0 -c:a aac -f hls -hls_time 2 -hls_list_size 0 -hls_segment_filename build/webhook-fixture-media/segment%03d.ts -y build/webhook-fixture-media/static.m3u8
```

Run the server and journey:

```powershell
python tools/playback_webhook_fixture.py --root D:/antigravity-scratch/iptv-player/build/webhook-fixture-media
adb install --no-streaming -r -t app/build/outputs/apk/debug/app-debug.apk
adb install --no-streaming -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant com.streamvault.app.debug android.permission.POST_NOTIFICATIONS
adb shell am instrument -w -r -e webhookJourney true -e class com.streamvault.app.playback.PlaybackWebhookJourneyTest com.streamvault.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The fixture uses host port 8877 and emulator host alias `10.0.2.2`; port 8878 is intentionally unavailable. Restart the fixture between acceptance runs to clear its in-memory event list. The journey restores webhook configuration and removes its test provider in `finally`. It inserts synthetic VOD after startup catalog sync so that sync cannot delete its records. Evidence is written below the debug app's external `files/webhook-playback` directory.

The separate `PlaybackWebhookSettingsJourneyTest` renders the production page against a deterministic port and captures layouts independently of network delivery. It also tests remote activation/Back and the longest help translations at 320dp. Its input screenshot harness intercepts the software keyboard and advances focus animation frames explicitly; the app journey also captures the real TV keyboard.

## Boundaries

- Real Yamtrack/Jellyfin/Watcharr/Home Assistant installations are untested. Receiver formats are pinned source contracts; an HTTP success does not prove a tracker inserted history.
- Yamtrack v0.26.3 episodes require actual episode IMDb/TVDB IDs. Catalog show TMDB IDs are emitted as `series_ids` in native JSON; unsupported Yamtrack episodes are skipped locally.
- Watcharr needs linked Jellyfin identities and server lookups; standalone compatibility is not claimed.
- Physical TCL/Sony TVs, release/R8 runtime, process termination on a physical device, and native-speaker translation review are untested. No new video overlay requires GPU measurement.
- Catch-up, recordings, trailers, unidentified external URLs, cast, external-player playback, and multiview are outside the standard-player webhook scope.
