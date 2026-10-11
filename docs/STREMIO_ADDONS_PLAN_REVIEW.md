# Stremio addons plan review

Reviewed 2026-10-11 in `.worktrees/stremio-addons`, branch
`feature/stremio-addons`, against the uncommitted implementation and the official
Stremio SDK protocol documentation. The review itself was a planning/code
inspection pass; subsequent verification results are recorded in
`STREMIO_ADDONS_HANDOFF.md`.

The host-native client, separate addon registry, dedicated Addons destination,
and direct/debrid-first delivery are appropriate. Keep those decisions. The
following additions are needed before treating the plan as implementation-ready.

## Required additions

### 1. Cross-addon resource routing (P2–P3, release blocker)

The plan describes picking from "the addon's" streams but does not define how a
catalog-only addon such as Cinemeta connects to stream-only and subtitle-only
addons. Add a resource resolver that queries enabled installed addons matching
resource, media type, and content/video ID. Honor global restrictions and
per-resource overrides, including `idPrefixes`; distinguish absent restrictions
from explicitly empty ones where the protocol requires it. The current
`StremioManifest.supportsResource` only checks resource/type, not ID prefixes.

Keep catalog origin as provenance. For metadata, define deterministic precedence
(origin first when eligible, then installed order), fallback after empty/failure,
and a useful preview-only state if no metadata provider is available. Never assume
Cinemeta is installed automatically. For streams/subtitles, use bounded concurrent
fan-out, preserve addon attribution/order, allow partial results and per-addon
retry, and cancel obsolete requests when leaving/changing the item. Disabling
catalog rows must not disable that addon's stream/subtitle resources.

Acceptance: browse a Cinemeta item and see streams from a separate stream-only
addon; one failing addon does not hide successful results; an unrelated ID prefix
is never queried; disabling an addon removes it from resource routing.

### 2. Catalog capability and pagination semantics (P2, release blocker)

`StremioMapper` currently converts absent `hasMore` to `false`. The standard
catalog response does not require that field, so consuming this boolean directly
would stop many catalogs after page one. Preserve whether it was supplied, and
define the standard fallback: only paginate catalogs advertising `skip`; use
100-item pages/offsets and stop on a short page. Track raw response count before
filtering or deduplication so dropped cards do not change server offsets. Support
an explicit `hasMore` extension without creating an infinite repeated-page loop.

Honor required extras, including search-only catalogs and genre options; don't
request a default row when required inputs are missing. Decide which extra names
v1 supports and display a clear limitation for unknown required extras. Reset
pagination on query/filter changes, cancel stale searches, retain focus/scroll
when returning from details, and define bounded cache keys/TTL/invalidation.

Acceptance: a 100-item response without `hasMore` loads page two; a short page
ends pagination; repeated pages cannot cause endless fetching; required-search
catalogs receive requests only after search input exists.

### 3. Playback identity and history reopening (design before P3; implement P4)

AD-8's string key is not directly compatible with the existing pipeline:
`PlaybackHistory` and `PlayerHistoryCoordinator` require numeric `contentId` and
`providerId`. `PlaybackHistoryNavigation` replays a stored `streamUrl`, which can
expire for debrid media or refer to an old plugin loopback session.

Choose an explicit persistence/navigation integration: a dedicated addon progress
store or an extension of the existing identity model, with any required Room
migration. Do not invent a provider row or use an unchecked string hash as an ID.
Keep the exact video ID returned in `meta.videos`; do not synthesize all episode
IDs as `tt...:season:episode`. Keep parent series identity separately. Specify
whether custom addon IDs share a namespace; only merge identities when they
represent the same content. Reopening history must resolve fresh streams and
restore progress, with a picker when automatic selection is not reliable.

Acceptance: progress survives process death and addon reinstall; changing stream
providers retains position for the same video; expired URLs are resolved again;
two unrelated custom-ID addons cannot overwrite each other's progress.

### 4. Media headers and credentials (P1/P4, release blocker)

AD-5 says never forward addon headers to non-addon hosts, while P4 requires
`proxyHeaders.request` for playback. Stream/CDN hosts commonly differ from the
addon API host. Replace this conflict with an explicit media-origin policy:
validated request headers belong to the selected stream's media requests, not to
every addon API/image/subtitle request. Define handling for redirects and HLS/DASH
child resources on other hosts, with credential scoping and fixtures. Response
proxy headers are not outgoing request headers. Header-dependent casting and
external-player handoff need explicit support or a clear unavailable state.

Configured install paths/queries and returned media URLs may contain secrets.
Preserve encoded path bytes (`StremioAddonUrl.parse` currently uses decoded
`URI.path`), redact entire sensitive paths/queries from logs/errors/screenshots,
and decide storage, backup/export, and removal policy. Verify the injected HTTP
client's interceptors do not log configured URLs or attach provider credentials.
Validate schemes for API, media, artwork, subtitles, and plugin output separately;
HTTP support must also be checked against Android network configuration.

Acceptance: encoded configuration paths round-trip; a header-dependent stream
plays without credentials leaking to unrelated hosts; sanitized diagnostics
contain no configured URL secrets.

### 5. P1 completeness and manifest lifecycle (before the P1 gate)

The plan claims rejection of `configurationRequired`, but
`StremioAddonRepositoryImpl.installAddon` currently persists such manifests.
Define a clear configure-in-browser/paste-configured-URL error and implement it.
Manifest parsing currently requires only an ID and defaults missing name/version;
decide which malformed or unusable manifests to reject while still accepting
valid stream-only/subtitle-only addons. Define duplicate identity as configured
install URL (same manifest ID with different configuration can be legitimate).
Map database/uniqueness errors to user-facing failures; serialize or transact
conflicting registry operations rather than letting rapid updates lose settings.

Add explicit manifest refresh with last-known-good fallback, version/capability
changes, and reconciliation of catalog selections. Decide now that v1 supports
copy/paste install links; OS `stremio://` intent registration is optional and must
not be implied by URL parsing support. Separate the manager from catalog browsing
within the Addons destination.

Existing `stremio-test-run2.log` ends in a compile failure:
`AddonsViewModelTest.kt:157` cannot resolve `StremioManifestResource`; its import
is absent in the inspected file. The handoff's previously reported passes are
historical evidence, not fresh verification. Run app, Stremio, settings, and
targeted data tests as separate invocations so data test filters don't exclude
unrelated test classes. Then complete manager emulator validation (install,
duplicate/configuration errors, enable/disable, reorder, catalog selection,
removal, restart persistence, and TV focus), beyond the single install screenshot.

### 6. Torrent plugin contract feasibility (design spike before P6)

Existing IPC can route `magnet:` but the claim that the plugin needs no host
contract work is premature:

- `MSG_PREPARE_PLAYBACK` sends only `input_url`. Specify a documented URI encoding
  for `fileIdx` and tracker sources, or version an IPC field; prove the plugin
  selects the requested episode file. Normalize `tracker:` source prefixes and
  define DHT handling rather than passing source strings blindly as magnet trackers.
- Preparation currently has both a five-second handler deadline and a five-second
  total deadline. Timeout/no handler returns the original stream. A cold torrent
  may exceed this: choose fast loopback endpoint allocation with asynchronous
  buffering, or a bounded readiness/progress contract. Reject unresolved magnet
  output before engine start, including plugin failure, timeout, disabled state,
  and a nominal success response lacking usable output.
- There is no playback-session stop signal in the documented IPC. Define session
  ownership, cancellation, cleanup on switch/back/process death, inactivity expiry,
  and seek/range behavior; an HTTP disconnect alone is not a reliable stop signal.

Keep torrent provenance after resolution. AD-6's possible loopback cast rewrite
conflicts with the stated non-goal of torrent casting: block cast for the whole
torrent-backed session, including resolved URLs. Decide external-player support
separately, since handoff changes session ownership. A separate APK does not itself
settle distribution or licensing requirements; review those when choosing an engine.

Acceptance: file-index preservation, cold-start beyond five seconds, cancellation,
plugin death, seek/range requests, and cleanup all work using a deterministic
fixture before a real-network end-to-end trial.

### 7. Subtitle coverage (P5)

Handle both inline `stream.subtitles` and enabled subtitle-resource addons. The
current DTO/model does not include inline subtitles; `fetchSubtitles` lacks the
standard `videoSize` extra although behavior hints already retain it. Carry video
ID/hash/size/filename through the playback request. Deduplicate with provenance,
handle unavailable tracks without interrupting video, reset on stream/episode
changes, and reuse existing language/style preferences. Verify three-letter
language aliases, unknown codes, and supported formats against player capabilities.

Acceptance: inline-only subtitles and an independent subtitle addon both work;
the correct episode and optional hash/size/filename reach the addon; switching
episodes cannot retain the previous subtitle request/result.

### 8. Parental controls and content-type scope (before v1 release)

Move the parental-control decision out of optional P7 polish. An Addons screen
can bypass restrictions even without Home/search integration. Manifest
`behaviorHints.adult` exists but is absent from the current model. Do not assume
all catalogs/meta expose a reliable adult flag. Decide a conservative behavior for
unrated/custom addon content under a locked profile, and gate install, details,
playback, and history entry points consistently with the app's existing controls.

Explicitly define v1 as movie/series playback, with `tv`/`channel` browsing or
playback deferred unless separately implemented. The current model recognizes
these types, while the plan's playback/resume design assumes VOD. Newer guide and
archive-source capabilities remain unsupported v1 states; preserve a visible
unsupported row rather than silently dropping identifiable stream kinds.

### 9. Release validation and documentation (P4–P5 release gate)

Add a deterministic multi-addon fixture setup covering catalog-only, stream-only,
subtitle-only, custom episode IDs, missing `hasMore`, required extras, redirects,
headers, unsupported stream kinds, cancellation, partial failures, and expiring
URLs. Use legitimate direct MP4 and HLS media; exercise seek, resume after restart,
audio/subtitle selection, TV focus restoration, and existing provider playback.

First frame/build/install alone are not playback acceptance. Capture progression
and sanitized player logs across a meaningful playback window. If live addon
playback is added, follow AGENTS.md exactly: at least 45 screenshots at two-second
intervals (prefer 61 for prior one-minute failures), healthy media session, no
fatal/stuck/unintended fallback, and multiple channels for general live bugs.
Do not claim live validation from the VOD fixture runs.

Separate mandatory v1 polish/documentation from optional Home/search integration
and P6 torrent work. Revise the schedule after identity and plugin spikes; the
original estimates do not account for these unresolved integration contracts.

## Recommended continuation order

1. Close P1 registry/configuration and verification gaps; obtain the requested
   commit decision only after concrete test/emulator evidence exists.
2. Design resource resolution and playback identity before starting P2/P3 UI.
3. Implement P2/P3 with fake multi-addon fixtures, then P4 direct playback and
   history reopening, then P5 subtitles.
4. Ship direct/debrid v1 only after parental controls, privacy, TV interaction,
   and regression validation pass.
5. Run the plugin feasibility spike before committing to the P6 implementation
   spec. Home/global search remains optional after v1.

## Primary protocol references

- [Resource routing and cross-addon IDs](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md)
- [Manifest resources, required extras, and adult/configuration hints](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/manifest.md)
- [Catalog pagination](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineCatalogHandler.md)
- [Stream headers, file indices, tracker sources, and inline subtitles](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/stream.md)
- [Subtitle request extras](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineSubtitlesHandler.md)
