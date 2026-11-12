# Stalker sync and recovery investigation

Date: 2026-11-10 (Asia/Jerusalem)

Implementation update: G3 is fixed in the working tree. Transient
guide failures no longer record an unsupported capability, and previously
recorded unsupported observations no longer suppress a later native guide
attempt. The test report below records the earlier characterization; its G3
behavior describes the pre-fix implementation. The targeted guide and sync
manager run passed 128 tests across three suites after updating the regression
and legacy assertion. Portals truly lacking native EPG may now be probed again
on subsequent eligible guide syncs; there is no definitive unsupported signal
in this path to safely distinguish them from transient failures.

## Current status and open work (2026-11-11)

- **Implemented:** G1 session-expiry deferral, G2 recovery of recognized
  exhausted transient failures, G3 guide retry after inconclusive failures, and
  G7 rate-limit deferral without spending the category retry budget. Historical
  descriptions and proposals below describe behavior before those fixes.
- **Open — scheduling and process recovery:** Test actual WorkManager retry and
  continuation execution, including constraints, cooldown wake-ups, sequential
  sections, and process death/restart. Emulator unique-work isolation and stale
  lease reclamation now pass, but do not exercise the full retry timeline. The emulator database-reopen test passes,
  but does not simulate process death or prove a worker is scheduled.
- **Open — authentication concurrency:** Exercise simultaneous expired-token
  requests, refresh/invalidation, cookies, and bounded request counts (G5).
- **Open — visibility:** Verify the relationship between queued/partial UI,
  persisted job state, and actual scheduled work (G4/G6); decide whether users
  need explicit waiting reasons and retry times. No UI change has been made.
- **Open — legacy ambiguity:** Old ambiguous authorization/error records are not
  automatically reopened; conservative recovery does not cover every legacy
  error string. Definitive account/device denials remain terminal.
- **Open — real provider:** No affected-device logs or real-portal reproduction
  establish the screenshot's cause or verify recovery against that provider.

Compared baseline: `dc7c7cf1fbdbcf55d9dfaa1cd6ece0159b9ac54f`.
Source inspected at: `1588c6406a14f268c8aedcbe294f7ff90b00264b`.

Branch update: all investigation changes and continuing work are on
`codex/provider-sync-recovery`. A focused recovery run passed 32 tests across
six suites, including a real in-memory Room database for both movie and series
checkpoints and Robolectric Android preferences for both persisted deadline
stores. This verifies recreated store objects against real DAO/preferences APIs,
not actual Android process death. A file-backed Room reopen test could not run
on this Windows/Robolectric environment: SQLite returned SQLITE_CANTOPEN even
with TRUNCATE journaling. No emulator was connected.

2026-11-11 continuation: an Android TV emulator became available and a
file-backed Room reopen instrumentation test was added. Its Kotlin source
compiled, but `:data:connectedDebugAndroidTest` stalled in Android dexing before
installing or executing the test and was cancelled. Those attempts did not
execute the emulator test; the later successful run is recorded below.
A second run with `--max-workers=2 --no-parallel` stalled at the same packaging
stage and was also cancelled. The earlier 32-test in-memory Room/preferences
result remains valid.
On 2026-11-11 a later emulator-test retry again stalled before execution and was
cancelled. A subsequent run completed successfully on Android TV emulator
`Television_1080p` (Android 16): the file-backed Room test passed for movie and
series checkpoints after closing and reopening the database. It verified saved
page progress, retry budget, cooldown eligibility, and the next page to fetch.
Command: `gradlew :data:connectedDebugAndroidTest
-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.sync.StalkerRecoveryReopenInstrumentationTest
--max-workers=2 --no-parallel -Pkotlin.compiler.execution.strategy=in-process --info`.
This is a database reopen in one process, not a process-death, actual network
recovery, or WorkManager scheduling test; those remain unverified.

Follow-up: [Xtream comparison and targeted test results](XTREAM_SYNC_RECOVERY_INVESTIGATION.md)
records which issues carry over and the separate Xtream recovery gaps.

## Reported symptom and evidence limits

The supplied Settings screenshot shows a Stalker/MAG provider with 17,208 live
channels, 1,619 films, 1,529 series, zero EPG programs, movies and series marked
queued, and a French "Connexion requise" availability badge. The user believes
this is probably after adding a new provider whose catalog is much larger.

This is a single observation, not proof that the counts stopped changing. No
affected-device logs, database, elapsed time, or reproduction are available.
`adb devices` showed no connected devices during the investigation. No runtime
tests had been executed when this initial report was written. Findings below
distinguish source behavior from suspected explanations.

Pressing Sync intentionally starting a fresh sweep is expected behavior. There
is no evidence that repeatedly pressing it produced this screenshot; the initial
manual-refresh hypothesis must not be treated as the root cause.

## Changes since the baseline

- `3d601e696` (Adjust automatic sync playback behavior) changes manual movie and
  series section refreshes to restart pending indexes rather than preserve their
  checkpoints. Scheduled background refreshes preserve pending work. The test was
  explicitly changed from resume semantics to restart semantics.
- The same commit makes manual index continuations append a successor while
  automatic continuations use KEEP by default. The separate index continuation
  coordinator still explicitly appends successors. A stranded automatic job has
  not been demonstrated.
- The default `deferWhileBusy` changes from true to false. Persisted settings may
  differ from that default; independent playback traffic admission also exists.
- The recent EPG job-state repair in SyncManager is guarded for Xtream providers;
  it does not repair Stalker guide recovery.
- Stalker transport, index worker, and the recovery policies discussed below were
  unchanged in the inspected baseline-to-HEAD range. These are possible existing
  gaps, not established regressions from the recent changes.

## Connection, authentication, and request lifetime

1. Authentication selects an endpoint and MAG compatibility/device profile,
   performs a handshake, obtains a token, optionally performs credential auth,
   retrieves profile/account information, and validates catalog access as needed.
2. A cached logical session includes endpoint, token, authentication time,
   compatibility identity, and server cookies. Shared caches/mutexes coordinate
   provider authentication; resumable authentication can also be persisted.
3. Each page is a separate HTTP request containing bearer authorization, merged
   MAG/server cookies, device User-Agent, and Referer as appropriate.
4. HTTP clients are cached and derived from a client with a connection pool
   configured for 10 idle connections and a five-minute keep-alive. A request can
   reuse a socket or use another socket. A bearer token is not a socket, and a
   disconnected socket does not itself erase the logical session or checkpoints.
5. Session checks use an explicit expiry if supplied, otherwise 30 minutes from
   authentication. Portal rejection can trigger reauthentication earlier.
6. Invalidation clears local/shared authentication, persisted resumable auth,
   relevant category/stream caches, and API session scopes. It does not itself
   reset durable catalog page checkpoints.
7. A failed cached authentication endpoint can trigger bounded endpoint recovery.
   Broad device/profile rediscovery is more restricted than a routine token retry.

Sources: `StalkerProvider.kt` (ensureAuthenticated, runWithAuthorizedSession,
invalidateAuthentication); `OkHttpStalkerApiService.kt` (authenticate,
requestJson, stalkerHttpClientFor); `StalkerApiService.kt` (StalkerSession);
`app/.../di/NetworkModule.kt`; `StalkerTransport.kt`.

## What pages mean and why initial counts can be small

Catalog requests use routes such as `type=vod&action=get_ordered_list` with a
category identifier and page parameter `p`. Responses contain a portion of the
catalog and advertised page/item totals. This downloads metadata, not media.
Internally checkpoints use one-based pages; portal page-base compatibility is
handled by the transport/provider implementation.

Initial preparation can fetch the first page of each category and defer the rest
for large fresh sources (observed criteria include page size 1..50 and at least
200 remaining pages). Bootstrap continuation warms category heads and schedules
the background catalog walk and guide work. Small nonzero movie/series counts
can therefore represent a healthy intermediate state.

The importer permits up to eight concurrent requests by default, with an
adaptive network admission policy initially allowing three and adjusting from
one to eight. Section ordering is configurable: parallel, movies first, or series
first. With sequential ordering, pending work/cooldown in the first section can
delay the other section.

Sources: `StalkerPagedCatalogImporter.kt`; `StalkerCatalogSyncExecutor.kt`;
`SyncManager.kt` (resumeStalkerBootstrap); `StalkerAdaptiveIndexPolicy.kt`;
`StalkerCatalogExecution.kt`; `StalkerIndexPolicy.kt`; `data/build.gradle.kts`.

## Recovery layers and persisted progress

| Situation | Current behavior seen in source |
| --- | --- |
| Transport interruption or server 5xx | API runApiCall normally allows three attempts with short exponential/jittered delays. |
| Eligible page still fails | Importer adds up to three attempts with short delays; these are layered attempts, not one global three-request limit. |
| Authorization rejection | Provider invalidates authentication and retries once; the summary-page wrapper also supplies an explicit auth-refresh retry. |
| HTTP 401 | Classified as SessionExpired, a subtype of Authorization. |
| HTTP 403 | Session-like response can be SessionExpired; HTML/blocking responses are distinguished as BlockedOrConfiguration. |
| HTTP 429 | Provider-wide circuit cooldown gates requests, including authentication; one half-open probe checks recovery. |
| Retryable category failure | Persist failure and budget; successor worker retries when eligible. |
| Worker postponed | Network/battery constraints, low memory, playback, workflow ownership, or automatic-sync policy may delay execution. |
| App/process interrupted | Saved checkpoints survive; startup reconciles pending Stalker jobs. In-flight requests and unsaved buffers do not survive. |

Each category saves last attempted/successful page, item/page totals, completion,
last error/status, failure count, retry budget, and retry deadline. Pages may be
fetched concurrently but are published in contiguous order. If page 18 fails
after pages 1..17 were checkpointed, pages 19..20 cannot advance the checkpoint;
the next eligible pass starts at page 18. Data and checkpoint writes are not all
one monolithic catalog transaction: interruption can cause a page to be replayed
through upserts. This is not an exactly-once transport guarantee.

Continuation is scheduled as unique provider work; pending category state is the
authority for choosing what to fetch. WorkManager retries use exponential
backoff, and category cooldown is a separate mechanism. Startup rescheduling
does not reset terminal category failures.

Sources: `StalkerPagedCatalogImporter.kt`; `StalkerIndexCheckpointStore.kt`;
`StalkerIndexContinuationCoordinator.kt`; `StalkerIndexWorker.kt`;
`ProviderSyncWorkScheduler.kt`; `ProviderWorkflowRunner.kt`;
`app/.../AppStartupCoordinator.kt`.

## Gaps and risks requiring tests

### G1: Exhausted session recovery becomes a permanent category failure

Implementation update: typed session-expired and token-rejected failures now
enter a 15-minute cooldown after the existing authentication refresh attempts,
without spending the category budget. Explicit blocked-account, invalid-MAC,
device-conflict, unregistered-device, and clock-skew errors remain permanent.
Previously saved permanent receipts explicitly saying "session expired" are
eligible to resume from their saved page. Ambiguous old 401s are not reopened.

`StalkerIndexRecoveryPolicy.failureState` returns FAILED_PERMANENT for likely
authorization failures, including SessionExpired. The importer then records a
terminal category failure with zero budget; `canAttempt` excludes it. A later
working session does not automatically reopen that category. This can preserve
partial rows while abandoning the rest after a transient authorization incident.
Actual occurrence in the supplied screenshot is unproven.

### G2: Bounded retry exhaustion can abandon recoverable categories

Implementation update: exhausted receipts with recognized transient error text
(rate limit, timeout, connection reset/DNS/connect errors, or selected 5xx)
become eligible again after cooldown, preserving page progress. Newly exhausted
receipts receive a 15-minute delay; subsequent retries use capped backoff.
Ambiguous, malformed-data and definitive authorization errors remain terminal.
Matching legacy text is conservative and does not recover every possible provider
error message; this is not a claim that all old failed records are migrated.

Verification: the combined Stalker importer, checkpoint, policy, catalog
execution, provider, and sync-manager run passed 220 tests across six suites.
This covers session-expiry deferral, definitive device/account rejection,
legacy transient eligibility, retry cooldown/budget restoration, and page
preservation with fake DAO state. Real provider, Room process death, and
WorkManager scheduling remain untested.

Retryable category failures consume a budget of three unsuccessful passes. With
no intervening successful page, the first failure has no category cooldown, the
second waits approximately ten minutes, and the third exhausts the budget.
Successful page progress resets the budget. Terminal/budget-exhausted categories
are skipped by continuation and startup recovery. An outage long enough to
consume the budget may therefore require explicit reset/repair after it ends.

### G3: EPG failures can incorrectly teach "unsupported"

The native guide importer tries bulk guide and potentially per-channel fallback.
If no channel was replaced and the fallback has failed channels, it records
`epgSupported=false`, without requiring definitive unsupported-endpoint evidence.
Transient network/auth failures can enter this branch.

Later `syncStalkerProviderEpg` skips native guide when validated learned support
is false and epgUrl is blank. This check occurs even with force=true. The state
store has a seven-day validation window; other operations can refresh validatedAt,
so seven days is not a guaranteed recovery deadline. Authentication/profile
evidence or explicit learning reset may alter this state; verify those paths.

### G4: Queued/partial status does not establish active progress

Settings maps RETRY_WAIT to Queued. The importer publishes QUEUED while unresolved
categories remain and PARTIAL when only terminal/problematic coverage remains.
Startup can reschedule PARTIAL jobs without making terminal categories attemptable.
Need end-to-end assertions that the UI, persisted job, worker queue, and terminal
category state remain understandable and consistent.

### G5: Layered retries and concurrent authentication deserve integration coverage

Several retry layers multiply requests. Auth mutexes serialize authentication,
but correctness under concurrent old-token failures, invalidation, refresh, and
in-flight requests has not been established in this investigation. Test token
expiry across concurrent pages, server cookie preservation, and bounded request
counts; do not assume an authentication race is already proven.

### G6: Delays can resemble a hang

Playback admission can defer indexing by 15 minutes; low memory and worker
constraints can also hold work. Sequential section ordering can hold the other
section behind pending work. Distinguish expected deferral from an orphaned job.
No evidence yet shows that the recent KEEP scheduling change loses a continuation.

### G7: Rate-limit cooldown can consume the category retry budget locally

Implementation update: new rate-limit failures now persist COOLDOWN with a
retry deadline without reducing the category budget, and the importer stops its
immediate page retries on a rate-limit response. A positive Retry-After is used,
bounded to one minute through two hours; absent/zero delay defaults to 15 minutes.
If the provider's existing circuit lasts longer, subsequent admission is deferred
again without consuming budget. Previously exhausted checkpoints are not reset
by this change. The historical reproduction below describes the pre-fix code.
The post-fix targeted run passed 44 tests across the request coordinator,
checkpoint store, index policy, and importer suites, including a 429 importer
regression and repeated local cooldown rejection checks. Real WorkManager and
portal behavior remain untested.

Follow-up source trace and component test confirm a mismatch between the shared
network circuit and category retry state:

- HTTP 429 and explicit soft-throttle responses become RateLimited. Retry-After
  controls cooldown; absent that header, default backoff is 15, 30, 60, then 120
  minutes for successive strikes without recovery. The initial duration is
  clamped to one minute..two hours. A server 5xx with a positive Retry-After
  also opens the shared circuit.
- All new API requests for that saved provider, including authentication and
  playback-link resolution, are rejected locally during cooldown. Existing
  in-flight requests are not automatically cancelled; existing media playback
  is not itself a portal API request. Cooldown is persisted across restart.
- After expiry one half-open request tests recovery. Success clears the circuit;
  a failed probe without another explicit delay waits five more minutes.
- API runApiCall avoids retrying RateLimited, but the outer importer retries
  eligible failures and ultimately records a failed category pass. The category
  classifier treats RateLimited as retryable and does not use retryAfterMillis
  when deciding its next retry deadline.
- Therefore three failed passes can consume the category budget while all page
  attempts are still being refused locally by the same cooldown. The first
  category retry has no delay; the second waits ten minutes, shorter than the
  default fifteen-minute portal pause. After circuit recovery, the exhausted
  category is still terminal until explicitly reset.

The new test composes the real coordinator, failure classifier, checkpoint
store, and attempt policy with retained fake DAO state and a simulated clock.
It demonstrates local refusals exhausting budget before cooldown expiry and a
successful half-open recovery not reopening the category. It does not execute
the full WorkManager scheduling timeline; exact wall-clock occurrence depends
on worker admission and scheduling.

Pure EPG rate-limit handling stops remaining fallback calls and returns retryable
warnings; it does not by itself add a failed channel or learn unsupported. Mixed
earlier channel failures followed by throttling can still reach G3's unsupported
branch when no guide data was replaced. That mixed EPG case is source-inspected,
not separately reproduced.

Sources: `StalkerRequestCoordinator.kt` (recordRateLimit, acquireNetworkPermit,
recordNetworkFailure); `OkHttpStalkerApiService.kt` (runApiCall, HTTP/soft-error
parsing); `StalkerPagedCatalogImporter.kt`; `StalkerIndexCheckpointStore.kt`;
`ProviderEpgSyncExecutor.kt`.

## Historical candidate follow-up changes (proposed before fixes)

- Separate recoverable session expiry/token rejection from definitive account,
  MAC, registration, or device-identity denial. Bound retries and preserve portal
  cooldowns; do not create an unlimited authentication loop for blocked accounts.
- Represent a provider-wide outage/session recovery separately from a category's
  malformed/permanent failure so a temporary outage does not retire many categories.
- Treat local circuit rejection as deferral until the provider deadline, without
  spending a category's retry budget or repeating auth/page requests locally.
- Define how a successful provider recovery reopens categories stopped by a
  transient incident, preserving last successful pages rather than requiring a
  fresh catalog sweep. Decide separately how exhausted malformed-data failures
  should be retried.
- Learn EPG unsupported only from definitive capability evidence. Treat transport,
  session, and server failures as inconclusive; allow explicit force/repair to
  re-probe a cached unsupported capability.
- Expose why work is waiting, retry time, and terminal coverage where necessary,
  with all UI/localization/contrast requirements applied if product UI is changed.

These were the original proposals. See the current-status summary and the
implementation updates above for what has since been completed or remains open.

## EPG recovery is different from catalog page recovery

Native bulk EPG imports batches of 5,000 programs; fallback is limited to 48
channels per run when windowing is necessary, with a day-based rotating window.
An interrupted bulk response can preserve already received guide data without
advancing successful freshness and should cause a retry. It does not persist a
byte offset into the old bulk response; retry starts another import. Untouched
channels retain prior data, while channels being replaced are updated in batches.

## Meaning of the connection badge

ProviderAvailabilityRepositoryImpl makes a separate account/profile availability
check. Authentication-required can represent denied authAccess, an expired or
blocked/disabled profile, or an authorization exception. It is not a live socket
monitor or proof of the indexing failure's cause. A stale availability result and
active indexing can coexist. Capture timestamps and the underlying reason.

## Validation plan

Test execution and reproductions are tracked separately in
[`STALKER_SYNC_RECOVERY_TEST_RESULTS.md`](STALKER_SYNC_RECOVERY_TEST_RESULTS.md).
The investigation above describes source inspection; consult that report for
which scenarios have actually been executed and any corrections from testing.

Initial targeted baseline: 178 tests passed with zero failures, errors, or skips
across OkHttpStalkerApiServiceTest (87), StalkerProviderTest (64),
StalkerSessionTest (2), StalkerIncrementalIndexExecutorTest (12),
StalkerIndexPolicyTest (7), StalkerIndexWorkerTest (3), and
StalkerNativeGuideInterruptionTest (3). This confirms the existing suite passes;
it does not establish that G1-G3 recover correctly. Focused fault-injection
results are recorded in the linked report.

### Executed focused validation

The final combined run passed 188 tests across nine classes, with zero failures,
errors, or skips. This includes four new characterization tests and six existing
HTTP cancellation tests in addition to the baseline selection. Results were
independently checked against the Gradle XML reports.

- G1: The actual SessionExpired classifier plus checkpoint store persists
  FAILED_PERMANENT, zero budget, and the prior successful page. A fresh store
  over the retained DAO state remains ineligible under the actual attempt policy.
- G2: Three failed category passes exhaust the budget; the second has a measured
  ten-minute cooldown. Prior page progress remains. A successful resumed page
  resets the failure count and budget. These are component tests, not real
  backend/network or Android restart tests.
- G3: The actual guide executor and portal-state store reproduce transient
  failure -> learned unsupported -> forced retry skipped. On that skipped run,
  hasRetryableFailure is false, despite the warning and unchanged old guide.
  Explicit capability invalidation allows the restored mock backend to be
  contacted and import data, providing a control for the cached-state cause.

The original characterization run asserted the then-current undesirable
outcomes. Subsequent fixes and their regression tests are recorded in the
implementation updates above. See the separate report for the original
commands, assertions, and remaining untested scope.

1. Run existing Stalker session/provider, paging, policy, worker, coordinator,
   startup/recovery, and interrupted native-guide tests as appropriate.
2. Add focused fault-injection tests for G1: expired token recovers at the same
   page; repeated auth rejection becomes terminal; restored access does not
   silently resume a terminal category. Contrast genuinely blocked accounts.
3. Exercise G2: outage, budget/cooldown, later success, and successful-page budget
   reset. Check both parallel and sequential section ordering.
4. Reproduce G3 across two guide runs with persisted learned state: transient
   bulk/fallback failures, restored backend, and forced retry. Verify whether the
   restored backend is actually contacted. Preserve prior guide data assertions.
5. Test cancellation/restart with already committed and out-of-order pages;
   ensure the first gap is retried, rows remain, and completion is not falsely set.
6. Inspect coverage of unique-work KEEP/append continuation behavior and report
   where mocks do not establish actual WorkManager scheduling correctness.
7. Keep defect characterization distinct from assertions about desired behavior.
   Do not change production recovery policy while investigating.
8. Record exact commands, results, test counts, environment blockers, and remaining
   untested scenarios in a separate test report linked here.

## Runtime evidence still required

Obtain sanitized logs and persisted job/category state from an affected initial
import: page cursor, successful/expected totals, errors, retry budget/deadline,
worker state/next execution, auth refresh outcomes, learned EPG capability, and
availability-check timestamps. Never include portal credentials, bearer tokens,
MAC addresses, cookies, or secret URLs in shared reports.

No UI or playback changes are part of this investigation. Real-provider initial
import, emulator behavior, connection reuse on the wire, and the screenshot's
actual root cause remain untested until separate runtime evidence is gathered.
