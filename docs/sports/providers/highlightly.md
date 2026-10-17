# Highlightly provider

The Highlightly adapter uses the direct All Sports API for event schedules,
scores, status, standings, and basic team/league metadata. It does not fetch
artwork, lineups, statistics, odds, highlights, video, or playback URLs.

## Account and configuration

- API base: `https://sports.highlightly.net`
- Authentication: `x-rapidapi-key` with the user's direct Highlightly key.
- A RapidAPI host header is not sent.
- Provider settings reuse the shared credential form; no key is hard-coded or
  included in diagnostics.
- League scopes use `prefix:leagueId`, such as `football:39` or `nba:NBA`.
  Empty scope means all documented sport prefixes. IDs returned by the adapter
  are namespaced (for example `football:501`) to avoid collisions across APIs.
- The API's request limit is shared across sport prefixes. Cache keys include
  provider instance, configuration revision, and a SHA-256 credential
  fingerprint; raw credentials are not used as cache keys.

The adapter currently routes the documented prefixes `football`,
`american-football`, `nba`, `baseball`, `nhl`, `cricket`, `basketball`, `hockey`,
`rugby`, `volleyball`, and `handball`. Numeric league IDs use the provider's
ID filters. The `american-football`, `nba`, `baseball`, and `nhl` routes use
their league-name filters for schedules/standings; league metadata is enabled
only on routes where numeric league lookup is supported.

## Bounds and cache policy

- Match schedules are fetched one UTC date at a time with `limit=100` and
  `offset`; at most three pages per date, 14 dates, 120 HTTP requests, and 2,000
  mapped events are accepted per call.
- Scores/status accept at most 30 event IDs.
- Events cache for 2 minutes, individual match snapshots for 1 minute,
  standings for 30 minutes, and metadata for 24 hours.
- Plan tier is read from the response envelope. Remaining-request and request
  limit values are read from numeric rate-limit headers. The latest snapshot is
  shared across provider instances for the same credential in the app process
  and shown in provider settings when present.

## Terms and rights review

The [Highlightly terms](https://highlightly.net/terms/) permit storing and using
API data in applications/products while prohibiting API resale, sublicensing,
proxy/pass-through access, and systematic extraction to create a competing
service. The adapter does not expose images or logos because visual-asset rights
remain the user's responsibility. See the [All Sports API documentation](https://highlightly.net/sport-api/documentation/)
for supported products and endpoints, [authentication](https://highlightly.net/sport-api/documentation/getting-started/authentication/)
for the direct key header, and the [pagination guide](https://highlightly.net/football-api/documentation/getting-started/pagination/)
for page and quota metadata.

## Verification

Offline MockWebServer fixtures cover direct authentication, football and cricket
events, offset pagination, live score/status mapping, football and NBA standings,
team/league metadata, shared quota observations, invalid scopes, bounds, and
sanitized 429 handling. No live Highlightly account was available for this pass, so
the adapter is marked **Beta** in provider settings until the in-app connection
and real-data smoke test can be completed with a user key. This label is separate
from its terms and release gate.
