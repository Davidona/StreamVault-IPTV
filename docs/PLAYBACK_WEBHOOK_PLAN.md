# Playback webhooks: issue #325 implementation plan

Status: implemented and emulator-validated on `codex/playback-webhooks`, based on local `develop` at `1588c6406`. Issue and all three comments reviewed 2026-11-12; final acceptance completed 2026-11-13. Real receiver deployments and physical TVs remain untested.

### Implementation progress

- Added the independent domain event model and lifecycle planner, including progress cadence, completion threshold, session ordering, metadata enrichment, live behavior, and discontinuity handling.
- Added explicit native JSON serialization and a dedicated cancellable single-attempt HTTP sender. The sender disables redirects and implicit retries, supports custom authentication headers, classifies failures, and bounds `Retry-After`.
- Implemented encrypted destination configuration, independent bounded dispatchers, durable history outboxes, and background recovery. Destination edits cancel obsolete work while preserving untouched sessions.
- Connected the standard player lifecycle, metadata, channel switches, completion, recovery exhaustion, and cast handoff. Buffering and physical variant recovery preserve the logical viewing session.
- Added the settings page, searchable entry, payload preview, explicit native test, diagnostics, filters, and all 47 resources in all 38 advertised locales. Added theme contrast and emulator journeys.
- Implemented separate official Jellyfin v22 and Yamtrack v0.26.3 formats. Released Yamtrack requires true episode IMDb/TVDB identities; a series TMDB identity alone is insufficient. Watcharr still requires a real linked Jellyfin service.
- Final acceptance results and remaining device/receiver limitations are recorded in `PLAYBACK_WEBHOOK_VALIDATION.md`. See `PLAYBACK_WEBHOOKS.md` for setup and the implemented event/delivery contract.
- First-release UI choices: a single optional header name/value pair covers bearer and custom-token authentication; advanced controls are visible in the scrolling editor. Diagnostics show status, HTTP status, and pending count. Disabling/deleting clears pending history. A dedicated history browser/clear button, attempt-time display, and collapsible advanced section remain follow-ups.

## Product scope

Build optional outgoing playback webhooks for self-hosted tracking and home automation. Nothing is sent until a destination is configured and enabled. Trakt remains independently configurable.

The [issue](https://github.com/Davidona/StreamVault-IPTV/issues/325) requests start, pause, resume, stop, and watched events, configurable watched percentage, endpoint URL, optional authentication, movie/episode/live metadata, and a Jellyfin-compatible preset. The [requester's follow-up](https://github.com/Davidona/StreamVault-IPTV/issues/325#issuecomment-6615041392) adds periodic progress events, around every 30 seconds, and TMDB IDs whenever already known. The other comments acknowledge the request and discuss release timing; they add no technical requirements.

Recommended additions for the first release:

- Multiple named destinations, independently enabled, so a tracker and Home Assistant can run together.
- Per-destination event and content-type filters.
- Versioned payloads, stable event IDs, playback session IDs, and event ordering.
- Payload preview, a safe test action, and a small redacted delivery-status view.
- Separate delivery policies for current-state automation and recoverable watch-history writes.
- Actual session playback time alongside position, so a receiver can distinguish elapsed playback from seeking.

These additions serve the original use cases. A full profile system, arbitrary scripting, bidirectional sync, and extra transport protocols are separate projects.

## What the code already provides

| Existing area | Implication |
| --- | --- |
| `feature/playback/.../PlayerViewModel.kt` | Already samples playback every 500 ms, reports terminal playback before autoplay, and performs cleanup on exit. Reuse observation points; do not add a second polling loop. |
| `PlayerTraktSupport.kt`, `PlayerTraktCoordinator.kt` | Useful examples of session guards and optional tracking isolation. They intentionally exclude live/catch-up and require Trakt identity. Webhooks need an independent model that also works without external IDs. |
| `domain/.../trakt/TraktScrobble.kt` | Trakt has start/pause/stop semantics and maps resume to start. Do not change this public behavior to accommodate webhook event names. |
| `domain/.../model/Movie.kt`, `Series.kt` | Movies and series expose TMDB IDs. Episodes have season/episode numbers but no episode-specific external IDs in these models. Do not fabricate IDs. |
| `domain/.../util/PlaybackCompletion.kt` | Existing library completion defaults to 95%. A configurable webhook threshold must not silently alter library history or Trakt behavior. |
| `data/.../security/CredentialCrypto.kt`, `EncryptedTraktTokenStore.kt` | Reuse the credential-encryption abstraction and the separate no-backup store pattern for sensitive destination configuration. |
| `app/.../di/TraktModule.kt` | Demonstrates a dedicated HTTP client without redirects or implicit connection retries. Avoid the general IPTV client's logging/interceptors for webhook requests. |
| `feature/settings/.../SettingsDestination.kt`, `SettingsContentPane.kt` | Add a searchable Playback webhooks page under App settings, preserving existing destination IDs and Trakt navigation. |

The checkout was clean before the planning document was created. The implementation adds independent webhook observation; existing Trakt and library completion policies remain separate.

## Compatibility must be verified per receiver

"Jellyfin webhook" does not describe one universal receiver contract:

- The [official Jellyfin plugin](https://github.com/jellyfin/jellyfin-plugin-webhook) supports templated payloads and fields such as notification type, playback position ticks, and played-to-completion. A flat official-plugin format is a useful preset, but does not guarantee receiver compatibility.
- [Yamtrack's released receiver](https://github.com/FuzzyGrim/Yamtrack/blob/v0.26.3/src/integrations/webhooks/jellyfin.py) expects nested `Event`, `Item`, and optional `Series` objects. Movie matching accepts movie TMDB/IMDb; episode matching requires true episode IMDb/TVDB. The preset skips unsupported identities locally.
- [Watcharr's receiver](https://github.com/sbondCo/Watcharr/blob/dev/server/feature/jellyfin/webhook.go) resolves users through linked Jellyfin services and looks up episode series in Jellyfin. Its [data contract](https://github.com/sbondCo/Watcharr/blob/dev/server/feature/jellyfin/webhook_data.go) uses the official flat fields. Inference: simply emitting those fields from StreamVault cannot provide general standalone Watcharr support. Matching real Jellyfin user/series IDs or a separate receiver adapter would be needed.
- [Home Assistant](https://www.home-assistant.io/docs/automation/trigger/#webhook-trigger) can receive the native JSON format. Supply an example automation using only the selected playback transitions.

The implemented presets pin released field contracts. HTTP 2xx alone does not establish receiver-side matching or history insertion; real third-party receiver deployments remain a separate acceptance boundary.

## Event contract and lifecycle

Introduce a pure domain planner, independent of Trakt, fed immutable playback observations. Freeze the outgoing event before switching content or clearing the player.

| Event | Proposed behavior |
| --- | --- |
| `start` | Once after actual playback begins, rather than when preparation or buffering starts. |
| `pause` | When an established session becomes intentionally paused. Temporary buffering, engine recovery, and seeks must not produce this event. |
| `resume` | Once when a paused session actually plays again. Buffer recovery is not a resume. |
| `progress` | Every 30 seconds by default while playing; selectable off/15/30/60 seconds. No catch-up burst after suspension. |
| `watched` | Once per movie/episode session when the configured position percentage is reached during actual playback. Default 90%; configurable 50–100%. No watched event for live or unknown duration. |
| `stop` | Once on user exit, content replacement, natural completion, or final unrecoverable failure. Include the final snapshot and reason. Stop old content before starting new content. |

Proposed native envelope, retaining the issue's field names where practical:

```json
{
  "schema_version": 1,
  "event_id": "uuid",
  "session_id": "uuid",
  "sequence": 4,
  "event": "progress",
  "timestamp": "2026-11-12T12:00:00Z",
  "type": "episode",
  "title": "Episode title",
  "series": "Series title",
  "season": 2,
  "episode": 3,
  "series_ids": { "tmdb": 1396 },
  "position_ms": 1234567,
  "duration_ms": 2700000,
  "progress_percent": 45.72,
  "played_ms": 180000,
  "watched": false
}
```

Contract details:

- `ids` identifies the played movie or episode; `series_ids` identifies the series. A series TMDB ID must never masquerade as an episode TMDB ID. Omit absent IDs, including IMDb/TVDB unless genuinely available.
- Native events still work when metadata is incomplete. An adapter requiring an external ID reports a local skipped/no-match result instead of guessing by title.
- `played_ms` is monotonic active playback time for this session, excluding pause, buffering, and seek jumps. It is not unique media coverage and may include rewatched segments.
- `watched` explicitly means the configured position threshold was reached; it is not proof that the same percentage was consumed. Require a real advancement sample, not opening/resuming/seeking alone, to trigger it. Document that playing briefly after seeking near the end can qualify. Strict consumed-coverage tracking is a possible later mode.
- Once true, watched stays true through rewind and stop within the session. Replaying creates a new session. Capture the threshold per session so changing settings cannot retroactively generate an event.
- Live uses elapsed session position and null duration/progress; no percentage-based completion. A channel switch is a stop/start pair even when both channels have the same title.
- Use UTC for event timestamps and monotonic time for progress scheduling and playback accounting.
- Metadata can enrich the current session when it becomes known; it must not create a second start. Do not make network metadata lookup a prerequisite for emitting an event.
- Do not export stream URLs, source credentials, playlist addresses, or artwork URLs by default. Use generated install/session identifiers rather than hardware identifiers if a receiver needs device identity.
- Abrupt process termination cannot guarantee a final stop. Persist only events already observed; never manufacture completion on the next launch.

Scope for the initial player integration: the standard in-app player for movies, episodes, and live TV, including Stremio items when verified metadata is available. Explicitly exclude trailers, unidentified external URLs, recordings/catch-up, external-player handoff, cast playback, and multiview from automatic emission initially. Add those paths only after separate identity and lifecycle tests; do not infer remote playback from the local engine.

## Architecture and delivery

Proposed components, with names subject to implementation conventions:

| Module | Work |
| --- | --- |
| `domain/playback/events` | Observation/event/media models, lifecycle planner, clock interfaces, destination/filter contracts. |
| `feature/playback/player` | Webhook coordinator and snapshot resolver alongside the Trakt integration. Use a logical media session that survives engine retries and quality changes; the prepare-request counter alone may not represent a new viewing session. |
| `data/webhook` | Encrypted destination store, native/Jellyfin/Yamtrack serializers, dedicated sender, bounded per-destination dispatcher, history outbox, redacted delivery results. |
| `data/local` | Outbox schema/migration if history recovery is enabled; persist no authentication headers or endpoint URLs in queue rows. |
| `app/di` | Bind configuration, dedicated HTTP client, application-lifetime dispatcher, and recovery worker. |
| `feature/settings` | Destination list/editor, filters, interval/threshold controls, preview/test, and delivery diagnostics. |

Behavior requirements:

1. Player callbacks enqueue immutable observations/events without awaiting HTTP or disk I/O. Serialize lifecycle state changes before asynchronous delivery; a slow destination cannot affect another destination, playback, or Trakt.
2. Preserve per-destination session ordering. Coalesce pending progress observations; never allow progress traffic to displace stop/watched events. Bound both memory and disk queues, and report overflow/expiry explicitly.
3. Provide two delivery policies: **real time**, the default for native automation destinations, with a short 30-second expiry and suppression of superseded pending states; and **history**, the default for tracker presets, with a durable ordered transition outbox and a 24-hour expiry. Progress is ephemeral in both. Explain that a receiver should use sequence/current-state checks where possible.
4. History delivery is at-least-once within the retry/expiry budget, not exactly-once. Reuse the same event ID across retries. Assume third-party presets may not deduplicate; do not promise duplicate-free history.
5. Use bounded timeouts, exponential backoff with jitter for connection failures/5xx/429, and respect bounded `Retry-After`. Stop automatic retries for permanent 4xx/auth failures and surface an actionable status. Disable implicit HTTP retries and redirects so the dispatcher owns delivery policy.
6. Reuse the app dispatcher for immediate sending; use WorkManager only to drain durable history after interruption. Do not schedule 30-second progress through WorkManager or block playback cleanup waiting for a request.
7. Disabling/deleting a destination cancels its pending work and clears its queue; disabling the feature sends nothing further. Endpoint/auth edits invalidate old queued deliveries so history is not accidentally sent to a replacement URL. State clearly that a request already received remotely cannot be recalled.
8. Support explicit HTTP endpoints for LAN installations and HTTPS with normal certificate validation. Reject malformed URLs, embedded userinfo, unsupported schemes, invalid header names/values, and transport-controlled headers. Never install a trust-all TLS mode.
9. Encrypt the full destination configuration, since tokens can be inside URL paths as well as headers. Keep it outside backups. Use destination names and opaque IDs in diagnostics; redact full paths, query strings, tokens, header values, and response bodies from logs and reports.
10. Independent destinations can represent separate tracker accounts, but all enabled destinations receive matching playback. This does not identify which person is watching. Household viewer selection needs a separate product design.

Before coding transport, fix the exact queue limits and retry ceilings in the contract tests; proposed starting limits are 4 destinations, 256 durable transition deliveries per destination, and 5 attempts per event within its expiry. If a history queue is full, discard expired items first and report failure for newly rejected deliveries rather than silently dropping terminal events.

## Settings experience

Add App → Playback webhooks, discoverable through Settings search. Show a master off-by-default switch and named destinations. The editor contains URL, preset, authentication mode (none/bearer/custom header), content types, event filters, progress interval, watched threshold, and delivery policy. Keep advanced controls collapsed initially.

The native preset supports every event. Receiver presets restrict unsupported events rather than translating pause/progress into misleading start/stop writes. For example, do not turn watched into a fake PlaybackStop while playback continues. Trackers that only recognize completion on stop receive completion there; native watched notifications remain immediate.

Preview is entirely local and masks sensitive values. Native destinations can send an explicitly requested synthetic `test` event. Third-party presets need a verified non-mutating test mechanism; otherwise show preview plus real delivery status and a documented test procedure, rather than inserting fake viewing history. Never auto-test on save.

Delivery diagnostics show last attempt, success/failure category, HTTP status, and pending/expired/skipped counts. Bound local retention and offer clear-history. Do not add player overlays or playback-blocking errors for webhook failures.

All copy belongs in the owning module's default `res/values/strings.xml` and real translations for every locale in `app/src/main/res/xml/locales_config.xml`. Use existing palette/typography/spacing tokens, defined D-pad order and Back destinations, overflow handling, TV-readable text, and phone touch targets.

## Implementation sequence and acceptance gates

1. **Receiver contract spike and fixtures.** Pin released Jellyfin-plugin, Yamtrack, and Watcharr versions. Verify one movie and one episode payload against actual receiver behavior. Document Watcharr's identity/server dependencies; choose an explicit bridge or direct receiver API project if needed. Deliver a compatibility matrix; never label an unverified preset as supported.
2. **Domain contract and planner.** Implement event semantics, identity namespaces, filtering, scheduling, deduplication, and fake-clock tests. Cover seek/resume near threshold, specials season zero, unknown/changing duration, late metadata, replay, and stale callbacks.
3. **Configuration and delivery backend.** Add encrypted storage, dedicated HTTP transport, per-destination queues, history outbox migration, retry/expiry/cancellation, and serializer fixtures. Use MockWebServer to exercise ordering, slow/unreachable endpoints, timeouts, redirects, 401/403, 429, 5xx, response-size limits, and secret redaction.
4. **Player integration.** Hook start/pause/play-state changes directly as well as the existing sampling loop; do not rely on a 500 ms sampler to see every transition. Preserve snapshots before autoplay/zap/cleanup. Test engine replacement, recovery, speed/quality changes, Back, natural end, final failure, and app background behavior. Verify Trakt event sequences remain unchanged.
5. **Settings and localization.** Implement destination list/editor, search entry, safe diagnostics/test/preview, and all advertised translations. Test edit/disable/delete during an in-flight delivery and during an active session. Enabling mid-playback creates a fresh webhook session on the next real playback observation, with no retroactive events.
6. **Adapters and documentation.** Ship native JSON/Home Assistant example, Yamtrack adapter, and the verified subset of official Jellyfin payload behavior. Watcharr support is a separately gated deliverable: a successful payload-shape test does not satisfy its receiver requirements. Publish configuration examples, payload schema, watched semantics, retry/duplicate behavior, privacy, backup policy, and explicit unsupported playback paths.
7. **End-to-end validation and beta.** Validate movie, episode, and at least two live channels with destinations enabled and disabled, including an unreachable endpoint. Ask the issue reporter to test a beta on their offered Sony TV/Dispatcharr-Xtream setup after emulator acceptance.

Suggested automated commands once implementation exists:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest :feature:playback:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest :core:ui:testDebugUnitTest
.\gradlew.bat :app:assembleDebug
```

Add targeted instrumentation for Settings navigation and the playback/webhook journey. Required repository acceptance evidence:

- Every changed screen, including dialogs and error/focus states, captured on emulator in `classic_blue`, `light`, and `amoled_black`, default and focused.
- Computed contrast for every changed text/surface and meaningful UI pair across all nine themes and relevant states, with token names and all-palette unit tests. Text meets 4.5:1 or the applicable large-text 3:1 threshold; meaningful UI/focus meets 3:1. No forbidden raw colors, sizes, or spacing.
- Translation-key/placeholder coverage for every advertised locale; long-translation layouts and RTL mirroring in Arabic, Persian, and Hebrew.
- For each of at least two live channels: prefer 61 screenshots at 2-second cadence, confirm changing frames throughout, record unique hashes and channel names, confirm media session PLAYING with `error=null`, and inspect sanitized logs for healthy HLS progression/recovery and absence of fatal/stuck/unintended MPEG-TS fallback. Include unreachable-webhook runs to prove isolation. Hash differences alone are insufficient if only UI chrome changes.
- Receiver-side history/state assertions, bounded queue/retry behavior, disable/delete cancellation, process-restart recovery, and no secret leakage.

Untested devices or playback paths must be labeled untested. TCL GPU frame measurement is required if implementation introduces the full-width translucent video layer prohibited by AGENTS.md; this design does not require such a layer.

## Further potential after the first release

- Household viewer selection tied to destination routing, without pretending destination fan-out is a user-profile system.
- Watcharr direct integration or a small external bridge once its supported API and identity model are verified.
- Optional HMAC signatures for custom receivers and receiver-side idempotency examples.
- Strict consumed-coverage completion mode, if users need a stronger watched definition than position percentage.
- Cast, multiview (with per-window identity), catch-up, and recordings as separately tested extensions.
- Seek/error/session-end events, configurable custom metadata, or MQTT only when concrete receiver needs justify them.

Recommended first-release boundary: finish the issue's event/metadata/configuration requirements, multiple destinations, reliable isolated delivery, useful diagnostics, native/Home Assistant and verified tracker adapters. Keep broader account management and unverified compatibility claims out of that release.
