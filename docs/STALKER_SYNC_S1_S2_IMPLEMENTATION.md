# Stalker setup: resolved category reuse and identity persistence

Implemented 2026-10-10. Covers S1 and S2 of
[the improvement plan](STALKER_SYNC_IMPROVEMENT_PLAN.md). S3-S6 remain pending.

## Changes

Successful series/VOD category responses obtained by existing layout detection
are retained for one use by the subsequent category mapping path. Upfront portal
discovery, authentication, compatibility validation, and type/layout decisions
are unchanged. Failed probes are not converted into cached empty responses.

An explicit process-local handoff bridges the discovery client and the sync
client after the final provider ID is assigned. Its key includes provider ID,
configuration generation, and a digest of the complete typed configuration.
Consumption requires matching endpoint, token, authentication epoch, session
scope, expiry, and compatible resolved layout. The snapshot has a 60-second
lifetime; the shared cache is limited to 256 entries. Authentication invalidation
and provider deletion clear applicable handoffs. No new persistent session,
credential, or raw payload storage is introduced. The normal network path remains
the fallback after cache misses, restarts, mismatches, and expiration.

Unified VOD consumes the VOD endpoint probe while continuing to map categories
under the VOD content type. Raw category IDs are translated only within the
final provider's identity scope. Existing category/adult/wildcard mapping remains
authoritative.

Identity resolution now uses chunked existing-raw-ID and occupied-surrogate-ID
queries, followed by batch inserts. SQL parameter chunks are capped at 400.
Existing mappings, sorted assignment order, preferred category IDs, numeric
candidates, legacy hash candidates, and high-surrogate allocation precedence
are preserved. Ignored insert conflicts take the existing collision recovery
path; established identities are never replaced. The current lock and database
transaction still own the operation. No schema migration is needed.

Immutable category lookup maps replace repeated list scanning during row mapping.
They preserve the earliest list match when ID and name disagree, duplicate-name
ordering, and case-insensitive name matching. They rebuild when the underlying
category seed list changes and clear when authentication is invalidated.

The existing 200-row bootstrap limit, catalog activation, continuation scheduling,
and setup navigation behavior are unchanged.

## Validation

The following command completed successfully:

```powershell
.\gradlew.bat :data:testDebugUnitTest --tests '*Stalker*Test' --tests '*SyncManagerTest' --tests '*ProviderRepositoryImplTest' :data:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.local.StalkerIdentityIntegrationTest' --console=plain
```

- 346 unit tests across 24 suites passed, with no failures, errors, or skips.
- The Room identity integration test passed on `emulator-5554`, reported as
  `Television_4K_2(AVD) - 16` by the Android test runner.
- New coverage verifies one-shot split/unified category reuse, normal mapping,
  invalidation, new-provider ID handoff through the existing persisted session,
  configuration/generation/session/layout mismatches, expiry, and deletion.
- Identity checks cover more than one SQL chunk, equivalence with sequential
  assignment rules, numeric aliases, occupied numeric/hash candidates, refresh,
  provider isolation, and competing identity conflict recovery.
- Room checks verify actual unique constraints, ignored conflicts without
  replacement, reverse lookup, preferred category IDs, and transaction rollback.
- Category mapping checks preserve earlier name matches when a later ID match
  disagrees, including duplicate case-insensitive names.
- `git diff --check` passed.

The first Room run exposed an incorrect assertion that raw ID `1` necessarily
owned surrogate ID `1`. Existing sorted assignment lets `001` claim that ID
first. The assertion now checks the actual assigned surrogate; identity rules
were not changed to satisfy the test.

Validation output is under ignored `build/stalker-validation.log`, the Gradle
unit-test reports, and the connected Android test reports. No before/after
performance measurement was run or required. No measured speedup is claimed.
Real-provider onboarding and sustained playback were not exercised in this
implementation batch; the decoder and ingestion pipeline have not changed.
