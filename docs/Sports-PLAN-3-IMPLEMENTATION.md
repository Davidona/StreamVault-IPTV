# Sports Plan 3 implementation

## Phase 3.1 — Provider core (2026-10-17)

The provider core is separate from the existing feature/live Sports implementation.
The local Sports repository, canonical event identity, channel matching, playback,
and settings have not been changed by this phase. No external adapter is registered
and no provider requests run when Sports opens.

### Boundaries

- `domain/.../sports/provider` owns capabilities, authentication declarations,
  provider descriptors, account configurations, registry/factory contracts,
  health, typed results, and the persistence contract.
- `data/.../sports/provider` owns encrypted persistence and HTTP transport.
- At this phase the factory set was empty. The registry, singleton encrypted
  store, and dedicated client use the existing app user-agent identity. The
  TheSportsDB reference adapter is registered with `@IntoSet` in Phase 3.5.
- Sports data capabilities contain no stream URL or playback capability.
- Provider labels and error presentation remain for the settings phase. There
  are no new user-facing strings in this phase; internal enum names and exception
  markers must be mapped to localized resources before being displayed.

### Configuration and credentials

Provider type IDs identify adapters; instance IDs identify individual accounts.
Adapter configuration versions are independent of the storage-envelope version.
Factories can migrate old configuration versions while preserving account and
type identity. Future versions, unsupported migrations, duplicate adapter types,
and prohibited endpoint overrides fail validation.

The store writes one encrypted envelope atomically using the existing
`CredentialCrypto` implementation. It lives at
`Context.noBackupFilesDir/sports_providers.enc`, outside Room, DataStore,
preferences export, and backup. The entire envelope is encrypted, including
endpoint metadata and usernames. Failed encryption does not replace the prior
file; malformed/future envelopes are errors rather than an empty account list.
No plaintext write fallback is allowed. Reads and writes are bounded to 256 KiB
of encrypted data, with a 128 KiB plaintext write limit.

Configuration updates preserve the existing secret when no replacement is
provided. Credential reads are explicit and separate from configuration reads.
Removing an instance deletes its credentials as well. Credentials have a redacted
string representation; storage errors carry no raw cause or server text.

Base URLs currently require HTTPS and prohibit inline credentials, query strings,
and fragments. Authentication declares key placement independently of endpoint
configuration. OAuth2 is an adapter declaration and bearer transport only at this
stage; authorization, refresh, PKCE/device-code flows are not implemented.

### HTTP behavior

The dedicated OkHttp client has no shared request logger, disk cache, cookies, or
automatic redirects. It supports no authentication, header/query API keys,
Bearer, Basic, and adapter-managed OAuth access tokens. HTTPS is the default;
the transport has an explicit cleartext opt-in for future self-hosted adapters,
but configuration validation currently remains HTTPS-only.

Connect/read/overall timeouts are 15/20/30 seconds. Responses are bounded to 2 MiB
by default (including decompressed/chunked payloads), configurable up to 8 MiB.
Errors return categories and optional status/retry time, without URLs, response
bodies, or exception messages. `Retry-After` supports delay seconds and HTTP dates.
Cancellation cancels the underlying call and parser cancellation propagates.

There are no automatic retries or polling in this phase. Adapters/capability
repositories must use the typed failures and retry time to implement bounded
backoff, quota-aware caching, stale-data policy, and refresh scheduling later.
No real provider has been contacted.

### Verification

Focused tests cover deterministic capability filtering, multiple accounts,
configuration migration, endpoint validation, health recovery, credential
persistence/rotation/removal, failed writes, corrupt/future storage, auth
injection, redirect isolation, error sanitization, rate limiting, cancellation,
and fixed/chunked/gzip response limits. Storage tests use fake crypto with
Robolectric; they test the persistence boundary, not Android hardware Keystore.

The focused suites passed: 27 tests, zero failures/errors (10 domain, 6 storage,
11 HTTP). Commands:

```text
gradlew.bat :domain:test --tests "com.streamvault.domain.sports.provider.*"
gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.sports.provider.*"
```

An initial storage test detected coroutine debug stack recovery attaching a
cause to the sanitized exception marker. The marker now explicitly initializes
its null cause; the full focused suite passed on the rerun.

`:app:assembleDebug` also passed, including app-level Hilt dependency generation
and compilation. `git diff --check` passed. No emulator install or playback
validation was performed: this phase adds no screen behavior or playback changes.

### Phase 3.1 handoff

Phase 3.2, recorded below, adds capability-specific interfaces and provider-neutral
DTOs before event resolution or UI integration. Reuse the Plan 2 identity and normalization
boundaries when Phase 3.3 starts. Keep the outstanding Plan 2 device checks and
the documented WSL stream failures visible; this foundation does not resolve or
reclassify those findings.

## Phase 3.2 — Capability interfaces (2026-10-17)

All ten declared capabilities now have independent interfaces in the domain
module: events, live scores, game status, standings, broadcasters, team metadata,
league metadata, artwork, lineups, and statistics. Adapters implement only the
interfaces they support. There are no mandatory unsupported-operation methods.

The registry checks every created adapter's declared capabilities against its
implemented interfaces. A descriptor can advertise a subset of the implemented
interfaces (for example an account tier), but implementing an interface alone
does not enable a capability for routing. Configuration priority and eligibility
still use explicit declarations.

### Provider-neutral data

`SportsProviderModels.kt` and `SportsProviderCapabilities.kt` define request and
response contracts without Gson, vendor JSON models, Android UI, local channel
IDs, or playback URLs. External IDs are scoped to the receiving provider account
instance. Requests use a half-open competition-time interval or explicit external
IDs; adapters own their API pagination and batching.

Events retain raw competitor/league names and optional session, status, and
kickoff/end times. Times are `Instant`; missing kickoff remains unknown. These
fields do not represent the EPG coverage window. Plan 2 alias and canonical-event
normalization remain the resolver's responsibility in Phase 3.3.

Scores support nullable text values, optional game state/clock/period, and
optional update times. Standings contain groups with independent named column
keys and nullable text cells, allowing tied rankings and sport-specific tables.
Broadcasters are names/region/language/type metadata only. Artwork is a separate
typed HTTPS URI whose string representation omits the URL; loading and caching
remain subject to the future adapter's rights/attribution review.

### Shared adapter mapping

`parseProviderTimestamp` supports explicitly selected offset ISO timestamps,
Unix seconds, and Unix milliseconds. It preserves fractional seconds and never
guesses the device timezone or numeric unit. Other provider-specific formats must
be explicitly converted by their adapter. Call it inside the HTTP decoding or
item-mapping error boundary so raw parsing exceptions never reach presentation.

`mapProviderItems` retains valid records, counts rejected records without keeping
their raw values or exceptions, and propagates cancellation. An entirely malformed
nonempty feed returns `INVALID_RESPONSE`; a valid empty feed returns a successful
empty batch. Mapping work is bounded to 10,000 attempted records by default,
configurable from 1 to 100,000, counting invalid records too. Exceeding that budget
returns `RESPONSE_TOO_LARGE`, leaving adapters responsible for smaller API pages.

### Contracts and verification

Reusable `EventProviderContract` and `LiveScoreProviderContract` test suites live
under `data/src/test/.../sports/provider/contract`. Future data-layer adapters can
subclass them, supply static fixture scenarios and expected IDs/times, and use
MockWebServer rather than a live account. The suites exercise stable event IDs,
UTC kickoff/update conversion, capability declarations, mixed valid/invalid
records, empty versus invalid event feeds, optional score fields, non-numeric
scores, normalized live state, and sanitized authentication failures.

Test-only fixture adapters run those suites through the real shared HTTP client
and mapping helper using JSON resources in `data/src/test/resources/sports/providers`.
They are not registered in production. Additional domain tests cover all-capability
declaration rejection, undeclared interface eligibility, fractional timestamps,
timezone-less rejection, cancellation, mapping budgets, non-team sessions,
standings schemas, text scores, and artwork URL redaction.

Validation passed:

```text
gradlew.bat :domain:test --tests "com.streamvault.domain.sports.provider.*" :data:testDebugUnitTest --tests "com.streamvault.data.sports.provider.*"
gradlew.bat :app:assembleDebug
```

The combined provider suites now have **52 tests, zero failures/errors**, including
25 new checks for this phase. The debug APK built successfully. No user-facing
strings, real provider connections, UI behavior, or playback behavior changed;
no emulator installation or playback validation was performed.

## Phase 3.3 — Source provenance and event resolver (2026-10-17)

`SportsEventProvenance.kt` defines account-scoped provider references, EPG
programme references, field-level source values, independent event and broadcast
times, source selection, and deterministic field precedence. EPG keeps control
of descriptions and its broadcast window. A selected event provider can replace
title, league, and competition times only after a high-confidence identity match;
the selected score and metadata providers have separate roles.

`SportsProviderEventMatcher` reuses Plan 2's sport, team-alias, league, and title
normalization. It rejects known sport, team, league, and session conflicts, skips
replays and studio programming, requires kickoff to fall within the EPG broadcast
window (with a 30-minute edge allowance) for team fixtures, and needs at least
0.8 confidence with a 0.12 uniqueness margin before linking. Broadcaster names
are only corroborating metadata and never become playback routes.

`SportsProviderEventResolver` preserves unmatched EPG events and selected
provider-only events, matches score-provider events independently, and retains
the source for each accepted value. The account-scoped in-memory link cache is
bounded to 10,000 links, expires links after seven days, rejects kickoff drift
over 60 minutes, and evicts after three recorded failures. The resolver is a
foundation for later source-selection and UI work; this phase does not change
the Sports Hub presentation or contact an external provider.

Focused resolver tests now verify high-confidence event merging, source
provenance, preservation of the EPG broadcast window and description, cached
event links, and retention of unmatched provider events as data-only records.
Validation passed:

```text
:feature:live:testDebugUnitTest --tests "com.streamvault.feature.live.sports.SportsProviderEventResolverTest"
```

Both tests passed. `:app:assembleDebug` also passed. No playback behavior
changed.

## Phase 3.4 — Provider settings

Settings now has a Sports category with a Data Providers page. It lists saved
accounts and registered adapters, shows each adapter's declared capabilities,
and supports adding, editing, testing, enabling, disabling, and removing an
account. API keys, bearer secrets, and Basic credentials are masked during
entry and remain in the encrypted sports-provider store; the normal account
list never reads or displays them. Custom endpoints are shown only for
descriptors that allow overrides and accept HTTPS URLs without embedded
credentials or query parameters.

Adapters can implement `SportsProviderConnectionTester` to provide a typed,
sanitized connection result. The settings page reports when an adapter does not
offer a test, and shows a removable unavailable account if its adapter is no
longer registered. The registry includes the TheSportsDB reference adapter,
which requires a user-provided Premium API key.

All new settings strings are translated in the 38 advertised locales. The
Phase 3.4 implementation and focused ViewModel tests are complete. Tests verify
credential storage on save and connection testing of a draft without persisting
it. The provider name and Premium API key label added for Phase 3.5 are also
translated in all 38 advertised locales. Provider selection in the Sports Hub
and API-first broadcast matching remain later phases.

Verification for Phase 3.4: `:feature:settings:compileDebugKotlin` and
`:app:assembleDebug` passed. Resource XML and formatting placeholders were
checked in all 38 advertised locales. Focused settings tests passed:

```text
:feature:settings:testDebugUnitTest --tests "com.streamvault.feature.settings.presentation.SportsProviderSettingsViewModelTest"
```

Both tests passed; the full Settings suite was not run in this continuation.

## Phase 3.5 — TheSportsDB reference adapter (2026-10-17)

TheSportsDB was selected to satisfy Plan 3's requirement for one legitimate
BYOK adapter. Plan 3.5 uses it to exercise the provider contracts; Plan 4 still
owns release/compliance gating and production expansion.

The adapter uses the Premium V2 base URL and `X-API-KEY` header. It implements
connection testing, league-season schedule lookup, live-score lookup, and game
status mapping. Schedule requests require a numeric league ID because the
provider exposes schedules by league and season; event-source selection must
therefore supply league IDs in Phase 3.6. Returned events are filtered by the
requested time range and optional sport. Schedule timestamps without offsets
are interpreted as UTC, as documented in
[`sports/providers/thesportsdb.md`](sports/providers/thesportsdb.md).

Seasons are cached for 24 hours, schedules for 15 minutes, and live scores for
two minutes in a bounded process-local cache. Live scores are fetched from the
provider's all-sports endpoint and filtered by requested external event IDs.
The adapter does not implement standings, broadcasters, artwork, or source
selection, and it does not feed data to the Sports Hub yet.

Verification passed:

```text
:data:testDebugUnitTest --tests "com.streamvault.data.sports.thesportsdb.*" --tests "com.streamvault.data.sports.provider.*"
:app:assembleDebug
```

The focused run passed **45 tests**. The two new Settings labels (provider name
and Premium API key) are present and translated across all 38 advertised
locales. The current terms review requires a paid subscription for app-store
apps, source mention, and separate artwork-rights review; production release
approval and point-of-use Sports Hub attribution remain Plan 4 / later-phase
work. No live API account or playback path was used.

## Phase 3.6 — Event source selection

Sports event source is now a persisted setting with Automatic, TV Guide / EPG,
and enabled event-provider account choices. Automatic orders accounts by their
configured priority, filters through the registry's declared `EVENTS`
capability, and moves to the next provider after a typed request failure. If no
provider succeeds, it uses the existing EPG path. EPG mode skips provider
requests, while a specific account selection only tries that account.

Provider accounts now store an optional, bounded list of provider-neutral
league IDs. Event adapters receive one request per configured league, and
adapters that support broad discovery receive one request without a league ID.
This gives TheSportsDB the league scope its schedule API requires without
putting TheSportsDB identifiers into the shared event model. The value is part
of encrypted provider configuration; the source choice remains in normal app
preferences and contains no credentials.

Provider events are resolved against assembled EPG events. Strong matches can
contribute selected event metadata while EPG descriptions, broadcast windows,
and channel choices remain intact. Unmatched EPG events stay in the list, and
unmatched provider events appear as data-only events with no channel attached.
The existing fallback channel matcher continues to run for EPG events that lack
a direct playable choice. Provider event scopes and source choice participate
in the local event-index cache key.

All five new Settings strings are translated and XML-checked in the 38
advertised locales. Focused tests cover EPG-only routing, capability and enabled
account filtering, provider priority fallback, multi-league requests and event
deduplication, explicit account selection, resolver provenance and unmatched
events, persisted source preferences, provider-scope storage, and existing
Sports Hub behavior. The focused run passed **26 tests** across the live,
settings, and data modules with zero failures. `:feature:live:verifyFeatureLiveBoundary`
and `:feature:settings:verifyFeatureSettingsBoundary` passed. The final
integration build passed with `:app:assembleDebug`.

## Phase 3.7 — API-first broadcast matching

Provider events continue to resolve against EPG events through the existing
confidence-aware matcher and provider-event link cache. The selected event
provider is also selected for broadcaster metadata, so its provider-neutral
`ProviderBroadcaster` values remain attached to matched and unmatched events.

When an event has no playable EPG mapping, broadcaster names are normalized
with the shared channel normalizer and matched exactly against the user's
playable local channel catalog. These matches join the existing Plan 2 event
and date signals, and the existing manual picker can search, select, and
remember the local channel. Playable EPG mappings remain first-choice; the
fallback never creates or accepts a provider-supplied playback URL. Provider-
only events can use the same local catalog matching path.

Verification passed: all **188** `:feature:live:testDebugUnitTest` tests passed
with zero failures or errors, including 16 channel-matcher tests, 3 provider
resolver tests, and 11 Sports Hub ViewModel tests. The feature boundary check
passed, `git diff --check` was clean, and `:app:assembleDebug` succeeded. No
user-facing strings or live TV playback behavior changed.

## Phase 3.8 — Live-score enrichment (2026-10-17)

Live-score source is an independent setting with Automatic, Off, and enabled
provider-account choices. Automatic prefers the selected event account when it
also provides events and live scores, then follows provider priority. A
different score account must expose events as well as scores so its
provider-scoped event IDs can be matched safely to local fixtures.

The score router reuses the event load when both source roles use the same
account. For a separate account it loads that provider's event records and
reuses the existing confidence-checked event-link cache to join scores to EPG
fixtures. The sports cards now show competitor scores, provider status, period,
and clock. A live provider status also drives the existing localized live
badge; retained scores and clocks are dimmed while stale.

The repository checks for score updates every 60 seconds while a live fixture
is visible (and continues refreshing the On now range). Score responses are
cached for two minutes, score-provider event lists for five minutes, and a
last successful score response can be retained as stale for up to ten minutes
after provider failure. Failed sources are held until the provider's `retryAt`
or for two minutes when it supplies none. Automatic selection falls through
failed providers; an explicit account selection stays on that account.
Requests and response mapping remain provider-neutral, while each adapter
continues to own its API limits and typed failures.

The two new Settings labels are translated and XML-checked in all 38 advertised
locales. Verification passed: **195** `:feature:live:testDebugUnitTest` tests,
**1,396** `:data:testDebugUnitTest` tests, all 3 focused
`SportsProviderSettingsViewModelTest` tests, and `:app:assembleDebug`. The
settings ViewModel tests cover source selection, while router and resolver
tests cover independent accounts, event-link reuse, two-minute refresh
caching, automatic fallback, provider retry times, and stale-score retention.
The full Settings test task still reports one `SettingsLocalizationCoverageTest`
failure in the separate `settings_redesign.xml` resources; the score labels use
regular `strings.xml` resources and are present in each advertised locale.

## Phase 3.9 — Standings and metadata (2026-10-17)

Standings and team/league metadata now have independent Automatic, Off, and
provider-account preferences. Standings routing uses the selected event
provider's league ID only when the standings adapter has the same provider
type; otherwise it uses that account's configured league scope. Responses
must match the requested league identity. Provider-defined groups, column
labels, and cell values are retained so different sports can use different
table shapes. Standings are loaded on demand from an event details view to
avoid unnecessary quota use.

Standings results are cached for 30 minutes, retained as stale for up to 12
hours after a failure, and retried after the provider's `retryAt` or a
five-minute default cooldown. The in-memory cache is bounded to 64 league
entries. Metadata lookup requires the provider type to match the event source,
so provider-scoped team and league IDs never cross API namespaces. Requests
are bounded to 24 teams and 12 leagues per enrichment pass. Successful team
and league metadata is cached for 24 hours and can remain stale for seven days;
failed sources cool down for the supplied `retryAt` or ten minutes by default.
Metadata caches are bounded to 256 teams and 64 leagues.

Selected metadata is merged into the canonical event with field-level source
references and provider attribution. The event details view shows available
team/league metadata, the metadata source, and the provider's standings table.
Disabling or removing a selected provider resets affected capability choices
to Automatic. The two new Settings and ten Sports Hub labels are translated
and placeholder-checked in all 38 advertised locales.

Verification passed: **201** `:feature:live:testDebugUnitTest` tests, all **4**
focused `SportsProviderSettingsViewModelTest` tests, and the
`PreferenceBackupRegistryTest`. `:app:assembleDebug` succeeded, and
`git diff --check` was clean. The standings and metadata router tests cover
provider selection, provider-ID namespace safety, scoped lookup, dynamic
table preservation, successful caching, stale fallback, event provenance,
attribution, and Off mode.

## Phase 3.11 — Multiple-provider routing audit (2026-10-17)

This phase was delivered incrementally in Phases 3.6–3.10. Event, live-score,
standings, and metadata choices are independently capability-filtered. In
Automatic mode, event requests follow priority and move to the next eligible
provider after failure; score, standings, and metadata routers apply their
own account preferences, fallback, retry, and stale-data policies. Explicit
account selection does not silently switch sources.

The event resolver merges provider fields only after confidence-aware identity
matching. EPG broadcast timing, descriptions, and playable channel choices
remain authoritative; selected provider title, league, event timing, status,
score, and broadcaster data retain field-level source references. Unmatched
provider events remain data-only, so provider payloads cannot become playback
URLs. The full **201-test** `:feature:live:testDebugUnitTest` suite passed with
zero failures, including source routing, conflicts, provenance, partial
failures, provider-ID namespace safety, and stale fallback.

## Phase 3.10 — Custom JSON sports provider (2026-10-17)

Custom provider accounts store their declarative JSON mapping in the existing
encrypted, no-backup sports-provider envelope. Credentials remain a separate
encrypted field. The mapping supports HTTPS event feeds and optional live
scores, with `none`, API-key header, API-key query, Bearer, and Basic
authentication. Account capabilities and display names come from the saved
definition, so an account without a score mapping does not appear as a score
source and multiple custom APIs are distinguishable in Settings.

Endpoint templates accept bounded date, league, sport, and event-ID
placeholders. They append beneath the configured API base path, percent-encode
values, and remain on the same HTTPS host and port. Field selectors are
bounded dot-separated JSON object paths; the provider executes no user code
and follows no supplied URL. The shared client caps each response at 2 MiB;
the adapter also caps configuration, selector depth, scalar lengths, and
mapped event/score counts. Pagination and generic standings remain deferred.
The configuration schema and example are in
[`sports/custom-provider.md`](sports/custom-provider.md).

The new three Settings labels are translated and XML-checked in all 38
advertised locales. Verification passed all **201** Sports Hub unit tests,
all **205** domain tests, the **24** focused custom-provider, encrypted-store,
and HTTP-client tests, and all **5** focused provider-settings ViewModel
tests. `:feature:live:verifyFeatureLiveBoundary`,
`:feature:settings:verifyFeatureSettingsBoundary`, and
`:app:assembleDebug` passed. `git diff --check` was clean.

## Phase 3.12 — Production hardening (2026-10-17)

The large-feed resolver now indexes guide events by canonical competitor pair
and title, validates cached guide links by local ID, and indexes provider
assignments and scores before merging. A synthetic regression case with 1,400
guide events and 400 provider events completes in **0.096 seconds** in the JVM
test run, while retaining ambiguity and conflict checks. Resolver duration is
also available through the bounded `SportsPerf` diagnostics stages.

The shared built-in response cache now partitions entries by account, canonical
endpoint, and a SHA-256 credential fingerprint. Cache keys contain no raw
credentials. Regression tests confirm event/season and score responses do not
cross credential changes, nor do responses cross endpoint changes; bounded
cache size and TTL behavior remain covered. The provider HTTP client maps
offline I/O to typed network failures, maps HTTP 429 and parses both supported
`Retry-After` forms, and exposes neither response bodies nor exception messages
in failure results. Router tests cover network/rate-limit fallback and stale
data behavior. The credentials object redacts `toString`; a source audit found
no sports-provider URL, API key, or credential logging.

The Settings source review covered capability-filtered account choices, custom
provider name display, multiline JSON editing, test/save flow, and TV focusable
controls. The three new strings were verified as nonempty translations in all
38 advertised locales. A repeat device review was not possible in this session
because only `emulator-5554` was attached; the prior Android TV measurements
and D-pad review remain linked from the Plan 3 checklist.

The Play release dependency review found no `mockwebserver` dependency in
`:app:releaseRuntimeClasspath`. Full verification passed:
`:data:testDebugUnitTest` (**1,406 tests**),
`:feature:live:testDebugUnitTest` (**202 tests**), both feature-boundary checks,
and `:app:assembleDebug`, all with zero test failures. The remaining terms and
attribution review is explicitly deferred to Plan 4's release gate for built-in
providers.
