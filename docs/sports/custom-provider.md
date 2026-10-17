# Custom JSON sports provider

The Custom JSON provider connects one HTTPS JSON API account to sports events and, optionally, live scores. Its mapping is declarative: the app selects fields from JSON and never runs scripts or follows provider-supplied URLs.

## Setup

In Settings, add **Custom JSON provider**, enter the API base URL, then paste a definition like this into **Configuration (JSON)**:

```json
{
  "name": "My League API",
  "auth": {
    "mode": "api_key_header",
    "parameterName": "X-API-Key"
  },
  "events": {
    "endpoint": "/events?from={fromDate}&to={toDate}&league={leagueId}",
    "items": "data.events",
    "id": "id",
    "sport": "sport.name",
    "leagueName": "league.name",
    "leagueId": "league.id",
    "title": "name",
    "start": "start_time",
    "startFormat": "ISO_OFFSET",
    "homeName": "home.name",
    "homeId": "home.id",
    "awayName": "away.name",
    "awayId": "away.id",
    "competitorKind": "TEAM",
    "status": "status",
    "clock": "clock",
    "period": "period"
  },
  "scores": {
    "endpoint": "/scores?ids={eventIds}",
    "items": "data.scores",
    "eventId": "event_id",
    "homeName": "home.name",
    "homeScore": "home.score",
    "awayName": "away.name",
    "awayScore": "away.score",
    "status": "status",
    "clock": "clock",
    "period": "period",
    "updatedAt": "updated_at",
    "updatedAtFormat": "ISO_OFFSET"
  }
}
```

The event mapping is required. The score mapping is optional; its presence enables the Live scores capability. The `name` is shown in Settings so separately configured custom APIs are easy to tell apart.

After entering the definition and any required credentials, use **Test** to call the configured events endpoint for a 24-hour UTC window and validate the response mapping. Then save the account.

## Authentication

`auth` can be omitted for `none`, or set to one of these modes:

| Mode | Additional field | Credential fields |
| --- | --- | --- |
| `none` | — | none |
| `api_key_header` | `parameterName` | Secret is sent in the named header |
| `api_key_query` | `parameterName` | Secret is sent as a query parameter |
| `bearer` | — | Secret is sent as a Bearer token |
| `basic` | — | Username and secret are sent with HTTP Basic authentication |

Credentials are stored separately from the definition in the encrypted provider store. Query-key mode necessarily places the secret in the request URL, so use it only when the API requires that method. OAuth and arbitrary custom headers are not supported.

## Endpoints and selectors

Endpoint templates must start with `/`. They are appended to the base URL path, so base URL `https://example.com/api` and endpoint `/events` request `https://example.com/api/events`. The final request must remain HTTPS on the same host and port. Placeholder values are percent-encoded. Event endpoints accept `{date}`, `{fromDate}`, `{toDate}`, `{leagueId}`, and `{sport}`. `{date}` is the UTC date at the start of the requested window. Score endpoints must use exactly one of `{eventId}` or `{eventIds}`; `{eventIds}` sends a comma-separated, sorted ID list, with at most 50 requested IDs.

`items` selects the array of records, or one object record, from the response. It may use `$` for the whole response. Other selectors are dot-separated object keys such as `data.events` or `home.name`; an optional `$.` prefix is accepted. Array indexes, wildcards, general JSONPath expressions, and scripts are not supported.

Every event needs an ID, start timestamp, and at least one competitor name. Event timestamps require `startFormat`; score `updatedAtFormat` defaults to `ISO_OFFSET` when `updatedAt` is mapped. Supported formats are:

- `ISO_OFFSET`: ISO-8601 timestamp containing its UTC offset, such as `2026-10-17T19:30:00Z`.
- `UNIX_SECONDS`: Unix epoch seconds.
- `UNIX_MILLISECONDS`: Unix epoch milliseconds.

Competitor kinds are `TEAM`, `PERSON`, and `PLAYER`. Common status values such as `scheduled`, `in_progress`, `halftime`, `postponed`, `cancelled`, and `finished` map to provider-neutral game states; unrecognized values are retained as status detail.

## Limits and current scope

Definitions are limited to 32 KiB, endpoint templates to 512 characters, selectors to 256 characters and 16 path segments, and mapped scalar values to 512 characters. HTTP responses are capped at 2 MiB by the shared provider client. Event feeds are capped at 2,000 records and score feeds at 1,000 records.

This version supports events and live scores only. It does not follow pagination links, execute scripts, support OAuth, or map generic standings tables. Use a reviewed built-in adapter when an API needs pagination, richer authentication, metadata, or provider-specific behavior.
