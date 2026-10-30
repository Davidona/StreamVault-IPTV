# Catch-up Support Findings and Plan

**Review date:** 2026-10-30  
**Status:** The implementation plan is complete. Provider-backed archive playback remains an environment-dependent smoke check.

## Findings

### The archive playback flow exists

Choosing a guide programme sends its start and end times to the provider repository. The repository builds replay URL candidates, which then use the normal player recovery path. The existing flow is programme based; there is no guide-free timestamp entry flow.

### Xtream and Stalker use provider-specific replay paths

- Xtream reads its archive flag and window and builds timeshift URLs.
- Stalker builds archive requests from its internal stream token and the selected programme interval.
- Stalker still requires a provider-marked archive programme when it has no reported archive window.

### M3U metadata and URL modes were incomplete

The parser read catch-up attributes, but the channel and sync persistence path dropped the mode and correction value. Numeric legacy `timeshift` values were not used as the archive window. URL generation expanded only a full source template, so it missed append/default query formats, SIPTV shift requests, Flussonic paths, and Xtream-style paths.

The default and append modes also need a provider-specific query format when a playlist does not include `catchup-source`. A fixed UTC query cannot fit every provider.

### Unknown windows blocked some advertised archives

An M3U or Xtream channel with an explicit replay mechanism and no reported day count could not play an ordinary past guide entry unless the entry itself carried an archive flag.

## Implementation completed

- Preserve M3U catch-up mode and correction seconds through parsing, channel models, sync staging, projections, and Room persistence. The database is version 90 with a migration from version 89.
- Parse `catchup` and `catchup-type`, numeric legacy `timeshift`, and channel-level or playlist-level correction values.
- Build and expand full templates, append/default query fragments, SIPTV shift, Flussonic, and Xtream-style paths. Support programme start/end, current time, date, duration, and offset tokens, while applying catch-up correction.
- Add an editable M3U fallback query format in Live TV settings. It defaults to `?utc={utc}&lutc={lutc}`, is used only when an entry has no source template, can be left blank to disable the fallback, and is included in settings backup and restore.
- Localize the new setting in all 38 advertised locales.
- Allow completed M3U and Xtream programmes when an explicit replay mechanism exists but the provider window is unknown. Keep Stalker’s provider-marked archive rule.
- Include M3U replay metadata in sync fingerprints so playlist changes are applied.
- Avoid offering unresolved `{catchup-id}` templates. Programme identifiers are not currently mapped from guide data.

## Decisions

- A single editable fallback query format is sufficient for this pass. Per-channel `catchup-source` values take precedence. A provider that needs another format can set the global fallback or supply its own source.
- Manual timestamp entry is a separate UI feature and is out of scope for programme-based archive playback.
- `catchup=vod` templates that require `{catchup-id}` are unsupported until the programme-specific identifier is parsed and persisted end to end.

## Validation completed

- Focused domain/data tests passed for archive eligibility, M3U parsing, configured query expansion, append/shift/Flussonic/Xtream URL construction, M3U capability-adapter URL generation, repository request wiring, preference persistence, backup classification, and sync-stage metadata/fingerprint propagation.
- Room migration instrumentation passed on the `Television_1080p` Android 16 emulator. It migrated a version 89 database containing provider, channel, and staged channel rows to version 90 and verified the new metadata columns and retained rows.
- `:feature:settings:testDebugUnitTest` passed.
- `:app:compileDebugKotlin` passed.
- XML resource validation found all three new keys in the default resources and each of the 37 translated locale directories.
- `git diff --check` passed.

## Provider playback smoke check

No provider credentials or working M3U archive fixture were available in this environment, so an actual provider response and decoded archive playback were not verified. The local tests verify the final candidate URLs and the app’s adapter/repository metadata flow, but they cannot confirm that a particular provider accepts those URLs. Run one archive programme against a known-good M3U provider before claiming provider-specific playback compatibility.
