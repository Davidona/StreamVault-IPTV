# Platform Smoke Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make platform-smoke failures diagnosable and validate the exact release matrix on `develop` before merging to `master`.

**Architecture:** Extract the matrix into a develop-push/reusable/manual GitHub Actions workflow and make release delegate to it. Run Gradle only while the emulator is active, and capture Android failure evidence from an exit trap before the emulator is destroyed.

**Tech Stack:** GitHub Actions YAML, POSIX shell, Gradle Android instrumentation, adb

**Spec:** `docs/superpowers/specs/2026-08-22-platform-smoke-reliability-design.md`

## Global Constraints

- Do not trigger or publish a master release during implementation.
- Preserve API 25, 26, 28, 32, 33, 35, and 36 coverage.
- Preserve the original failing exit status when collecting diagnostics.
- API 25–35 use their last known-good execution path without a precompile stage.
- API 36 remains isolated as Android TV x86 until CI evidence supports another configuration.

---

### Task 1: Failure diagnostics

**Files:**
- Create: `tools/capture-platform-smoke-diagnostics.sh`
- Modify: `tools/run-platform-smoke.sh`
- Create: `tools/tests/platform-smoke-diagnostics-test.sh`

**Interfaces:**
- Consumes: `PLATFORM_SMOKE_ARTIFACT_DIR`, `PLATFORM_SMOKE_ACTIVE_SUITE`, `ANDROID_SERIAL`
- Produces: diagnostic text/log files without altering the test command's exit status

- [x] Write a shell test with fake `adb` and failing `gradlew`; assert a nonzero status, active-suite record, crash-buffer call, and DropBox call.
- [x] Run `bash tools/tests/platform-smoke-diagnostics-test.sh`; expect failure because diagnostics do not exist.
- [x] Add the diagnostic collector and an exit trap installed before the first Gradle suite.
- [x] Run the shell test and existing emulator-wait test; expect both to pass.

### Task 2: Reusable workflow

**Files:**
- Create: `.github/workflows/platform-smoke.yml`
- Modify: `.github/workflows/release.yml`
- Create: `tools/tests/platform-smoke-workflow-test.sh`

**Interfaces:**
- Produces: reusable `platform-smoke.yml` run on develop pushes, callable by release, and manually dispatchable after it exists on the default branch
- Produces: artifacts named `platform-smoke-api-<api>-diagnostics`

- [x] Write static assertions for workflow-call/manual triggers, release delegation, no precompile stage, and connected-test artifact paths.
- [x] Run `bash tools/tests/platform-smoke-workflow-test.sh`; expect failure while the matrix remains embedded in release.
- [x] Move the matrix job into the reusable workflow and replace the release matrix with a local reusable-workflow call.
- [x] Add always-run artifact upload per matrix entry.
- [x] Run both CI helper tests and parse both workflow YAML files.

### Task 3: Verification and handoff

**Files:**
- Modify only files required by verification findings.

- [x] Run all shell helper tests.
- [x] Run instrumentation compile gates and relevant host tests.
- [x] Run `graphify update .` and review the final diff.
- [ ] Commit and push the isolated branch to `origin/develop`.
- [ ] Manually dispatch `platform-smoke.yml` against `develop`, never `release.yml` or master.
- [ ] Inspect every matrix job; if one fails, use its uploaded diagnostics to identify the exact Android exception before changing configuration.
