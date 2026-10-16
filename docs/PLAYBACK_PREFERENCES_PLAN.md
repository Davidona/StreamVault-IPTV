# Playback Preferences and Quality Profiles

## Purpose

Expand [issue #263](https://github.com/Davidona/StreamVault-IPTV/issues/263) into a coherent playback preference system. Users should be able to set quality limits, save meaningful preferences for particular content, understand the quality currently playing, and trust that recovery and restarts preserve their choices.

This document specifies future implementation. The release covers profiles, connection limits, provider defaults, saved live-channel/movie/series ceilings, temporary choices, backup/restore, and playback lifecycle integration.

## What exists today

- Playback settings persist Wi-Fi and Ethernet maximum video heights in DataStore.
- `PlayerTrackController` applies the active network height to Media3 track selection.
- The player can list and manually select video tracks, but the video selection is session-local.
- VOD audio and subtitle choices persist globally and by movie or series scope.
- Live channel variant selections are remembered by provider and logical channel group.
- Adaptive HLS/DASH playback can temporarily restrict renditions when decoder pressure is detected.
- Multiview can impose a 720p ceiling.
- Compatibility decoder mode separately writes a 720p maximum after initial parameters, which can replace a stricter user maximum.
- Room already stores channel aspect ratio and AV offset, with deletion cascades and portable backup support. Migration 12 to 13 moved per-channel state out of DataStore. Its raw-channel row identity is different from a logical channel group's lifetime.
- `applyInitialParameters` clears audio, text, and video overrides; reactive quality updates must not call it unchanged.
- `PlayerTrack` needs structured height/bitrate metadata for semantic saves. Preloaded sources need policy enforcement before background video loading as well as foreground promotion.

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
- Add provider-default controls to provider settings using Inherit/No additional limit/maximum choices.
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

### Stage 0: Identity and integration design

- Map live/raw/movie/series identities to storage and portable references, including concrete provider-specific fallbacks.
- Specify Room schema/inherited-field encoding and trace all selector writers and preload setup.
- Design the capability gate, recovery suppression, and preservation of play intent.
- Design TV/mobile flows using the resolved product rules above.

Gate: identity lifetime, schema, and single selector ownership cover existing playback paths.

### Stage 1: Shared policy and migration

- Add the domain preference types and a pure resolver for profile, network, saved scope, source capability, and runtime constraints.
- Add policy tests for field inheritance, unknown/malformed metadata, mixed limits, no match, and exceptions; expose numeric candidate metadata in player models.
- Read existing Wi-Fi and Ethernet height caps as equivalent constraints so the user's current settings retain their meaning.
- Add preference repository flows and update operations with validation and default behavior.
- Implement scoped Room persistence, migration, provider cascade, portable backup and pending restore together, before exposing saved settings.

Gate: legacy caps round-trip, scoped settings survive restart/portable restore, and no preference attaches to ambiguous content.

### Stage 2: Apply policy to playback

- Route adaptive HLS/DASH rendition selection through the resolved policy.
- Integrate the resolver with decoder-pressure and multiview constraints without allowing temporary automatic changes to overwrite user preferences.
- Keep provider SD/HD stream switching available and report it distinctly from an adaptive rendition.
- Report actual selected resolution/bitrate and the effective limiting reason to the playback UI and diagnostics.
- Introduce single video-policy ownership, strict selector behavior, capability gating, fixed-mode validation, and temporary consent.
- Integrate settings/network observation, session revisions, retry/recreation, preload, compatibility mode, and timeshift checks.

Gate: all supported internal playback paths respect policy; gated waiting causes no recovery loop and video updates preserve other track choices.

### Stage 3: Settings and saved choices

- Replace or regroup the existing Wi-Fi/Ethernet quality-cap controls under the profile model while preserving their values.
- Add profile selection and Custom controls for height and maximum video bitrate.
- Add in-player save, reset, and temporary selection actions for supported live channels and VOD items.
- Ensure a manual action is persisted only when the user chooses a saved scope.
- Add provider-default controls, fixed-mode labels, inherited states, source capability explanations, and scoped consent.

Gate: users can save/reset a ceiling and recognize a temporary exception through TV/mobile controls.

### Stage 4: Backup, compatibility, and release readiness

- Validate versioned backup parsing, invalid-entry handling, and older backups that lack quality sections.
- Verify provider deletion and playlist refresh do not attach a preference to a different logical channel.
- Verify older installs retain their network caps and receive a safe Auto default for new profile settings.
- Add or update UI and runtime tests at the domain, repository, player, and playback integration boundaries.
- Verify backup classification, checkpoint rollback, deferred identity remapping, and preloading restrictions introduced in earlier stages.
- Document metadata-based limits and stricter legacy no-match behavior: previous caps could permissively fall back above the selected height.

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
