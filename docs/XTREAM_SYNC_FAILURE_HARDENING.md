# Xtream failure handling and continuation queues

Date: 2026-10-10. Branch: `codex/xtream-live-sync-performance`.

## Changes

- A rejected bulk Live TV request no longer starts category-stream fallback for
  authentication failures. An already-started category metadata request may finish.
  Action-specific HTTP 403 remains eligible for category fallback: denying a full
  catalog request does not by itself prove that the credentials were rejected.
  HTTP 403 no longer spends three transient retries repeating the denied catalog
  request before switching topology; transport/throttle retries retain backoff.
- Full sync preserves the previous Live success timestamp after failed or partial
  coverage. Keeping the old channel library does not prove a fresh complete import.
  A complete category fallback still counts as success despite bulk-path warnings.
- Retryable movie/series category discovery failures now produce durable index
  continuations immediately. The worker retries category discovery before summaries.
  Rejected credentials produce permanent job status and no automatic continuation.
- Authentication failures in full summary streams or category slices terminate
  the section without scheduling more categories. A permanent rejection replaces
  an older PARTIAL job, so recovery cannot repeatedly reuse that partial status.
- Launch/periodic summary admission does not restart FAILED_PERMANENT jobs merely
  because they have no successful timestamp. Explicit forced operations can retry.
- Provider-specific index workers complete their WorkManager node after a recorded
  permanent section failure, allowing already-appended sections to execute. The
  durable index job and workflow retain the failure. Transient failures still use
  WorkManager retry/backoff; standalone periodic batch failures still report failure.

## VOD and EPG ordering

Continue using durable queues. Add Provider commits Live TV first, then fetches
movie and series category shells before returning. Summary payloads and hydrated
details are outside initial Live ingestion; details remain on demand.
The category shells also identify VOD-only providers when Live TV is empty, so
this pass retains their existing setup/activation behavior.

The provider catalog continuation chain runs Live auxiliary index backfill, movie
summaries, and series summaries. Category slices checkpoint completed work and
enqueue continuation nodes. This is serialized provider work, not simultaneous
full Live/VOD/guide downloads. Category-shell failure now has an immediate durable
recovery path rather than depending on a later periodic check.

EPG is scheduled only when enabled, in a separate unique chain with the existing
30-second initial delay. It checks queued/running catalog jobs and defers with
backoff while they remain. Guide retries therefore do not occupy the catalog chain.
Transient catalog failures can delay subsequent catalog jobs; permanent failures
release the chain and remain visible in durable section state. This change does
not introduce parallel full VOD downloads or alter slice/batch budgets.

## Verification

Focused tests cover rejected Live bulk requests, rejected summary/category
requests, permanent-state admission, transient discovery continuations, previous
PARTIAL status replacement, recovery coalescing and failed-live freshness.

The benchmark's optional `--settle-seconds 30` observes terminal setup state and
channel counts every second after completion, before synthetic-provider cleanup.
Its request report rejects bulk requests occurring after category fallback begins.
Use setup-only runs; explicit refresh/background probes intentionally issue requests
and cannot be combined with this assertion. The observation window is bounded and
does not establish indefinite quiescence or playback health.

Two setup-only fault scenarios passed on the API 36 Android TV emulator, using
the isolated benchmark package with bulk-first AUTO explicitly enabled:

| Scenario | Live requests | Result | Observation |
| --- | --- | --- | --- |
| 1,000 channels / 20 categories, bulk disconnected after 500 rows | 4 incomplete bulk attempts, then 20 categories | Partial; all 1,000 rows and 20 category records matched fixture fields | 30 seconds of stable terminal state/count; zero bulk requests after fallback |
| Same Live catalog, bulk HTTP 503; 200 movies / 100 series with full summary HTTP 503 | 3 rejected Live bulk attempts, then 20 categories | Partial; exact Live fields; movie/series jobs SUCCESS with exact Room/index counts | 30 seconds of stable terminal state/count; zero bulk requests after fallback |

Reports are retained locally under ignored build directories:

- `build/xtream-sync-performance/hardening-truncated-bulk-1k/report.json`
- `build/xtream-sync-performance/hardening-503-bulk-vod-1k/report.json`

The earlier `hardening-truncated-1k` and `hardening-503-vod-1k` runs used the
benchmark's historical AUTO policy opt-out and made no bulk Live requests. They
passed category-route checks but are not evidence of bulk failure recovery.
Both corrected runs completed instrumentation successfully and recorded complete
request traces. Synthetic providers and reverse mappings were cleaned afterward.
EPG was SKIP in these fixtures; no guide download or playable media was tested.

Fixture/report/catalog-fixture Python tests passed (17 tests). The final Gradle
run passed 196 JVM tests: 181 focused sync tests and 15 backup manager tests
included by the test-name filter. This includes the HTTP 401/403 distinction,
attempt isolation, category scheduling, provider recovery and continuation tests.
Both isolated app and instrumentation APKs assembled successfully; their package
IDs were checked before installation. `git diff --check` passed.

Queue completion/admission policy has focused unit coverage. The emulator VOD
run exercises successful continuation after transient bulk rejection; it does
not exercise a permanent-failure WorkManager dependency chain or EPG fetching.
Real-provider sync, guide acceptance, sustained playback and other fault profiles
remain separate release gates.

## Manual testing default follow-up

The isolated benchmark package formerly defaulted to the historical AUTO policy
whenever its override preferences were absent. After instrumentation cleaned the
preferences, a manual Add Provider run in that same app therefore selected
category mode on LOW devices. The emulator log and foreground package identified
this mismatch. Its ordinary UI now defaults to bulk-first like normal packages.
The instrumentation harness writes an explicit true/false override for route
comparisons and clears it afterward; historical opt-out comparisons remain available.
