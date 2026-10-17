# football-data.org provider

The football-data.org adapter supplies football fixtures, scores/status, and
competition standings. It does not request team artwork, logos, broadcasters,
odds, or playback data.

## Account and credential handling

- API base: `https://api.football-data.org/v4`
- Authentication: the user's API token is sent only in the `X-Auth-Token`
  request header.
- The shared provider settings form stores credentials in the encrypted,
  no-backup provider store. StreamVault does not provide a shared token or sync
  this credential.
- The registration terms restrict a key to one application. The integration
  uses that customer's key only in the StreamVault Android application.
- Team logos and profile images are excluded because the provider's terms say
  their rights belong to their respective owners.

## Scope and usage

- Empty competition scope requests all competitions available to the user's
  plan. A configured scope accepts football-data.org competition codes such as
  `PL` and `CL`.
- Fixtures are limited to a 14-day request window and 500 returned matches.
  Score and status refreshes batch at most 50 event IDs per request.
- The adapter locally caps requests at the published Free-tier rate of 10 per
  minute. It also reads `X-RequestsAvailable` and
  `X-RequestCounter-Reset` when returned by the API.
- Schedule responses cache for 2 minutes, score/status snapshots for 30
  seconds, and standings for 30 minutes. Cache entries are scoped to the
  provider instance, configuration revision, and credential fingerprint.
- A 401 or 403 clears all cached data associated with that credential.
- Free-tier scores and schedules are delayed; real-time scores require an
  applicable account plan. Standings are available on the Free tier.

## Attribution and terms review

The app displays the required credit on football-data.org event and standings
surfaces using the provider's specified wording:

> Football data provided by the Football-Data.org API

The adapter does not display provider logos or profile photos. Terms and
documentation were reviewed on 2026-10-17; this records a review of published
terms, not a separate license or provider endorsement.

- [Registration terms](https://www.football-data.org/client/register)
- [FAQ and attribution guidance](https://www.football-data.org/documentation/faq)
- [Plan features and request limits](https://www.football-data.org/pricing)
- [API v4 documentation](https://docs.football-data.org/general/v4/index.html)
- [API lookup tables](https://docs.football-data.org/general/v4/lookup_tables.html)

## Verification

Offline MockWebServer fixtures cover credential-header placement, empty
connection-probe responses, event/time-window filtering, competition scope,
scores and statuses, standings, quota metadata and local request limits,
credential-scoped cache invalidation, and sanitized authentication failures.
No live football-data.org account or provider token was used.
