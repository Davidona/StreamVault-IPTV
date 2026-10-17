# Sports Hub v1 — Integration Map

**Updated:** 2026-10-17 — canonical league modeling and EFL guide regression verification.

## Existing integration points

- EPG programmes are exposed as `Program` through `EpgRepository`.
- `EpgRepository.getResolvedProgramsForChannels` applies StreamVault's existing
  EPG-to-channel mapping and returns programs keyed by the mapped stream ID or
  EPG channel ID.
- `ChannelRepository` and `CombinedM3uRepository` expose playable channels for
  the active provider or combined M3U profile.
- Live playback already crosses the feature boundary through
  `LiveChannelPlaybackRequest` and the app's existing player mapper.
- The app shell derives its top-level navigation from
  `AppTopLevelDestination`; TV browse colors and focus behavior live in
  `:core:ui`.

## Current implementation

- Added Sports as a top-level app destination and a route owned by `:feature:live`.
- Read EPG time windows for channels in the active live source, classify
  competitive sports programmes from title, description, genre/category, and
  channel metadata, extract recognized league names, and merge duplicate event
  broadcasts within a 15-minute start-time tolerance.
- Support On Now, the remaining schedule for the local calendar day, and the
  next seven calendar days after today. On Now refreshes every minute; date
  windows reload when selected and at local midnight.
- Cache classified Today and Upcoming event snapshots in memory for five
  minutes. Provider EPG sync metadata, external source refreshes, source
  assignments, combined-profile membership, and hidden live channel/category
  preferences invalidate the cache and reload the selected window.
- Filter assembled events by Football, Basketball, Motorsport, Other, and
  detected league in memory, without issuing another EPG query when a filter
  changes. Show the distinct playable-channel count for the visible events.
- Emit classifier and deduplication diagnostics only when the Android `SportsHub`
  log tag is enabled at DEBUG level. Enable it with
  `adb shell setprop log.tag.SportsHub DEBUG`. With diagnostics enabled, read
  persisted channel-mapping confidence in one batched query per provider.
- Show a detected league on event cards; clean matchup titles by removing the
  league prefix and rendering team pairs consistently.
- Open a TV-friendly details dialog from featured and regular event cards,
  showing the schedule, description, and all playable channel choices.
- Add a `Find another channel` picker to event details. Empty search ranks
  event-specific candidates and direct mappings; typed search covers the
  current visible, playable channel index and returns at most 40 results.
- Use the existing live-channel presentation setting for quality variants:
  grouped mode supplies the group's retained variants and quality options to
  Sports, while raw mode leaves each channel separately selectable. Sports
  does not discard playable alternatives or merge language/region variants
  with a new global rule.
- Keep equal-confidence fallback results in neutral channel-name order when
  no explicit language or region preference is available. Do not apply a
  fixed geography preference. Direct channel choices still open the existing
  live player; event-only CH+/CH− cycling is deferred because the current
  playback request represents one channel.
- Let the user choose a channel for this event, remember it for seven days,
  and forget it later. Revalidate saved picks against the active playable
  index, including hidden, adult, protected, and stream-URL rules.
- Focus the first picker result when it appears, keep search focus while the
  user types, debounce edits by 200 ms, and avoid rerunning event-wide fallback
  scoring for typed queries. Keep only the top eight automatic event matches
  and top 40 manual results to bound transient work on large guides.
- Keep the screen event-first. Show a featured event and focused event cards
  with each matched playable channel as a direct playback choice. Upcoming
  cards include the event date; the LIVE badge appears only while the EPG
  programme is airing.
- Reuse existing app playback and browse styling. Localize Sports labels and
  screen states in every advertised locale.
- Keep scores, external sports feeds, a persisted event index, and new database
  migrations out of the current implementation.
- Reuse compiled normalization patterns and pre-normalized sport/channel terms.
  Compute event fingerprints only for competitive candidates unless diagnostics
  are enabled, then reuse each fingerprint during grouping and event creation.
- Keep aliases in a shared registry. Added common MUFC, BVB, and Bayern München
  spellings, plus aliases for Copa, UEFA, EFL, Scottish, Saudi, and Liga MX
  competitions where the source names are distinctive.
- Use real-guide-informed negative classification for news, weather, traffic,
  edition-style programmes, and schedule-time strings that can look like
  matchup competitors. Added French `basket-ball` recognition and the `LNB Pro
  A` league alias after the configured guide exposed a real event.
- Review configured-guide false negatives and add focused fixture coverage.
  Added `fudbal` as a football term, compact matchup parsing for a single
  unspaced hyphen inside a competition-prefixed title, trailing `Etapa N`
  session extraction, ATP 1000 tour-code recognition, and tennis `QF N`
  quarterfinal sessions. The guide examples now classify as competitions;
  team fixtures expose both competitors and ATP QF4 exposes `Quarterfinal 4`.
- Run Today/Upcoming classification and assembly on `Dispatchers.Default`,
  checking coroutine cancellation during channel/program scans so large guide
  windows do not monopolize the UI thread.
- Parse event-shaped title data in `SportsEventParser`, with a list-based
  competitor model, canonical aliases, content type, and sport-specific
  session. Keep the original `Program` records on each assembled event for
  fallback presentation and diagnostics. Golf, tennis, motorsport, and fight
  cards can carry sessions without fabricated competitors.
- Deduplicate exact event identities with confidence, league, session, and
  content-type compatibility checks. Keep a list of groups per identity so
  repeated same-team fixtures and partially described league variants remain
  separate when the metadata conflicts. Use a 15-minute window for team
  fixtures and a 30-minute window for non-team schedules.

## Current limitations

- On Now still queries once per minute. Today and Upcoming snapshots are
  in-memory only, and guide changes trigger a full selected-window rebuild.
  A debug-only `SportsPerf` tag now records aggregate device timings for visible
  channel preparation, resolved-program loading, classification, event
  assembly, fallback-index construction, and cold/cache-hit windows. Enable it
  with `adb shell setprop log.tag.SportsPerf DEBUG`; entries contain stage
  names, elapsed milliseconds, and numeric counts only.
- There is no durable Sports index. The existing programs table is already the
  persisted guide source, while Sports events are derived from resolved
  programs, active-source membership, channel mappings, and visibility rules.
  A second Room index would duplicate derived state. Incremental rebuilds also
  need changed programme IDs plus affected source/channel/time ranges, which
  current EPG refresh APIs do not expose. Keep the bounded in-memory cache and
  existing invalidation until that change feed exists. Extracting the loader
  from the ViewModel into a feature-owned repository is a useful future
  separation, but by itself would not make the index durable or incremental.
- Provider/profile and EPG-refresh invalidation could not be exercised on the
  configured AVD without changing user setup. It has one saved Xtream provider,
  no combined profile, and no standalone EPG source. The provider sync dialog
  exposes no EPG-only refresh; no provider/catalog sync was run. The TV Guide
  screen's Refresh action reloads its local snapshot, not the provider feed.
- Sport filters group every classification outside Football, Basketball, and
  Motorsport under Other, including unknown sports.
- League recognition and team aliases remain curated sets. The new parser has
  an initial session vocabulary; broad multilingual event-name equivalence and
  noisy IPTV channel-name normalization remain future work.
- Event details are available in a dialog; richer team/competition detail
  fields still depend on what the EPG provides.
- Team and series preferences are optional and deferred; only event-scoped
  remembered choices are implemented.
- Sports respects the shared live channel grouping preference rather than
  forcing grouped or raw variants in one feature. No explicit user/source
  language-ranking signal is passed to fallback matching, so equal-score
  choices remain alphabetic and geography-neutral.
- Earlier cold-load observations were On Now 57 s (334 available channels),
  Today 93 s (616), and Upcoming 93 s (585). The next pass recorded On Now at
  about 50 s (347), Today at 95.1 s (580), and Upcoming at 100.4 s (585), with
  five-minute revisits taking 6.6 s and 5.5 s. After hot-path optimization, a
  debug-instrumented run on `emulator-5590` measured `window-cold` at 18.8 s
  for On Now, 40.7 s for Today, and 53.5 s for Upcoming. The respective guide
  resolved channel-program entry counts were 6,497, 198,808, and 102,256;
  visible channel rows were 28,398 in each range. Latest stage timings were:

  | Range | Visible channels | Resolved programs | Classification | Assembly | Fallback index | Window |
  | --- | ---: | ---: | ---: | ---: | ---: | ---: |
  | On Now | 13.7 s | 0.57 s | 2.9 s | 0.16 s | 1.47 s | 18.8 s |
  | Today | 14.1 s | 9.84 s | 16.3 s | 0.40 s | 0.03 s | 40.7 s |
  | Upcoming | 13.8 s | 1.02 s | 38.0 s | 0.70 s | 0.02 s | 53.5 s |

  A warm Today revisit logged `window-cache-hit elapsedMs=0` and displayed
  564 available channels. Resolved-program timing varied between Today runs
  (4.5–9.8 s), so the aggregate shows guide resolution as a material cost but
  does not isolate individual Room statements. Caching `ChannelNormalizer`
  patterns reduced visible-channel preparation from about 40 s to about 14 s.
  Precomputing channel sports metadata and removing a duplicate sport-term scan
  reduced the latest Today classification stage from 19.4 s to 16.3 s. The
  configured guide is large and Upcoming remains CPU-heavy; classification
  continues on `Dispatchers.Default`, so this work does not block the UI thread.
- The Golf mapping remains the stable two-minute playback pass. The two WSL
  Sport TV choices were retested on 2026-10-16 and still fail; current evidence
  is recorded below. Playback across every displayed channel choice is not
  verified.

## Large-guide CPU microbenchmark

The same production Kotlin classifier and event assembler were run in a
standalone JVM harness with synthetic programmes. Each dataset used 90%
entertainment programmes and 10% sports matchups, with three channel broadcasts
per matchup. The harness included classification, candidate fingerprinting,
and event assembly; it excluded database queries, EPG resolution, Android
runtime costs, and UI work.

| Synthetic programmes | Before median | After median | Improvement |
| ---: | ---: | ---: | ---: |
| 5,000 | 1,203.72 ms | 80.81 ms | 14.9× |
| 20,000 | 4,794.96 ms | 266.62 ms | 18.0× |
| 50,000 | 11,993.92 ms | 871.93 ms | 13.8× |

Each timing is the median of three runs after one warmup, on the local Windows
JVM (Microsoft OpenJDK 17.0.20). These figures quantify the classifier and
dedupe CPU path only; they are not an end-to-end device performance claim.

## Device verification record — 2026-10-16

The user configured a provider on the isolated Android TV AVD
`SportsHub_Worktree_20261016` (`emulator-5590`). The installed APK was built
from this worktree, and its local/device SHA-256 matched. The older emulator
was not used. Guide values below describe this run only; provider names, URLs,
and credentials are intentionally omitted.

| Check | Observation |
| --- | --- |
| On Now | WSL event displayed once with two mapped Sport TV choices. A Golf card exposed `EXYU| SPORT KLUB GOLF HD`; a fight card exposed a beIN Sports choice. |
| On Now / Today / Upcoming | Earlier cold loads were 57 s / 334 channels, 93 s / 616, and 93 s / 585; the current pass loaded On Now in about 50 s / 347, Today in 95.1 s / 580, and Upcoming in 100.4 s / 585. Remote navigation and screenshots confirmed Today and Upcoming were selected. Revisits within the five-minute cache window took 6.6 s for Today and 5.5 s for Upcoming, with the expected event and channel count still visible. |
| Filters / details | Basketball filter showed 55 channels; LNB Pro A narrowed to one channel. Asvel vs Roanne and its French description were visible, and Program Details opened. |
| News false positive | `2 News Nevada vs 5:00p` no longer appeared as a competitive event. |
| Stable playback | Golf mapped channel reached `PLAYING`, `error=null`; 61 screenshots at roughly two-second intervals produced 61 unique SHA-256 hashes over about two minutes. HLS prepare and first-frame logs were present; no fatal/stuck error or MPEG-TS fallback was found. Final frame showed Buick LPGA Shanghai coverage. Back returned to Sports. |
| Earlier FHD capture | `PT| SPORT TV 6 FHD` for the WSL event showed one unique image across 46 screenshots at two-second intervals. The session ended in `NONE`, `error=null`; logs showed HLS first-frame success followed by no-candidate recovery with TS fallback disabled. Superseded by the paced retest below. |
| Earlier HD capture | `PT| SPORT TV 6 HD` stayed on the buffering screen. Although 61 screenshots differed because the loading spinner animated, no video frame or HLS first-frame log appeared. Earlier logs recorded a network source error and selected `XTREAM_TS_FALLBACK`; the final session snapshot was `BUFFERING`, `error=null`. Superseded by the paced retest below. |
| WSL FHD paced retest | On 2026-10-16, 61 screenshots at a 2.00 s average interval covered 120 s and produced 2 unique SHA-256 hashes: video appeared, then froze. The media session ended at `NONE`, `error=null`. Sanitized logs showed HLS prepare and first-frame success, two decoder stalls, then `live-recovery no-candidate` with TS fallback disabled; no fatal error or MPEG-TS fallback was observed. |
| WSL HD paced retest | On 2026-10-16, 61 screenshots at a 2.00 s average interval covered 120 s and produced 61 unique hashes because the buffering indicator animated; no video frame appeared. The final media session was `BUFFERING`, `error=null`. Sanitized logs showed a network timeout, one retry, HLS prepare, and no first-frame success; no MPEG-TS fallback was observed in this retest. |
| Second short playback | The fight event's beIN channel reached `PLAYING`, `error=null`, and HLS first-frame success. Only a short static-event-bumper observation was made, so this is not counted as a stable-feed pass. |
| Phase C/D/E build | The latest debug APK from this worktree was installed with `adb install -r` on `emulator-5590`; local and installed SHA-256 both equal `2D269DB83A791006E593A4480A53DA3349A8FC46445832785E7A2A8706D0F33E`. The configured provider remained available and the WSL event loaded in Sports after relaunch. |
| Phase F picker/search | On the final Phase F build, a guide-backed event opened with the first result focused. D-pad Up returned to search. Query `ESPN` returned configured channels; after the soft keyboard was dismissed, D-pad Down moved focus to the first result. The app remained resumed, and after logcat was cleared before the query, no `OutOfMemoryError`, fatal exception, or ANR appeared. No channel was chosen and no playback was started. |
| Phase F build | Built from this worktree and installed with `adb install -r` on `emulator-5590`, preserving the configured provider. Local and installed SHA-256: `72F05CA007ED06D5A8CA333AFDD56ABEFD97B82733D2F18644C66D05FD1DDFD3`. |
| Performance build | Regex caching and per-channel classification context were installed on `emulator-5590` with `adb install -r`, preserving provider data. Cold windows: On Now 18.8 s, Today 40.7 s, Upcoming 53.5 s; warm Today cache hit 0 ms. The build showed 386/564/585 available channels respectively. Local and installed APK SHA-256 matched: `19965856F24487EA7A05AC3378E82C005D3A2149C00DE8DE73D5E7DEC65DC9B6`. |
| Visibility refresh | Hid `PT| SPORT TV 6 FHD` in Live TV, then Sports reloaded with 28,397 visible channel rows. Unhid that individual channel and refreshed Sports again; the count returned to 28,398. The provider data was preserved. |
| Channel safety tests | Focused fallback-matcher and event-pick policy classes passed (14 + 3 tests), including adult, protected, and unplayable channel exclusions. |
| Real-guide false negatives | Four guide titles were repaired and verified as competitive: `Fudbal - Španska liga: Deportivo - Levante`, `Fudbal - Francuska liga: Le Mans - Toulouse`, `Live Bundesliga: Eintracht Frankfurt-Koln Etapa 6`, and `ATP1000 '26: Shanghai Masters QF4`. Team pairs were extracted for football; ATP QF4 produced `Quarterfinal 4`. A follow-up resolved `Burnley vs Wolverhampton Wanderers - EFL - English Football League SkyBet - Championship 2026/2027` as EFL Championship despite a generic Premier League description. |
| Fallback precision sample | The loaded guide produced zero automatic fallback candidates because all assembled events had direct mappings. The mapped event's picker showed its two direct Sport TV options only. No real unresolved event was available to measure fallback precision or tune thresholds. |
| Provider/profile and EPG refresh | The isolated AVD had one Xtream provider, no combined profile, and no standalone EPG source. The sync dialog had no EPG-only refresh. No provider/catalog sync was run; TV Guide Refresh only reloaded its local snapshot. |
| Sports JVM tests | On 2026-10-17, `:feature:live:testDebugUnitTest --tests 'com.streamvault.feature.live.sports.*'` passed: 50 tests across 7 suites, 0 failures or errors. |
| Phase C league model | Canonical league IDs drive alias-aware merging, event identity, filter keys, and UI grouping. The EFL Championship label uses localized resources for all 38 advertised locales. On `emulator-5590`, the EFL Championship filter showed Burnley vs Wolverhampton Wanderers; the featured opponents no longer include the competition suffix. See `artifacts/sports-ui-review/league-regression.png`. |
| Latest build | Built and installed on `emulator-5590`; local and installed APK SHA-256 matched: `D4319147C105FD943411A5A9BBA589ABDACB426715E500105093DCCFA097FC1A`. The configured provider remained available. |

Remaining checks: provider/combined-profile switching and provider/external
EPG refresh need an AVD with those sources configured; fallback precision
needs a real event without direct mappings; and the two WSL playback failures
need a stream/provider diagnosis or code-level resolution. The cache and
resolved-program stages are measured; individual Room statements and
fallback-search time are not isolated. The four original guide false negatives
and the later EFL league/title mismatch are covered by focused
parser/classifier fixtures.

## Boundaries

- Programme and channel data remains user-owned; only already configured
  channels can be played.
- Sports events are derived when the hub loads from the existing resolved EPG
  API; Compose recomposition does not trigger another EPG query.
- Event status describes the EPG broadcast schedule, not independently
  verified match status.

## Revised plan progress

- Phase A guide-backed UI, filter, details, and mapping checks are complete on
  the isolated AVD. Stable playback succeeded on the Golf channel for the full
  two-minute capture. The two WSL Sport TV choices failed their paced
  progression retests, so every surfaced choice has not yet been verified.
- The 48-test sports JVM suite passes across classification, metadata
  registries, event identity/assembly, broadcast status, XMLTV fixtures, and
  conservative channel fallback matching.
  Added event-type/session, alias, same-team repeat, league-conflict,
  confidence, non-team tournament, and time-tolerance coverage. The fixture set
  covers the local-news schedule-time false positive and French LNB Pro A
  basketball programme. Thirty-nine existing data-layer tests passed for EPG
  resolution and persisted/manual overrides.
- Phase C/D introduced typed competitors, `SportsEventType`, `session`, raw
  `Program` references, and the parser boundary; Phase D adds confidence and
  metadata-aware grouping, separate replay/show content, multilingual team
  aliases, and separate non-team sessions. Broad league enrichment remains
  open where no guide evidence justified it.
- Phase E adds a cached normalized index of visible, playable channels and
  ranked fallback candidates from team pairs, fight pairs, and event-specific
  non-team phrases. ISO, named-month, and unambiguous numeric dates reject
  mismatched event channels; ambiguous numeric dates provide no date bonus.
  Direct EPG broadcasts remain first, and fallback channels are added only
  when an event has no playable direct stream. Runtime playback failures do
  not yet invoke this matcher automatically; the configured WSL event had
  working direct mappings, so automatic fallback playback remains unverified.
- Phase F adds manual event search, seven-day event-scoped picks, forgetting,
  and active-index validation. The picker defaults to an event-specific
  suggestion when available and searches only the current visible/playable
  channel set. Typed queries are debounced and bounded to prevent a large-guide
  scan from exhausting the Android TV heap. Focused matcher and preference
  tests pass; team/series preferences remain optional and deferred.
- `LiveGraphBehaviorTest` passed on the isolated worktree AVD (two test
  methods). The Phase F picker/search received a device smoke check, including
  D-pad Down from the search field after keyboard dismissal; persistent
  choice and forget were validated by unit tests rather than a device playback
  session.
- No external sports provider, score provider, or backend was added. The
  existing resolved EPG-to-channel path remains the primary mapping.

- Phase H found and repaired four real-guide false negatives: two compact
  football matchups, a Bundesliga title with a trailing `Etapa 6` session, and
  an ATP1000 quarterfinal title. Focused classifier/XMLTV coverage and the full
  sports JVM suite pass. Fallback precision remains unmeasured because every
  loaded event had a direct mapping; the mapped-event picker showed only the
  two direct choices. Provider/profile switching and provider/external EPG
  refresh also remain unexercised because the isolated AVD lacks those sources.

