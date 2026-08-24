# Phase 5 Playback Extraction Build Baseline

Captured 2026-08-24 on branch `feature/improveCompose`, before playback
extraction. These samples use the repository Gradle wrapper, Gradle profiling,
plain console output, and a temporary comment-only source edit that was
restored after each sample set.

## Source, import, and resource inventory

The inventory covers both source roots requested by the Phase 5 brief:

- `app/src/main/java/com/streamvault/app/ui/screens/player/`
- `app/src/main/java/com/streamvault/app/ui/screens/multiview/`

Counts at capture time:

| Evidence | Count | File |
|---|---:|---|
| Kotlin source files | 79 | [source-files.txt](source-files.txt) |
| direct project imports | 477 | [project-imports.txt](project-imports.txt) |
| Android resource references | 335 | [resources.txt](resources.txt) |

`app/src/main/java/com/streamvault/app/navigation/graph/PlayerGraph.kt` was
also inspected. It registers both the player and multiview destinations and
passes the validated player navigation request into `PlayerScreen`.

The transitional dependency ledger is recorded in
[`docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md`](../../../docs/COMPOSE_REDUCTION_PHASE5_TRANSITIONAL_DEPENDENCIES.md).

## Build command

```powershell
.\gradlew.bat :app:compileDebugKotlin --profile --console=plain
.\gradlew.bat :app:compileDebugUnitTestKotlin --profile --console=plain
```

Every sample exited with code 0 and reported `BUILD SUCCESSFUL`.

## Player UI compile samples

Temporary edit: comment-only marker in
`PlayerOverlayItemKeys.kt`; removed after sample 5.

| Sample | Elapsed | Gradle result | Executed task summary | Profile report |
|---:|---:|---|---|---|
| 1 | 28.66s | 63 actionable; 3 executed; 60 up-to-date | `:app:kspDebugKotlin`, `:app:compileDebugKotlin`, plus `:player:verifyLocalFfmpegArtifact` | `profile-2026-08-24-17-42-55.html` |
| 2 | 2.38s | 63 actionable; 1 executed; 62 up-to-date | `:app:compileDebugKotlin` | `profile-2026-08-24-17-43-23.html` |
| 3 | 2.35s | 63 actionable; 1 executed; 62 up-to-date | `:app:compileDebugKotlin` | `profile-2026-08-24-17-43-25.html` |
| 4 | 2.51s | 63 actionable; 1 executed; 62 up-to-date | `:app:compileDebugKotlin` | `profile-2026-08-24-17-43-28.html` |
| 5 | 2.17s | 63 actionable; 1 executed; 62 up-to-date | `:app:compileDebugKotlin` | `profile-2026-08-24-17-43-30.html` |

Gradle also emitted the existing warning that `-module-name` was passed
multiple times; all builds still succeeded.

## Player unit-test compile samples

The five authoritative samples below were rerun after restoring
`PlayerInputPolicyTest.kt` byte-for-byte to `HEAD`; the test identifiers were
valid for every command. The temporary marker was removed before this clean
rerun.

| Sample | Elapsed | Gradle result | Executed task summary | Profile report |
|---:|---:|---|---|---|
| 1 | 3.23s | 73 actionable; 1 executed; 2 from cache; 70 up-to-date | `:app:compileDebugUnitTestKotlin` | `profile-2026-08-24-17-46-43.html` |
| 2 | 2.55s | 73 actionable; 1 executed; 72 up-to-date | `:app:compileDebugUnitTestKotlin` | `profile-2026-08-24-17-46-46.html` |
| 3 | 2.30s | 73 actionable; 1 executed; 72 up-to-date | `:app:compileDebugUnitTestKotlin` | `profile-2026-08-24-17-46-48.html` |
| 4 | 2.29s | 73 actionable; 1 executed; 72 up-to-date | `:app:compileDebugUnitTestKotlin` | `profile-2026-08-24-17-46-51.html` |
| 5 | 2.29s | 73 actionable; 1 executed; 72 up-to-date | `:app:compileDebugUnitTestKotlin` | `profile-2026-08-24-17-46-53.html` |

The unit-test compile also emitted the existing duplicate `-module-name`
warning and completed successfully in every sample.
