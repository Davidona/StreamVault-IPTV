# Catalog profile validation

Date: 2026-09-03

The generated profile workflow was rerun on 2026-09-03 with
`:app:generateBaselineProfile` on `emulator-5554` (`Television_1080p(AVD)`,
API 36). It passed in 19m 04s; the connected profile suite finished 18 tests,
with the eight configured macrobenchmark cases skipped by configuration.

The refreshed profile sources now contain feature Catalog descriptors and no
legacy app Catalog descriptors:

| Source | Legacy app Catalog matches | Feature Catalog matches |
|---|---:|---:|
| `app/src/main/generated/baselineProfiles/baseline-prof.txt` | 0 | 885 |
| `app/src/main/generated/baselineProfiles/startup-prof.txt` | 0 | 763 |

The generated files were not hand-edited. The profile run exercised seeded
Home/Live journeys; the public M3U fixture does not provide VOD/movie/series
content, so it is profile evidence rather than full Catalog journey coverage.

The current packaging guardrail (`:app:assembleBeta` and
`:app:assembleRelease`) passed after generation. The configured profile suite
still skips its eight ordinary macrobenchmark cases. Separately, the focused
Dashboard `dashboardVerticalScroll` diagnostic rerun passed after seeded
recent-channel data made Home scrollable; it does not close the required
cache-equivalent paired performance comparison.
