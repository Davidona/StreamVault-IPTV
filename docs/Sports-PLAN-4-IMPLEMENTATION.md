# Sports Plan 4 implementation — Phases 4.1–4.10

Date: 2026-10-17
Branch: `codex/sports-integration`
Worktree: `D:\antigravity-scratch\iptv-player\.worktrees\sports-integration`

This record covers the compliance registry, TheSportsDB completion,
BALLDONTLIE, Highlightly, football-data.org, the gated OddsPapi prototype, and
the optional OpenLigaDB adapter.
Sportmonks was skipped at the user's direction without contacting the provider.
TheSportsDB and Highlightly are marked **Beta** in provider settings because
fixture coverage passed but no live provider accounts were available. This is a
technical verification label; it does not alter either provider’s terms or
release gate. OddsPapi is development-only until provider-use confirmation is
obtained. OpenLigaDB was validated offline only.

## Completed behavior

- Provider review descriptors record terms/docs URLs, review date, commercial
  status, app-store availability, attribution, paid-account requirements, artwork
  rights, and release gate. These records do not create provider rights.
- Registry defaults to production policy. Filtering removes factories, which
  prevents saved configurations from bypassing gates through creation, source
  eligibility, display names, or capability lookup. Duplicate registrations
  remain invalid even when one would be filtered out.
- App DI enables development access only for the exact debug build type. Beta,
  benchmark, nonMinifiedRelease, and release use the production policy. Debug
  still excludes DO_NOT_SHIP, prohibited providers, and uncleared artwork.
- The custom REST transport remains available without a vendor review; this is
  not an approval of user-configured data sources. Its settings form offers a
  loadable mapping example and localized instructions; the schema is documented in
  `sports/providers/custom-json.md`. No conditional, enterprise, or RED provider
  adapters were added.
- TheSportsDB adds standings, event TV, team metadata, and league metadata to its
  existing events/scores/status adapter. No artwork, odds, highlights, or
  provider-supplied playback endpoints are exposed.
- TV enrichment skips usable EPG choices, requests at most twelve unresolved
  events nearest the current time, and respects per-account cooldowns and
  Retry-After. Canonical broadcaster matching outranks event-name fallback;
  playback choices still come exclusively from configured user channels.
- Attribution appears on event cards, featured events, details, manual channel
  selection, standings, and provider configuration. Card heights accommodate
  credits. Standings headers align with their data columns.
- Connection testing checks V2 sports, schedule, live-score, and TV envelopes.
  The shared free key is rejected. Standings additionally require successful V2 authorization for the exact credential before V1 is contacted; a denied V2 key cannot fall through to V1. No event-source preference changes on save.
- Response cache keys now include configuration revision as well as account,
  credential fingerprint, endpoint, and query, so edits cannot reuse older data.
- Schedule mapping retains unknown league IDs and handles the postponed flag.
- Fixed a pre-existing missing SportsProviderConfig import required for the
  Sports Hub repository to compile.
- BALLDONTLIE adds the shared API-key settings flow, account subscription
  introspection, and sport-scoped capability resolution for NBA, NFL, MLB,
  and EPL. Sports endpoints receive the raw key in `Authorization`; the
  account endpoint receives the separate Bearer form.
- BALLDONTLIE maps schedules, participants, status, scores, and supported
  standings. Calls check the account's per-sport subscription and endpoint
  tier before making a restricted request: EPL matches/scores need ALL-STAR,
  NBA standings need GOAT, and NFL/MLB standings need ALL-STAR; EPL standings
  are Free. MLB division rank is derived from the published win-percentage
  fields. Unsupported sports remain out of this initial adapter.
- BALLDONTLIE requests are bounded to 31 calendar dates, three schedule pages,
  and four detail reads per sport. Account, schedule, detail, and standings
  caches use 5-minute, 2-minute, 1-minute, and 30-minute TTLs respectively,
  scoped by credential fingerprint and config revision.
- A fixture-backed NBA event joins its local EPG event while retaining the
  user's playable channel. Provider artwork, broadcasters, odds, highlights,
  and playback URLs are not exposed. See `sports/providers/balldontlie.md` for
  source links and the scope review.

## Documented API limitation

The official V2 documentation and OpenAPI do not list a standings endpoint.
Standings therefore use the documented V1 table endpoint with the user's
Premium key. All other operations use V2 header authentication. The V1 request
uses the dedicated transport without URL logging, redirects, disk cache, or
cookie storage. Exceptions, URLs, and server bodies do not cross the sanitized
provider-result boundary. See `sports/providers/thesportsdb.md` for the review
and source links.

## Offline verification

Commands run with ANDROID_HOME pointing to the installed Android SDK:

```powershell
.\gradlew.bat :domain:test :data:testDebugUnitTest --tests 'com.streamvault.data.sports.*' :feature:live:testDebugUnitTest --tests 'com.streamvault.feature.live.sports.*' :feature:settings:testDebugUnitTest --tests '*SportsProvider*' :app:assembleDebug --no-configuration-cache --console=plain
.\gradlew.bat :app:compileReleaseKotlin --no-configuration-cache --console=plain
```

Both commands passed. The final targeted suite passed 378 tests with zero
failures, errors, or skips: domain 211, data sports 67, live sports 95, settings
sports 5. Tests cover release-gate bypasses, missing review/rights/credit,
blocked artwork, TV identity/deduplication/countries, malformed/empty data,
standing scope and season, authentication/quota/server failures, account edits,
metadata identity, event states, TV fanout/cooldowns, cancellation, channel
matching priority, preserved EPG mappings, and credit provenance.

Localization verification passed 380 values across all 38 advertised locales:
eight new standings labels, new settings attribution, and the reused live
attribution template. Every key is present once, nonempty, and preserves its
formatting contract. Translations are actual localized text.

Evidence:

- `../artifacts/plan4-validation.log`
- `../artifacts/plan4-release-compile.log`
- `../artifacts/plan4-tests.txt`
- `../artifacts/plan4-localization.txt`

The initial build exposed the missing import above and existing configuration
cache incompatibility in verifyFeatureLiveBoundary. Final verification uses
`--no-configuration-cache`; the unrelated Gradle task was not changed.

Phase 4.3 follow-up verification passed:

```powershell
.\gradlew.bat :data:testDebugUnitTest --tests 'com.streamvault.data.sports.balldontlie.*' --no-configuration-cache --console=plain
.\gradlew.bat :feature:live:testDebugUnitTest --tests '*SportsProviderEventResolverTest' --no-configuration-cache --console=plain
.\gradlew.bat :app:assembleDebug :app:compileReleaseKotlin --no-configuration-cache --console=plain
```

The BALLDONTLIE provider fixture suite passed 8 tests, and the API-first-to-EPG
resolver suite passed 6 tests, with zero failures or errors. The last command
verifies app registration in the debug assembly and release Kotlin
compilation. All Phase 4.3 data validation is offline against
documented-response fixtures; no BALLDONTLIE credential or live account was
used.

## Emulator verification

Started the existing `SportsHub_Worktree_20261016` AVD on port 5590 at the user's
request. The default graphics boot stalled before system_server was available.
A cold start using `-no-snapshot -gpu swiftshader` booted successfully without
wiping user data. The other emulator was not changed.

Installed the updated debug APK with `adb -s emulator-5590 install -r` and
launched the app. Existing IPTV channels and app data remain present. Navigated
with the TV remote controls to Settings > Sports > Sports data providers.
The list displays TheSportsDB's seven implemented capabilities and Custom JSON.
TheSportsDB and Highlightly show a Beta notice that live-account validation is
pending. The Custom JSON form offers an Insert example action and localized
setup guidance. Opened TheSportsDB's Add Provider dialog and visually confirmed provider credit,
league scope, masked Premium credential field, and connection-test controls.
Evidence: `../artifacts/plan4-provider-dialog.png`.

**Live provider validation remains pending:** this AVD has no configured Sports
data provider. The user confirmed they do not have a Premium key and explicitly requested
leaving the live provider test pending. TheSportsDB is shown as Beta until a real
Premium-account check can be completed. No credentials were read/exported, no provider was saved with fake or
shared credentials, and no live API success is claimed. The real-device checkbox
remains open until Premium connection, events/scores/standings, credit on real
provider data, and broadcaster-to-local-channel behavior can be observed.

For the Phase 4.3 UI check, the updated debug APK was installed on
`emulator-5590`. Settings > Sports data providers listed BALLDONTLIE, and its
Add Provider form exposed the shared localized league-scope and API-key fields.
The unsaved form was dismissed without credentials; no BALLDONTLIE connection
test or API request was run.

## Phase 4.4 — Highlightly

Implemented the direct All Sports adapter with one key header, 11 sport-prefix
routes, bounded UTC-date match pagination, live score/status mapping, standings,
and basic team/league metadata. Response quota headers and plan-tier envelope
data are tracked per credential fingerprint and displayed in Sports provider
settings when present. The adapter does not call artwork, odds, lineups,
statistics, highlights, video, or playback endpoints. Scope syntax, terms review,
request/cache bounds, and the live-test limitation are documented in
`sports/providers/highlightly.md`.

Fixture tests passed for multi-prefix schedule pagination, score/status data,
football and NBA standings, metadata, quota sharing, invalid scopes, request
bounds, direct authentication, and sanitized rate-limit failures. Localization
verification covers the two quota labels in all 38 advertised locales. Highlightly
is shown as Beta until live-account testing is possible; no account key is available.

For any live playback stuckness investigation, follow the repository's full
2-second screenshot cadence and media-session/log checks. No live playback bug
was reported or claimed fixed in this change.

## Phase 4.5 — Sportmonks

Skipped per the user's instruction on 2026-10-17. No contact was made with
Sportmonks. Their public documentation recommends middleware to protect API
tokens but does not answer the Plan 4 direct native-BYOK question. The release
gate is therefore uncleared; no Sportmonks adapter is present or enabled.

## Phase 4.6 — football-data.org review

Official terms and API documentation were reviewed on 2026-10-17. The
registration terms explicitly allow the API key to be used by one application
in web and/or mobile form on any platform, require the key owner to keep their
credentials confidential, and require attribution. StreamVault uses each
user's own key in the one StreamVault Android application, stores it in the
existing encrypted no-backup provider store, and sends it only in the
`X-Auth-Token` request header. No shared key, cloud sync, URL token, or artwork
is used. This supports the single-application/BYOK design. The adapter follows
the most restrictive published request tier (10 requests/minute) and clears
that credential's response cache after a 401/403 so data from a lost
subscription is not reused.

Official sources:

- https://www.football-data.org/client/register
- https://www.football-data.org/documentation/faq
- https://www.football-data.org/pricing
- https://docs.football-data.org/general/v4/index.html
- https://docs.football-data.org/general/v4/lookup_tables.html

The adapter uses the shared token-entry/connection-test form, maps fixtures,
score/status, and total/group standings, and displays the provider's required
attribution on event and standings surfaces. Fixture requests are bounded to
14 days and 500 matches, score/status requests to 50 match IDs, and local
traffic to 10 requests/minute. Schedule, score, and standings responses use
short bounded in-memory caches keyed by configuration revision and credential
fingerprint; a 401/403 clears that credential's cached results. Logos and
artwork are not requested. Details and offline verification are recorded in
`sports/providers/football-data.md`.

The first fixture review found that distinct time ranges on one calendar date
could share the same filtered-event cache key. The key now includes the exact
request instants, and a same-day, two-window fixture case covers the regression.

## Phase 4.7 — OddsPapi prototype

Official public terms and API docs were reviewed on 2026-10-17. The adapter
uses the documented `apiKey` query parameter; the shared transport does not log
request URLs and returns only typed errors without response bodies. It maps
bounded fixtures, participants, state, and period scores. It observes the
documented fixture/detail and score endpoint cooldowns, caches by credential
fingerprint/config revision, clears that credential's cache on 401/403, and
does not request or expose odds-related products.

OddsPapi's terms say API keys are personal and prohibit standalone data resale,
but direct paid-app display is not explicitly covered. No contact was made.
The adapter is marked `CONDITIONAL` with `REQUIRES_VENDOR_CONFIRMATION`, so the
production registry excludes it. The debug policy enables the prototype for
offline development only. See `sports/providers/oddspapi.md` and the live
provider-use gate in the main plan.

Offline OddsPapi fixture suite passed five tests, covering the production gate,
query-key placement and sanitized errors, fixture mapping/range filtering,
score aggregation and cached state, and the strict unscoped date-window limit.
No live OddsPapi account or credential was used.

## Phases 4.8–4.10 — remaining provider gates

On 2026-10-17, API-SPORTS and SportDB.dev public terms were reviewed, and the
official SportDevs terms URL was retried but returned HTTP 502. API-SPORTS'
terms withhold publication permission and assign third-party sports rights to
the user; its gate remains `DO_NOT_SHIP`. SportDB.dev requires a paid plan for
production and leaves paid-app data display unclear; its gate remains
`REQUIRES_VENDOR_CONFIRMATION`. SportDevs remains `DO_NOT_SHIP` pending a
successful terms review. No messages were sent and no adapters were added for
these three conditional services.

SportsDataIO and Sportradar are deferred because there is no customer
request or property-specific agreement in this task.

OpenLigaDB's official site states that API data are under ODbL 1.0. The review
recorded the Produced Work attribution requirement, derivative-database
share-alike conditions, 60-requests/minute-per-IP API limit, and separate logo
rights. The optional adapter now shows linked source/license credits and keeps
only bounded, short-lived raw JSON in process memory. It does not persist a
normalized or merged database and ignores team-logo URLs. Offline fixtures
cover connection probe, event window/status, final score selection, standings,
scope limits, release gating, and raw-cache bounds. No live account was used.
See `sports/providers/conditional-and-open-data-review-2026-10-17.md` and
`sports/providers/openligadb.md`.

## Verification added in this continuation

- OpenLigaDB: six offline fixture tests passed for the release gate, connection
  probe, season event mapping/date filter, score/status cache reuse, standings,
  scope/range bounds, and raw response-cache limits.
- OddsPapi: five offline fixture tests passed, including the quota-free
  `/account` connection probe.
- Full unit suites passed for `data` (1,455), `domain` (211), and `feature:live`
  (209). Debug compilation of `data`, `feature:live`, and `feature:settings`,
  plus production `app:compileReleaseKotlin`, succeeded.
- `feature:settings:testDebugUnitTest` ran 81 tests: 80 passed and the existing
  `SettingsLocalizationCoverageTest` failed for `values-ar/settings_redesign.xml`
  (67 expected keys missing and eight unexpected plural entries). That
  navigation/redesign resource set was not changed here. The Plan 4 provider
  keys were separately checked in all 38 advertised locales with matching
  positional placeholders.
