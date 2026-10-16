# Stremio addon support implementation plan

Status (2026-10-16): P0–P6 implementation and required P7 polish are complete;
P6 real-torrent playback acceptance is pending the user. The companion source
and debug APK live in the separate `D:\antigravity-scratch\streamvault-torrent-plugin`
repository. P1–P6 code/build verification is recorded in
`STREMIO_ADDONS_HANDOFF.md`. P4 MP4/history and provider fixture
playback checks passed. The synthetic HLS VOD fixture initially failed around
68 seconds over local HTTP/1.0; the same asset completed over HTTP/1.1 on the
same host port. A public Mux HLS sample passed 61 screenshots at a two-second
cadence through the local Stremio fixture, with unique frames and a healthy
media session. The provider HLS fixture then passed a 145-second advancing
playback hold, with 61 screenshots across 120.26 seconds (60 unique hashes
because the fixture loops at 120 seconds), healthy media-session state, and no
retry/fatal/stuck/fallback logs. These local fixtures do not close the external
real third-party addon HLS/DASH child-resource qualification. Scoped addon
headers are intentionally rejected for casting, while headerless direct streams
use the normal cast path; external-player handoff is outside v1. P5 inline and
independent subtitle-addon playback smoke passed on the isolated API 36 emulator;
the German WebVTT cue rendered from the subtitle-only addon.
The parental PIN gate is now smoke-tested at all four levels on TV, and D-pad
focus through Extensions and its Plugins/Stremio Add-ons tabs passes. Credential
storage/export/removal policy is documented, and Manage shows only addon server
origins. No suitable public-domain third-party HLS addon was found: the official
catalog returns torrents, the Archive.org addon returns direct files, and the
community Foreign Movies manifest host is down and its source has no HLS
playlist references. All direct/debrid v1 implementation and local regression
gates are complete. A real-addon HLS/DASH child-request smoke remains external
qualification. Scoped-header cast rejection is unit-tested; headerless direct
streams use the normal cast path. External-player handoff is outside v1.
Plan reviewed 2026-10-11; see `STREMIO_ADDONS_PLAN_REVIEW.md` for external
qualification and user acceptance.

## Current handoff status (2026-10-16)

- **User acceptance correction (2026-10-14), implemented; user acceptance pending:** UI/UX
  cleanup makes Stremio Add-ons open by default, independently of the
  global parental-control level. Uses a separate Settings → Privacy →
  "Hide Stremio Add-ons" option, off by default. This supersedes the blanket
  Stremio parental gate described below; those sections record the prior
  implementation and its historical verification. The user owns manual UI/UX
  acceptance testing; agent checks should be limited to builds and code tests.
- [x] P0–P5 direct/debrid Stremio support is implemented; required P7 polish is
  implemented. Verification details are in `STREMIO_ADDONS_HANDOFF.md`.
- [x] P6 implementation is complete: the host routes validated magnet URIs to
  an enabled scheme-owning plugin, restricts plugin output to loopback, and
  sends best-effort playback-stop IPC. The separate companion repository
  contains the libtorrent engine, byte-range server, configuration Activity,
  and debug APK. Unit tests and APK build pass; real-torrent emulator acceptance
  is pending the user. See `STREMIO_TORRENT_P6_ACCEPTANCE.md`.
- [x] Debug APK `1.0.19-debug` was built from this worktree, installed with
  replace/retain-data on `emulator-5558`, and `MainActivity` was launched on the
  dedicated Android TV API 36 AVD `Codex_StremioProvider_Validation_20261013`.
- [ ] User UI/UX and normal-use acceptance pass is pending; use
  `STREMIO_ADDONS_ACCEPTANCE_CHECKLIST.md` and leave its items unchecked until
  the user exercises them.
- [ ] Real third-party addon HLS/DASH child-request qualification remains open.
- [ ] User end-to-end torrent playback acceptance is pending; use
  `STREMIO_TORRENT_P6_ACCEPTANCE.md`. The agent has not installed the companion
  APK or tested a real torrent.

Prepared 2026-10-10 against source
revision `540ec30c` (`develop`), branch `feature/stremio-addons`, worktree
`.worktrees/stremio-addons`.

Goal: StreamVault can install Stremio addons by URL and use them natively — browse
catalogs, open meta/details, choose from a stream list, play direct and torrent-backed
streams, and load addon subtitles. Torrent playback is delivered by a companion
StreamVault plugin APK so the main application and repository never ship a BitTorrent
engine.

## Outcome and scope

Deliver a first-class "Addons" surface in StreamVault:

- Install an addon by pasting its manifest URL or a `stremio://` install link.
- Manage installed addons (enable/disable, remove, reorder, catalog selection).
- Browse catalog rows per addon with pagination, genre filters, and catalog search.
- Open movie/series meta pages for addon items (poster, background, description,
  cast, release info, seasons/episodes).
- Pick from the addon's stream list ("stream picker"), showing name/title/quality
  hints exactly as the addon returns them.
- Play direct (http/https, including debrid-resolved) streams through the existing
  player pipeline, with resume and history.
- Load addon-provided subtitles into playback.
- When a torrent stream (infoHash/magnet) is selected and the companion torrent
  plugin is installed, resolve it through the plugin into loopback playback.
  Without the plugin, show an explicit "torrent playback requires the StreamVault
  Torrent plugin" state instead of failing playback.

V1 scope note: direct/debrid streams are the phase-1 usable product. Torrent
playback is phase-gated (P6) behind the companion plugin and a separate repository.

Non-goals for this plan:

- Stremio account, sync, or Trakt integration.
- YouTube (`ytId`) stream playback.
- `externalUrl` / opener streams, NZB, or other non-media endpoints.
- A web-based addon configuration editor. V1 accepts already-configured manifest
  URLs (users configure addon URLs in their browser, then paste the result).
- Addon content inside the Live TV, Movies, Series, or Guide tabs in v1. Stremio
  content lives in its own destination; Home/global-search integration is a P7
  option.
- Casting torrent-backed streams. See AD-6.

## Why the existing plugin M3U path is not enough

The plugin API can already surface a companion APK as a provider
(`MSG_GET_PROVIDER_URL`, `StreamVaultPluginManager.kt:661`) and can prepare playback
URLs (`MSG_PREPARE_PLAYBACK`, `StreamVaultPluginManager.kt:481`), but it cannot
produce the Stremio experience:

- The M3U importer flattens everything into channels/movies/series rows with a
  single stream URL. Plugin providers are additionally forced to live-only
  classification (`m3uVodClassificationEnabled = false` reset at
  `StreamVaultPluginManager.kt:700`; importer treats entries as LIVE at
  `SyncManagerM3uImporter.kt:240`).
- Stremio's model is one meta to many streams, with user selection at play time.
  No catalog rows, meta pages, or stream-picker UI exist in StreamVault's catalog
  surfaces (`feature/catalog/.../presentation`).
- A plugin cannot inject UI into the host. Only its own configuration Activity.
- Sync-based ingestion is the wrong data flow: Stremio catalogs are remote,
  dynamic (IMDB-id keyed), paginated, and change per request. They must not be
  copied into the synced provider catalog tables.

Conclusion: the host application must become the Stremio addon client. The plugin
boundary is kept only where it is genuinely needed — torrent resolution — using the
existing `playback.prepare` capability with a `magnet:` scheme (see AD-3).

## Stremio addon protocol summary (implementation-relevant)

Transport: plain HTTP(S) JSON endpoints under the addon base URL.

- Manifest: `GET {base}/manifest.json` → `id`, `version`, `name`, `description`,
  `logo`, `background`, `resources` (e.g.
  `["catalog","meta","stream","subtitles"]`), `types` (`movie`, `series`,
  `channel`, `tv`), `catalogs` (`type`, `id`, `name`, optional `extra`,
  `genres`), `idPrefixes` (e.g. `["tt"]`), `behaviorHints`
  (`configurable`, `configurationRequired`).
- Catalog: `GET {base}/catalog/{type}/{id}.json`, with extras:
  `/catalog/{type}/{id}/skip={n}.json`, `/genre={genre}.json`,
  `/search={query}.json`. Response: `metas` array of meta previews plus optional
  `hasMore`.
- Meta: `GET {base}/meta/{type}/{id}.json` → `meta` with full detail; series meta
  carries `videos` (seasons/episodes, id format `tt1234:1:2`).
- Stream: `GET {base}/stream/{type}/{id}.json` → `streams` array. A stream is one
  of: `url` (http/https direct, debrid addons use this), `infoHash` (+ optional
  `fileIdx`, tracker `sources`), `ytId`, `externalUrl`. Optional `name`, `title`,
  `description`, `behaviorHints` (`notWebReady`, `bingeGroup`, `filename`,
  `videoSize`, `proxyHeaders`).
- Subtitles: `GET {base}/subtitles/{type}/{id}.json` (plus optional
  `/videoHash=...`/`/filename=...` extras) → `subtitles` array with `id`, `url`,
  `lang` (3-letter codes).
- Install links: `stremio://host/path/manifest.json` is an install alias for the
  corresponding `https://` manifest URL. Many addons are configured by query
  parameters, so the "install URL" is the whole configured manifest URL and must
  be stored verbatim (minus the scheme rewrite).

Compatibility notes for the client:

- Unknown fields anywhere must be ignored (forward compatibility).
- `resources` may be objects with per-resource `types`/`idPrefixes`; catalog
  entries may each declare their own `extra`.
- Missing `idPrefixes` means the addon handles all ids of its declared types.
- Long catalog/meta responses can be large; keep timeouts and page sizes bounded.

## Architecture decisions

### AD-1: Host-native Stremio addon client

The host implements the protocol directly (HTTP + kotlinx.serialization + OkHttp,
following existing data-module network client conventions and the Hilt `Json`
config in `app/.../di/NetworkModule.kt`). Addons are ordinary HTTP resources; no
plugin or WebView is required. This is the only way to get native catalog/meta/
stream-picker UX in the host, and it keeps the plugin contract untouched for
P0–P5.

### AD-2: Separate content store, not a provider type

Stremio addons are not `ProviderType` entries and do not run through provider sync.
New Room table `stremio_addons` (P1) stores install records; catalogs/meta/streams
are fetched on demand (bounded in-memory cache per session; a persistent response
cache is explicitly deferred). This avoids polluting provider-scoped catalog DAOs,
`ProviderSnapshot` assumptions, and the sync pipeline. `LegacyProvider`/
`ProviderSnapshot` (`domain/.../model/Provider.kt`, `ProviderConfiguration.kt`)
are untouched by this feature.

### AD-3: Torrent playback via companion plugin, existing IPC

The plugin IPC already supports this without contract changes:

- `preparePlaybackStreamInfo` accepts any non-blank URL; a `magnet:` URI has no
  host, so scheme-only ownership applies (`PluginPlaybackRouting.kt:37-41`). A
  plugin declaring `playbackUrlSchemes: ["magnet"]` receives
  `MSG_PREPARE_PLAYBACK` and can return `output_url` (an http loopback proxy),
  `stream_type`, and headers (`applyPlaybackPreparationResponse`,
  `StreamVaultPluginManager.kt:553`).
- What blocks magnet today is only ingress: `StreamEntryUrlPolicy` /
  `UrlSecurityPolicy` allowlists (http, https, rtsp, rtsps, rtmp, file, content)
  reject `magnet:` before playback. Addon streams bypass those provider-path
  policies and get their own ingress policy (AD-5).

Decision: the torrent engine (libtorrent4j or equivalent JNI) lives in a separate
companion APK in its own repository, not in this repo or the main APK. The host
learns nothing about torrents beyond "a `magnet:` URL may be resolved by an
enabled plugin".

### AD-4: One top-level "Extensions" destination

Keep separate `AppDestination.Plugins` and `AppDestination.Addons` routes and
startup landing options, but show one `AppTopLevelDestination.EXTENSIONS`
destination in the global navigation. Its screen has focusable in-page tabs for
native APK Plugins and Stremio Add-ons, so both features remain distinct without
using two top-level slots. Migrate saved `plugins` and `addons` top-level
navigation values to `extensions`; retain Plugins and Stremio Add-ons as
separate startup choices while Extensions is visible. The addon manager remains
inside Stremio Add-ons, and torrent plugins remain managed as APK plugins.
Files: `domain/.../model/AppTopLevelDestination.kt`,
`domain/.../model/AppLandingDestination.kt`,
`app/.../navigation/AppRouteCodec.kt`, `app/.../navigation/AppNavigationAdapters.kt`,
`app/.../ui/components/shell/AppShellNavigation.kt`,
`core/ui/.../components/shell/ExtensionsSectionTabs.kt`,
`app/.../navigation/StartupNavigationResolver.kt`, `app/.../navigation/AppNavHost.kt`.

### AD-5: Dedicated addon-stream ingress policy

Do not reuse provider URL policy. Introduce a Stremio-specific validation:

- Playable now: `http`, `https`, `magnet` (magnet only resolvable with the torrent
  plugin enabled).
- Recognized but unsupported: `ytId`, `externalUrl`, `nzbUrl` → show a clear
  "not supported" row state in the picker, never a broken player start.
- Everything else: rejected.
Also sanitize poster/background/logo URLs (https preferred), bound response sizes,
and never forward addon-supplied headers to non-addon hosts.

### AD-6: Cast and external-playback stance

- `magnet:` streams are never castable. Add an explicit guard in
  `CastMediaRequestFactory` (`feature/playback/.../cast/`) so an unresolved magnet
  can never reach a receiver; the picker hides the Cast action for them.
- After plugin resolution the URL is `http://127.0.0.1:<port>/...`; the existing
  cast rewrite path (`cast.rewriteUrl` with `LOCAL_URI` reason) already handles
  loopback URLs — without a cast-capable plugin the cast attempt fails as
  `UNSUPPORTED`, which is acceptable and should surface a clear message.
- External player handoff: only resolved http(s) URLs are offered; the external
  launcher allowlist already excludes `magnet`. Stremio external-player handoff
  is outside direct/debrid v1 because the launcher cannot preserve origin-scoped
  request headers.
- Casting rejects Stremio media with addon-supplied request headers, since the
  receiver cannot preserve their origin scope. Headerless direct streams use the
  normal cast path. This behavior is covered by unit tests.

### AD-7: Phase gating — direct/debrid first, torrents at P6

P0–P5 ship a complete, useful product for addons that return direct URLs
(debrid-backed Torrentio, catalog addons, etc.). P6 adds the torrent plugin and
the magnet flow end to end. This keeps legal exposure and schedule risk out of the
main repository for as long as possible.

### AD-8: Content identity, resume, and history

Do not use `PlaybackHistory` for addon items. That table requires a real provider
row through a foreign key, numeric `contentId`/`providerId`, and its replay
navigation currently reuses the stored `streamUrl`. Those assumptions do not fit
remote Stremio IDs, and a stored direct/debrid URL may expire.

Add a `StremioPlaybackHistory` model/repository and a Room table keyed by a
canonical string `contentKey`. The table stores the exact Stremio item/video ID,
type, parent series ID where applicable, display metadata, resume position,
duration, watched state, and catalog-origin namespace. It does not store a
playable URL, stream headers, or addon response bodies. A selected stream's addon
namespace may be kept as a preference hint, but never as content identity.

Identity rules:

- Preserve protocol IDs byte-for-byte after the existing trim validation; do
  not synthesize episode IDs from season/episode numbers. The `meta.videos[].id`
  value is the episode's request ID; retain the parent `meta.id` separately.
- Recognize only validated IMDb IDs (`tt` followed by digits) as cross-addon
  identities: `stremio:global:{type}:{exact-id}`. Episode keys use the exact
  `video.id` under `series-episode`; the parent series key remains separate.
- Namespace every other ID by the catalog-origin install identity:
  `stremio:addon:{source-key}:{type}:{exact-id}`. `source-key` is a
  domain-separated SHA-256 fingerprint of the persisted configured manifest
  URL (preserving raw encoded path and query bytes), never a numeric playback
  ID. This survives uninstall/reinstall of that
  same configured addon without storing a credential-bearing URL in history;
  do not log the fingerprint or configured URL. Different addon installs with
  colliding custom IDs remain separate.
- Keep `catalogOrigin`, `metaOrigin`, and selected `streamOrigin` distinct.
  Switching stream providers for a global ID preserves progress. A custom ID
  stays scoped to its catalog origin even when another addon can answer the
  same prefix.

Add an optional typed Stremio playback reference to `PlayerNavigationRequest`.
The player uses it to read/write addon progress through the Stremio history
repository; it does not fabricate numeric provider/content IDs. History replay
routes back through the Addons feature, resolves fresh metadata and streams, and
shows the picker unless a prior stream choice can be safely revalidated. For a
global IMDb ID, reinstalled/other eligible addons may provide fresh streams. For
a custom ID, try its origin first and keep the item unavailable until that
source is reinstalled if no eligible metadata/stream source can resolve it.
Keep an Addons-owned Continue Watching row in v1; integrating the existing
provider-only Watch Next/history surfaces is a later explicit project.

### AD-9: Resource routing across installed addons

Use a single `StremioResourceResolver` for meta, stream, and subtitle requests.
Its candidate input is the enabled installed-addon snapshot, resource kind,
Stremio type, exact requested ID, and optional preferred origin. Catalog
selection controls only which catalog rows are shown; disabling a catalog does
not suppress that addon's meta/stream/subtitle capability. A disabled addon is
never a candidate.

Match manifests using protocol filters, preserving missing versus explicitly
empty arrays in the parsed model. A string resource uses manifest-level `types`
and optional `idPrefixes`; an object resource uses its resource-level type
filter (falling back to manifest-level types only when omitted) and its own
optional `idPrefixes`. Omitted prefixes mean any ID; an explicitly empty prefix
list matches no IDs. `catalog` is routed only through the catalog declaration,
not by ID-prefix matching. Compare prefixes against the exact request ID.

For meta, query the eligible catalog-origin addon first, then remaining eligible
addons in installed order. Empty and failed responses fall through; cancellation
propagates. If no meta succeeds, keep the catalog preview usable. A movie may
still request streams using its item ID; a series without `videos` stays
preview-only and cannot invent episode IDs.

For streams and subtitles, fan out to eligible enabled addons with bounded
concurrency (maximum four in flight). Preserve installed order while merging
results, attach addon attribution to each row, expose partial errors/retry per
addon, and cancel the fan-out when item or screen scope changes. Apply
`idPrefixes` to the exact movie ID or exact episode `video.id`, never to the
parent series ID by assumption. Subtitle requests use the protocol's video ID
and carry available hash/size/filename separately.

### AD-10: Catalog page and cache contract

Preserve optional `hasMore` as nullable and retain raw response count before
filtering/deduplication. A catalog advertises `skip` before the UI offers
pagination. Request 100 raw items per page; when `hasMore` is absent, continue
only after a full raw page. When present, honor it, but guard against repeated
page fingerprints and impose a per-session page cap. Advance `skip` by raw
response count, not the number of renderable cards. Never paginate a catalog
without `skip`.

Required extras must be satisfied before making a request. V1 supports `search`,
`genre`, and `skip`; a required unknown extra disables that catalog row with a
clear unsupported-requirement message. Search-only catalogs wait for a nonblank
query. Reset pagination and stale-request ownership on query/filter changes.
Keep the P2 cache bounded to 200 pages with a five-minute TTL; key it by
configured-addon fingerprint, catalog type/id, normalized search/genre, and
skip. Invalidate affected entries on addon removal, manifest refresh, or catalog
setting changes.

## Phases

| Phase | Work item | Status | Exit criteria |
| --- | --- | --- | --- |
| P0 | Protocol client + models | Done | Domain models (manifest/catalog/meta/stream/subtitle), lenient DTO mapping, URL normalization (`stremio://` → https, configured paths, query preservation) and endpoint builders, `StremioAddonClient` with bounded responses and fixture-based tests — 22 tests passing (`StremioAddonClientTest`, `StremioAddonUrlTest`) |
| P1 | Persistence + addon manager | Implemented and verified | Room migration 78 → 79 (`stremio_addons`) with `MigrationTestHelper` coverage and schema export per `docs/DATABASE_MIGRATION_INVARIANTS.md`; add/remove/enable/reorder/catalog-select; configuration-required manifests rejected with a clear message; unit tests and emulator install smoke pass. Remaining release concerns are tracked in `docs/STREMIO_ADDONS_PLAN_REVIEW.md`. |
| P2 | Catalog browsing | Implemented and verified | Browse/Manage tabs with enabled per-addon catalog rows; nullable `hasMore`, raw-count 100-item pagination only when `skip` is advertised, required `search`/`genre` gating and unsupported-extra states, stale-request cancellation, refresh-aware bounded cache, loading/empty/error states, and visible D-pad focus on TV. Seven browse ViewModel tests pass; Cinemeta catalog fetch and TV card-focus smoke pass on API 36. |
| P3 | Meta + stream picker | Implemented and verified | Catalog cards open a detail page with provenance-first metadata fallback, preview-only behavior, exact episode IDs, attributed cross-addon stream rows, bounded partial fan-out and per-addon retry, visible unsupported stream kinds, and a typed player handoff. Focused domain, data, and feature tests plus app unit tests and debug assembly pass. API 36 TV smoke verified Cinemeta detail/provenance and the no-stream-provider state; it did not validate playback. |
| P4 | Playback integration | Implemented; fixture playback verified | Direct/debrid streams use the existing player preparation path with media headers scoped to playback requests; Room v80 stores Stremio progress without URLs or headers; resume/history reopening resolves fresh streams; magnet cast/playback states are explicit. Tests, MP4/history smoke, and all 45 migration instrumentation tests pass on isolated API 36, including 79→80. Synthetic HLS completed at 120 seconds over HTTP/1.1; HTTP/1.0 serving produced transient EOFs. Remote Mux HLS passed through the local Stremio fixture. On a clean dedicated API 36 AVD, a provider HLS fixture passed a 145-second advancing hold and 61 screenshots over 120.26 seconds (60 unique frames due to the fixture loop); progressive MP4 reached first frame. This is fixture VOD coverage. A real third-party addon HLS/DASH and child-resource smoke remains external qualification because no suitable public-domain addon was found. Same-origin scoped headers are applied by the playback policy, and cross-origin redirect removal is integration-tested. Cast rejects scoped addon headers but allows headerless direct streams; external-player handoff is outside v1. |
| P5 | Subtitles | Implemented; partial emulator verification | Inline and addon subtitles are carried to the player, addon resources load lazily, request extras include hash/size/filename, language codes map to Android locales, and external tracks use `PlayerEngine.addExternalSubtitle`. The CC picker remains available for Stremio subtitles even when the stream has no embedded text track. Tests/build and independent subtitle-only addon playback smoke pass. |
| P6 | Torrent companion plugin | Implementation complete; user acceptance pending | Host: validated `infoHash`/tracker/file-index conversion, scheme-owned plugin prepare, clear missing/unhandled plugin errors, loopback-only output validation, and best-effort `MSG_STOP_PLAYBACK`. Separate repo/APK: libtorrent engine, loopback HTTP range streaming server, `playbackUrlSchemes: ["magnet"]`, and configuration Activity with buffer/speed/status/stop/clear controls. Unit tests and debug build pass. User still needs to verify real torrent playback, seeking, replay, and teardown with a torrent-backed addon. |
| P7 | Required polish + optional integrations | Implemented; optional Home/global search deferred | Conservative parental gate is implemented, unit-tested, and smoke-tested on TV at Off/Locked/Private/Hidden; Private omits Stremio continue-watching from the aggregate. Extensions and the Plugins/Stremio Add-ons landing destinations are wired. README, handoff, plan review, changelog, and the user acceptance checklist document the feature and its limitations. Optional Home/global search remains deferred. |

Effort estimate: P0–P5 ≈ 5–6 weeks for a shippable direct/debrid v1; P6 adds
≈ 4–8 weeks (torrent engine quality on TV hardware is the dominant risk); P7
≈ 1–2 weeks. Total to full parity ≈ 2–4 months.

## Host change map

New module `feature:stremio` (add to `settings.gradle.kts`), following the
`feature/catalog` conventions:

- `feature/stremio/src/main/java/com/streamvault/feature/stremio/presentation/`
  — AddonsScreen, AddonInstallDialog, AddonManagerScreen, StremioMetaScreen,
  StremioStreamPickerScreen.
- `feature/stremio/.../api/` — ports consumed by `app` (navigation actions,
  playback handoff), mirroring `feature/catalog/.../api`.

Data layer:

- `data/.../local/` — `stremio_addons` and `stremio_playback_history` entities,
  Room version 80, migrations `78 → 79` and `79 → 80`, and schema exports.
- `data/.../stremio/` — `StremioAddonRepository`, `StremioCatalogClient` (HTTP),
  response models, bounded cache.

Domain:

- `domain/.../model/` — addon, catalog, meta, stream, subtitle models (pure
  Kotlin), plus `AppTopLevelDestination.ADDONS` / `AppLandingDestination.ADDONS`.

App wiring:

- `app/.../navigation/AppRouteCodec.kt`, `AppNavigationAdapters.kt`,
  `StartupNavigationResolver.kt`, `AppNavHost.kt` (+ new graph registration),
  `app/.../ui/components/shell/AppShellNavigation.kt`.
- `app/.../di/` — repository/client bindings.

Playback (small, surgical):

- `feature/playback/.../cast/CastMediaRequestFactory.kt` — magnet guard.
- `feature/playback/.../player/PlayerPreparationCoordinator.kt` — already routes
  through plugin prepare (`:72`); verify non-http probe skip (`:193`) and that a
  magnet that fails plugin resolution produces a user-facing error rather than an
  engine start.
- `player/.../Media3PlayerEngine.kt` / `PlayerEngine.kt` — no changes expected for
  P4; P5 uses `addExternalSubtitle`.

Plugin docs:

- `docs/PLUGIN_API.md` — appendix: torrent-style plugins declare
  `playbackUrlSchemes: ["magnet"]`, must return loopback `output_url`, must never
  expect host headers/credentials, and are non-castable.

## Companion torrent plugin (P6) — integration spec

Separate repository and APK (working name StreamVault Torrent Plugin). It uses the
existing plugin API; the host needs zero torrent-specific code.

- Manifest: `capabilities: ["playback.prepare", "configuration.activity"]`,
  `playbackUrlSchemes: ["magnet"]`, no `playbackUrlHosts`,
  `configurationMode: "activity"`.
- `MSG_PREPARE_PLAYBACK` with `input_url = magnet:?...` (or assembled from
  `infoHash` + trackers):
  1. Start/attach a torrent session (foreground service, persistent notification).
  2. Prioritize the target file (respect `fileIdx` when present; otherwise largest
     video file), streaming piece deadlines around the play head.
  3. Serve ranges on `http://127.0.0.1:<port>/stream/<token>`; return
     `success=true`, `handled=true`, `output_url`, `stream_type=progressive`
     (or `hls` if the plugin remuxes), and an `expirationTime`-like short-lived
     token recommendation (host currently has no expiry field in the response —
     acceptable; the URL stays valid while the session is alive).
  4. Return `success=false` with a user-facing `message` for magnets it cannot
     resolve; the host then shows the plugin's message.
- Config Activity: download directory, buffer target, upload/speed caps, connected
  peer/status view, active session list, stop/clear actions. TV-first layout per
  the native Activity guidance in `docs/PLUGIN_API.md`.
- Security posture: loopback bind only, no LAN exposure by default, no telemetry,
  session teardown when playback stops.
- Legal posture: engine and indexing live only in this separate APK/repo; the main
  app references nothing torrent-specific in code, branding, or docs beyond the
  plugin capability description.

## Testing and validation strategy

- P0–P1: pure unit tests for URL normalization, manifest validation, extra/query
  encoding, response parsing (fixture JSON committed under test resources,
  including malformed/unknown-field cases).
- P1: Room migration tests per `docs/DATABASE_MIGRATION_INVARIANTS.md`
  (`StreamVaultDatabaseMigrationTest` adjacent hop + populated multi-hop origin),
  schema JSON export committed, `PRAGMA foreign_key_check` on the new table.
- P2–P3: viewmodel/repository tests with a fake client; D-pad focus checks on
  emulator for the new screens (TV-first).
- P4: emulator smoke confirmed direct MP4 playback and history/resume. The
  synthetic HLS asset completed for 120 seconds over HTTP/1.1 on the same host
  port; local HTTP/1.0 serving produced transient network EOFs. A remote Mux HLS
  sample played through the local Stremio fixture for 61 screenshots at a
  two-second cadence (61 unique hashes), with HLS prepare/first-frame logs and a
  healthy session. A separate provider HLS fixture reached first frame and
  advanced for 145 seconds; 61 screenshots over 120.26 seconds had 60 unique
  hashes because the synthetic source loops at 120 seconds. Media session was
  PLAYING with `error=null`; logs showed HLS playlist/segment reads without retry,
  fatal error, stuck timeout, or MPEG-TS fallback. Progressive fixture MP4 also
  reached first frame. This remains fixture VOD evidence. Verify a real
  third-party addon endpoint and its redirect/header behavior before closing
  that gate. Confirm magnet-without-plugin does not start the engine.
- P5: tests cover inline/addon subtitle conversion and external playback paths.
  Emulator smoke confirmed inline English discovery and selected a German track
  from an independent subtitle-only addon; its WebVTT cue rendered during playback.
  Episode-switching subtitle isolation remains a regression check.
- P6: end-to-end on emulator with the torrent plugin APK: magnet → plugin prepare
  → first frame; seek forward/back; plugin stop tears the session down; capture
  log evidence analogous to the AGENTS.md live playback validation discipline
  (screenshot cadence during the stream, player health, no unintended fallbacks).
- Regression: run existing migration suite and feature/catalog tests; verify no
  changes to provider sync behavior.

## Risks and mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Addon ecosystem drift (protocol variance, giant responses) | Broken browsing | Lenient parsing (ignore unknown fields), bounded page sizes/timeouts, per-addon error surfaces, fixture tests |
| Legal exposure for torrent support | Takedown/abuse reports | Engine only in separate plugin APK/repo; main repo has no torrent code; document posture |
| TV hardware limits for torrent streaming | Poor playback | Buffer/peer caps, file selection heuristics, clear errors; validate on 4K TV emulator and real device before calling P6 done |
| Room migration mistakes | Data loss | Follow `DATABASE_MIGRATION_INVARIANTS.md`; migration tests + schema export as a hard gate |
| Adult content via addon catalogs | Parental controls bypass | Treat all unrated Stremio content as protected; gate install, catalogs, details, playback, and history through the existing parental PIN policy; deny all at Hidden |
| Cast expectations | User confusion | Cast hidden for magnet; resolved loopback streams surface the existing `UNSUPPORTED` path with a clear message |
| Scope creep (config webviews, Home integration early) | Slips v1 | Phase gate: P0–P5 plus required P7 polish is direct/debrid v1; P6 torrents and optional Home/search integration are follow-ons |

## Resolved implementation decisions

- Stremio metadata has no dependable age-rating coverage, so the entire feature
  is treated as protected: Off allows access, Locked/Private require a PIN once
  per process session, and Hidden blocks the feature. Private omits the Stremio
  continue-watching aggregate. Route gates prevent protected screens and their
  requests from starting before unlock; view-model entry points fail closed.
- Unsupported YouTube/external stream kinds stay visible as disabled rows with a
  reason.
- Subtitle language codes map through the existing player locale/preferences
  path; no Stremio-specific preference was added.
- Catalog responses use a bounded in-memory cache; disk persistence is deferred
  unless browsing performance requires it.
- V1 accepts copy/paste install links; `stremio://` intent registration is
  optional.
- V1 rejects `configurationRequired` addons with a clear configure-in-browser
  message.

## Deferred follow-ups

- V1 assumes configured addons return playable direct/debrid HTTP(S) URLs. In-app
  debrid credentials and API-key UI are outside the current scope.
- Manifest refresh with last-known-good fallback, capability changes, and
  catalog-setting reconciliation is post-v1 work.
