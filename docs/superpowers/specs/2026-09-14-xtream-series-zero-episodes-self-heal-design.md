# Xtream Series Zero-Episode Self-Heal — Design

## Problem

An Xtream provider can leave a series showing "Season N with 0 episodes" after a user re-enters it. Two distinct local states produce this symptom:

1. **Fresh-but-empty**: the `series` row still reports `cache_state = DETAIL_HYDRATED` with a recent `detail_hydrated_at`, but the `episodes` rows are gone. `getSeriesDetails` trusts `hasFreshXtreamDetails()` and returns the empty persisted set without re-fetching.
2. **Partial re-scrape**: re-hydration returns a smaller (or remapped) episode set than what is already persisted, and `episodeDao.replaceAll` blindly deletes the larger local set.

Both cases were investigated against provider `line.dndnscloud.ru`. Foreign-key enforcement is OFF in this app (Room 2.8.4 does not call `setForeignKeyConstraintsEnabled`, and the app never does), so `onDelete = CASCADE` never fires; the loss is via `EpisodeDao.replaceAll` or the fresh-cache early return, not cascade.

## Design

All changes are contained in `SeriesRepositoryImpl.getSeriesDetails`.

### Fix 1 — fresh-but-empty self-heal

The Xtream branch currently returns unconditionally when the row is fresh. Mirror the existing Stalker "impossible state" guard: only return the cached seasons when they actually contain episodes; otherwise fall through to the normal re-hydration path.

### Fix 2 — non-destructive episode adoption

Compute one boolean used in two places:

```
adoptRemoteEpisodes = remoteEpisodes.nonEmpty && (persisted.isEmpty() || remoteEpisodes.size >= persisted.size)
```

- `true` → persist the remote set (`replaceAll`) and render the remote seasons.
- `false` → keep the persisted set and render it; a partial/remapped response never shrinks the library.

This preserves the existing "empty remote keeps persisted episodes" behavior (which is currently handled implicitly) and extends it to the "partial remote" case.

## Non-goals

- No sibling/variant redirect (opening a same-TMDB sibling that has episodes) — left for a follow-up if the fresh-but-empty guard does not cover the observed symptom.
- No DAO or schema change.
- No change to how a genuinely empty series is represented (`SUMMARY_ONLY`, season shells rendered from provider metadata).

## Test coverage

- Fresh Xtream series + zero persisted episodes → re-hydrates.
- Fresh Xtream series + episodes present → no re-fetch (existing behavior preserved).
- Non-fresh series + partial remote set → persisted (larger) set is kept, `replaceAll` not called.
- Existing "remote returns only season metadata keeps persisted episodes" test must remain green.
