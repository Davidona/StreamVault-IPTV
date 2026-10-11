# Stremio addon support implementation plan

Status: P0 and P1 implemented; P1 unit and emulator smoke verification complete. Plan reviewed
2026-10-11; see `STREMIO_ADDONS_PLAN_REVIEW.md` for required additions and gates.
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

### AD-4: New top-level "Addons" destination

Add `AppDestination.Addons`, `AppTopLevelDestination.ADDONS` ("addons",
`AppLandingDestination.ADDONS`), route constant, shell nav item, and startup
landing mapping. Users can reorder/hide it like existing tabs
(`AppTopLevelDestination.defaultOrder`), and redirect startup to it. The addon
manager UI is part of the Addons surface, not the APK Plugins screen; the torrent
plugin is still managed as an APK plugin under Plugins.
Files: `domain/.../model/AppTopLevelDestination.kt`,
`domain/.../model/AppLandingDestination.kt`,
`app/.../navigation/AppRouteCodec.kt`, `app/.../navigation/AppNavigationAdapters.kt`,
`app/.../ui/components/shell/AppShellNavigation.kt`,
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
  launcher allowlist already excludes `magnet`.

### AD-7: Phase gating — direct/debrid first, torrents at P6

P0–P5 ship a complete, useful product for addons that return direct URLs
(debrid-backed Torrentio, catalog addons, etc.). P6 adds the torrent plugin and
the magnet flow end to end. This keeps legal exposure and schedule risk out of the
main repository for as long as possible.

### AD-8: Content identity, resume, and history

Use a stable identity: `stremio:{type}:{id}` (e.g. `stremio:series:tt1234:1:2` for
episodes). This keeps resume/history working across addons and re-installs and is
the future key for Watch Next integration. Do not map addon items onto synced
catalog ids.

## Phases

| Phase | Work item | Status | Exit criteria |
| --- | --- | --- | --- |
| P0 | Protocol client + models | Done | Domain models (manifest/catalog/meta/stream/subtitle), lenient DTO mapping, URL normalization (`stremio://` → https, configured paths, query preservation) and endpoint builders, `StremioAddonClient` with bounded responses and fixture-based tests — 22 tests passing (`StremioAddonClientTest`, `StremioAddonUrlTest`) |
| P1 | Persistence + addon manager | Implemented and verified | Room migration 78 → 79 (`stremio_addons`) with `MigrationTestHelper` coverage and schema export per `docs/DATABASE_MIGRATION_INVARIANTS.md`; add/remove/enable/reorder/catalog-select; configuration-required manifests rejected with a clear message; unit tests and emulator install smoke pass. Remaining release concerns are tracked in `docs/STREMIO_ADDONS_PLAN_REVIEW.md`. |
| P2 | Catalog browsing | Planned | Addons screen with per-addon catalog rows, pagination (`skip`), genre filter, catalog search, loading/empty/error states, D-pad focus pass on TV |
| P3 | Meta + stream picker | Planned | Movie and series meta pages (videos → seasons/episodes), stream list rendering name/title/description/behaviorHints, unsupported-kind rows marked, selection hands a play request to the player |
| P4 | Playback integration | Planned | Direct http/https streams play through `PlayerPreparationCoordinator` with headers/UA from the addon when declared; resume/history on `stremio:` ids; cast guard for magnet; clear "needs torrent plugin" state |
| P5 | Subtitles | Planned | Addon subtitles fetched lazily in the player, language mapping from 3-letter codes, wired via `PlayerEngine.addExternalSubtitle` (`player/.../PlayerEngine.kt:104`), respects existing subtitle style/preferences |
| P6 | Torrent companion plugin | Planned | Separate repo/APK: libtorrent4j engine + loopback HTTP streaming server; `playbackUrlSchemes: ["magnet"]`; `MSG_PREPARE_PLAYBACK` resolves `magnet:` → `http://127.0.0.1:<port>/...`; config Activity with buffer/speed/status; end-to-end emulator validation with real torrent-backed addon |
| P7 | Polish + integration options | Planned | Parental filtering stance for addon content, startup destination wiring, optional Home row / global search fan-out, docs (`docs/PLUGIN_API.md` magnet appendix, this plan updated to Done), CHANGELOG entries |

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

- `data/.../local/` — `stremio_addons` entity, DAO, database version bump
  (`STREAM_VAULT_DATABASE_VERSION` → 79) and `78 → 79` migration, schema export.
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
- P4: playback validation on emulator with screenshots — direct stream from a
  fixture addon plays to first frame and keeps progressing; resume restores
  position; magnet without plugin shows the explicit state (no engine start).
- P5: subtitle selection test using `ExternalSubtitlePlayback` paths.
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
| Adult content via addon catalogs | Parental controls bypass | P7 filtering stance: per-addon disable + adult catalog/meta flag handling; decide before enabling Home/search integration |
| Cast expectations | User confusion | Cast hidden for magnet; resolved loopback streams surface the existing `UNSUPPORTED` path with a clear message |
| Scope creep (config webviews, Home integration early) | Slips v1 | Phase gate: P0–P5 is the product; P6/P7 are explicit follow-ons |

## Open questions

- Do we ship an in-app "not supported (YouTube/external)" stream row, or hide
  unsupported kinds per addon? (P3 decision, default: show a disabled row with
  reason.)
- Subtitle language mapping from Stremio 3-letter codes to Android locales — do we
  reuse `VodTrackPreference` or add a Stremio-specific preference? (P5.)
- Persistent response caching (disk) — worth it, or session cache only? (Defer
  unless browsing performance proves it necessary.)
- `stremio://` deep-link registration on the host manifest — required for browser
  install buttons, or is copy/paste enough for TV? (P1 decision; manifest change
  is cheap.)
- Per-addon account/config flows for addons whose manifest is
  `configurationRequired` — v1 rejects with a clear message; revisit in P7.
- Debrid API keys: plan assumes addons (e.g. Torrentio) return already-resolved
  direct URLs; no in-app debrid credential UI. Confirm desired.
