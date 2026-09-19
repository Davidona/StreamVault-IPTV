# Phase 5 Settings Task 8 — Root Screen Extraction Evidence

Date: 2026-08-28  
Commit: `075452c6` (`refactor: move settings root presentation to feature`)

## Ownership transfer

- Moved `SettingsScreen.kt` into
  `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/`.
- Moved the recording-browser orchestration entry point beside the feature
  renderer. Playback is expressed as `SettingsRecordingPlaybackRequest` and
  delegated through `SettingsPlatformHost`.
- Replaced app-shell presentation with `CoreAppScreenScaffold` and app-supplied
  `List<UiDestination>` values. The app still supplies `onNavigate`, provider
  navigation callbacks, and the close-app callback.
- Replaced direct `BackupFileBridge`, `CrashReportStore`, build-verifier,
  removable-storage, and recording-player calls with the existing app adapter
  ports. The provider backup-preview request remains unchanged.
- Copied the root screen's previously app-only default strings into the feature
  default catalog. Locale ownership remains an open follow-up.

## Structural evidence

```text
rg -n "com\.streamvault\.app|NavHostController|NavController|MainActivity|com\.streamvault\.feature\.(provider|playback)" feature/settings/src/main -g '*.kt' -g '*.java'
(no matches)

rg -n "com\.streamvault\.app\.ui\.screens\.settings" app/src/main feature core domain -g '*.kt' -g '*.java'
(no matches)
```

The app settings production directory is now empty; only the feature owns the
settings presentation sources.

## Verification

```text
gradlew.bat :feature:settings:verifyFeatureSettingsBoundary :feature:settings:testDebugUnitTest :feature:settings:lintDebug :feature:settings:compileDebugAndroidTestKotlin :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 58s
```

The first connected-test compile was intentionally run before the move and
failed on the missing `SettingsScreen` symbol. The existing preview test used
an unavailable `assertExists` extension in this module; it now uses the
equivalent `assertIsDisplayed` assertion so the feature Android-test source set
compiles.

`graphify update .` completed after the code move. The refreshed report records
15,302 nodes, 29,862 edges, and 375 communities.
