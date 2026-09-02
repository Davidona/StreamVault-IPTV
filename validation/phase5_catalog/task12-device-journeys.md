# Phase 5 Catalog - Task 12 device journeys

Date: 2026-09-03

The configured API 36 TV emulator was booted and authorized. Automated
connected coverage exercised the feature-owned selection semantics and the four
golden entry points; app navigation and platform compatibility suites also
passed. A debug production APK was then installed with the configured public
M3U fixture. Provider sync returned HTTP 200 and staged 1,467 live channels.

The available seeded production surfaces were checked on-device:

- Home loaded on `streamvault.destination:home` (the update notice was visible).
- Live TV loaded on `streamvault.destination:live_tv` and exposed the seeded
  channel/category surface.
- Movies and Series loaded on their routes and correctly reported `Sync needed`
  because the M3U fixture has no VOD payload.
- Search accepted the query `CNN` and rendered the no-match state.

The on-device logcat spot scan showed the provider-sync HTTP 200/staging path
and no app fatal exception; unrelated platform Cast/StrictMode noise was not
treated as Catalog evidence.

Full Catalog journeys were **not run** because this fixture has live channels
only and this checkout has no VOD/movie/series seed or production-activity
journey harness. The route captures used for this pass are ignored local files;
no production screenshots or raw logcat are committed.

Unavailable journey set:

- Dashboard shelves, customization save/cancel, and focus traversal
- Movies/Series/VOD filtering, sorting, load-more, detail return, and episode focus
- Search typing/results activation and Favorites direct-host rendering
- Detail favorite, variant, copy URL, download, and Cast chooser flows
- Phone/tablet, RTL, and reduced-motion variants

These remain the next acceptance gate once a seeded test fixture and production
activity harness are available. The connected golden follow-up is documented in
`task12-connected-validation.md`; the feature-owned baselines remain preserved
for rerun.
