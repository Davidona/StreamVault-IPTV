# BALLDONTLIE provider

Review date: 2026-10-17

## Scope and release review

The built-in adapter currently supports events, live scores, game status, and
standings for NBA, NFL, MLB, and the English Premier League (EPL). BALLDONTLIE
publishes additional sports APIs, but those are not included until their routes
and response models have dedicated adapter coverage. The adapter does not
expose logos or other artwork, odds, broadcasters, highlights, or provider
playback URLs. Live playback choices continue to come from the user's local
channel and EPG data.

The reviewed [terms](https://www.balldontlie.io/terms.html) permit lawful
personal and commercial use subject to their restrictions, including not
competing with BALLDONTLIE and not reselling unmodified raw data or API access.
The terms do not require attribution by default. They also note that provider
terms do not grant rights to third-party names, logos, or other intellectual
property, so provider artwork stays disabled. Re-review the terms before a
material expansion or release.

## Account and authentication

The existing localized provider settings form collects the user's API key and
optional event scope. For this adapter, scope values are the provider sport
codes `nba`, `nfl`, `mlb`, and `epl`; leaving the field empty selects the
account's active event-enabled sports. The key is stored in the encrypted
provider credential store. The account API uses `Authorization: Bearer <key>` at
`/account/v1/subscriptions`; the four sports APIs use the raw key in
`Authorization: <key>`. The connection test first reads subscriptions, then
probes one selected sports endpoint. It rejects missing/inactive configured
sports and relies on the shared sanitized HTTP client for authentication,
quota, network, and server errors.

Subscriptions are independent by sport and plan. The adapter checks the
account-reported sport and tier before data requests. NBA, NFL, and MLB games
are available on Free; EPL matches require ALL-STAR/Paid or above. EPL
standings are available on Free, NFL and MLB standings require ALL-STAR/Paid,
and NBA standings require GOAT/Paid Plus or an equivalent higher tier. EPL
score and status refreshes use the paid matches detail endpoint, so they use
the same ALL-STAR gate. A denied request returns an authentication failure
without calling the restricted endpoint.

## Routes and mapping

| Scope | Event list/detail | Standings | Event tier | Standings tier |
|---|---|---|---|---|
| NBA | `/v1/games` | `/v1/standings` | Free | GOAT/Paid Plus |
| NFL | `/nfl/v1/games` | `/nfl/v1/standings` | Free | ALL-STAR/Paid |
| MLB | `/mlb/v1/games` | `/mlb/v1/standings` | Free | ALL-STAR/Paid |
| EPL | `/epl/v2/matches` | `/epl/v2/standings` | ALL-STAR/Paid | Free |

Schedules map event IDs, league/sport, participants and roles, event time,
scores, and status. EPL match names use the documented away-at-home form when
the response omits a participant object. IDs are namespaced by sport, such as
`nba:15907925`, so an NBA ID cannot collide with another API's ID. Detail
requests supply score and status refreshes and share a one-minute cache.

Standings are grouped by the provider's conference, division, or competition.
The published MLB standings shape has no regular-season rank, so the adapter
derives division rank by descending win percentage and uses team name for ties.
The other supported tables use their provider rank fields. UI labels describe
the mapped factual statistics; no betting fields are requested or displayed.

## Request bounds and cache

- Schedule ranges cover at most 31 calendar dates, with up to three pages of
  100 items per sport. A response that still advertises another page after
  that bound is rejected as too large.
- One score/status call fetches at most four event details per sport.
- Subscription profiles cache for 5 minutes, schedules for 2 minutes, event
  details for 1 minute, and standings for 30 minutes. The process-local cache
  is bounded to 192 entries.
- Cache keys include a SHA-256 credential fingerprint and configuration
  revision; raw credentials are not placed in cache keys or logs.
- The shared HTTP client handles `Retry-After` and rate-limit responses. The
  adapter bounds pagination and detail fanout rather than polling continuously.

## Verification

Offline fixtures cover subscription introspection and separate account/sport
authentication headers, NBA schedule/score/status mapping, EPL standings,
NBA standings entitlement gating, MLB rank derivation, and unsupported scope.
The live-sports resolver fixture joins a BALLDONTLIE NBA event to its matching
EPG event while preserving the configured local channel and stream URL.

No live API request or credential-based validation is claimed. The
TheSportsDB Premium-account smoke test from Phase 4.2 also remains deferred at
the user's request.

## Official references

- [BALLDONTLIE terms](https://www.balldontlie.io/terms.html)
- [BALLDONTLIE API documentation](https://www.balldontlie.io/docs/)
- [NBA API documentation](https://docs.balldontlie.io/)
- [Account API and subscriptions](https://www.balldontlie.io/account/)
- [NFL API documentation](https://nfl.balldontlie.io/)
- [MLB API documentation](https://mlb.balldontlie.io/)
- [EPL API documentation](https://epl.balldontlie.io/)
