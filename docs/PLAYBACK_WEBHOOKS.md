# Playback webhooks

Open **Settings → App & Remote → Playback webhooks**, add a destination, save it, and enable playback webhooks. The feature is off by default. Up to four independently enabled destinations can receive events at the same time.

Enter a complete HTTP or HTTPS URL. For bearer authentication, use `Authorization` as the header name and `Bearer YOUR_TOKEN` as the header value. A custom header such as `X-API-Key` is also supported. URLs and header values are masked while editing, encrypted on disk, and excluded from app backups.

Each destination selects its content types, events, payload format, delivery policy, progress interval, and watched threshold. Activation controls cycle through the available values and work with touch, keyboard, or D-pad. Back returns from preview to the editor, and from the editor to the destination list. Unsaved edits are discarded when leaving the editor.

## Native events

Native JSON supports `start`, `pause`, `resume`, `progress`, `watched`, and `stop`:

```json
{
  "schema_version": 1,
  "event_id": "0160cf66-621e-46dd-91b1-be6a80ce00a2",
  "session_id": "0a53b69b-eed3-459f-974d-de82b0fb18fe",
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

- Progress defaults to 30 seconds and can be disabled or set to 15, 30, or 60 seconds. It is emitted while playing, with no burst of missed updates after suspension.
- Watched defaults to 90% of the playback position; the control cycles from 50% through 100% in 5% steps. It is emitted once per session after a real advancement sample. Opening or seeking near the end alone does not emit watched; playing afterward can. This definition does not mean the same percentage was consumed.
- `played_ms` measures confirmed active playback intervals in the current session. Pauses, buffering, and explicit seek jumps are excluded. Rewatching a segment can count again.
- `ids` identifies the movie or episode itself. `series_ids` identifies its series. IDs are omitted when unknown. A show TMDB ID is never sent as an episode TMDB ID. Known canonical IMDb identities from Stremio are included in the correct namespace.
- Live playback has null duration/progress percentage and never emits watched. Its position describes session playback time. Channel switches stop the previous session before starting the next.
- Stop includes `stop_reason`: `user`, `replaced`, `completed`, or `error`. Natural completion is captured before autoplay. Engine/variant recovery stays in the same viewing session. Terminal recovery exhaustion ends it; a later user retry can start a new session.
- Event IDs remain stable across delivery retries. Sequence numbers increase within a destination's session; consumers can use them to reject obsolete updates.

The standard in-app player is supported. Catch-up, recordings, trailers, unidentified external URLs, cast playback, external-player playback, and multiview are excluded. A successful cast handoff ends local tracking. Stremio items can emit native events when the app has a recognized movie/episode reference; metadata availability determines which IDs are present.

## Formats and receivers

**Native JSON** is intended for custom trackers, relays, and home automation. Preview never makes a request. Send test event explicitly sends `event: "test"`; production automations should filter the event types they handle.

**Jellyfin format** follows the playback subset of the [official plugin v22 field contract](https://github.com/jellyfin/jellyfin-plugin-webhook/tree/v22). It sends `PlaybackStart` and `PlaybackStop`, flat item/provider fields, playback ticks (10,000 ticks per millisecond), and `PlayedToCompletion` on stop. It represents StreamVault's own installation/item identities, not identities in a Jellyfin library. Standalone Watcharr compatibility is not provided: Watcharr's receiver uses linked Jellyfin users and performs server-side series lookups.

**Yamtrack format** follows the nested `Event`/`Item` receiver contract in [Yamtrack v0.26.3](https://github.com/FuzzyGrim/Yamtrack/blob/v0.26.3/src/integrations/webhooks/jellyfin.py). Movie delivery requires a known movie TMDB or IMDb ID. That released receiver resolves episodes through actual episode IMDb or TVDB IDs, as its [TV processor](https://github.com/FuzzyGrim/Yamtrack/blob/v0.26.3/src/integrations/webhooks/tv.py) demonstrates. A series TMDB ID plus season/episode numbers alone is insufficient for that version. Such deliveries are locally skipped, with a diagnostic, rather than marked delivered. The current catalog models generally provide series IDs, so native JSON or a receiver that supports series-based matching is the useful episode integration today.

Tracker presets emit start/stop only. Pauses, progress updates, and reaching the threshold do not generate fake start/stop notifications. Completion is included on the eventual stop. Synthetic tests are unavailable for these presets to avoid inserting fake viewing history. Verify a real movie in the receiver after setup; an HTTP success only acknowledges receipt, not a completed receiver-side match.

## Delivery behavior

**Real time** expires events after 30 seconds and coalesces obsolete pending states. It can suppress a pending pause or stop when newer state is queued, including the next item's start. It suits lighting and other automations where replaying old commands would be undesirable. Choose History when every transition is needed. An already received request cannot be recalled.

**History** persists ordered transitions for up to 24 hours, with up to five attempts per event. A background worker recovers persisted events after process interruption. Progress is ephemeral in both modes. Delivery is at-least-once within that budget; receivers should deduplicate event IDs. Third-party receivers may not implement deduplication.

Each destination has an independent worker and a bounded queue. Temporary network errors, HTTP 408/429, and 5xx responses retry with bounded backoff; `Retry-After` is capped at five minutes. Other responses, including redirects and authentication failures, are rejected. Response bodies are not retained. Delivery never waits on the playback thread.

Disabling, deleting, or editing a destination cancels its pending work and clears obsolete deliveries. Editing one destination preserves the sessions of other destinations. The global switch stops all destinations. Diagnostics identify delivered, retrying, rejected, expired, overflowed, unsupported, or storage-failure outcomes without exposing endpoint paths or authentication values.

Abrupt process termination cannot guarantee a final stop; only already persisted history events can recover. Destination fan-out sends matching playback to every enabled destination. It does not identify which household member is watching.

## Home Assistant example

Use a native destination pointing to a Home Assistant webhook and select start, pause, resume, and stop. An example automation structure is:

```yaml
triggers:
  - trigger: webhook
    webhook_id: your-private-webhook-id
    allowed_methods: [POST]
    local_only: true
conditions:
  - condition: template
    value_template: "{{ trigger.json.event in ['start', 'pause', 'resume', 'stop'] }}"
actions:
  - choose:
      - conditions: "{{ trigger.json.event in ['start', 'resume'] }}"
        sequence:
          - action: light.turn_on
            target:
              entity_id: light.living_room
            data:
              brightness_pct: 15
    default:
      - action: light.turn_on
        target:
          entity_id: light.living_room
        data:
          brightness_pct: 80
```

Adjust the destination, light entity, and brightness for your installation. The webhook test event is ignored by the condition.

## Validation

Run `python tools/verify_webhook_locales.py` to check resource keys and formatting contracts in all advertised locales. `PlaybackWebhookContrastTest` verifies the new text and focus pairs for every theme. Unit and opt-in emulator journeys cover lifecycle events, delivery isolation, encryption, preview/error layouts, and playback progression. Record acceptance results and device limitations with each release's test evidence; build/install success alone is not playback validation.
