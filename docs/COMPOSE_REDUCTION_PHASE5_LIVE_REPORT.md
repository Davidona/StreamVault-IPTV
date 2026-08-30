# Compose Reduction Phase 5: Live report

Date: 2026-08-30
Slice: `:feature:live` (Phase 5 order 4 of 6)  
Rollback point: `ea349d83dceb8baa45ef1a18b7a45fa295a264f5`

## Outcome

Home Live TV, categories, channel preview, EPG/Guide presentation, state, and
dialogs now compile under `:feature:live`. The app remains the composition root
for route registration, platform/scaffold adapters, typed player request
mapping, and Playback MultiView composition. The slice is not a Phase 5
completion claim: the remaining exhaustive fixture-driven journeys, formal
paired performance/clean-build guardrails, profile regeneration, and
neighboring slice gates remain open.

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
- `:feature:live:connectedDebugAndroidTest`: final full-suite rerun passed 24
  tests, 0 failures, 0 errors, and 0 skipped (the original 13 tests plus the
  Home 6-test and Guide 5-test screen-host suites). XML is under
  `feature/live/build/outputs/androidTest-results/connected/debug/`.
- `LivePresentationGoldenTest`: passed 6/6 at 1920x1080 after visual review.
  The checked-in feature baselines cover selection chips, locked/unlocked
  category rows, progress/locked channel surfaces, the app-parity row fixture,
  the selected/unavailable source switcher, and the reorder bar. The separate
  app-owned route golden is covered by the review and regeneration checkpoint
  below.
- Focused feature/app structural bundle (unit tests, lint, Android-test
  compilation, app compilation/tests, and debug assembly): passed after the
  lint fix. Gradle reported 235 actionable tasks, 23 executed, 212 up-to-date.
- Final fresh verification (`:feature:live:verifyFeatureLiveBoundary`,
  `:feature:live:check`, the focused app-adapter and Playback-handoff tests,
  app debug assembly, and `verifyBaselineProfileSources`): passed in 7m 39s
  (375 actionable tasks; 64 executed, 37 from cache, 274 up-to-date). The
  focused XML results contain 4 app-adapter and 9 Playback-handoff tests, all
  passing with no failures, errors, or skips.
- Fixture-flow follow-up on 2026-08-30: the reversible Live quick-filter
  save/reload/remove journey passed. `Movies` survived force-stop/relaunch,
  was removed through Settings > Browsing > Live TV Quick Filters, and the
  final Live drawer reported no saved filters. The selected synthetic
  schedule/PIN contract suite also passed: 39 tests, 0 failures, 0 errors,
  and 0 skips across `:feature:live` and `:domain`.
- Direct app adapter coverage was completed test-first for stream-result
  preservation, exactly-once surface refresh, MultiView occupied-slot/capacity
  mapping, and preview-origin mapping. The four app tests plus the existing
  Playback handoff suite passed together in 40s (163 actionable tasks; 12
  executed, 2 from cache, 149 up-to-date).
- Graphify refreshed at 15,838 nodes, 30,884 edges, 440 communities. The
  legacy app Home/EPG source directories and imports are absent.
- The locale audit compared all 245 moved defaults and placeholder sequences
  against every app locale. It corrected two mojibake feature defaults
  (`live_preview_loading` and `time_range_format`) and found no remaining
  default or placeholder mismatch. All 25 translated feature locale files now
  have `featureMissing=0`, `featureValueMismatch=0`, and
  `featureUnexpected=0`; app copies remain for shared consumers.
- The app-owned Live route golden was reviewed and regenerated from a fresh
  1920x1080 recorder capture after confirming the layout/content matched and
  the drift came from the current `AppColors.Canvas`/gradient palette. The
  non-recording `live_route_matchesGolden` assertion now passes 1/1.
- The extracted Home/Guide behavior coverage is now present in
  `HomeScreenBehaviorTest` (6 tests) and `EpgScreenBehaviorTest` (5 tests).
  These validate presentation-host contracts; they do not close the
  fixture-dependent ViewModel journey matrix below.
- The documented post-extraction clean debug command passed twice: 4m 46s
  after configuration-cache invalidation (136 executed, 73 from cache) and 1m
  01s on the final tree with configuration-cache reuse (95 executed, 114 from
  cache). Both had 209 actionable tasks. Five final-tree warm no-change builds
  passed with a 13.690s median and 13.984s average; every run had 1 executed
  and 197 up-to-date tasks. The 3.7x clean-run spread makes the formal clean
  guardrail inconclusive without cache-equivalent pre/post snapshots. Exact
  samples and limitations are in `validation/phase5_live/performance-after.md`.
- Fresh `:app:generateBaselineProfile` passed on the `Television_1080p` API 36
  emulator in 13m 45s: the two `BaselineProfileGenerator` tests passed and the
  eight separate macrobenchmark tests were skipped by configuration. It
  generated 48,627 baseline and 31,990 startup rules. The follow-up
  `verifyBaselineProfileSources`, `:app:assembleBeta`, and
  `:app:assembleRelease` passed in 3m 41s (387 actionable tasks; 20 executed,
  1 from cache, 366 up-to-date). Fresh descriptor scans found zero stale app
  Home/EPG matches and 1,544 baseline / 43 startup `feature/live` matches.
  The earlier no-device and native-memory failures remain recorded as
  historical attempts; generated profiles were never hand-edited. A direct
  `:benchmark:connectedNonMinifiedReleaseAndroidTest` attempt also passed in
  13m 09s, but its XML again reports the eight macrobenchmark cases as
  `ignored (-)`; no performance measurements were produced.

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
not change state on this emulator, although both controls were later reached
through D-pad focus. Visible channels had no schedule data; the remaining
schedule/PIN/reminder/recording/archive and filtered-result matrix therefore
remains open. A
second Enter handed the
selected Guide channel to the existing full-screen player; its overlay exposed
EPG, Multiview, Stats, Record, and Pause, and Back returned to the Guide route.
Activating Multiview returned to the Live route with the empty-favorites/queue
state. A follow-up populated-planner journey switched to `All Channels`,
long-pressed `01 00s Replay`, added it through the management dialog, assigned
it to slot 1, launched MultiView, and returned with Back. The planner evidence
is under `validation/phase5_live/task10-multiview/`; the remaining manual matrix
is still fixture- or input-path-dependent. A follow-up fixture pass also
validated adding/removing two channels from Favorites and entering populated
Favorites reorder mode; the final device state was restored to zero favorites.
The D-pad-activated category search accepted `Movies` and filtered the
category rows. The D-pad-activated All Channels search accepted the `Replay`
input path (received as `Rep`) and reported exactly one result, `01  00s
Replay`; both queries were cleared and All Channels returned to `1,467`.
The Add quick filter dialog accepted `Movies` and enabled `Save filter`; it
was then saved, survived a force-stop/relaunch, and was removed through
Settings. The final Live drawer and Settings row reported no saved filters;
the device artifacts are under
`validation/phase5_live/task11-fixture-flows/`. The same fixture pass
then opened the Movies `Lock Group` action and
reached the focused `Enter PIN` keypad; no PIN was submitted because the
configured value was unavailable, Back canceled the dialog, and Movies
remained unlocked (`live_lock_options.*`, `live_pin_dialog.*`, and
`live_after_pin_cancel.xml`).
The populated Favorites reorder path was also exercised: `00s Replay` was
grabbed and moved above the other fixture favorite with D-pad Up, then the
unsaved reorder was canceled and both favorites were removed; the final count
was zero (`live_reorder_dragging.xml`, `live_reorder_moved_up.xml`, and
`live_fav_restored_again.xml`).
The Save Order callback was also validated in a reversible round trip: the
swapped order was saved, the original order was restored and saved again, and
both favorites were removed (`live_save_order_saved.xml`,
`live_save_order_restored.xml`, and `live_save_order_final.xml`).

A further reversible device pass changed the locale from `en-US` to `ar-SA`:
Live TV and Guide stayed interactive, Arabic labels rendered, and the Live
sidebar plus Guide grid mirrored correctly before the locale was restored.
The three Android animation scales were then set to `0.0`; Guide remained
interactive through D-pad input with no app fatal marker in the bounded scan,
and all scales were restored to `1.0`. D-pad also opened Program Search and
Guide Options; Guide mode was switched and restored. The Compose text field
accepted Home category/channel text input after D-pad activation. Guide
Program Search was also activated through D-pad and accepted `Movies`; the
dialog was dismissed and Live TV restored, but the loaded provider's visible
guide rows still reported `No schedule`, so a distinct filtered program-result
assertion remains unavailable. Screenshots and hierarchies are recorded in
`validation/phase5_live/task10-manual/`.

The planner edge follow-up populated two slots, removed slot 2, replaced the
occupied slot 1 with the pending channel, and used Clear All. The planner
dismissed as designed, and the final Live fixture state remained Favorites `0`
and All Channels `1,467`. Evidence is under
`validation/phase5_live/task10-multiview/`.

A further planner pass launched the two-slot configuration with `00s Replay`
and `3ABN Dare To Dream Network`; the MultiView surface showed both channel
labels and two empty slots. The planner was reopened from a queued channel,
Clear All removed both active slots, and Live TV was restored with no split
badge, Favorites `0`, and All Channels `1,467`.

## Open gates

- Full Home/EPG ViewModel-driven manual journey matrix (Guide search focus
  restoration, mutation, PIN submission/unlock verification, reminders/recording
  conflicts, archive, focus restoration, and any still-unexercised MultiView cases)
  remains fixture-dependent and open. Hidden category/channel hide and restore
  paths, PIN-dialog reachability, RTL/reduced-motion smoke checks, and the
  core planner placement/removal/replacement/clear and two-slot launch paths
  are covered in the
  follow-up fixture/device passes; PIN submission/unlock verification, Guide
  filtered-result verification, and the unavailable schedule branch remain
  open. Quick-filter save/reload/remove is no longer open.
- The feature presentation golden review and the app-owned Live route golden
  review are complete. The app baseline was regenerated from the reviewed
  current capture; the old baseline is preserved as
  `route_live_browse_baseline_before.png` in the ignored follow-up evidence.
- The formal paired performance target and clean-build guardrail remain open.
  Five
  post-extraction Live source-edit and five moved-test samples are recorded in
  `validation/phase5_live/performance-after.md`; they establish the
  post-extraction envelope but are not a comparable pre/post pair because the
  available pre record is no-change rather than source-edit.
  Two current clean task-graph runs passed at 4m46s and 1m01s under materially
  different cache states, so the clean guardrail is inconclusive; five
  final-tree warm no-change samples passed with a 13.690s median.
  `verifyBaselineProfileSources`, Beta assembly, and Release assembly pass
  against the regenerated 48,627 baseline / 31,990 startup rules. The
  baseline-profile producer's eight macrobenchmark tests were skipped by
  configuration, so
  macrobenchmark performance remains open even though profile generation and
  descriptor freshness now pass. Details are in
  `validation/phase5_live/profile-validation.md`.
- Playback, Provider, and Settings acceptance/performance gates remain governed
  by their existing reports. The separate Provider and Settings `check` tasks
  pass; the aggregate neighboring command stops at the pre-existing Playback
  lint gate (29 errors, first at `PlayerOverlayGoldenTest.kt:51`). Catalog and
  System were not started. Phase 5 is not complete.
