# Playback Preferences and Quality Profiles

## Purpose

Expand [issue #263](https://github.com/Davidona/StreamVault-IPTV/issues/263) into a coherent playback preference system. Users should be able to set quality limits, save meaningful preferences for particular content, understand the quality currently playing, and trust that recovery and restarts preserve their choices.

This document specifies the agreed release and tracks implementation progress. The release covers profiles, connection limits, provider defaults, saved live-channel/movie/series ceilings, temporary choices, backup/restore, and playback lifecycle integration.

## Implementation status

Updated while implementation is in progress. Completed items below describe code currently present; unchecked items remain planned or incomplete.

Latest verification: `:app:compileDebugKotlin` succeeds with quality consent, policy diagnostics, fixed-track status, Cast messaging, source-only constrained preloads, and per-field player controls. All 30 strings added in the playback module exist in all 38 advertised locales; XML parses, no translation is an English placeholder, and positional formatting contracts match. `git diff --check` reports no whitespace errors. Runtime playback, migration/restore behavior, and automated test coverage have not been validated yet.

- [x] Added domain profile, limit, scope override, candidate, and resolver models.
- [x] Added global profile/custom/Wi-Fi/Ethernet bitrate preferences to the DataStore contract and implementation, preserving the existing Wi-Fi/Ethernet height keys.
- [x] Added global backup export/import and classified the new setting as a global preference.
- [x] Added localized Settings > Playback controls for Auto, Data Saver, Balanced, Custom, custom height/bitrate, and connection bitrate limits in all configured locales.
- [x] Exposed video height and advertised bitrate metadata on player tracks.
- [x] Applied profile, connection, provider-default, multiview, and compatibility limits through the current player track controller; made constrained selection strict and validated manual video choices before application and on policy updates.
- [x] Observed global quality preference changes in playback; constrained neighbor preloads stop at source/manifest preparation, and already-buffered windows are cleared when a quality limit tightens.
- [x] Added provider/content-scoped Room storage, a domain-facing read/write contract, provider-delete cascade, migration 82 → 83, and exported schema 83.
- [x] Added portable backup/restore for scoped quality preferences using portable provider identity, validation, per-provider replacement behavior, and restore checkpoint rollback.
- [x] Pass stable provider-supplied live/movie/series identifiers into playback; resolve saved provider and content preferences during foreground prepare.
- [x] Added player actions to save the current resolution as an adaptive ceiling and remove a saved ceiling. Saving returns selection to Auto, so it stays adaptive under the saved limit.
- [x] Added localized save/remove actions for every advertised locale.
- [x] Added temporary adaptive ceilings to the shared resolver and player selector; Auto clears the temporary ceiling, and content changes discard it.
- [x] Added player quality-menu actions to save the current resolution as a provider-wide adaptive ceiling and remove that provider default.
- [x] Added provider settings controls for per-field inherited height/bitrate limits, no additional limit, and explicit maxima.
- [x] Observe provider-default and content-scope preference flows during playback; scope switches cancel stale observations and apply updates to the active player without waiting for another prepare.
- [x] Keep a manual fixed-track choice session-local across same-media retries; reject choices outside current policy limits and reapply only after availability and policy revalidation.
- [x] Add sanitized logs and player diagnostics for effective limits, contributing policy sources, observed video format, and decoder-pressure ceilings.
- [x] Expose per-dimension Save maximum, Inherit, and content-scoped No additional limit actions in the player; saving one dimension preserves the other.
- [x] Gate no-match/unknown-metadata playback with explicit consent, preserve play intent, and defer player recovery while intentionally waiting. The gate offers one candidate, a source alternative when available, and stop; Continue once relaxes only the violating user dimensions and remains bounded to the current source/policy. Compatibility and multiview constraints still apply; decoder-pressure state disables the exception.
- [x] Effective limits, contributing sources, observed format, and decoder-pressure ceilings are logged and shown in the diagnostics UI.
- [x] Constrained neighbor preloads stop at source/manifest preparation, and tighter limits clear existing ranges; candidate-specific selector enforcement remains an optimization. Internal adaptive/single-rendition, timeshift, and fixed-track capability/status are reported. Cast reports that the receiver controls quality; external-player launch/handoff integration remains open.
- [ ] Complete migration/restore/lifecycle validation and emulator playback validation. Migration and restore test coverage is still pending.

Current implementation boundary: Settings edits the global profile, connection policy, and provider-wide height/bitrate defaults with per-field Inherit/No additional limit/maximum choices. Playback can save current height or advertised bitrate ceilings per dimension for content or provider scope, restore inherited fields, opt a content scope out of a provider default per dimension, use temporary adaptive ceilings, and gate no-match/unknown-metadata playback pending explicit consent. Diagnostics report effective limits, policy sources, decoder pressure, adaptive versus single-rendition capability, timeshift, Auto versus Fixed track mode, and unavailable Cast enforcement; current-quality save actions are disabled during Cast playback. Consent is source-scoped and invalidated on policy, connection, multiview, compatibility, or source changes while same-media retries retain it. Candidate-specific preloading, external-player launch/handoff integration, migration/restore validation, and runtime playback validation remain open.

## What exists today

- Playback settings persist Auto/Data Saver/Balanced/Custom profile values, connection bitrate limits, and the existing Wi-Fi/Ethernet height caps in DataStore.
- Provider settings expose independent height and bitrate overrides with Inherit, No additional limit, and maximum choices; an existing saved maximum outside the preset list remains selectable.
- `PlayerTrackController` combines global profile, active network, provider-default, multiview, and compatibility constraints; unknown constrained metadata is ineligible and automatic exceedance is disabled.
- The player can list and manually select video tracks, save height and advertised-bitrate ceilings per dimension for content or provider scope, restore inherited fields, opt content out of provider ceilings per dimension, and set a temporary adaptive ceiling that clears on Auto or content change.
- VOD audio and subtitle choices persist globally and by movie or series scope.
- Live channel variant selections are remembered by provider and logical channel group.
- Adaptive HLS/DASH playback can temporarily restrict renditions when decoder pressure is detected.
- Multiview can impose a 720p ceiling.
- Compatibility decoder mode contributes a 720p maximum to the same resolved policy, so it cannot replace a stricter user maximum.
- Room schema 83 adds sparse provider/content preference rows with provider-delete cascade. Portable backup/restore resolves provider identities and retains stable remote scope keys; migration/restore runtime validation remains pending.
- Room already stores channel aspect ratio and AV offset, with deletion cascades and portable backup support. Migration 12 to 13 moved per-channel state out of DataStore. Its raw-channel row identity is different from a logical channel group's lifetime.
- `applyInitialParameters` clears audio, text, and video overrides; reactive quality updates must not call it unchanged.
- `PlayerTrack` exposes numeric height and advertised bitrate metadata for semantic saves. With an active quality limit, neighbor preloading prepares only the source/manifest and does not load a video range; tighter limits clear existing preload windows. Candidate-specific selector enforcement remains an optimization.

These mechanisms are useful, but they have separate inputs and do not give the user one view of the effective quality rule or why a rendition was selected.

## Product behavior

### Preference model

Save semantic adaptive ceilings, such as Up to 720p, rather than persistent fixed tracks or Media3 track IDs. Rendition IDs and ordering can change between sessions. Adaptive bitrate selection and decoder-pressure adaptation remain active within a saved ceiling.

Support these scopes:

1. A global playback profile and quality ceiling.
2. Optional connection-specific ceilings for Wi-Fi and Ethernet.
3. An optional provider default.
4. An optional channel or VOD item preference.
5. A temporary adaptive ceiling or fixed-track selection; a save action creates a durable adaptive ceiling, not a persistent track lock.

Global profile and active connection maxima combine using the strictest maximum in each dimension. Provider defaults supply content ceilings; saved channel/movie/series values replace provider defaults per field, and temporary adaptive values replace those saved defaults for the session. None can silently exceed the global/connection limits. Compatibility, device support, multiview, and decoder-pressure constraints can narrow eligibility further and cannot be bypassed by an above-limit exception.

For scoped fields, distinguish Inherit, No additional limit, and a specific maximum. No additional limit removes an inherited content default while retaining global/connection limits. Player Auto clears temporary selection and resumes applicable policy. Remove saved preference separately clears durable content settings. Auto must not be presented as unlimited when inherited limits remain.

Initial presets are product defaults, not device-performance promises:

| Profile | Maximum video height | Maximum advertised video bitrate |
| --- | --- | --- |
| Auto | No additional limit | No additional limit |
| Data Saver | 480p | 2 Mbps |
| Balanced | 720p | 4 Mbps |
| Custom | User selected | User selected |

Custom provides Auto/480p/720p/1080p/2160p height choices and No Limit/2/4/8 Mbps bitrate choices. Editing a preset copies it into Custom. Existing installs start with Auto and retain connection caps. Height and bitrate are independent limits; Maximum video bitrate filters advertised rendition metadata and does not throttle all network traffic or guarantee total data use. Frame-rate caps are deferred.

Live preferences apply to a stable provider/logical channel group, or an unambiguous remote channel when stable grouping is unavailable. Movie preferences apply to the logical movie or remote item; series preferences carry across episodes. Episode-specific settings and cross-provider identity matching are deferred.

Fixed track remains an advanced temporary mode. It obeys applicable limits but disables automatic decoder-pressure adaptation by design, and status explicitly says Fixed. If the selected rendition disappears, return to adaptive selection within policy with a notice. Saving a quality means saving a ceiling, using numeric metadata rather than a translated label; do not implicitly save the selected rendition's bitrate as a new cap without the user choosing that bitrate maximum.

### Source capability and fallback

Apply rendition limits to adaptive sources that expose multiple tracks, including HLS and DASH. Preserve the existing provider-level stream variant choice (for example, a channel's SD or HD URL) as a separate source choice in the same quality experience. Do not suggest that a fixed-quality progressive or transport stream can be downshifted when it exposes no lower rendition.

[Media3 permits exceeding video constraints by default](https://developer.android.com/reference/androidx/media3/exoplayer/trackselection/DefaultTrackSelector.Parameters#exceedVideoConstraintsIfNecessary) when nothing fits. Configure the selector to disable automatic exceedance and validate manual overrides against the same eligibility rules. Install constraints before prepare/autoplay; once tracks are known, publish Checking quality, Within limits, Choice required, or Unverified exception.

If nothing qualifies, pause the whole player and preserve user play intent rather than starting an audio-only session. Choice required is intentional waiting and must not trigger stuck timeouts or recovery that bypasses policy. Offer Choose another available source, Continue once, and Stop. Display the limit being exceeded and the offered quality before consent. An exception for a known rendition relaxes only the required dimensions up to the displayed maxima; unrelated and runtime safety limits still apply.

Unknown height excludes a track when height is constrained; unknown advertised bitrate excludes it when bitrate is constrained. Never treat unknown as zero. If no eligible track remains, explain Cannot verify quality or No quality meets your limits. A user can explicitly continue with an unverified exception. Source metadata can be inaccurate: pause and re-evaluate if later format information contradicts it. This cannot guarantee zero preparation traffic or prevent every transient decode of incorrectly described content.

Keep provider-source choice distinct from adaptive tracks; offer a lower source explicitly, then evaluate it under policy. Automatic provider-URL switching to enforce caps is deferred.

### Player and settings experience

- Put profile and connection limits in Settings > Playback, alongside the existing network quality caps.
- [x] Add provider-default controls to provider settings using Inherit/No additional limit/maximum choices.
- Extend the player quality panel with Auto within policy, adaptive ceilings, Fixed track, provider sources, Save for this channel/movie/series, and Remove saved preference. Cover TV remote focus and mobile layouts.
- Show requested policy and observed playback quality together, such as “Balanced · up to 720p · playing 480p.”
- In player diagnostics, explain which constraint is active: profile, connection, saved content choice, decoder protection, multiview, or source availability.
- Diagnostics include every contributing constraint, effective maxima, supported/eligible tracks, selected format, connection class, session/policy revision, and blocked/downshift reasons. Use the existing log sanitizer for URLs/tokens. Unknown metadata remains unknown, and Fixed/temporary exception states are visible.
- Keep audio and subtitle preferences working as they do today. Align them with shared scope conventions where that does not broaden this release unnecessarily.

## Policy and data boundaries

- Put profile, inherited-field, scope, candidate metadata, effective-limit/reason, and enforcement-status models in `domain`, with a pure resolver that imports no Android/Media3 types.
- A playback policy coordinator owns session inputs and revisions. Compatibility, multiview, and decoder-pressure components submit constraints; one adapter applies the combined video result. A compatibility maximum of 720p plus a user maximum of 480p stays 480p. Removing runtime constraints restores remaining user policy, not unlimited playback.
- The adapter owns video overrides so a saved adaptive ceiling does not disable pressure adaptation by appearing as a foreign override. Fixed mode follows its separate behavior. Reactive video changes preserve audio/subtitle overrides, injected subtitles, playback speed, and play intent.
- Keep global profile, Custom values, and connection settings in DataStore. Retain existing Wi-Fi/Ethernet height keys as authoritative to avoid duplicate migration/write paths.
- Add a sparse Room `playback_quality_preferences` table for provider/content settings: provider FK/cascade, scope type/key/identity version, inherited height/bitrate fields, and unique provider/scope index. Keep persistence behind domain-facing preferences/repository contracts.
- Do not attach logical-group quality only to `ChannelPreferenceEntity.channelId`: changing variants changes the raw row, and removing one member must not erase a group's ceiling. Reuse Room lifecycle/portable preference infrastructure while supporting the correct scope lifetime.
- Prove group identity stability across sync and grouping changes, or fall back to remote-item scope. Include parent/category namespace when a provider requires it. Ambiguous matches do not receive a preference. Keep temporarily absent settings dormant; partial/failed sync is not evidence of permanent removal.
- Follow `docs/DATABASE_MIGRATION_INVARIANTS.md`: migrate from the version current at implementation, export the schema, cover populated origins, and check foreign keys.
- Classify new global keys in `PreferenceBackupRegistry`; export scoped settings through portable provider/content references, never local IDs. Extend pending restore for catalogs that hydrate after import.
- Define absent versus explicitly-empty sections, merge/replace behavior, ambiguous/unmatched reporting, and cross-store restore checkpoint/rollback. Older backups lacking quality settings retain corresponding current settings. Test restore with changed provider/catalog IDs. Exclude temporary selections, consent, observation caches, and runtime pressure state from backup.

## Playback lifecycle and temporary choices

Observe preference and connection changes instead of only sampling `.first()` during prepare. Unclassified connections use global limits; offline playback does not inherit a stale connection class. Re-resolve at prepare, content/source/manifest changes, settings/network revisions, player recreation, retries/recovery, compatibility changes, multiview entry/exit, preload promotion, and live/timeshift transitions.

Scope asynchronous work to session and policy revision; reject old-channel/old-policy callbacks. Apply changes idempotently without track-update loops. Tightening a cap gates incompatible playback before resuming; relaxing it lets ordinary adaptation choose when to raise quality.

Temporary ceilings/fixed choices persist through same-session transient reconnects and player recreation after revalidation. They end on new content, Stop/close, process restart, or Auto. A next episode ends a temporary choice; a saved series ceiling carries forward. View-model/navigation recreation that preserves the session must preserve its identity.

Above-limit consent additionally binds to connection class, applicable user-policy revision, and source identity. Changing those inputs, source switching, or recovery to a different source invalidates consent. Same-source retries may retain it. Consent never becomes a saved preference or authorizes another channel.

Preload selectors must enforce each candidate's limits before selecting/loading video, using independent selector instances. A foreground exception cannot apply to adjacent candidates. Cancel/invalidate incompatible preloads on tighter limits and revalidate at promotion. If candidate-specific enforcement is unsupported, reduce that candidate's preload to source/manifest preparation rather than load unconstrained video.

Timeshift snapshots cannot supply a lower rendition never captured. Check snapshot format and explain the limitation or offer return to live; do not silently play above changed limits. Local recordings use fixed-source capability reporting. External/cast players report enforcement unavailable when they cannot support these limits; internal-player policy must not be presented as controlling them.

## Delivery stages

### Stage 0: Identity and integration design (partially complete)

- Map live/raw/movie/series identities to storage and portable references, including concrete provider-specific fallbacks.
- Specify Room schema/inherited-field encoding and trace all selector writers and preload setup.
- Design the capability gate, recovery suppression, and preservation of play intent.
- Design TV/mobile flows using the resolved product rules above.

Gate: identity lifetime, schema, and single selector ownership cover existing playback paths.

### Stage 1: Shared policy and migration (implementation complete; validation pending)

- [x] Add the domain preference types and a pure resolver for profile, network, saved scope, source capability, and runtime constraints.
- [ ] Add policy tests for field inheritance, unknown/malformed metadata, mixed limits, no match, and exceptions; numeric candidate metadata is exposed in player models.
- [x] Read existing Wi-Fi and Ethernet height caps as equivalent constraints so the user's current settings retain their meaning.
- [x] Add preference repository flows and update operations for global and scoped preferences with validation and default behavior.
- [x] Implement scoped Room persistence, migration, provider cascade, and portable backup/restore with provider identity remapping and checkpoint rollback.

Gate: legacy caps round-trip, scoped settings survive restart/portable restore, and no preference attaches to ambiguous content.

### Stage 2: Apply policy to playback (in progress)

- [x] Apply global, connection, provider-default, content-scope, multiview, and compatibility limits through the player track controller; constrained automatic selection is strict.
- [x] Carry a temporary adaptive ceiling through preparation/retry, return to Auto on set/clear, and discard it when content changes.
- [x] Re-resolve active playback when global preferences, connection class, or the current provider/content scoped preference changes; cancel stale scoped observers when playback identity changes.
- [x] Retain and revalidate a manual video-track override through same-media retries; new media resets the selection and unavailable or newly disallowed tracks return to Auto.
- [x] Scope consent to the current source; invalidate it for global/scoped/temporary policy, connection, multiview, compatibility, and media changes, while retaining it through same-media retries. Runtime recreation/source transitions remain to be validated.
- [x] Integrate the resolver with decoder-pressure and multiview constraints without allowing temporary automatic changes to overwrite user preferences.
- [x] Keep provider SD/HD stream switching available and report provider variants distinctly from adaptive rendition capability.
- [x] Diagnostics show actual resolution/bitrate, effective quality limits, grouped limiting sources, and active decoder-pressure ceilings; sanitized logs include the same policy and pressure information.
- [x] Strict selector behavior and manual override revalidation; gate no-match and unverifiable candidates; report fixed mode, internal source capability, timeshift, and temporary consent.
- [x] Integrate settings/network observation, policy/source consent expiry, same-media retry retention, and compatibility changes. Runtime recreation, timeshift, and external-source transitions remain to be checked.

Gate: all supported internal playback paths respect policy; gated waiting causes no recovery loop and video updates preserve other track choices.

### Stage 3: Settings and saved choices (in progress)

- [x] Add profile selection and Custom controls for height and maximum video bitrate alongside the existing Wi-Fi/Ethernet height controls.
- [x] Add in-player save-as-adaptive-ceiling and remove-saved-ceiling actions for supported live channels and VOD items.
- [x] Add a session-only adaptive ceiling that returns playback to Auto; Auto/content changes clear the temporary ceiling.
- [x] Manual fixed-track selection remains temporary and survives same-media retry after revalidation; provider settings expose Inherit/No additional limit controls for both quality dimensions.
- [x] Persist manual quality changes only after a user chooses a saved content or provider scope; temporary limits and exceptions remain session-only.
- [x] Add player-menu provider-default save/remove actions, per-field reset controls, provider-settings controls, fixed-mode labels, source capability explanations, and scoped consent.

Gate: users can save/reset a ceiling and recognize a temporary exception through TV/mobile controls.

### Stage 4: Backup, compatibility, and release readiness (in progress)

- [ ] Validate versioned backup parsing, invalid-entry handling, and older backups that lack quality sections.
- [ ] Verify provider deletion and playlist refresh do not attach a preference to a different logical channel.
- [ ] Verify older installs retain their network caps and receive a safe Auto default for new profile settings.
- [ ] Add or update UI and runtime tests at the domain, repository, player, and playback integration boundaries.
- [x] Implement backup classification, checkpoint rollback, and portable provider identity remapping for the scoped quality section.
- [ ] Validate backup classification, checkpoint rollback, deferred catalog identity remapping, and preloading restrictions introduced in earlier stages.
- [x] Document metadata-based limits and stricter legacy no-match behavior: previous caps could permissively fall back above the selected height.

Gate: release the complete vertical feature after storage, enforcement, diagnostics, UI, and playback validation pass.

## Acceptance criteria

- Auto, Data Saver, Balanced, and Custom produce deterministic eligible-rendition sets for a given source and runtime constraint.
- Existing Wi-Fi/Ethernet cap values still constrain playback after upgrade.
- A channel/content preference survives app restart and source refresh when its stable identity is unchanged.
- Temporary quality selections never persist implicitly; a save action creates the corresponding semantic ceiling, and exceptions are never saved.
- Known rendition metadata respects effective ceilings unless the user accepts a scoped exception. Unknown metadata requires explicit unverified consent when constrained, and later contradictory metadata triggers re-evaluation.
- Unsupported or fixed-quality sources are described accurately; a no-match case is visible and does not silently break playback.
- Decoder protection and multiview continue to work, and neither silently raises a user's configured ceiling.
- Backup/restore and provider deletion have defined behavior for saved overrides.
- Playback diagnostics make it possible to distinguish a user cap from network adaptation, decoder protection, multiview, and source limitations.
- Unknown constrained metadata never silently qualifies. Intentional waiting neither autoplays audio-only nor triggers stuck/recovery loops.
- Compatibility mode cannot raise a 480p cap to 720p. Saved adaptive ceilings preserve pressure adaptation; Fixed mode is explicitly different.
- Live settings/network changes take effect without waiting for a new content prepare, and stale callbacks cannot overwrite the current policy.
- Temporary consent expires on content/source/connection/policy changes as specified, and never spreads to preload candidates.

## Validation plan

Use deterministic manifests and supported decoders to assert selected rendition metadata and eligibility through preparation and every transition. Changing screenshots verify liveness, not cap enforcement. Advertised video bitrate and observed transfer rate must not be conflated.

| Scenario | Required evidence |
| --- | --- |
| Scope inheritance and mixed dimensions | Inherit differs from No additional limit; each rendition satisfies both effective maxima. |
| Unknown/no-match/fixed-quality sources | Accurate gating; no above-cap or audio-only autoplay; scoped Continue once and Stop work. |
| Saved adaptive and temporary Fixed modes | Adaptive selection can downshift within a saved ceiling; fixed mode is intentionally nonadaptive and gracefully handles missing tracks. |
| Compatibility/multiview/pressure | Runtime constraints never raise user limits; removing them preserves remaining policy and audio/subtitle selections. |
| Connection/settings changes | New revisions apply live, relevant consent expires, and stale work is rejected. |
| Retry/recreation/source fallback/next episode | Same-session choices survive applicable reconnects; consent and temporary preferences expire at their defined boundaries. |
| Manifest/preload/timeshift transitions | No stale override or incompatible background video load; snapshot limitations are visible. |
| Restart/sync/group member removal | Stable scopes restore without transfer to other content or deletion when one variant disappears. |
| Migration/provider deletion/portable restore | Populated migration preservation, cascade cleanup, changed-local-ID mapping, pending restore, and rollback. |
| TV/mobile controls and diagnostics | Focus/save/reset/Auto/exception states work; actual quality and sanitized reasons agree. |

Exercise HLS and DASH, at least two live channels, a VOD movie and successive series episodes, separate provider sources, and a fixed-quality stream. Include bandwidth-driven adaptation, decoder pressure, unknown metadata, and a cap tightening during active playback.

- For live playback validation, test more than one live channel. Capture 61 screenshots per channel at a two-second interval (about two minutes), confirm hashes change and the video region progresses throughout the capture, and check that the media session remains `PLAYING` with `error=null`. Clock/overlay changes alone are not evidence of progressing video.
- Review sanitized emulator logs for HLS prepare/read/first-frame or intended recovery, no fatal player error, no stuck-player timeout, no unintended MPEG-TS fallback, and evidence that quality policy was applied. Record channel names, frame counts, interval, unique hash counts, media-session status, and log findings.
- Also record applied limits, the selected rendition timeline, and any explicit exceptions. Intentional Choice required cases use expected paused/gated assertions instead of requiring PLAYING.

## Out of scope for this release

- A guaranteed network-wide bandwidth shaper or a guarantee on total bytes transferred.
- Automatically changing a provider's URL or transcoding its stream.
- A thermal sensor or device-temperature promise. Decoder-pressure protection remains based on playback signals already available to the app.
- Cloud-synced preferences or a broad redesign of every audio, subtitle, buffer, decoder, and transport setting.
- Persistent fixed tracks, episode-specific rules, and recording/download/timeshift capture quality controls. Their playback sources still receive capability reporting.

## Relevant code areas

- `domain/src/main/java/com/streamvault/domain/settings/PlayerPreferences.kt`
- `domain/src/main/java/com/streamvault/domain/settings/SettingsPreferences.kt`
- `domain/src/main/java/com/streamvault/domain/settings/VodTrackPreferences.kt`
- `data/src/main/java/com/streamvault/data/preferences/PreferencesRepository.kt`
- `data/src/main/java/com/streamvault/data/local/entity/Entities.kt` and `dao/Daos.kt`
- `domain/src/main/java/com/streamvault/domain/manager/BackupManager.kt`
- `data/src/main/java/com/streamvault/data/manager/PreferenceBackupRegistry.kt`
- `data/src/main/java/com/streamvault/data/manager/BackupManagerImpl.kt`
- `data/src/main/java/com/streamvault/data/manager/PendingBackupRestoreCoordinator.kt`
- `player/src/main/java/com/streamvault/player/PlayerTrack.kt`
- `player/src/main/java/com/streamvault/player/tracks/PlayerTrackController.kt`
- `player/src/main/java/com/streamvault/player/tracks/AdaptiveDecodePressureTracks.kt`
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt`
- `player/src/main/java/com/streamvault/player/playback/Media3PreloadWindowManager.kt`
- `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerPreparationCoordinator.kt`
- `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerPlaybackPreferenceActions.kt`
- `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsPlaybackSection.kt`
