# Stalker sync and recovery test results

Date: 2026-11-10 (Asia/Jerusalem)

Historical note: this report captures the pre-fix characterization. Subsequent
working-tree changes address G1, G2, G3, and G7; their current implementation
and post-fix verification are recorded in
[the investigation](STALKER_SYNC_RECOVERY_INVESTIGATION.md).

## Result

The final focused Android unit-test run passed **188 tests across 9 suites** with
0 failures, 0 errors, and 0 skipped tests. No production files were changed.

The new G1 and G2 tests are **characterizations of current behavior**, not
assertions that the behavior is desirable. The guide test also characterizes the
current failure and verifies that explicit capability invalidation restores a
working backend probe. A separate assertion verifies the intended checkpoint
reset when a resumed page succeeds.

## New coverage

- **G1 — terminal authorization state:** Uses the production
  `StalkerIndexRecoveryPolicy`, `StalkerIndexCheckpointStore`, and
  `StalkerIndexPolicy`. A `StalkerApiError.SessionExpired` response classifies
  as permanent, persists `FAILED_PERMANENT` with zero retry budget, and remains
  unattemptable after reconstructing the store and advancing time, absent an
  explicit checkpoint reset. A committed page 1 stays checkpointed while page 2
  remains the gap.
- **G2 — retry exhaustion:** Uses the same production components. Three
  retryable failures preserve page 1 and page 2 as the gap, then persist
  `FAILED_BUDGET_EXHAUSTED`; the policy still refuses a later attempt after
  reconstructing the store and advancing time, absent an explicit checkpoint
  reset. The test advances a simulated timestamp across the cooldown without
  waiting in real time.
- **Recovery control:** A successful resumed page advances the cursor and resets
  `failureCount` to 0 and retry budget to the production budget of 3.
- **G3 — learned EPG unsupported:** Runs the production
  `ProviderEpgSyncExecutor` and `StalkerPortalStateStore` against a stateful DAO
  fake. A transient bulk error plus per-channel errors preserves prior guide
  rows but writes `epgSupported=false`. A subsequent forced run makes no guide
  requests, reports no retryable failure, and retains cached data even after the
  test backend is restored. Explicit `invalidateCapabilities()` makes the next
  forced run query the restored backend and record support as true. The restored
  bulk response covers channel 100; existing channel 200 data correctly remains
  untouched because usable bulk coverage suppresses per-channel fallback.

## Final suite counts

| Test suite | Tests | Failures / errors / skipped |
| --- | ---: | ---: |
| `StalkerRecoveryPersistenceTest` | 3 | 0 / 0 / 0 |
| `StalkerNativeGuideInterruptionTest` | 4 | 0 / 0 / 0 |
| `StalkerIncrementalIndexExecutorTest` | 12 | 0 / 0 / 0 |
| `StalkerIndexPolicyTest` | 7 | 0 / 0 / 0 |
| `StalkerIndexWorkerTest` | 3 | 0 / 0 / 0 |
| `StalkerProviderTest` | 64 | 0 / 0 / 0 |
| `StalkerSessionTest` | 2 | 0 / 0 / 0 |
| `OkHttpStalkerCancellationTest` | 6 | 0 / 0 / 0 |
| `OkHttpStalkerApiServiceTest` | 87 | 0 / 0 / 0 |
| **Total** | **188** | **0 / 0 / 0** |

The existing executor suite includes `failedPageDoesNotCommitLaterPageAndRetryResumesFromTheGap`
and `adaptiveCategoryWindowNeverCheckpointsPastAFailedPage`. Provider tests
include typed authorization renewal and persisted-session restoration after a
new provider instance. The API suite distinguishes HTTP 401 / rejected-token
`SessionExpired` from a blocked/configuration 403 response. The cancellation
suite covers cancellation reaching the OkHttp call.

## Exact commands

Final combined focused run, from the repository root:

```powershell
.\gradlew.bat :data:testDebugUnitTest --tests com.streamvault.data.sync.StalkerRecoveryPersistenceTest --tests com.streamvault.data.sync.StalkerNativeGuideInterruptionTest --tests com.streamvault.data.sync.StalkerIncrementalIndexExecutorTest --tests com.streamvault.data.sync.StalkerIndexPolicyTest --tests com.streamvault.data.sync.StalkerIndexWorkerTest --tests com.streamvault.data.remote.stalker.StalkerProviderTest --tests com.streamvault.data.remote.stalker.StalkerSessionTest --tests com.streamvault.data.remote.stalker.OkHttpStalkerCancellationTest --tests com.streamvault.data.remote.stalker.OkHttpStalkerApiServiceTest
```

Result: `BUILD SUCCESSFUL in 8s`. The Gradle XML reports in
`data/build/test-results/testDebugUnitTest/` were written at 20:43:13 +02 on
2026-11-10 and independently total 188 tests with no failures, errors, or
skips.

Baseline run before adding coverage:

```powershell
.\gradlew.bat :data:testDebugUnitTest --tests com.streamvault.data.sync.StalkerIndexPolicyTest --tests com.streamvault.data.sync.StalkerIndexWorkerTest --tests com.streamvault.data.sync.StalkerNativeGuideInterruptionTest --tests com.streamvault.data.sync.StalkerIncrementalIndexExecutorTest --tests com.streamvault.data.remote.stalker.StalkerProviderTest --tests com.streamvault.data.remote.stalker.StalkerSessionTest --tests com.streamvault.data.remote.stalker.OkHttpStalkerApiServiceTest
```

Result: 178 tests passed, with 0 failures, errors, or skips.

There were two intermediate failed runs while the new fixture was being edited:
one compiled an earlier version of the EPG fixture, and one exposed an incorrect
expectation in the explicit-reset control. Both were corrected before the final
combined run above.

## Source findings confirmed and clarified

- `SessionExpired` extends `Authorization`; `StalkerIndexRecoveryPolicy`
  classifies authorization failures as `FAILED_PERMANENT`. The test confirms
  this includes the `SessionExpired` subtype.
- The checkpoint store decrements retry budget for retryable failures and marks
  the category exhausted at zero. A success restores the budget to 3. More
  precisely, failure 1 has no cooldown; failure 2 records a 10-minute cooldown
  before failure 3 can run; failure 3 exhausts the budget. The cooldown is
  computed as five minutes times `(prior failure count + 1)`.
- `recordEpg(false)` updates both learned support and `validatedAt`. `getValidated`
  accepts the observation for up to seven days, and authentication can refresh
  validation. Therefore the unsupported state is not permanent in all
  circumstances, but a forced guide sync still skips while a validated false
  capability is present and the provider has no external EPG URL. The skip path
  also does not set `hasRetryableFailure`, so the worker can treat that run as
  complete. Explicit capability invalidation clears the learned false value.

## Limits

- The checkpoint tests exercise production policy and store code, but use
  stateful in-memory DAO implementations. Recreating a store over those rows
  models persisted state; it does not prove Room behavior or an Android process
  death/restart.
- The checkpoint characterization does not invoke a real portal transport or a
  full index worker. Existing page-gap tests use a mocked operations port, and
  the worker test covers the `ExistingWorkPolicy` selection helper rather than
  WorkManager scheduling/chain execution.
- The guide test uses the real sync executor and capability store, but its DAO
  and `StalkerProvider` transport methods are fakes/mocks. No credentials or real
  portal requests were used.
- No affected-device state, emulator playback, or live provider behavior was
  validated. These unit tests do not establish that the investigation
  screenshot has any particular cause.

## Follow-up: rate-limit interaction (G7)

After the 188-test combined run above, a focused follow-up passed **24 tests
across two suites**, with zero failures, errors, or skips:

- StalkerRequestCoordinatorTest: 20 existing tests, including provider-wide
  Retry-After rejection, single half-open probe, and server Retry-After behavior.
- StalkerRecoveryPersistenceTest: four tests, including the three earlier
  characterizations plus a new local-cooldown/budget interaction test.

```powershell
.\gradlew.bat :data:testDebugUnitTest --tests com.streamvault.data.remote.stalker.StalkerRequestCoordinatorTest --tests com.streamvault.data.sync.StalkerRecoveryPersistenceTest
```

Result: BUILD SUCCESSFUL in 9s. The Gradle XML counts were independently read.
This selection replaces the current on-disk XML selection from the earlier run;
the earlier counts describe that earlier successful invocation.

The new test opens the real coordinator's default fifteen-minute RateLimited
cooldown, obtains three local network-admission rejections, and records them
through the real classifier and checkpoint store at category retry deadlines.
The category becomes FAILED_BUDGET_EXHAUSTED before the provider cooldown ends.
At cooldown expiry the coordinator admits a half-open probe; recording its
success clears the circuit but leaves that category ineligible under the real
attempt policy. No network requests are made in this component test.

This confirms the policy/store interaction, not an exact real-world worker
timeline. It does not test Android scheduling, an actual HTTP server, or the
mixed EPG case where earlier ordinary failures precede a rate-limit response.
Production code was unchanged at the time of that historical test run; see the
investigation for subsequent fixes.
