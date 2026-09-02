# Phase 5 Catalog - Task 12 device journeys

Date: 2026-09-02

The configured API 36 TV emulator was booted and authorized. Automated
connected coverage exercised the feature-owned selection semantics and the four
golden entry points; app navigation and platform compatibility suites also
passed. Full seeded journeys were **not run** because this checkout has no
seeded provider/catalog dataset or test harness that can drive the production
`MainActivity` through Dashboard, Movies, Series, VOD, Search, Details, and
Favorites flows. No screenshots or logcat evidence are claimed for those
journeys.

Unavailable journey set:

- Dashboard shelves, customization save/cancel, and focus traversal
- Movies/Series/VOD filtering, sorting, load-more, detail return, and episode focus
- Search typing/results activation and Favorites direct-host rendering
- Detail favorite, variant, copy URL, download, and Cast chooser flows
- Phone/tablet, RTL, and reduced-motion variants

These remain the next acceptance gate once a seeded test fixture and production
activity harness are available. The four golden failures are documented in
`task12-connected-validation.md`; the baselines remain preserved for rerun.
