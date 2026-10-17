# OddsPapi provider prototype

The adapter prototype reads fixtures, fixture state, and period scores only. It
does not call odds, markets, bookmaker, settlement, historical-odds, or betting
endpoints. It does not expose the fixture's `hasOdds` field.

## Account and requests

- API base: `https://api.oddspapi.io/v4`
- Authentication: the user's key is passed in the documented `apiKey` query
  parameter. The shared encrypted, no-backup provider store holds the key; the
  transport has no URL logger, does not include URLs in typed failures, and
  does not propagate response bodies or exception messages.
- Fixture requests are limited to 500 records. Unscoped requests are less than
  48 hours; sport-filtered requests are bounded to 9 days; a configured numeric
  tournament ID allows up to 14 days. At most five tournament IDs can be
  configured to bound fan-out and quota use.
- Fixture, fixture-detail, and score responses cache in memory for 2 minutes,
  5 minutes, and 15 seconds. Cache keys include account instance, config
  revision, and key fingerprint. A 401/403 clears the key's cached values.
- Connection testing uses the documented account endpoint, which does not consume the metered request quota. The adapter observes the documented endpoint cooldowns: 2 seconds for
  fixtures/fixture detail, and 1 second for sports lookup and scores. Score
  requests are bounded to 10 fixture IDs per call.
- Period score values are summed for the two participants; status comes from
  the fixture endpoint.

## Terms and release gate

The [published terms](https://oddspapi.io/en/legal/terms) say the API key is
personal and tied to the subscriber's plan, prohibit standalone resale or
redistribution, and ask users with uncertain use cases to contact OddsPapi.
Those terms do not specifically confirm a user-key integration inside a paid
Android media-player app. The Plan 4 provider-use gate remains unresolved. No
vendor inquiry was sent. `REQUIRES_VENDOR_CONFIRMATION` and
`CONDITIONAL` keep this prototype out of production provider registries; the
debug policy may include it for offline development only.

- [Terms](https://oddspapi.io/en/legal/terms)
- [API documentation and authentication](https://oddspapi.io/en/docs)
- [Fixture endpoint](https://oddspapi.io/en/docs/get-fixtures)
- [Score endpoint](https://oddspapi.io/en/docs/get-scores)
- [Sport lookup](https://oddspapi.io/en/docs/get-sports)
- [Request quota and endpoint cooldowns](https://oddspapi.io/en/docs/requests-and-quota)

## Verification

Offline MockWebServer fixtures cover the production release gate, query-key
placement, sanitized authentication failures, sport lookup, fixture/time-window
mapping, participants and states, period score aggregation, fixture-cache use,
and the unscoped range bound. The tests do not contact OddsPapi; no live
account/key was used. The provider-use confirmation remains required before
the adapter can be released.
