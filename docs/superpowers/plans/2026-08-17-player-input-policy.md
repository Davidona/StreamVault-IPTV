# Player Input Policy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with verification checkpoints.

**Goal:** Extract the player root key-routing decisions into a tested, platform-free policy without changing TV input behavior.

**Architecture:** `PlayerInputPolicy.kt` owns immutable state snapshots, platform-free keys, typed decisions, and pure preview/normal decision functions. `PlayerScreen.kt` keeps Android event conversion, Compose modifiers, focus/pointer behavior, state mutation, ViewModel calls, and Back execution.

**Tech Stack:** Kotlin, Jetpack Compose, Android `KeyEvent` adapter, JUnit4, Truth.

## Global Constraints

- Preserve separate preview and normal key phases.
- Preserve action-down-only handling and `notifyUserActivity()` ordering.
- Preserve all current overlay/modal/RTL/catch-up predicates and command ordering.
- Keep `PlayerBackNavigationPolicy` and all mutable state in `PlayerScreen.kt`.
- Do not introduce Phase 2 modal state, navigation contracts, or ViewModel APIs.

### Task 1: Add the platform-free policy contract and tests

**Files:**
- Create: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerInputPolicy.kt`
- Test: `app/src/test/java/com/streamvault/app/ui/screens/player/PlayerInputPolicyTest.kt`

**Interfaces:**
- Produces `PlayerInputKey`, `PlayerInputState`, `PlayerInputAction`, `PlayerInputDecision`, `playerPreviewInputDecision()`, and `playerInputDecision()`.

- [x] **Step 1: Write failing policy tests** covering preview zapping/blocking, modal Back/pass-through, center numeric/live behavior, RTL left/right mapping, seek, episode picker, media/guide/info/menu, channel previous, mute repeat, and numeric digits.
- [x] **Step 2: Run the focused test and verify it fails because the policy contract is absent.**
- [x] **Step 3: Implement the smallest pure policy model and decision functions matching the design invariants.**
- [x] **Step 4: Run the focused policy test until all cases pass.**

### Task 2: Wire the policy into `PlayerScreen`

**Files:**
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerScreen.kt` in the root modifier key handlers.
- Modify: `app/src/main/java/com/streamvault/app/ui/screens/player/PlayerInputPolicy.kt` only if adapter visibility requires it.

**Interfaces:**
- Consumes the pure decisions from Task 1.
- Produces the same existing ViewModel/state mutations and modifier return values.

- [x] **Step 1: Add the Android key-code adapter at the screen boundary, including numeric digit conversion.**
- [x] **Step 2: Build one `PlayerInputState` snapshot from the existing screen values without moving ownership.**
- [x] **Step 3: Replace only the preview handler’s branching with `playerPreviewInputDecision()`, preserving notification and callback order.**
- [x] **Step 4: Replace only the normal handler’s branching with `playerInputDecision()`, preserving the existing `handleBackPress()` call and all side effects.**
- [x] **Step 5: Compile and run focused policy tests.**

### Task 3: Verify behavior-preserving decomposition

**Files:**
- Inspect: `PlayerScreen.kt`, `PlayerInputPolicy.kt`, and `PlayerInputPolicyTest.kt`.

- [x] **Step 1: Run source checks confirming Back policy, pointer input, focus properties, and ViewModel ownership remain in `PlayerScreen.kt`.**
- [x] **Step 2: Run `:app:testDebugUnitTest` and `:app:compileDebugKotlin`.**
- [x] **Step 3: Run `git diff --check` and `graphify update .`.**
- [ ] **Step 4: Re-run applicable player/golden/live validation; record any existing environment failures without attributing them to this extraction.**

Verification recorded 2026-08-17: the focused policy test and full `:app:testDebugUnitTest` pass, and `:app:compileDebugKotlin` passes. `:app:lintDebug` still reports the repository’s 56 unsuppressed baseline findings; none reference the changed player input/modal files. Fresh emulator evidence captured 61 frames at a 2-second cadence for USA CBSN Minnesota (provider HTTP 509, one unique frame, terminal provider-limit error) and USA Fox News (21 unique frames, HLS first frame followed by provider-limit recovery to MPEG-TS; media session was PLAYING during recovery but the final post-window dump was STOPPED). These are provider/environment outcomes, not attributed to the input extraction, but they do not satisfy the clean live gate. The manual TV matrix and final player acceptance therefore remain open Phase 1 gates.

`:app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.ui.PlayerSmokeTest'` also reproduces the existing three failures (category search-field focus, controls play-button focus, and track-selection fixture setup); the mute-action smoke test passes. No failure points at the extracted policy.
