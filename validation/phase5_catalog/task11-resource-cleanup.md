# Phase 5 Catalog — Task 11 resource and golden ownership

Date: 2026-09-02  
Scope: `:feature:catalog` ownership migration and structural cleanup

## Golden fixtures

The four Catalog route baselines now live in
`feature/catalog/src/androidTest/assets/ui-goldens/`:

| Fixture | Previous path | New path | Content check |
| --- | --- | --- | --- |
| `route_dashboard_default.png` | `app/src/androidTest/assets/ui-goldens/` | `feature/catalog/src/androidTest/assets/ui-goldens/` | Git blob unchanged (`89a9ab988988ddd75a1806c9f35f094ed5701df5`) |
| `route_movies_landing.png` | app | feature | Git blob unchanged (`0765a4041b16abf63e66f1975a120234b27ff0d2`) |
| `route_series_detail.png` | app | feature | Git blob unchanged (`0e15b61d39b6d6cfb2e5794dcc234886278ce1c0`) |
| `route_search_results.png` | app | feature | Git blob unchanged (`b10b54fd369396b897171e8d2787f6fadb5bccee`) |

`CatalogPresentationGoldenTest` owns the same four test names and baseline
names. `PremiumRouteGoldenTest` retains only the live and saved/guide/settings
app-shell fixtures.

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
