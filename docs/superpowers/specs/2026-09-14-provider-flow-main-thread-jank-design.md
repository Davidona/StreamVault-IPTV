# Provider Flow Main-Thread Jank Remediation Design

**Date:** 2026-09-14

**Status:** Approved direction; awaiting written-spec review

## Summary

Remove the intermittent UI stalls caused by provider configuration decryption on the Android main thread. The data layer will decode encrypted provider configuration only when configuration rows change, publish one process-scoped shared provider stream, and retain only credential-redacted projections in replayed state. App navigation will consume the already collected navigation state instead of opening additional repository flows from composables.

The change preserves the `ProviderRepository` interface, provider behavior, Room schema, and credential-at-rest format. Playback preload and thumbnail behavior are outside the implementation scope unless post-fix measurements show a remaining independent problem.

## Evidence and Problem Statement

ADB diagnostics from `com.streamvault.app.debug` showed cumulative frame jank of 3,988 out of 24,090 frames (16.55 percent), a 99th-percentile frame time of 250 ms, and a recent 56-frame skip. In the captured log window, 77 StrictMode events exceeded 100 ms, 24 exceeded 500 ms, and the longest reached 674 ms.

The repeated main-thread stack was:

1. Compose's `AndroidUiDispatcher` resumes a provider flow collector.
2. `ProviderRepositoryImpl.getProviders()` handles a Room `combine` emission.
3. `ProviderConfigurationCodec.decode()` decrypts stored configuration.
4. `AndroidKeystoreCredentialCrypto.decryptIfNeeded()` loads Android Keystore and obtains the key.
5. Keystore disk and binder work blocks the UI thread.

`getProviders()` currently combines identity, configuration, account-runtime, and Stalker-learning tables before decoding every stored configuration. A runtime or learning update therefore repeats configuration decryption even when encrypted configuration has not changed. `getActiveProvider()` creates another cold mapping flow for every call, and app navigation has multiple long-lived and Compose-created collectors.

Memory samples remained flat and later decreased, with no OOM or ANR. The observed failure is bursty main-thread blocking rather than a demonstrated heap leak or sustained CPU saturation.

## Goals

- Execute provider configuration decode and Android Keystore access away from the main thread.
- Decode a provider configuration only when its stored configuration row changes.
- Share a single upstream providers stream across all repository consumers.
- Share a single active-provider derivation instead of rebuilding it per call.
- Remove repository and activity lookups from app-shell composables.
- Keep public provider emissions credential-redacted.
- Preserve the first-emission behavior relied on by callers using `first()`.
- Preserve provider ordering and all identity, runtime, catalog-layout, and Stalker-learning fields.
- Add automated regression coverage for decode frequency, sharing, redaction, and navigation destination construction.
- Verify the result on the connected emulator using repeatable ADB measurements.

## Non-goals

- Changing the Room schema or encrypted JSON format.
- Replacing Android Keystore or weakening credential encryption.
- Removing StrictMode from debug builds.
- Refactoring all provider consumers to a new repository interface.
- Redesigning navigation or app-shell visuals.
- Changing Media3 preload, thumbnail extraction, or playback behavior in the initial fix.
- Establishing a general application-wide performance framework.

## Chosen Architecture

### Lazily shared repository streams

`ProviderRepositoryImpl` will own stable flow instances rather than constructing a new cold flow in `getProviders()` and `getActiveProvider()`.

The streams use `shareIn(repositoryScope, SharingStarted.Lazily, replay = 1)`. Lazy sharing preserves the current behavior in which `first()` waits for a real Room-backed emission; it does not introduce an artificial empty provider list or null active provider. Once the singleton repository receives its first subscriber, the upstream Room observation remains shared for the process lifetime.

`getProviders()` returns the stable shared providers flow. `getActiveProvider()` returns a stable shared flow derived from it with `distinctUntilChanged()`.

### Configuration-only decode stage

Configuration decoding becomes its own upstream stage sourced only from `ProviderSnapshotDao.observeConfigs()`. It runs on `Dispatchers.IO` before sharing and produces a map keyed by provider ID.

Each output entry is a private data-layer projection containing:

- The configuration generation.
- A `LegacyProvider` template populated with configuration-derived public fields.
- A blank password/credential field.

The typed `ProviderConfiguration` and decrypted password exist only while the projection is built. They are not retained in the shared flow replay cache. The encrypted payload is not logged.

M3U configurations that require no decryption follow the same mapping path for consistent behavior. Malformed or undecryptable configuration continues to follow the repository's existing failure behavior; the performance change does not silently convert credential corruption into a valid provider.

### Lightweight assembly stage

The final providers stream combines:

- Provider identity rows.
- Shared redacted configuration projections.
- Account runtime rows.
- Stalker portal state rows.

The combine transform performs only map lookups, generation validation, and immutable copies. Runtime and Stalker-learning emissions must not invoke `ProviderConfigurationCodec.decode()`.

The assembly helper overlays current identity fields, account-runtime fields, and generation-valid Stalker learning onto the redacted configuration template. A provider without a typed configuration row retains the existing `ProviderEntity.toPublicDomain()` behavior. Output order remains the identity DAO order.

The projection/assembly boundary will be implemented as focused internal data-layer code rather than adding more responsibilities to the already large `ProviderRepositoryImpl.kt`.

### Keystore key-handle cache

`AndroidKeystoreCredentialCrypto` will cache the process-local `SecretKey` handle returned by Android Keystore. The cache uses synchronized lazy initialization so concurrent callers cannot load or generate the alias more than once.

Only the opaque `SecretKey` handle is cached; raw key material is never exported. Encryption and decryption still create a fresh `Cipher`, IV, and GCM operation per value. Existing exception wrapping and actionable credential-reentry message remain unchanged.

The caching mechanism will be isolated behind a small internal key-provider class with an injectable loader, allowing JVM tests to prove single-load and concurrent behavior without requiring Android Keystore.

### Navigation state ownership

`AppNavigation` already collects `AppNavigationCoordinator.state`, whose `topLevelDestinations` and `catalogLayout` fields are sufficient to build app destination items. It becomes the sole app-shell source for these values.

`rememberAppDestinationItems()` changes from looking up `MainActivity`, collecting preferences, and invoking `providerRepository.getActiveProvider()` to a pure composable adapter that accepts configured destinations and catalog layout. `AppNavigation` computes the localized `UiDestination` list once per relevant state change and supplies it to `AppNavHost` and app-shell scaffolds through an app-owned composition local.

`AppNavHost` passes the same destination list to the settings graph. `AppScreenScaffold` reads the composition local instead of opening repository flows. Existing test/golden callers outside `AppNavigation` receive the current default destination list through the composition-local default, preserving isolated rendering.

`AppNavigationCoordinator` may continue to observe active provider state for startup and catalog reconciliation. Both observations consume the same repository-shared active-provider stream, so they no longer duplicate database observation or decryption.

## Data Flow

```text
provider_configs Room flow
    -> IO decode/decrypt
    -> credential-redacted configuration projection
    -> lazy shared replay(1)

provider identity + redacted projection + runtime + Stalker learning
    -> lightweight provider assembly
    -> lazy shared providers replay(1)
    -> shared distinct active provider replay(1)
    -> AppNavigationCoordinator state
    -> AppNavigation destination items
    -> AppNavHost and AppScreenScaffold
```

An account-runtime update enters only the lightweight assembly stage. It cannot reach configuration decode or Android Keystore.

## Concurrency and Lifecycle

- Android Keystore load, configuration decode, and redacted projection creation run on `Dispatchers.IO`.
- Shared flows live in the existing singleton repository scope backed by `SupervisorJob() + Dispatchers.IO`.
- Compose receives immutable, credential-redacted `LegacyProvider` values.
- The key-handle cache is safe under concurrent encryption/decryption calls.
- No process-global scope or new always-running worker is introduced.
- Repository write methods and one-shot typed snapshot resolution retain their current transactional and dispatcher behavior; this design changes the public observation path only.

## Error and Security Behavior

- Keystore or decryption failures remain `CredentialDecryptionException` failures with the existing user-actionable message.
- A failed decode must not publish a partially populated or plaintext provider.
- Shared replay state contains no password, Jellyfin token, or decrypted encrypted JSON.
- Logs and ADB validation output must remain sanitized and must not include provider URLs, usernames, headers, device identifiers, tokens, or passwords.
- Debug StrictMode remains enabled so regressions continue to be visible.
- The implementation must not catch cancellation as a provider decode failure.

## Testing Strategy

### Data unit tests

Add focused tests proving:

- One configuration emission decodes each configured provider once.
- Account-runtime-only emissions update runtime fields without additional codec calls.
- Stalker-learning-only emissions update learned fields without additional codec calls.
- Multiple simultaneous `getProviders()` and `getActiveProvider()` collectors share one upstream decode pass.
- A changed configuration generation or encrypted payload triggers exactly one new decode.
- Removed configurations are removed from the projection cache/replay output.
- Provider order and active-provider selection remain unchanged.
- Every emitted public provider has a blank password/credential field.
- A decode exception terminates the emission without exposing a partial provider.

Use fake or mocked DAO flows and a counting codec/projection dependency. Tests must control flow emissions explicitly rather than relying on timing delays.

### Crypto unit tests

Add JVM tests for the internal key-handle cache proving:

- Sequential requests call the loader once and return the same handle.
- Concurrent requests call the loader once.
- A loader exception is propagated and is not cached, allowing a later request to retry.

Existing credential error-message coverage remains.

### App navigation tests

Extend destination tests to prove:

- Split and unified catalog layouts preserve the existing route behavior.
- Destination construction is driven entirely by supplied state.
- The default composition-local destination list keeps isolated scaffold and golden tests working.
- `AppNavigation` forwards one consistent destination list to both app-shell and settings consumers.

No visual golden should change because the destination contents and order remain unchanged.

### Build and regression tests

Run the focused data and app unit-test tasks first, then the repository-wide debug unit tests and debug APK assembly. Existing provider configuration migration, setup, sync, navigation, and shell tests must remain green.

## Emulator Performance Validation

Use the same connected emulator and debug package for before/after comparison.

1. Install and launch the new debug APK.
2. Clear logcat and reset `dumpsys gfxinfo com.streamvault.app.debug` counters immediately before the scenario.
3. Exercise the same navigation and playback path that previously became laggy, while allowing provider runtime/background updates to occur.
4. Capture StrictMode, Choreographer, process CPU, `/proc/<pid>/status`, `dumpsys meminfo`, and `dumpsys gfxinfo` evidence.
5. Keep URLs and credential-bearing values out of saved or reported logs.

The fix passes when:

- No StrictMode stack contains `CredentialCrypto`, Android Keystore, or `ProviderConfigurationCodec.decode()` on the main thread.
- Runtime and Stalker state updates produce no repeated provider configuration decode in diagnostic logs/tests.
- No provider/Keystore main-thread event exceeds 100 ms.
- The repeated scenario shows materially fewer skipped and janky frames than the captured 16.55 percent baseline; emulator-wide rendering noise is reported separately rather than attributed to the app.
- Process RSS and thread count show no monotonic growth during a minimum five-minute observation.
- There is no ANR, OOM, fatal player error, or behavioral regression in provider selection, navigation layout, setup, sync, or playback startup.

If meaningful jank remains after these criteria pass, collect a fresh trace and treat preload/thumbnail behavior as a separate diagnosis. It must not be bundled into this fix without evidence.

## Expected Code Organization

### Data module

- `ProviderPublicProjection.kt`: private/internal redacted configuration projection and lightweight identity/runtime/learning assembly.
- `ProviderRepositoryImpl.kt`: stable lazy shared flows and repository API delegation.
- `CredentialCrypto.kt`: Android implementation using the cached key provider.
- `CachingSecretKeyProvider.kt`: testable synchronized key-handle cache.
- Data repository and security tests covering emission and cache behavior.

### App module

- `AppNavigation.kt`: derive destination items from collected coordinator state and provide them to descendants.
- `AppNavHost.kt`: consume the supplied shared destination list and forward it to settings.
- `AppShellNavigation.kt`: pure destination construction plus composition-local consumption; no activity/repository lookup.
- Existing navigation and shell tests expanded for the state-driven path.

### Documentation

- Add a concise changelog entry describing removal of repeated main-thread provider decryption after implementation and validation.
- Record emulator measurements in the implementation handoff; do not commit raw credential-bearing logcat output.

## Delivery Sequence

1. Add the testable Keystore key-handle cache and crypto tests.
2. Add redacted provider projection and assembly code with pure unit tests.
3. Replace cold repository flows with lazily shared provider and active-provider streams; add multi-collector and emission-frequency tests.
4. Remove repository collection from app-shell composables and route coordinator state through navigation; add app tests.
5. Run focused and repository-wide tests, assemble/install the debug APK, and perform ADB comparison validation.
6. Update the changelog only after the measured acceptance criteria pass.

Each step must preserve a buildable, testable state and must be committed independently during implementation.
