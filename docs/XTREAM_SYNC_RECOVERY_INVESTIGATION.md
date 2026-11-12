# Xtream comparison with the Stalker recovery findings

Date: 2026-11-10 (Asia/Jerusalem)

Branch update: continuing recovery work is on `codex/provider-sync-recovery`.
Robolectric Android SharedPreferences reconstruction was verified for the EPG
and catalog deadline stores as part of a 32-test focused recovery run. The X1
policy, executor, and sync-manager suites subsequently passed 137 tests. This is
not a real Android process-death or WorkManager scheduling test.

Implementation update: X2 is fixed in the working tree. Retryable category
failures still persist the partial checkpoint, but now propagate an IOException
to the owning worker so it can retry with WorkManager backoff. The X2 description
and characterization below record the pre-fix behavior; the updated regression
tests assert retry signaling and resumption from the saved cursor. The focused
post-fix executor, sync-manager, and worker run passed 158 tests across four
suites, including a simulated 503 reaching the sync-manager retry result.

Related reports: [Stalker investigation](STALKER_SYNC_RECOVERY_INVESTIGATION.md)
and [Stalker tests](STALKER_SYNC_RECOVERY_TEST_RESULTS.md).

## Current status and open work (2026-11-11)

- **Implemented:** X1 action-level 403 is retryable; X2 partial category failures
  signal retry; X3 catalog Retry-After deadlines and X4 XMLTV deadlines persist.
  Explicit account authentication rejection remains permanent.
- **Open — old permanent 403 jobs:** Stored error text does not reliably identify
  whether an old 403 was an edge block or account rejection, so those jobs are
  not automatically reopened.
- **Open — runtime validation:** Test actual WorkManager backoff, wake-up timing,
  process death/restart, and real provider responses. Shared workflow lease and
  unique-work isolation tests passed on Android, but do not run an Xtream retry. The 403 cursor-resume test
  uses fake operations and does not validate a real HTTP or worker run.
- **Known scope:** Catalog pacing is not a universal gate for direct API callers;
  changing a provider endpoint can retain its old catalog deadline until expiry.
  XMLTV workers may wake during cooldown, though early network requests are gated.

## Conclusion

Some failure outcomes carry over, but the protocol and retry mechanisms differ.
Xtream does not use the Stalker bearer-token/session or its per-category
three-pass retry budget. The Stalker EPG unsupported capability also does not
gate Xtream XMLTV. The investigation below records Xtream-specific issues and
their subsequent fixes, along with remaining validation gaps.

These findings do not establish the cause of any real-provider screenshot.
The original investigation preceded production fixes. Current implementation
status is recorded in the X1 through X4 updates below; previously permanent
403 jobs and real-device recovery validation remain open.

## Comparison

| Scenario | Xtream behavior | Evidence / qualification |
| --- | --- | --- |
| Expired Stalker bearer session | Not applicable: ordinary Xtream player_api requests include username/password rather than that bearer session. | Source trace through XtreamProvider and XtreamUrlFactory. |
| Persistent 401/403 stops automatic indexing | Explicit account-authentication rejection and request 401 remain permanent. Action-level request 403 is now retryable. | Existing auth-rejection tests and the X1 regression test; previously recorded permanent 403 jobs are not automatically reopened. |
| Three category failures exhaust a durable budget | No equivalent Stalker category budget in this Xtream executor. Retryable failures retain a PARTIAL section and category cursor. | New test exercises four failed invocations and then a successful explicit invocation. |
| Local cooldown rejections spend category budget | The same mechanism is absent. Adaptive sync awaits its deadline instead of rejecting locally; there is no such durable category budget. | Existing Retry-After tests; source trace. |
| Retry-After survives restart | Catalog sync now persists provider-scoped deadlines; reconstructed policy and store tests pass. | Actual Android process death remains untested. |
| EPG failure teaches unsupported and skips forced guide sync | No Stalker epgSupported=false gate on Xtream's XMLTV path. HTTP transient failures preserve old data and return retryable warnings. | Existing real HTTP response -> repository -> executor tests. |
| EPG honors Retry-After | XMLTV now persists a guide-specific deadline and prevents requests before it expires. | Worker wake-up timing and real process death remain untested. |
| Retry resumes exact page | Xtream bulk/category import differs from Stalker paging: completed category cursor is durable; a failed/in-flight category is fetched again. Interrupted bulk import can fall back to categories. | Existing cursor, row-budget, and cancellation tests. No byte-offset resume guarantee. |

## X1: Permanent 401/403 classification

Implementation update: action-level `XtreamRequestException(403)` is now
retryable, allowing the worker to back off and resume from its saved cursor.
Explicit `XtreamAuthenticationException` (including account-level 403) and
request 401 remain permanent. Existing permanent job records are not reopened:
their stored error strings cannot reliably distinguish edge blocks from account
rejection. The targeted policy, executor, and sync-manager suites passed 137
tests, including the new classification regression. Actual WorkManager scheduling
and a real provider response remain unverified. A subsequent 16-test policy and
executor run passed, including an action-level 403 that preserves the failed
category cursor and resumes at that category after recovery. The executor uses
fake operations, so this is not a real network or WorkManager test. The detailed
discussion below describes the pre-fix classification.

OkHttpXtreamApiService maps HTTP 401 to XtreamAuthenticationException. HTTP 403
on account authentication is also authentication failure; on an action route it
is XtreamRequestException. XtreamIndexRecoveryPolicy makes both authentication
failure and request 401/403 permanent. SyncManager persists FAILED_PERMANENT;
CatalogIndexJobStore and persisted-job eligibility suppress ordinary retries.

This is reasonable for invalid credentials, but a transient provider/WAF 403
can also retire the section if it reaches that classification. The adaptive sync
policy considers request 403 a throttle/stress signal while the durable index
policy considers it permanent. This semantic mismatch deserves review. There is
no Stalker-style bearer refresh to perform for ordinary credential requests.

## X2: Retryable category failure does not schedule its own continuation

XtreamIncrementalIndexExecutor stops at the first failed category, retains that
category's cursor, writes PARTIAL, and does not call scheduleIndex when failures
exist. Since the failed category has not advanced the cursor, hasMoreCategories
is true; the final throw guarded by !hasMoreCategories is not taken in this path.
The invocation therefore returns normally despite incomplete retryable work.

SyncManager sees no thrown failure, and the worker's command-success branch
returns workflow success. This means this invocation requests neither worker
retry nor a new continuation for that section. A different already-queued task,
manual action, launch/foreground check, or policy-enabled automatic refresh can
recover it later. Partial jobs remain eligible; this is not proof of permanent
abandonment under every scheduling configuration.

ProviderSyncWorker registers hourly and launch/foreground checks through the
automatic planner; legacy XtreamIndexWorker periodic/launch checks are cancelled
by StartupWorkRegistry. Therefore do not assume the old six-hour worker repairs
this path independently of current automatic-sync policy.

The new executor characterization runs four 429-failing invocations with retained
job fields. Each returns normally with PARTIAL, unchanged cursor, and no scheduled
successor. A later explicit healthy invocation finishes from the failed category
and does not revisit prior completed categories. Network behavior and actual
WorkManager chaining are mocked/outside this test.

## X3: Rate-limit deadlines are scoped to the in-memory sync policy

Implementation update: Xtream catalog sync now persists provider-scoped
Retry-After deadlines, checks them before gated sync requests, and retains the
longer of concurrent/overlapping deadlines. Provider deletion clears its saved
deadline. Reconstructed policy/store tests cover persistence, provider isolation,
deadline extension, and cleanup. The focused store/policy/support run passed 13
tests; the broader catalog and sync-manager selection and
`:app:hiltJavaCompileDebug` also passed. The description below is historical pre-fix
behavior. Direct API consumers that do not use the sync policy remain outside
this gate; a change to the same provider's endpoint can retain its prior deadline
until it expires. Actual Android process death and WorkManager timing remain
untested.

Xtream API catalog responses retain Retry-After as retryAfterMs. The adaptive
policy records provider backoff and awaits the maximum deadline before subsequent
gated sync requests. Existing tests verify healthy successes do not erase that
deadline and a new throttle can extend a pending wait. Unlike Stalker, waiting
does not manufacture local RateLimited failures.

The health/pacing maps are instance memory. Reconstructing the policy loses the
old deadline, as the new test demonstrates. This is not a durable, universal gate
for every Xtream request: XMLTV and ordinary direct API consumers do not all pass
through this sync policy.

Movie/series category operations also have an outer 60-second timeout around the
retry/pacing loop. A long Retry-After can therefore outlive the current category
attempt, producing a retryable failure and the PARTIAL path in X2. This interaction
is source-inspected, not an additional end-to-end timeout reproduction.

## X4: XMLTV drops the rate-limit deadline

Implementation update: XMLTV HTTP transient responses now retain Retry-After
in the returned network exception and persist a provider-and-guide-specific
deadline. The request boundary rejects early attempts locally until that deadline
expires, including after repository recreation. Guide URLs are hashed before
forming persistence keys; deleting a provider clears its deadline entries.
WorkManager can still wake and back off during the wait; this change prevents
early network requests, not worker execution. Catalog sync uses a separate
persisted provider-scoped deadline as described in X3.

The unit tests use a stateful SharedPreferences fake and mocked HTTP responses,
including repository recreation, provider/URL isolation, and retry after expiry.
Actual Android process death and WorkManager behavior are not validated. The
focused deadline and guide HTTP run passed four tests; a broader guide,
repository, sync-manager, and worker selection also passed before the final
expiry assertion was added. `:app:hiltJavaCompileDebug` also passed, checking
the production injection graph. The description below is historical pre-fix behavior.

EpgRepositoryImpl handles 408, 429, and 5xx by returning a Result.Error containing
a plain IOException. It does not preserve Retry-After. ProviderEpgSyncExecutor
wraps that result in IllegalStateException with the IOException cause and marks
the guide failure retryable. That is sufficient for later worker retry, but not
for honoring the server-specified deadline.

The immediate retryTransient helper checks its outer error rather than the cause:
for the HTTP-429 wrapper in the test, there are no three immediate helper retries.
The new characterization confirms a Retry-After: 600 response is surfaced as a
retryable result without retaining or waiting for 600 seconds. One direct
repository probe and one executor invocation occur at virtual time zero. It does
not assert that a real worker sends its next request immediately.

The first test version expected short immediate helper retries. Its failure
showed this assumption was incorrect; the assertion and report were corrected
to the observed wrapper behavior before the final run.

## Test changes and command

Three new characterizations were added:

- XtreamIncrementalIndexExecutorTest: four rate-limited category invocations,
  no terminal budget, no self-continuation, then successful explicit resume.
- XtreamLiveCategorySchedulingTest: reconstructing the adaptive policy loses a
  previously recorded Retry-After deadline.
- GuideHttpFailureTest: XMLTV Retry-After header is not retained through guide
  error classification; old guide data is preserved and the result is retryable.

```powershell
.\gradlew.bat :data:testDebugUnitTest --tests com.streamvault.data.sync.XtreamIncrementalIndexExecutorTest --tests com.streamvault.data.sync.XtreamLiveCategorySchedulingTest --tests com.streamvault.data.sync.GuideHttpFailureTest --tests com.streamvault.data.sync.CatalogIndexJobStoreTest --tests com.streamvault.data.sync.ProviderSyncWorkerTest --tests com.streamvault.data.sync.XtreamIndexWorkerTest --tests com.streamvault.data.remote.xtream.OkHttpXtreamApiServiceTest --tests com.streamvault.data.sync.SyncManagerTest
```

Final result: **BUILD SUCCESSFUL in 50s; 189 tests across eight suites, zero
failures, errors, or skips**. Counts were read directly from Gradle XML reports.

| Suite | Tests |
| --- | ---: |
| OkHttpXtreamApiServiceTest | 15 |
| CatalogIndexJobStoreTest | 6 |
| GuideHttpFailureTest | 3 |
| ProviderSyncWorkerTest | 19 |
| SyncManagerTest | 121 |
| XtreamIncrementalIndexExecutorTest | 14 |
| XtreamIndexWorkerTest | 3 |
| XtreamLiveCategorySchedulingTest | 8 |
| Total | 189 |

These are targeted unit tests; no real portal, emulator, process death, or
WorkManager scheduling was validated. The original characterization run records
pre-fix behavior; later production changes and regression results are described
in the implementation updates above.

## Relevant production sources

- data/src/main/java/com/streamvault/data/sync/XtreamIncrementalIndexExecutor.kt
- data/src/main/java/com/streamvault/data/sync/XtreamIndexRecoveryPolicy.kt
- data/src/main/java/com/streamvault/data/sync/XtreamAdaptiveSyncPolicy.kt
- data/src/main/java/com/streamvault/data/sync/SyncManagerXtreamSupport.kt
- data/src/main/java/com/streamvault/data/sync/SyncManagerXtreamFetcher.kt
- data/src/main/java/com/streamvault/data/sync/SyncManager.kt
- data/src/main/java/com/streamvault/data/sync/CatalogIndexJobStore.kt
- data/src/main/java/com/streamvault/data/sync/ProviderSyncWorker.kt
- data/src/main/java/com/streamvault/data/sync/ProviderEpgSyncExecutor.kt
- data/src/main/java/com/streamvault/data/remote/xtream/OkHttpXtreamApiService.kt
- data/src/main/java/com/streamvault/data/repository/EpgRepositoryImpl.kt
- app/src/main/java/com/streamvault/app/StartupWorkRegistry.kt


## Combined Stalker and Xtream validation (2026-11-11)

The following focused combined unit run passed **458 tests across 29 suites**,
with zero failures, errors, or skips. It covers both providers' indexing and
recovery suites, sync manager, guide HTTP failures, workers, Stalker coordinator
and provider, and both API services:

```powershell
.\gradlew.bat :data:testDebugUnitTest --tests 'com.streamvault.data.sync.Stalker*' --tests 'com.streamvault.data.sync.Xtream*' --tests com.streamvault.data.sync.SyncManagerTest --tests com.streamvault.data.sync.SyncManagerXtreamSupportTest --tests com.streamvault.data.sync.GuideHttpFailureTest --tests com.streamvault.data.sync.CatalogIndexJobStoreTest --tests com.streamvault.data.sync.ProviderSyncWorkerTest --tests com.streamvault.data.remote.stalker.StalkerProviderTest --tests com.streamvault.data.remote.stalker.StalkerRequestCoordinatorTest --tests com.streamvault.data.remote.stalker.OkHttpStalkerApiServiceTest --tests com.streamvault.data.remote.xtream.OkHttpXtreamApiServiceTest --max-workers=2 --no-parallel '-Pkotlin.compiler.execution.strategy=in-process'
```

A separate Android 16 TV emulator run passed **6 instrumentation tests**:
Stalker file-backed checkpoint reopen (1), WorkManager unique-work isolation and
coalescing (2), and provider workflow lease/priority behavior (3). The workflow
lease test closes and reopens the database and simulates an expired lease; it
does not kill the Android process. The WorkManager tests inspect queued work and
isolation; they do not establish a full provider retry/backoff timeline.

```powershell
.\gradlew.bat :data:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.sync.StalkerRecoveryReopenInstrumentationTest,com.streamvault.data.local.ProviderWorkManagerSerializationTest,com.streamvault.data.local.ProviderWorkflowIntegrationTest' --max-workers=2 --no-parallel '-Pkotlin.compiler.execution.strategy=in-process'
```

No actual process kill, real-provider recovery, or end-to-end timed retry was
performed. These checks do not determine the cause of the original screenshot.
