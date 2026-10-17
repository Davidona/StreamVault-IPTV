# Sports loading performance — 2026-10-17

Worktree: `D:/antigravity-scratch/iptv-player/.worktrees/sports-integration`  
Branch: `codex/sports-integration`  
Device: `emulator-5590`, existing configured playlist and guide retained.

## Changes — first pass

- Resolve provider portal configuration once per provider per channel presentation pass, including null results for non-Stalker providers. Previously the callback queried Room for every presented channel.
- Keep one observed channel snapshot per active provider while the Sports ViewModel is alive. Range changes and minute refreshes reuse it. Catalog/presentation/visibility changes replace it and invalidate derived events; combined-profile membership changes restart the appropriate subscriptions.
- Reuse prepared classifier channel contexts within each snapshot. Programme resolution still runs for On Now each minute, so changes in what is airing are not hidden behind a stale event cache.
- Serialize event-cache invalidation through the loading collector, including explicit Retry. A failed channel subscription can be restarted with Retry.
- Skip absent literal tags before running normalization regexes. Existing regex boundaries and decoration order remain authoritative for possible matches.
- Defer the full channel fallback index until an event lacks a playable direct choice or manual channel search requests it. Remembered picks use the visible channel entries independently of that index.
- Share repeated token strings within the fallback index; construct canonical-name and EPG-ID maps lazily. Cache identity uses the actual channel entries instead of allocating a second 28,398-row signature list.
- Add an `initial-content` debug timing covering the initial channel subscription through publication of the Sports UI state. The existing `window-cold` timing now measures processing after the observed channel snapshot is available; it should not be read as total initial navigation latency.

No user-facing text or localization resources changed. No playback path changed, and no durable Sports database was introduced.

## Tests — first pass

- `:domain:test --tests '*ChannelNormalizerTest'`: 17 tests, no failures/errors.
- `:data:testDebugUnitTest --tests '*ChannelRepositoryImplTest'`: 19 tests, no failures/errors.
- `:feature:live:testDebugUnitTest --tests '*sports.*'`: 56 tests, no failures/errors.
- `:app:assembleDebug`: passed; installed with replacement on the configured emulator.
- `git diff --check`: passed.

The new ViewModel tests cover catalog subscription reuse across a minute tick and range changes, category visibility invalidation, changed playback URLs, provider switching, retry after an initial catalog failure, and manual search/remembered choices with deferred indexing. Repository tests verify one portal query for 100 raw or grouped channels, including absent portal configuration. Normalizer coverage includes embedded-token rejection, mixed-case decorations, accented names, and URL-derived transport/quality metadata.

The first test invocation exposed a JUnit setup return-type error in the new test fixture; it was corrected before the passing run. Final verification used `--no-configuration-cache` because the existing feature-boundary task reports configuration-cache incompatibilities.

## Device measurements — first pass

Debug `SportsHub` and `SportsPerf` logging remained enabled throughout these runs. The guide supplied 28,398 visible channels, 6,404 On Now programme groups, and 133 assembled events in the two fresh-process runs below.

| Stage | First pass in this session | Final implementation |
| --- | ---: | ---: |
| Initial visible-channel snapshot | 16,942 ms | 8,843 ms |
| Initial programme resolution | 8,678 ms | 6,100 ms |
| Initial classification | 9,567 ms | 5,611 ms |
| Initial event assembly | 285 ms | 123 ms |
| Eager fallback index | 5,047 ms | Not needed / not built |
| Initial state publication, including channel wait | 41,423 ms | 21,223 ms |
| First automatic On Now refresh | Process ran out of heap during the following EPG read | 6,143 ms; no channel reload |
| Second automatic On Now refresh | Not completed | 5,829 ms; no channel reload |

These are individual debug emulator observations, not a statistically controlled benchmark. The first-pass run already included the portal-query fix and observed channel snapshots, but still built the eager fallback index and lacked the literal normalization guard. Retained pre-change warm-process logs measured approximately 21–25 seconds per minute rebuild, including 14–15.5 seconds preparing channels each time; they are not a fresh-process cold-entry baseline.

The first-pass memory failure was an `OutOfMemoryError` on a 192 MB managed-heap limit during EPG resolution. The final run's initial loaded state showed about 36 MB Dalvik heap allocation in `dumpsys meminfo`, rising to about 37 MB allocated after the capture.

Final UI verification captured 61 screenshots over 151 seconds, using a two-second pause between captures (ADB screenshot/pull overhead made the observed cadence approximately 2.5 seconds). Process 31610 survived throughout, with zero fatal AndroidRuntime entries for that process. Logs show exactly one visible-channel preparation, zero fallback-index builds, the initial window load, and two automatic refreshes. The second refresh changed the event count from 133 to 132 as a programme stopped matching On Now. The final screenshot and UI hierarchy both retained the Sports controls, featured fixture, and league cards. Static screenshots are expected after loading; this was a tab-load test, not a video frame-progression test.

Evidence is under `artifacts/sports-performance/`: `normalizer-build.log`, `before-retained.log`, `after-first-pass.log`, `first-pass-crash.log`, and the final device capture/logs. Screenshots validate tab loading and UI survival, not moving video. This is not live-playback stability certification.

## Continuation — classification cache, retained revisits and Today query

Loading, observed channel snapshots, event snapshots, and filters now live in a feature-owned `@Singleton SportsHubRepository`. The ViewModel delegates to it, so a replacement ViewModel can immediately expose the existing valid state. The repository uses the application's repository coroutine scope and pauses EPG loads when the state has no screen observers. Lightweight guide/source observation and channel invalidation continue while the tab is hidden. Re-entry prunes expired fixtures and rejects a retained snapshot after five minutes or a local-date change; refreshing then runs against the existing channel snapshot. This is an in-process cache, not disk persistence.

`SportsClassificationCache` is a content-based LRU capped at 8,192 entries and an estimated 8 MiB. Keys include title, description, genre, category, detected channel sport, sports-channel evidence, and group title. Programme IDs, schedule timestamps, raw channel identities and stream URLs are not retained in the key. Classification is independent of those fields; event fingerprints are still derived separately for each candidate, preserving channel-dependent fallback identity. Changed text/context misses the cache, negative results are cached, and unusually large individual descriptions bypass storage. Explicit Retry clears the classification cache.

Today now queries `[now, next local midnight)` through the existing overlap queries (`end_time > start` and `start_time < end`), retaining programmes already in progress without fetching programmes that have already ended. Today/Upcoming event-cache hits are also pruned by the current window before presentation.

Guide-source policy is included in revision invalidation alongside sync metadata, guide sources, assignments, hidden categories/channels, profile membership and observed channel presentation changes. No user-facing text changed.

Validation: `:feature:live:testDebugUnitTest --tests '*sports.*'` passed 66 tests with no failures/errors; `:app:assembleDebug` passed. The new tests cover content/context cache identity, negative caching, LRU/byte limits, immediate retained state during a blocked refresh, hidden-category invalidation while unobserved, no EPG reads while unobserved, expired-event removal and the narrowed Today query with an ongoing programme. Existing classifier/parser/assembly/fallback tests remain included.

The first fresh-process device run of this continuation published 133 events in 18,404 ms, including 8,952 ms for 28,398 visible channels, 5,014 ms programme resolution, 3,524 ms classification and 128 ms assembly. Classification already reused 2,793 inputs within that first load, with 3,611 misses and approximately 4.17 MB estimated cache storage. The first automatic refresh took 1,493 ms: 562 ms resolution, 688 ms classification and 82 ms assembly. All 6,404 classifications were cache hits. Debug logging settings were retained from the first pass; these remain individual emulator observations.

The second automatic refresh took 1,457 ms, with 6,400 hits and four misses for changed inputs. A new 61-frame capture with two-second pauses spanned 149.4 seconds. Process 32455 survived both refreshes, with zero fatal AndroidRuntime entries; approximately 38 MB Dalvik heap allocation was observed afterwards. The Sports controls, featured event and league cards remained visible.

The tab was then left on Home for a measured 81-second interval. Sports window-load count stayed at three throughout that interval. Returning exposed 127 retained, time-pruned events (`retained-presentation elapsedMs=0`, indicating state availability rather than screen rendering time), while a background refresh published 131 current events after 1,646 ms. There was no channel reload. The first Sports revisit screenshot at 22:25:25.293 UTC already contained the fixture/filter UI, before background refresh publication at 22:25:25.973 UTC.

Cold Today loading after that revisit took 6,729 ms: 555 ms for 17,688 resolved programme entries, 5,813 ms classification, and 182 ms assembly, yielding 250 events. Classification used 11,776 hits and 5,912 misses. The cache stayed below its byte cap at 8,387,502 estimated bytes and 7,312 entries. This is the current guide/time window, not a controlled comparison against the older full-day Today measurements above.

Today remained loaded through a 31-frame capture with no fatal error. Switching to On Now took 1,891 ms after some classifier entries had been evicted by the larger Today working set (5,658 hits / 746 misses). Switching back to Today logged `window-cache-hit elapsedMs=0 items=250` and did not issue another resolved-programme query. The final cache estimate observed was 8,387,836 bytes, below the 8,388,608-byte cap. The app was left on Today with the same process still running.

Continuation artifacts are under `artifacts/sports-performance/retained-cache/`; the passing build log is `artifacts/sports-performance/retained-cache-final-build.log`.

## Remaining work

- Cold entry still spends substantial time preparing the shared channel catalog, resolving EPG data, and classifying programmes. It is not instant.
- Programme rows still need resolution on On Now refreshes, even when classification hits the cache. A durable or incremental Sports index remains outside this change.
- Process recreation still pays the cold cost; retained revisits apply within the running app.
- Cold Upcoming performance and large-catalog manual-search memory use need separate device measurements. The deferred index changes when that cost is paid; manual search still needs an index.
- This pass does not validate guide refresh or combined-profile switching on the configured device, which has one saved provider. Provider switching and visibility invalidation are covered with controlled unit fixtures.
