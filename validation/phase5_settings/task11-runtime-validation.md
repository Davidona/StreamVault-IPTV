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

- Settings entry/section navigation, D-pad focus restoration, dialog semantics,
  Back behavior, RTL, accessibility, and reduced-motion behavior.
- Backup/restore picker, local/folder/USB/Drive flows, partial-import recovery,
  and provider backup-preview rendering on a running app.
- Parental controls, recording browser/player handoff, EPG dialogs, provider
  management/sync, diagnostics, update flows, and external callback behavior.

No credentials, accounts, files, or destructive external operations were used.
Manual flows still require suitable fixtures (and, where applicable, accounts
or local files).

## Related open gates

The nine direct `:data` imports and the `AudioCompatibilityMemoryStore`
concrete dependency remain ledgered Phase 7 removal candidates. Existing
playback/provider runtime, performance, and manual acceptance gates remain
open under their respective reports.
