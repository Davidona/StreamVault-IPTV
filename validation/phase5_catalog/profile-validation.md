# Catalog profile validation

Date: 2026-09-02

The checked-in generated profile sources were inspected after the Catalog move.
They currently contain the pre-extraction app-package descriptors and no
`com/streamvault/feature/catalog` descriptors:

| Source | Legacy app Catalog matches | Feature Catalog matches |
|---|---:|---:|
| `app/src/main/generated/baselineProfiles/baseline-prof.txt` | 748 | 0 |
| `app/src/main/generated/baselineProfiles/startup-prof.txt` | 726 | 0 |

This is an open gate. The existing Phase 5 Live/Provider reports record a
successful profile-generation workflow on the same API 36 TV emulator, but a
fresh Catalog-specific `:app:generateBaselineProfile` run was not repeated in
this slice because that workflow takes roughly 25 minutes and requires seeded
profile journeys. The generated files were not hand-edited. Re-run the
existing generator, then verify that legacy Catalog descriptors are absent and
feature descriptors are present before claiming profile completion.

The current packaging guardrail (`:app:assembleBeta` and
`:app:assembleRelease`) passed; it does not substitute for profile generation.
