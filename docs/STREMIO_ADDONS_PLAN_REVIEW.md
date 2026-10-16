# Stremio addons plan review

2026-10-14 acceptance correction: the blanket Stremio parental gate described
below is superseded. Stremio is open by default with a separate Settings →
Privacy → Hide Stremio Add-ons preference. The prior gate verification remains
historical evidence; use the revised acceptance checklist for current UX.

2026-10-14 acceptance correction: the blanket Stremio parental gate described
below is superseded. Stremio is open by default with a separate Settings →
Privacy → Hide Stremio Add-ons preference. The prior gate verification remains
historical evidence; use the revised acceptance checklist for current UX.

Reviewed 2026-10-11 in `.worktrees/stremio-addons`, branch
`feature/stremio-addons`, against the uncommitted implementation and the official
Stremio SDK protocol documentation. The review itself was a planning/code
inspection pass; subsequent verification results are recorded in
`STREMIO_ADDONS_HANDOFF.md`.

Design follow-up (2026-10-11): resource routing, catalog pagination, and
playback identity now have concrete decisions in implementation-plan AD-8
through AD-10. Those design gates are closed. AD-8 through AD-10 implementation
is now in place; the HLS and remaining release-check gaps are described in the
2026-10-12 update below.

P2 verification update (2026-10-11): AD-10 catalog browsing is implemented and
verified. The handoff records unit-test, build, and API 36 TV emulator evidence.
AD-9 resource routing, AD-8 playback history, and the release gates below were
still open at that review point; see the implementation updates below.

P3 verification update (2026-10-12): AD-9 resource routing and the meta/detail
stream picker are implemented and verified. Tests cover catalog-only,
stream-only, and subtitle-only capabilities, ID-prefix filtering, provenance
fallback, partial fan-out/retry, exact episode IDs, and cancellation. An isolated
API 36 TV smoke confirmed the Cinemeta detail page and no-stream-provider state;
it did not validate playback. P4 and P5 have since been implemented, with the
remaining smoke and release gates recorded below and in the handoff.

Implementation update (2026-10-12): P0â€“P5 are implemented. P4 adds direct
playback, scoped media headers, magnet guards, and URL-free Room progress at
database version 80. Synthetic MP4 playback and history/resume smoke passed. The
synthetic HLS VOD fixture raised transient `unexpected end of stream` errors over
local HTTP/1.0; the same asset completed over HTTP/1.1 on the same host port with
no error. A remote Mux Big Buck Bunny HLS sample then passed 61 screenshots at a
two-second cadence through the local Stremio fixture, with 61 unique frame hashes,
HLS prepare/first-frame logs, and a healthy media session. Confirm against a real
third-party addon endpoint and its redirect/header behavior before closing that gate.
P5 carries inline subtitles and lazily resolves addon subtitle resources; tests,
debug assembly, inline English discovery, and independent subtitle-only playback
smoke pass. On isolated API 36 `emulator-5556`, the fresh `tt7654327` item exposed
the German subtitle-only addon track, selection logged `addExternalSubtitle`, and
the WebVTT cue rendered while media session remained healthy. Direct/debrid v1 is
still gated on a real third-party addon HLS endpoint, the final header/privacy
review, and TV regression checks. The parental PIN gate was smoke-tested at all
four levels on isolated API 36 `emulator-5556` on 2026-10-13; details are below.

Provider-playback update (2026-10-13): on a new clean API 36 TV AVD, the local
V11 provider fixture played HLS through a 145-second health and position-advance
hold and progressive MP4 to first frame. The HLS capture used 61 screenshots at
two-second cadence over 120.26 seconds, with 60 unique hashes because the
synthetic VOD repeats after 120 seconds. During capture media session was
PLAYING with `error=null`; sanitized logs show HLS prepare, first frame, playlist
and segment reads, and no retry/fatal/stuck/MPEG-TS fallback. This closes the
provider-fixture regression check, not the real third-party-addon gate. A check
of the community Public Domain Foreign Movies addon found its manifest host
returns 404 and no `.m3u8` references in source; the official public-domain addon
returns torrents and Archive.org returns direct files. No suitable public-domain
HLS addon was found.

The host-native client, separate addon registry, dedicated Addons route, and
direct/debrid-first delivery are appropriate. The Plugins and Stremio Add-ons
routes are now grouped under one Extensions navigation destination with
focusable in-page tabs. Direct/debrid v1 implementation and local regression
checks are complete; the remaining items below identify external qualification
and the user's manual UI/usage acceptance pass.

## Required additions

### 1. Cross-addon resource routing (P2â€“P3, implemented and verified)

The plan describes picking from "the addon's" streams but does not define how a
catalog-only addon such as Cinemeta connects to stream-only and subtitle-only
addons. Add a resource resolver that queries enabled installed addons matching
resource, media type, and content/video ID. Honor global restrictions and
per-resource overrides, including `idPrefixes`; distinguish absent restrictions
from explicitly empty ones where the protocol requires it. The original
implementation only checked resource/type; P3 now preserves absent versus empty
filters and applies manifest/resource-level type and prefix rules to exact IDs.

Keep catalog origin as provenance. For metadata, define deterministic precedence
(origin first when eligible, then installed order), fallback after empty/failure,
and a useful preview-only state if no metadata provider is available. Never assume
Cinemeta is installed automatically. For streams/subtitles, use bounded concurrent
fan-out, preserve addon attribution/order, allow partial results and per-addon
retry, and cancel obsolete requests when leaving/changing the item. Disabling
catalog rows must not disable that addon's stream/subtitle resources.

Acceptance: browse a Cinemeta item and see streams from a separate stream-only
addon; one failing addon does not hide successful results; an unrelated ID prefix
is never queried; disabling an addon removes it from resource routing. These
cases are covered by domain/repository/feature tests; the TV smoke verified the
metadata detail and preview state, not real multi-addon playback.

### 2. Catalog capability and pagination semantics (P2, implemented and verified)

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

### 4. Media headers and credentials (P1/P4, implemented; external HLS qualification open)

P4 implements origin-scoped selected-stream headers. The playback client avoids
shared interceptors/cookies/authentication state, applies credentials only to
the configured media origin, and strips them after a redirect to another host.
Addon URL parsing preserves encoded path bytes, and playback diagnostics redact
private origins and paths. Tests cover origin matching and sanitization.

The local MockWebServer test confirms a cross-origin 302 removes Authorization;
the playback client also isolates cookies, authenticators, and cache. Every HLS
or DASH child request uses the same origin-scoped media policy. Credential
policy: configured manifest URLs remain in app-private Room; do not log, back up,
or manually export addon records; show only the server origin in Manage; Remove
deletes the active addon row, while disabling retains it. Room is not app-level
encrypted and relies on the Android app sandbox and device storage encryption.
Stremio streams with scoped headers are rejected by casting; headerless direct
streams can use the normal cast path. External-player handoff is outside v1
because it cannot preserve the header scope. The public-domain addons checked
did not provide a suitable HLS/DASH endpoint, so real-addon child-resource
playback remains external qualification rather than a local implementation gap.
The app's network security configuration allows HTTP for media sources.

Acceptance: encoded configuration paths round-trip; a header-dependent stream
plays without credentials leaking to unrelated hosts; sanitized diagnostics
contain no configured URL secrets. Unit and MockWebServer redirect coverage pass.
A suitable real addon remains needed to confirm end-to-end HLS/DASH child
requests with its own manifest and media URLs.

### 5. P1 completeness and manifest lifecycle

Resolved P1 finding: `StremioAddonRepositoryImpl.installAddon` rejects
`configurationRequired` manifests with a configure-in-browser/paste-configured-URL
message, and the configured install URL is the registry identity.

Manifest refresh with last-known-good fallback, version/capability changes, and
catalog-selection reconciliation remains a post-v1 follow-up. Document validation
boundaries for malformed or unusable manifests while retaining valid stream-only
and subtitle-only addons. V1 supports copy/paste install links; OS `stremio://`
intent registration remains optional. Keep the manager separate from catalog
browsing within the Addons destination.

The compile failure recorded in `stremio-test-run2.log`
(`AddonsViewModelTest.kt:157` could not resolve `StremioManifestResource`) is
historical; the import was fixed and the current Stremio/app tests and debug build
pass. Emulator smoke confirmed addon installation. Repository/ViewModel tests
cover duplicate and configuration-required installs, enable/disable, reorder,
catalog selection, and removal. The manual acceptance pass should confirm
retention after app restart and D-pad focus through the manager.

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

Implemented: inline `stream.subtitles` and enabled subtitle-resource addons are
carried to playback. Requests include the exact video ID and available
`videoHash`, `videoSize`, and `filename` extras. Results are deduplicated with
provenance, three-letter language codes are mapped, and external tracks use the
existing player path and preferences. The subtitle picker appears when Stremio
sources exist even if no embedded text track is present. Focused tests/build and
the inline English picker smoke pass. An isolated API 36 TV smoke selected a
German track from an independent subtitle-only fixture and rendered its WebVTT
cue over playback.

Remaining acceptance: verify that switching episodes cannot retain the previous
subtitle request/result.

### 8. Parental controls and content-type scope (before v1 release)

The parental-control decision is now implemented ahead of v1. Addon catalogs
and metadata do not provide dependable age ratings, so all Stremio content is
treated as protected: Off allows access, Locked and Private require the existing
PIN once per app session, and Hidden blocks the feature, including installation.
Both browse and item routes are gated before screen construction/network fetches;
install, catalog, metadata, and stream actions also fail closed. Private omits
Stremio continue-watching from the aggregate surface. Feature tests cover each
level and blocked install/catalog/detail requests. The isolated API 36 TV smoke
verified Hidden denial, Locked/Private PIN prompts, wrong-PIN rejection, valid
unlock, re-lock after a level change, and Off access without a PIN; the original
emulator preferences were restored and hash-verified. This release gate is
closed.

Explicitly define v1 as movie/series playback, with `tv`/`channel` browsing or
playback deferred unless separately implemented. The current model recognizes
these types, while the plan's playback/resume design assumes VOD. Newer guide and
archive-source capabilities remain unsupported v1 states; preserve a visible
unsupported row rather than silently dropping identifiable stream kinds.

### 9. Release validation and documentation (P4â€“P5 release gate)

Add a deterministic multi-addon fixture setup covering catalog-only, stream-only,
subtitle-only, custom episode IDs, missing `hasMore`, required extras, redirects,
headers, unsupported stream kinds, cancellation, partial failures, and expiring
URLs. Use legitimate direct MP4 and HLS media; exercise seek, resume after restart,
audio/subtitle selection, TV focus restoration, and existing provider playback.

First frame/build/install alone are not playback acceptance. The provider
fixture now covers a 145-second advancing HLS playback window and progressive
first frame; real addon HLS/DASH and child-resource behavior remains open. Capture
progression and sanitized player logs across a meaningful playback window. If live addon
playback is added, follow AGENTS.md exactly: at least 45 screenshots at two-second
intervals (prefer 61 for prior one-minute failures), healthy media session, no
fatal/stuck/unintended fallback, and multiple channels for general live bugs.
Do not claim live validation from the VOD fixture runs.

Separate mandatory v1 polish/documentation from optional Home/search integration
and P6 torrent work. Revise the schedule after identity and plugin spikes; the
original estimates do not account for these unresolved integration contracts.

## Recommended continuation order

1. P1 registry/configuration, verification, and commit are complete; results
   are in the handoff.
2. P2 catalog browsing against AD-10 is implemented and verified; results are
   recorded in the handoff.
3. P3 cross-addon routing and the detail/stream picker against AD-9 are complete
   and verified; results are recorded in the handoff. P4/P5 are implemented, and
   the direct MP4/history and independent subtitle-addon smoke checks passed.
4. Parental PIN TV smoke at Off, Locked, Private, and Hidden is complete. D-pad
   navigation through Extensions and its Plugins/Stremio Add-ons tabs also
   passes. No suitable public-domain third-party HLS addon was found: the
   official Public Domain Movies addon returns torrent hashes, while the
   maintained Archive.org addon returns direct file URLs and its published host
   no longer resolves. The community Public Domain Foreign Movies endpoint also
   returns 404 and its source has no HLS playlist references. Keep the real-addon
   HLS gate open; neither local fixture smoke satisfies that gate.
5. Provider playback passes with a dedicated fixture. Direct/debrid v1 code and
   local regression gates are complete. A real third-party addon HLS/DASH and
   child-resource smoke remains external qualification because the public-domain
   candidates checked so far do not provide usable HLS. Same-origin headers are
   scoped in the player request policy and the cross-origin redirect integration
   test confirms credentials are removed. Casting rejects streams with scoped
   addon headers and allows headerless direct streams. External-player handoff
   is outside v1.
   The install-URL secret storage, backup/export, Manage display, and removal
   policy is documented in the handoff and enforced by the origin-only row.
6. Keep the P6 torrent-plugin feasibility spike deferred until the direct/debrid
   user acceptance pass is reviewed. Home/global search remains optional after
   v1.

## Primary protocol references

- [Resource routing and cross-addon IDs](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md)
- [Manifest resources, required extras, and adult/configuration hints](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/manifest.md)
- [Catalog pagination](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineCatalogHandler.md)
- [Stream headers, file indices, tracker sources, and inline subtitles](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/responses/stream.md)
- [Subtitle request extras](https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/api/requests/defineSubtitlesHandler.md)
