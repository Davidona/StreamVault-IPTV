# TheSportsDB provider review

Reviewed 2026-10-17 for Sports Plan 4, Phases 4.1–4.2.

## Official sources

- Documentation: <https://www.thesportsdb.com/documentation>
- V2 OpenAPI: <https://api.thesportsdb.com/api/spec/v2/openapi.yaml>
- Terms: <https://www.thesportsdb.com/docs_terms_of_use.php>

## Technical facts

V2 requires a Premium key in `X-API-KEY`. The documented base is
`https://www.thesportsdb.com/api/v2/json`. Events are scoped to league IDs and
seasons. Schedule timestamps without offsets are interpreted as UTC, consistent
with the provider site's timezone indicator.

The published V2 documentation and OpenAPI list `/lookup/event_tv/{idEvent}`,
`/lookup/team/{idTeam}`, and `/lookup/league/{idLeague}`. TV records identify
channels and countries but do not establish whether a station is linear TV,
streaming, or radio. The adapter preserves UNKNOWN rather than guessing.

**Standings exception:** neither published V2 reference documents a table endpoint.
Standings use the documented V1 `lookuptable.php?l=...&s=...` endpoint with the
same user-owned Premium key. This is the only request that places the key in a
URL path. It uses the dedicated transport with no URL logging, redirects, disk
cache, or cookie storage. Response bodies and exception messages never escape
as diagnostics. No shared free key is used. Tables are limited to the provider's
featured football leagues; a successful empty table is valid.

## Terms and licensing findings

The terms page reviewed is dated 2026-09-17. Published app-store use requires a
paid subscription; paid data use requires identifying TheSportsDB as the source
and staying within rate limits. API resale requires separate permission.

Artwork has separate licensing and third-party rights requirements. A returned
URL or Creative Commons status flag does not establish image-specific rights.
Provider images, logos, highlights, and video are excluded from this adapter.

## StreamVault implementation

Capabilities: EVENTS, LIVE_SCORES, GAME_STATUS, STANDINGS, BROADCASTERS,
TEAM_METADATA, LEAGUE_METADATA. The descriptor records source URLs, review date,
paid-account requirement, `REQUIRES_ATTRIBUTION`, permitted factual-data use,
app-store availability conditional on paid access, and disabled artwork.

The production registry only admits this descriptor when the app declares its
attribution surfaces implemented. All other adapters require their own review.
Debug permits development adapters, but DO_NOT_SHIP and prohibited providers
remain excluded. The custom REST transport remains an advanced user-configured
option and does not imply approval of any vendor configured through it.

Connection testing probes sports, a league schedule, live scores, and event TV
lookup using V2. Success requires all four envelopes to be valid. A provider
403 remains a sanitized authentication failure; it is not proof of a particular
subscription tier. The shared free key `123` is explicitly rejected. Before a V1 standings request, the exact key must also pass a V2 sports probe; successful Premium authorization is cached for fifteen minutes. Saving a key alone cannot enable the V1 path.

TV enrichment requests at most twelve unresolved events nearest the current
time. It skips usable EPG mappings, respects account-specific failure cooldowns
and Retry-After, and never spends another account's quota. Canonical broadcaster
matches rank ahead of event-name fallback matches. Only existing playable user
channels become playback choices; TV records cannot provide playback URLs.
Explicitly typed radio sources are ignored by matching. TheSportsDB itself does
not provide enough information to classify radio or streaming stations.

Visible provider credit uses localized resources on featured cards, ordinary
cards, event details, manual channel selection, loaded standings, and the
provider configuration dialog. Credit is derived from displayed data provenance,
including score-only enrichment, rather than simply from configured accounts.
Local-only events retain their existing layout. Standings labels are localized
in every advertised locale.

Process-local bounded caches: seasons/teams/leagues 24 hours, schedules 15
minutes, live scores 2 minutes, TV 6 hours, standings 30 minutes. Cache identities
include account, credential fingerprint, configuration revision, endpoint, and
query. No raw credentials are retained in cache keys. Existing routers control
metadata/standings stale display and eligibility after removal or disabling.

Fixtures are synthetic examples based on the documented field shapes. Tests use
MockWebServer and require no live provider account.

## Verification status

The adapter is marked **Beta** in provider settings because its fixture suite is
offline-only and no Premium account was available for a live connection and data
check. This technical status is separate from the published commercial terms,
paid-account requirement, and attribution release gate described above.

## Open questions and release checks

- Complete the Premium-account emulator smoke check recorded in
  `docs/Sports-PLAN-4-IMPLEMENTATION.md` before claiming live verification.
- Re-check terms before a significant release and record changes here.
- Verify image-specific rights separately before adding artwork capability.
- Prefer a documented V2 standings endpoint if the provider introduces one.
- Do not infer paid-account status from a locally saved credential alone; V2
  authorization is checked by the connection probe and real requests.
