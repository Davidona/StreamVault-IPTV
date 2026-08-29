# Compose Reduction Phase 5: Live report

Date: 2026-08-29  
Slice: `:feature:live` (Phase 5 order 4 of 6)  
Rollback point: `ea349d83dceb8baa45ef1a18b7a45fa295a264f5`

## Outcome

Home Live TV, categories, channel preview, EPG/Guide presentation, state, and
dialogs now compile under `:feature:live`. The app remains the composition root
for route registration, platform/scaffold adapters, typed player request
mapping, and Playback MultiView composition. The slice is not a Phase 5
completion claim: locale parity, exact golden review, exhaustive fixture-driven
journeys, performance/profile evidence, and neighboring slice gates remain
open.

## Ownership and boundaries

Live-owned production includes Home and Guide screens, ViewModels/state,
preview pane and lifecycle calls, category/channel/EPG hosts, dialogs, remote
shortcut policy, default resources, unit tests, and connected presentation
tests. `:app` owns `LiveGraph`, route compatibility, `AppLivePlaybackRequestMapper`,
the Home/EPG scaffold and platform adapters, and the Playback MultiView planner
adapter. Preview handoff remains delegated to the existing Playback singleton.

The feature boundary verifier passes with only the approved project
dependencies: `:core:navigation`, `:core:ui`, `:domain`, `:data`, and `:player`.
No app or feature-to-feature implementation imports remain. The two temporary
`:data` imports (`PreferencesRepository` and `ProviderSyncStateSource`) remain
documented for the Phase 7 domain-contract migration.

## Delivered checkpoints

The implementation was built incrementally from the rollback point through the
following Live checkpoints (plus the final lint fix):

- `bd4f2649`, `96e9c9b9`: move Home and EPG presentation units;
- `108ca79f`, `aeb7cea7`, `6766c528`, `d27fff05`, `597f9706`, `e2422a6b`:
  Home composition ports, adapters, and remote dispatch;
- `abc05272`, `6d2d6d9d`, `ccf06258`, `c8957551`, `62124ade`, `844a1f19`,
  `a71ec037`, `979cf11c`, `b715d026`, `57b9389c`, `218d6b4a`, `95d40b96`,
  `e7864086`, `19b95766`: extracted Home/EPG policies, focus, grid, and
  presentation seams;
- `ffcf710b`, `23c26d64`, `8a3e8cd1`, `354d2842`, `28971f89`, `7de15c6a`:
  preview/route/graph/request contracts and connected presentation coverage;
- `0cf3927c`: Compose lint-safe resource lookups and Crossfade state usage.

## Verification

- `:feature:live:verifyFeatureLiveBoundary`: passed.
- `:feature:live:connectedDebugAndroidTest`: passed after the final lint fix,
  7 tests, 0 failures, 0 errors, 0 skipped (56s). XML is under
  `feature/live/build/outputs/androidTest-results/connected/debug/`.
- Focused feature/app structural bundle (unit tests, lint, Android-test
  compilation, app compilation/tests, and debug assembly): passed after the
  lint fix. Gradle reported 235 actionable tasks, 23 executed, 212 up-to-date.
- Final fresh verification (`:feature:live:verifyFeatureLiveBoundary`,
  `:feature:live:check`, Android-test compilation, app unit tests/assembly, and
  `verifyBaselineProfileSources`): passed in 2m 35s (373 actionable tasks; 23
  executed, 2 from cache, 348 up-to-date).
- Graphify refreshed at 15,797 nodes, 30,791 edges, 436 communities. The
  legacy app Home/EPG source directories and imports are absent.
- The locale audit compared all 245 moved defaults and placeholder sequences
  against every app locale. It corrected two mojibake feature defaults
  (`live_preview_loading` and `time_range_format`) and found no remaining
  default or placeholder mismatch; translated-locale migration remains open.
- The existing app Live route golden remains open: exact pixel drift at `(0,0)`
  from RGB `(6,16,26)` to `(7,17,27)`. The baseline was not rewritten.

## Runtime acceptance

Using `E:\androidSdk\platform-tools\adb.exe` on `emulator-5554`
(`Television_1080p`, API 36, `AOSP_TV_on_x86`), both channels were opened from
Live TV preview into full playback and captured for approximately two minutes:

| Channel | Frames / cadence | Unique hashes | Media session | Logs |
| --- | --- | ---: | --- | --- |
| `01 00s Replay` | 61 / ~2 s / ~120 s | 61 | `PLAYING`, `error=null` | HLS prepare + `first-frame-success`; no fatal/stuck/MPEG-TS markers |
| `02 3ABN Dare To Dream Network` | 61 / ~2 s / ~124 s | 61 | `PLAYING`, `error=null` | HLS prepare + `first-frame-success`; no fatal/stuck/MPEG-TS markers; two non-fatal video-stall/reprepare warnings |

Full PNG, hash, media-session, and logcat evidence is in
`validation/phase5_live/task10-live-playback/`. The connected and route smoke
record is `validation/phase5_live/task10-connected-validation.md`.

The bounded Guide journey moved focus right/right through the timeline,
down/down through program rows, and left/up back toward channel content while
remaining on `streamvault.destination:epg`; Back restored
`streamvault.destination:home`. The corresponding local screenshots and
hierarchies are under `build/adb-validation/guide-journey-*` and
`guide-back.xml`.

A follow-up D-pad-first pass opened Live TV from Home, selected the `Movies`
category (77 channels rendered), moved into the preview pane, and opened Guide
through the top navigation. Valid PNG/XML artifacts are in
`validation/phase5_live/task10-manual.md` and the adjacent
`task10-manual/` directory. Touch taps on Guide `Program Search`/`Options` did
not change state on this emulator, and visible channels had no schedule data;
the exhaustive search/filter/PIN/favorite/reorder/reminder/recording/archive/
RTL/reduced-motion matrix therefore remains open. A second Enter handed the
selected Guide channel to the existing full-screen player; its overlay exposed
EPG, Multiview, Stats, Record, and Pause, and Back returned to the Guide route.
Activating Multiview returned to the Live route with the empty-favorites/queue
state; no queued-channel fixture was available to validate a populated planner
result.

## Open gates

- Full Home/EPG ViewModel-driven manual journey matrix (search, filters,
  hidden/locked/favorite/reorder/PIN, reminders/recording conflicts, archive,
  RTL/reduced-motion, focus restoration, and MultiView edge cases) remains
  fixture-dependent and open.
- Locale-by-locale resource parity is open. The feature has 245 default keys;
  translated app locales remain in the app until values and placeholders are
  compared. No app resources were deleted.
- Golden baseline review, the formal paired performance target, clean/warm
  guardrails, and baseline/startup profile regeneration are open. Five
  post-extraction Live source-edit and five moved-test samples are recorded in
  `validation/phase5_live/performance-after.md`; they establish the
  post-extraction envelope but are not a comparable pre/post pair because the
  available pre record is no-change rather than source-edit.
  `verifyBaselineProfileSources` passes (46,249 baseline / 30,093 startup
  rules), but `:app:generateBaselineProfile` hit a JVM native-memory OOM during
  release Java compilation; generated files still contain stale app Home/EPG
  descriptors and were not hand-edited. Details are in
  `validation/phase5_live/profile-validation.md`.
- Playback, Provider, and Settings acceptance/performance gates remain governed
  by their existing reports. The separate Provider and Settings `check` tasks
  pass; the aggregate neighboring command stops at the pre-existing Playback
  lint gate (29 errors, first at `PlayerOverlayGoldenTest.kt:51`). Catalog and
  System were not started. Phase 5 is not complete.
