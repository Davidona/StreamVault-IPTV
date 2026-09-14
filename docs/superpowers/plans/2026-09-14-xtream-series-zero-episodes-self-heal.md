# Xtream Series Zero-Episode Self-Heal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent an Xtream series from showing an empty episode list after a user re-enters it. Two complementary guards make the detail path self-heal: (1) a freshly-hydrated Xtream series whose local episodes have vanished is re-fetched instead of returning an empty cache, and (2) re-hydration never replaces a larger local episode set with a smaller (partial) remote set.

**Architecture:** Keep all changes inside `SeriesRepositoryImpl.getSeriesDetails`. Fix 1 mirrors the existing Stalker "impossible state" self-repair (`SeriesRepositoryImpl.kt:489-500`) for the Xtream branch. Fix 2 computes a single `adoptRemoteEpisodes` decision and uses it both to gate the destructive `episodeDao.replaceAll` call and to select which season set is shown, so the UI and the persisted rows stay consistent.

**Tech Stack:** Kotlin, Kotlin Coroutines, Room, Mockito-Kotlin, Truth, Gradle.

**Spec:** `docs/superpowers/specs/2026-09-14-xtream-series-zero-episodes-self-heal-design.md`

## Global Constraints

- Preserve the `SeriesRepository` interface and the Room schema.
- No change to `EpisodeDao.replaceAll` or any DAO signature; the guards live in the repository layer.
- A genuinely empty series (provider returns no episodes and none are persisted) must still show as empty and remain `SUMMARY_ONLY`; do not loop re-fetching it on every open.
- The provider-empty case (e.g. series 10588) must be unchanged: single variant, no episodes server-side, stays `SUMMARY_ONLY`.
- Healthy series (fresh + episodes present, or remote superset) keep their existing behavior exactly.
- Foreign-key enforcement is OFF in this app (`onDelete = CASCADE` on `episodes` never fires); do not rely on cascade semantics.
- Run `graphify update .` after production code files are modified.

---

## File Map

| File | Responsibility |
| --- | --- |
| `data/src/main/java/com/streamvault/data/repository/SeriesRepositoryImpl.kt` | Add the Xtream fresh-but-empty guard and the non-destructive episode-adoption decision. |
| `data/src/test/java/com/streamvault/data/repository/SeriesRepositoryImplTest.kt` | Add tests for the fresh-but-empty re-hydration path and the partial-set preservation path; adjust the existing fresh-cache test to carry episodes. |

## Interfaces Produced for Later Tasks

No new public interface is introduced. The only contract change is the internal decision variable `adoptRemoteEpisodes`, which is private to `getSeriesDetails`.

---

### Task 1: Self-heal a fresh Xtream series whose episodes vanished

**Files:**

- Modify: `data/src/main/java/com/streamvault/data/repository/SeriesRepositoryImpl.kt:486-488`
- Modify: `data/src/test/java/com/streamvault/data/repository/SeriesRepositoryImplTest.kt`

- [ ] **Step 1: Add a failing test for the fresh-but-empty re-hydration.**

Add `getSeriesDetails rehydrates fresh xtream series that lost its episodes`: a `DETAIL_HYDRATED` series with a recent `detailHydratedAt` and an empty `episodeDao.getBySeriesSync`, then assert `seriesCatalogSource.hydrateSeries` and `xtreamContentIndexDao.markDetailHydrated` are invoked (i.e. the detail path fell through to re-hydration instead of returning the empty cache).

- [ ] **Step 2: Guard the existing fresh-cache test so it still means "fresh with episodes".**

In `getSeriesDetails uses fresh xtream hydrated cache without refetching`, change the empty `episodeDao.getBySeriesSync` stub to return a single `EpisodeBrowseEntity` so the test keeps asserting that a fresh series *with* episodes does not refetch.

- [ ] **Step 3: Implement the Xtream fresh-but-empty guard.**

Replace the unconditional early return in the Xtream branch with the Stalker-style check:

~~~kotlin
if (provider.type == ProviderType.XTREAM_CODES && seriesEntity.hasFreshXtreamDetails()) {
    val localSeries = buildSeriesWithPersistedEpisodes(seriesEntity)
    if (localSeries.seasons.any { season -> season.episodes.isNotEmpty() }) {
        return Result.success(attachSeriesPresentation(localSeries, knownPresentation))
    }
}
~~~

- [ ] **Step 4: Run the focused repository tests.**

~~~powershell
.\gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.repository.SeriesRepositoryImplTest"
~~~

Expected: the new test and the adjusted fresh-cache test pass.

### Task 2: Never shrink the episode set during re-hydration

**Files:**

- Modify: `data/src/main/java/com/streamvault/data/repository/SeriesRepositoryImpl.kt:546-547,615-617,626`
- Modify: `data/src/test/java/com/streamvault/data/repository/SeriesRepositoryImplTest.kt`

- [ ] **Step 1: Add a failing test for partial remote sets.**

Add `getSeriesDetails keeps persisted episodes when remote returns a partial set`: a non-fresh Xtream series (or `SUMMARY_ONLY`) with two persisted episodes whose `hydrateSeries` returns one episode, then assert the result still shows the persisted (larger) episode set and `episodeDao.replaceAll` is not called.

- [ ] **Step 2: Capture the persisted episode list once.**

Change the `hasPersistedEpisodes` lookup to retain the list so the count can be compared:

~~~kotlin
val persistedEpisodesBefore = episodeDao.getBySeriesSync(seriesEntity.id)
val hasPersistedEpisodes = persistedEpisodesBefore.isNotEmpty()
~~~

- [ ] **Step 3: Gate `replaceAll` behind a superset decision.**

Replace the existing `if (episodesToPersist.isNotEmpty())` guard:

~~~kotlin
val adoptRemoteEpisodes = episodesToPersist.isNotEmpty() &&
    (persistedEpisodesBefore.isEmpty() || episodesToPersist.size >= persistedEpisodesBefore.size)
if (adoptRemoteEpisodes) {
    episodeDao.replaceAll(seriesEntity.id, providerId, episodesToPersist)
}
~~~

- [ ] **Step 4: Use the same decision when merging seasons.**

Change the first `mergedSeasons` branch condition from `remoteSeries.seasons.any { it.episodes.isNotEmpty() }` to `adoptRemoteEpisodes` so the displayed seasons match what was persisted.

- [ ] **Step 5: Run the focused repository tests.**

~~~powershell
.\gradlew.bat :data:testDebugUnitTest --tests "com.streamvault.data.repository.SeriesRepositoryImplTest"
~~~

Expected: the partial-set test passes and the existing "remote only has season metadata" test (which relies on persisted episodes being kept) still passes.

### Task 3: Full Regression and Graph Update

**Files:**

- Modify: none unless a test identifies a regression in `SeriesRepositoryImpl.kt`.

- [ ] **Step 1: Run the data module unit tests.**

~~~powershell
.\gradlew.bat :data:testDebugUnitTest
~~~

Expected: PASS with no series sync or repository regressions.

- [ ] **Step 2: Update graphify after the production change.**

~~~powershell
graphify update .
~~~

Expected: the graph updates successfully. If the CLI is unavailable, record the limitation and continue with the verified Gradle results.

- [ ] **Step 3: Inspect the worktree.**

~~~powershell
git status --short
git diff --check
~~~

Expected: only the two intended files are modified; no whitespace errors; pre-existing `docs/CHANGELOG.md`/`docs/upgrade.txt` changes remain unstaged and untouched.

## Self-Review Checklist

- [ ] Fix 1 mirrors the existing Stalker self-repair and only fires for a fresh Xtream series with zero persisted episodes.
- [ ] Fix 2 never reduces the locally persisted episode count; a partial remote response keeps the larger local set.
- [ ] Genuinely empty series still resolve to `SUMMARY_ONLY` and are not refetched in a loop.
- [ ] Healthy series (fresh + episodes, or remote superset) are behaviorally unchanged.
- [ ] No DAO signatures or Room schema changes.
- [ ] Test commands use the repository's Gradle module name (`:data`) and the existing test class.
- [ ] `graphify update .` is run after production files change.
- [ ] `docs/CHANGELOG.md` and `docs/upgrade.txt` remain untouched and unstaged.
