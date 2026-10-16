# Stremio addons — continuation handoff

User acceptance correction (2026-10-14), implemented; user acceptance pending: Stremio
Add-ons must be open by default, independent of the global parental-control
level. Add Settings → Privacy → "Hide Stremio Add-ons", off by default.
This replaces the blanket parental gate documented in the historical results
below. The updated debug APK is installed on emulator-5558. Track
the new acceptance criteria in `STREMIO_ADDONS_ACCEPTANCE_CHECKLIST.md`.

Working agreement: the user owns manual UI/UX and emulator acceptance testing.
The agent implements changes and runs build/code checks, then hands over the
build. Do not continue manual emulator testing unless the user asks.

UI cleanup implements a stable Extensions heading, compact fixed-scale tabs,
plugin-specific APK/URL action labels, and Stremio onboarding with a visible
Add action. The hide preference is independent of global parental controls,
off by default, and retained in DataStore. The temporary hide choice used
during agent checks was returned to off; global parental settings are intact.
Build and code checks passed (30 Stremio, 63 Settings, 165 app unit tests,
plus the focused visibility preference test). Four existing plugin UI checks
passed before the user clarified testing ownership. Plugin golden assets were
re-recorded; their subsequent comparison was not run. User acceptance items
remain unchecked.

P6 implementation is complete. The Stremio stream picker constructs a validated
magnet URI from `infoHash`, tracker sources, and `fileIdx`; the plugin manager
requires an enabled scheme-owning handler, rejects non-loopback plugin outputs,
and sends best-effort stop IPC when playback closes. The separate companion
repository at `D:\antigravity-scratch\streamvault-torrent-plugin` contains the
libtorrent engine, loopback byte-range server, plugin IPC service, configuration
Activity, tests, and a successful debug APK build. JVM tests and APK assembly
pass. The companion source is committed locally at `9ac943c` (branch `main`, no
remote configured). Real torrent playback, seeking, replay, and teardown have not been tested;
the user owns that manual acceptance pass in `STREMIO_TORRENT_P6_ACCEPTANCE.md`.

Continuation update (2026-10-12): P0–P5 are implemented in this worktree. P1 is
committed at `e96ce49e`; P2–P5 and the Extensions navigation follow-up remain
uncommitted. Unit tests and debug assembly pass. P4 direct MP4 playback and
history/resume were smoke-tested. The synthetic HLS fixture raised transient
network EOFs over local HTTP/1.0, but the same playlist and segments completed
over HTTP/1.1 on the same host port; validate with a real third-party addon
endpoint before classifying this as a player defect. A remote HLS VOD smoke also
passed through the local Stremio fixture, as recorded below. P5 inline subtitle
discovery and the picker were smoke-tested. Cross-addon subtitle playback is now
verified on isolated API 36 `emulator-5556`: the fresh `tt7654327` item exposed
the German track from the independent subtitle-only addon, and its VTT cue
rendered while the player remained healthy. At that point the remaining v1
gates were a real third-party addon HLS/DASH child-resource check, provider
playback, and header-dependent cast/external-player regression. No suitable
public-domain third-party HLS addon was found. The install-URL storage/export/
removal policy is documented, Manage shows only addon server origins, and D-pad
focus through Extensions and its sub-tabs is verified. See
`STREMIO_ADDONS_PLAN_REVIEW.md`.

Continuation update (2026-10-13): provider playback now passes on a dedicated
clean Android TV API 36 AVD using the local V11 fixture. HLS played through a
145-second health/position-advance hold; 61 screenshots at two-second cadence
span 120.26 seconds with 60 distinct hashes because the synthetic VOD loops at
120 seconds. Media session was PLAYING with `error=null` during capture, and
sanitized logs show HLS prepare, first frame, playlist/segment reads, and no
retry, fatal error, stuck timeout, or MPEG-TS fallback. Progressive MP4 also
reached first frame. Cleanup passed and the fixture provider row count is zero.
This is provider-fixture VOD validation, not a real provider or live-TV result.
The real third-party addon HLS/DASH child-request check remains open: the
official Public Domain Movies addon returns torrents, the Archive.org addon
returns direct files and its old host is down, and the community Public Domain
Foreign Movies manifest URL returns 404; its source contains no HLS playlist
references. The header-dependent cast/external-player regression also remains
open.

Copy this whole block into a new session working in the worktree.

---

Continue the StreamVault Stremio addon feature. Work only in:
`D:\antigravity-scratch\iptv-player\.worktrees\stremio-addons` (branch `feature/stremio-addons`, based on `develop` @ 540ec30c). Read `docs/STREMIO_ADDONS_IMPLEMENTATION_PLAN.md` first. P1 was committed after its verification; check the current branch history for the commit.

Important: the worktree now uses Room DB version 80. It includes the historical
78→79 addon-registry migration and the new 79→80 Stremio playback-history
migration. A separate emulator profile with a version-81 database is still not a
valid target for this branch; do not clear shared emulator data to work around it.

## Done and verified

- P0 (protocol client): `data/src/main/java/com/streamvault/data/remote/stremio/` (`StremioAddonClient`, `StremioAddonUrl`, `StremioAddonDtos`, `StremioMapper`) + domain models `domain/src/main/java/com/streamvault/domain/model/stremio/`; the latest focused client/URL run has 24 passing tests.
- P1 data layer: `StremioAddonEntity`, `StremioAddonDao`, `FeatureMigrationsV78To79`, `StremioAddonRepository` (domain) + `StremioAddonRepositoryImpl`, `StremioCatalogSettingsCodec`, `StremioAddonClient.fetchManifestSnapshot/parseManifest`; DI wired in `DatabaseModule`/`RepositoryModule`; schema export `data/schemas/com.streamvault.data.local.StreamVaultDatabase/79.json`; repository tests covered configuration-required rejection (14 now passing after P2 coverage); `StreamVaultDatabaseMigrationTest` — 43 tests green on emulator-5554 (API 36), including the new `migrate78To79_...` test.
- P1 verification update: `:app:testDebugUnitTest` passed 163 tests; `:feature:stremio:testDebugUnitTest` passed 6; focused data tests passed 34 (`StremioAddonClientTest` 13, `StremioAddonUrlTest` 9, `StremioAddonRepositoryImplTest` 10, migration registry 2). `:app:assembleDebug` succeeded. A broader data run hit unrelated download mock-server and Google Drive backup test failures; the focused Stremio rerun passed.
- P1 emulator smoke: installed `com.streamvault.app.debug` on isolated `Television_1080p` (API 36) and installed `https://v3-cinemeta.strem.io/manifest.json`. Screenshot: `addons.png`. At that point the branch was at DB v79. The current worktree is v80; `Television_4K_2` was later found at DB v82 and remains incompatible, so its data was left untouched.
- P1 nav/UI: `AppDestination.Addons` and its route remain distinct; the global navbar now uses one `AppTopLevelDestination.EXTENSIONS` item with focusable Plugins / Stremio Add-ons tabs. Legacy saved top-level `plugins` and `addons` values migrate to `extensions`, while plugin/addon startup destinations remain available. The new `feature:stremio` module (`StremioRoutePatterns.ADDONS="addons"`, `AddonsViewModel`, `AddonsScreen`) is wired through `AppNavHost`; the APK Plugins screen is still separate. Unit and emulator smoke validation for P1 are complete.
- P2 catalog browsing and Extensions navigation polish are implemented but uncommitted: Browse and Manage tabs, enabled-addon/catalog rows, search/genre and required-extra gates, nullable `hasMore`, raw response counts and 100-item skip pagination, duplicate-page/page-count guards, cancellation of stale requests, refresh-aware cache identity, 200-page/5-minute LRU cache, errors/retry/empty/loading states, and TV card focus border. Seven `StremioCatalogBrowseViewModelTest` cases and six existing Addons ViewModel tests pass. Focused data tests pass (14 client, 10 URL, 14 repository); `:app:assembleDebug` passes.
- P2 TV smoke: on isolated `Television_1080p` API 36 (`emulator-5556`), installed the latest APK, installed Cinemeta through Manage, loaded its Popular catalog/posters through Browse, and navigated by D-pad to a focused card. The catalog heading renders with readable foreground contrast. The shared `emulator-5554` was not used for this smoke.
- Extensions navigation follow-up (2026-10-12): one Extensions navbar/settings item now opens focusable Plugins and Stremio Add-ons tabs; the plugin and addon routes and startup landing choices remain distinct. Legacy top-level `plugins`/`addons` preferences normalize to `extensions`. `:domain:test`, `:app:testDebugUnitTest`, `:feature:system:testDebugUnitTest`, `:feature:stremio:testDebugUnitTest`, `:feature:system:compileDebugAndroidTestKotlin`, and `:app:assembleDebug` pass. Updated plugin golden screenshots; both plugin screenshot tests pass on the isolated read-only `Codex_Trakt` API 36 emulator. The full System golden class still has four existing welcome/download pixel mismatches on that AVD.
- P3 meta/detail and cross-addon resource routing (2026-10-12): implemented `StremioResourceResolver` for enabled addons with resource/type/exact-ID-prefix matching, catalog-origin-first metadata fallback, stable bounded stream/subtitle fan-out, attributed partial results, retry, and cancellation. The detail page preserves catalog provenance and exact `meta.videos[].id` values, shows unsupported stream rows with reasons, and creates a typed playback reference for eligible direct URLs.
- P4 playback/history (2026-10-12): direct/debrid playback uses the normal player pipeline with selected media headers scoped to media requests; magnet casting is blocked with an explicit unsupported state; addon progress lives in `stremio_playback_history` at Room version 80 and reopens through fresh stream resolution rather than stored URLs. Migration, history, playback-identity, header-sanitization, cast, and player tests pass. The migration instrumentation suite passed all 45 tests on isolated `Television_1080p` (API 36), including 79→80. On isolated API 36 `emulator-5556`, synthetic MP4 playback and resume/history reopening worked. The synthetic 120-second HLS asset raised `unexpected end of stream` at media positions 67.878s and 115.874s when served by the local HTTP/1.0 fixture; the player retried and recovered each time. The same playlist and segments served over HTTP/1.1 on the same host port completed at 120.007s with 51 screenshots (38 unique frames), all 31 datasource reads complete, and no retry/error. Treat the failure as a local fixture transport issue pending a real Stremio addon HLS smoke. This VOD check is not live-TV validation.
- P4 remote HLS VOD smoke (2026-10-12): the public Mux Big Buck Bunny HLS sample (`https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8`) played through the local Stremio fixture on isolated API 36 `emulator-5556`. Captured 61 screenshots at a two-second cadence; all 61 frame hashes were unique. Logs showed HLS prepare and first-frame success, and media session was `PLAYING` at position 213,376 ms with `error=null` when capture ended. There were two startup audio-underrun warnings and no retry, fatal error, stuck timeout, or MPEG-TS fallback. This validates remote HLS VOD playback; a real third-party addon endpoint and its redirect/header behavior remain open. This is not live-TV validation.
- P5 subtitles (2026-10-12): inline `stream.subtitles` are parsed and carried ephemerally with the playback request; addon subtitle resources are loaded lazily from the player picker, including `videoHash`, `videoSize`, and `filename` extras, language mapping, URL filtering/deduplication, and external-track injection. A focused overlay test fixed the no-embedded-track dead end by keeping the CC action visible when Stremio subtitle sources exist. Focused subtitle, player, data, app tests and debug assembly pass. On isolated API 36 `emulator-5556`, the picker showed the German resource from the independent subtitle-only addon for `tt7654327`; selecting it logged `addExternalSubtitle language=de`, rendered “Fixture German subtitle,” and media session remained `PLAYING` with `error=null`.
- Parental-control gate (2026-10-13): because addon catalogs and meta do not provide a dependable age rating, the whole Stremio feature is treated as protected content. Off allows access; Locked and Private require the existing PIN once per app session; Hidden blocks Stremio content and install. Private also omits Stremio continue-watching from the aggregate surface. Both browse and item routes are gated before their screens (and network requests) are created; install, catalog fetch, metadata, and stream actions also fail closed. Unit tests cover all four levels and blocked install/catalog/detail requests. TV smoke on isolated `Television_1080p` API 36 (`emulator-5556`) verified Hidden denial, Locked and Private PIN prompts, rejection of a wrong PIN, successful unlock, re-lock after changing levels, and Off access without a PIN. The original 1,750-byte preferences file was restored and its SHA-256 verified; `emulator-5554` and `Television_4K_2` were not used.
- Header/privacy review (2026-10-13): selected stream headers are validated and applied by an isolated playback client with cookies, authenticators, and cache disabled. The network interceptor adds them only for the selected media origin; an existing MockWebServer test confirms a cross-origin 302 does not forward Authorization. Playback history contains no stream URL or request headers. Credential policy: keep configured install URLs in app-private Room so addon configuration survives app restarts; do not log, back up, or include addon records in manual export; show only the origin in Manage; Remove deletes the active addon row, while disabling retains it. Room does not add app-level encryption, so protection relies on the Android app sandbox and device storage encryption. HLS/DASH child-resource and cast/external-player behavior still need verification with a suitable addon or fixture.
- Public-domain addon source check (2026-10-13): the official Public Domain Movies addon serves torrent `infoHash` streams for public-domain catalog items (verified with `tt0012349`, `tt0063350`, and `tt0013257`); torrent playback is deferred to P6, so this cannot validate direct HLS playback. The maintained Internet Archive addon source generates `archive.org/download/...` file URLs for AVI/MP4/MKV/WMV/MOV/M4V and does not emit HLS playlists; its previously published Baby-Beamup host no longer resolves. The community Public Domain Foreign Movies repository advertises public-domain/free-to-view sources, but its published `now.sh` manifest currently returns 404 and a source scan found no `.m3u8` stream references. No suitable public-domain third-party HLS addon was found. Keep the third-party-addon HLS gate open; do not count either local-fixture VOD smoke as satisfying it. Sources: [official Stremio addon index](https://github.com/Stremio/stremio-official-addons/blob/master/index.json), [Public Domain Foreign Movies source](https://github.com/jaruba/stremio-public-domain-foreign/blob/master/index.js), [Public Domain Foreign Movies README](https://github.com/jaruba/stremio-public-domain-foreign/blob/master/README.md).
- TV navigation regression check (2026-10-13): on isolated API 36 `emulator-5556`, D-pad focus moved from Home to the single Extensions navbar item, then between Plugins and Stremio Add-ons tabs. The Stremio Add-ons content correctly displayed the Hidden parental-control gate. The emulator's restored 1,750-byte preferences file still matches its original SHA-256; no settings were changed.
- Provider playback fixture validation (2026-10-13): created a separate clean Android TV API 36 AVD, `Codex_StremioProvider_Validation_20261013` (`emulator-5558`), and ran instrumentation directly with `adb` so Gradle did not uninstall app data. `V11 HLS Adaptive Fixture` resolved as HLS (H.264/AAC, 640x360, 2.5 Mbps; TTFF 1,122 ms); local server events included the master and child playlist plus `segment_000.ts` and `segment_001.ts`. The probe held HLS playback for 145 seconds and checked every two seconds that state stayed READY, `isPlaying` stayed true, and position advanced. Concurrent capture took 61 screenshots at a two-second cadence over 120.26 seconds; 60 hashes were unique because the 120-second synthetic VOD loop repeats its first frame at the end. At about 105 seconds, media session was PLAYING at position 105,101 ms with `error=null`. Logs contain HLS prepare/first-frame and progressing hold entries, with no retry, fatal error, stuck timeout, or MPEG-TS fallback. The progressive fixture also reached first frame (H.264/AAC, 640x360; TTFF 354 ms). A cleanup instrumentation test passed, and a read-only database check found zero `stremio_addons` rows after cleanup. This is VOD fixture validation, not live-TV validation or a third-party addon test.
- Playback-test environment incident (2026-10-13): `:app:connectedDebugAndroidTest` was run without a serial filter, so Gradle targeted both connected devices. The isolated `Television_1080p` run passed; `Television_4K_2` failed because its database is version 82 while this worktree expects version 80. Gradle removed `com.streamvault.app.debug` from `emulator-5556` after the run. The app was reinstalled and its Room v80 database and saved preferences were restored from local snapshots, but uninstalling destroyed Android Keystore keys: the restored provider rows' encrypted credentials now fail decryption and must be re-entered. The original local M3U cache was not in the available backups. Do not use `emulator-5556` for provider playback until credentials are re-entered, do not clear `emulator-5554`/`Television_4K_2`, and avoid connected Gradle tasks without an explicit single-device filter. The fresh `emulator-5558` was used for the fixture check instead.
- Credential display regression fix (2026-10-13): the Manage-addon row now renders only the parsed server origin, omitting credentials in URL userinfo, path, query, and fragment. `StremioAddonOriginLabelTest` covers configured secrets, default/custom ports, IPv6, and malformed URLs. The focused Stremio unit test and `:app:assembleDebug` pass.
- Cast privacy and usability follow-up (2026-10-13): Stremio playback retains an origin marker even with no request headers so its isolated network client is still used. `CastMediaRequestFactory` now blocks casting only when a Stremio stream actually has scoped headers; headerless direct streams can use the normal cast path. Focused playback tests cover both outcomes, including `blocks addon origin scoped headers from cast requests` and `allows headerless addon media to use the normal cast path`.
- Final local code gate (2026-10-13): `:feature:playback:testDebugUnitTest --tests com.streamvault.feature.playback.cast.CastMediaRequestFactoryTest :app:testDebugUnitTest :feature:stremio:testDebugUnitTest :domain:test :data:testDebugUnitTest :app:assembleDebug` passed. The latest APK was installed with `adb -s emulator-5558 install -r ...` and launched on the dedicated clean Android TV API 36 AVD; `com.streamvault.app.debug/MainActivity` resumed successfully. No connected Gradle task was run.
- P3 verification: `:domain:test --tests com.streamvault.domain.repository.StremioResourceResolverTest`, focused Stremio data tests (`StremioAddonClientTest`, `StremioAddonRepositoryImplTest`), `:feature:stremio:testDebugUnitTest`, `:app:testDebugUnitTest`, and `:app:assembleDebug` passed. A fresh `:feature:stremio:testDebugUnitTest :app:assembleDebug` run also passed after a TV-discovered detail-text contrast fix.
- P3 TV smoke: on read-only isolated `Television_1080p` API 36 (`emulator-5556`), installed Cinemeta, browsed Popular, opened the Unabomber detail page, confirmed metadata provenance and readable text, then requested streams and confirmed the clear no-enabled-stream-addon state. This smoke did not validate playback or a real multi-addon stream list; cross-addon behavior is covered by repository/domain/feature tests. The shared `emulator-5554` was not used.

## Immediate next steps

1. P1 unit verification, emulator install smoke, and the reviewed P1 commit are
   complete; see the results above. Commit: `e96ce49e`.
2. P2 and P3 are implemented and verified against AD-10 and AD-9. Their worktree
   changes, including the Extensions navigation follow-up, remain uncommitted.
3. The direct/debrid v1 implementation and local verification are complete.
   The parental PIN smoke is complete on isolated API 36 `emulator-5556` at
   Off, Locked, Private, and Hidden levels. D-pad navigation through Extensions
   and its Plugins/Stremio Add-ons tabs is also verified. No suitable
   public-domain third-party HLS addon was found: the official public-domain
   catalog returns torrent streams, Archive.org returns direct files, and the
   community foreign-movies addon has a dead manifest host and no HLS URLs in
   its source. Keep the real-addon HLS gate open until an eligible endpoint is
   available; the remote sample and provider fixture through local servers are
   complete. The credential storage/export/removal policy is now documented and
   the Manage screen displays only the server origin. Scoped-header casting is
   intentionally rejected and unit-tested; headerless direct streams use the
   normal cast path. External-player handoff is outside direct/debrid v1. The
   only remaining external qualification is real-addon HLS/DASH child-resource
   playback; no suitable public-domain endpoint was found. The independent
   subtitle-only fixture smoke is complete. Playback checks use VOD only; do not
   report them as live-TV validation. The latest debug APK is installed and
   launched on clean Android TV API 36 `emulator-5558` for the manual UI/usage
   pass in `docs/STREMIO_ADDONS_ACCEPTANCE_CHECKLIST.md`.
4. P6 implementation is complete; the user still owns the real-torrent manual
   acceptance pass in `STREMIO_TORRENT_P6_ACCEPTANCE.md`. Home/global search
   remains optional.

## Environment gotchas

- Windows PowerShell; use `.\gradlew.bat`. Long builds: run detached `Start-Process cmd.exe -ArgumentList "/c",".\gradlew.bat ... > log 2>&1" -PassThru -NoNewWindow` and poll the log — don't block in foreground >2 min.
- Worktree has its own `local.properties` (sdk.dir=C:\Users\David\AppData\Local\Android\Sdk).
- Emulator `emulator-5554` (API 36) is connected; instrumented: `:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.local.StreamVaultDatabaseMigrationTest`.
- `Television_4K_2` has database version 82 while this worktree is at 80; use a clean/read-only emulator profile for this version-80 worktree rather than clearing shared app data.
- The 2026-10-13 unfiltered `:app:connectedDebugAndroidTest` also targeted `Television_4K_2` and uninstalled the app from `emulator-5556`; app-private backups do not restore Android Keystore keys. Existing provider credentials there cannot decrypt until re-entered. Use serial-targeted `adb shell am instrument` on the clean `emulator-5558` for fixture work, and do not clear data on `emulator-5554` or `Television_4K_2`.
- Data module uses OkHttp + kotlinx.serialization DTOs; domain has no serialization — keep that split.
- Cosmetic compiler warning: `hiltViewModel()` has moved to `androidx.hilt.lifecycle.viewmodel.compose`; the current build succeeds.
- Do not reuse provider URL policies for addon streams (relevant in P4).

## Remaining phases (details in the plan doc)

Direct/debrid v1 implementation (P0–P5 and required P7) and local regression
checks are complete. Cross-addon subtitles, remote HLS VOD through the local
Stremio fixture, provider fixture playback, the parental gate, and Extensions
navigation have passed their recorded checks. The latest debug APK is installed
and launched on clean Android TV API 36 `emulator-5558`, ready for the manual
UI/usage pass. A real third-party addon HLS/DASH and child-resource smoke remains
external qualification because no suitable public-domain addon endpoint was
found. Scoped-header casting is intentionally rejected and unit-tested;
headerless direct addon streams use the normal cast path. External-player
handoff is outside direct/debrid v1. P6 implementation is complete and its
real-torrent acceptance remains with the user; optional Home/global search
remains deferred. P1 is verified and
committed; P2–P5, the parental gate, and Extensions navigation remain uncommitted.
