# Settings Phase 5 Performance Protocol

Capture five paired samples on a committed pre-extraction ref and a committed
post-extraction ref. Use the same host, Gradle version, daemon state, worker
count, cache state, variant, and `--console=plain --warning-mode=none` flags.
Do not compare mixed dirty-tree timings.

## Feature source edit

Apply a comment-only edit to a settings production file, run the command five
times, revert that exact edit with `apply_patch`, and repeat on the other ref:

```powershell
.\gradlew.bat :app:compileDebugKotlin --profile --console=plain --warning-mode=none
```

Post-extraction command:

```powershell
.\gradlew.bat :feature:settings:compileDebugKotlin --profile --console=plain --warning-mode=none
```

Record wall time, executed/up-to-date tasks, Kotlin/KSP tasks, Gradle daemon,
cache state, and profile paths for each sample.

## Feature test edit

Apply a comment-only edit to a moved settings unit test and run five samples:

```powershell
.\gradlew.bat :app:compileDebugUnitTestKotlin --profile --console=plain --warning-mode=none
```

Post-extraction command:

```powershell
.\gradlew.bat :feature:settings:compileDebugUnitTestKotlin --profile --console=plain --warning-mode=none
```

## Guardrails

Capture clean debug and warm no-change guardrails with:

```powershell
.\gradlew.bat :app:assembleDebug --profile --console=plain --warning-mode=none
```

The target ranges are at least 25% faster for an isolated feature edit and at
least 20% faster for feature-test compilation, with no clean/warm regression
over the Phase 0 guardrails. If a comparable clean committed pre-ref cannot be
constructed, report the performance gate as open.
