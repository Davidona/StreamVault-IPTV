# Stremio add-on compatibility

StreamVault consumes the modern JSON add-on protocol over HTTP(S). This document
records working paths and explicit limits; recognizing a JSON field alone does
not count as playback support.

## Implemented protocol paths

| Area | Behavior |
| --- | --- |
| Catalogs | Movie, series, channel, TV, and custom media-type names; search, pagination, genre and custom filters; required inputs; multi-selection limits; legacy extra declarations; filter-specific caches. |
| Metadata | Cross-addon resolution, exact video IDs, embedded video streams, default-video selection, live/scheduled metadata hints, poster shapes, logo fallback, and metadata links. |
| Native EPG | An Extensions guide for opted-in TV catalogs; local-day navigation; all overlapping UTC-day requests; schedule deduplication and validation; short-page pagination; category hiding and PIN access. Channel IDs remain the playback identity. |
| Streams | Native HTTP(S), scoped request headers, torrent handoff, embedded streams, progressive source lookup, retries, and same-provider binge-group series episode continuity when autoplay is enabled. |
| External playback | YouTube links, external URLs, Android TV app links, player-frame URLs, and supported network-media schemes can open in a suitable external app or browser. |
| Subtitles | Inline and add-on resources, language mapping, custom labels, and filename/hash/size matching inputs. |
| Add-on directories | `addon_catalog` manifests can be browsed and selected for installation. Installation fetches the selected manifest independently; a directory entry never silently installs an add-on. |
| Configuration | Configured manifest URLs preserve path/query data; configurable add-ons have browser configuration actions, including in discovery. |
| Library | Per-addon saved items, last-watched/title/watched sorting, canonical watched state, season/show actions and optional library activity events. IDs are stored locally without stream URLs. |
| Activity events | `player` state/seek/stop events and explicit `library` actions are sent only to enabled, matching add-ons whose activity-sharing switch has been enabled. Sharing is off by default. |
| History | Movies, series, channel videos and custom types use scoped history identities; only validated IMDb identities are globally shared. Live TV has no VOD resume history. |

## Viewing experience (2026-11-02)

Stremio viewing history now feeds Home Continue Watching, Recently Watched,
series-level Up Next and Android TV Watch Next. Resume, Start Over, Details and
Dismiss actions retain exact video/provider identity. Dismiss does not remove
history or saved-library membership. Completed playback and explicit watched
controls share history, with legacy marker fallback.

Saved series have per-series release tracking, new-episode counts and a 90-day
release calendar. Tracking refreshes with history/add-on changes, explicitly,
and on foreground resume after ten minutes. A network-constrained background job
synchronizes connected accounts and checks followed series every two hours.
Android release notifications respect permission, channel blocking, parental
access and per-series tracking. These are periodic release checks, not a
server-push transport. See `STREMIO_EXPERIENCE_AUDIT.md` for details.

The platform Watch Next shelf publishes unfinished progress; synthetic next
episodes appear on the app Home shelf. Live catalogs remain in Extensions >
Browse and the Extensions guide, rather than becoming IPTV-provider channels.

## Playback and transport limits

- Archive, FTP and Usenet sources require a separately running Stremio Service.
  Configure its reachable URL under Extensions > Account. The client converts
  `rarUrls`, `zipUrls`, `7zipUrls`, `tgzUrls`, `tarUrls`, `nzbUrl`/`nzbUrls` and
  FTP sources into the official service endpoints. Usenet additionally requires
  NNTP server information from the stream. Engines are not bundled into Android.
  ZIP, TAR and TGZ were checked against the official service with controlled
  media; RAR/7-Zip and Usenet have protocol tests but no end-to-end playback claim.
- YouTube is opened through an external YouTube app/browser rather than an
  unofficial extraction engine. Other external schemes require an installed
  handler; failures produce a localized error.
- IPFS/IPNS manifest and direct stream URLs resolve through an HTTPS gateway.
  No native P2P node or deprecated Stremio v1/v2 transport is implemented.
- Stremio TV catalogs and guides live under Extensions; they do not create IPTV
  provider/channel rows in the main Live TV tab.

## Ecosystem audit

The implementation follows resource capabilities rather than maintaining a list
of allowed add-on names. The audit covered the SDK protocol, native EPG,
stream formats, the official add-on directory, and CNCVerse Bridge's published
integration instructions. This includes catalog-only, stream-only, subtitles,
TV/EPG, creator-channel, custom-type, external-link, and activity add-ons.

Public manifests and repository documentation are not end-to-end provider
certification. Configured tokens, regional restrictions, upstream availability,
DRM, and codecs can still affect individual sources. A stream-only add-on has
no catalog to display; it participates when resolving an item's streams.

## Validation (2026-11-01)

- 147 targeted domain, data, Stremio feature and playback unit tests passed.
- Debug APK assembled and installed on an isolated, read-only Android TV API 36
  emulator. The Windows environment needed a local build-only workaround for
  Gradle's native class-directory copy stall; no project build configuration was
  changed. APK SHA-256:
  `84d4d4e7ce929f85bc70bd7d3470e379f1eff39c7eedde0924bd283fbed1f252`.
- All 15 added or changed product resource keys are present across all 38
  advertised locales, with translations for the new copy.
- Emulator checks exercised a required custom multi-select filter (US + GB,
  third option blocked), a custom `anime` type, default-video selection,
  embedded stream playback, saved-library browsing and removal,
  watched/unwatched actions, the two-channel native EPG and short-page loading,
  and discovery followed by installation through the normal manifest dialog.
- An eight-second fixture series automatically advanced from Episode 1 to
  Episode 2 using the same provider and binge group, then ended normally.
- Activity sharing remained off in the emulator. Event generation, resource
  filtering and dispatch inputs were covered by unit tests; no public tracking
  service was contacted.

Live checks used controlled HLS fixture channels, not public provider streams:

| Channel | Screenshots | Cadence | Elapsed | Unique hashes | Consecutive unchanged | Final media session |
| --- | ---: | --- | ---: | ---: | ---: | --- |
| Compatibility News | 61 | 2 seconds | 120.252 s | 37 | 0 | PLAYING, error=null |
| Compatibility Sport | 61 | 2 seconds | 120.228 s | 46 | 0 | PLAYING, error=null |

The synthetic source loops every 60 seconds, so non-consecutive screenshot
hashes can repeat. Screenshots continued changing across each full window.
Sanitized logs confirmed HLS prepare, first frame and segment read progression
for both channels, with no fatal error, stuck timeout, ERROR media-session
state, or unintended MPEG-TS fallback. News had two brief early rebuffers and
Sport had one; all recovered. An initial failed fixture run exposed missing
HTTP range handling in the test server; the table records complete reruns after
that server was corrected.

Local evidence is retained under ignored `tmp/stremio-compat/`, including
`validation-summary.json`, per-channel frame hashes, media-session dumps and
`sanitized-evidence.log`. These checks establish client behavior against the
controlled protocol fixtures, not universal public add-on availability.

## Viewing validation (2026-11-02)

- 180 targeted unit tests passed: domain 10, data/backup 75, Stremio feature 71,
  app navigation/launcher 24. No failures or errors. Debug APK assembled,
  installed and checked on the isolated API 36 TV emulator. SHA-256:
  `4a74c1f093a48f188e2afd1cc78b3232e98469518fd7540cbf720d070bea6056`.
- Two instrumentation tests passed on Android TV API 36: release-date offset
  handling in the bundled Android time library, and native Watch Next progress
  plus its exact-item replay intent.
- Manual checks verified Home without an IPTV provider, retained completed
  history, Up Next for the released second episode, tomorrow’s third episode in
  the calendar, dismiss preserving progress, start-over playback, resume, bulk
  watched/unwatched actions and persisted per-series tracking controls.
- Real schema 84→91 migration preserved a 12,000/60,000 ms history row and
  initialized its dismissal timestamp to zero.
- All 17 new viewing strings are translated in all 38 advertised locales;
  placeholders match. Across both compatibility passes, all 32 added/changed
  resource keys are present in every locale.
- This pass changes VOD/history and Home integration. It does not change the
  live playback engine; the earlier two-channel 61-frame live validations above
  remain the live-playback evidence.
- Ignored local evidence: `viewing-instrumentation-final.log`, `viewing-validation-summary.json`,
  `upgrade-result.json`,
  `viewing-*.png`, and final Gradle logs under `tmp/stremio-compat/`.

## Account, background and service extension (2026-11-02)

- Optional official device linking exchanges a transfer token for this client’s
  own session. The token, pending link and sync ledger are encrypted in Android’s
  no-backup directory. Disconnect removes the session and retains local viewing
  data. No password entry or third-party login proxy is used.
- Connected accounts import installed add-ons and synchronize library membership,
  watched flags/anchored episode bitmaps, notification preferences and current
  video progress in both directions. Unknown cloud fields are retained;
  newer cloud edits win conflicts. Existing unrelated local data is baselined
  when linking and is not automatically uploaded into a new account. Incognito
  prevents account synchronization. Add-on collection changes are imported;
  this client does not publish add-on installs/removals back to the account.
- Device-link creation was reachable in this pass (HTTP 200). Real sign-in and
  signed-in cloud interoperability require a user-owned Stremio account and
  were not performed. Account token exchange, imports, uploads, conflict/retry,
  disconnect and privacy behavior are covered with a controlled API server.
- Public defaults found: `https://pengu.uk/manifest.json` (1.4.0) and
  `https://cncverse.dpdns.org/manifest.json` (2.19.30). PenguPlay’s unconfigured
  manifest has no catalogs and advertises configuration. CNCVerse exposes TV
  and custom-type catalogs; two live catalogs returned nonempty HTTP 200 data.
  These are observations of the public defaults, not certification of a private
  configured install or every upstream stream.
- Streaming-service interoperability returned exactly the controlled 3,150,122
  byte video from ZIP, TAR (USTAR) and TGZ. LZ-string URI compression matches
  official JavaScript vectors including Unicode. Service requests strip source
  proxy headers, preserve playback identity and redact compressed credentials
  from player errors. Native service limitations (for example TGZ seeking and
  archive variants) still apply.
- Eighteen added resource keys are present across all 38 advertised locales;
  placeholder contracts match. Proper product/protocol names remain unchanged.
- This extension does not change the live HLS playback engine; the two-channel
  live validation above remains the live-playback evidence.

## Final extension validation (2026-11-02)

- 173 targeted unit tests passed: domain 29, data 57, Stremio feature 71,
  playback feature 8, player 7 and app worker 1; no failures, errors or skips.
- Three Android TV runtime tests passed on the final service implementation:
  official device-link creation/cancellation; real public PenguPlay/CNCVerse
  installation/refresh plus two nonempty live catalogs; and byte-exact media
  from ZIP/TAR/TGZ through the player's OkHttp data source. ZIP and TAR seeks
  also returned the exact expected bytes.
- An initial archive seek check exposed an official service ZIP response with
  one byte beyond Content-Length. Service-engine playback now closes idle
  connections so that excess bytes cannot poison a later seek. A reproducing
  unit test passes. Ordinary HLS/direct sources retain their existing client.
- The TV account screen displayed persisted encrypted service settings. Manual
  ZIP playback rendered the controlled clip with four distinct frames at a
  two-second cadence. This is VOD smoke coverage, not a new live-TV stuckness test.
- The final debug APK was assembled and installed. SHA-256:
  `f85833cdd92fa621cbdc637d55917ef63129fed81cca81426c58830d9585f502`.
- Ignored evidence: `tmp/stremio-next/test-summary.json`,
  `runtime-tests-fixed.log`, `archive-interop.json`, `archive-ui-check.json`,
  `archive-player-evidence.log`, resource checks and Gradle logs.

## References

- [Manifest and catalog extras](https://stremio.github.io/stremio-addon-sdk/api/responses/manifest.html)
- [Metadata and embedded streams](https://stremio.github.io/stremio-addon-sdk/api/responses/meta.html)
- [Native EPG](https://stremio.github.io/stremio-addon-sdk/epg.html)
- [Stream formats](https://stremio.github.io/stremio-addon-sdk/api/responses/stream.html)
- [Add-on directories](https://stremio.github.io/stremio-addon-sdk/api/requests/defineResourceHandler.html)
- [Player and library events](https://stremio.github.io/stremio-addon-sdk/protocol.html)

- [Official add-on directory](https://github.com/Stremio/stremio-official-addons/blob/master/index.json)
- [CNCVerse Bridge integration](https://github.com/NivinCNC/CNCVerse-Bridge/blob/main/README.md)

- [Official streaming service](https://github.com/Stremio/stremio-service)
- [Official account request types](https://github.com/Stremio/stremio-core/blob/development/src/types/api/request.rs)
- [Watched bitmap format](https://github.com/Stremio/stremio-core/blob/development/stremio-watched-bitfield/src/watched_bitfield.rs)
