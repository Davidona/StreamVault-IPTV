# Phase 5 Settings and Live Regression Audit Design

Date: 2026-08-30
Status: Approved for execution

## Context

Phase 5 mechanically extracted `:feature:settings` and `:feature:live` from
`:app`. The accepted extraction designs require behavioral and visual parity,
but the existing connected suites and post-extraction goldens do not by
themselves prove parity with the pre-extraction application. Confirmed
regressions have already included an inherited black Live category title and
provider completion leaving the Add Provider route open.

The primary checkout is actively used by another agent. This audit may write
Markdown there, but must not change its branch, stage or commit files, edit
code, build from it, install an APK from it, or otherwise disturb it. All code
inspection that needs generated artifacts, builds, tests, fixes, or commits
will occur in isolated external worktrees.

## Goal

Produce an evidence-backed regression audit for the Phase 5 Settings and Live
slices, classify every observed difference as a regression, intentional
improvement, pre-existing issue, equivalent change, or inconclusive result,
and fix confirmed regressions in an isolated audit worktree with focused tests.

## Comparison points

- Settings rollback: `e12f816fd12ac688a6df6391b5740d33d8a3ada0`.
  This is the recorded inventory/baseline commit and the parent of the first
  Settings implementation commit, `1c0e6ae6`.
- Live rollback: `ea349d83dceb8baa45ef1a18b7a45fa295a264f5`.
  This is the rollback point recorded in the Live report and the parent of the
  first Live implementation commit, `712751b7`.
- Combined rollback: the Settings rollback, used once to detect interaction
  drift across both extracted slices.
- Frozen target: `bb52375d04e72e95a7dd70b0c79e2aab91c2121a`
  plus the non-document working-tree snapshot captured at audit start.
  The tracked patch hash is `29be114106b2c471531e25caa6fd1a54027d2b3b`;
  the untracked-file manifest SHA-256 is
  `46ebf25851b43bd6ecdb9daff3d1aa68fd2644c0313104c0fdcb36b4ad699cc9`.

The target is frozen because the primary checkout may continue changing while
the audit runs. A final read-only drift check will report changes made after
the freeze; it will not silently redefine the target.

## Isolation and concurrency

Create uniquely named detached worktrees beneath Windows temporary storage:

- Settings historical worktree at the Settings rollback.
- Live historical worktree at the Live rollback.
- Target audit worktree at the frozen target, with the captured tracked patch
  and two captured untracked test files applied.

Read-only Settings, Live, and cross-slice history reviews may run in parallel.
Builds and emulator journeys are serialized. Only one process may install an
APK, mutate emulator state, capture device evidence, or run connected tests at
a time.

No audit commit is merged, cherry-picked, pushed, or applied to the primary
checkout. Fix commits remain in the target audit worktree and are reported for
later integration.

## Audit method

### Static differential review

For each slice, compare its rollback against the frozen target and inspect:

- route arguments, destination mapping, Back behavior, and dialog dismissal;
- callback order, exactly-once side effects, persistence, and error paths;
- focus request order, D-pad/Enter/long-press behavior, and focus restoration;
- colors, dimensions, placement, ordering, conditional visibility, text,
  plurals, placeholders, and locale resources;
- state defaults, stable keys/content types, list ordering, and saveable state;
- app-to-feature adapters and cross-slice contracts.

The cross-slice review covers Settings controls that affect Live behavior,
provider completion entered through Settings, backup preview composition,
Live player/MultiView handoff, and shared resources or UI primitives.

### Automated evidence

Run the focused unit, boundary, lint, Android-test compilation, connected, and
app integration gates named in the two accepted extraction designs. Historical
commits use the tasks that existed at those commits; missing historical tasks
are recorded rather than treated as failures.

Every confirmed regression fixed during the audit receives the narrowest
practical reproducer test before production changes. Existing tests are not
rewritten merely to accept changed behavior.

### Visual parity

Use the same API 36 `Television_1080p` emulator at 1920x1080 for baseline and
target captures. Record locale, layout direction, density, font scale,
animation scales, theme, package, fixture state, and capture command. Reset to
the same documented fixture state between builds.

Capture Settings navigation and every reachable section/dialog state, plus
Live category/channel drawers, source switching, search, quick filters,
hidden items, favorites/reorder, preview, Guide grid/hero/options/search,
empty/error states, and MultiView entry. Compare historical and target PNGs by
dimensions, hashes, pixel/perceptual difference, and manual review. A
post-extraction golden is supporting evidence only, not a historical oracle.

### Behavioral parity

Replay equivalent D-pad, Enter, Back, long-press, text-input, persistence, and
navigation journeys against baseline and target. Record the focused node or
route at every transition and verify callback/side-effect counts where the UI
does not expose enough evidence.

### Live playback

Validate at least two Live channels. Prefer 61 screenshots per channel at a
two-second cadence, never fewer than 45 over roughly 90 seconds. A pass needs
changing hashes through the full window, media session `PLAYING` with
`error=null`, sanitized HLS prepare/read/first-frame or intended recovery
evidence, and no fatal error, stuck timeout, or unintended MPEG-TS fallback.

## Finding classification

Each finding records an ID, slice, comparison, screen/flow, type, severity,
expected and observed behavior, reproduction, suspected introduction point,
evidence paths, classification, fix commit, test, and final status.

Allowed classifications are:

- Regression: unintended loss of prior behavior or visual parity.
- Improvement: intentional or demonstrably better behavior with no contract
  violation; the exact benefit and compatibility reasoning are recorded.
- Equivalent: implementation changed but observable behavior did not.
- Pre-existing: reproducible at the applicable rollback point.
- Inconclusive: blocked by fixture, hardware, external service, or unstable
  evidence; never promoted to a pass.

## Completion criteria

- Both slices have complete static review inventories.
- The applicable focused automated gates are recorded with exact commands and
  results.
- Baseline/target visual and behavior matrices identify passed, failed, and
  unavailable states without collapsing unavailable into passed.
- Confirmed regressions have isolated fixes and focused verification, or an
  explicit reason they remain open.
- Two-channel long-duration Live evidence satisfies the project protocol.
- The audit report contains all findings, improvements, limitations, target
  drift, worktree fix commits, and remaining gates.
- The primary checkout has no non-Markdown audit changes.

