# Catalog extraction pre-measurement

Date: 2026-09-02
Baseline commit: `d303e2aaf090843ff51081687c7d4fa4d7af6a7c`
Toolchain: Java 21.0.5, Gradle 8.12, Windows 11 amd64

## Required sample protocol

The plan requires five warm source-edit samples using a reversible
whitespace-only edit to `app/src/main/java/com/streamvault/app/ui/screens/dashboard/DashboardHomeShelves.kt`
and `:app:compileDebugKotlin --profile`, followed by five warm test-compile
samples using the equivalent edit to
`app/src/test/java/com/streamvault/app/ui/screens/dashboard/DashboardHomeShelvesTest.kt`
and `:app:compileDebugUnitTestKotlin --profile`.

Each sample includes wall-clock duration, Gradle result, profile path, and
whether the touched file is byte identical to `HEAD` after restoration. The
source and test files were restored after every run; their final SHA-256 hashes
were `d8c4ee07e1c68ad220041d65ecb8e3d1fce371480f5cc7c48d404571e169a96a` and
`2a3081041beb851b22037f177c0b78ec5d272fdf4e55bb35e972810b6c7a9d86`.

## Source-edit samples

| Sample | Command | Duration | Result | Profile | Restored |
|---:|---|---:|---|---|---|
| 1 | `:app:compileDebugKotlin --profile` | 62.3s | PASS | `profile-2026-09-02-20-28-54.html` | yes |
| 2 | `:app:compileDebugKotlin --profile` | 15.2s | PASS | `profile-2026-09-02-20-29-50.html` | yes |
| 3 | `:app:compileDebugKotlin --profile` | 15.1s | PASS | `profile-2026-09-02-20-30-05.html` | yes |
| 4 | `:app:compileDebugKotlin --profile` | 13.6s | PASS | `profile-2026-09-02-20-30-20.html` | yes |
| 5 | `:app:compileDebugKotlin --profile` | 14.9s | PASS | `profile-2026-09-02-20-30-34.html` | yes |

## Test-compile samples

| Sample | Command | Duration | Result | Profile | Restored |
|---:|---|---:|---|---|---|
| 1 | `:app:compileDebugUnitTestKotlin --profile` | 47.1s | PASS | `profile-2026-09-02-20-30-55.html` | yes |
| 2 | `:app:compileDebugUnitTestKotlin --profile` | 17.3s | PASS | `profile-2026-09-02-20-31-36.html` | yes |
| 3 | `:app:compileDebugUnitTestKotlin --profile` | 15.1s | PASS | `profile-2026-09-02-20-31-53.html` | yes |
| 4 | `:app:compileDebugUnitTestKotlin --profile` | 15.6s | PASS | `profile-2026-09-02-20-32-09.html` | yes |
| 5 | `:app:compileDebugUnitTestKotlin --profile` | 15.7s | PASS | `profile-2026-09-02-20-32-24.html` | yes |

All ten samples passed and both touched files are restored. These are the
before values for the post-extraction comparison; they do not close the final
performance gate until the after samples are collected in Task 13.
