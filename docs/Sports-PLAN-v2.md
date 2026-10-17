# StreamVault Sports Hub — Revised Implementation Plan (EPG-First + Resilience Enhancements)

**Status:** Supersedes the previous Sports plan for future work.  
**Baseline:** Existing implementation on `codex/sports-integration`, based on the supplied `Sports-PLAN.md` and `Sports-IMPLEMENTATION.md`.  
**Core rule:** Keep the Sports Hub EPG-first. Do not introduce ESPN, Apple, scraping, reverse-engineered sports providers, or a new backend.  
**Goal of this revision:** Preserve every unfinished requirement from the original plan, keep the work that is already implemented, and add useful event-discovery and resilience improvements.

---

# 0. Agent instructions

This is an **incremental continuation plan**, not a greenfield rewrite.

Before changing code:

1. Read the current Sports implementation and the existing integration map.
2. Treat items marked **Implemented** below as existing behavior that should be preserved unless a later section explicitly changes them.
3. Do **not** rebuild already-working features merely to match class/file names suggested in this document.
4. Reuse the existing StreamVault EPG resolution and playback paths.
5. Prefer targeted improvements over architecture churn.
6. Add tests before materially expanding heuristics.
7. Do not add any remote sports-data provider in this plan.
8. Do not add ESPN integration.
9. Do not add Apple Sports integration.
10. Do not add scraping of sports sites.
11. Do not add unauthorized stream discovery/resolution.
12. Do not add a new backend.
13. Do not persist a second Sports database/index unless the EPG refresh architecture later exposes enough change information to make incremental updates reliable.
14. If an implementation detail in this plan conflicts with an existing, better StreamVault abstraction, keep the StreamVault abstraction and satisfy the behavior instead.

The implementation must remain useful with:

```text
User XMLTV / EPG
+
User M3U / Xtream
```

only.

---

# 1. Current implementation baseline

The current branch already implements a substantial part of the original plan.

## 1.1 Already implemented — preserve

- [x] Sports top-level navigation and route.
- [x] Active provider / combined-profile awareness.
- [x] Existing resolved EPG-to-channel mapping is reused.
- [x] Hidden/adult/protected channel filtering before Sports playback choices.
- [x] EPG-based sports classification.
- [x] Initial sport classification from title, description, genre/category, and channel metadata.
- [x] Initial league recognition.
- [x] Initial team alias support.
- [x] Event candidate generation.
- [x] Event fingerprinting.
- [x] Duplicate-event grouping with a 15-minute start-time tolerance.
- [x] Multiple playable broadcasts retained on a single event.
- [x] On Now / Today / Upcoming.
- [x] Seven-day Upcoming horizon.
- [x] Sport filtering.
- [x] League filtering.
- [x] Playable-channel counts.
- [x] Sports event details dialog.
- [x] Playback through the existing StreamVault live player.
- [x] Five-minute in-memory Today/Upcoming cache.
- [x] Cache invalidation for EPG/source/profile/visibility changes.
- [x] Optimized normalization pattern reuse.
- [x] Reused event fingerprints during assembly.
- [x] Diagnostic logging behind `SportsHub` DEBUG.
- [x] CPU microbenchmark through 50,000 synthetic programmes.
- [x] Localized Sports UI labels.
- [x] Kotlin compilation and translation-key checks.

These features are not to be reimplemented from scratch.

---

# 2. What remains from the original plan

Every unfinished item from the previous plan remains in scope unless explicitly deferred here.

## 2.1 Domain/model depth

- [x] Add richer competitor modeling with display and canonical names.
- [x] Add richer league modeling where useful: recognized leagues now carry a stable canonical ID, display name, sport, and normalized aliases through classification and event assembly.
- [x] Keep status semantics clearly "broadcast schedule status" unless enriched by a future score provider.
- [x] Ensure the model handles team sports and non-team sports without forcing a two-team shape.
- [x] Add direct unit coverage for domain behavior.

## 2.2 Classification

- [x] Broaden classifier fixture coverage.
- [x] Improve international sport/competition recognition from the configured guide.
- [x] Improve negative classification for sports news, previews, replays, highlights, studio shows, documentaries, and post-game programmes.
- [x] Ensure missing category/genre data does not prevent strong title/description detection.
- [x] Ensure generic `Sports` category alone does not incorrectly turn studio programming into competitive events.

## 2.3 Normalization

- [x] Broaden team aliases.
- [x] Broaden league aliases.
- [x] Add better punctuation/Unicode normalization.
- [x] Add Sports-owned normalization/indexing for IPTV channel names, preserving language/region qualifiers while grouping quality decorations for matching.
- [x] Improve non-team sport title cleanup.
- [x] Keep aliases structured/data-driven rather than scattering replacements across code.

## 2.4 Event extraction

- [x] Introduce a dedicated event parser boundary for title and event/session extraction.
- [x] Parse competitors more reliably.
- [x] Parse event type/session for non-team sports.
- [x] Preserve raw EPG programme records for diagnostics/fallback presentation.
- [x] Never invent competitors when evidence is weak.

## 2.5 Deduplication

- [x] Add confidence checks and compatibility signals to event matching.
- [x] Add false-positive tests.
- [x] Handle same teams playing more than once in one day.
- [x] Prevent replay/highlight programmes from merging into a live/upcoming event.
- [x] Handle team aliases across language variants.
- [x] Support wider time tolerance for non-team sports than for team fixtures.

## 2.6 Repository/index architecture

- [x] Keep the current bounded in-memory cache for now.
- [ ] Optionally extract loading/caching behind a feature-owned `SportsRepository` if this materially improves separation/testability.
- [x] Do **not** add a durable duplicate Sports table yet; Sports remains derived from persisted EPG programmes.
- [ ] Revisit incremental indexing only after the EPG refresh layer can expose changed programme IDs and affected source/channel/time ranges.

## 2.7 Runtime/device verification

- [x] Test with a real configured playlist + EPG on the isolated AVD.
- [x] Verify the observed WSL event appears once with its two mapped channel choices.
- [ ] Verify every surfaced playback option actually launches the expected stream; Golf passed its two-minute check, but both WSL choices still fail progression. The 2026-10-16 retest captured 61 frames at two-second intervals for each choice: FHD froze after HLS first-frame success and decoder stalls; HD stayed buffering after a network timeout.
- [ ] Verify switching provider/combined profile refreshes Sports correctly; the isolated AVD currently has only one saved Xtream provider and no combined profile, so switching could not be exercised without changing the user's setup.
- [ ] Exercise Sports invalidation after a provider EPG refresh and a standalone external-guide refresh; this AVD has no external guide source, and its provider sync dialog exposes no EPG-only refresh. No provider/catalog sync was run for this check.
- [x] Verify visibility changes refresh Sports correctly; hiding one channel changed the Sports input from 28,398 to 28,397 visible rows, and unhiding it restored 28,398 on refresh.
- [x] Verify Today/Upcoming cache hits using remote navigation and fresh UI state; confirmed revisits took 6.6 s for Today and 5.5 s for Upcoming. A later same-process Today revisit logged a `window-cache-hit` at 0 ms and retained the expected 564-channel UI.
- [x] Measure actual Room/resolution cost, not only CPU classification cost. Debug-only stage logs measured 28,398 visible channel rows per range; resolved-program loading took 0.57 s On Now, 4.5–9.8 s Today, and 1.02 s Upcoming. This is an aggregate resolution path, not per-statement Room profiling.
- [x] Review D-pad focus and card readability on the 1920x1080 Android TV AVD; physical TV hardware remains unverified.

## 2.8 Test coverage

- [x] Unit tests for classification.
- [x] Unit tests for aliases/normalization.
- [x] Unit tests for competitor extraction.
- [x] Unit tests for league extraction.
- [x] Unit tests for fingerprints.
- [x] Unit tests for deduplication.
- [x] Unit tests for conservative channel fallback matching, date rejection, protected/unplayable filtering, scoring, and direct-mapping precedence.
- [x] Unit tests for broadcast status calculation.
- [x] Fixture-driven XMLTV tests for categorized/minimal guides, exclusions, non-team events, and duplicate broadcasts.
- [x] M3U + XMLTV fixtures for the current resolved path (exact, missing, unresolved, and duplicate-event cases; data-layer persistence/override tests pass). Future event-channel fallback tests belong to Phases E/F.
- [x] Run the adapted `LiveGraphBehaviorTest`.

---

# 3. Core architecture — keep EPG-first

The primary architecture remains:

```text
XMLTV / EPG
     |
     v
resolved EPG programmes for active playable channels
     |
     v
Sports classification
     |
     v
event extraction
     |
     v
normalization
     |
     v
deduplication
     |
     v
canonical SportEvent
     |
     v
playable channel choices already associated with the EPG
     |
     v
Sports UI
     |
     v
existing StreamVault live player
```

This is intentionally different from an event-first sports discovery model.

An event-first sports app starts with an external event and searches for the user's matching IPTV channel.

StreamVault starts with an EPG programme that is normally **already associated with a channel**.

Therefore:

> Existing EPG/channel resolution is always the primary and highest-confidence path.

No fallback matcher should replace a working EPG → channel mapping.

---

# 4. Revised channel-resolution strategy

The current implementation already resolves EPG programmes to playable StreamVault channels.

Keep that as **Tier 0**.

Add channel-discovery fallbacks only for unresolved or incomplete cases.

## Tier 0 — existing StreamVault resolved mapping

Use the existing resolved-program path.

Conceptually:

```text
EPG programme
    |
    v
existing StreamVault mapping
    |
    v
playable channel
```

This is the normal path and should dominate all other signals.

If this succeeds, no channel-name fuzzy search is needed merely because it exists.

## Tier 1 — exact EPG/channel identifiers

Where the underlying mapping exposes identifiers such as:

```text
XMLTV channel id
M3U tvg-id
Xtream channel mapping
persisted user channel mapping
```

keep exact identity as the strongest specific signal.

This may already be encapsulated by the existing resolved-program path.

Do not duplicate that matching logic in Sports.

## Tier 2 — temporary/event-named IPTV channel fallback

**New channel-discovery improvement.**

Use only when a Sports event has no usable direct playback choice, or when the user explicitly opens "Find another channel."

Many IPTV providers expose temporary event channels such as:

```text
EPL | Arsenal vs Liverpool
NHL | Kraken @ Red Wings
UFC 330 | Fighter A vs Fighter B
F1 | Japanese Grand Prix
```

Build a fallback matcher capable of finding these from the user's existing channel list.

The fallback must not introduce any external streams.

### Team-sport matching

Require strong evidence.

For a two-team event, prefer:

```text
one alias from team A
+
one alias from team B
```

in the channel name.

Examples:

```text
"Arsenal vs Liverpool"
"Arsenal - Liverpool"
"Liverpool @ Arsenal"
```

should match.

A channel containing only `Arsenal` should normally not auto-match a single event when multiple Arsenal programmes/channels could exist.

### Fight matching

Use:

```text
fighter A full name or distinctive surname
+
fighter B full name or distinctive surname
```

Avoid short/common surnames as sole evidence.

### Racing/golf/event sports

Use event or series phrases:

```text
Japanese Grand Prix
Formula 1
F1
PGA Tour
The Open
```

Prefer event-specific phrase matches over generic series matches.

### Matching policy

Temporary/event-named channel matching should be:

- deterministic where possible;
- confidence-ranked;
- explainable in diagnostics;
- non-destructive;
- used as fallback, not as replacement for EPG mapping.

---

# 5. Date-aware temporary-channel matching

**New event-resolution improvement.**

Temporary event channels can survive after the event or may include future/past dates.

Recognize common date patterns in channel names:

```text
Oct 18
18 Oct
2026-10-18
18/10
10/18
```

Only use ambiguous numeric dates where locale/source context makes them safe enough; otherwise treat them as weak evidence.

If a channel contains a recognizable date:

- require it to be compatible with the event date;
- allow a small timezone tolerance around UTC/local-day boundaries;
- reject an obviously different event date.

Example:

```text
EPL | 18 Oct | Arsenal vs Liverpool
```

must not be auto-selected for the next Arsenal/Liverpool meeting months later.

Date awareness should increase confidence, not become the only required signal.

---

# 6. Fallback matcher indexing

**New performance improvement.**

Do not scan every channel with expensive regex/fuzzy logic separately for every event.

If temporary-channel fallback is introduced, build an index once per effective channel list.

Suggested reusable data:

```text
normalized channel name
token -> channel indexes
canonical channel key -> channel indexes
EPG id -> channel indexes
channel id -> index
```

Conceptual structure:

```kotlin
data class SportsChannelIndex(
    val channels: List<Channel>,
    val normalizedNames: List<String>,
    val tokenIndex: Map<String, IntArray>,
    val byCanonicalName: Map<String, IntArray>,
    val byEpgId: Map<String, IntArray>
)
```

The exact representation should follow existing StreamVault structures.

### Index invalidation

Rebuild only when the effective playable-channel set changes, e.g.:

- active provider changes;
- combined-profile membership changes;
- playlist refresh changes channels;
- visibility rules materially change the available channel set if hidden channels are excluded at index time.

Do not rebuild on every Sports recomposition.

---

# 7. Channel fallback scoring

Add a testable, explainable score only for fallback candidates.

Example initial signals:

```text
both competitors present in channel name      +100
event title phrase strongly present            +90
recognized matching date present               +20
series/league phrase present                   +15
preferred known channel mapping                +15
quality/source preference                      +small bias
language/region preference match               +small bias
obviously wrong date                           reject
only one team with no other evidence           weak / do not auto-select
```

Calibrate ranking weights to this app's own data instead of reusing external weights.

Tune against fixtures and real user IPTV data.

Return a reason model:

```kotlin
enum class SportsChannelMatchReason {
    RESOLVED_EPG,
    EXACT_IDENTIFIER,
    EVENT_CHANNEL_NAME,
    REMEMBERED_EVENT_PICK,
    REMEMBERED_TEAM_PICK,
    REMEMBERED_SERIES_PICK,
    MANUAL_SELECTION
}
```

If a generic broadcaster/network alias fallback is later added, give it its own reason and keep it lower confidence than event-specific evidence.

---

# 8. Manual correction and remembered channel choices

**New sports-hub product improvement.**

Automatic mapping will never be perfect across all IPTV providers.

Give the user a manual correction path.

## 8.1 Event details action

When viewing an event:

```text
Watch on
- beIN Sports 1
- SuperSport LaLiga

Find another channel
```

`Find another channel` searches only the user's configured playable channels.

No external provider data is involved.

## 8.2 Selection modes

After selecting a channel, support these scopes where meaningful:

```text
Use for this event
Always prefer for this team
Always prefer for this league/series
```

Do not force all three options for every sport.

Suggested applicability:

### Team sports

```text
this event
this team
```

Optionally:

```text
this league
```

only if real-world testing shows it is useful and not overbroad.

### Racing

```text
this event
this series
```

Example:

```text
Prefer Sky Sports F1 for Formula 1
```

### Golf

```text
this event
this series
```

### Fighting

Usually:

```text
this event
this promotion/series
```

A persistent fighter-to-channel mapping is unlikely to be useful.

## 8.3 Precedence

Recommended:

```text
event pick
    >
team/series preference
    >
direct resolved EPG mapping (when user has explicitly overridden)
    >
automatic fallback candidate
```

However, be careful:

A remembered preference must not silently route to a channel that is not currently carrying the event.

For persistent team/series preferences, require at least one corroborating signal:

- EPG currently shows the event on the channel; or
- channel name currently identifies the event; or
- user explicitly chooses "play anyway" from manual results.

An old preference is a ranking hint, not proof that the event is airing there today.

## 8.4 Expiry

Event-specific picks should expire automatically after a short period.

Example:

```text
7 days
```

Team/series preferences may persist until removed.

## 8.5 Forget action

Provide:

```text
Forget this choice
```

or equivalent.

Do not trap the user in a bad remembered mapping.

---

# 9. Channel-name normalization improvements

Extend existing normalization only as needed for fallback matching.

Common noise:

```text
HD
FHD
UHD
4K
HEVC
H265
H.265
RAW
VIP
Backup
Alt
Feed
```

Examples:

```text
BEIN SPORTS 1 FHD [AR]
beIN Sports 1 HD
BEIN 1 UHD
```

Normalization should separate:

```text
canonical identity
quality
language hint
country/region hint
source/provider decoration
```

Do not discard useful qualifiers too early.

For example:

```text
beIN Sports 1 AR
beIN Sports 1 EN
```

may need to remain distinguishable to the user even if both map to the same broadcaster family.

---

# 10. Region/language ranking

Support following teams without hardcoding a US/CA/UK-first region preference.

StreamVault is international.

Use:

1. user's app language where relevant;
2. explicit playlist/channel language metadata where available;
3. provider/group metadata;
4. user channel history/preferences if an existing safe abstraction already exists;
5. otherwise neutral ordering.

Do not globally downgrade Arabic, French, Spanish, German, etc.

A channel should only receive a regional/language preference bonus if it aligns with known user/source context.

This is a ranking refinement, never a reason to hide legitimate alternatives.

---

# 11. Duplicate broadcast/channel presentation

Keep all valid playable choices, but reduce UI clutter.

Example raw channels:

```text
beIN Sports 1 HD
beIN Sports 1 FHD
beIN Sports 1 4K
beIN Sports 1 Backup
```

Possible UI:

```text
beIN Sports 1
  4 sources
```

Selecting the group opens the variants.

Alternatively, if StreamVault already has a source-priority abstraction, use it to choose a primary variant while keeping the others accessible.

Do not silently discard alternatives.

Do not incorrectly collapse channels that only look similar but represent different language/region feeds.

---

# 12. Optional event zap-list behavior

**Optional team-following enhancement.**

When an event has multiple matched playable channels:

```text
Barcelona vs Real Madrid

beIN Sports 1
SuperSport
Arena Sport
```

consider passing those channels as an event-specific zap list to the existing player if the player architecture supports it cleanly.

Then:

```text
CH+
CH-
```

could cycle through only the channels carrying that event.

This is optional and should only be added if:

- it fits the current live-player abstraction;
- it does not complicate the player substantially;
- it preserves normal navigation/back behavior.

Do not create a separate Sports player.

---

# 13. Event model refinement

The current model should evolve toward an event shape that supports both team and non-team sports.

Avoid a mandatory:

```text
homeTeam
awayTeam
```

core model.

Prefer:

```kotlin
data class SportEvent(
    val id: String,
    val sport: SportType,
    val league: SportLeague?,
    val title: String,
    val competitors: List<Competitor>,
    val session: String?,
    val startTime: Instant,
    val endTime: Instant?,
    val scheduleStatus: BroadcastScheduleStatus,
    val eventType: SportEventType,
    val broadcasts: List<BroadcastOption>,
    val confidence: Float,
    val rawProgrammeRefs: List<String>
)
```

Exact fields may differ from existing implementation.

The important behavior is:

- zero competitors allowed;
- one competitor allowed;
- two competitors allowed;
- many competitors allowed;
- event/session title remains first-class.

---

# 14. Non-team sports — make this a first-class remaining task

The existing implementation still needs broader non-team event cleanup.

Treat the following as separate shapes.

## 14.1 Motorsport

Recognize:

```text
Formula 1
F1
NASCAR
IndyCar
MotoGP
```

and sessions:

```text
Practice
FP1
FP2
FP3
Sprint Qualifying
Sprint
Qualifying
Race
```

Example canonical display:

```text
Formula 1
Japanese Grand Prix — Qualifying
Saturday · 09:00
```

Do not attempt to invent competing "teams" from the EPG.

## 14.2 Golf

Recognize tournament + round/session when available:

```text
The Open Championship — Round 3
PGA Tour — Final Round
```

Do not require player lists.

## 14.3 Combat sports

Recognize:

```text
UFC
PFL
boxing
Fight Night
Main Card
Prelims
```

If two fighters are clearly named, store competitors.

If only a card name exists:

```text
UFC 330 — Main Card
```

is still valid.

## 14.4 Tennis

Support both:

```text
Carlos Alcaraz vs Jannik Sinner
```

and generic tournament programming:

```text
ATP Madrid — Quarterfinals
```

## 14.5 Other field/tournament sports

Ensure the fallback model can represent:

```text
cycling
athletics
snooker
darts
multi-team tournaments
```

without forcing a team matchup.

---

# 15. Event type classification

Implement/finish:

```kotlin
enum class SportEventType {
    LIVE_EVENT,
    SCHEDULED_EVENT,
    REPLAY,
    HIGHLIGHTS,
    PREVIEW,
    PRE_GAME,
    POST_GAME,
    SPORTS_NEWS,
    SPORTS_SHOW,
    UNKNOWN
}
```

Use title/subtitle/description/category signals.

Examples:

```text
Replay
Full Match Replay
Encore
Repeat
Highlights
Match Highlights
Pregame
Pre-Game
Postgame
Post-Match
Preview
Review
Sports News
```

Rules:

- replay must not merge into the original live event merely because competitors match;
- highlights must not appear in the default upcoming/live competition row;
- generic shows may optionally live in a later `More Sports` section;
- do not discard them from the data model if preserving them is cheap.

---

# 16. Classification pipeline refinement

Continue reading all useful EPG information:

```text
title
subtitle
description
categories/genres
keywords if available
channel name
channel group
EPG live/repeat flags if available
start/stop
```

Suggested classification output:

```kotlin
data class SportsClassification(
    val isSports: Boolean,
    val isCompetitiveEvent: Boolean,
    val sport: SportType,
    val eventType: SportEventType,
    val league: SportLeague?,
    val confidence: Float,
    val reasons: Set<ClassificationReason>
)
```

Keep reasons diagnostic-friendly.

---

# 17. Alias registry expansion

Maintain structured alias data.

Required categories:

```text
team aliases
league aliases
sport aliases
event-series aliases
broadcaster/channel aliases where needed
```

Examples:

```text
Manchester United
Manchester Utd
Man Utd
MUFC
```

=>

```text
Manchester United
```

```text
English Premier League
Premier League
EPL
```

=>

```text
Premier League
```

For non-team sports:

```text
Formula One
Formula 1
F1
```

=>

```text
Formula 1
```

Avoid overly aggressive short aliases that create false positives.

For example, a three-letter team abbreviation should generally require sport/league/context support before being treated as definitive.

---

# 18. Event identity and deduplication v2

Current identity + 15-minute grouping is useful and should remain.

Strengthen it with an explainable confidence layer.

## 18.1 Team events

Signals:

```text
same sport
same league
same canonical competitor set
start-time proximity
title similarity
```

Do not rely on title similarity alone.

## 18.2 Same teams twice in one day

Do not merge two distinct games if:

- start times are clearly different;
- league/competition differs;
- one is a replay;
- one is a youth/women/reserve variation;
- one title includes a materially different competition/session.

## 18.3 Multi-language broadcasts

These should merge when canonical competitors/league/time strongly agree.

## 18.4 Non-team sports

Fingerprint from:

```text
sport
series/league
canonical event title
session
date/start bucket
```

Example:

```text
motorsport|formula1|japanese-grand-prix|qualifying|2026-10-17
```

Race and qualifying must remain separate.

---

# 19. Confidence model

Use configuration/constants that tests can tune.

Illustrative team-event score:

```text
same canonical competitor pair      +50
same league                           +15
same sport                            +15
start within 5 minutes                +20
start within 15 minutes               +15
strong normalized title agreement     +15
same event type                       +10
different sport                      reject
different known competition           -30
explicit different competitor         reject
replay vs live                        reject merge
```

Do not hardcode these exact values without fixture validation.

The objective is not "maximum fuzzy merging."

The objective is:

> merge confidently identical broadcasts while avoiding incorrect collapse.

---

# 20. Sports repository/index decision

For the current architecture:

## Keep

```text
persisted EPG programmes = source of truth
bounded in-memory Sports snapshots = derived cache
```

## Do not yet add

```text
duplicate persisted Sports event table
```

because the current EPG refresh boundary does not expose reliable changed-program deltas.

## Optional refactor

A feature-owned repository is acceptable if it improves:

- testing;
- ViewModel size;
- cache ownership;
- event query semantics.

Possible interface:

```kotlin
interface SportsRepository {
    fun observeOnNow(): Flow<List<SportEvent>>
    fun observeToday(): Flow<List<SportEvent>>
    fun observeUpcoming(): Flow<List<SportEvent>>
    suspend fun refresh(force: Boolean = false)
}
```

But repository extraction is **not** the same thing as a persisted index.

Do not perform it purely for architectural aesthetics.

---

# 21. Refresh behavior

Preserve current invalidation behavior.

Sports should refresh when:

```text
provider EPG refresh succeeds
external EPG source refresh succeeds
source assignment changes
combined-profile membership changes
channel/category visibility changes
local day rolls over
selected time window changes
```

On Now may continue to refresh on a short interval because broadcast-state labels change with time.

Avoid reparsing unrelated future windows during every On Now tick.

---

# 22. Broadcast-state semantics

Without a score provider:

```text
now < start     => UPCOMING
start <= now < end => AIRING / ON NOW
now >= end      => ENDED
```

This is **broadcast schedule state**.

Do not imply independently verified match state.

Use UI wording deliberately.

Preferred default:

```text
ON NOW
```

unless StreamVault's existing TV-guide semantics already call a currently airing programme `LIVE`.

If an EPG explicitly marks the programme live, a `LIVE` badge is appropriate.

---

# 23. Live scoring — unchanged architectural decision

Do not add a live-score provider in this plan.

Keep scoring optional.

Desired future boundary:

```kotlin
interface LiveScoreProvider {
    suspend fun scoreFor(event: SportEvent): LiveScore?
}
```

Potential model:

```kotlin
data class LiveScore(
    val competitorScores: Map<String, String>,
    val state: GameState?,
    val clock: String?,
    val period: String?,
    val lastUpdated: Instant
)
```

The Sports UI must remain fully functional with:

```text
score == null
```

---

# 24. Future user-configured scoring

Keep this as a future investigation, not current implementation.

Potential directions:

```text
user supplies API key for a supported provider
user supplies a compatible endpoint/plugin
commercially licensed provider added later
```

Any future integration must be evaluated separately for:

```text
terms
commercial use
rate limits
client-side secret safety
attribution
redistribution
availability
sport coverage
```

Do not let a score provider become the canonical source of Sports events.

The flow should remain:

```text
EPG SportEvent
    +
optional score enrichment
```

---

# 25. My Teams — possible EPG-first version, not required now

The sports hub can offer a My Teams feature backed by schedule data.

StreamVault should **not** copy that external dependency.

However, a future EPG-first version is possible:

```text
user follows canonical team alias
    |
    v
Sports events already detected from EPG
    |
    v
filter/rank events involving followed team
```

This would require no remote API.

Possible future behavior:

```text
My Teams
- Manchester United
- Real Madrid
- Lakers
```

and show only events found in the user's guide.

This is optional and outside the immediate implementation phases below.

Do not implement standings under the current EPG-only model because EPG does not provide a standardized standings dataset.

---

# 26. League manager — EPG-first interpretation

Do not build a static ESPN-slug catalogue.

Instead, if a richer league manager is desired later:

```text
Detected leagues from user's EPG
+
curated StreamVault alias catalogue
```

Users can:

```text
show/hide detected leagues
reorder preferred leagues
```

A league should only appear as active when StreamVault has seen relevant EPG content, unless it is presented as a generic filter preset.

This avoids maintaining a fake catalogue disconnected from the user's guide.

---

# 27. Sports hub UX improvements worth adopting

## 27.1 Strong focused-card behavior

Review Sports TV focus behavior on-device.

The focused card must clearly identify:

```text
event
status/time
league/sport
available playback choices
```

without making selected filters visually compete with current focus.

## 27.2 Direct OK behavior

Recommended:

If exactly one high-confidence playable channel exists:

```text
OK -> play
```

If multiple choices exist:

```text
OK -> event details/channel choice
```

If no channel is resolved:

```text
OK -> event details + Find another channel
```

Long-press behavior is optional; only add it if StreamVault already has a stable TV long-press interaction convention.

## 27.3 Manual picker

Search suggestions should prioritize:

```text
event-named channels
recognized team names
league/series names
existing related mapped channels
```

The picker should remain usable by normal text search.

## 27.4 Preserve D-pad stability

When filters/lists reorder, keys/stable item identity should prevent focus-node confusion.

Use stable event IDs and stable filter IDs.

Do not rely on list position as identity.

---

# 28. Event card information hierarchy

Recommended:

```text
[SPORT / LEAGUE]

FC Barcelona
vs
Real Madrid

ON NOW / 21:00

2 channels
```

Non-team example:

```text
FORMULA 1

Japanese Grand Prix
Qualifying

Saturday · 09:00

1 channel
```

Fallback generic example:

```text
FOOTBALL

Live Spanish Football

ON NOW

beIN Sports 1
```

Never fabricate teams or event detail.

---

# 29. Event details

Keep the current details dialog and extend only as needed.

Display:

```text
canonical event title
sport
league
event/session type
schedule
raw/clean description
playback choices
match reason where useful in diagnostics or picker
```

Optional user-facing source labels:

```text
From guide
Found by channel name
Your saved choice
```

Use plain language, not internal scoring values.

---

# 30. Search for another channel

New flow:

```text
Event details
    |
    +-- existing mapped channels
    |
    +-- Find another channel
             |
             v
       fallback search
             |
             +-- event-name candidates
             +-- team candidates
             +-- series candidates
             +-- manual text results
```

Results must be limited to the user's currently playable/allowed channel set.

Respect:

```text
hidden channel rules
adult/protected filtering
active provider / combined profile
source membership
```

Do not let Sports bypass normal StreamVault visibility rules.

---

# 31. Testing — mandatory before more heuristic expansion

Testing is the most important unfinished original area.

## 31.1 Classification unit tests

Include:

```text
Barcelona vs Real Madrid
FC Barcelona - Real Madrid CF
Live Premier League Football
LaLiga Football
Formula 1 Japanese GP Qualifying
Japanese Grand Prix - Race
UFC 330
UFC 330 Main Card
NBA Basketball
ATP Madrid: Alcaraz vs Sinner
SportsCenter
Match of the Day
Football Highlights
Premier League Preview
Post Match Show
Sports Documentary
```

Assert:

```text
isSports
isCompetitiveEvent
sport
eventType
league
confidence band
```

where relevant.

## 31.2 Alias tests

Cover:

```text
Manchester United / Man Utd / MUFC
FC Barcelona / Barcelona / Barça
Bayern München / Bayern Munich
Borussia Dortmund / BVB
Premier League / EPL
Champions League / UCL
Formula 1 / Formula One / F1
```

Include false-positive aliases.

## 31.3 Event extraction tests

Test:

```text
two-team matchup
one named competitor
no competitors
fight card
race event
race session
golf tournament
tennis matchup
generic sports programme
```

## 31.4 Deduplication tests

Must merge:

```text
Barcelona vs Real Madrid @ 21:00
FC Barcelona - Real Madrid CF @ 21:00
LaLiga: Barcelona v Real Madrid @ 20:55
```

Must not merge:

```text
live match vs replay
same teams in another competition
same teams much later
race vs qualifying
pre-game show vs match
men's vs women's team when distinguishable
```

## 31.5 Temporary-channel fallback tests

Given channels:

```text
EPL | 18 Oct | Arsenal vs Liverpool
EPL | 17 Oct | Arsenal vs Liverpool
Arsenal TV
Liverpool FC TV
Sky Sports Premier League
```

and event:

```text
Arsenal vs Liverpool
18 Oct
```

expect the dated event channel to rank first.

Do not auto-match:

```text
Arsenal TV
```

solely because one team name matches.

## 31.6 Fight fallback tests

Given:

```text
UFC 330 | Pereira vs Ankalaev
```

ensure both fighters are required for strong event-specific match.

## 31.7 Racing fallback tests

Given:

```text
F1 | Japanese Grand Prix | Qualifying
F1 | Japanese Grand Prix | Race
Sky Sports F1
```

ensure the session-specific channel outranks generic F1 for qualifying.

## 31.8 Manual-pick tests

Test:

```text
event pick precedence
expiry
team preference
series preference
forget action
preference does not bypass channel visibility
```

## 31.9 Fixture-driven XMLTV tests

Create:

```text
good_sports_epg.xml
minimal_sports_epg.xml
messy_sports_epg.xml
multichannel_same_match.xml
multilingual_same_match.xml
replays_and_highlights.xml
non_team_sports.xml
missing_categories.xml
generic_sports_shows.xml
same_teams_twice.xml
```

## 31.10 M3U + XMLTV integration fixtures

Test:

```text
exact tvg-id mapping
existing persisted mapping
missing tvg-id
duplicate channel variants
unresolved EPG channel
temporary event channel fallback
manual selection fallback
```

No internet access in tests.

Phase B covers the current resolved path with exact, missing, unresolved, and
duplicate-event fixtures; existing data-layer tests cover persisted mappings
and EPG manual overrides. Temporary event-channel fallback and Sports-specific
manual channel selection will be tested with Phases E/F.

---

# 32. Real-device verification

Run after automated coverage is in place.

## Scenario A — normal resolved EPG

- [x] Load a real playlist + EPG on the isolated AVD.
- [x] Confirm current sports events appear.
- [x] Confirm the Golf event's mapped channel is available.
- [x] Play it; Golf playback remained progressing for roughly two minutes.
- [x] Return to Sports.

## Scenario B — duplicate broadcasts

- [x] One guide event appears with two mapped channel choices.
- [x] Confirm one canonical event card.
- [x] Confirm both mapped playback choices are accessible in the UI; stable playback has not been confirmed for every choice.

## Scenario C — combined profile

- [ ] Same event supplied by channels from multiple profile members.
- [ ] Confirm choices are correct.
- [ ] Switch source membership.
- [ ] Confirm Sports refreshes.

## Scenario D — temporary event channels

- [ ] Use a provider with event-named channels if available.
- [ ] Remove/disable the direct EPG channel mapping for the test.
- [ ] Confirm fallback finds the correct temporary channel.
- [ ] Confirm wrong-date temporary channel is not preferred.

## Scenario E — manual override

- [x] Select a different channel.
- [x] Save event preference.
- [x] Verify the saved channel is promoted and added to the event when absent from direct EPG choices.
- [x] Forget it.
- [x] Verify normal resolution resumes and the added channel is removed.

AVD check on `emulator-5590`: for Burnley vs Wolverhampton Wanderers, selected the
ranked temporary `BR| DISNEY+ 028` channel. The details view showed `SAVED` and
15 choices; forgetting the pick restored the original 14 choices with no saved
badge. No event preference remains. Selecting a manual result also opened the
existing live player as designed by the current UI callback.

## Scenario F — non-team sport

- [x] Golf and surfing examples from the real EPG.
- [x] Verify cards do not fabricate home/away teams.
- [x] Verify event/session titles are readable.

## Scenario G — visibility/security rules

- [x] Hide a channel.
- [x] Ensure it disappears from Sports; the visible-channel input fell from 28,398 to 28,397, then returned to 28,398 after the channel was unhidden.
- [x] Verify protected/adult channel rules remain intact through fallback-index and remembered-pick tests that exclude protected, adult, and unplayable channels.

---

# 33. Performance validation

The classifier/assembler has already been CPU-profiled.

Now measure the full Android path.

Record:

```text
guide programme count
visible channel count
active source type
combined-profile member count
cold Today load
warm Today cache load
cold Upcoming load
fallback index construction time
fallback event search time
UI stall/frame issues
device/emulator model
```

## Targets

Do not impose arbitrary hard limits before measuring.

Goals:

- Sports opening should feel immediate after cached data exists.
- Filtering should not re-query EPG.
- Fallback matching should not scan tens of thousands of channels expensively per event.
- Compose recomposition must not trigger EPG reloads.
- On Now refresh should avoid rebuilding unrelated windows.

If fallback indexing adds measurable startup cost, build it lazily only when:

```text
direct mapping failed
or
user opens Find another channel
```

This is likely preferable because normal EPG-first users may never need it.

---

# 34. Diagnostics expansion

Keep existing `SportsHub` DEBUG diagnostics.

Add fallback-matcher diagnostics when enabled:

```text
event id
event normalized title
competitor aliases considered
event/session phrases
candidate channel
normalized candidate name
date extracted from channel name
fallback score
match reason
accepted/rejected
remembered-pick source
```

Do not log:

```text
stream URLs
credentials
tokens
playlist URLs
sensitive provider details
```

Production logs should remain quiet by default.

---

# 35. Privacy and data boundaries

Keep Sports local.

Do not send:

```text
EPG content
playlist data
channel names
viewing history
credentials
playlist URLs
Xtream credentials
```

to a third-party Sports service.

Manual preferences remain local/profile-scoped using the same storage model as other user preferences where appropriate.

---

# 36. Explicitly rejected external-provider patterns

Do **not** implement these external-provider assumptions in this plan:

- ESPN scoreboard API.
- ESPN standings API.
- ESPN team schedule API.
- ESPN logos/CDN as a dependency.
- ESPN broadcaster data as a dependency.
- external fixture source as canonical event source.
- ESPN league slug catalogue.
- A hardcoded US/CA/UK region preference.
- API-first event discovery.

Reason:

StreamVault's core architecture is intentionally:

```text
EPG -> SportEvent
```

not:

```text
external sports API -> event -> search EPG
```

---

# 37. Sports hub improvements adopted

The revised plan adopts these ideas:

- [x] event-named temporary IPTV channel fallback;
- [x] date-aware temporary-channel matching;
- [x] efficient channel indexing for fallback search;
- [x] manual channel correction;
- [x] event-scoped remembered channel choice;
- [ ] team/series preference where useful;
- [x] forget/reset mapping;
- [x] distinct handling for team sports, fights, racing, and field/tournament sports;
- [x] stable item identity for TV focus;
- [ ] optional event-specific zap list;
- [x] conservative matching rather than broad fuzzy broadcaster matching;
- [x] explainable candidate ranking;
- [ ] source/language/region preferences that respect the user rather than hardcoded geography.

The fallback is geography-neutral when no explicit language/region preference is
available. Passing explicit source/language/region preferences into ranking is
not implemented yet.

---

# 38. Recommended implementation order from current state

This order assumes the existing branch described in the supplied implementation map.

## Phase A — Verify the current implementation before extending it

- [x] Run current branch with a real playlist and populated EPG on the isolated worktree AVD.
- [x] Verify On Now end-to-end, including stable playback on the Golf event. The separate WSL FHD choice stalled after its first frame.
- [x] Verify Today.
- [x] Verify Upcoming.
- [x] Verify sport/league filters.
- [x] Verify duplicate broadcast grouping and playable choices for the observed event.
- [x] Verify details.
- [x] Verify stable playback on a surfaced channel choice; see the WSL channel failure below.
- [x] Record current guide false positives and playback findings before extending heuristics.

**Do this first.**

Do not expand matching logic before observing actual EPG/provider behavior.

---

## Phase B — Finish original automated test debt

- [x] Classification unit tests.
- [x] Normalization/alias tests.
- [x] Event extraction tests.
- [x] Fingerprint tests.
- [x] Dedup tests.
- [x] Status tests.
- [x] XMLTV fixtures for categorized/minimal guides, exclusions, non-team events, and duplicate broadcasts.
- [x] M3U/XMLTV fixtures for the current resolved path (exact, missing, unresolved, and duplicate-event cases; data-layer persistence/override tests pass). Future event-channel fallback tests belong to Phases E/F.
- [x] Run `LiveGraphBehaviorTest` on the isolated worktree AVD.

This creates a safety net before changing event identity and parsing.

---

## Phase C — Finish event-model and parsing depth

- [x] Refine competitor representation.
- [x] Refine league representation: canonical league identity now drives alias-aware event merging, event IDs, UI grouping, and league filters while preserving the display label.
- [x] Add/complete `SportsEventType`.
- [x] Complete dedicated event extraction boundary.
- [x] Improve competitor extraction.
- [x] Improve non-team event/session extraction.
- [x] Broaden aliases from structured registries.
- [x] Improve non-team title cleanup.

---

## Phase D — Strengthen deduplication

- [x] Confidence-aware merge logic.
- [x] Replay/highlight separation.
- [x] Same-teams-twice handling.
- [x] Multilingual match merging.
- [x] Non-team fingerprinting.
- [x] False-positive fixture coverage.

---

## Phase E — Add fallback channel discovery

Only after the normal EPG mapping is proven.

- [x] Create a cached normalized channel index for the effective playable channel set; rebuild it when channel, profile, visibility, or guide revision inputs change.
- [x] Add conservative two-competitor channel-name matching with aliases.
- [x] Add fighter-pair matching using full names or distinctive surnames.
- [x] Add event-title and series phrases for non-team sports, with event-specific phrases scoring above generic series-only names.
- [x] Parse ISO, named-month, and unambiguous numeric dates; ignore ambiguous numeric dates and reject incompatible dates, with a two-hour local-midnight boundary allowance.
- [x] Return ranked candidates with score and match reasons.
- [x] Preserve direct EPG choices; only enrich an event when none has a playable stream URL. Phase F adds explicit manual search; runtime playback failure does not yet trigger an automatic search.
- [x] Add fixture-level tests for matching, date rules, quality variants, direct-mapping precedence, and protected/unplayable channels.

---

## Phase F — Add manual correction and preferences

- [x] `Find another channel`.
- [x] Manual channel search over the current visible, playable channel index.
- [x] "Use for this event."
- [x] Event-pick expiry (seven days).
- [ ] Optional team preference.
- [ ] Optional series preference.
- [x] "Forget my choice."
- [x] Ensure preferences never bypass hidden/protected or unplayable channel rules.
- [x] Add tests for search ranking, preference policy, expiry, and persistence.

Team and series preferences remain optional and are deferred; event-scoped choices cover the v1 correction flow.

---

## Phase G — Channel presentation refinement

- [x] Group quality variants through StreamVault's existing live-channel grouping preference; grouped channels retain their variant and quality-option data, while raw mode keeps each channel separately selectable.
- [x] Preserve all playable alternatives in event details and manual search.
- [x] Keep fallback ordering confidence-ranked and neutral by channel name when no explicit user/source language preference is available; do not favor a fixed region.
- [x] Review direct-OK behavior: selecting a channel choice enters the existing live player, while Program Details remains a separate action.
- [ ] Consider event zap-list if player integration is simple.

The event zap-list remains deferred: `LiveChannelPlaybackRequest` carries one
channel choice, so event-only CH+/CH− cycling would require a wider player
navigation contract rather than a small Sports integration.

---

## Phase H — Real-device and large-guide validation

- [x] Android TV device/emulator run on the isolated AVD.
- [x] D-pad/focus review, including first-result focus, navigation back to search, and moving from search to the first result after keyboard dismissal.
- [x] Real EPG false-positive review for the guide-derived news/time-title case.
- [x] Real EPG false-negative review; repaired four configured-guide cases: `Fudbal - Španska liga: Deportivo - Levante`, `Fudbal - Francuska liga: Le Mans - Toulouse`, `Live Bundesliga: Eintracht Frankfurt-Koln Etapa 6`, and `ATP1000 '26: Shanghai Masters QF4`. A follow-up guide-backed check also corrected an EFL Championship entry whose description referenced Premier League.
- [ ] Real fallback-channel matching review.
- [x] End-to-end performance measurement on a configured guide, including cold and cache-hit Today/Upcoming selection with fresh screenshot confirmation.
- [ ] Tune confidence thresholds based on evidence.

The 2026-10-16 guide load logged the four repaired titles as competitive, with team pairs extracted for the football fixtures and `Quarterfinal 4` for ATP QF4. The focused classifier/XMLTV suites pass. The current guide produced zero automatic fallback matches because all assembled events had direct EPG mappings; the manual picker review surfaced the mapped options only. Fallback precision and threshold tuning therefore remain open until a real unresolved event is available.

---

## Phase I — Architecture cleanup only if justified

After behavior is stable:

- [ ] Extract a Sports repository if it materially improves testability/ownership.
- [x] Keep current cache; configured-guide measurements recorded a 0 ms warm Today cache hit.
- [x] Do not add a durable Sports index without a changed-program feed; current derived snapshots remain in memory.
- [ ] Document any future EPG refresh API changes that would enable incremental event rebuilding.

---

# 39. Acceptance criteria for revised v1

The revised v1 is complete when:

## Core EPG-first path

- [x] A user with a valid playlist and EPG can see current/upcoming sports.
- [x] Events are grouped instead of shown as duplicate channel programmes.
- [x] Direct EPG/channel mappings remain the primary playback path.
- [x] Multiple mapped channels remain accessible.
- [x] Playback uses the existing StreamVault player.

## Classification quality

- [x] Team sports work across common title variants.
- [x] Replays/highlights/previews are not treated as live matches.
- [x] Non-team sports have readable event/session cards.
- [x] Generic low-information EPG entries degrade gracefully.

## Channel-discovery resilience

- [x] If direct EPG mapping fails, temporary event-named channels can be found.
- [x] Wrong-date temporary channels are rejected/down-ranked.
- [x] The user can manually select a channel.
- [x] The user can remember and forget a useful mapping.
- [x] Fallback search remains fast on large channel lists.

## Testing

- [x] Classifier tests exist.
- [x] Dedup false-positive tests exist.
- [x] XMLTV fixtures exist.
- [x] M3U/XMLTV integration fixtures exist.
- [x] Fallback matcher tests exist.
- [x] Manual-pick tests exist.

## Runtime verification

- [x] Tested on a configured Android TV environment.
- [x] Cold/warm load measurements recorded.
- [x] No major D-pad/focus regressions.
- [x] No visibility/security bypass.
- [x] No external sports API required.

---

# 40. Future work after revised v1

Not part of the immediate implementation:

```text
live scoring provider
standings provider
team metadata/artwork provider
My Teams based on canonical EPG teams
league preference manager
sports-specific notifications
event zap list if deferred
```

Each remote provider, if ever added, must be reviewed separately.

Do not weaken the EPG-first core to add any of these.

---

# 41. Final architecture

```text
                          USER DATA
                   +---------------------+
                   |                     |
                   v                     v
                XMLTV                M3U/Xtream
                   |                     |
                   v                     v
         existing resolved EPG       playable channels
                   |
                   v
          SportsEpgClassifier
                   |
                   v
          SportsProgrammeParser
                   |
                   v
              SportEvent
                   |
             normalize
                   |
             deduplicate
                   |
          +--------+---------+
          |                  |
          v                  v
 direct mapped channels   no/weak mapping
          |                  |
          |                  v
          |         fallback channel matcher
          |         - event channel names
          |         - date-aware matching
          |         - team/event/series phrases
          |                  |
          |          +-------+--------+
          |          |                |
          |          v                v
          |     auto candidate    manual picker
          |          |                |
          |          +-------+--------+
          |                  |
          |          remembered preference
          |                  |
          +---------+--------+
                    |
                    v
              BroadcastOption[]
                    |
                    +----------------------+
                    |                      |
                    v                      v
                Sports UI            Future optional
                    |                LiveScoreProvider
                    v
         Existing StreamVault Player
```

The central rules are:

> **EPG defines the Sports event and normal broadcast mapping.**

> **The user's playlist defines what StreamVault is allowed to play.**

> **Fallback matching exists only to recover from incomplete IPTV/EPG metadata.**

> **Manual correction is preferable to aggressive guessing.**

> **No external sports API is required for the Sports Hub.**

> **Future scoring enriches an EPG-derived event; it never becomes the source of truth for the Sports screen.**

---

# 42. Immediate next action for the agent

**Status snapshot, 2026-10-16:** Phase E fallback, Phase F manual correction,
and core Phase G channel presentation are implemented; team/series preferences
and the event zap-list remain deferred. The real-guide false-negative review
found and fixed four classifier/parser misses, with 48 Sports unit tests
passing. Golf remains the stable playback pass. Both WSL choices were retested
for 61 screenshots at two-second intervals and still failed: FHD froze after
HLS first-frame success and decoder stalls; HD stayed buffering after a
network timeout. Provider/combined-profile switching and provider/external
EPG refresh remain unexercised on the configured AVD. Fallback precision
remains unmeasured because every event had direct EPG mappings. See
[Sports-IMPLEMENTATION.md](Sports-IMPLEMENTATION.md) for evidence.

**Progress, 2026-10-17:** Phase C league representation is complete. The
league registry now returns canonical IDs, normalized aliases, sport identity,
and display names. Classification carries this metadata into assembled events;
league aliases merge consistently and canonical IDs back event identity, filters,
and UI row grouping. The configured guide exposed a title/description conflict
on `Burnley vs Wolverhampton Wanderers - EFL - English Football League SkyBet -
Championship 2026/2027`; title-first classification now identifies EFL
Championship despite generic Premier League text in the description, and
competitor parsing preserves Burnley and Wolverhampton Wanderers. The EFL
Championship label is localized through the owning module's resources in all
38 advertised locales. Bare `EFL` and `EFL Cup` no longer resolve to
Championship. Focused Sports coverage passes 50 tests across seven suites,
including alias lookup, cross-channel merging of `English Premier League` and
`EPL` entries, and this guide regression. The corrected featured fixture and
selected EFL Championship filter were verified on `emulator-5590`;
see [league-regression.png](../artifacts/sports-ui-review/league-regression.png).

**Progress, 2026-10-16:** Core Phase A guide and playback checks are complete on the isolated
`SportsHub_Worktree_20261016` AVD (`emulator-5590`). The configured guide was
loaded by the user. The installed debug APK was built from this worktree and
its local/device SHA-256 matched. The older AVDs were not used for this pass.

The guide-backed UI checks cover On Now, Today, Upcoming, Basketball and
LNB Pro A filtering, event details, and a mapped event with multiple playable
choices. Earlier cold-load observations were On Now 57 s / 334 available
channels, Today 93 s / 616, and Upcoming 93 s / 585. In the current confirmed
run, On Now loaded in about 50 s / 347 channels, Today in 95.1 s / 580, and
Upcoming in 100.4 s / 585. Remote selection and screenshots confirmed the
Today and Upcoming tabs and their event content. Revisit loads inside the
five-minute window took 6.6 s for Today and 5.5 s for Upcoming. Counts and
timings describe this configured guide and isolated AVD.

The subsequent instrumented build measured cold `window-cold` stages at 18.8 s
for On Now, 40.7 s for Today, and 53.5 s for Upcoming. Each window loaded from
28,398 visible channel rows. Resolved-program loading processed 6,497 rows
(6,497 keys) in 0.57 s for On Now, 198,808 rows (6,546 keys) in 4.5–9.8 s for
Today, and 102,256 rows (6,418 keys) in 1.02 s for Upcoming. Classification
took 2.9 s, 16.3 s, and 38.0 s respectively; assembly took 0.16 s, 0.40 s,
and 0.70 s. First fallback-index construction took 1.47 s; the later windows
reused it in 20–30 ms. A same-process Today revisit logged a 0 ms cache hit.
Channel-normalizer regex caching reduced visible-channel preparation from
about 40 s to about 14 s, and avoiding redundant sport-term checks reduced the
latest Today classification stage from 19.4 s to 16.3 s. Individual Room
statements and fallback-search time are still not isolated. Provider/combined
profile switching remains unmeasured. A visibility refresh check hid
`PT| SPORT TV 6 FHD`, observed 28,397 visible Sports input rows, then unhid it
and observed 28,398 after refresh. Provider data was preserved.

The real guide exposed a false matchup from a local news programme whose title
contained a schedule time. The classifier now rejects news/weather/traffic and
edition-style show titles, and excludes broadcast-time strings such as `5:00p`
from competitor extraction. Fixture coverage now verifies that case. A genuine
French basketball event (`Asvel vs Roanne`) also exposed missing `basket-ball`
and `LNB Pro A` recognition; both are covered by registry/fixture assertions.
Guide classification and assembly now run on `Dispatchers.Default` with
cancellation checks, keeping the large window scan off the UI thread.

Sports JVM coverage was 40 passing tests before Phase F. Focused Phase F tests
now pass for manual search, bounded result ordering, preference policy, expiry,
and event-pick persistence. Phase C/D adds typed canonical competitors, event
type and session parsing, retained raw programme references, and
confidence-aware compatibility grouping. Phase E adds a cached channel index,
conservative team/fight/event matching, date checks, ranked reason-bearing
candidates, and direct-mapping precedence. Phase F adds manual search,
event-scoped seven-day choices, forget behavior, and visible/playable-channel
validation. The Android TV AVD showed the first result focused on picker open,
search remained focused while typing, and the `ESPN` query returned configured
channels. After the keyboard was dismissed, D-pad Down moved focus to the first
result. After logcat was cleared before the query, no OOM, fatal exception, or
ANR appeared. Event choice and forget behavior remain covered by unit tests
rather than a device playback session. `LiveGraphBehaviorTest` also passed on the
isolated AVD.

Playback passed for the Golf event's mapped `EXYU| SPORT KLUB GOLF HD`
channel: 61 screenshots at roughly two-second intervals had 61 unique hashes
across about two minutes, the media session stayed `PLAYING` with `error=null`,
and HLS prepare/first-frame logs were present. Back returned to Sports.

In the earlier playback capture, `PT| SPORT TV 6 FHD` for the World Surf
League event produced an HLS first frame, then stopped progressing: 46
screenshots at two-second intervals had one unique image, the media session
ended in `NONE` with `error=null`, and logs showed no-candidate recovery with
TS fallback disabled. The second WSL choice, `PT| SPORT TV 6 HD`, stayed on
the buffering screen for 61 screenshots over roughly two minutes. Those
screenshots differed because the loading indicator animated; no video first
frame was logged. The media session remained `BUFFERING`, `error=null`, in the
final snapshot, and sanitized logs recorded a network source error followed by
`XTREAM_TS_FALLBACK` selection. `AR | BEIN-SPORTS ENGLISH 1 (Tod) [BK]`
reached `PLAYING` with `error=null` and produced an HLS first frame, but only a
short observation of a static event bumper was made. No stream URLs,
credentials, or provider identifiers are recorded here.

The WSL choices were retested on 2026-10-16 with paced captures. FHD had 61
screenshots at a 2.00-second average interval across 120 seconds, but only 2
unique hashes: a video frame appeared and then froze. The final media session
was `NONE`, `error=null`; sanitized logs showed HLS prepare/first-frame,
decoder stalls, and no-candidate recovery with TS fallback disabled. HD had 61
screenshots at a 2.00-second average interval across 120 seconds and 61 unique
hashes from the animated loading indicator, with no video frame. Its final
session was `BUFFERING`, `error=null`; sanitized logs showed a network timeout,
one retry, HLS prepare, and no first-frame success. No MPEG-TS fallback was
observed in either retest. This confirms the previously reported failures for
these two choices; it is not a general live-channel survey.

Remaining work: provider/combined-profile switching and provider/external EPG
refresh need an AVD with those sources configured; fallback precision needs a
real event without direct mappings; and the WSL failures need a stream/provider
diagnosis or code-level resolution. Visibility refresh is verified: hiding
`PT| SPORT TV 6 FHD` changed the visible Sports input from 28,398 to 28,397
rows, and unhiding it restored 28,398. Aggregate resolved-program timing is
measured, but individual Room statements and fallback-search time are not
isolated. Keep the fallback matcher behind the direct EPG path.
