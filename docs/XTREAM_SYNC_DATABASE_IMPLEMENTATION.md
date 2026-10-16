# Xtream live database write improvements

P4 on `codex/xtream-live-sync-performance`, following the
[improvement plan](XTREAM_SYNC_IMPROVEMENT_PLAN.md).

## Changes and invariants

The isolated Xtream live writer uses its unique-ID admission count for the
per-provider staging limit. The first batch checks that its private session is
empty. Later batches avoid a growing-table COUNT query. The writer advances its
successful accepted count only after insertion returns; a failed write ends and
discards the attempt under P1 rules. Completed-category merges, completion seals
and recovery still reconcile actual database counts. General M3U/Stalker writers
keep database accounting. The optimized entry point must not be used to append
to a recovered session or with a duplicate/nonexclusive admission set.

Stage entities and fingerprints are prepared before entering the fenced write
transaction. SHA-256 inputs, delimiters and lowercase hexadecimal output remain
compatible with existing persisted fingerprints. Hex encoding uses a lookup
table, and text normalization reuses its whitespace expression. Unchanged-row
promotion continues to compare fingerprints and preserves local channel IDs.

Fallback categories classify repeated names once and still allow later adult
flags or alternate adult names to strengthen classification. Only placeholders
can be renamed. The live writer materializes the category snapshot at completion
instead of copying the accumulated category list after every batch.

Live index backfill uses a provider-scoped transactional DELETE plus INSERT
SELECT from channels, under the workflow commit fence. It preserves the previous
field mapping and defaults without allocating two full Kotlin lineup lists.
An empty lineup clears obsolete live index rows; other providers and content
types are untouched.

Full and partial staged live promotion rely on Room's FTS content-sync triggers
instead of rebuilding the global channel search index. Explicit rebuild paths
and other content-type rebuilds remain. No schema migration is required.

## Validation

- 151 focused Kotlin unit tests passed, including attempt retry isolation,
  supersession/recovery, fresh-session checks, size limits, fingerprint encoding,
  adult category promotion and existing sync regression tests.
- 14 Python fixture/benchmark tests passed.
- Real emulator Room tests passed: SQL projection fields, provider isolation,
  empty-lineup cleanup and full/section M3U catalog equivalence.
- FTS tests passed on fresh Room and actual databases migrated from v9 and v78.
  They exercise initial insert, unchanged refresh, rename, partial insertion,
  stale deletion, another provider, transaction rollback, stable local IDs,
  favorite linkage and user protection through production staged promotion.
  Before removing rebuilds, the same search matrix also passed through the
  production promotion DAO calls without a rebuild.
- Data instrumentation now targets API 36. Its previously unspecified target
  caused Android's old-app installation block. This affects the library test APK.
- The existing M3U equivalence assertion was corrected to normalize generated
  identities across providers; provider IDs intentionally affect those hashes.
  Stale-row assertions still inspect real persisted remote IDs.

## Measured results and limits

The 10,000-row/100-category synthetic fixture has the same payload SHA-256 as P3:
`4dfbcaf7e39ce168eb4cd58e34b5d8c78dddf4592d6638cb46c76b0fb31d63a3`.
On the API 36 emulator with the isolated debug app, five measured initial/refresh
pairs plus one warmup pair produced these medians:

| Operation | P3 | P4 before FTS removal | Change |
| --- | ---: | ---: | ---: |
| Add Provider backend | 4,245.5 ms | 3,883.6 ms | 8.5% lower |
| Settings Live refresh | 4,305.8 ms | 3,809.0 ms | 11.5% lower |

Report: `build/xtream-sync-performance/20261010T074712205202Z/report.json`.
All 12 samples retained exactly 10,000 channels and cleanup/count assertions
passed. Thirteen complete bulk responses occurred: one account had a redundant
third import, with a 7,831 ms refresh tail. This scheduling issue remains open.
Aggregate staging time across those 13 imports decreased from 21,982 to 20,043
ms. Peak sampled Java heap among measured pairs was 9,958,416 bytes; sampling
does not capture exact peak RSS or all native SQLite memory.

After FTS removal, all three modes passed initial and refresh coverage at 2,000
rows/10 categories, with no routine `channel-fts-rebuild` timing event. Report:
`build/xtream-sync-performance/20261010T075202281663Z/report.json`.
Category mode still incurs its old pacing floor; one refresh had an extra import.
These single samples are coverage checks, not comparable performance medians.

The final 50,000-row/200-category coverage run also passed both paths:
`build/xtream-sync-performance/20261010T075233892541Z/report.json`.
Initial import took 24,672 ms and refresh took 49,078 ms, versus P3's single
21,090/40,451 ms samples. Both runs performed three complete bulk imports for
two requested operations. This larger case does not show a latency improvement;
it retains the duplicate-refresh problem and requires more investigation.
The final run contained no global channel FTS rebuild timing events.

This is backend debug-emulator evidence. The emulator was restarted to resolve
the test installation investigation, and repeated runs can differ in device
state. Release-device end-to-end readiness, LOW/MID/HIGH acceptance and sustained
real-channel playback remain open. At the time of this P4 report, AUTO had not
yet changed; P6 later enabled bulk-first AUTO in normal app profiles on this
branch for direct testing. These results do not establish a tenfold overall
improvement.
