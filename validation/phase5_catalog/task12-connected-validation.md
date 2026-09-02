# Phase 5 Catalog - Task 12 connected validation

Date: 2026-09-03
Target: `emulator-5554` (`Television_1080p(AVD) - 16`), API 36, AOSP TV on x86,
physical 1920x1080 at 320 dpi, animation scales 1.0.

## Catalog instrumentation

Command:

```text
./gradlew.bat :feature:catalog:connectedDebugAndroidTest
```

Initial result: **4/5 passed, 1/5 suite failed**.

Follow-up result after correcting the feature golden harness and re-recording
the reviewed feature-only baselines: **5/5 passed** on the same target.

- `CatalogPresentationBehaviorTest.selectionChipRow_exposesSelection_andDispatchesClickOnce` passed.
- The four Catalog golden methods now pass pixel comparison against the
  feature-owned 1920x1080 baselines. The prior 1824x984 failure was caused by a
  padded test node and old app-shell assets; the harness now captures an
  unpadded Canvas surface and the new assets were reviewed before rerun.

The replacement PNG assets are recorded with SHA-256 values in
`task11-resource-cleanup.md`. The old app-shell blobs were not reused because
they did not represent the feature-only fixture.

## App compatibility instrumentation

```text
:app:connectedDebugAndroidTest -P...class=com.streamvault.app.ui.AppNavigationContractTest
  3/3 passed
:app:connectedDebugAndroidTest -P...class=com.streamvault.app.compat.PlatformCompatibilityMatrixTest
  4/4 passed
```

No Catalog-caused fatal exception was reported by these runs. The feature test
APK was uninstalled by the connected-test teardown; a post-run package query
therefore returned no installed `streamvault` package.
