# Phase 5 Catalog — Task 11 resource and golden ownership

Date: 2026-09-02  
Scope: `:feature:catalog` ownership migration and structural cleanup

## Golden fixtures

The four Catalog route baselines now live in
`feature/catalog/src/androidTest/assets/ui-goldens/`:

| Fixture | Previous path | New path | Content check |
| --- | --- | --- | --- |
| `route_dashboard_default.png` | `app/src/androidTest/assets/ui-goldens/` | `feature/catalog/src/androidTest/assets/ui-goldens/` | Re-recorded feature fixture; reviewed SHA-256 `BB9A46C4F9DB40769DD5D3AF620931DAF278A9D09FEA3AFCDC5DB2D09BBFC4A8` |
| `route_movies_landing.png` | app | feature | Re-recorded feature fixture; reviewed SHA-256 `B43061A9E119619CC510587BF942E6C8E06F33FAA696EC10D3C3C004DA641A02` |
| `route_series_detail.png` | app | feature | Re-recorded feature fixture; reviewed SHA-256 `D21266C13E1F141CD8950DCA38BDFE273904BE761400D2062FE4E72BB6C247E4` |
| `route_search_results.png` | app | feature | Re-recorded feature fixture; reviewed SHA-256 `36AA09E63BB632F44DA4C31D662D14D1D9ED66E2811CFB601E7DCC3DB2B7E001` |

`CatalogPresentationGoldenTest` owns the same four test names and baseline
names. The original app-shell assets were not semantically equivalent to the
feature-only fixture, and their padded test node captured 1824×984 instead of
the full 1920×1080 target. The feature test now captures an unpadded
1920×1080 Canvas surface and its reviewed assets are checked in above.
`PremiumRouteGoldenTest` retains only the live and saved/guide/settings app-shell
fixtures.

## Resource audit

No app locale resource was deleted in this task. The feature resource set is
intentionally duplicated while app-shell and compatibility surfaces continue to
resolve shared strings. The audit was run with:

```powershell
powershell -ExecutionPolicy Bypass -File validation/phase5_catalog/locale_audit.ps1
```

Result: exit code `0`; 351 expected keys; missing `0`; value mismatches `0`;
format mismatches `0`; unexpected keys `0`; 525 source-locale fallbacks are
allowed and reported for transparency. Android resource processing also passed:

```text
:feature:catalog:processDebugResources  PASS
:app:processDebugResources             PASS
```

## Test helper ownership

`GoldenCapture.kt` moved to
`feature/catalog/src/androidTest/java/com/streamvault/feature/catalog/test/`
and now reports the feature asset path. The obsolete app
`ChannelProgressTickerTest` was removed because its behavior is covered by the
feature-owned primitive test; the app shell card keeps a private equivalent
progress calculation for its retained shell golden fixture.
