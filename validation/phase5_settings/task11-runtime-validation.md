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

The profile-source check and both release-like assemblies also pass together:

```text
gradlew.bat verifyBaselineProfileSources :app:assembleBeta :app:assembleRelease \
  --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 3m 43s
```

The app build now declares the normalized profile installer as an explicit
dependency of beta/release art-profile and startup-profile merge tasks. This
prevents Gradle's implicit-output validation failure when verification and
assembly are requested in one invocation.

The stale-descriptor scan currently reports 24 lines in the committed profile
sources under the pre-extraction `com/streamvault/app/ui/screens/settings`
package and zero lines under `com/streamvault/feature/settings`. These files
were not edited by hand; fresh profile generation on a seeded target is still
required to replace them safely.

## Runtime gate

```text
adb devices
List of devices attached
```

No emulator or physical device was connected. Consequently, the following
acceptance checks were not run and remain open:

- Settings entry/section navigation, D-pad focus restoration, dialog semantics,
  Back behavior, RTL, accessibility, and reduced-motion behavior.
- Backup/restore picker, local/folder/USB/Drive flows, partial-import recovery,
  and provider backup-preview rendering on a running app.
- Parental controls, recording browser/player handoff, EPG dialogs, provider
  management/sync, diagnostics, update flows, and external callback behavior.

No credentials, accounts, files, or destructive external operations were used.
The feature AndroidTest sources are ready for execution when a suitable TV
emulator/device and test fixtures are available.

## Related open gates

The nine direct `:data` imports and the `AudioCompatibilityMemoryStore`
concrete dependency remain ledgered Phase 7 removal candidates. Existing
playback/provider runtime, performance, and manual acceptance gates remain
open under their respective reports.
