# StreamVault Sports Hub — PLAN 3: Generic Sports Data Providers

**Purpose:** Add a provider-neutral, user-configured sports-data system on top of the Sports Hub.  
**Sequence:** Implement after the related Sports Plan 2 channel-discovery and resilience improvements are stable.  
**Core principle:** External providers enrich or optionally drive event discovery, but StreamVault playback always resolves to the user's own M3U/Xtream channels.

---

# 0. Relationship to Plan 2

Plan 3 is **not a replacement** for Plan 2.

Implement Plan 2 first, especially the pieces that create a stable local Sports foundation:

- canonical event model;
- improved team/non-team event parsing;
- stronger normalization and aliases;
- confidence-aware deduplication;
- reliable EPG-to-channel resolution;
- fallback event-channel matching;
- manual channel selection;
- remembered channel preferences;
- fixture and integration tests;
- real-device verification.

Plan 3 depends on those foundations because external APIs introduce additional event identities, team names, league names, kickoff times, broadcaster names, and partial/conflicting data.

The intended progression is:

```text
PLAN 1
EPG-first Sports Hub
        ↓
PLAN 2
Real-world IPTV resilience
(IPTV resilience improvements)
        ↓
PLAN 3
Generic / BYOK Sports Data Providers
```

Do not pause Plan 2 halfway through merely to start connecting APIs.

However, once the canonical event model and normalization boundaries are stable, provider interfaces may be scaffolded without implementing real providers yet.

---

# 1. Goal

Allow users to configure one or more sports-data providers using their own:

- API key;
- bearer token;
- username/password where appropriate;
- OAuth sign-in where a supported provider requires it;
- base URL / endpoint where configurable;
- custom REST/JSON source.

Providers may supply different capabilities, including:

```text
event discovery / fixtures
live scores
game state / clock / period
standings
team metadata
league metadata
artwork
broadcast/network metadata
lineups
statistics
```

StreamVault must not assume every provider supports every capability.

The system must work with:

```text
no provider configured
```

using the existing EPG Sports Hub.

---

# 2. Major architectural change

The current Sports Hub is EPG-first.

Plan 3 generalizes this to a **capability-based source strategy**.

Do not think of the whole Sports Hub as globally:

```text
EPG-first
```

or:

```text
API-first
```

Instead decide independently which source is authoritative for each capability.

The important capabilities are:

```text
1. Event discovery
2. Live scores / game state
3. Standings
4. Team / league metadata
5. Broadcaster metadata
6. Playback/channel discovery
```

Playback/channel authority is special:

> **Only the user's configured M3U/Xtream sources can provide playable StreamVault channels.**

An external API may help identify broadcasters, but it must never inject arbitrary third-party streams.

---

# 3. Target architecture

```text
                      EVENT SOURCES

               +----------+-----------+
               |          |           |
               v          v           v
              EPG      Provider A  Provider B
               |          |           |
               +----------+-----------+
                          |
                          v
                  Event Resolver
                 / Normalization
                          |
                          v
                  Canonical SportEvent
                          |
          +---------------+----------------+
          |               |                |
          v               v                v
      Live Scores      Standings        Metadata
          |               |                |
          +---------------+----------------+
                          |
                          v
                 Broadcast Resolver
                          |
          +---------------+----------------+
          |               |                |
          v               v                v
   API broadcaster      XMLTV       event-channel names
          |               |                |
          +---------------+----------------+
                          |
                          v
                  User M3U / Xtream
                          |
                          v
                        PLAY
```

---

# 4. Source modes

Expose an event-discovery strategy in Settings.

Recommended user-facing options:

```text
Sports event source

● Automatic
○ TV Guide / EPG
○ <configured provider capable of events>
```

## 4.1 Automatic

Recommended default.

Behavior:

```text
if a configured provider supports EVENTS:
    prefer that provider for canonical event discovery
    merge/corroborate EPG information
else:
    use EPG event discovery
```

Automatic should not be tied to a particular vendor.

## 4.2 TV Guide / EPG

Force the current local behavior.

Useful when:

- the user's guide is excellent;
- provider quota is limited;
- API coverage is worse than the user's EPG;
- user prefers local-only behavior.

## 4.3 Specific provider

Allow a provider to become the event-discovery source only if it advertises the required capability.

Example:

```text
Sport Provider A
Capabilities:
✓ Events
✓ Live scores
✓ Standings
✗ Broadcasters
```

It may be selected for event discovery even without broadcaster data.

Channel discovery can still rely on EPG + playlist matching.

---

# 5. Provider capability model

Define explicit capabilities.

Example:

```kotlin
enum class SportsCapability {
    EVENTS,
    LIVE_SCORES,
    GAME_STATUS,
    STANDINGS,
    TEAM_METADATA,
    LEAGUE_METADATA,
    ARTWORK,
    BROADCASTERS,
    LINEUPS,
    STATISTICS
}
```

A provider adapter declares:

```kotlin
interface SportsProvider {
    val id: String
    val displayName: String
    val capabilities: Set<SportsCapability>
}
```

Do not infer a capability from successful HTTP calls.

Capabilities should be explicitly declared by the adapter/configuration.

---

# 6. Provider roles are independent

A user may configure different providers for different jobs.

Example:

```text
Event discovery:
Provider A

Live scores:
Provider A

Standings:
Provider B

Team metadata:
Provider C

Playback:
Automatic / local playlist
```

Internally, support this from the beginning.

The first UI may simplify this to:

```text
Automatic
```

for most capabilities.

---

# 7. Recommended Settings UX

Top-level:

```text
Settings
  Sports
    Data Providers
    Event Source
    Live Scores
    Standings
    Metadata
```

## Data Providers screen

Example:

```text
Sports Data Providers

Provider A
Connected
Fixtures · Live scores · Standings

Provider B
Connected
Live scores · Team metadata

Custom Sports Provider
Connected
Fixtures · Live scores

+ Add provider
```

Do not expose secrets in normal UI after entry.

Provide:

```text
Test connection
Edit
Disable
Remove
```

---

# 8. Adding a known provider

Flow:

```text
Add Provider
    ↓
Choose provider type
    ↓
Configure credentials / endpoint
    ↓
Test connection
    ↓
Discover/validate capabilities
    ↓
Save
```

Example:

```text
Provider: Sportmonks

Base URL:
[ default value ]

API key:
[••••••••••••••]

Test connection
```

For known providers, users should not have to manually enter every endpoint path.

The provider adapter knows its routes.

---

# 9. Base URL configuration

Each provider descriptor may define:

```kotlin
data class ProviderEndpointConfig(
    val defaultBaseUrl: String,
    val allowOverride: Boolean
)
```

For official hosted APIs:

```text
default URL
```

should normally be sufficient.

Allow override when useful for:

- regional endpoints;
- self-hosted compatible servers;
- API gateways;
- mirrors explicitly supported by the provider;
- enterprise deployments.

Do not require the user to enter a separate URL for every API operation for known adapters.

---

# 10. Authentication model

Support reusable authentication types.

Example:

```kotlin
sealed interface ProviderAuth {
    data object None : ProviderAuth

    data class ApiKeyHeader(
        val headerName: String
    ) : ProviderAuth

    data class ApiKeyQuery(
        val parameterName: String
    ) : ProviderAuth

    data object BearerToken : ProviderAuth

    data object BasicAuth : ProviderAuth

    data class OAuth2(
        val providerId: String
    ) : ProviderAuth
}
```

Provider adapters declare which scheme they use.

---

# 11. API-key handling

Keys belong to the user.

Do not ship a shared StreamVault production API key.

Store credentials securely using Android's supported encrypted/Keystore-backed mechanism appropriate to the project's architecture.

Requirements:

- never log API keys;
- never include API keys in diagnostics;
- avoid exposing query-string keys in logs;
- redact credentials from exception messages;
- do not include credentials in backups unless existing secure-settings behavior explicitly allows it and the user understands that;
- do not sync credentials through unrelated preference export unless intentionally designed.

---

# 12. OAuth providers

OAuth cannot be fully generic in practice.

Different providers may vary in:

```text
authorization endpoint
token endpoint
PKCE requirements
client ID
client secret rules
redirect URI
scopes
device-code support
refresh-token behavior
token lifetime
```

Therefore:

> Build a reusable OAuth framework, but configure/implement OAuth per supported provider.

Do not pretend one generic "OAuth URL" field supports all services safely.

Prefer PKCE/device authorization where appropriate for installed apps.

Never embed a confidential client secret in the APK when the provider requires that secret to remain private.

---

# 13. Provider adapter interface

Do not create one giant interface with mandatory methods.

Prefer capability-specific interfaces.

Example:

```kotlin
interface SportsProvider {
    val descriptor: SportsProviderDescriptor
}

interface EventProvider : SportsProvider {
    suspend fun events(
        request: EventRequest
    ): ProviderResult<List<ProviderEvent>>
}

interface LiveScoreProvider : SportsProvider {
    suspend fun liveScores(
        request: ScoreRequest
    ): ProviderResult<List<ProviderScore>>
}

interface StandingsProvider : SportsProvider {
    suspend fun standings(
        request: StandingsRequest
    ): ProviderResult<ProviderStandings>
}

interface BroadcastProvider : SportsProvider {
    suspend fun broadcasters(
        request: BroadcastRequest
    ): ProviderResult<List<ProviderBroadcaster>>
}

interface TeamMetadataProvider : SportsProvider {
    suspend fun teamMetadata(
        request: TeamMetadataRequest
    ): ProviderResult<ProviderTeamMetadata>
}
```

A single adapter may implement several interfaces.

---

# 14. Provider descriptor

Example:

```kotlin
data class SportsProviderDescriptor(
    val id: String,
    val displayName: String,
    val capabilities: Set<SportsCapability>,
    val authType: ProviderAuthType,
    val defaultBaseUrl: String?,
    val supportsCustomBaseUrl: Boolean,
    val requiresAccount: Boolean,
    val attribution: ProviderAttribution?,
    val configurationVersion: Int
)
```

Do not include legal/commercial claims in the descriptor unless verified.

Provider integration documentation should separately record reviewed terms.

---

# 15. Provider-neutral event model

External events must first become provider-neutral DTOs.

Example:

```kotlin
data class ProviderEvent(
    val providerEventId: String,
    val sport: String?,
    val league: ProviderLeague?,
    val title: String?,
    val competitors: List<ProviderCompetitor>,
    val eventStart: Instant?,
    val eventEnd: Instant?,
    val status: ProviderGameStatus?,
    val broadcasters: List<ProviderBroadcaster>,
    val metadata: Map<String, String>
)
```

Then normalize into StreamVault's canonical `SportEvent`.

Do not expose provider JSON models directly to the UI.

---

# 16. Separate event time from broadcast time

Plan 3 introduces a critical distinction.

An API may say:

```text
Kickoff: 21:00
```

while XMLTV says:

```text
TV coverage: 20:30–23:30
```

Both are correct.

Model separately:

```kotlin
data class SportEventTiming(
    val eventStart: Instant?,
    val eventEnd: Instant?,
    val broadcastStart: Instant?,
    val broadcastEnd: Instant?
)
```

UI can then show:

```text
Kickoff 21:00
Coverage from 20:30
```

when both are known.

Do not treat normal pre-game coverage as an API/EPG conflict.

---

# 17. Event provenance

Once multiple sources exist, provenance becomes essential.

Each canonical event should know its source references.

Example:

```kotlin
sealed interface SportsSourceRef {
    data class Epg(
        val programmeId: String,
        val channelId: String
    ) : SportsSourceRef

    data class Provider(
        val providerId: String,
        val externalEventId: String
    ) : SportsSourceRef
}
```

A canonical event may contain:

```text
EPG source
+
Provider A source
+
Provider B source
```

---

# 18. Field provenance

Where practical, preserve provenance for enriched fields.

Example:

```kotlin
data class SourcedValue<T>(
    val value: T,
    val source: SportsSourceRef,
    val updatedAt: Instant?
)
```

This is particularly useful for:

```text
score
status
league
event start
team metadata
artwork
broadcaster
```

Do not necessarily wrap every UI field in `SourcedValue`; the internal resolver may keep provenance separately if that is cleaner.

---

# 19. Event resolver

Introduce a provider/EPG event resolver.

Responsibilities:

```text
normalize provider event
normalize EPG event
generate candidate identities
match equivalent events
merge trusted fields
preserve source references
cache provider-event mappings
```

Input:

```text
EPG SportEvent candidates
ProviderEvent candidates
```

Output:

```text
Canonical SportEvent
```

---

# 20. External-event matching

Match on:

```text
sport
league
competitors
event title/session
kickoff time
event date
```

## Team sports

Strong signals:

```text
same canonical competitor pair
same league
event start within tolerance
```

## Fights

Use:

```text
fighter pair
promotion
card date
```

## Motorsport

Use:

```text
series
grand prix/event
session
date/time
```

## Golf

Use:

```text
tour
tournament
round/date
```

## Tennis

Use:

```text
tour/tournament
player pair when available
round
time
```

Do not use one universal fuzzy-title matcher for every sport.

---

# 21. External ID mapping cache

After a high-confidence match:

```text
StreamVault event identity
↕
provider event ID
```

cache that association.

Example:

```kotlin
data class ProviderEventLink(
    val providerId: String,
    val providerEventId: String,
    val localEventFingerprint: String,
    val confidence: Float,
    val linkedAt: Instant
)
```

This reduces repeated fuzzy matching during score refreshes.

Invalidate/re-evaluate when:

```text
event identity changes materially
provider season changes
kickoff moves significantly
teams change
cached link fails repeatedly
```

---

# 22. Source conflict policy

Providers and EPG may disagree.

Do not silently overwrite values without a defined policy.

Example rules:

## Event identity

Configured event provider wins when selected as event authority and confidence is high.

## Broadcast times

EPG wins for:

```text
broadcastStart
broadcastEnd
```

## Kickoff/event start

Configured event provider wins when available.

## Playback channels

Local StreamVault EPG/channel mapping and user's playlist win.

## Live score/status

Configured live-score provider wins.

## Description

Prefer EPG description unless provider metadata is explicitly selected.

## Artwork

Use configured metadata/artwork provider according to priority.

---

# 23. Automatic source strategy

`Automatic` should be deterministic.

Suggested initial policy:

```text
EVENTS:
1. explicitly selected EventProvider
2. highest-priority enabled provider with EVENTS
3. EPG

LIVE SCORES:
1. explicitly selected LiveScoreProvider
2. highest-priority enabled provider with LIVE_SCORES
3. none

STANDINGS:
1. explicitly selected StandingsProvider
2. highest-priority enabled provider with STANDINGS
3. unavailable

METADATA:
1. selected metadata provider
2. enabled provider priority
3. EPG/local metadata

PLAYBACK:
always local M3U/Xtream
```

Do not automatically make a provider authoritative merely because it happens to return data.

---

# 24. API-first event discovery

When a provider is selected for `EVENTS`:

```text
Provider
   |
   v
ProviderEvent[]
   |
   v
normalize
   |
   v
Canonical SportEvent[]
   |
   +--------------------------+
   |                          |
   v                          v
EPG corroboration        no EPG match
   |                          |
   v                          v
mapped channels        fallback channel matcher
```

The API may show events the user's EPG completely omits.

Those events may still appear in Sports.

If no playable StreamVault channel is found:

```text
No matching channel in your TV sources
```

Do not hide the event merely because playback is unavailable.

---

# 25. EPG-first with provider enrichment

When EPG is selected for events:

```text
EPG
  |
  v
SportEvent
  |
  v
ProviderEventMatcher
  |
  +--> score
  +--> real game state
  +--> league metadata
  +--> team metadata
  +--> standings link
```

This preserves the existing Sports behavior while improving data quality.

---

# 26. Hybrid event discovery

Automatic may eventually merge event sets from both sources.

Example:

```text
API events
+
EPG-only sports events
```

This is useful when a provider covers football/basketball but the user's EPG also contains:

```text
wrestling
local sports
regional competitions
minor leagues
```

that the API does not cover.

Do not drop valid EPG sports merely because an API is enabled.

Recommended behavior:

```text
canonical provider events
+
unmatched high-confidence EPG events
```

with deduplication between them.

---

# 27. Broadcaster data

Some providers may supply broadcaster/network information.

Model separately from playable channels.

Example:

```kotlin
data class ProviderBroadcaster(
    val name: String,
    val country: String?,
    val language: String?,
    val type: BroadcastType?
)
```

Possible types:

```text
LINEAR_TV
STREAMING_SERVICE
RADIO
UNKNOWN
```

Provider broadcaster data is a **matching signal**, not a playable stream.

---

# 28. Broadcast resolver

Signals, strongest to weakest:

```text
1. existing resolved EPG -> StreamVault channel
2. user's remembered event/team/series choice
3. exact EPG/channel identifier
4. provider broadcaster + exact/canonical local network match
5. event-named IPTV channel
6. EPG title near API event time
7. manual selection
```

Exact order may vary when a user explicitly overrides a choice.

Do not let broad fuzzy broadcaster names auto-route dangerously.

Example:

```text
FOX
TNT
ABC
```

may be too ambiguous without region/network context.

---

# 29. Poor-EPG repair

One major benefit of API event discovery is repairing weak XMLTV.

Example:

API:

```text
FC Barcelona vs Real Madrid
LaLiga
Kickoff 21:00
```

EPG:

```text
Live Football
20:30–23:30
beIN Sports 1
```

Resolver may infer a high-confidence match from:

```text
sport
time overlap
league/channel context
provider broadcaster
other available signals
```

Then StreamVault can display:

```text
FC Barcelona vs Real Madrid
LaLiga
Kickoff 21:00

Watch on:
beIN Sports 1
```

while preserving the EPG broadcast window.

This inference must be confidence-gated and testable.

---

# 30. Provider priority

Allow ordering enabled providers.

Example:

```text
Provider priority

1. Provider A
2. Provider B
3. Custom Server
```

Use priority only among providers that support the requested capability.

Do not call providers lacking the capability.

---

# 31. Rate limiting and quota awareness

BYOK means each user generally consumes their own quota.

Still, StreamVault must behave responsibly.

Each provider adapter should define refresh policy guidance.

Example:

```kotlin
data class ProviderRatePolicy(
    val liveRefresh: Duration,
    val upcomingRefresh: Duration,
    val standingsRefresh: Duration,
    val metadataRefresh: Duration
)
```

Use caching.

Suggested conceptual behavior:

```text
live score:
frequent

upcoming fixtures:
minutes/hours

standings:
tens of minutes

team metadata:
days
```

Do not use one universal polling interval.

---

# 32. HTTP cache / application cache

Provider responses should be cached according to capability.

Examples:

```text
event schedule cache
score cache
standings cache
team metadata cache
artwork cache
```

Avoid re-downloading static metadata every Sports-screen opening.

Cache keys must include:

```text
provider
account/config identity where necessary
sport/league/team/event
query period
```

Never include raw API keys in cache keys.

---

# 33. Error isolation

A failing provider must not break the Sports Hub.

Examples:

```text
Provider A score request fails
    ↓
show event without score

Provider B standings fails
    ↓
show "Standings unavailable"

Event provider unavailable
    ↓
Automatic may fall back to EPG if allowed
```

One malformed provider event must not discard an entire league response.

---

# 34. Provider health

Track lightweight runtime health:

```text
last successful request
last error category
authentication failure
rate-limited
temporarily unavailable
invalid configuration
```

Settings may show:

```text
Provider A
Connected

Provider B
Authentication failed
```

Do not expose raw sensitive server errors unnecessarily.

---

# 35. Retry policy

Classify failures:

```text
authentication error
rate limit
server 5xx
network timeout
parse/schema error
configuration error
```

Rules:

- do not retry invalid credentials aggressively;
- respect `Retry-After`;
- use bounded backoff for transient failures;
- do not hammer a broken endpoint;
- retain stale cached data where safe and clearly applicable.

---

# 36. Custom Sports Provider

Add an advanced generic integration mode.

Target users:

```text
self-hosted API users
advanced users
custom backend users
unsupported legitimate APIs
```

This should not require a StreamVault-specific provider adapter for simple JSON APIs.

---

# 37. Custom provider configuration

Example:

```text
Custom Sports Provider

Name:
My Sports API

Base URL:
https://example.com/api

Authentication:
Bearer Token

Events endpoint:
/events

Live scores endpoint:
/scores/live

Standings endpoint:
/standings
```

Endpoints may be optional.

Capabilities are enabled only when the required mapping/configuration exists.

---

# 38. Custom JSON mapping

Provide a declarative mapping format.

Example conceptual configuration:

```json
{
  "events": {
    "path": "$.data.events",
    "id": "$.id",
    "sport": "$.sport.name",
    "league": "$.league.name",
    "title": "$.name",
    "start": "$.start_time",
    "homeName": "$.home.name",
    "awayName": "$.away.name",
    "homeScore": "$.home.score",
    "awayScore": "$.away.score",
    "status": "$.status"
  }
}
```

Actual implementation may use JSONPath or a simpler internal field-selector language.

Do not execute arbitrary user code/scripts in v1.

Prefer declarative mapping.

---

# 39. Custom request templates

Advanced configuration may need:

```text
query parameters
headers
date placeholders
league placeholders
event ID placeholders
pagination
```

Example:

```text
/events?date={date}&league={leagueId}
```

Design a safe template language.

Do not allow arbitrary shell/code execution.

Potential placeholders:

```text
{date}
{fromDate}
{toDate}
{leagueId}
{teamId}
{eventId}
{page}
```

---

# 40. Custom authentication

Support:

```text
none
header API key
query API key
Bearer
Basic
```

OAuth for arbitrary custom providers should be deferred unless a secure generic flow can be designed.

Known provider adapters can support OAuth explicitly.

---

# 41. Pagination

Provider adapters must own pagination logic.

Possible patterns:

```text
page/pageSize
offset/limit
cursor
next URL
```

Do not expose pagination to Sports UI.

Custom provider mapping may eventually support common pagination modes.

For v1 custom-provider support, it is acceptable to support only simple non-paginated/single-page event feeds if clearly documented.

---

# 42. Provider-specific adapters

Known providers should have dedicated adapters.

Benefits:

- better validation;
- better pagination;
- better error handling;
- provider-specific status mapping;
- provider-specific league/team identity;
- correct authentication;
- richer capability support.

The custom provider is a fallback, not a replacement for quality first-class adapters.

---

# 43. Adding a provider checklist

Every built-in adapter must document:

```text
provider name
official developer documentation
commercial-use status / terms review
auth type
base URL
capabilities
rate limits known to the user/account
required attribution
endpoint list
pagination
time format
league identity
team identity
live status mapping
score representation
broadcast representation
known limitations
```

Do not add a provider simply because an endpoint works technically.

---

# 44. Undocumented / reverse-engineered providers

Do not include provider-specific Play-build adapters for undocumented third-party services merely disguised behind codenames.

A user-supplied URL does not change the fact that StreamVault would be shipping provider-specific parsing logic.

Therefore:

```text
official/allowed provider adapter
    ✅ appropriate for built-in support

generic Custom REST/JSON mapping
    ✅ provider-neutral

undocumented provider-specific parser
    ❌ not part of Plan 3 Play implementation
```

If the project later makes a separate distribution decision for an experimental GitHub-only adapter, it must remain isolated from the Play build.

That is outside this plan.

---

# 45. No codenames as a compliance mechanism

Do not use:

```text
Provider X
Provider Y
```

to hide the identity of an integration.

Codenames do not materially change what the software does.

Provider names may be abstracted internally for architecture, but legal/terms decisions are based on actual behavior, not labels.

---

# 46. Provider module isolation

Suggested architecture:

```text
sports/
├── providers/
│   ├── core/
│   │   ├── SportsProvider.kt
│   │   ├── SportsCapability.kt
│   │   ├── ProviderConfig.kt
│   │   ├── ProviderAuth.kt
│   │   ├── ProviderRegistry.kt
│   │   ├── ProviderResult.kt
│   │   └── ProviderHealth.kt
│   │
│   ├── matching/
│   │   ├── ProviderEventMatcher.kt
│   │   ├── ProviderEventLink.kt
│   │   └── SourceMergePolicy.kt
│   │
│   ├── custom/
│   │   ├── CustomProvider.kt
│   │   ├── CustomProviderConfig.kt
│   │   ├── JsonFieldMapping.kt
│   │   └── RequestTemplate.kt
│   │
│   └── <known-provider>/
│       ├── <Provider>Adapter.kt
│       ├── <Provider>Api.kt
│       ├── <Provider>Mapper.kt
│       └── <Provider>Models.kt
```

Adapt to the actual StreamVault module structure.

Do not restructure unrelated code solely to match this example.

---

# 47. Provider registry

Use a registry.

Example:

```kotlin
interface SportsProviderFactory {
    val descriptor: SportsProviderDescriptor

    fun create(
        config: SportsProviderConfig
    ): SportsProvider
}
```

Registry:

```kotlin
class SportsProviderRegistry(
    factories: Set<SportsProviderFactory>
)
```

This avoids giant `when(providerType)` blocks across the app.

---

# 48. Provider configuration versioning

Saved provider configuration must be versioned.

Example:

```kotlin
data class SportsProviderConfig(
    val instanceId: String,
    val providerType: String,
    val configVersion: Int,
    val enabled: Boolean,
    val baseUrl: String?,
    ...
)
```

Adapters may migrate their own configuration if fields change.

Do not make users re-enter API keys because a non-secret config format changed.

---

# 49. Multiple accounts of the same provider

Design IDs so this can be supported eventually.

Example:

```text
Sport API — Personal
Sport API — Family
My self-hosted API
```

Even if v1 limits one account per provider, avoid using provider type itself as the unique configuration ID.

Use:

```text
provider instance ID
```

---

# 50. Provider request client

Centralize:

```text
timeouts
user agent
credential injection
redaction
Retry-After handling
common response limits
network errors
JSON parse errors
```

Do not let every adapter reinvent unsafe HTTP behavior.

Still allow adapters to perform provider-specific calls.

---

# 51. Response size protection

Custom endpoints could accidentally return enormous payloads.

Set reasonable safeguards for:

```text
maximum response size
maximum events parsed per request
maximum nested mapping work
pagination bounds
```

Fail gracefully.

Do not allow a broken custom endpoint to exhaust memory on Android TV.

---

# 52. Time handling

Convert provider timestamps to `Instant`.

Support provider formats such as:

```text
ISO 8601
Unix seconds
Unix milliseconds
provider-specific documented format
```

For custom providers, require the user to specify format if automatic parsing is ambiguous.

Never treat a timezone-less timestamp as safely local without an explicit rule.

---

# 53. League/team normalization reuse

Plan 3 must reuse Plan 2's normalization system.

Do not build provider-specific team alias systems inside each adapter unless the provider requires an initial translation.

Flow:

```text
provider raw team
    ↓
provider mapper
    ↓
ProviderCompetitor
    ↓
shared TeamNormalizer
    ↓
canonical Competitor
```

Provider IDs remain attached as source references.

---

# 54. Broadcaster normalization reuse

Normalize:

```text
ESPN
ESPN HD
ESPN US
beIN Sports 1
beIN SPORTS 1 HD
Sky Sports F1
```

through the same channel/broadcaster normalization concepts developed in Plan 2.

Do not create separate broadcaster identity logic for every provider.

---

# 55. Standings

Standings are provider data, not EPG data.

Model independently.

Example:

```kotlin
data class StandingsTable(
    val league: CanonicalLeagueRef,
    val season: String?,
    val groups: List<StandingsGroup>,
    val source: SportsSourceRef,
    val updatedAt: Instant
)
```

Do not try to force every sport into identical columns.

Different sports may expose:

```text
W/L
points
goal difference
games behind
conference/division
driver championship points
constructor points
```

Use sport-aware presentation.

---

# 56. Live scores

Scores are optional enrichment.

Example:

```kotlin
data class LiveScore(
    val eventRef: CanonicalEventRef,
    val competitorScores: Map<CanonicalCompetitorRef, String>,
    val state: GameState?,
    val clock: String?,
    val period: String?,
    val detail: String?,
    val source: SportsSourceRef,
    val updatedAt: Instant
)
```

Do not require numeric scores because:

```text
tennis
cricket
combat sports
racing
golf
```

may require different representations.

Allow provider-specific structured extensions later.

---

# 57. Game-state normalization

Normalize provider states into a small canonical set:

```kotlin
enum class GameState {
    SCHEDULED,
    LIVE,
    SUSPENDED,
    DELAYED,
    POSTPONED,
    CANCELLED,
    FINAL,
    UNKNOWN
}
```

Preserve provider-specific detail:

```text
74'
Q3 04:12
Top 7th
Set 3
Rain Delay
```

as display detail.

---

# 58. Provider event coverage

Different providers cover different sports/leagues.

A configured provider must not suppress valid EPG events outside its coverage.

Example:

```text
Provider covers:
football
basketball

EPG additionally contains:
WWE
local hockey
regional motorsport
```

Automatic mode should retain unmatched high-confidence EPG events.

---

# 59. Capability-specific user choice

Advanced Settings can expose:

```text
Event discovery
Automatic / EPG / Provider A

Live scores
Automatic / Off / Provider A / Provider B

Standings
Automatic / Off / Provider B

Team metadata
Automatic / Off / Provider A
```

Only show providers that implement the capability.

---

# 60. Simple mode vs advanced mode

Default Settings should stay understandable.

## Simple mode

```text
Sports Data Providers
+ Add provider

Use providers automatically  [ON]
```

## Advanced

```text
Event source
Score source
Standings source
Metadata source
Provider priority
Refresh settings
```

Do not force ordinary users to understand capability routing.

---

# 61. Quota display

If the provider exposes quota headers or account usage information, optionally show:

```text
Requests remaining
Reset time
Rate-limited until
```

Do not invent quota values when the API does not provide them.

Avoid polling merely to display quota.

---

# 62. User-controlled polling

For expensive providers, optionally allow:

```text
Live score refresh:
Automatic
30 seconds
1 minute
2 minutes
5 minutes
```

But adapter/provider minimums and rate-limit safety must override unsafe selections.

Do not allow users to configure request rates that obviously violate provider rules.

---

# 63. Background refresh

Do not add aggressive background polling in the first implementation.

Initial behavior should focus on:

```text
Sports screen open
event details open
user refresh
normal scheduled cache refresh
```

Background notifications/watchers can be designed later.

---

# 64. Offline behavior

When offline:

```text
EPG Sports remains available from local data
cached external metadata remains available where appropriate
live score displays stale/last-updated state or is hidden
```

Do not block Sports because a configured provider cannot be reached.

---

# 65. Attribution

Some providers may require attribution.

Provider descriptor/configuration should support:

```kotlin
data class ProviderAttribution(
    val text: String,
    val url: String?,
    val placement: AttributionPlacement
)
```

Do not hide required attribution.

Settings should clearly identify the data source where required.

---

# 66. Artwork policy

Do not assume a sports-data API's logos may automatically be redistributed or cached indefinitely.

Each provider integration should document:

```text
logo/artwork rights
hotlinking rules
cache rules
attribution
```

If unclear, do not ship artwork use merely because the JSON includes a URL.

---

# 67. Terms/commercial review per provider

Before adding an official built-in adapter:

- [ ] verify developer/API documentation;
- [ ] verify commercial app use;
- [ ] verify BYOK use is allowed;
- [ ] verify client-side key usage expectations;
- [ ] verify redistribution/display rights;
- [ ] verify caching rules;
- [ ] verify attribution;
- [ ] verify rate-limit behavior;
- [ ] verify logo/artwork terms;
- [ ] document source URLs/date of review.

This review is separate from the provider's technical adapter.

---

# 68. Testing architecture

Every provider adapter needs:

```text
parser fixture tests
error tests
auth tests where possible
pagination tests
status mapping tests
time parsing tests
capability tests
```

Use captured/anonymized static fixtures.

Do not hit live provider APIs in normal unit tests.

---

# 69. Contract tests

Create provider-neutral contract tests.

Example:

Every `EventProvider` must:

- return stable provider event IDs;
- convert timestamps to UTC `Instant`;
- not throw on one malformed event if others are usable;
- expose declared capabilities accurately;
- sanitize errors;
- never expose credentials in exceptions.

Every `LiveScoreProvider` must:

- associate results with a provider event ID or matchable identity;
- report `updatedAt` where possible;
- normalize game state;
- tolerate missing score fields.

---

# 70. Event resolver tests

Fixtures should cover:

```text
EPG + API exact match
EPG coverage starts before kickoff
API event with no EPG match
EPG event with no API match
team alias differences
league alias differences
kickoff shifted by 5/15/60 minutes
same teams twice in one day
multilingual EPG
provider duplicate events
replay vs live event
F1 race vs qualifying
fight card vs prelims
```

---

# 71. Broadcast resolver tests

Cover:

```text
API broadcaster matches exact local channel
API broadcaster has no local channel
EPG mapping beats fuzzy broadcaster
event-named channel fallback
remembered user choice
wrong-region ambiguous broadcaster
multiple local variants
no playable result
```

---

# 72. Provider Settings tests

Cover:

```text
add
edit
disable
remove
test connection
bad key
bad base URL
rate limit
OAuth cancelled
OAuth refresh failure
config migration
secret redaction
```

---

# 73. Custom-provider tests

Cover:

```text
header API key
query API key
Bearer
Basic
array JSON root
nested JSON root
missing field
bad timestamp
bad JSON
huge response
unsupported pagination
mapping validation
```

---

# 74. Diagnostics

Add provider diagnostics behind a dedicated debug tag or the existing Sports diagnostics mechanism.

Useful:

```text
provider instance ID
capability
request type
HTTP status
duration
cache hit/miss
events parsed
events rejected
event match confidence
source merge decision
rate-limit state
```

Never log:

```text
API key
Bearer token
password
OAuth refresh token
full sensitive URL query
credentials
```

---

# 75. Migration from current Sports Hub

Do not rewrite current Sports events immediately.

Recommended migration:

## Step 1

Introduce provider interfaces with no active providers.

Existing EPG flow remains unchanged.

## Step 2

Wrap current EPG event production as a conceptual `EpgEventSource` / resolver input.

Do not force a large refactor if a lightweight adapter suffices.

## Step 3

Introduce source provenance.

## Step 4

Add ProviderEvent model + event matcher.

## Step 5

Add one legitimate BYOK provider as the reference adapter.

## Step 6

Add source strategy Settings.

## Step 7

Add live-score enrichment.

## Step 8

Add Custom Sports Provider.

Do not implement five providers at once before the abstraction is proven.

---

# 76. First provider strategy

Choose one provider that has:

```text
clear documentation
user API keys
reasonable developer terms
events
live scores
good sport coverage
stable schema
```

Use it to validate the framework.

The purpose of the first adapter is not maximum provider count.

It is to prove:

```text
BYOK config
auth
events
matching
scores
caching
error handling
settings
```

Then generalize.

---

# 77. Recommended implementation phases

## Phase 3.1 — Provider core

- [x] `SportsCapability`
- [x] provider descriptor
- [x] provider instance/config model
- [x] secure credential storage
- [x] provider registry
- [x] provider health/error model
- [x] common HTTP client behavior
- [x] provider configuration versioning
- [x] tests

Implemented 2026-10-17. See [Phase 3 implementation record](Sports-PLAN-3-IMPLEMENTATION.md)
for boundaries, persistence/transport policy, verification, and remaining phases.

No external provider required yet.

---

## Phase 3.2 — Capability interfaces

- [x] `EventProvider`
- [x] `LiveScoreProvider`
- [x] `StandingsProvider`
- [x] `BroadcastProvider`
- [x] metadata provider interfaces
- [x] provider-neutral DTOs
- [x] contract tests

Implemented 2026-10-17. See [Phase 3 implementation record](Sports-PLAN-3-IMPLEMENTATION.md)
for contracts, fixtures, validation, and the Phase 3.3 handoff.

---

## Phase 3.3 — Source provenance and event resolver

- [x] `SportsSourceRef`
- [x] provider event link model
- [x] source merge policy
- [x] event resolver
- [x] event/provider identity matching
- [x] event-link cache
- [x] eventStart vs broadcastStart distinction
- [x] tests

The Phase 3.3 implementation and focused resolver tests are complete. See
`Sports-PLAN-3-IMPLEMENTATION.md` for coverage and validation.

---

## Phase 3.4 — Provider settings

- [x] Data Providers list
- [x] Add provider
- [x] credential entry
- [x] base URL override where allowed
- [x] Test connection
- [x] disable/remove
- [x] capability display
- [x] secret-safe UI
- [x] tests

Focused ViewModel tests cover saving credentials in the dedicated store and
testing a draft connection without persisting its credentials.

---

## Phase 3.5 — First official BYOK adapter

- [x] provider review/documentation
- [x] API client
- [x] auth
- [x] events
- [x] live scores
- [x] mappings
- [x] cache policy
- [x] error policy
- [x] fixtures/tests

Implemented 2026-10-17 with TheSportsDB as the single reference adapter. See
[`sports/providers/thesportsdb.md`](sports/providers/thesportsdb.md) for the
technical and terms review. Its current scope is events, live scores, and game
status; source selection and Sports Hub presentation remain in later phases.
Plan 4 still owns the production release/compliance gate and additional
provider-specific capabilities.

Do not add more providers until this adapter validates the abstractions.

---

## Phase 3.6 — Event source selection

- [x] Automatic
- [x] EPG
- [x] configured EventProvider
- [x] provider capability filtering
- [x] fallback to EPG in Automatic on provider failure where safe
- [x] preserve unmatched EPG events outside API coverage
- [x] tests

Phase 3.6 now stores source selection independently from provider credentials,
supports per-account league scopes for APIs that require them, and routes event
requests through enabled providers that declare `EVENTS`. See
[`Sports-PLAN-3-IMPLEMENTATION.md`](Sports-PLAN-3-IMPLEMENTATION.md) for behavior
and validation details.

---

## Phase 3.7 — API-first broadcast matching

- [x] API event -> EPG event matching
- [x] provider broadcaster -> local broadcaster matching
- [x] local EPG mapping remains preferred where available
- [x] Provider-neutral fallback channel matcher from Plan 2
- [x] manual correction integration
- [x] tests

Implemented 2026-10-17. Provider event broadcasters now participate in the
existing local-channel fallback and manual correction flow. See
[`Sports-PLAN-3-IMPLEMENTATION.md`](Sports-PLAN-3-IMPLEMENTATION.md) for matching
rules and verification.

---

## Phase 3.8 — Live-score enrichment

- [x] score source selection
- [x] event-link cache reuse
- [x] score/status/clock UI
- [x] stale-state handling
- [x] live refresh policy
- [x] quota-aware caching
- [x] provider failure degradation
- [x] tests

Implemented 2026-10-17. See
[`Sports-PLAN-3-IMPLEMENTATION.md`](Sports-PLAN-3-IMPLEMENTATION.md) for
selection, refresh, failure, and verification details.

---

## Phase 3.9 — Standings and metadata

- [x] standings source selection
- [x] sport-aware tables
- [x] team/league metadata enrichment
- [x] attribution
- [x] cache policy
- [x] tests

Implemented 2026-10-17. Provider standings and metadata can be selected
independently, preserve provider provenance, and degrade to cached stale data
when a provider fails. See
[`Sports-PLAN-3-IMPLEMENTATION.md`](Sports-PLAN-3-IMPLEMENTATION.md) for routing,
cache policy, localization, and verification details.

---

## Phase 3.10 — Custom Sports Provider

- [x] custom provider config
- [x] auth modes
- [x] endpoint templates
- [x] JSON mapping
- [x] mapping validation
- [x] safe response limits
- [x] event support
- [x] live-score support
- [x] optional standings reviewed and deferred because this mapping model does not cleanly represent provider-specific table structures
- [x] tests

Implemented 2026-10-17. The generic adapter supports declarative HTTPS JSON event and optional live-score mappings, encrypted definitions and credentials, bounded selectors and response mapping, five authentication modes, and same-origin endpoint templates. Pagination, arbitrary headers/scripts, OAuth, and standings remain out of scope for this first version. See [`sports/custom-provider.md`](sports/custom-provider.md) for the configuration schema and current limits.

---

## Phase 3.11 — Multiple-provider routing

- [x] provider priority
- [x] capability-specific provider selection
- [x] Automatic provider choice
- [x] field/source provenance
- [x] conflict policy
- [x] partial provider failure
- [x] tests

Implemented across Phases 3.6–3.10. Event, score, standings, and metadata each use capability-aware account selection. Automatic selection follows provider priority and falls through typed failures; explicit account choices remain pinned. Event identity matching controls merges, EPG playback fields remain authoritative, and merged provider fields retain source provenance. Scores, standings, and metadata keep their own retry and stale-cache behavior. See [`Sports-PLAN-3-IMPLEMENTATION.md`](Sports-PLAN-3-IMPLEMENTATION.md) for routing and conflict details.

---

## Phase 3.12 — Production hardening

- [x] Android TV performance
- [x] large event-set performance
- [x] provider cache validation
- [x] secret redaction audit
- [x] offline behavior
- [x] rate-limit behavior
- [x] settings UX review
- [ ] Terms/attribution checklist for each built-in adapter — deferred to Plan 4's built-in-provider release gate
- [x] Play-build dependency review

The Android TV measurements and navigation review are recorded in
[`Sports-PERFORMANCE.md`](Sports-PERFORMANCE.md) and
[`Sports-UI-REVIEW.md`](Sports-UI-REVIEW.md). The current emulator session exposes
`emulator-5554`; the earlier `emulator-5590` device is not attached in this
session, so the existing device evidence was retained rather than repeated.
Provider cache, offline, rate-limit, redaction, and large-feed evidence is
recorded in [`Sports-PLAN-3-IMPLEMENTATION.md`](Sports-PLAN-3-IMPLEMENTATION.md).
Provider terms and attribution remain in Plan 4, whose release gate owns review
of each built-in adapter.

---

# 78. What is explicitly not in Plan 3

Do not implement:

```text
StreamVault-hosted shared API keys
StreamVault-owned sports backend
pirated sports streams
automatic scraping
DRM/token bypass
undocumented provider adapters disguised with codenames
arbitrary user JavaScript
arbitrary shell commands
API keys logged to diagnostics
external provider controlling playback URLs
```

---

# 79. Success criteria

Plan 3 succeeds when all of the following are true.

## No provider

```text
EPG Sports works exactly as before.
```

## Score-only provider

```text
EPG provides event/channel
API adds score/status
```

## Event provider without broadcasters

```text
API provides fixtures/events
EPG + Plan 2 channel matcher finds playback
```

## Event provider with broadcasters

```text
API provides event + broadcaster
EPG/local channels corroborate and resolve playback
```

## API event absent from EPG

```text
event appears
fallback channel matching runs
manual channel selection available
event remains visible even if no channel is found
```

## Weak EPG

```text
API identity repairs generic EPG metadata
without losing EPG broadcast window/channel
```

## Multiple providers

```text
capability routing is deterministic
source provenance is preserved
one provider failure does not break Sports
```

## Custom provider

```text
advanced user can configure a documented/self-hosted JSON sports source
without StreamVault shipping a new adapter
```

---

# 80. Final design principles

1. **The Sports Hub must always work without an external API.**
2. **Providers are capability-based, not all-or-nothing.**
3. **Event discovery may be EPG-first or API-first.**
4. **Playback always comes from the user's own StreamVault TV sources.**
5. **An API broadcaster is metadata, not a stream.**
6. **EPG broadcast time and real event kickoff are separate concepts.**
7. **Multiple sources must preserve provenance.**
8. **User-supplied credentials belong to the user and use their quota.**
9. **A provider failure must degrade gracefully.**
10. **Plan 2 normalization/matching is reused, not duplicated.**
11. **Generic Custom REST/JSON support is preferable to hidden provider-specific reverse engineering.**
12. **Every built-in provider requires a technical and terms/commercial review.**
13. **Do not sacrifice the existing local Sports experience to add API features.**
14. **Automatic should be the simple default; advanced source routing should remain available.**

---

# 81. Immediate execution order

The agent should **not start Plan 3 immediately if Plan 2's core improvements are still incomplete**.

Recommended order:

```text
1. Finish Plan 2 verification + tests
2. Finish Plan 2 event model / non-team parsing / dedupe
3. Finish Plan 2 fallback channel matching + manual correction
4. Verify Plan 2 on a real provider/EPG
5. Begin Plan 3 provider core
6. Implement one legitimate BYOK provider
7. Add API-first event mode
8. Add live scores
9. Add Custom REST/JSON
10. Add multiple-provider routing
```

This minimizes rework.

The most important dependency is:

> **Do not build external event matching until the canonical local event identity and normalization system are trustworthy.**

That identity layer is what allows EPG, API events, live scores, broadcaster metadata, and user channel mappings to meet cleanly.
