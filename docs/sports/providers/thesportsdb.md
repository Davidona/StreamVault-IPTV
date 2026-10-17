# TheSportsDB provider review

Reviewed 2026-10-17 for the Plan 3.5 reference-adapter scope. This review records
technical behavior separately from provider terms; it is not a release approval.

## Official sources

- API documentation: <https://www.thesportsdb.com/documentation>
- V2 OpenAPI specification: <https://api.thesportsdb.com/api/spec/v2/openapi.yaml>
- Terms of use: <https://www.thesportsdb.com/docs_terms_of_use.php>

## Technical facts

- V2 is available to Premium subscribers and authenticates with `X-API-KEY`.
- The V2 base URL is `https://www.thesportsdb.com/api/v2/json`.
- The adapter checks `/all/sports`, enumerates `/list/seasons/{idLeague}`, reads
  `/schedule/league/{idLeague}/{season}`, and fetches `/livescore/all`.
- Schedule lookup requires a league ID and season. There is no global schedule
  query in this adapter; source selection must provide configured league IDs.
- The published Premium rate limit is 100 requests per minute. The adapter
  caches seasons for 24 hours, schedules for 15 minutes, and live scores for two
  minutes, and relies on the shared transport to categorize HTTP 429 responses.
- Schedule examples include timestamps without offsets. The adapter interprets
  these as UTC, consistent with the provider site's UTC timezone indicator;
  this assumption should be rechecked if the provider changes its format.

## Terms and rights

The current terms page is dated 2026-09-17. It says published app-store apps
require a paid API subscription, paid API use must mention TheSportsDB as the
data source and stay within rate limits, and API access cannot be resold without
specific permission. StreamVault therefore uses a user-supplied Premium key and
does not fall back to the shared free key.

Artwork rights are separate from API access. The terms require checking the
specific image licence and third-party rights; `strCreativeCommons` is a status
flag rather than the licence itself. This adapter does not request or expose
artwork, team logos, video highlights, or broadcaster data. Attribution must be
shown with future Sports Hub data presentation before that presentation is
released.

## Plan 3.5 implementation

The adapter currently declares `EVENTS`, `LIVE_SCORES`, and `GAME_STATUS`. It
maps event identity, league, teams, UTC kickoff, scores, status, progress, and
update time into provider-neutral DTOs. Fixtures and MockWebServer contract tests
cover valid, empty, malformed, mixed, missing-field, and authentication-error
responses. No live provider account is used in tests.

## Open work

- Validate league selection and event-source routing in Plan 3.6.
- Add user-facing attribution beside provider data in the Sports Hub before
  enabling data presentation.
- Complete the Plan 4 release/compliance gate before treating the adapter as
  approved for a production app-store release.
- Keep artwork disabled until image-by-image rights and attribution are handled.
