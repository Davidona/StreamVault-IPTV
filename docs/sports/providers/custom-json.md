# Custom REST/JSON sports provider

Use this advanced option when you have an HTTPS sports API that is not covered by a built-in provider. It makes ordinary GET requests and maps the API's JSON response into StreamVault events and optional live scores. It does not run scripts or provide standings, artwork, or playback links.

In Settings > Sports data providers, choose **Custom JSON provider**. Enter the API's HTTPS base URL, select **Insert example**, then edit the endpoint paths and field selectors to match the API documentation. Put the API key or token in the credential field, choose **Test**, and save only after the test succeeds. The inserted configuration is a template, not a working account.

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

The API base URL is entered separately. Endpoint paths must begin with `/` and stay on that HTTPS host. The sample assumes fixture rows like `data.events[0]` and score rows like `data.scores[0]`; replace those assumptions with the exact response structure documented by your API.

## Configuration fields

- `name` is the label shown in provider settings.
- `auth.mode` is `none`, `api_key_header`, `api_key_query`, `bearer`, or `basic`. Header/query key modes require `parameterName`. Enter the key or bearer token in the secret field; Basic auth uses the username and password fields. Credentials are stored separately from the mapping.
- `events.endpoint` is a relative path. It may use `{date}`, `{fromDate}`, `{toDate}`, `{leagueId}`, and `{sport}`. These values come from the sports request; the date bounds are UTC.
- `events.items` selects the array of event records. `id`, `start`, `homeName`, and `awayName` are required selectors. Other event selectors are optional.
- Selectors are dot-separated object paths such as `data.events`, `home.name`, or `league.id`. Array indexes, wildcards, and executable expressions are not supported. Use `$.` before a path or `$` only when the entire response is the array.
- `startFormat` and `updatedAtFormat` accept `ISO_OFFSET`, `UNIX_SECONDS`, or `UNIX_MILLISECONDS`. Use the format that matches the API response.
- `scores` is optional. Its endpoint must include exactly one of `{eventId}` or `{eventIds}`. The batch form `{eventIds}` receives a comma-separated list. `items` selects score records, and `eventId` must map back to the event ID. At least one score or status selector is required. Each score selector requires its corresponding `homeName` or `awayName` selector.
- Score timestamps are optional. When present, set `updatedAtFormat` to match their representation.

The app bounds request sizes and only follows endpoint paths on the configured HTTPS host. You remain responsible for having the rights to use the chosen API and data in your setup.
