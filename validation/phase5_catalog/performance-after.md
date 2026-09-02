# Catalog extraction post-measurement

Date: 2026-09-02
Post-extraction reference: `5f1e615e` plus the app navigation-boundary fix in
the final Catalog documentation commit
Toolchain: Java 21.0.5, Gradle 8.12, Windows 11 amd64, `--no-daemon`

## Feature source-edit samples

Each run used a reversible comment-only edit in
`feature/catalog/src/main/java/com/streamvault/feature/catalog/presentation/dashboard/DashboardHomeShelves.kt`.
The file was restored and its normalized Git hash matched `HEAD` afterward.

| Sample | Command | Duration | Result | Profile |
|---:|---|---:|---|---|
| 1 | `:feature:catalog:compileDebugKotlin --profile` | 109.4s | PASS | `profile-2026-09-02-23-39-00.html` |
| 2 | same | 8.3s | PASS | `profile-2026-09-02-23-40-45.html` |
| 3 | same | 8.5s | PASS | `profile-2026-09-02-23-40-54.html` |
| 4 | same | 8.2s | PASS | `profile-2026-09-02-23-41-02.html` |
| 5 | same | 8.3s | PASS | `profile-2026-09-02-23-41-10.html` |

The first run includes post-clean/configuration overhead. Warm median: 8.3s;
warm range: 8.2–8.5s.

## Feature test-compile samples

Each run used a reversible comment-only edit in
`feature/catalog/src/test/java/com/streamvault/feature/catalog/presentation/dashboard/DashboardHomeShelvesTest.kt`.

| Sample | Command | Duration | Result | Profile |
|---:|---|---:|---|---|
| 1 | `:feature:catalog:compileDebugUnitTestKotlin --profile` | 30.8s | PASS | `profile-2026-09-02-23-41-37.html` |
| 2 | same | 8.9s | PASS | `profile-2026-09-02-23-42-05.html` |
| 3 | same | 8.7s | PASS | `profile-2026-09-02-23-42-14.html` |
| 4 | same | 8.7s | PASS | `profile-2026-09-02-23-42-22.html` |
| 5 | same | 9.0s | PASS | `profile-2026-09-02-23-42-31.html` |

Warm median: 8.7s; warm range: 8.7–9.0s. Both temporary edits were removed
before the final validation commit.

## Isolation and packaging

An actual Catalog-only edit followed by `:app:assembleDebug` executed
`:feature:catalog:compileDebugKotlin`; sibling feature Kotlin tasks were
reported `UP-TO-DATE` and did not execute. The unfiltered Gradle dry-run lists
all dependency tasks by design, so the actual execution result is the
authoritative isolation check.

Additional guardrails:

- `clean :app:assembleDebug`: PASS, 80.7s.
- warm `:app:assembleDebug`: PASS, 14.9s.
- `:app:assembleBeta :app:assembleRelease`: PASS, 332.3s.

These are current-checkout samples, not a cache-equivalent five-run clean-build
comparison. The formal macrobenchmark and paired performance gates remain open.
