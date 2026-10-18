# Custom REST/JSON sports provider

Use this advanced option when you have an HTTPS sports API that is not covered by a built-in provider. It makes ordinary GET requests and maps JSON into provider-neutral sports data: events, scores and status, standings, team and league metadata, broadcasters, artwork references, lineups, and statistics. Every section beyond `events` is optional. The adapter never runs scripts or supplies playback links.

In Settings > Sports data providers, choose **Custom JSON provider**. Enter the API's HTTPS base URL, then paste a definition or use **Load JSON file** to import one. Put the API key or token in the credential field, choose **Test**, and save only after the test succeeds. The inserted generic configuration is a template, not a working account.

## Starter configuration

```json
{
  "name": "My Sports API",
  "auth": { "mode": "api_key_header", "parameterName": "X-API-Key" },
  "events": {
    "endpoint": "/fixtures?from={fromDate}&to={toDate}&leagueId={leagueId}",
    "items": "data.events",
    "id": "id",
    "sport": "sport",
    "leagueName": "league.name",
    "leagueId": "league.id",
    "title": "title",
    "start": "start",
    "startFormat": "ISO_OFFSET",
    "homeName": "home.name",
    "awayName": "away.name",
    "status": "status"
  },
  "scores": {
    "endpoint": "/scores?ids={eventIds}",
    "items": "data.scores",
    "eventId": "id",
    "homeName": "home.name",
    "homeScore": "home.score",
    "awayName": "away.name",
    "awayScore": "away.score",
    "status": "status",
    "updatedAt": "updatedAt",
    "updatedAtFormat": "ISO_OFFSET"
  }
}
```

The API base URL is entered separately. Endpoint paths must begin with `/` and stay on that HTTPS host. The sample assumes fixture rows like `data.events[0]` and score rows like `data.scores[0]`; replace those assumptions with the exact response structure documented by your API. If endpoint paths include both `{sport}` and `{leagueId}`, configure scopes either in the JSON `scopes` array or the provider's league IDs setting as `sport/league` pairs.

Custom discovery runs at most four requests concurrently, with a 15-second discovery budget; completed scopes are retained when another scope stalls. Duplicate IDs retain the earliest scope in definition order. Event-scoped score calls also use four concurrent requests and retain completed results within four seconds. Guide and API discovery run concurrently, so either can supply visible events while the other finishes. Automatic clock refresh waits for an in-flight scan rather than canceling it. Optional broadcaster, score, and metadata enrichment cannot keep the Sports tab on its initial loading screen. Saving, editing, disabling, or removing an account invalidates the retained Sports tab.

## Configuration fields

- `name` is the label shown in provider settings.
- `auth.mode` is `none`, `api_key_header`, `api_key_query`, `bearer`, or `basic`. Header/query key modes require `parameterName`. Enter the key or bearer token in the secret field; Basic auth uses the username and password fields. Credentials are stored separately from the mapping.
- `events.endpoint` is a relative path. It may use `{date}`, `{fromDate}`, `{toDate}`, `{fromDateFormatted}`, `{toDateFormatted}`, `{leagueId}`, and `{sport}`. The raw date bounds are ISO timestamps; the formatted bounds use `events.dateFormat` and are UTC. `{toDateFormatted}` is the last date in the request interval, since the end bound is exclusive. For APIs that accept a range, place the separator in the endpoint template, for example `?dates={fromDateFormatted}-{toDateFormatted}`.
- Optional `events.dailyRequests: true` queries every UTC calendar date touched by the requested interval (exclusive end), using `{date}` and `events.dateFormat`. It requires `{date}` in the endpoint, accepts at most 14 calendar dates, and shares the same concurrency and time budget across dates and leagues. Leave it absent or `false` for APIs that accept a whole interval in one request.
- Optional top-level `scopes` declares the sport/league routes the adapter should query when the provider's **League IDs** setting is empty. Each entry may contain `sport`, `leagueId`, and `leagueName`; at least `sport` or `leagueId` is required. The adapter queries each declared scope, combines the events, and removes duplicate event IDs. If both a sport and league placeholder are used, every declared scope needs both values. Up to 50 scopes are accepted. Put specific league routes before broad aggregate routes so a duplicated event keeps its league-specific ID. `leagueName` is also used as fallback metadata and to select the matching league-specific standings route.
- If **League IDs** contains values, those settings take precedence and the adapter queries those scopes instead. For endpoints with both `{sport}` and `{leagueId}`, enter values as `sport/league` (for example `soccer/eng.1`).
- `events.items` selects the array of event records. `id` and `start` are required. Competitors can use the legacy `homeName`/`awayName` selectors or a `competitors` collection mapping with `items`, `name`, optional `id` and `role`, and optional `homeValue`/`awayValue` strings. When role is mapped, its values default to `home` and `away`.
- A collection mapping can traverse nested arrays, and maps each selected object as a competitor. For example, `competitions[0].competitors[*]` selects competitors from the first competition. Use `[0]` for an array index or `[*]` to select every element.
- Add an optional `events.competitors.logo` selector for inline team logos (for example, `team.logo`). Only absolute HTTPS image URLs without credentials or fragments are accepted; missing or invalid logos use the team's initial.
- `sportValue`, `leagueNameValue`, and `leagueIdValue` can provide fixed event metadata when it is identified by the configured endpoint rather than returned on every event. Each fixed value is mutually exclusive with its corresponding selector.
- Selectors are dot-separated object paths such as `data.events`, `home.name`, or `league.id`, with explicit array indexes (`[0]`) and wildcards (`[*]`) such as `competitions[0].competitors[*]`. Use `$.` before a path or `$` only when the entire response is the array. General JSONPath expressions and scripts are not supported.
- `startFormat` and `updatedAtFormat` accept `ISO_OFFSET`, `UNIX_SECONDS`, or `UNIX_MILLISECONDS`. Use the format that matches the API response. `dateFormat` controls `{date}`, `{fromDateFormatted}`, and `{toDateFormatted}` endpoint values; it accepts `ISO_LOCAL_DATE` (the default) or `YYYYMMDD`.
- `scores` is optional. Its endpoint must include exactly one of `{eventId}` or `{eventIds}`. The batch form `{eventIds}` receives a comma-separated list. Event-scoped endpoints can also use `{sport}` and `{leagueId}`; score requests inherit those values from the event's configured scope. `items` selects score records, and `eventId` must map back to the event ID. At least one score or status selector is required. Scores can use legacy `homeName`/`homeScore` and `awayName`/`awayScore` selectors, or a nested `competitors` mapping with `items`, `name`, optional `id`, `score`, `role`, role values, and `kind`.
- Score timestamps are optional. When present, set `updatedAtFormat` to match their representation.
- `statuses` is an optional event status endpoint. It accepts `{eventId}` or `{eventIds}`, plus optional `{sport}` and `{leagueId}` scope values, and maps `eventId`, `status`, and optional clock, period, detail, and update time. `scores.status` or `events.status` also enables the game-status capability.
- `standings` maps grouped tables. Its `groups.items` selects conference/division objects, and `groups.rows` maps team names, optional IDs and ranks, plus optional `statistics` arrays into table columns. Use `leagueIdValue` when the response does not include an ID.
- `team` and `league` map one metadata record each. Their endpoints may use `{teamId}` or `{leagueId}`, plus optional `{sport}` and `{leagueId}` scope values where applicable. `artwork` maps HTTPS artwork URLs for a requested `{entityId}` and `{entityType}`; it can also use the event's `{sport}` and `{leagueId}` scope. Relative URLs and non-HTTPS URLs are rejected.
- `broadcasters` maps a per-event detail response and may use `{eventId}`, `{sport}`, and `{leagueId}`. The same `items`, `name`, `country`, `language`, `type`, or `typeValue` fields may be nested under `events.broadcasters` to map broadcast data already included in the event response.
- `lineups` maps team groups and nested member records from an event detail endpoint. `statistics` maps team groups and their nested statistic rows into typed provider models. Both inherit `{sport}` and `{leagueId}` from the matching event.
- Optional endpoint placeholders include `{sport}` and `{leagueId}` for sport/league-scoped routes, `{eventId}`/`{eventIds}` for event details and status, `{leagueId}`/`{season}` for standings, `{teamId}` for team metadata, and `{entityId}`/`{entityType}` for artwork.

The app bounds request sizes and only follows endpoint paths on the configured HTTPS host. You remain responsible for having the rights to use the chosen API and data in your setup.
