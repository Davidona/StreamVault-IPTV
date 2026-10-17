# StreamVault Sports Hub — PLAN 4: Built-in Sports API Providers

**Research date:** 2026-10-16  
**Depends on:** Sports Plan 2 and Sports Plan 3  
**Purpose:** Define which real sports-data APIs StreamVault should support as first-class built-in providers, what each provider is allowed to do, what the user must supply, and the implementation/release order.

> This document is an engineering/product plan based on the providers' published documentation and terms reviewed on the research date above. It is not legal advice. Provider terms can change; every built-in provider must pass the release-gate checklist in this document again before shipping.

---

# 0. Why this is Plan 4

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
Generic provider framework
(BYOK, capability routing, API-first/EPG-first/Automatic)
        ↓
PLAN 4
Concrete built-in provider adapters
(real providers, real auth, real terms)
```

Do **not** call this Plan 2.5.

Plan 2 improves the local Sports engine.

Plan 3 defines the generic provider system.

Plan 4 fills that system with vetted provider adapters.

---

# 1. Core product decision

StreamVault should support two kinds of sports-data integrations.

## 1.1 Built-in providers

StreamVault knows:

```text
provider name
base URL
authentication method
endpoints
pagination
response schema
capabilities
cache policy
rate-limit behavior
error behavior
attribution requirements
```

The user supplies only the credentials/account information required by that provider.

Example:

```text
Add Provider
    ↓
TheSportsDB
    ↓
API key: ••••••••••
    ↓
Test connection
    ↓
Connected
```

No endpoint entry.

No JSON mapping.

No technical configuration for ordinary users.

## 1.2 Custom REST/JSON provider

This remains the advanced Plan 3 option for unsupported/self-hosted APIs. The settings form offers an editable starter mapping and setup instructions; the complete schema and examples are in [`sports/providers/custom-json.md`](sports/providers/custom-json.md).

Users may configure:

```text
base URL
auth
endpoint templates
field mappings
```

The built-in provider list must **not** be replaced by a generic endpoint editor.

---

# 2. Provider status taxonomy

Every investigated provider belongs to one of these groups.

## GREEN — first-class candidate

Published terms/docs are sufficiently clear that the provider is a strong candidate for a built-in StreamVault BYOK adapter, subject to the normal release review.

## GREEN-WITH-GATE — strong candidate, one material question remains

Technically and commercially promising, but StreamVault should obtain clarification or satisfy a specific term before enabling the adapter in production.

These colors track terms and release rights, not test maturity. The app marks TheSportsDB and Highlightly as **Beta** while live-account validation is unavailable; Beta does not alter either provider’s compliance gate.

## YELLOW — technically useful but rights/terms require caution

May be implemented behind the generic architecture, but do not ship as an ordinary built-in provider until the outstanding terms/licensing issue is resolved.

## ENTERPRISE — contract-specific

Official commercial API, but production rights depend on an individual commercial agreement/order form. A random consumer API key is not enough.

## RED — do not ship built-in adapter

Undocumented/reverse-engineered/scraped source or otherwise outside the clean provider strategy.

---

# 3. Summary matrix

| Provider | Status | API-first events | Live scores | Standings | Broadcasters/TV | Auth | Main release requirement |
|---|---|---:|---:|---:|---:|---|---|
| TheSportsDB | **GREEN** | Yes | Yes (premium) | Yes | **Yes** | V2 `X-API-KEY` | Paid subscription for app-store apps + attribution |
| BALLDONTLIE | **GREEN** | Yes | Yes | Yes | No known TV feed | `Authorization` API key | Respect plan capabilities; no raw-data resale |
| Highlightly | **GREEN** | Yes | Yes | Yes | No dedicated TV feed found | `x-rapidapi-key` | Avoid assuming logo/image rights |
| Sportmonks | **GREEN-WITH-GATE** | Yes | Yes | Yes | **Yes** | API token / `Authorization` | Confirm direct BYOK native-client use; protect token |
| football-data.org | **GREEN-WITH-GATE** | Yes | Yes on applicable plans | Yes | No | `X-Auth-Token` | Attribution; one-application rule; no logo rights |
| OddsPapi | **GREEN-WITH-GATE** | Yes | Yes | Limited/no general standings focus | No | `apiKey` | Use fixtures/scores only; verify app-display use |
| API-SPORTS | **YELLOW** | Yes | Yes | Yes | Not a core TV source | `x-apisports-key` | Terms say provider does not grant publication rights for competitions |
| SportDB.dev | **YELLOW** | Yes | Yes | Yes | No known TV feed | `X-API-Key` | Production is paid; downstream third-party rights remain user's responsibility |
| SportDevs | **YELLOW / PENDING** | Yes | Yes | Yes | Unknown | Bearer token | Current terms page must be successfully reviewed |
| SportsDataIO | **ENTERPRISE** | Yes | Yes | Yes | Depends on feed | `Ocp-Apim-Subscription-Key` | Commercial contract must authorize StreamVault/property/use case |
| Sportradar | **ENTERPRISE** | Yes | Yes | Yes | Product-dependent | API key | Production Order Form / authorized Properties |
| OpenLigaDB | **OPTIONAL OPEN DATA** | Yes, limited | Results/live-ish depending feed | Yes | No | None | ODbL attribution/share-alike obligations |
| ESPN site API | **RED** | Yes technically | Yes | Yes | Some | None | Undocumented site API; no clean third-party commercial license |
| scoreboard-api | **RED** | Yes technically | Yes | No | No | None | Scrapes ESPN HTML |
| SportTime.live | **RED** | Site only | N/A | N/A | Yes on site | N/A | No supported public API identified; do not scrape |
| Apple/private sports feeds | **RED** | Technically possible | Possible | Possible | Possible | Private | Reverse-engineered/private provider access |
| Unauthorized stream resolvers | **RED** | N/A | N/A | N/A | Streams | Various | Outside StreamVault's clean Sports-data architecture |

---

# 4. Recommended implementation waves

## Wave 1 — prove the framework with the cleanest/highest-value adapters

Implement:

1. **TheSportsDB**
2. **BALLDONTLIE**
3. **Highlightly**

Why these three:

```text
TheSportsDB
    -> broadcaster/TV metadata + simple sports model

BALLDONTLIE
    -> very clear commercial data-use terms + many distinct sport APIs

Highlightly
    -> one multi-sport key + uniform all-sports structure
```

Together they test:

```text
header auth
different capability sets
provider-event normalization
provider-specific sport routing
TV broadcaster metadata
live-score enrichment
standings
quota handling
API-first mode
```

Do not implement ten providers before these three validate Plan 3.

---

## Wave 2 — high-value specialist providers

Implement after Wave 1 architecture is stable:

4. **Sportmonks** — after direct-native-BYOK release clarification
5. **football-data.org**
6. **OddsPapi** — fixtures/scores only

---

## Wave 3 — conditional providers

Implement adapters only after the release gate is cleared:

7. **API-SPORTS**
8. **SportDB.dev**
9. **SportDevs**

The code may be prototyped using their documented APIs, but the production adapter must remain disabled/not shipped until its outstanding rights/terms issue is resolved.

---

## Wave 4 — enterprise adapters

Only when there is actual user/customer demand:

10. **SportsDataIO**
11. **Sportradar**

These are not normal consumer BYOK integrations.

A valid API key is not sufficient by itself; the user's commercial agreement must authorize the StreamVault use case/property.

---

## Optional open-data adapter

12. **OpenLigaDB**

Useful mostly as:

```text
free/open football fixture/results source
regional German-football coverage
testing/reference provider
```

Do not prioritize above the commercial/multi-sport providers.

---

# 5. Provider 1 — TheSportsDB

**Status:** GREEN  
**Priority:** Wave 1  
**Recommended role:** Events + live scores + metadata + **broadcast/TV matching**

Official sources reviewed:

- https://www.thesportsdb.com/docs_terms_of_use.php
- https://www.thesportsdb.com/documentation
- https://www.thesportsdb.com/docs/api

## 5.1 Why it is especially valuable to StreamVault

TheSportsDB is one of the few providers we found that exposes explicit TV/broadcast information.

Relevant capabilities include:

```text
event lookup
schedules
livescores
team/league metadata
event TV broadcasts
TV schedule by day
TV filtering by channel/country/sport
```

This makes it useful not only for scoring but also for:

```text
API event
    ↓
TheSportsDB broadcaster
    ↓
StreamVault broadcaster normalization
    ↓
user's M3U/Xtream channel
```

## 5.2 Terms/release notes

Current terms state, in substance:

- API-returned content may be copied/modified through official endpoints.
- Website scraping is not allowed.
- Free API usage is for development projects.
- **Apps cannot be published to an app store using the free tier.**
- Paid API subscribers may develop apps/services within rate limits.
- TheSportsDB must be mentioned as the data source.
- Image licensing/third-party rights remain a separate issue.

Therefore:

> For Play Store StreamVault support, require a user's **paid/premium TheSportsDB key** for production data integration.

Do not silently fall back to the shared free `123` key for the paid app.

## 5.3 Auth

Prefer V2:

```text
X-API-KEY: <user key>
```

Do not prefer V1 URL-key authentication for new StreamVault work.

## 5.4 Provider capabilities

Implement:

```text
EVENTS
LIVE_SCORES
GAME_STATUS
STANDINGS
TEAM_METADATA
LEAGUE_METADATA
BROADCASTERS
```

Do not implement video highlights initially.

## 5.5 StreamVault configuration

UI:

```text
TheSportsDB

Premium API key
[••••••••••••••]

[Test connection]

Attribution:
Data provided by TheSportsDB
```

## 5.6 Test connection

Test:

```text
authentication
V2 access
basic event endpoint
live-score endpoint capability
TV endpoint capability
```

Return:

```text
Connected
Premium V2
Events ✓
Live scores ✓
TV broadcasts ✓
```

## 5.7 Attribution

Implement provider attribution infrastructure from Plan 3.

Display TheSportsDB attribution in an appropriate visible Sports/About/provider location according to its terms.

## 5.8 Artwork

Do not assume all team/league images may be used commercially merely because the API returns a URL.

Prefer:

```text
EPG/channel art already used by StreamVault
```

until image rights are separately reviewed.

---

# 6. Provider 2 — BALLDONTLIE

**Status:** GREEN  
**Priority:** Wave 1  
**Recommended role:** Events + live scoring + standings + broad multi-sport metadata

Official sources reviewed:

- https://www.balldontlie.io/terms.html
- https://www.balldontlie.io/docs
- https://www.balldontlie.io/account/

## 6.1 Why it is a strong candidate

Its current terms contain unusually explicit downstream permission.

They state that data may be used for lawful:

```text
personal purposes
commercial purposes
media/publishing
analytics
fantasy/gaming
AI/ML
lawful betting products
redistribution
```

without separate permission/attribution by default, while prohibiting:

```text
competing with BALLDONTLIE
selling/reselling the raw data unchanged
sharing/reselling API access/keys outside permitted plans
```

That is a much cleaner foundation than an undocumented site endpoint.

## 6.2 Coverage

Current docs expose 20+ leagues/sports, including examples across:

```text
NBA / basketball leagues
EPL
La Liga
Serie A
Champions League
Bundesliga
Ligue 1
MLS
World Cup
NFL
NCAAF
MLB
NHL
ATP
WTA
PGA
Formula 1
MMA
esports
```

Coverage varies by sport and plan.

## 6.3 Auth

Use the documented API key authorization header.

StreamVault settings should only ask for the user's key.

## 6.4 Account introspection

Strongly consider using the Account API to detect:

```text
subscription tier
sports subscribed
available capabilities
rate limits
```

This is better than asking the user to tell us which plan they bought.

## 6.5 Provider capabilities

Capabilities vary per sport.

Adapter architecture:

```text
BalldontlieProvider
    |
    +-- sport-specific capability resolver
```

Do not declare every capability globally.

Example:

```text
NFL free
    games ✓
    standings maybe plan-dependent

MMA
    events ✓
    fights paid
    rankings paid

some basketball leagues
    games paid
    standings free/paid depending API
```

Use provider capability discovery rather than hardcoded assumptions where possible.

## 6.6 No broadcaster dependency

No general TV-broadcaster API was identified.

Therefore:

```text
BALLDONTLIE event
    ↓
Plan 2/3 broadcast resolver
    ↓
EPG
temporary event channels
manual selection
```

## 6.7 Artwork

Treat logos/images as separately reviewable.

Do not make remote provider artwork necessary for the adapter.

---

# 7. Provider 3 — Highlightly

**Status:** GREEN  
**Priority:** Wave 1  
**Recommended role:** Unified multi-sport events + scores + standings + metadata

Official sources reviewed:

- https://highlightly.net/terms/
- https://highlightly.net/sport-api/
- https://highlightly.net/sport-api/documentation/

## 7.1 Terms

Current terms explicitly state that:

```text
distribution, transfer, and storage of API data are allowed
data may be used in applications/products
```

while prohibiting:

```text
reselling/sublicensing direct API access
proxy/pass-through API services
systematic extraction to create a competing database/service
```

Visual assets/logos remain the user's responsibility for commercial rights.

This is suitable for a normal application-data adapter.

## 7.2 Coverage

The current All Sports API exposes a shared base with multiple sport prefixes, currently documented for:

```text
football
american-football
nba
baseball
nhl
cricket
basketball
hockey
rugby
volleyball
handball
```

Common capabilities include:

```text
matches / schedules / live scores
standings
teams
players
statistics
lineups
```

Coverage differs by sport.

## 7.3 Auth

Direct Highlightly:

```text
x-rapidapi-key: <user key>
```

The same key can be used against the Highlightly All Sports base for subscribed products.

RapidAPI and direct Highlightly accounts are separate.

## 7.4 StreamVault implementation

Support two account modes only if necessary:

```text
Highlightly direct
RapidAPI
```

Prefer direct Highlightly initially.

Avoid making RapidAPI mandatory.

## 7.5 Provider configuration

```text
Highlightly

API key
[••••••••]

Account source
● Highlightly
○ RapidAPI
```

If direct-only initially, omit the account-source choice.

## 7.6 Quota

Read quota headers and expose:

```text
remaining requests
plan tier
```

when available.

Implement capability-specific caching because one All Sports quota is shared across sport prefixes.

## 7.7 Highlights/videos

Do not implement video-highlight playback in the first adapter.

That introduces separate content/geo-rights questions unrelated to the core Sports Hub.

## 7.8 Artwork

Do not treat Highlightly image URLs as automatically commercially licensed.

Prefer local/EPG artwork unless rights are verified.

---

# 8. Provider 4 — Sportmonks

**Status:** GREEN-WITH-GATE  
**Priority:** Wave 2 after confirmation  
**Recommended role:** Football API-first events + live scoring + standings + **TV stations**

Official sources reviewed:

- https://www.sportmonks.com/terms-of-service/
- https://www.sportmonks.com/football-api/
- https://www.sportmonks.com/glossary/learn-how-to-retrieve-football-broadcast-data-using-the-sportmonks-football-api-v3-access-tv-stations-broadcaster-logos-and-fixtures-showing-on-each-channel/
- https://docs.sportmonks.com/v3/welcome/authentication

## 8.1 Terms

Sportmonks explicitly says its data may be used to build:

```text
apps
websites
games
other products
```

and commercial monetization of the resulting product is generally allowed.

It prohibits direct resale of its data as a competing data product.

Its enterprise summary also says customers may display and use data in their own product.

This makes it a strong commercial candidate.

## 8.2 Why it is especially valuable

Sportmonks exposes TV-station records linked to fixtures.

This directly supports:

```text
Sportmonks fixture
    ↓
TV stations
    ↓
canonical broadcaster names
    ↓
StreamVault local channel resolver
```

For football, this is arguably the richest provider we found for StreamVault's exact use case.

## 8.3 Release gate: direct BYOK client use

Sportmonks documentation recommends keeping API tokens behind middleware/backend rather than directly in a frontend.

StreamVault's model is different:

```text
user enters their own token
token stored locally
calls originate from user's device
```

Before enabling this adapter in the Play build:

- [ ] ask Sportmonks whether user-owned tokens may be used directly from a native third-party client;
- [ ] confirm that this does not violate account/domain restrictions;
- [ ] document their response.

If confirmed, ship.

If not confirmed, leave Sportmonks available only through:

```text
Custom provider / user's own proxy
```

or another architecture they approve.

## 8.4 Auth

Use header auth rather than query-string token:

```text
Authorization: <user token>
```

Store locally using secure credential storage.

## 8.5 Capabilities

Implement:

```text
EVENTS
LIVE_SCORES
GAME_STATUS
STANDINGS
TEAM_METADATA
LEAGUE_METADATA
BROADCASTERS
```

Sportmonks is football-centric in this adapter.

Other Sportmonks products such as F1/cricket should be separate capability modules unless their APIs are structurally compatible enough to share code.

---

# 9. Provider 5 — football-data.org

**Status:** GREEN-WITH-GATE  
**Priority:** Wave 2  
**Recommended role:** Football fixtures + live scores + standings

Official sources reviewed:

- https://www.football-data.org/about
- https://www.football-data.org/pricing
- https://www.football-data.org/documentation/api

## 9.1 Terms

Important requirements:

- API key applies to a single application.
- Credentials must be kept confidential.
- Attribution is required:
  `Football data provided by the Football-Data.org API`
- Graphics/team logos/profile photos require separate permission from their legal owners.
- After subscription cancellation, previously obtained football data may no longer be referenced through the user's service.
- Free/paid plans differ in live-score freshness, competitions, and call rate.

## 9.2 BYOK interpretation

A user supplying a key for StreamVault should treat StreamVault as the one application associated with that key.

Before production release:

- [ ] confirm the provider is comfortable with user-owned API keys entered into the StreamVault native client;
- [ ] document attribution placement.

## 9.3 Auth

Use the documented API token header (`X-Auth-Token` in the API documentation).

## 9.4 Capabilities

Implement:

```text
EVENTS
LIVE_SCORES
GAME_STATUS
STANDINGS
TEAM_METADATA
LEAGUE_METADATA
```

No broadcaster capability.

## 9.5 Subscription cancellation behavior

Do not persist provider-derived data indefinitely.

When provider access is removed/invalid:

```text
expire cached provider event/standings data
retain only local EPG-derived Sports data
```

## 9.6 Artwork

Do not use football-data.org-provided team logos in the commercial app unless separate rights are obtained.

---

# 10. Provider 6 — OddsPapi

**Status:** GREEN-WITH-GATE  
**Priority:** Wave 2  
**Recommended role:** Multi-sport fixtures + score enrichment

Official sources reviewed:

- https://oddspapi.io/en/legal/terms
- https://oddspapi.io/en/docs
- https://oddspapi.io/en/docs/get-fixtures
- https://oddspapi.io/en/docs/get-scores

## 10.1 Why support it at all

Although primarily an odds provider, it exposes useful non-odds data:

```text
sports
tournaments
fixtures
participants
scores
fixture status
```

This can provide an event/score source over a wide sports catalogue.

## 10.2 Terms

Terms prohibit:

```text
reselling/repackaging/redistributing data as a standalone product
API key sharing
abuse/scraping
```

and refer to third-party tools built using the data.

Before making it a normal built-in provider:

- [ ] obtain/confirm that displaying fixtures/scores inside a paid media-player app is within their intended use;
- [ ] document response.

## 10.3 Critical product restriction

StreamVault should **not implement odds/betting features** as part of this adapter.

Only expose:

```text
EVENTS
LIVE_SCORES
GAME_STATUS
basic participant/tournament metadata
```

Do not expose:

```text
bookmaker odds
betting markets
player props
settlements
```

This keeps the provider integration aligned to the Sports Hub rather than adding gambling functionality.

## 10.4 Auth

Current docs place `apiKey` in the request query.

Redact it from all logs and diagnostics.

If OddsPapi later supports secure header auth, prefer the header.

---

# 11. Provider 7 — API-SPORTS / API-Football family

**Status:** YELLOW  
**Priority:** Wave 3 only after legal/product review  
**Recommended role if cleared:** events + live scores + standings across multiple sports

Official sources reviewed:

- https://api-sports.io/terms
- https://api-sports.io/
- https://api-sports.io/documentation/football/v3

## 11.1 Technical fit

Very good technically.

Features:

```text
football
basketball
NBA
NFL/NCAA
baseball
hockey
Formula 1
MMA
AFL
handball
and others
```

Each API has a documented official endpoint and user key.

Auth example:

```text
x-apisports-key: <user key>
```

## 11.2 Why it is YELLOW

The terms simultaneously say the data is provided for building projects such as applications/websites, but explicitly state that API-SPORTS does **not** provide the user a license to publish data for competitions where third-party rights may apply.

The customer is responsible for obtaining required permissions from:

```text
leagues
federations
event organizers
other rights holders
```

The same warning applies to logos/images/trademarks.

Therefore an API key alone is **not** a clean rights grant.

## 11.3 Plan

We may implement the adapter internally after the provider framework exists, but do not enable it in the normal Play Store provider catalogue until one of these is true:

1. API-SPORTS provides written clarification that StreamVault's factual scores/fixtures display use is permitted for the intended competitions; or
2. we complete a legal review defining what can be displayed safely; or
3. the user/provider has a contractual license that clearly covers the use.

## 11.4 If eventually shipped

Default to text/factual data.

Do not automatically use:

```text
logos
player images
league marks
```

without a separate rights policy.

---

# 12. Provider 8 — SportDB.dev

**Status:** YELLOW  
**Priority:** Wave 3  
**Recommended role if cleared:** multi-sport events + live scores + standings

Official sources reviewed:

- https://sportdb.dev/
- https://sportdb.dev/tos.html

## 12.1 Technical capabilities

Current API advertises:

```text
live scores
fixtures
standings
match details
lineups
stats
clubs
players
transfers
multiple sports
```

Authentication uses an API key.

## 12.2 Terms issue

Terms state:

```text
free tier for testing
production requires paid plan
no API resale/sublicensing
downstream use must comply with third-party terms/IP rights
```

They do not give as explicit a downstream commercial-display permission as BALLDONTLIE/Highlightly.

## 12.3 Release gate

Before built-in release:

- [ ] obtain written confirmation for paid commercial app display;
- [ ] confirm end-user BYOK through a third-party native app;
- [ ] clarify any attribution/artwork requirements.

Until then:

```text
Custom Sports Provider
```

may be used by advanced users who configure their own compatible endpoint.

---

# 13. Provider 9 — SportDevs

**Status:** YELLOW / PENDING TERMS  
**Priority:** Wave 3 only after terms verification

Official sources reviewed:

- https://docs.sportdevs.com/docs
- https://www.sportdevs.com/... product pages

The documentation links to:

```text
https://sportdevs.com/terms
```

but the terms page could not be reliably fetched during this review.

## 13.1 Technical fit

SportDevs markets use cases including:

```text
live score apps
sports news sites
mobile sports apps
data analysis
betting platforms
```

and offers broad sport coverage with REST/WebSocket APIs.

Auth:

```text
Bearer token
```

## 13.2 Release rule

Do **not** ship a built-in provider based only on marketing examples.

Required first:

- [ ] fetch/read current Terms of Service successfully;
- [ ] verify commercial display/use;
- [ ] verify BYOK/native-client rules;
- [ ] verify redistribution/data rights;
- [ ] verify image/logo terms.

---

# 14. Provider 10 — SportsDataIO

**Status:** ENTERPRISE  
**Priority:** Wave 4, demand-driven  
**Recommended role:** enterprise-grade events/scores/standings/metadata

Official sources reviewed:

- https://sportsdata.io/help/data-rights-and-licensing-questions
- https://sportsdata.io/developers
- https://sportsdata.io/terms-of-service
- https://sportsdata.io/developers/api-documentation/global

## 14.1 Commercial model

SportsDataIO explicitly distinguishes:

```text
personal/non-commercial Discovery Lab
commercial licenses
```

Commercial/public-facing/revenue-generating products require a commercial license.

Commercial licenses may permit:

```text
display of raw scores/schedules/stats
caching/storage
derived analytics
```

within the licensed scope.

## 14.2 Important limitation for BYOK

Commercial rights are tied to the customer's license/use case.

Therefore StreamVault cannot assume:

```text
valid SportsDataIO key
=
permission to display data inside StreamVault
```

The user's agreement must authorize that use/property.

## 14.3 Auth

Use:

```text
Ocp-Apim-Subscription-Key: <key>
```

rather than query-string keys.

## 14.4 Implementation strategy

Do not show SportsDataIO in the default consumer `Add Provider` list initially.

Possible later UX:

```text
Enterprise providers
    SportsDataIO
```

with an explicit notice:

```text
Requires a production commercial license that authorizes use in StreamVault.
```

## 14.5 Trial behavior

Free trial/dev data may be scrambled and is not suitable for production display.

Adapter tests may use the trial schemas, but do not treat trial keys as production-capable.

---

# 15. Provider 11 — Sportradar

**Status:** ENTERPRISE  
**Priority:** Wave 4, demand-driven

Official sources reviewed:

- https://developer.sportradar.com/sportradar-updates/page/terms-and-conditions
- https://developer.sportradar.com/sportradar-updates/page/master-terms-and-conditions-for-non-betting-services
- https://developer.sportradar.com/getting-started/docs/your-account
- https://developer.sportradar.com/getting-started/docs/mcp-server

## 15.1 Commercial model

Free trial access is for:

```text
internal testing/evaluation
```

and does not authorize commercial/public data display.

Production access requires a customer relationship/order form.

The order form can define:

```text
Products
Properties
rate limits
fees
licensed use
```

## 15.2 BYOK rule

A Sportradar production key is usable in StreamVault only if the customer's agreement authorizes StreamVault/the relevant property/use.

Therefore:

> This is a contract-gated enterprise adapter, not a generic consumer BYOK integration.

## 15.3 Coverage

Technically exceptional:

```text
football
soccer
basketball
baseball
hockey
racing
tennis
golf
MMA
cricket
rugby
volleyball
darts
snooker
many other sports
```

## 15.4 Implementation

Only build once there is a real user/customer requesting it.

Use the provider's official schema/OpenAPI/MCP documentation to map the exact products licensed by that user.

Do not assume one Sportradar account contains all sports.

---

# 16. Optional provider — OpenLigaDB

**Status:** OPTIONAL OPEN DATA  
**Priority:** Low

Official sources reviewed:

- https://openligadb.de/
- https://api.openligadb.de/
- https://openligadb.de/lizenz

Current site states API data is provided under the **Open Database License (ODbL)**.

## 16.1 Benefits

```text
no API key
community-maintained
fixtures/results/tables
good regional/testing value
```

## 16.2 Downsides

```text
limited coverage compared with commercial providers
community data quality
ODbL attribution/share-alike obligations need to be respected
```

## 16.3 Implementation

If added:

- [ ] include ODbL attribution;
- [ ] document whether StreamVault creates/stores a derivative database;
- [ ] ensure any required share-alike obligations are understood;
- [ ] keep it isolated from proprietary provider data so database-license obligations do not accidentally contaminate unrelated cached datasets.

Do not make it the default global provider.

---

# 17. Explicitly rejected built-in providers/sources

## 17.1 ESPN undocumented site API

Do not implement in the Play provider catalogue.

Reason:

```text
publicly reachable
but undocumented
no clean third-party commercial API license identified
automated/commercial-use Terms concerns
```

No codename changes this.

## 17.2 scoreboard-api

Do not integrate.

The project explicitly scrapes ESPN web pages and extracts embedded ESPN page JSON.

Its MIT code license does not grant ESPN data rights.

## 17.3 SportTime.live

Do not scrape.

It is useful as product inspiration for:

```text
sports schedule
country-specific broadcaster lists
```

but no suitable public developer API/license was established.

## 17.4 Private Apple sports feeds

Do not reverse-engineer or ship.

## 17.5 Unauthorized stream providers/resolvers

Never part of the Sports Data Provider system.

---

# 18. What happened to previously discussed "TV Guide API"

StreamVault already has:

```text
user XMLTV / EPG
```

as its primary TV-guide/broadcast source.

Generic TV-guide APIs with restrictive shared quotas are not required for the base Sports architecture.

If a specific TV-guide vendor is later proposed, treat it as:

```text
BroadcastProvider
```

not automatically as an EventProvider.

It must pass the same terms/release review.

---

# 19. Built-in provider Settings design

Recommended:

```text
Settings
→ Sports
→ Data Providers
```

Screen:

```text
ADD PROVIDER

Recommended
────────────────────
TheSportsDB
BALLDONTLIE
Highlightly

More providers
────────────────────
Sportmonks
Football-Data.org
OddsPapi

Conditional / Enterprise
────────────────────
[only show when appropriate]

Advanced
────────────────────
Custom REST/JSON
```

Do not expose RED sources.

---

# 20. Provider card

Example:

```text
TheSportsDB

Not connected

Provides:
Fixtures
Live scores
Standings
TV broadcasters

[Connect]
```

Connected:

```text
TheSportsDB

Connected
Premium V2

Events ✓
Live scores ✓
Standings ✓
Broadcasters ✓

Quota: ...
Last success: ...

[Configure] [Disconnect]
```

---

# 21. Credentials UX

Known providers ask only for required user credentials.

Examples:

```text
TheSportsDB
API key
```

```text
BALLDONTLIE
API key
```

```text
Sportmonks
API token
```

```text
football-data.org
API token
```

No endpoint entry for built-in providers.

Base URLs live inside the adapter.

---

# 22. Connection validation contract

Every built-in adapter must implement:

```kotlin
interface ProviderConnectionValidator {
    suspend fun validate(
        config: SportsProviderConfig
    ): ProviderValidationResult
}
```

Return:

```text
VALID
INVALID_CREDENTIALS
PLAN_INSUFFICIENT
RATE_LIMITED
SERVICE_UNAVAILABLE
UNSUPPORTED_CONFIGURATION
TERMS_REVIEW_REQUIRED
```

Validation should detect capabilities when possible.

---

# 23. Provider capability discovery

Do not assume the user's paid plan.

Example:

```text
BALLDONTLIE
    -> account endpoint
    -> subscribed sports + tier
```

Example:

```text
Highlightly
    -> plan metadata / response envelope
```

Example:

```text
API-SPORTS
    -> status endpoint
```

Example:

```text
TheSportsDB
    -> V2/premium endpoint test
```

Where providers do not expose account capabilities, infer conservatively from successful authorized endpoint calls.

---

# 24. Shared adapter result model

Every adapter must normalize into Plan 3 DTOs.

Provider raw models must never reach UI code.

```text
Provider JSON
    ↓
Provider mapper
    ↓
ProviderEvent
ProviderScore
ProviderStanding
ProviderBroadcaster
ProviderTeamMetadata
    ↓
Plan 3 resolver
    ↓
Canonical SportEvent
```

---

# 25. API-first eligibility

A provider may be selected as the user's primary Event Source only if it has:

```text
EVENTS
stable event IDs
event time/date
sport identity
enough competitor/event identity to match EPG/channel data
```

Providers used only for live scores cannot be selected for API-first discovery.

---

# 26. Per-provider authority

Recommended default authority:

## TheSportsDB

```text
Events: allowed
Scores: allowed
Standings: allowed
Broadcasters: allowed
Playback: local only
```

## BALLDONTLIE

```text
Events: allowed
Scores: allowed
Standings: allowed when plan/sport supports
Broadcasters: none
Playback: local only
```

## Highlightly

```text
Events: allowed
Scores: allowed
Standings: allowed where sport supports
Broadcasters: none
Playback: local only
```

## Sportmonks

```text
Events: allowed
Scores: allowed
Standings: allowed
Broadcasters: allowed
Playback: local only
```

## football-data.org

```text
Events: allowed
Scores: allowed per plan
Standings: allowed
Broadcasters: none
Playback: local only
```

## OddsPapi

```text
Events: allowed
Scores: allowed
Standings: not primary capability
Odds: deliberately not exposed
Playback: local only
```

---

# 27. Broadcaster priority

For providers with broadcaster data:

```text
existing resolved EPG channel
    >
provider broadcaster exact/canonical match
    >
Plan 2 event-channel matcher
    >
manual picker
```

TheSportsDB/Sportmonks broadcaster metadata should improve matching, not override a correct existing EPG mapping.

---

# 28. Images/logos policy

The adapter and the artwork capability are separate.

Default release rule:

```text
Provider factual data
    ✅

Provider logo/image URL
    not automatically enabled
```

A provider can be GREEN for scores/events while its artwork remains disabled.

Use:

```text
EPG logos
existing StreamVault channel logos
generic sport icons
```

until image rights are established.

---

# 29. Highlights/video policy

Do not include highlights in the first provider implementations.

Even if an API exposes:

```text
YouTube highlight URLs
video clips
highlight embeds
```

that introduces:

```text
third-party copyright
geo restrictions
platform terms
playback policy
```

Treat highlights as a future separate feature/legal review.

---

# 30. Betting/odds policy

The Sports Hub does not need betting functionality.

Even when a provider exposes odds:

```text
Sportmonks
Highlightly
BALLDONTLIE
OddsPapi
API-SPORTS
SportsDataIO
Sportradar
```

do not expose odds in the initial provider adapter.

Provider code may ignore those endpoints entirely.

This keeps Plan 4 focused on:

```text
events
scores
status
standings
metadata
broadcasters
```

---

# 31. Caching by provider

Each adapter defines recommended TTLs.

Conceptual defaults:

```text
fixtures/upcoming
    10–60 min depending provider

live scores
    provider-safe interval, usually 30–120 sec

standings
    15–60 min

team metadata
    24h+

league metadata
    24h+

broadcasters
    cache until event changes / several hours
```

Do not poll all providers simultaneously for the same capability unless configured for fallback.

---

# 32. Quota-aware routing

If primary score provider is close to quota:

```text
do not silently spend another provider's quota
```

unless the user enabled automatic fallback.

Possible setting:

```text
Provider failure fallback

● Use another configured provider
○ Fall back only to EPG/local data
```

Default can remain conservative initially.

---

# 33. User-owned quota principle

For self-serve BYOK providers:

```text
user account
    ↓
user API key
    ↓
user subscription
    ↓
user quota
```

StreamVault does not ship a shared production key.

Do not proxy ordinary BYOK traffic through an OpenAI/StreamVault backend.

---

# 34. Secret handling

All provider credentials:

- encrypted/Keystore-backed at rest;
- never logged;
- never exported through normal debug bundles;
- query-string keys redacted before HTTP logging;
- removed from exception text;
- excluded from screenshots where possible;
- only available to the provider request layer.

---

# 35. Terms metadata in code

Each adapter should ship a machine-readable compliance descriptor.

Example:

```kotlin
data class ProviderComplianceInfo(
    val termsUrl: String,
    val reviewedAt: LocalDate,
    val attributionRequired: Boolean,
    val appStoreAllowed: Boolean?,
    val commercialUseStatus: CommercialUseStatus,
    val artworkStatus: ArtworkUseStatus,
    val releaseGate: ProviderReleaseGate
)
```

This is informational/internal.

Do not pretend the descriptor itself creates legal rights.

---

# 36. Release-gate statuses

```kotlin
enum class ProviderReleaseGate {
    APPROVED,
    REQUIRES_ATTRIBUTION,
    REQUIRES_VENDOR_CONFIRMATION,
    REQUIRES_CUSTOMER_CONTRACT,
    DO_NOT_SHIP
}
```

The Play build provider registry should only expose adapters whose release gate permits it.

---

# 37. Build-time provider registry

Prefer:

```text
clean built-in adapters
```

compiled into both distributions.

Do not compile RED adapters into the Play APK.

Enterprise adapters may be included but hidden unless intentionally enabled, or kept as optional modules if that better fits the codebase.

---

# 38. Provider-specific source modules

Suggested:

```text
sports-providers/
├── core/
├── thesportsdb/
├── balldontlie/
├── highlightly/
├── sportmonks/
├── footballdata/
├── oddspapi/
├── apisports/
├── sportdb/
├── sportdevs/
├── sportsdataio/
├── sportradar/
└── openligadb/
```

Use existing StreamVault module conventions rather than creating modules solely for aesthetics.

Conditional providers may exist in source while disabled from release registry only if doing so does not create policy/distribution concerns.

RED providers should not be implemented.

---

# 39. First adapter — recommended choice

Implement **TheSportsDB first**.

Reasons:

1. very simple auth;
2. broad sports/event model;
3. premium app-store use explicitly addressed in terms;
4. livescores;
5. event TV-broadcast endpoints;
6. low price barrier for real users;
7. ideal test of API broadcaster → local IPTV matching.

First end-to-end milestone:

```text
user adds TheSportsDB premium key
        ↓
StreamVault validates V2
        ↓
user selects TheSportsDB as Event Source
        ↓
events appear from API
        ↓
TV broadcaster/EPG matching finds local channels
        ↓
score/status enriches event
        ↓
play uses user's M3U/Xtream channel
```

---

# 40. Second adapter — BALLDONTLIE

Use it to prove:

```text
multi-sport provider
sport-specific plan capabilities
provider account introspection
score/standing enrichment
API-first without broadcaster feed
```

Milestone:

```text
BALLDONTLIE event
    ↓
EPG / Plan 2 channel resolver
    ↓
playable user channel
```

---

# 41. Third adapter — Highlightly

Use it to prove:

```text
one-key multi-sport base
shared quota across sports
prefix-based sport routing
pagination
standings + lineups + team metadata
```

Do not implement highlights first.

---

# 42. Fourth adapter — Sportmonks

Once the BYOK/native-client gate is cleared, use it to prove:

```text
rich football include model
high-quality TV-station matching
configurable league subscription coverage
```

This may become the best premium football provider for StreamVault.

---

# 43. Adapter contract tests

Every built-in adapter must pass the Plan 3 provider contract suite.

Add per-provider fixtures for:

```text
upcoming event
live event
finished event
postponed/cancelled event
missing competitor
unknown league
score update
standings
rate-limit response
bad key
plan-insufficient response
schema field missing
```

Broadcaster providers also test:

```text
one TV station
multiple countries
streaming-only service
unknown broadcaster
duplicate broadcaster records
```

---

# 44. No live-network dependency in CI

Capture/sanitize provider responses as fixtures where their terms allow it.

Tests must not depend on current live games.

Integration smoke tests against real providers should be optional/manual and require developer-owned credentials.

Never commit real API keys.

---

# 45. Terms re-check automation/process

Do not automatically scrape Terms pages.

Maintain:

```text
docs/sports/providers/<provider>-review.md
```

containing:

```text
review date
terms URL
documentation URL
capabilities
auth
attribution
commercial-use summary
open questions
release gate
```

Before each significant release:

- [ ] review providers whose terms changed;
- [ ] re-check any provider marked `REQUIRES_VENDOR_CONFIRMATION`;
- [ ] update review date/status.

---

# 46. Provider documentation files

Create:

```text
docs/sports/providers/
├── thesportsdb.md
├── balldontlie.md
├── highlightly.md
├── custom-json.md
├── sportmonks.md
├── football-data.md
├── oddspapi.md
├── api-sports.md
├── sportdb.md
├── sportdevs.md
├── sportsdataio.md
├── sportradar.md
└── openligadb.md
```

Each document separates:

```text
technical facts
terms/licensing findings
StreamVault implementation
open questions
```

Do not mix assumptions into verified terms.

---

# 47. Provider list UI release policy

The UI should only advertise providers that are actually releasable.

Initial production list after Wave 1:

```text
TheSportsDB
BALLDONTLIE
Highlightly
Custom REST/JSON
```

Then add Wave 2 providers as their gates clear.

Do not show:

```text
Coming soon ESPN
```

or any unsupported questionable source.

---

# 48. API-first defaults

When a provider is configured:

Do not automatically switch the user's Event Source without permission.

After successful connection:

```text
Use TheSportsDB for event discovery?

○ Keep Automatic
○ Use TheSportsDB
```

Recommended default:

```text
Automatic
```

Plan 3 source routing decides whether/when provider events are preferred.

---

# 49. Provider removal

On disconnect:

```text
delete local credentials
cancel provider polling
invalidate provider cache
remove provider-event links
fall back to EPG / other configured provider
```

Do not delete:

```text
user playlist
EPG
local manual channel mappings
```

unless those mappings were strictly provider-ID-specific and cannot be resolved without the provider.

---

# 50. Attribution removal

When a provider requiring attribution is disconnected and no cached licensed data remains in display, remove its attribution from active Sports UI.

Keep legal/open-source notices that must remain in About/licenses if required.

---

# 51. Offline behavior

No provider should make Sports unusable offline.

If API is unavailable:

```text
EPG Sports
    continues
```

Cached provider data may be shown only according to the provider's terms/cache rules and with a stale timestamp where useful.

---

# 52. Implementation sequence

## Phase 4.1 — Provider compliance registry

- [x] Add internal provider review metadata.
- [x] Add release-gate enum.
- [x] Add attribution capability.
- [x] Add artwork-rights status.
- [x] Add terms/docs source fields.
- [x] Add tests ensuring `DO_NOT_SHIP` adapters cannot appear in Play provider registry.

---

## Phase 4.2 — TheSportsDB

The Plan 3 implementation already provides the key UI, premium-key V2 auth, event and live-score mapping, bounded response caching, fixtures, and API-first source selection. These completed items are not work to repeat in Phase 4.2. Their existence does not clear the Phase 4.1 release/compliance gate.

- [x] Premium/V2 credential UI.
- [x] V2 auth.
- [x] event mapping.
- [x] live-score mapping.
- [x] standings mapping — documented V1-only endpoint; all other operations use V2.
- [x] TV-broadcast mapping.
- [x] attribution in every applicable Sports UI surface.
- [x] cache/rate policy.
- [x] fixture tests.
- [x] API-first integration.
- [x] broadcaster → local channel integration.
- [x] Beta status is visible in provider settings because no live Premium account is available.
- [ ] live Premium-account validation — deferred by the user (no key available); the provider remains Beta until a real account check is completed.

---

## Phase 4.3 — BALLDONTLIE

- [x] key UI — uses the localized shared provider configuration form.
- [x] account/subscription introspection.
- [x] sport capability resolver.
- [x] event mapping.
- [x] live-score mapping.
- [x] standings where available, with the account tier checked per sport.
- [x] cache/rate policy.
- [x] fixture tests.
- [x] API-first + EPG channel resolver test.

The initial adapter scope is NBA, NFL, MLB, and the English Premier League.
Other BALLDONTLIE sport APIs remain future work. The API-backed behavior was
verified against documented-response fixtures; no live account key was
configured or tested. See `sports/providers/balldontlie.md`.
Account-tier checks distinguish EPL match access (ALL-STAR/Paid) from its
Free standings route; NBA standings require GOAT/Paid Plus, and NFL/MLB
standings require ALL-STAR/Paid.

---

## Phase 4.4 — Highlightly

- [x] key UI through the shared provider credentials form.
- [x] direct All Sports base.
- [x] sport-prefix routing.
- [x] bounded offset pagination.
- [x] matches/live scores and game status.
- [x] standings for documented route shapes.
- [x] team and supported league metadata.
- [x] shared quota tracking; show remaining requests and plan tier when supplied.
- [x] fixture tests.
- [x] no highlights/video endpoints.

Implementation and fixture coverage are recorded in
[`sports/providers/highlightly.md`](sports/providers/highlightly.md). Highlightly is marked **Beta** in provider settings until a live account validation can be completed; no account key is available.

---

## Phase 4.5 — Sportmonks (skipped per user instruction)

**Status (2026-10-17):** Skipped at the user's direction. No vendor inquiry was
sent and no Sportmonks adapter was implemented or registered. The direct
native-BYOK gate remains uncleared; Sportmonks stays unavailable as a built-in
provider unless this decision is revisited and the gate is cleared.

Before coding production adapter:

- [ ] contact/review confirmation for native BYOK.
- [ ] record answer.
- [ ] if approved, continue.

Then:

- [ ] token UI.
- [ ] secure header auth.
- [ ] fixtures.
- [ ] livescores.
- [ ] standings.
- [ ] TV stations.
- [ ] rich participant/team mapping.
- [ ] rate metadata.
- [ ] fixture tests.

If not approved:

```text
do not ship built-in adapter
support user's Sportmonks proxy through Custom REST/JSON
```

---

## Phase 4.6 — football-data.org

- [x] token UI through the shared provider credential form.
- [x] required attribution on provider-backed event and standings surfaces.
- [x] event/score/standings mapping.
- [x] conservative local request cap and provider rate metadata.
- [x] credential-scoped cache cleanup on 401/403.
- [x] no provider logos or artwork.
- [x] single-application/BYOK review recorded from published terms.

Offline implementation and fixture coverage are recorded in
[`sports/providers/football-data.md`](sports/providers/football-data.md).
No live account validation was performed.

---

## Phase 4.7 — OddsPapi score-only adapter

- [x] key UI through the shared provider credential form.
- [x] query-key transport remains absent from app logs and typed failures.
- [x] bounded fixture lookup and time filtering.
- [x] participant mapping.
- [x] period-score aggregation.
- [x] fixture-state mapping.
- [x] omit odds endpoints and capabilities.
- [ ] provider-use confirmation before release.

**Release status:** Prototype only. Terms and documented API behavior were
reviewed on 2026-10-17. The terms do not answer whether a user's direct key may
be used to show fixtures/scores in a paid Android media-player app. No vendor
inquiry was sent, so the production release gate remains closed. See
[`sports/providers/oddspapi.md`](sports/providers/oddspapi.md).

---

## Phase 4.8 — Conditional providers

The published-term reviews were completed for API-SPORTS and SportDB.dev on
2026-10-17. SportDevs' official terms URL returned HTTP 502 and could not be
reviewed successfully. No provider was contacted and none has an adapter in
this change. See
[`sports/providers/conditional-and-open-data-review-2026-10-17.md`](sports/providers/conditional-and-open-data-review-2026-10-17.md).

For each of:

```text
API-SPORTS
SportDB.dev
SportDevs
```

- [x] finish the available rights/terms review.
- [ ] obtain clarification where public terms do not resolve app display.
- [x] set or retain a closed release gate.
- [x] keep adapters unimplemented/unreleased until their gates pass.

Current gates: API-SPORTS `DO_NOT_SHIP` pending publication permissions;
SportDB.dev `REQUIRES_VENDOR_CONFIRMATION`; SportDevs `DO_NOT_SHIP` until its
terms can be reviewed.

API-SPORTS adapter work may be prototyped against documented schemas but remains disabled if publication rights remain unresolved.

---

## Phase 4.9 — Enterprise adapters

Only after demand:

```text
SportsDataIO
Sportradar
```

Deferred because this work contains no customer request or production
agreement for these products. No adapters were added.

Requirements:

- [ ] user has production commercial agreement.
- [ ] agreement permits StreamVault/property/use case.
- [ ] adapter only exposes licensed products.
- [ ] no free-trial data displayed as production.
- [ ] contract-specific attribution/restrictions supported.

---

## Phase 4.10 — Optional OpenLigaDB

- [x] ODbL compliance analysis.
- [x] linked source and ODbL attribution on provider event and standings surfaces.
- [x] offline fixture, result/status, and table adapter.
- [x] keep only bounded raw responses in process memory; do not create a derivative database.
- [x] keep as a low-priority optional adapter.

The 2026-10-17 review found that publicly used Produced Works need an
OpenLigaDB/ODbL notice with source and license links; derivative databases have
additional share-alike and machine-readable access duties. The optional adapter
ships only bounded, short-lived raw-response caching, disables provider artwork,
and shows linked attribution on data surfaces. No live OpenLigaDB account was
used. Details are in
[`sports/providers/conditional-and-open-data-review-2026-10-17.md`](sports/providers/conditional-and-open-data-review-2026-10-17.md).

The implementation uses at most five configured `shortcut:season` scopes,
fetches documented group/season-match/table routes, filters fixtures to the
requested half-open time window, and spaces requests by at least 1.2 seconds
across the app process. See [`sports/providers/openligadb.md`](sports/providers/openligadb.md).

---

# 53. Provider ranking for StreamVault specifically

## Best overall first adapter

**TheSportsDB**

Why:

```text
app-store terms are explicit
cheap premium key
broad sport coverage
TV broadcasts
scores
simple API
```

## Best legal clarity for commercial factual data

**BALLDONTLIE**

Why:

```text
explicit commercial permission
explicit redistribution permission with restrictions
no mandatory attribution by default
multi-sport
```

## Best simple multi-sport integration

**Highlightly**

Why:

```text
one key
one base
11 sport prefixes
similar endpoint shapes
data allowed in applications/products
```

## Best football+broadcast premium provider

**Sportmonks** (unimplemented candidate; skipped in this change)

Why:

```text
excellent football data
TV stations
commercial product use allowed
rich fixture model
```

but only after the direct-BYOK/native-client question is cleared.

## Best enterprise providers

```text
SportsDataIO
Sportradar
```

but only for users with actual commercial contracts.

---

# 54. Final recommendation

For integrations that are enabled or have an approved next step, proceed in this
order. Sportmonks is skipped, OddsPapi and conditional services remain gated,
and enterprise providers wait for a customer contract:

```text
1. TheSportsDB
2. BALLDONTLIE
3. Highlightly
4. football-data.org
5. OpenLigaDB (optional)
6. OddsPapi score-only, after provider-use confirmation
7. conditional APIs as terms clear
8. enterprise adapters only when requested
```

Do not optimize for "largest number of provider logos in Settings."

Optimize for:

```text
clear rights
stable official API
good event IDs
good score data
useful broadcaster metadata
secure BYOK
predictable quota
clean failure behavior
```

---

# 55. Final architecture with concrete providers

```text
                    SPORTS EVENT SOURCES

        +--------------+--------------+--------------+
        |              |              |              |
        v              v              v              v
       EPG        TheSportsDB    BALLDONTLIE    Highlightly
        |              |              |              |
        |       Football-Data     OpenLigaDB        ...
        |              |              |              |
        +--------------+--------------+--------------+
                               |
                               v
                       Plan 3 Event Resolver
                               |
                               v
                        Canonical SportEvent
                               |
                +--------------+--------------+
                |              |              |
                v              v              v
             Scores        Standings       Metadata
                |
                v
                       Broadcast Resolver
                +--------------+----------------+
                |              |                |
                v              v                v
         provider TV data     XMLTV       Plan 2 fallback
        (where supported)      |         event-channel match
                +--------------+----------------+
                               |
                               v
                         M3U / Xtream
                               |
                               v
                              PLAY
```

The rule remains:

> **Sports data providers may tell StreamVault what event exists, what the score is, and which broadcaster carries it. Only the user's own configured StreamVault channels determine what can actually be played.**
