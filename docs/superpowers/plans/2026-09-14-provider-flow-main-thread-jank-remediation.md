# Provider Flow Main-Thread Jank Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate repeated provider configuration decryption from the Android main thread while preserving provider behavior and proving the improvement on the connected emulator.

**Architecture:** Add a synchronized process-local Android Keystore key-handle cache, move one-shot snapshot reads/decode to IO, and keep stable cold provider flows whose expensive credential-redacted projections are memoized by the exact stored configuration inputs. Move app-shell destination construction to `AppNavigation` state so Compose no longer creates repository collectors through `MainActivity` lookups. Cold flows preserve the repository's existing exception, completion, and `.first()` behavior; the redacted cache removes repeated Keystore work across collectors and runtime-only updates.

**Tech Stack:** Kotlin, Kotlin Coroutines Flow, Room, Android Keystore, Hilt, Jetpack Compose, Mockito-Kotlin, Truth, Gradle, ADB.

**Spec:** `docs/superpowers/specs/2026-09-14-provider-flow-main-thread-jank-design.md`

## Global Constraints

- Preserve the `ProviderRepository` interface, provider behavior, Room schema, and credential-at-rest format.
- Provider observation and one-shot snapshot Keystore/decode work, plus redacted projection creation, run on an injected IO dispatcher whose production value is `Dispatchers.IO`.
- Cached provider state contains no password, Jellyfin token, or decrypted encrypted JSON.
- `first()` callers must wait for a real Room-backed emission; do not introduce an artificial empty provider list or null active provider.
- Runtime and Stalker-learning updates must not invoke `ProviderConfigurationCodec.decode()`.
- Decode failures must reach the collecting caller and must not be cached; a later collection or changed row must be able to retry.
- Keep debug StrictMode enabled and sanitize all logs and ADB artifacts.
- Do not change Media3 preload, thumbnail extraction, or playback behavior in this fix.
- Do not overwrite the user's existing `docs/CHANGELOG.md` edits or restore `docs/upgrade.txt`.
- Run `graphify update .` after production code files are modified.

---

## File Map

| File | Responsibility |
| --- | --- |
| `data/src/main/java/com/streamvault/data/security/CachingSecretKeyProvider.kt` | Thread-safe, testable cache for the Android Keystore `SecretKey` handle. |
| `data/src/main/java/com/streamvault/data/security/CredentialCrypto.kt` | Use the cached key handle while preserving AES-GCM and error behavior. |
| `data/src/test/java/com/streamvault/data/security/CachingSecretKeyProviderTest.kt` | Sequential, concurrent, and failure behavior for key-handle caching. |
| `data/src/main/java/com/streamvault/data/provider/ProviderPublicProjection.kt` | Decode configurations into redacted templates and assemble identity/runtime/learning state without re-decoding. |
| `data/src/test/java/com/streamvault/data/provider/ProviderPublicProjectionTest.kt` | Projection redaction, type validation, runtime overlay, and Stalker-learning coverage. |
| `data/src/main/java/com/streamvault/data/provider/ProviderObservationStreams.kt` | Build stable cold provider flows backed by a synchronized redacted-projection cache. |
| `data/src/test/java/com/streamvault/data/provider/ProviderObservationStreamsTest.kt` | Decode-frequency, concurrent-collector, first-emission, ordering, retry, and failure-propagation tests. |
| `data/src/main/java/com/streamvault/data/repository/ProviderRepositoryImpl.kt` | Lazily create one stable pair of cold observation-flow objects and return them from the existing interface methods. |
| `data/src/main/java/com/streamvault/data/provider/RoomProviderSnapshotRepository.kt` | Run the complete one-shot provider snapshot read/decode/assembly path on IO. |
| `data/src/test/java/com/streamvault/data/provider/RoomProviderSnapshotRepositoryTest.kt` | Verify snapshot decode dispatching, credentials, missing rows, and type mismatch behavior. |
| `app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt` | Derive destination items from collected coordinator state and provide them to descendants. |
| `app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt` | Consume the shared destination list and pass it into the settings graph. |
| `app/src/main/java/com/streamvault/app/ui/components/shell/AppShellNavigation.kt` | Pure destination construction and composition-local consumption; no activity/repository access. |
| `app/src/test/java/com/streamvault/app/ui/components/shell/AppShellNavigationTest.kt` | State-driven destination behavior and unchanged route ordering. |
| `docs/CHANGELOG.md` | Read only; it already has user edits. Provide suggested release-note text in the handoff instead of modifying or staging it. |

## Interfaces Produced for Later Tasks

Task 1 produces:

~~~kotlin
internal class CachingSecretKeyProvider(
    private val loadOrCreate: () -> SecretKey
) {
    fun get(): SecretKey
}
~~~

Task 2 produces:

~~~kotlin
internal data class RedactedProviderConfigurationProjection(
    val providerType: ProviderType,
    val configurationGeneration: Long,
    val providerTemplate: LegacyProvider
)

internal interface ProviderPublicProjection {
    fun decode(entity: ProviderConfigEntity): RedactedProviderConfigurationProjection

    fun assemble(
        identity: ProviderEntity,
        configuration: RedactedProviderConfigurationProjection?,
        runtime: ProviderAccountRuntimeEntity?,
        portalState: StalkerPortalStateEntity?
    ): LegacyProvider
}

~~~

The production implementation is `DefaultProviderPublicProjection(codec, gson)`; tests can provide a counting implementation of the interface.

Task 3 produces:

~~~kotlin
internal class ProviderObservationStreams(
    providerDao: ProviderDao,
    providerSnapshotDao: ProviderSnapshotDao,
    projection: ProviderPublicProjection,
    workDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val providers: Flow<List<LegacyProvider>>
    val activeProvider: Flow<LegacyProvider?>
}
~~~

---

### Task 1: Cache the Android Keystore Key Handle

**Files:**

- Create: `data/src/main/java/com/streamvault/data/security/CachingSecretKeyProvider.kt`
- Modify: `data/src/main/java/com/streamvault/data/security/CredentialCrypto.kt:31-105`
- Create: `data/src/test/java/com/streamvault/data/security/CachingSecretKeyProviderTest.kt`
- Test: `data/src/test/java/com/streamvault/data/security/CredentialCryptoTest.kt`

**Interfaces:**

- Consumes: The existing `CredentialCrypto` contract and Android Keystore loader body.
- Produces: `CachingSecretKeyProvider.get()` for both `encryptIfNeeded()` and `decryptIfNeeded()`.

- [ ] **Step 1: Write the failing cache tests.**

Create tests in the existing `com.streamvault.data.security` package. Use `SecretKeySpec(byteArrayOf(1, 2, 3, 4), "AES")`, an `AtomicInteger`, and a loader lambda so the tests do not access Android Keystore:

~~~kotlin
@Test
fun `sequential gets load the key once`() {
    val loads = AtomicInteger()
    val key = SecretKeySpec(byteArrayOf(1, 2, 3, 4), "AES")
    val provider = CachingSecretKeyProvider {
        loads.incrementAndGet()
        key
    }

    assertThat(provider.get()).isSameInstanceAs(key)
    assertThat(provider.get()).isSameInstanceAs(key)
    assertThat(loads.get()).isEqualTo(1)
}

@Test
fun `concurrent gets load the key once`() {
    val loads = AtomicInteger()
    val key = SecretKeySpec(byteArrayOf(1, 2, 3, 4), "AES")
    val provider = CachingSecretKeyProvider {
        loads.incrementAndGet()
        Thread.sleep(10)
        key
    }

    val executor = Executors.newFixedThreadPool(8)
    try {
        val results = (1..16).map {
            executor.submit<SecretKey> { provider.get() }
        }.map { it.get() }
        assertThat(results).containsExactlyElementsIn(List(16) { key })
        assertThat(loads.get()).isEqualTo(1)
    } finally {
        executor.shutdownNow()
    }
}

@Test
fun `loader failure is not cached`() {
    val loads = AtomicInteger()
    val key = SecretKeySpec(byteArrayOf(1, 2, 3, 4), "AES")
    val provider = CachingSecretKeyProvider {
        if (loads.incrementAndGet() == 1) error("keystore unavailable")
        key
    }

    assertThrows<IllegalStateException> { provider.get() }
    assertThat(provider.get()).isSameInstanceAs(key)
    assertThat(loads.get()).isEqualTo(2)
}
~~~

- [ ] **Step 2: Run the focused test to confirm the cache does not exist yet.**

Run:

~~~powershell
.\gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.security.CachingSecretKeyProviderTest"
~~~

Expected: compilation failure because `CachingSecretKeyProvider` is not defined.

- [ ] **Step 3: Implement the synchronized cache.**

Create the class with a volatile fast path and synchronized initialization. Do not cache failures:

~~~kotlin
internal class CachingSecretKeyProvider(
    private val loadOrCreate: () -> SecretKey
) {
    @Volatile
    private var cached: SecretKey? = null

    fun get(): SecretKey {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: loadOrCreate().also { cached = it }
        }
    }
}
~~~

- [ ] **Step 4: Wire the production crypto implementation.**

In `AndroidKeystoreCredentialCrypto`, replace the private `getOrCreateSecretKey()` call sites with a field-backed provider:

~~~kotlin
private val secretKeyProvider = CachingSecretKeyProvider(::loadOrCreateSecretKey)

// encryptIfNeeded and decryptIfNeeded both call:
secretKeyProvider.get()
~~~

Rename the current keystore body to `private fun loadOrCreateSecretKey(): SecretKey`. Keep the alias, transformation, IV size, GCM tag size, error wrapping, and logging unchanged. Do not cache a `Cipher` or plaintext credential.

- [ ] **Step 5: Run crypto tests.**

Run:

~~~powershell
.\gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.security.CachingSecretKeyProviderTest" --tests "com.streamvault.data.security.CredentialCryptoTest"
~~~

Expected: all focused security tests pass.

- [ ] **Step 6: Update graphify after the production security change.**

~~~powershell
graphify update .
~~~

Expected: the graph updates successfully. If the CLI is unavailable, record the limitation and continue with the verified tests.

- [ ] **Step 7: Commit the isolated security change.**

~~~powershell
git add data/src/main/java/com/streamvault/data/security/CachingSecretKeyProvider.kt data/src/main/java/com/streamvault/data/security/CredentialCrypto.kt data/src/test/java/com/streamvault/data/security/CachingSecretKeyProviderTest.kt data/src/test/java/com/streamvault/data/security/CredentialCryptoTest.kt
git commit -m "perf: cache credential keystore key handle"
~~~

### Task 2: Build a Redacted Configuration Projection

**Files:**

- Create: `data/src/main/java/com/streamvault/data/provider/ProviderPublicProjection.kt`
- Create: `data/src/test/java/com/streamvault/data/provider/ProviderPublicProjectionTest.kt`
- Reference: `data/src/main/java/com/streamvault/data/provider/ProviderConfigRevisionCodec.kt:104-254`
- Reference: `data/src/main/java/com/streamvault/data/repository/ProviderRepositoryImpl.kt:2059-2070`

**Interfaces:**

- Consumes: `ProviderConfigurationCodec`, `ProviderConfigEntity`, `ProviderEntity`, `ProviderAccountRuntimeEntity`, `StalkerPortalStateEntity`, and the existing `toLegacyProvider()`/learning mappers.
- Produces: the `ProviderPublicProjection` interface, `DefaultProviderPublicProjection`, and the redacted projection model consumed by Task 3.

- [ ] **Step 1: Write projection tests before implementation.**

Use a real `ProviderConfigurationCodec` with a fake `CredentialCrypto` that returns values unchanged and counts decrypt calls. Build one Xtream `ProviderConfigEntity` with a non-empty password and one Stalker entity with a generation-valid learning row. Cover these assertions:

~~~kotlin
@Test
fun `decode returns a redacted template and preserves public configuration fields`() {
    val projection = defaultPublicProjection()

    val decoded = projection.decode(xtreamEntity(password = "secret"))

    assertThat(decoded.providerType).isEqualTo(ProviderType.XTREAM_CODES)
    assertThat(decoded.configurationGeneration).isEqualTo(7L)
    assertThat(decoded.providerTemplate.serverUrl).isEqualTo("https://example.test")
    assertThat(decoded.providerTemplate.username).isEqualTo("user")
    assertThat(decoded.providerTemplate.password).isEmpty()
}

@Test
fun `assemble overlays identity runtime and generation-valid Stalker learning`() {
    val result = defaultPublicProjection().assemble(
        identity = providerEntity(id = 9L, name = "Living room"),
        configuration = stalkerProjection(generation = 4L),
        runtime = ProviderAccountRuntimeEntity(
            providerId = 9L,
            maxConnections = 3,
            catalogLayout = CatalogLayout.UNIFIED_VOD,
            catalogLayoutDetectionVersion = 2
        ),
        portalState = generationValidPortalState(providerId = 9L, generation = 4L)
    )

    assertThat(result.name).isEqualTo("Living room")
    assertThat(result.maxConnections).isEqualTo(3)
    assertThat(result.catalogLayout).isEqualTo(CatalogLayout.UNIFIED_VOD)
    assertThat(result.stalkerConfigurationGeneration).isEqualTo(4L)
    assertThat(result.password).isEmpty()
}
~~~

Also test a type mismatch, missing configuration, stale Stalker learning, and a projection whose configuration contains a credential. Add table-driven characterization cases for Xtream, M3U, Stalker, and Jellyfin that compare `assemble()` with the existing `ProviderSnapshot.toLegacyProvider().redactedCredentials()` result before the old repository body is removed. Compare the complete `LegacyProvider`, not a selected field subset, so newly added configuration/runtime/learning fields cannot be silently dropped. The public output must never expose a password or Jellyfin credential.

- [ ] **Step 2: Run the new tests to establish the missing implementation.**

Run:

~~~powershell
.\gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.provider.ProviderPublicProjectionTest"
~~~

Expected: compilation failure because the projection types do not exist.

- [ ] **Step 3: Implement redacted configuration decoding.**

Define `ProviderPublicProjection` as the small internal interface shown above and implement it in `DefaultProviderPublicProjection`. This makes Task 3 tests count `decode()` directly instead of treating crypto calls as a proxy for decode passes.

Create `RedactedProviderConfigurationProjection` with `providerType`, `configurationGeneration`, and a redacted `LegacyProvider providerTemplate`. In `decode(entity)`, call the existing codec exactly once, convert the typed configuration through the current `ProviderConfiguration.toLegacyProvider()` mapper using a private non-observable identity template, and immediately clear `password` with the existing redaction rule. Retain only the redacted `LegacyProvider` template and generation after the method returns.

The private identity template must use the stored provider ID and type plus a non-empty internal name because `StableProvider` validates the name. `assemble()` must overwrite all identity fields before returning, so the template name never reaches callers.

- [ ] **Step 4: Implement lightweight assembly.**

`assemble()` must preserve the current `ProviderSnapshot.toLegacyProvider()` behavior:

1. Return `identity.toPublicDomain()` when no configuration projection exists.
2. Reject a projection whose `providerType` differs from `identity.type`, matching the existing snapshot type invariant.
3. Copy identity fields (`id`, `name`, `type`, `isActive`, `status`, `lastSyncedAt`, `createdAt`) onto the redacted template.
4. Overlay runtime fields (`maxConnections`, `expirationDate`, `apiVersion`, `allowedOutputFormats`, `catalogLayout`, and `catalogLayoutDetectionVersion`) from `ProviderAccountRuntimeEntity.toDomainRuntime()`, defaulting to `ProviderAccountRuntime()` when absent.
5. For Stalker providers, call `toGenerationValidLearning(gson, configurationGeneration)` and copy the existing generation, learned-profile, portal-profile, fingerprint, MAG preset, protocol-family, bootstrap-recipe, endpoint-preference, cookie-mode, playback-backend, and last-playback fields. Do not apply learning from another generation.
6. Keep the password/credential field blank after every copy operation.

Move the current private `ProviderEntity.toPublicDomain()` and `Provider.redactedCredentials()` helpers out of `ProviderRepositoryImpl.kt` into this focused file as `internal` helpers, delete the old private copies, and preserve their behavior exactly. Import the new helpers where the repository still uses them.

- [ ] **Step 5: Run projection tests and inspect the public-output assertions.**

Run:

~~~powershell
.\gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.provider.ProviderPublicProjectionTest"
~~~

Expected: all projection tests pass, including redaction and generation validation.

- [ ] **Step 6: Update graphify after the production projection change.**

~~~powershell
graphify update .
~~~

Expected: the graph updates successfully. If the CLI is unavailable, record the limitation and continue with the verified tests.

- [ ] **Step 7: Commit the projection layer.**

~~~powershell
git add data/src/main/java/com/streamvault/data/provider/ProviderPublicProjection.kt data/src/test/java/com/streamvault/data/provider/ProviderPublicProjectionTest.kt data/src/main/java/com/streamvault/data/repository/ProviderRepositoryImpl.kt
git commit -m "perf: add redacted provider configuration projection"
~~~

### Task 3: Memoize Redacted Projections While Preserving Cold-Flow Semantics

**Files:**

- Create: `data/src/main/java/com/streamvault/data/provider/ProviderObservationStreams.kt`
- Create: `data/src/test/java/com/streamvault/data/provider/ProviderObservationStreamsTest.kt`
- Modify: `data/src/main/java/com/streamvault/data/repository/ProviderRepositoryImpl.kt:158-206`
- Reference: `data/src/main/java/com/streamvault/data/local/dao/ProviderSnapshotDao.kt:15-31`

**Interfaces:**

- Consumes: `ProviderPublicProjection` from Task 2 and the existing DAO flows.
- Produces: stable cold `Flow<List<LegacyProvider>>` and `Flow<LegacyProvider?>` objects returned by the unchanged `ProviderRepository` methods.

- [ ] **Step 1: Write controlled-flow tests.**

Use `MutableSharedFlow` or `MutableStateFlow` instances for identities, configs, runtimes, and portal states. Stub the small `ProviderPublicProjection` interface with a thread-safe fake that delegates assembly but increments a counter in `decode()`. Pass a `StandardTestDispatcher(testScheduler)` as `workDispatcher` so ordinary flow behavior is deterministic. Add one dedicated concurrency test using `Dispatchers.Default` and latches: hold the first decode after it enters, start the remaining collectors, release it, and assert all collectors complete with only one decode. Do not use timing sleeps to prove the race.

Cover the following test shape:

~~~kotlin
val identities = MutableSharedFlow<List<ProviderEntity>>(replay = 1)
val configs = MutableSharedFlow<List<ProviderConfigEntity>>(replay = 1)
val runtimes = MutableSharedFlow<List<ProviderAccountRuntimeEntity>>(replay = 1)
val portalStates = MutableSharedFlow<List<StalkerPortalStateEntity>>(replay = 1)
val streams = ProviderObservationStreams(
    providerDao = fakeProviderDao(identities),
    providerSnapshotDao = fakeSnapshotDao(configs, runtimes, portalStates),
    projection = countingProjection,
    workDispatcher = StandardTestDispatcher(testScheduler)
)

val first = async { streams.providers.first() }
emitInitialRows(identities, configs, runtimes, portalStates)
assertThat(first.await().single().password).isEmpty()
assertThat(countingProjection.decodeCalls.get()).isEqualTo(1)
~~~

Add tests that:

- Emit a runtime-only change and assert provider runtime fields change while `decodeCalls` remains `1`.
- Emit a Stalker-learning-only change and assert learning fields change while `decodeCalls` remains `1`.
- Collect `providers` twice and `activeProvider` twice concurrently, then emit one configuration list and assert one decode pass despite the separate cold Room subscriptions.
- Emit a changed generation/payload and assert exactly one additional decode.
- Emit the same provider ID with a changed encrypted payload but unchanged generation and assert one additional decode; generation alone is not a sufficient cache key.
- Remove a configuration row and assert the final provider uses the same public fallback as before; re-add the identical row and assert it decodes again, proving removal evicted the cache entry.
- Assert identity order and active-provider selection remain unchanged.
- Start `first()` before the first DAO emission and assert it completes only after real rows are emitted.
- Throw from `decode()` and assert the exact exception reaches each collecting caller, no partially assembled provider is published, and a later successful emission/collection retries rather than reusing a failed entry.
- Complete a DAO flow and assert completion remains observable to the collector. This specifically guards against accidentally replacing the cold flows with `shareIn`/`stateIn`.

- [ ] **Step 2: Run the observation tests before implementation.**

Run:

~~~powershell
.\gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.provider.ProviderObservationStreamsTest"
~~~

Expected: compilation failure because the observation-stream class does not exist.

- [ ] **Step 3: Implement the synchronized redacted-projection cache.**

Keep the cache private to `ProviderObservationStreams`. Compare the complete immutable `ProviderConfigEntity` row rather than maintaining a hand-written subset of fields that could drift when the entity changes:

~~~kotlin
private data class CachedProjection(
    val source: ProviderConfigEntity,
    val value: RedactedProviderConfigurationProjection
)
~~~

Implement one synchronized `resolveConfigurationRows(rows)` critical section that:

1. Reuses an entry only when `cached.source == row`, covering type, schema version, generation, payload, policy fields, identity key, and timestamp automatically.
2. Calls `projection.decode(row)` once for a new or changed row.
3. Publishes a newly decoded value to the cache only after decode succeeds.
4. Evicts provider IDs absent from the latest Room row list.
5. Returns an immutable map for assembly.

The cache may retain only `RedactedProviderConfigurationProjection`; never retain a decoded typed configuration, plaintext password/token, or raw decrypted JSON. Do not swallow decode exceptions and do not install a failed/partial entry.

- [ ] **Step 4: Implement stable cold provider and active-provider flows.**

Build each flow object once, but leave it cold. The expensive transform runs on the injected dispatcher and consults the synchronized cache, so multiple collectors may create cheap Room subscriptions without repeating decryption:

~~~kotlin
private val publicConfigurations: Flow<Map<Long, RedactedProviderConfigurationProjection>> =
    providerSnapshotDao.observeConfigs()
        .distinctUntilChanged()
        .map(::resolveConfigurationRows)

val providers: Flow<List<LegacyProvider>> =
    combine(
        providerDao.getAll(),
        publicConfigurations,
        providerSnapshotDao.observeRuntimes(),
        providerSnapshotDao.observeStalkerPortalStates()
    ) { identities, configurations, runtimes, portalStates ->
        val runtimeByProvider = runtimes.associateBy { it.providerId }
        val portalStateByProvider = portalStates.associateBy { it.providerId }
        identities.map { identity ->
            projection.assemble(
                identity = identity,
                configuration = configurations[identity.id],
                runtime = runtimeByProvider[identity.id],
                portalState = portalStateByProvider[identity.id]
            )
        }
    }.flowOn(workDispatcher)

val activeProvider: Flow<LegacyProvider?> =
    providers
        .map { list -> list.firstOrNull(LegacyProvider::isActive) }
        .distinctUntilChanged()
~~~

Do not use `shareIn` or `stateIn`: their sharing coroutine owns upstream failure/completion, so public collectors would no longer receive the same exception/completion behavior as the existing cold repository flows. Do not add an initial value.

- [ ] **Step 5: Delegate the repository API to the stable cold flow objects.**

Add a lazy field in `ProviderRepositoryImpl` so existing JVM tests can stub DAO flows before construction of the observation stream:

~~~kotlin
private val observationStreams: ProviderObservationStreams by lazy {
    ProviderObservationStreams(
        providerDao = providerDao,
        providerSnapshotDao = providerSnapshotDao,
        projection = DefaultProviderPublicProjection(providerConfigurationCodec, gson)
    )
}

override fun getProviders(): Flow<List<Provider>> = observationStreams.providers

override fun getActiveProvider(): Flow<Provider?> = observationStreams.activeProvider
~~~

Remove the old `combine`/decode body from these methods. Preserve all one-shot repository methods; Task 4 separately fixes the dedicated `RoomProviderSnapshotRepository` path observed in the ADB StrictMode trace.

- [ ] **Step 6: Run data regression tests.**

Run:

~~~powershell
.\gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.provider.ProviderPublicProjectionTest" --tests "com.streamvault.data.provider.ProviderObservationStreamsTest" --tests "com.streamvault.data.repository.ProviderRepositoryImplTest"
~~~

Expected: the new flow tests pass and existing provider setup, update, sync, and migration tests remain green.

- [ ] **Step 7: Update the graph after production data code changes.**

Run:

~~~powershell
graphify update .
~~~

Expected: the graph updates without changing unrelated user files. If the CLI remains unavailable, record that verification limitation and continue with Gradle tests.

- [ ] **Step 8: Commit the memoized observation change.**

~~~powershell
git add data/src/main/java/com/streamvault/data/provider/ProviderObservationStreams.kt data/src/main/java/com/streamvault/data/repository/ProviderRepositoryImpl.kt data/src/test/java/com/streamvault/data/provider/ProviderObservationStreamsTest.kt
git commit -m "perf: memoize provider observation decoding"
~~~

### Task 4: Move One-Shot Snapshot Decode to IO

**Files:**

- Modify: `data/src/main/java/com/streamvault/data/provider/RoomProviderSnapshotRepository.kt:17-55`
- Create: `data/src/test/java/com/streamvault/data/provider/RoomProviderSnapshotRepositoryTest.kt`

**Interfaces:**

- Consumes: existing DAOs, `ProviderConfigurationCodec`, and an injectable internal `CoroutineDispatcher` defaulting to `Dispatchers.IO`.
- Produces: unchanged `ProviderSnapshotRepository` behavior with the complete read/decode/assembly operation confined to IO.

- [ ] **Step 1: Add one-shot snapshot regression tests.**

Instantiate the repository with recording fake DAOs, a real codec backed by a recording fake `CredentialCrypto`, and a dedicated `StandardTestDispatcher`. Assert that:

- `getSnapshot()` reaches neither the recording DAOs nor crypto until the supplied worker dispatcher is advanced, proving that the complete operation is dispatched instead of inheriting the caller context.
- A valid snapshot still returns the full typed configuration, including operational credentials; this path must not use the redacted public-projection cache.
- A missing provider or configuration still returns `null`.
- A provider/configuration type mismatch still throws the existing `IllegalStateException`.
- Runtime and generation-valid Stalker learning are assembled exactly as before.

- [ ] **Step 2: Run the new test before implementation.**

~~~powershell
.\gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.provider.RoomProviderSnapshotRepositoryTest"
~~~

Expected: the dispatch assertion fails because `getSnapshot()` currently inherits the caller context.

- [ ] **Step 3: Inject a dispatcher and wrap the whole operation.**

Keep the Hilt-visible `@Inject` constructor signature so no dispatcher binding is required. Move the dependencies plus `workDispatcher` to an internal primary constructor, then add an `@Inject` secondary constructor with the existing dependency list that delegates with `Dispatchers.IO`. Tests call the internal primary constructor with `StandardTestDispatcher`. Wrap the entire `getSnapshot()` body in `withContext(workDispatcher)`, including all DAO reads, type validation, Stalker-learning conversion, codec decode, and snapshot assembly:

~~~kotlin
override suspend fun getSnapshot(providerId: Long): ProviderSnapshot? =
    withContext(workDispatcher) {
        // existing read, validation, decode, and assembly body
    }
~~~

Do not cache this result or redact it: sync/playback callers require the typed credentials. Preserve cancellation and exception propagation.

- [ ] **Step 4: Run snapshot and provider data tests.**

~~~powershell
.\gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.provider.RoomProviderSnapshotRepositoryTest" --tests "com.streamvault.data.repository.ProviderRepositoryImplTest"
~~~

Expected: PASS with the dispatcher assertion and existing provider behavior intact.

- [ ] **Step 5: Update graphify and commit the one-shot fix.**

~~~powershell
graphify update .
git add data/src/main/java/com/streamvault/data/provider/RoomProviderSnapshotRepository.kt data/src/test/java/com/streamvault/data/provider/RoomProviderSnapshotRepositoryTest.kt
git commit -m "perf: move provider snapshot decode to IO"
~~~

If `graphify` is unavailable, record that limitation and continue with the verified Gradle results.

### Task 5: Remove Repository Collection from App-Shell Composables

**Files:**

- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt:19-61`
- Modify: `app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt:38-55,128-170`
- Modify: `app/src/main/java/com/streamvault/app/ui/components/shell/AppShellNavigation.kt:1-130`
- Modify: `app/src/test/java/com/streamvault/app/ui/components/shell/AppShellNavigationTest.kt`
- Inspect: `app/src/test/java/com/streamvault/app/navigation/AppNavigationCoordinatorTest.kt` to confirm existing coordinator assertions still cover active-provider-driven state

**Interfaces:**

- Consumes: `AppNavigationState.topLevelDestinations`, `AppNavigationState.catalogLayout`, and the stable repository flow objects from Task 3.
- Produces: one app-owned destination list shared by `AppNavHost`, settings, live graphs, catalog graphs, and shell scaffolds.

- [ ] **Step 1: Add pure destination-state tests.**

Extend `AppShellNavigationTest` so route assertions call the pure builder with explicit `configured` and `layout` values. Add a default-state test and assert that the same destination input produces equal route IDs regardless of caller:

~~~kotlin
@Test
fun `destination construction is independent of Activity and repository state`() {
    val result = buildDestinationItems(
        configured = AppTopLevelDestination.defaultOrder,
        layout = CatalogLayout.SPLIT
    )

    assertThat(result.map { it.route })
        .containsExactly(
            Routes.HOME,
            Routes.LIVE_TV,
            Routes.MOVIES,
            Routes.SERIES,
            Routes.DOWNLOADS,
            Routes.EPG,
            Routes.SEARCH,
            Routes.PLUGINS,
            Routes.SETTINGS
        )
        .inOrder()
}
~~~

`AppTopLevelDestination` has no `route` property; keep this explicit route list so the test compiles and documents the current mapping, including `GUIDE -> Routes.EPG`.

- [ ] **Step 2: Run app shell tests before changing the composable.**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.streamvault.app.ui.components.shell.AppShellNavigationTest"
~~~

Expected: existing tests pass.

- [ ] **Step 3: Make destination construction pure.**

Change `rememberAppDestinationItems()` to accept the already known state and remove `LocalContext`, `MainActivity`, `findMainActivity()`, `collectAsStateWithLifecycle`, and `providerRepository.getActiveProvider()` from `AppShellNavigation.kt`:

~~~kotlin
@Composable
internal fun rememberAppDestinationItems(
    configuredDestinations: List<AppTopLevelDestination>,
    catalogLayout: CatalogLayout
): List<UiDestination> {
    val destinationItems = remember(configuredDestinations, catalogLayout) {
        buildDestinationItems(configuredDestinations, catalogLayout)
    }
    return destinationItems.map { item ->
        UiDestination(
            id = item.route,
            label = stringResource(item.labelRes),
            icon = item.icon
        )
    }
}
~~~

Keep `buildDestinationItems()` unchanged so split/unified route behavior remains identical.

- [ ] **Step 4: Add an app-owned composition local for shared destinations.**

Define a nullable local so isolated tests can use a pure default without touching the repository:

~~~kotlin
internal val LocalAppDestinationItems = staticCompositionLocalOf<List<UiDestination>?> { null }
~~~

Update `AppScreenScaffold` to use the provided list, falling back to `rememberAppDestinationItems(AppTopLevelDestination.defaultOrder, CatalogLayout.SPLIT)` only when rendered outside `AppNavigation` (for example, an isolated preview or test). The fallback must be pure and must not inspect `Context`, `MainActivity`, preferences, or provider repository.

- [ ] **Step 5: Provide one shared destination list from `AppNavigation`.**

After collecting `state`, call the pure composable builder once and wrap `AppNavHost` in `CompositionLocalProvider`:

~~~kotlin
val navigationDestinations = rememberAppDestinationItems(
    configuredDestinations = state.topLevelDestinations,
    catalogLayout = state.catalogLayout ?: CatalogLayout.SPLIT
)

CompositionLocalProvider(LocalAppDestinationItems provides navigationDestinations) {
    AppNavHost(
        navigationDestinations = navigationDestinations,
        // existing arguments
    )
}
~~~

Add `navigationDestinations: List<UiDestination>` to `AppNavHost` and pass that exact list to `registerSettingsGraph`. Remove the old no-argument `rememberAppDestinationItems()` call from `AppNavHost`.

- [ ] **Step 6: Run navigation and shell tests.**

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.streamvault.app.ui.components.shell.AppShellNavigationTest" --tests "com.streamvault.app.navigation.AppNavigationCoordinatorTest"
~~~

Expected: route behavior and coordinator tests pass.

- [ ] **Step 7: Run app golden tests that exercise `AppScreenScaffold`.**

~~~powershell
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.ui.components.shell.ShellGoldenTest' --console=plain
.\gradlew.bat :app:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.app.ui.PremiumRouteGoldenTest' --console=plain
~~~

Expected: screenshots remain visually unchanged. Confirm the exact current test class names with `rg` before running; review generated diffs if a harness needs to provide `LocalAppDestinationItems` explicitly.

- [ ] **Step 8: Update graphify after the production navigation change.**

~~~powershell
graphify update .
~~~

Expected: the graph updates successfully. If the CLI is unavailable, record the limitation and continue with the verified tests.

- [ ] **Step 9: Commit the navigation state change.**

~~~powershell
git add app/src/main/java/com/streamvault/app/navigation/AppNavigation.kt app/src/main/java/com/streamvault/app/navigation/AppNavHost.kt app/src/main/java/com/streamvault/app/ui/components/shell/AppShellNavigation.kt app/src/test/java/com/streamvault/app/ui/components/shell/AppShellNavigationTest.kt
git commit -m "perf: remove provider flow collection from app shell"
~~~

### Task 6: Run Full Automated Regression and Assemble the Debug APK

**Files:**

- Modify: none unless a test identifies a regression in files from Tasks 1–5.
- Inspect only: `docs/CHANGELOG.md` so the final handoff can suggest placement without changing the user's edits.

**Interfaces:**

- Consumes: all production and test changes from Tasks 1–5.
- Produces: a tested debug APK and a clean verification record before emulator testing.

- [ ] **Step 1: Run focused data and app unit tests together.**

~~~powershell
.\gradlew.bat :data:testDebugUnitTest :app:testDebugUnitTest
~~~

Expected: PASS with no provider migration, setup, sync, navigation, or shell regressions.

- [ ] **Step 2: Run all repository debug unit tests.**

~~~powershell
.\gradlew.bat testDebugUnitTest
~~~

Expected: PASS across all modules. Diagnose an unrelated failure before continuing; do not weaken assertions.

- [ ] **Step 3: Assemble the debug APK.**

~~~powershell
.\gradlew.bat :app:assembleDebug
~~~

Expected: `app/build/outputs/apk/debug/app-debug.apk` exists and the build reports success.

- [ ] **Step 4: Audit every production configuration-decode call site.**

~~~powershell
rg -n "providerConfigurationCodec\.decode|codec\.decode\(" data/src/main/java -g "*.kt"
~~~

Expected: the public observation path is behind `flowOn(workDispatcher)`, `RoomProviderSnapshotRepository.getSnapshot()` is behind `withContext(workDispatcher)`, image-interceptor decoding is owned by an OkHttp worker thread, channel presentation decoding remains inside its existing worker-dispatched path, and provider update/persistence decoding remains transaction-dispatched. If any decode call can inherit a main-thread caller, add a focused dispatcher test and fix it before emulator validation rather than assuming the two observed stacks were exhaustive.

- [ ] **Step 5: Inspect the worktree before emulator installation.**

~~~powershell
git status --short
git diff --check
~~~

Expected: only the intended implementation and tests are present; existing `docs/CHANGELOG.md` and `docs/upgrade.txt` changes remain preserved and unstaged.

### Task 7: Validate the Fix on the Connected Emulator and Report the Result

**Files:**

- Modify: no repository files.
- Inspect only: `docs/CHANGELOG.md`; do not patch, stage, or commit it because it already contains user changes.
- Create outside the repository only: sanitized temporary ADB output under `$env:TEMP`.

**Interfaces:**

- Consumes: the assembled `app-debug.apk` and the single currently authorized ADB device selected at validation time.
- Produces: before/after performance evidence and suggested changelog text in the final handoff.

- [ ] **Step 1: Install and launch the debug APK.**

~~~powershell
$adb = 'C:\Users\David\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$serials = @(& $adb devices | Select-String "`tdevice$" | ForEach-Object { ($_ -split "`t")[0] })
if ($serials.Count -ne 1) { throw "Expected exactly one authorized ADB device, found $($serials.Count)" }
$serial = $serials[0]
& $adb -s $serial install -r .\app\build\outputs\apk\debug\app-debug.apk
& $adb -s $serial shell am force-stop com.streamvault.app.debug
& $adb -s $serial shell am start -n com.streamvault.app.debug/com.streamvault.app.MainActivity
~~~

Expected: exactly one authorized device is selected, install succeeds, `com.streamvault.app.debug` is resumed, and provider data remains available. If multiple devices are connected, stop and select the intended serial explicitly rather than guessing.

- [ ] **Step 2: Reset diagnostic counters and capture a clean baseline window.**

Before reproducing the scenario, clear logcat and reset gfxinfo counters:

~~~powershell
$adb = 'C:\Users\David\AppData\Local\Android\Sdk\platform-tools\adb.exe'
$validationDir = Join-Path $env:TEMP ("streamvault_jank_validation_{0}" -f (Get-Date -Format 'yyyyMMdd_HHmmss'))
New-Item -ItemType Directory -Path $validationDir | Out-Null
& $adb -s $serial logcat -c
& $adb -s $serial shell dumpsys gfxinfo com.streamvault.app.debug reset
~~~

Capture initial process status. Keep raw logcat only in the temporary directory, extract the narrow diagnostic patterns below to a separate report, and redact every URL, username, token, cookie, device identifier, MAC address, and password before reporting or copying anything into the repository.

- [ ] **Step 3: Exercise the same lag-producing workflow for at least five minutes.**

Use the emulator's existing navigation/playback scenario and allow background provider runtime updates to occur. Capture exactly 61 process samples at a 5-second cadence (roughly five minutes) with PID lookup, RSS, anonymous RSS, swap, thread count, and CPU. Timestamp each row and save it under a new `$env:TEMP\streamvault_jank_validation_<timestamp>` directory. Do not restart the app during the observation window. Poll in intervals short enough to keep the user updated; do not issue one blocking five-minute sleep.

Implement the sampler as a short reusable PowerShell function that appends CSV rows containing `timestamp`, `pid`, `VmRSS`, `RssAnon`, `VmSwap`, `Threads`, and the package's `dumpsys cpuinfo` line. Invoke it in batches of at most 12 samples, five seconds apart, until 61 total samples are recorded; use the pauses between batches for commentary updates. If `pidof` changes or returns empty, mark the run failed because the app restarted or exited.

If the reproduction includes live TV, additionally follow the repository's mandatory live-TV protocol: capture at least 45 screenshots at a 2-second cadence and prefer 61 (roughly two minutes), validate more than one live channel, compare image hashes for frame progression, confirm `media_session` remains `PLAYING` with `error=null`, and run the prescribed sanitized HLS/recovery/fatal-error log checks. Record channel names, screenshot count, interval, unique hash count, media-session state, and log findings.

- [ ] **Step 4: Check the main-thread stall signature.**

Run the following sanitized query against the captured log:

~~~powershell
& $adb -s $serial logcat -d -v time | Set-Content -Encoding utf8 (Join-Path $validationDir 'raw_logcat.txt')
rg -n "CredentialCrypto|ProviderConfigurationCodec|AndroidKeystore|StrictMode|Skipped [0-9]+ frames|ANR|OutOfMemory|FATAL EXCEPTION" (Join-Path $validationDir 'raw_logcat.txt')
~~~

Expected: no main-thread StrictMode stack contains Keystore, `CredentialCrypto`, or provider configuration decode. Runtime updates may still emit ordinary provider logs, but they must not trigger a decode stack.

- [ ] **Step 5: Collect final frame and process metrics.**

~~~powershell
& $adb -s $serial shell dumpsys gfxinfo com.streamvault.app.debug
& $adb -s $serial shell dumpsys meminfo -d com.streamvault.app.debug
& $adb -s $serial shell dumpsys cpuinfo
& $adb -s $serial shell dumpsys activity activities
~~~

Compare against the captured baseline: materially fewer janky/skipped frames than 16.55 percent, no main-thread provider/Keystore event over 100 ms, no ANR/OOM, and no monotonic RSS/thread growth. Report emulator rendering noise separately. Do not claim that all Keystore work disappeared: legitimate credential consumers can still decrypt on the IO dispatcher.

- [ ] **Step 6: If the provider signature is gone but lag remains, stop and diagnose preload separately.**

Do not modify preload or thumbnail code in this task. Capture a fresh stack or trace showing the remaining owner before opening a follow-up change.

- [ ] **Step 7: Prepare, but do not write, the changelog suggestion.**

Include this exact candidate in the final handoff after acceptance passes: `- Fixed progressive UI lag caused by repeated provider configuration decryption and Android Keystore work on the main thread.` Do not edit, stage, or commit `docs/CHANGELOG.md`; the user can choose how to integrate it with their current changes.

- [ ] **Step 8: Run graphify after the final production-code state and inspect status.**

~~~powershell
graphify update .
git diff --check
git status --short
~~~

Expected: graph is current, no whitespace errors, and only intended files plus the user's pre-existing unstaged `docs/CHANGELOG.md`/`docs/upgrade.txt` changes remain. Do not stage either user-owned path.

## Self-Review Checklist

- [ ] Every spec goal maps to Tasks 1–7: key cache, IO decode, redacted memoization, one-shot snapshot dispatch, stable cold flows, state-driven navigation, tests, and emulator validation.
- [ ] No task changes the public `ProviderRepository` interface or Room schema.
- [ ] The plan preserves `.first()`, upstream exception, and completion semantics by keeping the public flows cold and adding no initial value.
- [ ] Configuration decode is isolated to the projection stage; runtime and learning updates only assemble immutable copies.
- [ ] Plaintext credentials are not stored in the redacted projection cache; operational one-shot snapshots remain credential-complete and execute on IO.
- [ ] Navigation no longer calls `getActiveProvider()` from a composable.
- [ ] The test commands use the repository's Gradle module names and existing test classes.
- [ ] No raw credential-bearing logcat output is committed.
- [ ] `docs/CHANGELOG.md` and `docs/upgrade.txt` remain unstaged and untouched.
- [ ] No unresolved placeholder markers or unspecified implementation steps remain.
