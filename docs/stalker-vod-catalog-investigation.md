# Stalker VOD: complete catalog investigation

Date: 2026-10-19

## Recommendation

Automatically build a durable local index of all accessible VOD **summaries**, including unified movie/series portals. Make the provider usable after initial category loading, keep adding titles independently of browsing, and search the portal while the local index is incomplete. Fetch expensive details, episode lists, artwork bytes, and playback links when needed.

Extend the existing index infrastructure. Changing the default from `ON_DEMAND` to `BACKGROUND_INDEX` alone will not deliver this behavior for unified VOD.

“All” means the catalog available to the authenticated account, subject to the user's visibility and parental controls. Completion must describe verified coverage, not merely a successful category refresh.

## Implementation update

The recommended first implementation is now in the working tree:

- New Stalker setups default to `BACKGROUND_INDEX`; existing saved modes are preserved.
- Unified VOD categories now feed the durable Movie index job through `getUnifiedVodPage()`. The job keeps each row's movie/series classification and writes both movie summaries and VOD-derived series summaries.
- Unified providers use only the Movie index job for progress, completion, manual rebuilds, startup recovery, and automatic freshness planning. The old separate Series job is ignored for unified layouts.
- Global search merges local FTS results with portal-wide VOD search while coverage is incomplete, respecting the selected movie/series scope and keeping local matches available when the portal request fails.
- The setup copy is localized in all 38 advertised locales.

The implementation deliberately reuses the existing resumable page indexer and portal search API. It does not yet add adaptive pacing or a new all-movies browsing screen. Existing providers that saved `ON_DEMAND` keep that choice; they can still find uncached VOD via global search, and can opt into background indexing in provider settings.

## Baseline before this work

| Area | Current implementation | Consequence |
| --- | --- | --- |
| Default | `StalkerCatalogMode.ON_DEMAND` in provider configuration, setup state, and add-provider input | Most users start with category shells and progressively cached items. |
| Existing alternative | Setup Advanced options offer `Complete background index` | There is already a product setting and a durable worker to build on. |
| Split catalogs | Movie/series index jobs, per-category page checkpoints, retries, WorkManager continuations, summary upserts, and generation-fenced writes | Much of the required infrastructure exists. |
| Unified VOD refresh | `StalkerCatalogSectionExecutor.syncMovies()` takes the lazy path when `unified`, even if `indexCatalog` is true | A unified refresh does not queue the full item index. |
| Unified full sync | `StalkerCatalogSyncExecutor` stores `ContentType.VOD` categories and excludes unified catalogs from movie index queue setup | The full-sync path has the same coverage gap. |
| Worker category reads | Continuation and incremental executor callbacks read `MOVIE`/`SERIES` categories; the job store only accepts those sections | Scheduling the existing worker alone does not make it consume unified `VOD` categories or update unified browse receipts. |
| VOD category browsing | `VodRepositoryImpl` requests `OPEN`, prefetches one `NEXT_PAGE` in paged mode, and can request `COMPLETE` | Loading remains driven by visible shelves and opened categories. |
| Complete-on-open | Unified category hydration limits one COMPLETE request to 200 pages and records truncated coverage when more remain | It is a bounded interactive operation, not a durable whole-library index. The single-page API itself can fetch page 201 and later. |
| VOD search | `VodRepositoryImpl.searchVod()` calls the portal, persists stable IDs, and returns mixed movie/series pages; preference defaults to enabled | Users can already find uncached titles from this surface on portals that support search. |
| Global search | `SearchRepositoryImpl` queries local FTS, with local section-query fallback | Unloaded titles are absent. Portal search on the VOD screen does not make global search complete. |
| Wildcard strategy | Split indexing prefers explicit categories; wildcard paging is used when only the wildcard category remains and support is not known false | The app does not currently use a verified whole-catalog wildcard as the general fast path. |

Key code locations:

- `domain/src/main/java/com/streamvault/domain/model/Provider.kt`
- `feature/provider/src/main/java/com/streamvault/feature/provider/setup/ProviderSetupAdvancedOptions.kt`
- `data/src/main/java/com/streamvault/data/sync/StalkerCatalogSectionExecutor.kt`
- `data/src/main/java/com/streamvault/data/sync/StalkerCatalogSyncExecutor.kt`
- `data/src/main/java/com/streamvault/data/sync/StalkerIndexContinuationCoordinator.kt`
- `data/src/main/java/com/streamvault/data/sync/StalkerIncrementalIndexExecutor.kt`
- `data/src/main/java/com/streamvault/data/sync/SyncManager.kt`
- `data/src/main/java/com/streamvault/data/sync/VodCategoryHydrationCoordinator.kt`
- `data/src/main/java/com/streamvault/data/repository/VodRepositoryImpl.kt`
- `data/src/main/java/com/streamvault/data/repository/SearchRepositoryImpl.kt`

## Why a complete crawl can be slow

`StalkerRequestCoordinator` allows one active `BACKGROUND_INDEX` operation per provider and applies a **5,000 ms** interval to background HTTP requests. `OkHttpStalkerApiService.withTransportAwareStalkerCall()` maps background indexing to that network priority. The incremental executor also adds inter-page delays of 1 second normally or 2 seconds during playback, and yields after a four-minute section run.

The executor's maximum of two parallel category fetches therefore does not imply two simultaneous background requests. Raising that constant alone will not bypass network admission.

Illustrative estimates for a portal returning 14 rows per page, an uninterrupted background crawl, and no competing traffic:

| Raw catalog rows | Required pages | Time from five-second request spacing alone |
| ---: | ---: | ---: |
| 10,000 | 715 | About 60 minutes |
| 50,000 | 3,572 | About 5 hours |

These are calculations, not measurements of the user's portal. Category rounding, metadata requests, retries, idle restrictions, and continuation scheduling can add time. Page size is provider-controlled and may differ.

## What external implementations show

There is a bulk-style option on some portals, but it is still paginated rather than a single response containing the whole library. The `get_ordered_list` request can omit its category filter; some clients use that form for an unfiltered catalog listing, while others use a category or search filter. This is not a guarantee that every Stalker portal treats an omitted VOD category as “all.”

The app's HTTP service can omit the `category` query parameter, and `StalkerProvider` exposes nullable-category VOD page methods. The background indexer currently walks explicit categories page by page; it does not make one request for the whole library. Requests reuse the provider's cached authenticated session, so this is not a new authentication handshake for every page. A category-less listing could reduce request count on some portals, but before using it as the index source the app must verify that it covers the visible catalog, preserves real category IDs, and advances without repeating or truncating; otherwise it must keep category-by-category paging.

The available external sources support paged enumeration and portal search; they do not establish a universal single-request VOD export. Infomir's published REST API v1 page documents its administrative REST resources, not the legacy STB `get_ordered_list` endpoint, so the portal request contract is established mainly through client implementations and must still be checked against the user's portal.

- [stalkerhek's VOD implementation](https://github.com/kidpoleon/stalkerhek/blob/main/stalker/vod.go) implements full movie loading by walking ordered-list pages, and native-series loading with a VOD-series fallback.
- [iptv-m3u-reader's Stalker implementation](https://github.com/DimitarCC/iptv-m3u-reader/blob/main/src/StalkerProvider.py) sends `type=vod&action=get_ordered_list`, optionally with category and search, and derives continuation from `total_items` and `max_page_items`.
- [ynotv's Stalker client](https://github.com/tbeezy/ynotv/blob/main/packages/local-adapter/src/stalker-client.ts) explicitly handles portal page-numbering differences, bounded page walks, and detection of portals that ignore search. This is compatibility evidence from another client, not a guarantee about every portal.
- [Thunder TV's own architecture document](https://github.com/D-dezeeuw/thunder-tv/blob/master/docs/architecture/stalker-portal.md) confines its bulk `get_all_channels` cache to ITV and retains server-side VOD/series search. Its live-TV shortcut should not be assumed to enumerate movies.
- [Infomir's REST API v1 documentation](https://wiki.infomir.eu/eng/ministra-tv-platform/ministra-setup-guide/rest-api-v1) describes separately authenticated management/integration resources. It does not establish a bulk VOD endpoint usable with the app's existing MAC/token session.

Inference: the portable solution is resumable ordered-list indexing plus server search. A provider-specific bulk API could be an optional optimization only after capability and authorization checks.

## Remaining design considerations

### Unified VOD indexing details

The current implementation reuses the existing Movie job as the unified VOD index section. A future schema-level unified job could make the distinction more explicit, but is not required for the existing worker and search completeness to represent unified coverage.

Use `getUnifiedVodPage()` so each raw row retains its movie/series classification. Reuse the page-commit logic behind interactive hydration, after extracting a shared commit operation: persist movie/series summaries, `VodCatalogEntryEntity` page order, protection state, and the page checkpoint together. Apply the active workflow generation fence and coordinate with interactive category hydration so they cannot overwrite each other's progress.

Keep work bounded by page/time slices. A slice limit means continuation from the last committed page, rather than terminal catalog truncation. Preserve cached rows while refreshing. Prune only after verified complete coverage of the relevant category or generation; failed or anomalous scopes retain their previous content.

### Background indexing and browsing

Initial setup still finishes after usable category/live data; the index continues without opening categories. Existing users' explicitly selected modes remain unchanged.

Expose the whole local movie library through paged database queries and a windowed grid. Summary indexing should not trigger per-title details, episode expansion, image downloads, or `create_link` requests. Provider row counts and deduplicated displayed movie counts must remain distinct.

Show indexed count, coverage state, and actionable retry status. Derive unified search completeness from the unified ingestion job. Category-list freshness and full item-coverage freshness must be separate, since today's lazy refresh can succeed without enumerating items.

Any new or edited product wording must use owning-module resources and real translations in every locale advertised by `locales_config.xml`.

### Global search during the initial crawl

Return local FTS results immediately. While coverage is incomplete, also request portal search and merge results by provider identity. The current implementation retains the existing Flow cancellation behavior and paging, and relies on `VodRepository` for visibility filtering and persistence.

Track portal-search capability, including ignored search parameters. Failure or unsupported search must leave local results visible and the coverage state accurate. Validate native-series versus VOD-derived-series search according to the learned catalog layout. A portal search result does not certify that the local catalog is complete.

### 4. Use category-less catalog paging only where the portal proves support

Probe whether category-less or wildcard/all-category paging covers the accessible catalog, preserves category identity, and advances correctly. Prefer it when validated; otherwise crawl explicit categories. A non-empty first page is insufficient evidence of complete coverage.

Page numbering also varies across portals. The app keeps 1-based logical page checkpoints, probes `p=0` and `p=1` on the first VOD page using the same authenticated session, and maps subsequent requests to the detected portal offset. It persists the offset in the existing generation-fenced provider learning state, so resumed indexing keeps the same sequence without repeating the probe. A transient failure on `p=0` fails that page instead of silently treating page two as page one. This follows the compatibility approach in [ynotv's client](https://github.com/tbeezy/ynotv/blob/main/packages/local-adapter/src/stalker-client.ts); the actual offset still needs to be observed on each target portal, and unstable totals or repeated pages must never be treated as proof of complete coverage.

Learn paging behavior where necessary; current single-page requests clamp to page 1 and higher. Detect repeated/non-advancing pages across continuations, premature empties, changed totals, and missing totals. Do not mark these outcomes complete.

Retain the present request coordinator initially. Measure latency, unique rows per second, 429s, timeouts, and interactive delays. Then replace the fixed five-second interval with provider-scoped adaptive pacing: cautiously shorten intervals after sustained healthy responses, slow down on stress, preserve Retry-After and cooldowns, and keep playback/link resolution ahead of indexing. Keep the existing concurrency limits until measurements justify changing them.

## Validation and evidence

The research pass was source inspection only; no provider credentials were read and no production portal was queried. External clients demonstrate compatibility approaches, not the capabilities of the user's specific provider. The page-offset fix is validated with simulated zero-based and one-based portals; it has not been observed against the user's actual portal.

Executed:

```powershell
.\gradlew.bat :data:testDebugUnitTest --tests com.streamvault.data.remote.stalker.StalkerProviderTest --tests com.streamvault.data.remote.stalker.StalkerPortalStateStoreTest --tests com.streamvault.data.remote.stalker.OkHttpStalkerApiServiceTest --tests com.streamvault.data.repository.MovieRepositoryImplTest --tests com.streamvault.data.repository.SeriesRepositoryImplTest --tests com.streamvault.data.sync.SyncManagerTest --tests com.streamvault.data.provider.ProviderConfigurationCodecTest --tests com.streamvault.data.provider.RoomProviderSnapshotRepositoryTest --tests com.streamvault.data.security.CredentialCryptoTest
```

Latest result: **344 tests passed**, zero failures, errors, or skips. `git diff --check` also passed. Earlier baseline runs covered additional indexing and repository test classes; resource validation confirmed all six added setup strings exist in each of the 38 advertised locales.

Further validation should demonstrate:

1. On fresh unified and split providers, a title near the end of an unopened category becomes searchable through background indexing.
2. Mixed movie/series categories retain every correctly classified item and provider ordering; movies-only views include standalone movies in mixed categories.
3. A category beyond 200 pages completes through durable continuation. Process death and a failed page resume without losing or skipping committed coverage.
4. On the actual portal, confirm the detected page base and verify that unsupported wildcard/search, repeated pages, missing totals, changing totals, and early empty pages never produce false completion or destructive pruning.
5. Search while indexing merges stable IDs, honors hidden/protected content, and remains useful when the portal is offline.
6. Refresh discovers additions and handles removals only after proven coverage. Configuration changes reject stale writes; simultaneous category opening cannot corrupt checkpoints.
7. Record actual portal page size, requests, unique rows, elapsed time, retries/429s, memory use, and search latency on a low-memory TV/emulator before changing pacing defaults.
8. If pacing changes affect live playback, perform the repository's full multi-channel screenshot/log validation rather than relying on build or launch success.
