# Phase 5 Settings Runtime Validation

Date: 2026-08-28

## Automated connected-test evidence

The feature connected-test source set compiles successfully as part of the
focused verification bundle:

```text
gradlew.bat :feature:settings:compileDebugAndroidTestKotlin --no-daemon \
  --console=plain --warning-mode=none
BUILD SUCCESSFUL
```

The same fresh bundle also passed the feature boundary, feature unit tests,
feature lint, app Kotlin compilation, and app unit tests:

```text
gradlew.bat :feature:settings:verifyFeatureSettingsBoundary \
  :feature:settings:testDebugUnitTest \
  :feature:settings:lintDebug \
  :feature:settings:compileDebugAndroidTestKotlin \
  :app:compileDebugKotlin \
  :app:testDebugUnitTest \
  --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 25s
```

The complete feature unit suite was then rerun without task reuse:

```text
gradlew.bat :feature:settings:testDebugUnitTest --rerun-tasks \
  --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 3m 49s
34 tests, 0 failures, 0 errors
```

The app-owned composition-root seams were also rerun explicitly:

```text
gradlew.bat :app:testDebugUnitTest \
  --tests com.streamvault.app.settings.AppSettingsAdaptersTest \
  --tests com.streamvault.app.backup.BackupFileBridgeTest \
  --tests com.streamvault.app.ui.screens.settings.SettingsAppUpdateModelsTest \
  --rerun-tasks --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 5m 43s
16 tests, 0 failures, 0 errors
```

These tests cover the remaining app adapters and presentation-model seam; they
do not replace the manual TV journeys listed below.

The focused verification was rerun after the workstation Gradle path change.
The first invocation used the default C: Gradle user home and failed before
any Settings task executed:

```text
Could not determine the dependencies of task ':core:navigation:compileKotlin'.
Service 'SystemInfo' is not available (os=Windows 11 10.0 amd64, enabled=false).
```

The same command was rerun with `GRADLE_USER_HOME=E:\compose-settings-gradle-home`
and `E:\androidSdk`; boundary verification, 34 Settings unit tests, lint,
Android-test compilation, app Kotlin compilation, and app unit tests all passed
(`BUILD SUCCESSFUL in 4m 51s`, 248 actionable tasks, 5 executed, 243
up-to-date). The minimal `:core:navigation:compileKotlin` reproduction also
passed with the E: Gradle home, so the C: Gradle environment failure was not a
project or Settings assertion failure.

The profile-source check and both release-like assemblies pass together:

```text
gradlew.bat verifyBaselineProfileSources :app:assembleBeta :app:assembleRelease \
  --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 6m 17s
```

The app build now declares the normalized profile installer as an explicit
dependency of beta/release art-profile and startup-profile merge tasks. This
prevents Gradle's implicit-output validation failure when verification and
assembly are requested in one invocation.

The seeded-target profile producer was then run on the same emulator:

```text
gradlew.bat :app:generateBaselineProfile --no-daemon \
  --console=plain --warning-mode=none
BUILD SUCCESSFUL in 12m 37s
10 profile tests passed; 8 ordinary macrobenchmarks skipped by selector
```

The producer merged and installed `baseline-prof.txt` (46,249 rules) and
`startup-prof.txt` (30,093 rules). The generated source scan now reports zero
pre-extraction `com/streamvault/app/ui/screens/settings` descriptors and
feature/settings descriptors are present. Detailed evidence is recorded in
`validation/phase5_settings/profile-validation.md`.

## Runtime gate

The Android SDK supplied for this validation is `E:\androidSdk`, with the
following TV emulator attached:

```text
E:\androidSdk\platform-tools\adb.exe devices -l
emulator-5554 device product:sdk_google_atv_x86 model:AOSP_TV_on_x86
```

The focused settings connected suite now passes on that device:

```text
gradlew.bat :feature:settings:connectedDebugAndroidTest \
  --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 1m 11s
2 tests, 0 failures, 0 errors, 0 skipped
```

Result XML: `feature/settings/build/outputs/androidTest-results/connected/debug/TEST-Television_1080p(AVD) - 16-_feature_settings-.xml`.
The passing cases cover the feature-owned Settings entry point and the
provider backup-preview title contract. This is focused evidence, not a full
manual acceptance of every Settings journey.

The neighboring provider/app connected checks were also run:

```text
gradlew.bat :feature:provider:connectedDebugAndroidTest \
  :app:connectedDebugAndroidTest --no-daemon --console=plain --warning-mode=none
```

Provider passed (`1/1`). The app suite executed 26 tests with 5 existing
failures (21 passed, 0 skipped):

- `DownloadForegroundServiceQuotaInstrumentationTest.reducedDataSyncTimeoutReleasesDownloadLease`
- `LauncherProviderInstrumentationTest.watchNextProgram_supportsInsertUpdateDeleteRoundTrip`
- `PlayerSmokeTest.playerControlsOverlay_playButton_canReceiveFocus`
- `PlayerSmokeTest.categoryRailPanel_searchField_acceptsInitialFocusAndInput`
- `PlayerSmokeTest.playerTrackSelectionDialog_selectsAudioTrack`

These failures are outside the settings extraction and remain open under the
neighboring feature/app acceptance work. The settings-specific connected tests
do not exercise the full app shell or all runtime journeys. The following
acceptance checks therefore remain open:

- Manual Settings entry/section navigation, D-pad focus restoration, dialog
  semantics, Back behavior, RTL, accessibility, and reduced-motion behavior in
  the production app shell.
- Backup/restore picker, local/folder/USB/Drive flows, partial-import recovery,
  and provider backup-preview rendering on a running app.
- Parental controls, recording browser/player handoff, EPG dialogs, provider
  management/sync, diagnostics, update flows, and external callback behavior.

No credentials, accounts, files, or destructive external operations were used.
Manual flows still require suitable fixtures (and, where applicable, accounts
or local files).

## Expanded behavior-focused connected evidence (2026-08-28)

The Settings connected suite was expanded and run on the same API 36 TV
emulator using `E:\androidSdk`:

```text
gradlew.bat :feature:settings:connectedDebugAndroidTest \
  --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 1m 43s
12 tests, 0 failures, 0 errors, 0 skipped
```

The final XML reports `tests=12`, `failures=0`, `errors=0`, `skipped=0`, and
`time=62.167` seconds:

`feature/settings/build/outputs/androidTest-results/connected/debug/TEST-Television_1080p(AVD) - 16-_feature_settings-.xml`

The ten new tests cover section navigation, D-pad focus restoration, RTL
navigation, SearchInput accessibility semantics, backup preview strategy and
toggle callbacks, backup selection and Back dismissal, parental PIN state,
four-digit PIN entry, encoded backup URI compatibility, and the long parental
provider-id route argument. The two existing feature tests also passed. The
dialog host now registers its guarded Back callback with the host activity
dispatcher so platform Back dismisses Settings dialogs without finishing the
activity.

Exact failures in the accepted run: none.

The connected suite was rerun after the first attempt encountered an emulator
service race. At the time of the failed attempt, ADB could not find
`emulator-5554`, and installation reported `cmd: Can't find service: package`.
Once the existing emulator reported `sys.boot_completed=1` and its package
service was available, the same command passed without restarting, clearing,
or uninstalling the app:

```text
BUILD SUCCESSFUL in 58s
12 tests, 0 failures, 0 errors, 0 skipped
```

The refreshed XML retains the same path and reports `tests=12`,
`failures=0`, `errors=0`, `skipped=0`, `time=23.957`, timestamp
`2026-08-28T17:45:10`.

Screenshots were written to the emulator's shared test-media directory and
pulled before instrumentation teardown:

- `validation/phase5_settings/task11_screenshots/settings_navigation_dpad.png`
  (1920x1080, SHA-256 `e83cb65d1b0c250a447f0429da1388ef96986757e8849e6f8159a6b2454ab829`)
- `validation/phase5_settings/task11_screenshots/settings_navigation_rtl.png`
  (1920x1080, SHA-256 `5e69995c6d081740be1539046274f8d276c1e668a458da948cb372a408ecf791`)
- `validation/phase5_settings/task11_screenshots/settings_backup_preview.png`
  (431x49 title-node capture, SHA-256 `088b46f397f408d164768171f19420676ae998313a891375f5b32fe32f345ddb`)

## Production-shell manual journey evidence (2026-08-28)

The debug app was installed with `:app:installDebug` and launched directly as
`com.streamvault.app.debug/com.streamvault.app.MainActivity` on
`emulator-5554`. Existing app data was preserved; no uninstall or data clear
was used. Valid screenshots from the journey are under
`validation/phase5_settings/manual_journeys/`.

Safe journeys completed:

- Entered Settings from the top navigation and visited Providers, Playback,
  Privacy, Recording, Backup & Restore, EPG Sources, and About. The D-pad rail
  focus and Settings content rendered in the production app shell.
- Opened the Privacy protection-level dialog and dismissed it with platform
  Back. The host remained
  `com.streamvault.app.debug/com.streamvault.app.MainActivity`, confirming the
  guarded dialog Back callback did not finish the activity.
- Opened Enter New PIN without entering or changing a PIN. The existing PIN
  state was preserved.
- Opened Manage local backups; the UI correctly reported no
  StreamVault-created local backups. Import Data was also opened safely and
  reported: `No local StreamVault backups found. Export one first or open a
  backup file with StreamVault.` No restore or destructive operation was run.
- Opened EPG Sources; the UI reported no external EPG sources configured.
- Opened the About update screen; before the fix, the first Check now action
  reproduced the crash below. After reinstalling the patched build without
  clearing data, the same D-pad journey and Check now action completed, the
  activity stayed foregrounded, `Update status` remained `Up to date`, and
  `Last checked` advanced to `Aug 28, 2026 2:29 PM`.

Relevant manual screenshots include:

- `validation/phase5_settings/manual_journeys/settings-entry-2.png`
- `validation/phase5_settings/manual_journeys/settings-playback.png`
- `validation/phase5_settings/manual_journeys/settings-privacy.png`
- `validation/phase5_settings/manual_journeys/settings-pin-open.png`
- `validation/phase5_settings/manual_journeys/settings-pin-dialog-2.png`
- `validation/phase5_settings/manual_journeys/settings-backup-screen.png`
- `validation/phase5_settings/manual_journeys/backup-import-picker.png`
- `validation/phase5_settings/manual_journeys/backup-import-flow.png`
- `validation/phase5_settings/manual_journeys/settings-epg-2.png`
- `validation/phase5_settings/manual_journeys/update-replay-about.png`
- `validation/phase5_settings/manual_journeys/update-check-post-fix.png`

The first update attempt failed at `08-28 14:15:31.963` with this exact
exception:

```text
FATAL EXCEPTION: main
Process: com.streamvault.app.debug
java.lang.StackOverflowError: stack size 8188KB
    at com.streamvault.app.settings.AppSettingsUpdateAdapter.isRemoteVersionNewer(AppSettingsUpdateAdapter.kt:54)
    (same frame repeated recursively)
    at com.streamvault.feature.settings.presentation.SettingsViewModel$checkForAppUpdates$1.invoke(SettingsViewModel.kt:695)
    at com.streamvault.feature.settings.presentation.SettingsAppUpdateActions$checkForAppUpdates$1.invokeSuspend(SettingsAppUpdateActions.kt:72)
```

The app-owned adapter had an unqualified call from its override back to itself
instead of to the top-level current-build policy. The fix aliases the policy
import in `app/src/main/java/com/streamvault/app/settings/AppSettingsUpdateAdapter.kt`;
`app/src/test/java/com/streamvault/app/settings/AppSettingsUpdateAdapterTest.kt`
now guards the delegation. The regression test was first run red with the
same `StackOverflowError`, then green after the alias fix. The post-fix replay
above is the connected/manual confirmation. The About screen still shows the
saved pre-fix `StackOverflowError` report because app data was intentionally
preserved; no new crash was generated by the replay.

The seeded public provider `iptv-org US validation` was also exercised without
credentials. Its sync reached the network and staged 1,467 Live channels:

```text
GET https://iptv-org.github.io/iptv/countries/us.m3u
HTTP 200 ... 327094-byte body
SyncCatalogStore: channel-staging-batch(1000) took=406ms
SyncCatalogStore: channel-staging-batch(467) took=205ms
TvInputChannelSync: TV input channels operations=1467 took=969ms
```

The final provider UI reported `Last sync status: ERROR` for two exact fixture
limitations, not a Settings extraction crash:

```text
SyncManager: Section retry failed for provider 1 [MOVIES]: Playlist contains no movie entries
SyncManager: Section retry failed for provider 1 [EPG]: No EPG URL configured for this provider
```

Provider sync screenshots are
`provider-sync-start.png`, `provider-sync-running.png`, and
`provider-sync-finished.png`. A focus attempt labeled Provider Diagnostics
opened the Add a provider screen instead; no credentials were entered and Back
returned to the provider screen. Recording storage was inspected safely, but
no folder picker, recording, or player handoff was started.

The remaining safe TV pass then re-entered Settings from a direct launch and
covered Providers, parental controls, and Backup & Restore with the D-pad.
Parental controls displayed the existing `Unlocked` category state without a
mutation; platform Back returned to Providers with the Settings rail focus
restored. Export Data stayed within the TV-safe picker-free path and surfaced
the exact message:

```text
Backup saved locally. On supported TVs it is in Downloads/StreamVault; open it with a file manager.
```

Manage local backups subsequently listed these generated entries. Delete and
Restore were not selected:

```text
streamvault_backup_20260828_150353_675.json
streamvault_backup_20260828_150322_143.json
```

Captured artifacts (all 1920x1080 PNGs) are:

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `manual_journeys/remaining-launch.png` | 299723 | `479a0fb4e3429f21d7c2144c160cada4bc56811f1c50ba71eb3961c6c4e14528` |
| `manual_journeys/remaining-settings-screen.png` | 326047 | `fc135af39f297df26374cbbe70aef2b5e37db557a8808bbbb63c61d07fd79516` |
| `manual_journeys/remaining-parental-screen.png` | 192597 | `527d0e3b46a682738f30aa4cc319d10ec59ee7b0a3447d8380b2428b6df3593d` |
| `manual_journeys/remaining-settings-return.png` | 305551 | `1fec5e06bd8c99a335aeca6be233f4f43970754eea40dfec30869ae3d9c51a94` |
| `manual_journeys/remaining-backup-screen.png` | 449887 | `6d667520961eeb8a345e26267c1ff37f8b17e534da1fab8f6c113de8b858f46b` |
| `manual_journeys/remaining-export-result.png` | 431066 | `d86ae25d0ab1fdcc164ec6b348b897c7d6d69e8e35a94b8912674feaad6aa8df` |
| `manual_journeys/remaining-local-backups.png` | 213751 | `712b501e262fba2ad668e8e60c103f302d5a04ae3def75f95f1d62dedca9d9bc` |

The foreground activity remained
`com.streamvault.app.debug/com.streamvault.app.MainActivity`. No picker
location, external account, credential, USB/Drive flow, import, restore, or
delete action was used. Fixture-dependent restore, diagnostics action routing,
recording/player handoff, and full-shell accessibility/reduced-motion gates
remain open.

## Incremental build performance evidence

Five source-edit pairs, five moved-test compile pairs, and clean/warm guardrails
were run in detached snapshots at `e12f816f` (pre-extraction) and `cf468d3e`
(post-extraction). Both used `E:\androidSdk`, the repository Gradle wrapper,
the same dedicated E: Gradle/temp paths, and the same profile/console flags.
The complete raw table and profile paths are in
`validation/phase5_settings/performance-samples-2026-08-28.md`.

Source edit (`:app:compileDebugKotlin` versus
`:feature:settings:compileDebugKotlin`) succeeded in all ten runs. Medians were
`16926 ms` pre and `8858 ms` post (47.7% faster); averages were `19032 ms` pre
and `19432 ms` post because the first post run populated feature/KSP state. The
four post-setup samples averaged `9286 ms`, versus `15053 ms` for the matching
pre samples (38.3% faster). The five-sample p95 values were `34946 ms` pre and
`60017 ms` post.

Moved-test compilation (`:app:compileDebugUnitTestKotlin` versus
`:feature:settings:compileDebugUnitTestKotlin`) also succeeded in all ten runs.
The averages were `22852 ms` pre and `14915 ms` post (34.7% faster), but the
medians were `11324 ms` and `9889 ms` (12.7% faster), with p95 values of
`60069 ms` and `35141 ms`. The mixed first-run setup state means the formal
20% steady-state test target is not claimed as passed.

The clean guardrail succeeded in both snapshots: pre `BUILD SUCCESSFUL in
9m27s` with 154 actionable tasks (123 executed, 31 cached), and post `BUILD
SUCCESSFUL in 4m55s` with 176 actionable tasks (119 executed, 57 cached).
Warm no-change guardrails also succeeded: pre `10s`, post `12s`, with one
executed task and all other actionable tasks up to date. The Phase 0 clean
reference was `2m15.47s` from a different cache/storage state, so it is not a
comparable clean baseline; the post clean value is above it. The post warm
value is below the Phase 0 warm reference of `36.264s`. The performance gate
therefore remains open pending a comparable Phase 0-relative clean baseline and
a steadier test-compile comparison.

## Related open gates

The nine direct `:data` imports and the `AudioCompatibilityMemoryStore`
concrete dependency remain ledgered Phase 7 removal candidates. Existing
playback/provider runtime, performance, and manual acceptance gates remain
open under their respective reports.
