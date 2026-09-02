# Phase 5 Catalog - Task 12 connected validation

Date: 2026-09-02  
Target: `emulator-5554` (`Television_1080p(AVD) - 16`), API 36, AOSP TV on x86,
physical 1920x1080 at 320 dpi, animation scales 1.0.

## Catalog instrumentation

Command:

```text
./gradlew.bat :feature:catalog:connectedDebugAndroidTest
```

Result: **4/5 passed, 1/5 suite failed**.

- `CatalogPresentationBehaviorTest.selectionChipRow_exposesSelection_andDispatchesClickOnce` passed.
- The four Catalog golden methods reached the assertion but failed before pixel
  comparison because the expected baseline is 1920 pixels wide and the captured
  Compose window is 1824 pixels wide on this target.

Failing methods: `dashboard_route_matchesGolden`, `movies_route_matchesGolden`,
`series_detail_route_matchesGolden`, and `search_route_matchesGolden`.
The reviewed PNG assets were moved byte-for-byte (the Git blob IDs are recorded
in `task11-resource-cleanup.md`); they were not regenerated to conceal the
window/inset mismatch. Re-run on the baseline device/window configuration or
record a separately reviewed baseline set before treating visual validation as
passing.

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
