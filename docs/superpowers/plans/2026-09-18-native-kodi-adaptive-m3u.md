# Native Kodi Adaptive M3U Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add native, restart-safe Kodi adaptive M3U support for remote-license Widevine/PlayReady/ClearKey and static ClearKey DASH playback.

**Architecture:** Parse an allowlisted directive set into typed M3U playback metadata, serialize it as versioned JSON on imported channel/movie rows, and enrich `StreamInfo` when repositories resolve playback. Remote license URLs continue through Media3's existing DRM configuration; static ClearKey uses a redacted domain value and a `LocalMediaDrmCallback` installed on the selected media-source factory.

**Tech Stack:** Kotlin, AndroidX Room, AndroidX Media3 1.11.0, OkHttp, `org.json`, JUnit4, Truth, Mockito-Kotlin, Robolectric, Android instrumentation tests.

**Spec:** `docs/superpowers/specs/2026-09-18-native-kodi-adaptive-m3u-design.md`

## Global Constraints

- Accept only the Kodi/EXTVLC directives listed in the spec; continue ignoring unknown directives.
- Accept only HTTP(S) remote DRM license URLs.
- Static keys are legal only for ClearKey and every decoded KID/key must be exactly 16 bytes.
- Never include a raw key, full license URL query, or sensitive header value in logs, `toString()`, media IDs, or sync fingerprints.
- Invalid DRM metadata must not reject an otherwise valid playlist entry or provider import.
- Existing plugin DRM, non-M3U providers, non-DRM playlists, recording, Cast, preload, and timeshift behavior must remain compatible.
- Production behavior changes must follow red-green-refactor: run each named test and observe its expected failure before adding its implementation.
- Live validation must follow `AGENTS.md`: 2-second screenshot cadence, preferably 61 captures, frame-hash progression, `PLAYING` media session, and sanitized log review.

---

## File structure

- `domain/.../StreamInfo.kt`: add a redacted static-ClearKey license source while retaining source compatibility for remote-license callers.
- `data/.../parser/M3uPlaybackMetadata.kt`: typed normalized metadata, directive parsing, header parsing, static-key normalization, and versioned JSON codec.
- `data/.../parser/M3uParser.kt`: accumulate supported directives for the pending entry.
- `data/.../local/entity/Entities.kt`: persist metadata on live/movie rows and their import-stage rows.
- `data/.../local/FeatureMigrationsV77To78.kt`: additive Room migration.
- `data/.../local/StreamVaultDatabase*.kt`: database version and migration registration.
- `data/.../local/dao/CatalogSyncDao.kt`: copy metadata through atomic staging updates/inserts.
- `data/.../sync/SyncManagerM3uImporter.kt`: encode parsed metadata during M3U classification/import.
- `data/.../sync/SyncCatalogStore.kt`: stage metadata and incorporate a secret-safe digest into fingerprints.
- `data/.../repository/M3uClassificationRepositoryImpl.kt`: preserve metadata during manual live/movie reclassification.
- `data/.../repository/M3uStreamInfoMapper.kt`: decode persisted metadata and enrich resolved stream information.
- `data/.../repository/ChannelRepositoryImpl.kt` and `MovieRepositoryImpl.kt`: load the selected raw entity and invoke the mapper.
- `player/.../playback/StaticClearKeyDrm.kt`: build the local JWK response and DRM session manager.
- `player/.../playback/PlayerMediaSourceFactory.kt`: select remote or local DRM plumbing without leaking secrets.
- `player/.../playback/PreloadCoordinator.kt` and `Media3PreloadWindowManager.kt`: use redacted DRM fingerprints after the model change.
- Corresponding unit/instrumentation tests named in each task below.

---

### Task 1: Model remote and static DRM license sources safely

**Files:**
- Modify: `domain/src/main/java/com/streamvault/domain/model/StreamInfo.kt`
- Modify: `domain/src/test/java/com/streamvault/domain/model/StreamInfoTest.kt`

**Interfaces:**
- Produces: `StaticClearKey(keyIdBase64Url: String, keyBase64Url: String)`.
- Produces: `StaticClearKeyLicense(keys: List<StaticClearKey>, fingerprint: String)` with a redacted `toString()`.
- Extends: `DrmInfo(..., licenseUrl: String = "", staticClearKeyLicense: StaticClearKeyLicense? = null)`.
- Invariant: exactly one of nonblank `licenseUrl` and `staticClearKeyLicense` is present; static material requires `DrmScheme.CLEARKEY`.

- [ ] **Step 1: Write failing domain tests**

Add tests proving that existing URL-based construction still works, static ClearKey construction works without a URL, ambiguous/missing sources fail, non-ClearKey static sources fail, and neither `DrmInfo.toString()` nor `StaticClearKeyLicense.toString()` contains the supplied key or a remote license URL query value.

```kotlin
@Test
fun drmInfo_accepts_redacted_static_clearkey_source() {
    val key = StaticClearKey("ESIzRFVmd4iZqrvM3e7_8A", "_-7dzLuqmYh3ZlVEMyIRAA")
    val license = StaticClearKeyLicense(listOf(key), fingerprint = "sha256:test")

    val info = DrmInfo(
        scheme = DrmScheme.CLEARKEY,
        staticClearKeyLicense = license
    )

    assertThat(info.licenseUrl).isEmpty()
    assertThat(info.toString()).doesNotContain(key.keyBase64Url)
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew.bat :domain:testDebugUnitTest --tests "com.streamvault.domain.model.StreamInfoTest"`

Expected: compilation fails because `StaticClearKey` and `staticClearKeyLicense` do not exist.

- [ ] **Step 3: Implement the minimal domain types and invariants**

Keep the current `DrmInfo` constructor parameter order. Give `licenseUrl` a default empty string so existing named and positional remote-license calls compile. Implement explicit redacted `toString()` methods and value equality that does not print secrets.

- [ ] **Step 4: Run domain tests and verify GREEN**

Run: `./gradlew.bat :domain:testDebugUnitTest --tests "com.streamvault.domain.model.StreamInfoTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add domain/src/main/java/com/streamvault/domain/model/StreamInfo.kt domain/src/test/java/com/streamvault/domain/model/StreamInfoTest.kt
git commit -m "feat: model static ClearKey licenses"
```

### Task 2: Normalize Kodi adaptive directives and static keys

**Files:**
- Create: `data/src/main/java/com/streamvault/data/parser/M3uPlaybackMetadata.kt`
- Create: `data/src/test/java/com/streamvault/data/parser/M3uPlaybackMetadataTest.kt`

**Interfaces:**
- Produces: `M3uPlaybackMetadata(version, manifestType, drmScheme, licenseUrl, staticClearKeyLicense, manifestHeaders, streamHeaders, commonHeaders, userAgent, referer)`.
- Produces: `M3uPlaybackMetadataBuilder.applyDirective(line: String)` and `build(): M3uPlaybackMetadata?`.
- Produces: `M3uPlaybackMetadataCodec.encode(metadata): String` and `decode(raw): M3uPlaybackMetadata?`.
- Produces: `fingerprintMaterial(metadata): String`, returning a SHA-256 digest for secret-bearing values rather than raw values.

- [ ] **Step 1: Write failing normalization tests**

Cover case-insensitive aliases, `mpd`/`hls`/`ism`, duplicate scalar replacement, header merging, user-agent/referer, HTTP(S) licenses, and unsupported scheme rejection.

```kotlin
@Test
fun `widevine URL metadata normalizes JioTV directives`() {
    val builder = M3uPlaybackMetadataBuilder()
    builder.applyDirective("#KODIPROP:inputstream.adaptive.manifest_type=mpd")
    builder.applyDirective("#KODIPROP:inputstream.adaptive.license_type=com.widevine.alpha")
    builder.applyDirective("#KODIPROP:inputstream.adaptive.license_key=https://tv.example/live/key/144?q=high")

    val metadata = builder.build()!!

    assertThat(metadata.manifestType).isEqualTo(StreamType.DASH)
    assertThat(metadata.drmScheme).isEqualTo(DrmScheme.WIDEVINE)
    assertThat(metadata.licenseUrl).isEqualTo("https://tv.example/live/key/144?q=high")
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run: `./gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.parser.M3uPlaybackMetadataTest"`

Expected: compilation fails because the metadata builder and codec do not exist.

- [ ] **Step 3: Implement remote-license and header normalization**

Parse `name=value` only after matching an allowlisted prefix. Parse Kodi header strings as `&`-separated name/value pairs with UTF-8 percent decoding. Reject blank names, `Host`, `Content-Length`, `Transfer-Encoding`, `Connection`, and values containing CR/LF. Enforce 32 headers, 128 characters per name, 4096 per value, and 32768 total serialized characters.

- [ ] **Step 4: Run tests and verify GREEN for remote metadata**

Run the Task 2 test command. Expected: the remote/header tests pass.

- [ ] **Step 5: Add failing static ClearKey format tests**

Cover one hex `KID:key`, comma-separated pairs, JSON maps, base64/base64url pairs, and full JWK responses. Assert every normalized KID/key is unpadded base64url and decodes to 16 bytes. Add malformed length, odd hex, invalid JSON, more than 16 keys, and static-key-with-Widevine rejection cases.

- [ ] **Step 6: Verify the new static-key tests fail for the expected missing behavior**

Run the Task 2 test command. Expected: static ClearKey assertions fail while remote-license tests remain green.

- [ ] **Step 7: Implement static ClearKey normalization and versioned JSON codec**

Use strict decoding and canonical unpadded base64url. Encode JSON with `version: 1`; sort header names and ClearKey KIDs for deterministic output. Decode unknown versions or malformed payloads to `null`. Store the full license material only in the persisted payload; expose only its SHA-256 digest through `fingerprintMaterial`.

- [ ] **Step 8: Run tests and verify GREEN**

Run the Task 2 test command. Expected: PASS with raw-key redaction assertions.

- [ ] **Step 9: Commit**

```bash
git add data/src/main/java/com/streamvault/data/parser/M3uPlaybackMetadata.kt data/src/test/java/com/streamvault/data/parser/M3uPlaybackMetadataTest.kt
git commit -m "feat: normalize Kodi adaptive M3U metadata"
```

### Task 3: Attach adaptive metadata to parsed M3U entries

**Files:**
- Modify: `data/src/main/java/com/streamvault/data/parser/M3uParser.kt`
- Modify: `data/src/test/java/com/streamvault/data/parser/M3uParserTest.kt`

**Interfaces:**
- Extends: `M3uParser.M3uEntry` with `playbackMetadata: M3uPlaybackMetadata? = null`.
- Extends the pending parsed-entry state with an immutable/copyable metadata builder state.

- [ ] **Step 1: Write failing parser regression tests**

Add one issue #169 fixture and one JioTV fixture. Assert the URL remains the first non-comment line, the metadata is attached only to the preceding `#EXTINF`, a later entry does not inherit it, and `#EXTVLCOPT:http-user-agent`/referer are preserved.

```kotlin
@Test
fun `parse associates static clearkey directives with one entry`() {
    val entry = parseEntries(ISSUE_169_PLAYLIST).single()

    assertThat(entry.playbackMetadata!!.drmScheme).isEqualTo(DrmScheme.CLEARKEY)
    assertThat(entry.playbackMetadata!!.staticClearKeyLicense!!.keys).hasSize(1)
    assertThat(entry.userAgent).isEqualTo("Mozilla/5.0")
}
```

- [ ] **Step 2: Run the parser test and verify RED**

Run: `./gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.parser.M3uParserTest"`

Expected: compilation fails because `M3uEntry.playbackMetadata` does not exist.

- [ ] **Step 3: Implement pending-directive accumulation**

Route `#KODIPROP` and `#EXTVLCOPT` through the metadata builder while retaining existing `#EXTGRP` behavior. Reset all pending directive state after a URL, a replacement `#EXTINF`, an unexpected `#EXTM3U`, or EOF invalidation.

- [ ] **Step 4: Run parser tests and verify GREEN**

Run the Task 3 test command. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add data/src/main/java/com/streamvault/data/parser/M3uParser.kt data/src/test/java/com/streamvault/data/parser/M3uParserTest.kt
git commit -m "feat: parse Kodi adaptive M3U directives"
```

### Task 4: Persist playback metadata through Room staging

**Files:**
- Modify: `data/src/main/java/com/streamvault/data/local/entity/Entities.kt`
- Create: `data/src/main/java/com/streamvault/data/local/FeatureMigrationsV77To78.kt`
- Modify: `data/src/main/java/com/streamvault/data/local/StreamVaultDatabase.kt`
- Modify: `data/src/main/java/com/streamvault/data/local/StreamVaultDatabaseMigrationRegistry.kt`
- Modify: `data/src/main/java/com/streamvault/data/local/dao/CatalogSyncDao.kt`
- Modify: `data/src/main/java/com/streamvault/data/sync/SyncCatalogStore.kt`
- Modify: `data/src/test/java/com/streamvault/data/local/StreamVaultDatabaseMigrationRegistryTest.kt`
- Modify: `data/src/test/java/com/streamvault/data/sync/SyncCatalogStoreTest.kt`
- Modify: `data/src/androidTest/java/com/streamvault/data/local/StreamVaultDatabaseMigrationTest.kt`
- Generate: `data/schemas/com.streamvault.data.local.StreamVaultDatabase/78.json`

**Interfaces:**
- Adds: `playbackMetadataJson: String? = null` to `ChannelEntity`, `MovieEntity`, `ChannelImportStageEntity`, and `MovieImportStageEntity`.
- Adds: `FeatureMigrationsV77To78.MIGRATION_77_78`.

- [ ] **Step 1: Write failing sync-store tests**

Construct channel and movie entities containing `playbackMetadataJson`, stage them, and assert captured stage rows retain it. Assert changing only that payload changes `syncFingerprint`, while the fingerprint never contains a supplied raw key.

- [ ] **Step 2: Run the sync-store test and verify RED**

Run: `./gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.sync.SyncCatalogStoreTest"`

Expected: compilation fails because entity metadata fields do not exist.

- [ ] **Step 3: Add entity/stage fields and staging propagation**

Copy `playbackMetadataJson` in every `ChannelImportStageEntity` and `MovieImportStageEntity` constructor. Add only `fingerprint(playbackMetadataJson)` or the codec's redacted digest to channel/movie fingerprints; never concatenate the raw JSON into diagnostic output.

- [ ] **Step 4: Update catalog staging SQL**

Add `playback_metadata_json` to channel/movie changed-row assignments and missing-row insert/select column lists. Keep column and select expression order identical.

- [ ] **Step 5: Run sync-store tests and verify GREEN**

Run the Task 4 unit-test command. Expected: PASS.

- [ ] **Step 6: Write the failing 77-to-78 migration test**

Create a version-77 database with representative channel, movie, and stage rows, run `MIGRATION_77_78`, and assert all four tables expose nullable `playback_metadata_json` values while original row data remains intact.

- [ ] **Step 7: Run instrumentation migration test and verify RED**

Run with an available emulator/device:

`./gradlew.bat :data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.local.StreamVaultDatabaseMigrationTest`

Expected: the new migration test fails because migration 77-to-78 is absent.

- [ ] **Step 8: Implement and register the additive migration**

Set `STREAM_VAULT_DATABASE_VERSION` to `78`; add four `ALTER TABLE ... ADD COLUMN playback_metadata_json TEXT` statements; expose the migration from `StreamVaultDatabase`; add `v77To78` to the contiguous registry.

- [ ] **Step 9: Generate and verify Room schema 78**

Run: `./gradlew.bat :data:kspDebugKotlin`

Then run the Task 4 unit and instrumentation commands. Expected: registry, schema, migration, and staging tests pass.

- [ ] **Step 10: Commit**

```bash
git add data/src/main/java/com/streamvault/data/local data/src/main/java/com/streamvault/data/sync/SyncCatalogStore.kt data/src/test/java/com/streamvault/data/local data/src/test/java/com/streamvault/data/sync/SyncCatalogStoreTest.kt data/src/androidTest/java/com/streamvault/data/local/StreamVaultDatabaseMigrationTest.kt data/schemas/com.streamvault.data.local.StreamVaultDatabase/78.json
git commit -m "feat: persist M3U playback metadata"
```

### Task 5: Carry metadata through M3U import and manual classification

**Files:**
- Modify: `data/src/main/java/com/streamvault/data/sync/SyncManagerM3uImporter.kt`
- Modify: `data/src/main/java/com/streamvault/data/repository/M3uClassificationRepositoryImpl.kt`
- Modify: `data/src/test/java/com/streamvault/data/sync/SyncManagerM3uImporterTest.kt`
- Modify: `data/src/test/java/com/streamvault/data/repository/M3uClassificationRepositoryTest.kt`

**Interfaces:**
- Consumes: `M3uPlaybackMetadataCodec.encode`.
- Preserves: `playbackMetadataJson` during live-to-movie and movie-to-live conversions.

- [ ] **Step 1: Write failing importer tests**

Capture staged entities from an M3U containing Kodi properties. Assert both a live DASH entry and a VOD-classified entry contain decodable metadata. Add an oversized directive case proving the provider import survives with `playbackMetadataJson == null`.

- [ ] **Step 2: Run importer tests and verify RED**

Run: `./gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.sync.SyncManagerM3uImporterTest"`

Expected: metadata assertions fail because the importer does not encode parsed metadata.

- [ ] **Step 3: Encode metadata in all M3U entity construction branches**

Set `playbackMetadataJson` for normal live entries, manually assigned series-as-live entries, and movie entries. Include encoded metadata in field-bound admission checks without exposing its contents in warning text.

- [ ] **Step 4: Run importer tests and verify GREEN**

Run the Task 5 importer command. Expected: PASS.

- [ ] **Step 5: Write failing manual-classification preservation tests**

Assert moving an M3U channel to Movies copies the metadata and moving it back to Live restores it. Existing non-M3U/null metadata must stay null.

- [ ] **Step 6: Run the focused repository test and verify RED**

Run the test class selected in Step 5 through `:data:testDebugUnitTest`. Expected: copied entities have null metadata.

- [ ] **Step 7: Preserve metadata in classification conversions**

Copy the source entity's `playbackMetadataJson` in `upsertMovie` and `restoreMovieToLive`. If series/episode conversion has no persistence field for this metadata, reject DRM-bearing live-to-series manual conversion with a sanitized result rather than silently losing DRM configuration; document this limitation in the test name and changelog.

- [ ] **Step 8: Run focused importer and classification tests**

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add data/src/main/java/com/streamvault/data/sync/SyncManagerM3uImporter.kt data/src/main/java/com/streamvault/data/repository/M3uClassificationRepositoryImpl.kt data/src/test/java/com/streamvault/data
git commit -m "feat: retain adaptive metadata during M3U import"
```

### Task 6: Enrich channel and movie `StreamInfo`

**Files:**
- Create: `data/src/main/java/com/streamvault/data/repository/M3uStreamInfoMapper.kt`
- Create: `data/src/test/java/com/streamvault/data/repository/M3uStreamInfoMapperTest.kt`
- Modify: `data/src/main/java/com/streamvault/data/repository/ChannelRepositoryImpl.kt`
- Modify: `data/src/main/java/com/streamvault/data/repository/MovieRepositoryImpl.kt`
- Modify: `data/src/test/java/com/streamvault/data/repository/ChannelRepositoryImplTest.kt`
- Modify: `data/src/test/java/com/streamvault/data/repository/MovieRepositoryImplTest.kt`

**Interfaces:**
- Produces: `M3uStreamInfoMapper.enrich(base: StreamInfo, playbackMetadataJson: String?): StreamInfo`.
- Merge precedence: resolved-provider headers, then common headers, then manifest headers, then stream headers; explicit `#EXTVLCOPT` user-agent/referer win for M3U entries.
- DRM mapping: remote URL -> `DrmInfo(licenseUrl=...)`; static ClearKey -> `DrmInfo(staticClearKeyLicense=...)`.

- [ ] **Step 1: Write failing mapper tests**

Assert manifest type overrides extension inference, header precedence is deterministic and case-insensitive, referer becomes `Referer`, remote license headers stay on `DrmInfo.headers`, static keys select the local source, malformed JSON leaves the base `StreamInfo` unchanged, and no assertion failure prints a raw key.

- [ ] **Step 2: Run mapper tests and verify RED**

Run: `./gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.repository.M3uStreamInfoMapperTest"`

Expected: compilation fails because the mapper does not exist.

- [ ] **Step 3: Implement the pure mapper and verify GREEN**

Run the Task 6 mapper command. Expected: PASS.

- [ ] **Step 4: Write failing channel/movie repository tests**

Stub `channelDao.getById(channel.selectedVariantId)` and `movieDao.getById(movie.id)` with metadata-bearing entities. Assert returned `StreamInfo` contains the mapped DRM and selected grouped-channel variants use the selected raw row's metadata.

- [ ] **Step 5: Run repository tests and verify RED**

Run:

`./gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.repository.ChannelRepositoryImplTest" --tests "com.streamvault.data.repository.MovieRepositoryImplTest"`

Expected: returned stream information has no DRM metadata.

- [ ] **Step 6: Integrate entity lookup and enrichment**

For M3U URLs only, load the full selected entity and pass its JSON to the mapper after the existing resolver returns. Preserve the resolver's URL, proxy, transport policy, expiry, and headers. A missing/deleted row must fall back to existing behavior.

- [ ] **Step 7: Run repository tests and verify GREEN**

Run the Task 6 repository command. Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add data/src/main/java/com/streamvault/data/repository data/src/test/java/com/streamvault/data/repository
git commit -m "feat: resolve imported M3U DRM metadata"
```

### Task 7: Add Media3 local ClearKey playback

**Files:**
- Create: `player/src/main/java/com/streamvault/player/playback/StaticClearKeyDrm.kt`
- Create: `player/src/test/java/com/streamvault/player/playback/StaticClearKeyDrmTest.kt`
- Modify: `player/src/main/java/com/streamvault/player/playback/PlayerMediaSourceFactory.kt`
- Modify: `player/src/test/java/com/streamvault/player/playback/PlayerMediaSourceFactoryTest.kt`
- Modify: `player/src/main/java/com/streamvault/player/playback/PreloadCoordinator.kt`
- Modify: `player/src/main/java/com/streamvault/player/playback/Media3PreloadWindowManager.kt`
- Modify: `player/src/test/java/com/streamvault/player/playback/PreloadCoordinatorTest.kt`
- Modify: `player/src/test/java/com/streamvault/player/playback/Media3PreloadWindowManagerTest.kt`

**Interfaces:**
- Produces: `buildClearKeyJwkResponse(license: StaticClearKeyLicense): ByteArray`.
- Produces: `staticClearKeyDrmSessionManager(license): DrmSessionManager`.
- Produces: a redacted `DrmInfo.identityFingerprint()` used by media IDs and preload identities.

- [ ] **Step 1: Write failing JWK generation and redaction tests**

Assert deterministic JSON of the form below, ordering by KID, and assert media/preload identities change when key material changes without containing either raw key.

```json
{"keys":[{"kty":"oct","kid":"ESIzRFVmd4iZqrvM3e7_8A","k":"_-7dzLuqmYh3ZlVEMyIRAA"}],"type":"temporary"}
```

- [ ] **Step 2: Run player tests and verify RED**

Run: `./gradlew.bat :player:testDebugUnitTest --tests "com.streamvault.player.playback.StaticClearKeyDrmTest" --tests "com.streamvault.player.playback.PlayerMediaSourceFactoryTest"`

Expected: compilation fails because the helper and local-license source do not yet have player handling.

- [ ] **Step 3: Implement JWK response and DRM session manager**

Build `DefaultDrmSessionManager` with `C.CLEARKEY_UUID`, `FrameworkMediaDrm.DEFAULT_PROVIDER`, and `LocalMediaDrmCallback(responseBytes)`. Do not set a key-set ID or license URI for static ClearKey.

- [ ] **Step 4: Install local DRM on the media-source factory**

When static ClearKey is present, install a `DrmSessionManagerProvider` returning the local manager on DASH (and on other adaptive factories where the current Media3 API supports it). Continue using `MediaItem.DrmConfiguration` with an HTTP license URI for remote-license DRM. Static ClearKey `MediaItem` configuration specifies only the ClearKey UUID and compatible session flags.

- [ ] **Step 5: Replace raw DRM identity strings with digests**

Update `mediaIdFor`, `PreloadCoordinator.drmKey`, and `preloadStreamIdentity` to use the redacted identity fingerprint. Ensure no interpolated `DrmInfo` or static license object reaches a log statement.

- [ ] **Step 6: Run focused player tests and verify GREEN**

Run the Task 7 player command. Expected: PASS.

- [ ] **Step 7: Run the complete player unit suite**

Run: `./gradlew.bat :player:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add player/src/main/java/com/streamvault/player/playback player/src/test/java/com/streamvault/player/playback
git commit -m "feat: play static ClearKey DASH streams"
```

### Task 8: Regression verification, documentation, and device evidence

**Files:**
- Modify: `docs/CHANGELOG.md`
- Modify if behavior details warrant it: `README.md`
- Create evidence outside the repository: `$env:TEMP\streamvault_kodi_drm_validation\`

**Interfaces:**
- No new production interfaces.

- [ ] **Step 1: Add a changelog entry**

Document native Kodi adaptive M3U support, supported DRM/license forms, preserved HTTP properties, and the limitation that native playback does not rewrite incompatible manifests.

- [ ] **Step 2: Run formatting/static checks for changed modules**

Run: `./gradlew.bat :domain:lintDebug :data:lintDebug :player:lintDebug`

Expected: PASS without new errors.

- [ ] **Step 3: Run all affected JVM tests**

Run: `./gradlew.bat :domain:testDebugUnitTest :data:testDebugUnitTest :player:testDebugUnitTest :app:testDebugUnitTest`

Expected: PASS.

- [ ] **Step 4: Build the application**

Run: `./gradlew.bat :app:assembleDebug`

Expected: `BUILD SUCCESSFUL` and a debug APK under `app/build/outputs/apk/debug/`.

- [ ] **Step 5: Run Room migration instrumentation tests**

Run with an available emulator/device:

`./gradlew.bat :data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.local.StreamVaultDatabaseMigrationTest`

Expected: PASS.

- [ ] **Step 6: Validate an authorized Widevine DASH channel**

Import a direct JioTV-style or equivalent authorized M3U entry, confirm the license request succeeds, and capture 61 screenshots at two-second intervals. Record channel name, screenshot count, unique hash count, media-session state, and sanitized DRM/player log findings.

- [ ] **Step 7: Validate an authorized static-ClearKey DASH channel**

Use an issue #169-style entry whose MPD already contains compatible ClearKey signaling. Repeat the 61-screenshot capture and evidence recording. Confirm logs and media identifiers do not expose the KID/key.

- [ ] **Step 8: Validate a non-DRM regression channel**

Run the same sustained check on one non-DRM live DASH or HLS channel and confirm existing playback/recovery behavior remains healthy.

- [ ] **Step 9: Inspect required logs**

Use the exact media-session and log-search commands from `AGENTS.md`, adding sanitized searches for `DrmSession`, `ClearKey`, `KeysExpiredException`, and `ERROR_CODE_DRM`. A passing result has ongoing frame changes, `PLAYING` with `error=null`, no fatal/stuck fallback, and no leaked static key.

- [ ] **Step 10: Commit documentation**

```bash
git add docs/CHANGELOG.md README.md
git commit -m "docs: describe native adaptive M3U DRM support"
```

- [ ] **Step 11: Run final diff and secret checks**

Run:

```bash
git diff --check HEAD~8..HEAD
rg -n "912760c409eb5aff3e060422c502f410|bea2d0f89fb3fbafa1fc9f34ba8734a6" . --glob '!docs/superpowers/specs/**' --glob '!docs/superpowers/plans/**' --glob '!**/build/**'
```

Expected: no diff errors and no production/test occurrence of the issue reporter's example material.

---

## Completion criteria

- Every new behavior was introduced by a failing test that subsequently passed.
- Database migration 77-to-78 and schema validation pass.
- JioTV-style Widevine and issue #169-style static ClearKey both reach the correct Media3 paths.
- Raw keys and sensitive request values are absent from logs and identities.
- All affected unit suites, lint tasks, application build, and migration instrumentation tests pass.
- Sustained device validation evidence is recorded for Widevine, static ClearKey, and non-DRM playback, or the absence of authorized test streams is reported explicitly without claiming live playback validation.
