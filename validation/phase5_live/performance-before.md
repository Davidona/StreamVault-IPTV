# Phase 5 Live pre-extraction performance samples

Date: 2026-08-28

The required pre-extraction comparison uses the current app-owned Home/EPG
source and test compilation tasks. The focused behavioral baseline completed
successfully in `validation/phase5_live/task0-inventory.md`.

The five paired incremental samples are being collected before the first Live
module source move. Each sample records the Gradle profile path, wall time,
executed/up-to-date/from-cache task counts, and whether another Java/Gradle
process was active. A sample is comparable only when the same SDK, Gradle,
storage paths, cache state, and source edit are used after extraction.

## Source scenario

Command:

```text
./gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain --warning-mode=none --profile
```

| Run | Wall time | Profile | Notes |
|---:|---:|---|---|
| 1 | 89.260 s | `profile-2026-08-28-18-08-05.html` | no-change warm sample; first daemon/configuration cost |
| 2 | 12.104 s | `profile-2026-08-28-18-08-45.html` | no-change sample |
| 3 | 14.100 s | `profile-2026-08-28-18-08-58.html` | no-change sample |
| 4 | 11.960 s | `profile-2026-08-28-18-09-09.html` | no-change sample |
| 5 | 10.968 s | `profile-2026-08-28-18-09-19.html` | no-change sample |

## Test compilation scenario

Command:

```text
./gradlew.bat :app:compileDebugUnitTestKotlin --no-daemon --console=plain --warning-mode=none --profile
```

| Run | Wall time | Profile | Notes |
|---:|---:|---|---|
| 1 | 36.420 s | `profile-2026-08-28-18-11-30.html` | no-change warm sample; first daemon/configuration cost |
| 2 | 13.267 s | `profile-2026-08-28-18-11-53.html` | no-change sample |
| 3 | 11.850 s | `profile-2026-08-28-18-12-06.html` | no-change sample |
| 4 | 10.980 s | `profile-2026-08-28-18-12-20.html` | no-change sample |
| 5 | 11.244 s | `profile-2026-08-28-18-12-33.html` | no-change sample |

## Interpretation

These timings are real no-change baseline samples from the current checkout;
they establish the warm task-cost envelope but do not simulate an edited source
file. No performance target is claimed until post-extraction commands are run
from a comparable committed snapshot with the same environment.
