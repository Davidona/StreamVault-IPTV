# Compose Reduction Phase 2.5B Report

Date: 2026-08-22

## Status

The startup coordinator, route-guard, async-tracing, and redesigned profile
generator are present. A clean connected-device collection now passes both the
startup and critical-journey tests on the API 36 TV emulator. Phase 2.5B is
**not exit-ready**: the release APK is 1.44% larger than the pre-2.5B APK and
the required two ten-iteration comparisons on constrained physical TV hardware
were not available. Shipping packaging is now validated for beta, release APK,
and release AAB. Generated profile sources remain local and uncommitted pending
the remaining size and physical-device gates.

## Scope and implementation

- Added `AppStartupCoordinator` as an internal singleton. Process maintenance
  starts once from `StreamVaultApp`, independently of an Activity. Expensive
  task graphs are injected through `Provider<T>` and each task runs under
  supervisor isolation with named Android trace sections.
- `MainActivity` uses a one-shot `doOnPreDraw` plus posted callback to release
  television-only Watch Next, launcher recommendation, and TV-input refresh
  work after the first UI frame. Non-TV and service-only launches do not run
  those tasks.
- Applied AndroidX Baseline Profile plugin `1.4.1` to `:app` and `:benchmark`.
  The app uses `mergeIntoMain = true`, `saveInSrc = true`, and disables
  automatic generation during ordinary builds. The existing `:benchmark`
  module is the only profile producer.
- Added guarded `startup` and `criticalJourneys` collections. Startup contains
  only cold launcher-to-ready-Home. Critical journeys cover Home, Live TV,
  EPG, and live-player controls, with destination assertions before each
  continuation.
- Added source verification and controlled startup benchmarks. The treatment
  requires `BaselineProfileMode.Require`; a missing ProfileInstaller or
  packaged profile therefore fails instead of silently measuring a control.
  Source verification is a typed, read-only Gradle task and remains compatible
  with the repository's configuration-cache build path. A separate typed
  merge/install task unions startup rules into `baseline-prof.txt` after the
  profile plugin copies fresh captures, while preserving `startup-prof.txt` as
  the startup-only subset.
- Stabilized Kotlin's module name across release-like variants so internal JVM
  method names in a profile collected from `nonMinifiedRelease` also match beta
  and release. The merge task normalizes legacy variant-suffixed rules,
  canonicalizes flags, and deduplicates both maintained sources. Its explicit
  task ordering prevents Gradle from consuming copied profile outputs before
  they have been merged.

## Pre-2.5B baseline

Captured before the startup coordinator changes on the API 36 TV emulator:

| Field | Value |
| --- | --- |
| Device | `Television_1080p(AVD) - 16`, AOSP TV on x86 |
| Android | API 36, fingerprint `google/sdk_google_atv_x86/emulator_x86_arm:16/BT2A.251018.001.A1/14340881` |
| Hardware model | 4 cores, max 2 GHz, approximately 2 GiB RAM |
| Compilation | `CompilationMode.None()` |
| Iterations | 10 cold starts |
| TTID median | 2,077.1 ms |
| TTID min/max | 1,958.2 / 2,490.4 ms |
| TTID P90 | Not emitted by the original result; min/max were retained |

The original ten Perfetto trace files were captured, then removed by a later
workspace `clean` to recover disk space. Their names and measurements were
recorded before cleanup.

## Generated profile sources

The redesigned generator completed a clean connected-device collection on the
seeded API 36 TV emulator. `startup()` contains only cold launcher-to-ready-Home
work; `criticalJourneys()` covers Home, Live TV, EPG, and a seeded HLS player.
The profile plugin emitted the general and startup captures separately. The
typed merge/install task then added the startup capture's rules to the
maintained baseline source, which enforces the Android requirement that a
startup profile is a subset of the baseline profile without reusing PR #162's
generated files.

| Source | Size | Rules |
| --- | ---: | ---: |
| `baseline-prof.txt` (after startup union) | 5,433,664 bytes | 47,782 |
| `startup-prof.txt` | 3,156,029 bytes | 29,852 |

The latest raw captures contained 48,006 general and 29,857 startup lines.
Normalization canonicalizes Kotlin variant suffixes, profile flag strength, and
duplicate keys, producing the maintained 47,782/29,852 rule sets. The general
capture already covered every startup method/class; the merge adds only
genuinely missing startup keys if a future collection exposes one. No
beta/release generated-profile subdirectories were created.

The collection command used the final generator class explicitly:

```text
gradlew.bat :app:generateBaselineProfile ^
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile ^
  -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.benchmark.BaselineProfileGenerator
```

## Artifact validation

The final shipping-variant packaging run completed successfully:

```text
gradlew.bat :app:assembleBeta :app:assembleRelease :app:bundleRelease --max-workers=1
```

Pre-2.5B artifact hashes retained from the capture step were:

```text
benchmark APK  37,454,211 bytes  C3112180B567AC232CAFA909BC067B668D8D173928029490E9CD783ED500E8BF
beta APK       37,454,231 bytes  1CD0D0597A727B0EDAE88319DCAC9570111336E556B886F660CE103837F38328
release APK    18,270,972 bytes  8B311F02AC31043C72F14D09A771680B0413BF25F9A7C61D99F20C30CE4F8E86
release AAB    24,695,046 bytes  6AB694F96FEDF5C1CAC6B0CCBC04FAEC1CF5A6A1FAE86980D3F98F1BA8488D31
```

| Artifact | Pre-2.5B bytes | Post-2.5B bytes | Change | Post SHA-256 |
| --- | ---: | ---: | ---: | --- |
| Release APK | 18,270,972 | 18,533,311 | +1.436% | `117AD397DA585A1E03585EBF51177B0302218C1F6905A6F4B0F59CD1B2FE2FCF` |
| Release AAB | 24,695,046 | 24,880,653 | +0.752% | `C459414A8F36C9B1A21DE8DD9C73ADE88D30EFB561CD27D88D3BDB68A63202DD` |
| Beta APK | 37,454,231 | 38,486,423 | +2.756% | `E66DD73D03262588DD7866075D4F9F0C7E5AA060C12B66EF50A4515EE229E066` |

Both beta and release APKs contained:

```text
assets/dexopt/baseline.prof
assets/dexopt/baseline.profm
```

The release AAB contained the corresponding
`BUNDLE-METADATA/com.android.tools.build.profiles/baseline.prof` and
`baseline.profm` entries. Release R8 metadata recorded two DEX files, with the
startup-optimized entry set to `"startup":true`. Release had three DEX files.

The AAB is inside the 1% size budget. The APK is not, so the artifact-size
acceptance gate remains open. Beta/release build constants were checked to have
empty dev-seed fields; the non-shipping `nonMinifiedRelease` target alone reads
local.properties seed fields. No dev-server URL, username, password, or M3U
seed value was found in the beta/release shipping artifacts.

The D8 profile merge still emits non-fatal startup-class placement diagnostics
for some external dependency rules. The previous app-variant suffix misses are
gone after the stable module-name fix. Packaging and `Require` both succeeded;
the remaining dependency diagnostics should be reviewed before committing the
generated output.

## Controlled startup measurements

The valid post-refactor runs used the non-shipping `nonMinifiedRelease` target,
ten cold iterations, `StartupTimingMetric`, and the API 36 TV emulator. The
Macrobenchmark runner needed the explicit `enabledRules=Macrobenchmark`
override because the baseline-profile plugin otherwise selects its
`BaselineProfile` rule for this variant.

| Run | Compilation | Median TTID | P90 TTID* | Min | Max |
| --- | --- | ---: | ---: | ---: | ---: |
| Post-refactor control | `None()` | 1,808.0 ms | 1,955.8 ms | 1,758.2 ms | 2,216.6 ms |
| Post-refactor treatment | `Partial(Require)` | 1,700.9 ms | 1,809.6 ms | 1,639.6 ms | 1,818.4 ms |

The required-profile treatment was successful. On this emulator it was 5.9%
faster by median and 7.5% faster by P90 than the post-refactor control. P90 is
the linear percentile calculated from the ten raw run values; the benchmark
message itself reports median/min/max.

The controlled commands were:

```text
gradlew.bat :benchmark:connectedNonMinifiedReleaseAndroidTest ^
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark ^
  -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.benchmark.StreamVaultMacrobenchmark#coldStartupNoCompilation

gradlew.bat :benchmark:connectedNonMinifiedReleaseAndroidTest ^
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=Macrobenchmark ^
  -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.benchmark.StreamVaultMacrobenchmark#coldStartupWithBaselineProfile
```

Both prior runs generated ten Perfetto traces. The treatment trace contained
the named coordinator sections in order, including
`AppStartupCoordinator:process-maintenance` and the later
`AppStartupCoordinator:tv-integrations-after-first-frame`, with task sections
for process work and TV integrations. Those traces were collected before the
async trace-section fix, so the ordering must be re-captured before using them
as final evidence; physical-TV confirmation is still required.

## Validation record

Passed in the final verification pass:

```text
:app:testDebugUnitTest
:benchmark:compileBenchmarkKotlin
:app:compileDebugKotlin
:app:assembleBeta
:app:assembleRelease
:app:bundleRelease
:app:generateBaselineProfile (guarded generator; both tests passed)
:app:installMergedBaselineProfile
:verifyBaselineProfileSources
:data:testDebugUnitTest (focused cache/repository tests)
:app:testDebugUnitTest (focused startup coordinator test)
:benchmark:compileBenchmarkKotlin
:benchmark:connectedNonMinifiedReleaseAndroidTest (control)
:benchmark:connectedNonMinifiedReleaseAndroidTest (Require treatment)
```

The earlier combined command reached `:app:testDebugUnitTest` successfully and
then failed during `:app:assembleDebug` because the C: volume exhausted space
while dexing Compose dependencies. That failure is unrelated to the focused
tests or shipping packaging; a full debug assemble remains a separate local
workspace check.

The repository-wide `gradlew.bat test --max-workers=1` was also rerun. App and
data test tasks passed, but the suite stopped at the existing domain failure:
`AppHomeDashboardShelfTest.defaultOrder matches the existing home layout`
(`AppHomeDashboardShelfTest.kt:10`), with 132 domain tests completed and one
failure. This is outside the Phase 2.5B changes.

## Limitations and exit decision

- Only the API 36 TV emulator was available. The emulator warns that its
  performance is not representative of physical TV hardware.
- The plan requires two constrained physical-TV ten-iteration comparisons,
  with a no-profile regression limit and a with-profile benefit threshold.
  Those comparisons were not run.
- The release APK exceeds the 1% artifact-growth limit by approximately 0.436
  percentage points, although the AAB is within budget.
- The pre-run P90 was not emitted by the original baseline result, so the
  three-way P90 comparison needs a fresh controlled baseline if the physical
  gate is pursued.
- Generated profile sources are intentionally not committed yet.

Decision: **code/tooling and shipping packaging complete; Phase 2.5B exit gate
open** pending APK-size investigation and two physical-TV startup comparisons.
