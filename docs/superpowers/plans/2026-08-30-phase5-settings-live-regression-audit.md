# Phase 5 Settings and Live Regression Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Compare the Phase 5 Settings and Live extractions with their historical rollback commits, document every observable difference, and fix confirmed regressions in an isolated worktree.

**Architecture:** Three detached external worktrees provide the Settings baseline, Live baseline, and frozen target. Static slice reviews run independently; all builds and emulator work are serialized. The active checkout receives Markdown evidence only, while target code fixes remain committed in the isolated audit worktree.

**Tech Stack:** Git worktrees, Gradle/Android instrumentation, ADB API 36 TV emulator, Compose UI tests and goldens, PowerShell, Graphify.

**Spec:** `docs/superpowers/specs/2026-08-30-phase5-settings-live-regression-audit-design.md`

## Global Constraints

- Do not change branches, stage, commit, build, install, or edit code in the primary checkout.
- Only Markdown audit documentation may be written in the primary checkout.
- Settings baseline is `e12f816fd12ac688a6df6391b5740d33d8a3ada0`.
- Live baseline is `ea349d83dceb8baa45ef1a18b7a45fa295a264f5`.
- Frozen target is `bb52375d04e72e95a7dd70b0c79e2aab91c2121a` plus tracked patch hash `29be114106b2c471531e25caa6fd1a54027d2b3b` and untracked manifest SHA-256 `46ebf25851b43bd6ecdb9daff3d1aa68fd2644c0313104c0fdcb36b4ad699cc9`.
- Emulator installs, connected tests, fixture mutations, and screenshot captures run serially.
- Post-extraction goldens are supporting evidence, not the historical visual oracle.
- Fixes use a failing reproducer first and remain committed only in the isolated target worktree.
- Live playback validation covers at least two channels with the required 2-second, 45/61-frame protocol.
- Run `graphify update .` in the target worktree after code changes.

---

### Task 1: Freeze and isolate the audit inputs

**Files:**
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_LIVE_REGRESSION_AUDIT.md`
- Create outside primary checkout: `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\target-working-tree.patch`
- Create outside primary checkout: `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\untracked-manifest.txt`

**Interfaces:**
- Consumes: the primary checkout read-only Git state.
- Produces: three detached worktree paths and a reproducible target fingerprint.

- [ ] **Step 1: Record the primary checkout state without modifying it**

Run `git status --short --branch`, `git rev-parse HEAD`, and the tracked/untracked fingerprint commands from the design. Copy the two approved untracked test files into the temporary audit root.

- [ ] **Step 2: Capture the non-document target patch outside the checkout**

Run `git diff --binary -- . ':(exclude)docs/**'` and write its output to `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\target-working-tree.patch`. Record its SHA-256 and verify the source checkout status is unchanged.

- [ ] **Step 3: Create three detached external worktrees**

Use `git worktree add --detach` to create `settings-baseline` at `e12f816f`, `live-baseline` at `ea349d83`, and `target` at `bb52375d` beneath `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993`. Apply the frozen patch and copied untracked tests only in `target`.

- [ ] **Step 4: Verify target reconstruction**

Compare `git diff --binary` and the untracked manifest in the target worktree with the frozen hashes. Record exact paths and hashes in the audit report.

### Task 2: Audit the Settings slice statically

**Files:**
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_LIVE_REGRESSION_AUDIT.md`
- Create outside primary checkout: `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\settings-static-review.md`

**Interfaces:**
- Consumes: Settings rollback and frozen target source.
- Produces: a path-by-path Settings risk inventory and candidate findings.

- [ ] **Step 1: Inventory changed Settings ownership and adapters**

Diff `e12f816f..target` for Settings source/resources/tests, app Settings adapters, navigation registration, provider entry/completion, backup preview, and shared UI dependencies.

- [ ] **Step 2: Review observable contracts**

Check route arguments, callback order, dialog dismissal, Back/focus behavior, list ordering, defaults, persistence, locale text, colors, spacing, and platform handoffs against the pre-extraction implementation.

- [ ] **Step 3: Trace candidate changes to commits**

Use targeted `git log -p`, `git blame`, and parent comparisons to identify the earliest commit that introduced each candidate difference.

- [ ] **Step 4: Write the Settings review artifact**

Record every reviewed area, candidate finding, evidence path, and areas proven equivalent. Add confirmed or inconclusive findings to the main audit report.

### Task 3: Audit the Live slice statically

**Files:**
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_LIVE_REGRESSION_AUDIT.md`
- Create outside primary checkout: `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\live-static-review.md`

**Interfaces:**
- Consumes: Live rollback and frozen target source.
- Produces: a path-by-path Live/EPG risk inventory and candidate findings.

- [ ] **Step 1: Inventory changed Live/EPG ownership and adapters**

Diff `ea349d83..target` for Home, Live, EPG, app route/request mapping, preview handoff, surface refresh, MultiView composition, resources, and tests.

- [ ] **Step 2: Review observable contracts**

Check state defaults, ordering/identity, focus and remote dispatch, preview lifecycle/callback order, colors/layout/text, dialogs, search/filter/reorder, Guide navigation, and player/MultiView handoff.

- [ ] **Step 3: Trace candidate changes to commits**

Identify the earliest introducing commit for every observable difference, including the category-title color inheritance regression.

- [ ] **Step 4: Write the Live review artifact**

Record every reviewed area, candidate finding, evidence path, and areas proven equivalent. Add confirmed or inconclusive findings to the main audit report.

### Task 4: Audit cross-slice contracts

**Files:**
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_LIVE_REGRESSION_AUDIT.md`
- Create outside primary checkout: `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\cross-slice-review.md`

**Interfaces:**
- Consumes: both static reviews and the Graphify architecture map.
- Produces: cross-slice journey and dependency findings.

- [ ] **Step 1: Trace Settings-to-Live persisted controls**

Review quick filters, source-switcher visibility, category modes/sorting, parental controls, EPG settings, and provider selection from Settings mutation through Live/EPG observation.

- [ ] **Step 2: Trace provider and playback composition seams**

Review Add Provider completion, backup preview, player request mapping, preview handoff, surface refresh, and MultiView planner callbacks across app/feature boundaries.

- [ ] **Step 3: Record cross-slice findings and test gaps**

Classify differences and identify the exact unit/connected/manual evidence needed for each unresolved contract.

### Task 5: Run automated structural and behavioral gates

**Files:**
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_LIVE_REGRESSION_AUDIT.md`
- Create outside primary checkout: `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\logs\*.log`

**Interfaces:**
- Consumes: clean baseline and reconstructed target worktrees.
- Produces: exact command/result evidence without emulator state overlap.

- [ ] **Step 1: Run target Settings gates**

Run feature boundary, unit, lint/check, Android-test compilation, and focused app adapter/navigation tests. Save complete logs outside the primary checkout.

- [ ] **Step 2: Run target Live gates**

Run feature boundary, unit, lint/check, Android-test compilation, focused app adapters, playback handoff, and debug assembly. Save complete logs.

- [ ] **Step 3: Run historical buildability checks**

Build each historical debug target with the tasks available at that commit. Record missing tasks and unrelated baseline failures exactly.

- [ ] **Step 4: Run connected suites serially**

Run Settings and Live connected tests one suite at a time on `emulator-5554`; record XML paths, counts, failures, and skips.

### Task 6: Capture and compare historical visual states

**Files:**
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_LIVE_REGRESSION_AUDIT.md`
- Create outside primary checkout: `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\visual\` with `settings-baseline`, `settings-target`, `live-baseline`, `live-target`, and `diffs` children.

**Interfaces:**
- Consumes: baseline/target APKs and identical emulator fixture/configuration.
- Produces: paired 1920x1080 screenshots and visual classifications.

- [ ] **Step 1: Record and normalize emulator configuration**

Capture resolution, density, locale, layout direction, font scale, animation scales, package version, and fixture-reset procedure before installing either build.

- [ ] **Step 2: Capture Settings pairs**

Replay the same Settings navigation, sections, dialogs, focus states, backup preview, provider management, parental controls, and error/empty states against baseline and target.

- [ ] **Step 3: Capture Live/EPG pairs**

Replay equivalent drawer, list, selected/focused, search/filter, hidden/favorite/reorder, preview, Guide, option/dialog, empty/error, and MultiView-entry states.

- [ ] **Step 4: Compute and review diffs**

Record dimensions and SHA-256 hashes, generate pixel/perceptual comparisons, manually inspect every nonzero difference, and classify it in the findings ledger.

### Task 7: Replay behavioral parity journeys

**Files:**
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_LIVE_REGRESSION_AUDIT.md`
- Create outside primary checkout: `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\behavior\*.md`

**Interfaces:**
- Consumes: the normalized baseline/target emulator state.
- Produces: step-by-step route, focus, callback, persistence, and side-effect comparisons.

- [ ] **Step 1: Replay Settings journeys**

Cover D-pad section navigation, dialog opening/dismissal, Back/focus restoration, provider add completion, preference persistence, backup safe paths, update check, and parental/EPG controls.

- [ ] **Step 2: Replay Live journeys**

Cover category/source/channel selection, search, quick filters, hidden items, favorites/reorder, preview replacement/fullscreen/return, Guide navigation/options/search, and MultiView planning.

- [ ] **Step 3: Replay cross-slice persistence journeys**

Change Settings-owned Live preferences, force-stop/relaunch, and verify the corresponding Live/EPG state before restoring fixture state.

- [ ] **Step 4: Classify every observed behavior difference**

Record regression, improvement, equivalent, pre-existing, or inconclusive status with exact evidence.

### Task 8: Validate long-duration Live playback

**Files:**
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_LIVE_REGRESSION_AUDIT.md`
- Create outside primary checkout: `C:\Users\david\AppData\Local\Temp\streamvault-phase5-regression-audit-20260830-4e19dbd570eb4dd2bb945daa94aa2993\live-playback\channel-1\` and `channel-2\`.

**Interfaces:**
- Consumes: target APK, seeded live provider, and serialized emulator access.
- Produces: two-channel screenshot/hash/media-session/log evidence.

- [ ] **Step 1: Select and record two available HLS channels**

Record channel names and stream type without exposing credentials or sensitive URLs.

- [ ] **Step 2: Capture each channel at two-second cadence**

Prefer 61 screenshots over roughly two minutes per channel; never accept fewer than 45 over approximately 90 seconds.

- [ ] **Step 3: Validate progression and player health**

Count unique hashes, capture media session state, save sanitized logcat, and scan for HLS prepare/read/first-frame/recovery plus fatal/stuck/MPEG-TS markers.

- [ ] **Step 4: Record pass/fail per channel**

Do not infer a general Live pass from one healthy channel.

### Task 9: Fix confirmed regressions in the target worktree

**Files:**
- Modify in target worktree only: exact production/test files named by confirmed findings.
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_LIVE_REGRESSION_AUDIT.md`

**Interfaces:**
- Consumes: confirmed regression findings with deterministic reproducers.
- Produces: isolated fix commits and focused passing tests.

- [ ] **Step 1: Write or verify a failing focused test per regression**

Run each reproducer before the production fix and save the failure output. If an existing frozen-target change already contains the fix, verify the test fails against its immediate pre-fix form in a disposable worktree or parent comparison.

- [ ] **Step 2: Implement the smallest behavior-preserving fix**

Do not bundle redesign, unrelated cleanup, or acceptance of changed behavior into the fix.

- [ ] **Step 3: Run focused and neighboring tests**

Run the direct reproducer plus the relevant feature/app contract suite and record exact results.

- [ ] **Step 4: Commit and review each fix in isolation**

Commit only in the target worktree. Record commit SHA, test evidence, reviewer verdict, and integration notes in the report.

### Task 10: Final verification and report closure

**Files:**
- Modify: `docs/COMPOSE_REDUCTION_PHASE5_SETTINGS_LIVE_REGRESSION_AUDIT.md`
- Modify: `docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md`

**Interfaces:**
- Consumes: all static, automated, visual, behavioral, playback, and fix evidence.
- Produces: the final audit report and an explicit active-worktree integrity statement.

- [ ] **Step 1: Run final focused suites in the target worktree**

Re-run Settings/Live boundaries, unit/check gates, connected suites, app debug assembly, and all regression reproducers. Run `graphify update .` after code changes.

- [ ] **Step 2: Perform a final read-only primary checkout drift check**

Compare current HEAD/status with the frozen target. Record later changes separately; do not overwrite or incorporate them silently.

- [ ] **Step 3: Complete the findings ledger**

Ensure every finding has classification, severity, evidence, fix/test status, and remaining limitation. List improvements separately and retain inconclusive gates as open.

- [ ] **Step 4: Verify checkout integrity and hand off isolated commits**

Confirm the primary checkout has no audit-caused non-Markdown changes. Report isolated fix SHAs and paths; do not merge, cherry-pick, push, or remove another agent's worktree.
