# Compose Reduction Phase 5: Settings and Live Regression Audit

Date started: 2026-08-30
Date completed: 2026-08-31
Status: Audit complete; three isolated regression fixes verified, with documented residual risks

## Scope and comparison points

This audit compares the two most recent Phase 5 slices with their recorded
pre-extraction implementations:

| Slice | Historical baseline | First implementation commit | Target |
| --- | --- | --- | --- |
| Settings | `e12f816f` | `1c0e6ae6` | frozen working-tree snapshot based on `bb52375d` |
| Live/EPG | `ea349d83` | `712751b7` | frozen working-tree snapshot based on `bb52375d` |
| Combined Settings + Live | `e12f816f` | both slices | frozen working-tree snapshot based on `bb52375d` |

The frozen target includes non-document tracked patch hash
`29be114106b2c471531e25caa6fd1a54027d2b3b` and untracked manifest SHA-256
`46ebf25851b43bd6ecdb9daff3d1aa68fd2644c0313104c0fdcb36b4ad699cc9`.
The untracked files at freeze time were:

- `feature/provider/src/test/java/com/streamvault/feature/provider/navigation/ProviderGraphNavigationTest.kt`
- `feature/settings/src/test/java/com/streamvault/feature/settings/presentation/SettingsProviderSummaryFormattingTest.kt`

The external audit root is
`C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993`.
The captured patch SHA-256 is
`2f3d882d3e6aa9e234583d295c4f043132ee5d635aef8bcbf068d5af0582383`.
Reconstruction verification produced the same tracked Git-diff hash
`29be114106b2c471531e25caa6fd1a54027d2b3b` and untracked manifest SHA-256
`46ebf25851b43bd6ecdb9daff3d1aa68fd2644c0313104c0fdcb36b4ad699cc9`
in the target worktree. Both historical worktrees were clean after creation.

| Worktree | Path | State |
| --- | --- | --- |
| Settings baseline | `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\settings-baseline` | detached `e12f816f`, clean |
| Live baseline | `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\live-baseline` | detached `ea349d83`, clean |
| Integrated audit target | `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\target` | branch `audit/phase5-settings-live-integrated`; frozen `bb52375d` snapshot plus verified fixes through `3f4152d9` |

The accepted audit design and executable plan are:

- `docs/superpowers/specs/2026-08-30-phase5-settings-live-regression-audit-design.md`
- `docs/superpowers/plans/2026-08-30-phase5-settings-live-regression-audit.md`

## Isolation rule

The primary `feature/improveCompose` checkout is shared with another agent.
The audit will not change its branch, stage or commit files, edit code, build,
or install from it. Only Markdown evidence is written there. Historical
builds, target reconstruction, tests, screenshots, fixes, and fix commits are
performed in uniquely named external worktrees.

## Finding ledger

The two examples supplied in the request are known, already-fixed control
findings for this review. They are recorded to ensure the audit recognizes the
original symptom and the applied correction; they are not pending validation
tasks:

| Supplied example | Audit finding | Fixed behavior recorded |
| --- | --- | --- |
| Live TV category title was black instead of white | `LIVE-UI-001` | The category sidebar title explicitly retains the light/white `OnSurface` color. |
| Settings → Add Providers stayed in the Add Provider window after completion | `SET-NAV-001` | Completion returns through the existing Settings route via Back-first navigation with startup fallback. |

| ID | Slice | Classification | Severity | Summary | Evidence and status |
| --- | --- | --- | --- | --- | --- |
| `LIVE-UI-001` | Live | Confirmed regression, fixed in frozen patch | Major | The Live category sidebar title inherited host content color after extraction instead of retaining the original light/white `OnSurface` title. | Introduced by `9214b14b`. Baseline source explicitly used `OnSurface`; extracted header omitted it. The frozen patch restores it. The focused connected pixel test passed even under deliberately black `LocalContentColor`. |
| `SET-NAV-001` | Settings/provider entry | Pre-existing defect, fixed in frozen patch | Major | Completing Add Provider from Settings remained on the provider setup route instead of returning to the prior screen. | The behavior exists before Settings baseline `e12f816f`, so it is not an extraction regression. The supplied/frozen patch uses Back first with startup fallback, and the focused ProviderGraph navigation test passes. This is recorded as fixed; no additional production-shell replay is required for this known fix. |
| `SET-UPD-001` | Settings | Confirmed regression, fixed and verified | Major | A persisted newer app release was always mapped as `isUpdateAvailable = false` while applying a preference snapshot. | Introduced by `20a3f3f5`. Red test failed because the mapper exposed no comparator; fix `c2286afc` injects `appUpdatePort::isRemoteVersionNewer`. Focused and full Settings suites pass. Both historical and target checks used the same update release/version (`1.0.17.1`), so version drift does not explain the behavioral difference. |
| `SET-NAV-002` | Settings | Confirmed regression, fixed and verified | Major | The extracted parental-control screen received an empty top-navigation destination list. | Introduced by `0441d1e6` and retained by `579a00cc`. Fix `11f0ee6f` forwards the app-owned destinations. A composed route test and production-shell before/after screenshots verify restoration. |
| `SET-UPD-002` | Settings | Confirmed regression, already fixed | Critical | The extracted update adapter recursively called its own `isRemoteVersionNewer` override and could overflow the stack. | Introduced by `e9a44e24`; fixed in committed target by `d36005a7` using an aliased policy function. The target unit suite passed. |
| `LIVE-STATE-001` | Live | Confirmed regression, fixed and verified | Major | After a preview engine emitted `ERROR`, a later `READY`/`PLAYING` state left the engine error latched and could keep the recovered render surface hidden. | A red `IDLE -> ERROR -> READY` ViewModel test reproduced the latch. Fix `3f4152d9` tracks engine-originated errors and clears only those after recovery; the existing preparation-error persistence test still passes. |
| `LIVE-TEXT-001` | Guide | Confirmed regression, fixed in frozen patch | Minor | Extraction further corrupted an already-broken Guide preview time separator, adding invalid/mojibake code points. | Introduced by `f56d47da`. The frozen patch replaces it with a real en dash, improving on both extracted and historical text. Focused rendered/text coverage is still missing. |
| `LIVE-TEXT-002` | Guide | Pre-existing defect, open | Minor | The EPG override candidate descriptor separator remains mojibake. | The exact corruption is present in Live baseline `ea349d83`; it is not an extraction regression. |
| `SET-COPY-001` | Settings | Inconclusive copy drift | Minor | `Sync Now` and `Rebuild Index` became sentence-case `Sync now` and `Rebuild index`. | The only unexplained effective resource changes across the audited 26 resource folders. No behavior changes; product/design intent is not recorded. |
| `LIVE-POLICY-001` | Live | Inconclusive boundary drift | Minor | Archive eligibility is checked twice against wall clock instead of once. | Normally defensive/equivalent, but a request could expire between checks. Needs a shared/injected-clock boundary decision rather than a speculative fix. |
| `TEST-GOLDEN-001` | Live test infrastructure | Deterministic golden mismatch; no material UI regression observed | Minor | Six strict Live image goldens fail exact equality on the audit emulator. | Two target captures are byte-identical. All images retain 1920x1080 geometry; maximum channel delta is 1-7, mean absolute RGBA error is at most 0.0232, and no layout displacement is visible. Do not regenerate baselines until provenance/environment is resolved. |

No additional code changes were made in the shared primary worktree. The three
new production fixes exist only on the external integrated audit branch and
must be reviewed/cherry-picked separately.

## Improvements

- The frozen patch repairs pre-existing corrupted separators, arrows, minus,
  cloud, and dismiss glyphs across Settings, and repairs the Japanese backup
  count. These are improvements rather than extraction-regression fixes.
- The Guide preview separator fix produces a correct en dash, improving on the
  already-corrupt historical baseline as well as fixing the worse extracted
  value.
- French and Italian Live resource mojibake present in the historical baseline
  is corrected in the frozen patch.
- The update-comparison recursion is fixed in committed target code and guarded
  by a focused unit test.
- Live locale ownership is statically complete for the 25 audited locale
  folders, with Vietnamese and Chinese Live ownership added during extraction.
- The extracted feature boundaries replace app coupling with explicit ports and
  add targeted route, adapter, focus, remote-shortcut, preview, and stable-row
  coverage. These are architectural/testability improvements, not proof of
  runtime parity.

## Evidence matrix

| Area | Static review | Automated | Historical visual pair | Behavioral replay | Result |
| --- | --- | --- | --- | --- | --- |
| Settings shell, sections, dialogs, focus, Back | Static layout/order equivalent except findings | Integrated unit, boundary, assembly, and 13/13 connected tests passed | Settings entry and parental route paired | Parental navigation restored; Add Provider completion covered by focused route test | Pass |
| Settings provider/backup/parental/EPG/platform seams | Static adapter/action equivalence except `SET-*` findings | Cached-update and parental-route regression tests pass | Cached-update and parental top-nav pairs captured | Cached update action and parental destinations present | Pass |
| Live categories, channels, search, filters, favorites/reorder | Static policies/order/callbacks equivalent except title finding | 19/19 non-golden connected tests passed; strict title pixel test passed | Live baseline/target entry pair and six deterministic component candidates analyzed | Live list/category rendering exercised | Pass; exact goldens remain red |
| Guide grid, preview, options, search, recording/reminder/archive | Static policy equivalent except documented text/clock findings | Unit and boundary suites passed | Separator improvement inspected in source/resources | Credential/storage-dependent actions not replayed | Pass within available fixtures |
| Settings-to-Live persisted controls | Static cross-slice wiring reviewed | Integrated unit/boundary suites passed | Same provider fixture used | Live channels loaded after Settings checks | Pass |
| Preview/player/MultiView handoff | Static adapters/callback ordering equivalent except fixed `LIVE-STATE-001` | Recovery red/green test plus full Live unit suite passed | Not applicable | Two channels reached and retained playback with recovery | Pass |
| Two-channel long-duration Live playback | Not applicable | 61 screenshots per channel plus media/log checks | Not applicable | Both channels progressed and ended `PLAYING`, `error=null` | Pass with transient source-window recoveries documented |

## Automated verification log

### Target boundary and unit gate

The first attempt stopped during Gradle configuration because detached Git
worktrees do not copy the ignored `local.properties` file and neither
`ANDROID_HOME` nor `ANDROID_SDK_ROOT` was set. The primary checkout's existing
`local.properties` was copied into each external worktree; this supplies the
valid Android SDK path and the existing public development fixture without
changing the primary checkout. The identical Gradle command was then rerun:

```text
gradlew.bat :feature:settings:verifyFeatureSettingsBoundary \
  :feature:settings:testDebugUnitTest \
  :feature:live:verifyFeatureLiveBoundary \
  :feature:live:testDebugUnitTest \
  --console=plain --warning-mode=none
```

Result: `BUILD SUCCESSFUL in 2m 10s`; 87 actionable tasks, 37 executed and 50
from cache. Both fail-closed feature-boundary checks passed and both unit-test
suites passed. Gradle discarded the configuration-cache entry because the two
custom boundary tasks access project/script objects at execution time; this is
recorded as build-tooling debt, not a Settings or Live functional failure.
The complete successful log is
`C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\logs\target-boundary-unit-rerun.log`.

### Target check, Android-test compilation, and debug assembly

```text
gradlew.bat :feature:settings:check :feature:live:check \
  :feature:settings:compileDebugAndroidTestKotlin \
  :feature:live:compileDebugAndroidTestKotlin \
  :app:assembleDebug --console=plain --warning-mode=none
```

Result: `BUILD SUCCESSFUL in 5m 11s`; 426 actionable tasks, 224 executed,
118 from cache, and 84 up to date. Both feature lint/check gates, both
Android-test compilations, and the app debug APK completed successfully. The
same custom boundary-task configuration-cache warnings were emitted and the
cache entry was discarded. Complete log:
`C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\logs\target-check-androidtest-assemble.log`.

### Historical APK buildability

- Settings rollback `e12f816f`: `:app:assembleDebug` passed in 3m 31s;
  154 actionable tasks, 100 executed and 54 from cache.
- Live rollback `ea349d83`: `:app:assembleDebug` passed in 6m 57s;
  176 actionable tasks, 92 executed and 84 from cache.

The historical baseline APKs are therefore available for paired emulator
replay. Complete logs are `settings-baseline-assemble-debug.log` and
`live-baseline-assemble-debug.log` under the external audit `logs` directory.

### Target connected Settings suite

The audit emulator was recorded before execution as AOSP TV x86,
1920x1080, density 320, locale `en-US`, font scale 1.0, with all animation
scales at 1.0. No app process or foreground activity was present at capture.

```text
gradlew.bat :feature:settings:connectedDebugAndroidTest --console=plain
```

Result: all 12 tests passed with no failures or skips; `BUILD SUCCESSFUL in
1m 13s`. This covers the feature's connected Settings behavior suite, but it
does not compose the app-owned parental route or prove the full Settings to
Provider Setup and back journey. Complete logs are `emulator-pre-audit-config.txt`
and `target-settings-connected.log` under the external audit `logs` directory.

### Target connected Live suite and golden diagnosis

```text
gradlew.bat :feature:live:connectedDebugAndroidTest --console=plain
```

The suite executed 25 tests. Nineteen behavioral tests passed, including the
new `categorySidebarHeader_titleUsesLightOnSurfaceText` regression test. Six
strict exact-pixel cases in `LivePresentationGoldenTest` failed. Recording the
same six fixtures twice on the unchanged emulator produced byte-identical
candidate PNGs, establishing deterministic output. Comparing candidates to
checked-in expected images produced:

| Golden | Differing pixels | Image percent | Maximum channel delta | Mean absolute RGBA error |
| --- | ---: | ---: | ---: | ---: |
| `live_category_row` | 453 | 0.021846% | 1 | 0.000078366 |
| `live_channel_row_surface` | 6,151 | 0.296634% | 6 | 0.002638889 |
| `live_channel_surfaces` | 95,789 | 4.619454% | 7 | 0.019952980 |
| `live_reorder_top_bar` | 143,727 | 6.931279% | 2 | 0.023128376 |
| `live_selection_chips` | 1,745 | 0.084153% | 1 | 0.000274764 |
| `live_source_switcher` | 1,199 | 0.057822% | 1 | 0.000169753 |

Every pair is 1920x1080. The amplified diffs follow the same shapes and text
with sub-channel color rounding rather than displaced geometry; no material
visual change is observable. The strict suite therefore remains red, but the
evidence does not justify changing production UI or accepting regenerated
baselines. Candidate PNGs, raw/amplified diffs, and
`golden-diff-metrics.json` are under the external audit
`visual/live-target` directory. Complete execution logs are
`target-live-connected.log`, `target-live-golden-record.log`, and
`target-live-golden-record-run2.log`.

### Red/green regression fixes and integrated gate

Each newly changed behavior was first isolated in its own external worktree,
demonstrated red, fixed, run focused green, then run through its full feature
suite before integration:

| Finding | Red evidence | Fix | Green evidence |
| --- | --- | --- | --- |
| `SET-UPD-001` | `red-cached-update.log`: test could not supply a remote-version comparator because production omitted the dependency | `c2286afc` | `green-cached-update-focused.log`; `green-cached-update-settings-suite.log` |
| `SET-NAV-002` | `red-parental-nav.log`: composed route API did not provide navigation destinations | `11f0ee6f` | `green-parental-nav-focused.log`; `green-parental-nav-settings-suite.log` |
| `LIVE-STATE-001` | `red-live-preview-recovery.log`: expected cleared error after `READY`, observed `test-message` | `3f4152d9` | `green-live-preview-recovery-focused.log`; `green-live-preview-recovery-suite.log` |

The integrated target then passed both feature unit suites, both fail-closed
boundary tasks, and `:app:assembleDebug`; see
`logs\integrated-boundary-unit-assemble.log`. Settings connected tests passed
13/13 (`logs\integrated-settings-connected.log`). Live connected tests again
passed all 19 behavioral tests and failed only the same six deterministic
exact-pixel goldens (`logs\integrated-live-connected.log`). `graphify update .`
completed in the external target after these code changes: 1,225 files,
13,302 nodes, 23,516 edges, and 423 communities.

A final 2026-08-31 rerun produced the same outcome: unit/boundary/assembly
`BUILD SUCCESSFUL`, Settings connected 13/13, and Live connected 25 executed
with exactly the same six golden failures and no behavioral-test failures.

## Production-shell visual and behavior comparison

The emulator comparisons used the historical APKs and the integrated audit APK
at 1920x1080, density 320, `en-US`, font scale 1.0, and animation scales 1.0.

- Historical Settings parental controls showed the complete top destination
  bar. The pre-fix target showed only the StreamVault label. The integrated
  target again shows Home, Live TV, Movies, Series, Downloads, Guide, Search,
  Plugins, and Settings. Evidence:
  `visual\manual-settings-baseline\parental-with-topnav.png`,
  `visual\manual-target\parental-empty-topnav.png`, and
  `visual\manual-integrated-target\parental-topnav-restored.png`.
- With the same update release/version available in both comparisons, the
  pre-fix extracted Settings screen displayed the cached newer release but
  reported `Up to date` and omitted the download action. The integrated target
  displays `Download update` and the latest-release action. Evidence:
  `visual\manual-live-baseline\settings-about-cached-update.png` and
  `visual\manual-integrated-target\settings-about-cached-update.png`.
- Historical and integrated Live entry screens retain the same structure,
  placement, colors, and white Categories title. Evidence:
  `visual\manual-live-baseline\live-entry.png` and
  `visual\manual-integrated-target\live-entry.png`.
- The historical Settings provider summary visibly contained mojibake between
  fields; the frozen/integrated target renders proper bullets. This is an
  improvement, not a regression. Evidence:
  `visual\manual-settings-baseline\settings-entry.png` and
  `visual\manual-target\settings-entry.png`.
- The Add Provider completion issue is a supplied, already-fixed control
  finding (`SET-NAV-001`), covered by the focused ProviderGraph navigation test;
  it is not an outstanding production-shell validation task.

## Long-duration Live playback

The integrated target was exercised on two public-fixture HLS channels. Each
capture loop requested a screenshot, then slept two seconds. PNG encoding made
the observed screenshot-start interval longer than two seconds, so both runs
exceeded the required historical stuck window.

| Channel | Screenshots | Observed duration/cadence | Unique hashes | Final media session | Log result |
| --- | ---: | --- | ---: | --- | --- |
| `00s Replay` | 61 | 190.3 s; mean start interval 3.171 s | 59 | `PLAYING`, `error=null` | HLS prepare and first-frame success; two non-consecutive duplicate pairs; no fatal, stuck, MPEG-TS, malformed-source, or fallback marker |
| `3ABN Dare To Dream Network` | 61 | 317.1 s; mean start interval 5.286 s | 61 | `PLAYING`, `error=null` | HLS prepare and first-frame success; all hashes unique; no fatal, stuck, MPEG-TS, malformed-source, or fallback marker |

Both streams encountered transient `BehindLiveWindow` source-window errors.
The player retried as HLS, produced another first frame, and ended healthy. This
is successful recovery evidence, not a claim that the public source emitted no
transient errors. Screenshots, timing, hashes, final media sessions, and logs
are under `playback\channel1-00s-replay` and
`playback\channel2-3abn-dare-to-dream` in the external audit root.

## Static equivalence summary

Rename-aware source/history comparison supports equivalence, except for the
ledger findings, in these areas:

- Settings route patterns/arguments, section order, rail dimensions/colors,
  focus requester, persistence/action ordering, platform adapters, backup and
  recording bridges, and effective localized resources.
- Live/Guide route patterns and sentinels, request mapping, category/channel
  list order and identity, search/filters/hidden/favorites/reorder policies,
  Back/D-pad/shortcut/long-press policy, preview/fullscreen/MultiView callback
  ordering, and Guide grid/action policy.

Static equivalence is supplemented by the runtime evidence above. Platform/SAF
behavior, credentialed provider refresh, recording/reminder/archive side
effects, process restoration, and exact pixels across device configurations
remain outside the evidence obtained by this audit.

## Current limitations

- The primary checkout is intentionally live and may change after the frozen
  target snapshot. Later drift will be reported rather than silently folded
  into the comparison.
- Historical commits may expose tasks or fixtures that differ from the target;
  unavailable historical gates will be recorded exactly.
- Credentialed providers, external accounts, recording storage, archive
  windows, notification permission, and physical hardware remain evidence
  limitations unless suitable fixtures are already available.

## Recommended disposition

1. Review and cherry-pick `c2286afc`, `11f0ee6f`, and `3f4152d9` from
   `audit/phase5-settings-live-integrated` in that order.
2. Keep the six Live goldens open as test-infrastructure work; do not regenerate
   them from this emulator without first identifying the baseline renderer.
3. Decide product intent for `SET-COPY-001` and clock semantics for
   `LIVE-POLICY-001`; neither warrants a speculative audit fix.
4. Fix pre-existing `LIVE-TEXT-002` separately so it is not misclassified as
   Phase 5 extraction fallout.
