# Changelog

All notable product changes are recorded in this document.

## [1.0.18]

### Added

- Added an optional Live TV clock overlay with configurable corner placement, size, digital/clean/serif fonts, and System, 12-hour, or 24-hour time formatting.
- Added VOD chapter navigation with timeline markers, chapter selection, and previous/next chapter actions.
- Added chapter skip actions for Intro, Opening, Recap, and Outro segments, with contextual labels and seek-to-end behavior.
- Added chapter-aware series autoplay that presents the existing next-episode countdown when end credits begin, while preserving the end-of-video fallback for media without usable credit chapters.
- Added adaptive startup bitrate selection using network-class defaults and recent bandwidth measurements.
- Added selectable Classic blue, M3 purple, and Light app themes, with persistent settings and backup/restore support.
- Added persistent VOD audio and subtitle track choices: selections are saved per movie, shared across all episodes in the same series, and used as a global fallback for other VOD titles when a matching track is available; language and label matching survives provider track-ID changes, and explicit subtitle-off choices are preserved.
- Added an opt-in Live TV setting to auto-hide the category sidebar after selection, with Back-button and focus-aware restoration.
- Added VOD category pinning for Movies and Series, with pin-first browsing, Android TV pin controls, and pinned category content shelves on the Home dashboard.
- Added measured frame-rate and stream-bitrate diagnostics for IPTV playback, including MPEG-TS streams that omit these values from their metadata.
- Added portal-backed Stalker VOD search with mixed movie/series results, persisted result IDs, paging, parental/hidden-category filtering, and a Browsing setting to disable it.
- Added a Media3 `FrameExtractor` pilot for supported VOD seek thumbnails, while retaining `MediaMetadataRetriever` as a fallback for unsupported or failed sources.
- Added bounded VOD and episode sliding-window preloading for current and adjacent series or catch-up items, with lifecycle cleanup, stale-session protection, and direct-playback fallback; ordinary live playback remains on its existing path.

### Fixed

- Fixed provider observation cache eviction when another provider configuration fails to decode, preventing removed providers from retaining stale configuration data if they are added again later.
- Fixed an episode-completion `StackOverflowError` caused by playback history marking recursively invoking itself instead of the watched callback.
- Fixed opening credits and post-credit scenes being treated as end credits, and tightened generic Credits detection to avoid triggering next-episode autoplay too early.
- Fixed VOD “More Like This” movie posters opening movie information instead of starting playback directly.
- Fixed native EPG resolution for large channel lineups by chunking program lookups to stay below SQLite bind-variable limits on older Android devices.
- Fixed EPG collisions for channels sharing an EPG identifier by keying resolved guide data by the provider stream ID when available, while preserving legacy EPG-ID fallback queries.
- Fixed grouped VOD browse pagination and infinite scroll by using cursor windows, deduplicating before offset/limit application, and handling zero-timestamp catalog entries consistently.
- Fixed provider configuration decoding after typed-provider migrations that omitted the embedded configuration type, preserving access to existing provider settings and credentials.
- Fixed Stalker compatibility discovery treating every tokenless HTTP 200 response as a rate limit; generic endpoint or recipe responses now continue through fallback discovery.
- Fixed Stalker VOD playback on portals that reject bare movie commands by resolving movie file rows and trying validated `/media/file_<fileId>.mpg` fallback commands while preserving existing playback behavior.
- Fixed Stalker VOD category layout drift by retargeting stale stored category types before synchronization instead of forcing an unnecessary catalog re-download.
- Fixed Stalker guide synchronization continuing through dead per-channel EPG endpoints after both bulk and first per-channel responses are confirmed empty.
- Fixed Stalker channel logos returned as bare or portal-relative paths by resolving them against the portal logo directory, including channels imported before the fix.
- Fixed Xtream series showing an empty episode list after re-entering: a hydrated series whose local episodes went missing is now re-fetched, and re-hydration no longer replaces a larger cached episode set with a smaller, partially re-scraped provider response.

### Changed

- Improved long-running app responsiveness by sharing memoized provider observation streams, reusing redacted configuration projections across collectors, moving snapshot reads and decoding off the main thread, and removing per-screen repository subscriptions from the app shell.
- Updated the AndroidX Media3 playback stack from 1.9.2 to 1.11.0:
  - Improved external subtitle loading and preferred subtitle-language selection, while keeping optional subtitle I/O failures non-fatal to playback.
  - Updated the bundled FFmpeg decoder artifact to the matching Media3 release.
  - Inherited playback reliability fixes for decoder recovery and prewarming, codec reuse and flushing, audio-output retries, low-memory load control, and HLS, DASH, and MPEG-TS seeking and timeline handling.
  - Inherited subtitle timing and parser fixes for TTML and VobSub content.
  - Inherited compatibility improvements for HLS Content Steering, DASH/HLS metadata, AV1 and Dolby Vision, VVC, Matroska, and MP4 formats when supported by the device.
  - Reduced unnecessary artwork metadata extraction during playback to lower memory use on TV devices.
- Improved Android TV navigation, focus handling, and Back-button behavior across Live TV, player controls, provider setup, and Settings.
- Redesigned the VOD playback controls UI, including the control layout, actions, and navigation behavior.
- Improved playback quick-action readability by using shared theme-aware content colors across focused and unfocused controls, keeping contrast consistent across all playback buttons.
- Improved Live TV control reliability, including timeshift seeking and rewind interactions.
- Improved reliability when configuring providers, importing settings, and preserving playback preferences.
- Updated Italian translations across the app and feature modules, including previously missing strings and corrected resource ownership.
- Improved Stalker EPG synchronization by increasing channel batch sizes from 500 to 5,000, reducing request overhead for large channel lineups while retaining bounded processing.
- Reduced storage usage by limiting the shared HTTP cache to 16 MiB and pruning past EPG entries after 12 hours when a provider does not require a longer catch-up window.
- Improved device orientation behavior by allowing portrait rotation on phones and tablets while keeping Android TV and Fire TV interfaces locked to landscape.
- Improved grouped VOD deduplication performance by moving movie and series presentation work off the main thread, adding bounded normalization caches, and avoiding unnecessary ICU normalization for ASCII titles.
- Improved Stalker authentication resilience by stopping repeated handshakes after explicit soft-throttle signals, reusing short-lived encrypted sessions across provider instances and process restarts, and restoring session cookies for resumed API requests.
- Improved Stalker on-demand guide loading by preferring numeric short-EPG lookups, pacing prefetch requests, and publishing guide results in bounded chunks.
- Reduced repeated empty Stalker guide requests by sharing provider-scoped, expiring empty-key cache state between the Guide and Home now-playing fallbacks.
- Improved new Stalker provider onboarding by importing a capped live preview and category shells first, then scheduling a generation-aware background sync for the complete catalog and guide.

## [1.0.17]

### Added

- Added picker-free Android TV local backup export and restore, publishing exports to `Downloads/StreamVault` when available and falling back to app-managed storage when a system document picker is unavailable.
- Added provider credential snapshots to new local backup exports so restored providers can sync without manual password re-entry.
- Added local backup management with confirmation-based deletion for StreamVault-created Downloads, app-private, registered SAF, and supported Fire TV USB exports.
- Added timestamped Google Drive backup bundles containing the exact exported JSON and its matching provider credentials, with newest-first snapshot selection, ten-backup retention, and compatibility with legacy Drive backup formats.
- Added Google Drive snapshot management and deletion in Settings, plus multi-snapshot selection in both Settings and provider onboarding before the normal import preview.
- Added portable provider and catalog identities for favorites and playback history, including series and episode relationships, so restores can resolve records after local database IDs change.
- Added Android TV DPAD-aware scrolling, scroll guidance, initial focus, and footer-to-form focus routing for the backup import preview.
- Added the living backup/restore review and expanded Google Drive setup documentation covering supported flows, compatibility, privacy, and remaining limitations.
- Added generic Stalker/Ministra compatibility discovery across portal endpoints, authentication modes, and device profiles.
- Added Stalker portal transport controls for custom headers, API/player User-Agents, HTTP proxies, request rules, and TLS handling.
- Added Stalker catalog readiness tracking, persisted portal capability state, diagnostics, and playback recovery telemetry.
- Added generic Stalker catalog and playback support across Live TV, VOD, Movies, and Series.
- Added Stalker VOD browsing with paged loading, on-demand catalog expansion, and complete-category loading as an option.
- Added M3U-only VOD classification and manual organization: users can classify imported entries as Movies or Series from Live TV long-press menus, review and group episodes into series with editable names, seasons, and episode numbers, and move items back to Live TV; persisted provider/source overrides survive refreshes and use app-owned destinations without exposing source group names or emojis, while non-M3U providers remain unchanged.
- Added bounded XMLTV ingestion with deterministic timezone-policy handling for large or inconsistent guide feeds.
- Added capability-based provider routing with typed configurations and stable provider identities across supported sources.
- Added checkpointed, idempotent backup restore with semantic ID mapping, partial-result reporting, and bounded import validation.
- Added WorkManager-first recovery for scheduled work, including boot, package replacement, and exact-alarm permission changes.
- Added shared data-sync quota accounting and timeout finalization for recording and download operations.
- Added durable process-death download reconciliation with safe resume and restart handling.
- Added complete historical Room migration coverage through the current database schema.
- Added durable provider workflow checkpoints, configuration revisions, and recovery state for resumable background operations.
- Added plugin provider ownership and playback-routing support for safer plugin integrations.
- Added session-scoped playback coordination and recovery to prevent stale preparation work from changing the active player.
- Added app-update policy and worker support for channel-aware update checks.

### Changed

- Changed local backup naming to include milliseconds and collision handling, and changed successful SAF exports to be tracked as app-managed backups for later cleanup.
- Changed Drive push, pull, listing, and deletion operations to serialize access, classify authorization/network/payload failures, and keep pending credentials tied to the selected backup snapshot.
- Changed backup import/export admission to enforce bounded byte, JSON, and credential limits while removing rejected partial staging files and failed picker-free export targets.
- Changed backup checksum validation to prefer the exact exported JSON bytes while retaining compatibility with legacy parsed-object checksums.
- Changed backup restore conflict detection and replacement scopes to use provider identity, stable content identity, and group names instead of relying only on local Room IDs.
- Improved Stalker bulk live synchronization to tolerate slow large catalogs, detect stalled transfers, retry transient failures, and fall back to category loading when needed.
- Improved Stalker playback resolution with content capability detection and direct-media fallback for portals that return non-standard playback responses.
- Improved provider ingestion with bounded M3U, XMLTV, and Jellyfin processing that preserves the last committed catalog when a refresh fails or exceeds limits.
- Improved provider isolation for Stalker sessions, portal expiry recovery, timezone-aware dates, and collision-resistant remote IDs.
- Improved cancellation and recovery across downloads, reminders, backups, provider synchronization, and Jellyfin catalog pagination.
- Improved recording, timeshift, and plugin IPC resource ownership so cancellation and cleanup cannot outlive their sessions.
- Improved provider-scoped progress and serialized workflow admission so concurrent work cannot overwrite another provider's state.
- Improved plugin discovery with bounded cancellation, stable source identity, and lifecycle reconciliation.
- Improved playback progress persistence with coalesced writes, lifecycle flushes, and targeted Watch Next updates.
- Improved local database maintenance and long-lived caches so offline maintenance works and provider-owned entries are evicted safely.
- Improved XMLTV parsing and EPG-source handling to apply ingestion limits, preserve source policies, and recover cleanly from interrupted refreshes.

### Fixed

- Fixed checksum-mismatch failures in release-built and Google Drive-round-tripped backups by pinning serialized backup DTO fields, accepting earlier obfuscated field aliases, and preserving the original exported JSON text.
- Fixed repeated imports of the same backup after a provider or restored data was deleted by reopening completed restore checkpoints and applying the selected conflict strategy again.
- Fixed portable favorites, groups, and playback-history restoration across changed provider/catalog IDs, including unresolved-reference reporting, provider resynchronization, and safe replacement of affected provider/type scopes.
- Fixed stale Google Drive credentials and snapshot state leaking into normal imports, failed pulls, dismissals, sign-out, and incomplete restores; credentials are now applied only after a complete restore, and sign-out revokes Drive access before clearing the local session.
- Fixed partial restore handling so the selected source and import plan remain available for retry while credentials and completion state are cleared only after a complete restore.
- Fixed Google Drive backup races and mismatched backup/credential generations by bundling the matching snapshots, validating selected file IDs before deletion, and cleaning up legacy credential companions when required.
- Fixed oversized, malformed, and failed backup transfers so they are rejected with classified errors and do not leave partial local, cache, or Drive staging files behind.
- Fixed Android TV backup actions that previously depended on an unavailable document-picker app, and broadened file-manager intent handling for generic JSON MIME types.
- Fixed backup preview focus trapping on Cancel/Import by moving initial focus into the dialog body and routing DPAD Up back to the scrollable form.
- Fixed Stalker VOD loading for very large catalogs by paging within safe limits instead of aborting the catalog.
- Fixed Stalker poster, backdrop, and channel artwork loading when portals return relative image paths.
- Fixed Stalker Series loading for portals that expose series through VOD endpoints, with improved category mapping and fallback behavior.
- Fixed Stalker VOD and Series requests so mixed VOD/Series portal responses are classified correctly.
- Fixed Jellyfin image authentication so account-scoped image URLs use the correct provider credentials.
- Fixed failed provider replacements so the previously active configuration and catalog remain usable until the replacement is committed.
- Fixed reminder delivery failures so they remain observable and retryable instead of being marked as delivered.
- Fixed legacy external-route decoding on API 25–32.
- Fixed failed live-history writes so returning to the same channel can retry persistence.
- Fixed provider deletion and backup restore cleanup by recording durable work and resuming safely after interruption.

## [1.0.16] - 2026-06-19

### Added

- Added separate Audio Decoder Mode and Video Decoder Mode playback settings, so users can control audio and video decoder selection independently while existing combined decoder preferences continue migrating to both controls by default.
- Added a selectable Local Live Rewind backend preference with `Automatic`, `Storage`, and `Memory` modes, so users can control whether live rewind prefers app storage or stays memory-backed when troubleshooting storage-wear or device-compatibility concerns.
- Added provider-level guide-source and channel-logo source policies for supported IPTV providers, including setup and settings controls to prefer supplier data, prefer external XMLTV data, or disable guide usage entirely on a per-provider basis.
- Added a `Show Favorites category` Live TV setting plus a matching Live TV category action, so users can hide the virtual Favorites rail the same way they can already hide All Channels and Recent.
- Added two Live TV-aware startup landing options: `Play first favorite channel`, which launches the current number-one live favorite based on the saved favorites order, and `Play last watched live channel`, with both modes falling back to Live TV when no valid startup channel is available.
- Added capability-aware catch-up playback handling for Xtream and Stalker providers, including archive mechanism detection, replay-window diagnostics, and provider-specific troubleshooting messages.
- Added Google Cast entry points to movie details and series details, including casting the current movie, the resume episode, or an explicitly selected episode with title, artwork, and saved watch progress metadata.
- Added a richer manual provider-sync overlay in Settings with current-section labeling, elapsed-time tracking, and a cancel action for cancelable sync operations.
- Added provider deletion progress bar.

### Fixed

- Fixed bare-hostname URL resolution so Jellyfin, M3U playlist, and EPG source paths now probe for HTTPS and fall back to HTTP the same way Xtream and Stalker already did; previously a bare hostname entered for Jellyfin was silently forced to HTTPS with no fallback, and bare-hostname playlists or EPG sources passed validation but failed at fetch because no scheme was stored.
- Fixed URL scheme detection in bare-hostname resolution so `file://` and `content://` local playlist and EPG URIs are no longer mangled into `http://file://…` by the protocol probe.
- Fixed HTTPS protocol probe to treat any non-redirect response (including 4xx and 5xx) as proof that the TLS endpoint is reachable, and only fall back to HTTP when the server issues a 3xx redirect or the connection fails entirely.
- Fixed bare-hostname validation in playlist and EPG URL fields so schemeless input is accepted consistently across all provider setup flows, matching the behavior already present for Xtream and Stalker.
- Fixed in-app update handling so downloaded APKs are recognized as install-ready, stale downloaded APKs no longer block newer releases, unknown-sources permission prompts preserve the downloaded update for retry, and Home/Settings use the same channel-aware newer-version logic.
- Fixed Xtream raw live MPEG-TS playback using an HLS-specific Media3 TS extractor mode; direct `.ts` live streams now use the standard single-program TS path, improving compatibility with providers whose endless transport streams were failing during startup and retry recovery.
- Fixed external-only guide behavior so providers can now strictly avoid supplier Xtream/Stalker guide fallback, clear stale external-vs-provider mapping behavior correctly when policy changes, and resolve channel logos from supplier or matched EPG icons without overwriting stored supplier logos.
- Fixed Live TV default-category entry so opening Live TV with a saved default category now moves focus into the first channel in that category instead of leaving focus stranded on the sidebar.
- Fixed catch-up UI wiring so archive badges, EPG history, restart/archive actions, and player diagnostics only advertise replay when the channel has enough metadata to build a replay candidate; catch-up startup now tries all generated replay candidates before surfacing a failure.
- Fixed Google Cast request handling across player and VOD flows so HLS, DASH, SmoothStreaming, MPEG-TS, and progressive streams resolve with the correct Cast metadata, live streams always start from the live edge, VOD streams preserve resume position, and unsupported RTSP/RTMP, DRM, or non-rewritten local/header/proxy-only URLs fail with clearer user feedback instead of inconsistent behavior.
- Fixed Settings busy overlays so backup export, backup inspection, and history clearing no longer show a cancel action they cannot actually honor.
- Fixed Settings `Rebuild Index` sync progress so it now uses the same tracked elapsed-time and cancellation path as the other manual provider-sync actions.

## [1.0.15] - 2026-06-13

### Fixed

- Fixed Picture-in-Picture compatibility on Android 7.1 / API 25 devices.
- Fixed live custom-group counts so badges include grouped favorites correctly, no longer show zero for populated groups, and follow the same grouped channel count users see when opening a group.
- Fixed 4K / UHD / HDR live HLS playback stability by allowing live streams to promote to deeper HLS buffer policies when metadata or observed video format indicates high-resolution or high-bitrate playback needs.
- Fixed series episode preview fallback so missing or failed preview images still show the episode label instead of an empty tile.
- Fixed live playback recovery so decoder failures can retry with an alternate stream format after a software-decoder attempt.
- Fixed movie decoder fallback so HEVC playback failures can retry against an AVC/H.264 variant when another version is available.
- Fixed bundled FFmpeg validation and artifact support so MPEG Layer II (`MP2` / `audio/mpeg-L2`) audio remains available for compatible streams.
- Fixed grouped movie duplicate actions so favorites and custom-group dialogs account for all raw versions while play, copy URL, download, and add actions target the selected version.
- Fixed grouped movie detail loading to reuse the already-known variant group from browse navigation instead of re-discovering duplicate versions again on the detail screen.
- Fixed grouped series detail loading to reuse the already-known variant group from browse, search, continue-watching, and dashboard history navigation instead of re-discovering duplicate versions again on the detail screen.
- Fixed movie library multi-word search so FTS4 prefix matching no longer drops results like `No Place to Be Single` for queries such as `no place`.
- Fixed Stalker endpoint handling so authenticated requests stay on the discovered API endpoint instead of retrying the sibling `portal.php` or `server/load.php` path automatically.
- Fixed Stalker request and playback cookies to only send the required `mac`, `stb_lang`, and `timezone` client values, with encoded cookie formatting and no extra device-identity cookies.
- Fixed Stalker device-profile generation so empty Serial Number, Device ID, Device ID2, and Signature fields stay empty instead of being auto-generated.
- Fixed Stalker profile metrics so `uid` uses Device ID2, and removed `video_out` and `signature` from the generated metrics payload.
- Fixed Stalker profile requests to send `prehash=false` literally instead of numeric `0`/`1` values.
- Fixed the default Stalker `get_profile` version string to use the legacy MAG 250 `5.6.10` profile value expected by stricter portals.
- Fixed Stalker and Xtream setup overrides so saved User-Agent, custom headers, and Stalker advanced compatibility settings are respected across login, sync, playback resolution, and VOD/series detail requests.
- Fixed Stalker HTTP proxy playback support so proxy metadata is preserved through resolved streams and also used by Android TV Input playback.
- Fixed Stalker authentication so a handshake token is only accepted after `get_profile` succeeds without an `Authorization failed` portal error, and cached portal auth now stays reused until that specific authorization failure happens.
- Fixed provider onboarding URL handling so bare-host Xtream and Stalker server inputs resolve their protocol before validation and duplicate checks, allowing auto-detected `http://` or `https://` URLs to save correctly.
- Fixed default Stalker player requests to use MAG-style playback defaults, including `Lavf53.32.100` as the player User-Agent plus explicit `Accept`, `Connection`, and `Host` headers.
- Fixed Live TV favorites, recents, and custom-group ordered lists still collapsing SD/HD/FHD channel variants after grouped variants were disabled.
- Fixed Live TV provider feeds that include hash-wrapped pseudo-channel headers such as `#### GENERAL HD/4K ####` so those non-playable rows no longer appear in channel lists or inflate live category and total-channel counts.
- Fixed download speed test

### Added

- Added optional real-time translation subtitles for supported live playback, with subtitle-menu activation, a configurable external translation-service endpoint, and local Whisper service setup documentation.
- Added episode preview artwork in series details, preferring episode thumbnails and falling back to series poster/backdrop artwork when episode art is missing.
- Added customizable Home dashboard shelves so users can hide default rows, enable extra built-in rows, and reorder the Home layout.
- Added customizable top navigation so users can show, hide, and reorder primary tabs while keeping Settings always available and automatically constraining the default landing screen to visible tabs.
- Added a `Live buffer size` playback setting with `Auto`, `Small`, `Medium`, and `Large` modes for tuning live-stream buffering behavior.
- Added a playback setting to prefer live stream format selection with `Auto`, `HLS`, or `MPEG-TS` modes for Xtream live playback.
- Added Movies duplicate handling with smart/grouped presentation, preferred version ranking, detail-page version selection, sticky manual choices, and playback observations for reliability-based movie variant selection.
- Added Series duplicate handling with smart/grouped browse and search presentation, preferred version ranking, detail-page version selection with sticky manual choice, and raw-safe favorites/custom-group actions across grouped variants.
- Added Stalker advanced HTTP header overrides, including support for custom headers and removing default request/playback headers by leaving an override value blank.
- Added Stalker-specific SSL bypass handling for portal requests and player playback so invalid HTTPS certificates no longer block those connections.
- Added Stalker advanced compatibility options for hw_version, separate API/player User-Agent values, Ethernet/WiFi X-User-Agent link type, HTTP proxy support for API and playback, and action-scoped request blocking/parameter overrides.
- Added automatic HTTP/HTTPS protocol detection for Xtream and Stalker provider server URLs when users enter a bare host, while preserving full URLs that already include a scheme.
- Added Jellyfin provider support for direct Movies and Series library sync, playback, and provider setup from the main onboarding flow.
- Added Jellyfin Quick Connect onboarding with on-screen code and QR flow for signing into compatible servers without typing a password on TV.

### Changed

- Changed the app minimum supported Android version to Android 7.1 / API 25.
- Changed live HLS buffering to use content-aware auto scaling instead of one fixed live profile, keeping normal HD channels responsive while automatically using deeper buffers for detected or observed UHD / HDR / high-bitrate streams.
- Changed low-memory playback devices to cap automatic UHD live-HLS buffer promotion at the medium profile instead of always jumping to the largest buffer target.
- Changed the Stalker `Device profile` field label in setup to `MAG Type` while keeping the same underlying saved value and behavior.

## [1.0.14] - 2026-06-06

### Added

- DB migration from 1.0.12

### Fixed

- Login does nothing issue.

## [1.0.13] - 2026-06-06

### Added

- Added offline VOD download management with a Downloads screen, foreground service, grouped episode downloads, pause/resume/restart controls, and local playback for completed files.
- Added external playback mode and chooser handling so users can hand streams off to external player apps more reliably.
- Added a stream format selector to the player so users can switch formats when decoder recovery needs an alternate stream variant.
- Added a default landing screen setting so startup can open Home, Live TV, Movies, Series, Guide, Downloads, Plugins, or Settings instead of always opening Home.
- Added configurable colored remote button support for Android TV remotes, with global defaults plus playback and live-browse overrides for actions such as guide, channel info, favorites, category pinning, and split screen.
- Added QR-based provider pairing so users can scan from a phone on the same LAN, submit provider details locally, and add Xtream, Stalker, or M3U providers directly to the TV.
- Added a transparent live-TV full guide overlay that opens over player playback, reuses the EPG grid, supports category switching and search, and lets you tune channels directly from the overlay.
- Added a playback setting to let Split Screen/Multiview ignore provider max-connection limits when a provider allows multiple streams from the same IP.

### Changed

- Changed VOD downloads to use a single FIFO provider-stream scheduler with fresh provider URL resolution before each capture attempt.
- Fixed player stream-info failures to preserve and surface the underlying error message instead of dropping it.
- Fixed decoder error recovery to retry against alternate stream formats when available.
- Fixed XMLTV parsing for ISO timestamps that include timezone offsets.
- Fixed EPG repository test setup so reactive time-shift lookups are stubbed correctly during build verification.
- Fixed completed local download playback so `content://`/`file://` files do not consume the provider download lease and do not pause active downloads.
- Fixed failed download Resume to clear partial output and restart through the scheduler.
- Fixed provider playback interruptions leaving downloads stuck in `DOWNLOADING` by cancelling the active OkHttp call and resetting the partial row to zero-byte `PAUSED` state.

## [1.0.12] - 2026-05-15

### Added

- Added live-first Stalker sync with background Movies/Series indexing so providers become usable sooner while VOD keeps loading in the background.
- Added advanced Stalker auth and MAG compatibility support, including MAC-only, credential-based, mixed auth, and stricter MAG identity presets.
- Added variant-aware Stalker playback handling for direct URLs, multi-command entries, temp-link portals, and `play/live.php` / `play/movie.php` backends.
- Added persisted Stalker portal learning for auth mode, portal profile, MAG preset, bootstrap recipe, endpoint preference, cookie mode, and last working playback path.
- Added a Stalker replay-fixture matrix and HAR-to-fixture capture script for onboarding new portal families faster.

### Changed

- Changed Stalker bootstrap from a single happy-path flow into recipe-driven session setup with cookies, localization/modules support, portal fingerprinting, and fallback rediscovery.
- Changed Stalker catalog parsing to preserve backend flags, command variants, archive/timeshift hints, and other portal capabilities for later playback decisions.
- Changed Stalker playback resolution to choose backend-specific strategies instead of forcing everything through one `cmd -> create_link` path.
- Changed Stalker sync recovery and manual sync to reuse indexed state, resume partial work, and avoid duplicate or blocking reloads.
- Changed Stalker EPG refresh to update more safely channel-by-channel instead of wiping healthy guide data up front.

### Fixed

- Fixed delete provider dialog, not closing after deletion.
- Fixed many Stalker portals that could browse catalogs but failed at playback because they needed different auth, bootstrap, cookie, endpoint, or temp-link behavior.
- Fixed incorrect handling of ambiguous Stalker account states such as `status=0`, which could previously be treated too aggressively as expired.
- Fixed Stalker temp-link and backend-family failures being reported too generically by surfacing clearer empty-link and session-rejection diagnostics, including HTTP `204` cases.
- Fixed Stalker indexing and hydration reliability on large or unstable portals with persisted cursors, retries, cooldowns, and safer stale-data pruning.
- Fixed Stalker setup and sync edge cases around wildcard categories, stale progress, provider diagnostics, explicit MAG identity overrides, and per-provider EPG counts.
- Fixed Stalker series detail season selection when some portals returned season-shell rows again for per-season requests, which could make multiple seasons show the same pseudo-episode list instead of that season's episodes.
- Fixed the Movies and Series Filter & Sort dialog layout so chip rows wrap cleanly instead of clipping or wasting space in the VOD modal.
- Fixed VOD category browse sort wiring so `Newest` and `Recently Updated` no longer collapse to the same behavior; Movies now separate release-date ordering from provider-added ordering, while Series keeps `Recently Updated` based on `last_modified` and uses release metadata for `Newest` when available across both Xtream and Stalker category views.
- Fixed top navbar unified search so mixed-case queries match live TV, movies, and series results case-insensitively.
- Fixed `TextureView` live preview and fullscreen playback turning solid green by correcting the player-view handoff path and stale frame retention during render-surface reuse.
- Fixed external XMLTV download URLs that return gzip-compressed MyEPG-style files without a `.gz` suffix so they refresh correctly instead of failing with stream reset or parse errors.
- fixed diagnostics window, size and scrolling capabilities.

## [1.0.11] - 2026-05-13

### Added

- Added close app icon, top right corner in settings tab.
- Added audio decoder names to player diagnostics.
- Added FFmpeg artifact verification plus provenance and LGPL docs.
- Hide Unhide individual channels.
- Added advanced playback settings for audio output and compatibility memory.
- Added richer FFmpeg and compatibility diagnostics.
- Added APK companion plugin discovery, activation, and provider integration.
- Added plugin installation from APK URLs and local files, plus auto-detection of compatible installed plugins.
- Added host-rendered plugin settings screens with persisted values, validation, and plugin actions.
- Added native Activity-based plugin configuration for richer plugin UIs.
- Added channel-name marquee on focused EPG rows and in the player info overlay, plus a persistent resolution badge on the overlay while channel info is visible.
- Added a welcome-screen `Set up later` option.
- Added Google Drive backup sync, onboarding import, and credential restore.
- Added Live TV quick-filter management for hidden categories and channels.
- Added opt-in debug onboarding seeding from `local.properties` with docs and template updates.
- Added real-time first-boot library sync progress on the welcome screen.

### Changed

- Refreshed the Plugins screen with denser StreamVault-styled controls and a dedicated icon.
- Expanded plugin API docs for manifests, installation, IPC, configuration, and UI integration.
- Clarified the Google Drive maintainer guide for `drive.appdata` and production rollout.

### Fixed

- Fixed unsupported audio playback by bundling Media3 FFmpeg decoders for `AC-3`, `E-AC-3`, `DTS`, `MP2`, and `TrueHD`.
- Fixed unsupported audio streams not retrying once in software when FFmpeg is available.
- Fixed explicit `TextureView` live playback getting stuck on green or corrupted frames before first video render.
- Fixed movie playback context and recovery so non-live titles no longer inherit stale live-channel state, external subtitle attachment resumes from the current position, and VOD retries preserve playback progress correctly.
- Fixed in-app updates crossing release channels: stable builds now only offer stable releases, beta builds now only offer beta prereleases, and same-version beta updates fall back to publish time to detect newer beta builds.
- Fixed software nav bars overlapping the app on devices like Nexus 5X.
- Fixed touch and mouse activation for TV plugin controls on non-TV devices.
- Fixed `TvClickableSurface` and `TvIconButton` long-press behavior for home-screen actions.
- Fixed missing favorite actions on series detail screens.
- Fixed Xtream series detail pages rendering empty when only metadata-only season payloads were returned.
- Fixed Xtream live sync category requests hanging near the end of large imports.
- Fixed Xtream live category-bulk partial sync dropping staged channels and leaving providers stuck on `Live 0`/inactive after successful live category imports.
- Fixed VOD category ordering to sort by provider-added time.
- Fixed Stalker VOD category filtering drifting when portal responses omitted category metadata.
- Fixed backup export and import to preserve hidden live categories and channels.

---

## [1.0.10] - 2026-05-11

### Added

- Added Import Backup to startup provider setup, so new installs can restore saved providers from a backup instead of entering provider details manually.
- Added Android TV-friendly backup sharing and open-with import fallback for devices with limited file picker support.
- Added optional provider-level HTTP User-Agent and custom header overrides for Xtream and M3U providers.

### Changed
- Entire Xtream sync methodolgy was changed.

### Fixed

- Fixed long category names in Live TV and VOD classic view so focused rows marquee to reveal the full label, with RTL-aware direction for pure RTL names.
- Fixed movie and series fullscreen overlays taking too much screen space; VOD controls are now slimmer and quick actions stay on a single horizontal row instead of wrapping into a large second row.
- Fixed Live TV preview surface mode now matching fullscreen playback.
- Fixed fullscreen live playback now falling back from broken `TextureView` sessions to `SurfaceView`.
- Fixed player A/V sync state wiring in the fullscreen player UI.
- Fixed generic provider requests sometimes being sent without a User-Agent or consistent request identity. Xtream API, M3U playlist, and provider EPG requests now use a shared app-level User-Agent fallback with provider-scoped request profiles and sanitized request-header diagnostics; Stalker keeps its dedicated MAG-style identity path.
- Fixed favorites, recent live, and continue-watching provider scoping in all-providers mode.
- Fixed favorites persistence: partition-safe reorder, atomic move/merge, duplicate-safe global favorites, correct group counts.
- Fixed favorites screen empty-state so recent activity still appears when no favorites are saved.
- Fixed provider onboarding completing with a warning instead of failing when saved providers have a failed initial sync.
- Fixed Xtream and M3U onboarding validation: rejects embedded credentials, safe playlist-to-Xtream conversion, shared password validation.
- Fixed Xtream Live TV categories showing zero items when providers only send category membership in `category_ids`.
- Hardened playback session lifecycle for main, preview, and multiview engines; fixed stream renewal/catch-up/rewind losing headers, stale quality selections, and needless full re-prepares on metadata-only route updates.
- Fixed live playback decoder startup crashes and Auto-mode stutter by preferring hardware first with one software fallback.
- Fixed Fire OS / Android 9 playback-start crashes caused by unsupported SQLite UPSERT syntax in playback compatibility history persistence.
- Fixed default `AUTO` playback startup to use a safer stock Media3 path unless users explicitly enable AV sync or select a non-default decoder mode.
- Fixed automatic playback recovery to stay bounded and progressive instead of looping through aggressive startup-time workarounds.
- Fixed compatibility history so it is advisory only, non-fatal, and ignored during clean first startup.
- Fixed fatal playback failures now generating a release-safe crash report that users can view, share, and delete from Settings.
- Fixed several DVR lifecycle edge cases around recording promotion, foreground-service startup, handoff, and recovery.
- Fixed recording schedule backup/restore for padding, recurring rules, and partial import failures.
- Fixed reminder and recording scheduling: exact-alarm enforcement, stale suppression, alarm identity/cleanup, OS alarm cancel on provider delete.
- Fixed Android 13+ reminder and recording alert delivery by adding notification permission handling.
- Fixed backup import applying preferences before Room restore completed.
- Fixed movie and series filtered browse views returning drifted totals or broken paging.
- Fixed persistence guardrails: single active provider, cross-provider favorite group assignments rejected.
- Fixed Android TV app-shell re-entry for launcher and external navigation; rejected unsupported route payloads.
- Fixed Watch Next, launcher recommendations, and Live Channels staying aligned during provider switches and deletes.
- Fixed TV continue-watching and guide resilience: episode resume context preserved, Live Channels holds last-known-good rows during EPG gaps.
- Fixed search oversampling to 500 candidates before reranking so alphabetical SQL order no longer drops better matches.
- Fixed partial search failures showing as full empty state; sections now fail independently with a distinct "Search unavailable" notice.
- Fixed dashboard combined-profile VOD/health sourcing from a non-member provider.
- Fixed continue-watching in combined-profile mode now spanning all member providers.
- Fixed continue-watching and recommendations degrading silently to empty on transient IO failures; explicit degraded state shown instead.
- Fixed TV launcher recommendation channels being deleted on transient failures; channels preserved until a successful refresh confirms empty.
- Fixed TV launcher recommendations in combined-profile mode sourcing from a validated member, not the globally active provider.
- Fixed switching to a combined live profile not triggering a launcher recommendation refresh.
- Fixed provider delete requiring no confirmation; now requires explicit confirmation, is blocked while in-flight, and reports success/failure.
- Fixed active-provider switch writing preferences before the repository call succeeded.
- Fixed combined M3U profiles allowing activation with no enabled members and removal/disabling of the last enabled member while active.
- Fixed settings forms (combined M3U, EPG source add) dismissing before async operations complete; forms stay open with input intact on failure.
- Fixed backup import dialog allowing double-confirm in-flight and confirmation with all sections disabled; guard is now an atomic state transition.
- Fixed EPG source refresh only updating providers visible in the current settings session.
- Fixed EPG assignment observer jobs accumulating for deleted providers; cleaned up immediately on provider removal.
- Fixed recording storage settings (folder, pattern, retention, concurrency) racing on concurrent updates; writes serialized behind a mutex.
- Fixed privacy toggle rows firing twice from outer surface and inner Switch; Switch is now a passive indicator.
- Fixed internet speed test card allowing concurrent test runs.

---

## [1.0.9] - 2026-04-27

### Added

#### Player
- A/V sync offset controls — global and per-channel overrides; accessible from the channel info overlay (live) and player controls overlay (VOD).
- Compatibility Mode — improved decoder/surface fallback for stalled video playback.
- Video Surface selection — choose Auto, SurfaceView, or TextureView in Playback settings for device-specific rendering issues.
- Time format preference (System default, 12-hour, 24-hour) in Settings.
- Sleep timers — Stop Playback After and Allow Standby After Idle, with presets, countdowns, and warning overlays; defaults saved in Playback settings.
- Auto-Play Next Episode for series — 10-second **Up Next** countdown with Play Now and Cancel; toggle in Playback settings (on by default).

#### VOD
- Infinite scroll preference for Movies and Series — auto-load more rows and posters while browsing, or keep a manual Load More button.

### Fixed

- Fixed excessive live TV buffering from 1.0.8 — minimum buffer threshold reduced to 8 seconds (was effectively 30 seconds).
- Fixed inconsistent AM/PM and 24-hour timestamps — selected time format now applied consistently across Guide, Player overlays, Home, History, Dashboard, and Settings.
- Fixed EPG guide cutting off at 60 channels per category — additional channels now load automatically on scroll.
- Fixed a crash when opening Settings → EPG Sources on some devices by making the EPG source and provider list item keys unique.
- Fixed background EPG sync from stalling Live TV category loading after provider add.
- Fixed Stalker live sync loading large `get_all_channels` catalogs in a streaming, batched path to avoid out-of-memory crashes.
- Fixed Stalker live sync when portal category requests fail — the app now stages bulk live channels first and recovers fallback categories instead of leaving Live TV empty.
- Fixed Stalker native EPG fallback on portals that ignore per-channel `ch_id` requests and keep returning the full bulk guide — sync now detects the broken response shape and stops repeating the large download loop.
- Fixed modern VOD/series shelves to load on demand in smaller visible batches, with paged Stalker category loading and throttled Xtream category previews.
- Fixed Stalker series details using local numeric IDs instead of provider-native series IDs, which broke series info loading on portals that use composite IDs.
- Fixed Stalker series titles being overwritten by season-shell or numeric fallback names when detail payloads omitted the real title.
- Fixed shell-only Stalker series seasons to follow up with derived `season_id` requests and use explicit episode commands when the portal provides them.
- Fixed shell-based Stalker episode playback on stricter portals by passing the episode number into `create_link` instead of always using `series=0`.

---
## [1.0.8] - 2026-04-22

### Added

- Added resume action on movie and series detail screens — shows formatted position and episode context (e.g. "Resume · S2 E3 · 23:45").

### Fixed

- Fixed Pro-mode Live TV preview to fullscreen handoff so live playback can continue more smoothly without media-session crashes.
- Fixed manual EPG add flow crashing instead of showing a proper error.
- Fixed M3U/M3U8 playlists using `#EXTGRP` loading all Live TV channels into one group.
- Fixed Stalker live sync over-requesting channels on provider add.
- Fixed Stalker EPG sync making redundant requests for duplicate or placeholder guide keys.
- Fixed Stalker background EPG sync skipping the bulk portal request and going straight to per-channel fetches.
- Fixed new Stalker providers defaulting to upfront EPG sync instead of background sync.
- Fixed Stalker playback on portals requiring MAG-style headers or fresh direct-URL resolution.
- Fixed Stalker probe failures and HTTP `456` errors being reported too generically.
- Fixed Xtream fast sync leaving movie and series categories temporarily empty.
- Fixed VOD browse pagination skipping items or leaving groups incomplete.
- Fixed VOD resume position, episode progress, and continue-watching state not updating reliably.
- Fixed silent audio when a stream's codec (e.g. EAC3, AC3) is unsupported — player now surfaces a clear error.
- Fixed Xtream Live TV taking a very long time to load on first sync with large providers (50k+ channels).

---

## [1.0.7] - 2026-04-20

### Added

- Added Stalker Portal provider support.

### Fixed

- Fixed startup crashes on some Android 9 devices when secure preference storage fails to initialize.
- Fixed Picture-in-Picture setup on devices that report invalid or unsupported video aspect ratios.
- Fixed launch crashes for some upgrades from 1.0.4 or earlier caused by orphaned provider content left behind by older deletes.
- Fixed first-frame video freezes on some devices in fullscreen, live preview, and multiview playback.
- Fixed some playback failures not retrying after video had already started.

---

## [1.0.6] - 2026-04-19

### Fixed

- Fixed crash on upgrade from 1.0.4 to 1.0.5. The database migration FK integrity check (`PRAGMA foreign_key_check`) was running against the entire database instead of only the tables each migration modified. Users with any pre-existing orphaned rows (deleted provider history, stale EPG mappings, etc.) would hit an `IllegalStateException` and the app would crash on launch. Fresh installs were unaffected. The check is now scoped to the specific tables each migration writes to.

---

## [1.0.5] - 2026-04-16

### Added

#### Live Playback & DVR
- Added live rewind (timeshift) for live channels — up to 30 minutes buffer.
- Added timeline scrubber with live-edge indicator and seek controls.
- Added recording, catch-up, and restart controls directly in the player overlay.
- Added DASH stream support to the rewind engine.

#### Performance & Browsing
- Added incremental channel loading for large providers.
- Added incremental search results with automatic loading on scroll.
- Added automatic paging when reaching the end of the channel list.
- Added HTTP conditional requests (`ETag` / `If-Modified-Since`) to EPG refresh — unchanged feeds return `304 Not Modified` and skip download and parse entirely.
- Added EPG channel icon fallback — channels without a provider logo now use the icon from their matched EPG source.

#### System & Settings
- Added timeshift storage manager with automatic cleanup.
- Added auto-return on failed channels toggle.
- Added automatic update download option.
- Added options to show/hide “All Channels” and “Recent Channels”.
- Added "Hidden" option to Live TV Channel Numbering — hides channel numbers everywhere in the app (channel lists, player overlay, EPG, zap banner).
- Added live channel grouping settings with grouped/raw modes, grouped label style, and variant preference options.
- Added live channel variant selection in the player overlay, separate from adaptive stream quality selection.
#### Recording
- Schedule recordings directly from the EPG guide — Record, Record Daily, and Record Weekly buttons on any future program.
- Configurable pre/post recording padding (start early / end late) — applied automatically to all scheduled recordings.
- WiFi-only recording restriction — optionally prevent recordings from starting on mobile data.
- HLS recording quality cap — respects the per-network video quality preference instead of always picking the highest bandwidth variant.
- Recording conflict resolution dialog — shows which recordings overlap and offers to replace them instead of just showing an error.
- Recording status indicators on channel cards — REC and Scheduled badges on Dashboard and Search screens.
- Skip single occurrence of a recurring recording without cancelling the series.
- Search and filter in the recordings browser by title, channel, or status.
- Stalled-capture watchdog — recordings that stop receiving data for 60 seconds are automatically aborted.
- Automatic retry with exponential backoff on transient network failures during capture.
- Low disk space pre-flight check — scheduled recordings fail early with a clear message instead of writing partial files.
- Push notification on recording failure with channel name and reason.
- Completed recordings open in the player with resume position tracking.
#### VOD
- "Filter & Sort" button now shows active selections as a subtitle (e.g. "Favorites · Rating") and highlights with brand colour when non-default settings are applied — applies to both Movies and Series, modern and classic views.

#### Search
- Long-pressing a channel, movie, or series in Search now opens a context menu with Add/Remove Favorites, Hide, and Parental Lock/Unlock actions — consistent with the same actions available in other screens.

#### Parental Controls
- Added **PRIVATE** protection level — adult categories appear in the sidebar with a PIN lock, but are excluded from Search, EPG Guide, All Channels, and Recent so individual channel names (e.g. explicit titles) are never shown in mixed lists.
- PRIVATE is now the default protection level for new installs.
- Existing users with the old HIDDEN level are automatically migrated to the new HIDDEN (level 3); users previously on LOCKED remain on LOCKED.
- Protection level selection dialog upgraded to a title + description card layout matching other mode-selection dialogs.
- LOCKED level now correctly shows adult content in all views (EPG, Search, All Channels, Recent) — it previously blocked adult content from the EPG guide despite that not being the intended behaviour.

#### Database & Sync
- Search now indexes title, cast, director, and genre with BM25 relevance ranking — results are ordered by match quality.
- Program reminders — tap any guide entry to be notified before a show starts without scheduling a full recording.
- Watch progress and favorites are unified across providers for the same TMDB title — resume a movie on any provider from where you left off.
- Provider catalogs now enforce per-provider row limits to prevent malformed feeds from consuming device storage.
- EPG channel match quality is tracked per channel; channels with weak or missing EPG assignments surface in provider settings.
- Database diagnostics in Settings showing storage usage, per-table row counts, fragmentation level, and maintenance history.
- Background catalog and EPG sync jobs now survive process death and retry automatically on network recovery.
- Search history is now per content-type, timestamped, and stored in the database.
---

### Improved
- Faster loading and lower memory usage for large channel lists.
- More stable playback when switching channels.
- Live channel grouping now keeps raw provider rows intact and builds logical channels with explicit variants at read time.
- Live variant quality parsing now recognizes more tags, including `2K`, `576p`, `540p`, `fullhd`, `ultra hd`, and `uhd`.
- Live variant preference modes now support tag-first ranking and an observed-only mode based on actual playback telemetry.
- Channel grouping sub-options in Settings are visually nested under the main grouping control for clarity.
- Numeric channel entry now accepts up to 6 digits before resetting, which supports larger provider lineups.
- Better handling of live stream buffering and recovery.
- Improved recording settings layout with cleaner action grouping and a dedicated recordings browser dialog.
- Improved update UI feedback during downloads.
- EPG source delete now requires confirmation to prevent accidental removal.
- Refreshing an EPG source shows a per-source loading indicator instead of a global spinner, so multiple sources can refresh independently.
- Settings screen content scrolls up correctly when the keyboard opens, keeping text fields visible while typing.
- EPG feeds with non-UTF-8 encodings (ISO-8859-1/2, Windows-1252, etc.) now parse correctly — channel names and programme titles no longer appear garbled.
- EPG download read timeout increased from 30 s to 120 s for large or slow feeds.
- Catalog sync for large providers is significantly faster — field changes are diffed in Kotlin and applied in a single batched update instead of per-column correlated subqueries.
- EPG program data is now retained for the full catch-up window of each channel (up to 7 days) instead of a fixed 24-hour cutoff.
- EPG staging writes for large feeds are committed in a single transaction, cutting write time for 50 000-program feeds by roughly 10×.
- Series sync now skips unchanged series using server-provided last-modified timestamps, reducing API traffic on repeat refreshes.
- EPG and Xtream API responses are cached using server Cache-Control and ETag headers, eliminating redundant downloads on unchanged feeds.
- Channel and VOD browse lists now use keyset pagination — no items are skipped or duplicated during concurrent syncs and deep pages load faster.
- Movie and series watch-count sort no longer runs a subquery per row — watch count is stored on the content record.
- Playback progress is batched and flushed on a 30-second interval instead of writing to the database on every player tick.
- Partial sync no longer resets the provider refresh timer — only a complete successful sync marks a provider as up to date.

---

### Fixed

#### Playback
- Fixed incorrect channel info after auto-revert.
- Fixed repeated auto-revert causing channel switching loops.
- Fixed last-channel behavior after fallback.
- Fixed RTSP stream playback handling.
- Fixed MPEG-TS playback issues (black screen / corrupt frames).
- Fixed live buffer seeking returning incorrectly.
- Fixed player crashes on some devices.

#### Streaming & Stability
- Fixed stream detection for Xtream channels.
- Fixed DRM handling causing repeated retries.
- Fixed expired stream URLs during playback.
- Fixed heavy live-channel grouping work running too close to UI input paths by moving repository presentation transforms off the main thread.
- Fixed Xtream live recordings failing when provider direct-source URLs expired mid-recording.
- Fixed behind-live-window recovery issues.
- Fixed playback restarting due to EPG updates.

#### UI & Behavior
- Fixed update status stuck on “Downloading”.
- Fixed numeric channel input issues (0 handling).
- Fixed Picture-in-Picture activating incorrectly.
- Fixed missing channel handling after playlist refresh.
- Fixed active recording failures disappearing silently from the player without a notice.
- Fixed garbled average-speed placeholder text in recording details.
- Fixed favorites and custom groups being shared across unrelated providers. Combined M3U sources now merge member-provider favorites and groups in Home and Guide while saving changes back to the original provider.
- Fixed EPG category fallback logic.

#### EPG & Guide
- Fixed `.gz` EPG feeds being double-decompressed and failing to parse.
- Fixed blank URL accepted past the scheme validator when adding an EPG source.
- Fixed EPG list always appearing empty after a fresh install (list was never populated into the UI state).
- Fixed EPG sync rejecting plain HTTP URLs — M3U playlist header EPG (`x-tvg-url`), manual EPG sources, and M3U provider EPG fields now accept HTTP and HTTPS, matching the same policy already applied to Xtream providers.
- Fixed EPG search not escaping `%` and `_` wildcards, returning too many results on certain queries.
- Fixed "now & next" picking the wrong next program when programs had gaps.
- Fixed EPG channel-matching trimming whitespace inconsistently between ID index build and lookup.
- Fixed EPG source refresh not recording errors on failure, leaving stale "refreshing" status.
- Fixed EPG source refresh replacing live data non-atomically — writes now go to a staging area and swap in a single transaction.
- Fixed EPG override-candidates query loading all channels into memory instead of filtering in SQL.
- Fixed `MAX_EPG_SIZE_BYTES` duplicated across two repositories — now defined once in shared config.
- Fixed EPG source refresh not being rate-limited, allowing rapid repeated fetches.
- Fixed `jumpToDay` and `jumpToPrimeTime` using UTC epoch modulo instead of local-timezone arithmetic.
- Fixed guide timeline and program times using hardcoded 24-hour format instead of respecting the device locale.
- Fixed program progress bar gated on `isNowPlaying` flag instead of computing from timestamps.
- Fixed `EpgUiState` using `System.currentTimeMillis()` in default field values, causing inconsistent anchor times.
- Fixed guide search query not debounced, firing a database query on every keystroke.
- Fixed `nowTicker` flow restarting a new timer per collector instead of sharing a single upstream.

#### VOD
- Fixed "New to Old" sort not working for Movies. The `added` timestamp is now correctly used across the full pipeline, matching how series sorting works.

#### Provider Sync
- Fixed sync failing with "Login succeeded, but the initial sync failed" for live-only providers with no VOD or series content.
- Fixed providers with fast sync enabled showing **Partial** status incorrectly — providers now show **Active** when fast sync completes as intended.
- Fixed VOD-only providers (no live TV) failing on first sync when the server returns an empty live-streams response.

#### Database & Cache
- Fixed watch status not updating correctly after history was cleared — unwatched state is now read from the authoritative history table.
- Fixed stale data left behind when a provider was deleted while a sync was in progress.
- Fixed a background EPG job leaking a phantom reference when the job completed before it was registered.
- Fixed "now playing" showing an ended program on long-lived guide screens — current time is refreshed on each display tick.
- Fixed a forward clock jump suppressing cache refreshes beyond the corrected window.
- Fixed FTS search queries with unbalanced quotes or SQLite reserved operators causing crashes — malformed input is now sanitized before reaching the database.
- Fixed EPG provider mutex entries not being removed on provider deletion, leaking memory over long sessions.
- Fixed VACUUM maintenance running concurrently with an active sync, causing spurious sync failures.
- Fixed VACUUM pre-flight silently truncating the WAL file — replaced with a non-destructive passive checkpoint.
- Fixed favorites reordering issuing one database write per item — reordering now uses a single bulk update.
- Fixed category protection not being cleared when category IDs were remapped during a provider catalog restructure.
- Fixed sync session ID collisions after a clock correction — sessions now use random identifiers.
- Fixed database migrations that rebuild tables not verifying foreign key integrity before completing.
---

### Notes
- Live rewind works without provider catch-up support.
- Large playlists now load progressively instead of all at once.

## [1.0.4] - 2026-04-11

### Improved

#### Playback & Streaming
- Improved VOD playback compatibility with provider-resolved streams.
- Improved handling of expired Xtream stream URLs with automatic retry.
- Improved live-stream retry behavior and recovery.

#### TV Guide & Browsing
- Favorites and custom groups now appear in the TV Guide.
- Guide can now start on a selected default category.
- Improved category fallback when channels are missing.
- Improved browsing experience for Movies and Series categories.

#### Settings & UX
- Split Settings into separate Playback and Browsing sections.
- Improved provider sync with faster quick-sync and clearer progress feedback.
- Added Base64 compatibility toggle for Xtream providers.
- Improved About screen with build verification.

---

### Fixed

#### Playback
- Fixed playback failures for expired or invalid stream URLs.
- Fixed RTSP/RTMP casting failing silently.
- Fixed player startup crashes in some cases.
- Fixed audio-only streams not starting correctly.
- Fixed seek-preview thumbnails not clearing properly.

#### EPG & Guide
- Fixed EPG refresh stability and fallback behavior.
- Fixed EPG updates causing unnecessary UI refreshes.
- Fixed stale EPG data overwriting active channels.

#### DVR & Scheduling
- Fixed recurring recording conflicts and scheduling issues.
- Fixed DST-related recording time drift.
- Fixed invalid recordings being scheduled from outdated guide data.

#### Providers & Data
- Fixed Xtream playlist detection and import handling.
- Fixed M3U catch-up and replay support.
- Fixed provider sync inefficiencies and repeated loading.
- Fixed provider deletion cleanup and background sync issues.

#### Updates & Security
- Fixed update downloads getting stuck.
- Enforced HTTPS for update downloads.
- Improved APK integrity verification before install.
- Fixed URL validation edge cases.

#### UI & Behavior
- Fixed navigation focus issues in TV UI.
- Fixed numeric channel input handling.
- Fixed empty-state handling for offline libraries.
- Fixed long-press dialog accidental actions.

#### Performance & Stability
- Reduced memory usage during large operations (EPG, backups, search).
- Improved database maintenance behavior to avoid blocking operations.
- Reduced unnecessary UI recompositions and background work.

## [1.0.3] - 2026-04-09

### Added

#### DVR & Recording
- Added full DVR support with scheduled and background recording.
- Added conflict detection, persistence, and recovery handling.
- Added in-player recording controls and live recording indicator.

#### Providers
- Added combined M3U live sources with merged-provider profiles.
- Added provider management and active source selection in Settings.
- Added optional in-app provider/source browser for Live TV.

#### Localization
- Added broader locale support with improved typography handling.

---

### Improved
- Improved recording and provider settings UI for better TV navigation.
- Improved playback of recordings (now treated as local media).
- Improved storage handling with safe default folders and optional custom locations.

---

### Fixed
- Fixed recording failures/crashes on newer Android versions.
- Fixed D-pad navigation and click issues in recording settings.
- Fixed focus and styling issues in combined-provider dialogs.
- Fixed incorrect or broken translations across some locales.

## [1.0.2] - 2026-04-08

### Added

#### Updates & Settings
- Added in-app update support with download and install.
- Added playback troubleshooting controls (Media Session, decoder preference, timeouts).

#### Providers & M3U
- Added bulk category controls (Hide All / Unhide All).
- Added per-provider M3U VOD classification with refresh support.

---

### Improved
- Improved M3U EPG detection with broader header support and better parsing.
- Improved XMLTV and M3U ingestion to handle malformed feeds more reliably.
- Improved provider setup flows with clearer separation between M3U and Xtream options.
- Improved playback timeout settings with simpler controls.
- Improved player initialization and decoder fallback behavior.

---

### Fixed
- Fixed M3U imports missing EPG URLs due to BOM issues.
- Fixed provider sync to skip hidden categories and reduce overhead.
- Fixed category visibility controls not preserving existing hidden states.
- Fixed M3U sync inconsistencies across refreshes.
- Fixed XMLTV parsing errors on some real-world feeds.

## [1.0.1] - 2026-04-08

### Added

#### EPG Management
- Added manual EPG matching directly from the guide.
- Added EPG source priority controls in Settings.

---

### Improved
- Improved EPG resolution across playback, guide, and Home.
- Improved EPG refresh and assignment to update immediately after changes.
- Improved XMLTV support including gzip-compressed feeds.

---

### Fixed
- Fixed M3U imports not consistently carrying EPG sources.
- Fixed manual EPG overrides not persisting correctly.
- Fixed guide and playback ignoring resolved EPG mappings in some cases.
