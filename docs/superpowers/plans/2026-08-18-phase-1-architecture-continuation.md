# Phase 1 Architecture Continuation

## Goal

Finish the remaining Phase 1 source decomposition while preserving runtime behavior and keeping state ownership changes out of scope.

## Acceptance criteria

- Player input decisions read mutable input state at event time; rapid numeric-entry confirmation cannot observe a stale composition snapshot.
- ProviderSetup retains draft state, launchers, effects, and ViewModel calls in its root, while provider-specific UI branches and leaf utilities/hosts are separated into same-package files.
- AppNavigation retains root orchestration and `NavController` ownership, while route/request contracts, adapters, external-navigation handling, policy, and graph registrations are separated into same-package files.
- Existing focused and unit tests pass, whitespace/static checks pass, and graphify is updated.
- No typed-route, feature-module, or Phase 2 state-ownership redesign is introduced.

## Implementation tasks

1. Add a failing regression test for rapid numeric input followed by Enter, then make player event callbacks construct `PlayerInputState` from current values at event time. Run the focused test before and after the fix.
2. Extract the remaining `ProviderFormContent` branches into provider-specific composables with unchanged parameters and named call sites. Move `StalkerCompatibilitySelector`, `FormErrors`, password transformation, completion effects/dialogs, and remaining leaf dialogs to same-package host/component files without moving root-owned state or effects.
3. Split `AppNavigation.kt` into same-package contracts/adapters, external-navigation host, top-level navigation policy, and graph-registration extensions. Keep behavior, registration order, and root orchestration unchanged.
4. Run focused tests, the debug unit-test suite, `git diff --check`, and `graphify update .`. Review the diff for accidental behavior or ownership changes and update the architecture plan with status/gaps.

## Verification commands

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.streamvault.app.ui.screens.player.*" --tests "com.streamvault.app.ui.screens.provider.*" --tests "com.streamvault.app.navigation.*" --rerun --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --no-daemon --console=plain
git diff --check HEAD
graphify update .
```
