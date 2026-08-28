# Phase 5 Settings Profile Validation

Date: 2026-08-28

## Seeded-target generation

The Android SDK was set to `E:\androidSdk`. The connected target was the
`Television_1080p(AVD)` API 36 emulator (`emulator-5554`,
`AOSP_TV_on_x86`). The existing `:benchmark` producer was used; no profile
files were hand-edited.

```text
gradlew.bat :app:generateBaselineProfile --no-daemon \
  --console=plain --warning-mode=none
BUILD SUCCESSFUL in 12m 37s
```

The producer reported 10 profile-collection tests passed. Eight ordinary
macrobenchmark methods were skipped by the existing profile selector. The
profile plugin merged and installed:

- `app/src/main/generated/baselineProfiles/baseline-prof.txt`: 46,249 rules
- `app/src/main/generated/baselineProfiles/startup-prof.txt`: 30,093 rules

The normalized merge task reports the startup rules as a subset of the
baseline rule keys, and `verifyBaselineProfileSources` passes:

```text
gradlew.bat verifyBaselineProfileSources --no-daemon \
  --console=plain --warning-mode=none
BUILD SUCCESSFUL
Verified baseline profile sources: baseline=46249 rules, startup=30093 rules.
```

## Stale-descriptor audit

```text
source_stale_profile_lines=0
source_feature_profile_lines=92
```

The maintained source profiles no longer contain pre-extraction
`com/streamvault/app/ui/screens/settings` descriptors and now include
`com/streamvault/feature/settings` descriptors. Build intermediates may still
contain variant-specific diagnostics from the current compilation; the source
audit is the release input that is checked into the repository.

The generated profile changes are paired with the app build-script guard that
keeps release/beta assembly validation explicit while avoiding a cycle during
profile collection. Physical-device and performance comparisons remain
separate Phase 5 gates.

The exact roadmap invocation (generation, source verification, and both
assemblies in one request) was also dry-run after that guard change. Its task
graph resolved successfully without a cycle; the real collection and the
release-like assemblies were executed as separate commands above to avoid
repeating the 12-minute connected collection solely for graph validation.

```text
gradlew.bat :app:generateBaselineProfile verifyBaselineProfileSources \
  :app:assembleBeta :app:assembleRelease --dry-run \
  --no-daemon --console=plain --warning-mode=none
BUILD SUCCESSFUL in 34s
```
