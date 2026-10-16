# Stremio UX and functionality improvement plan

- Date: 2026-10-16.
- Status: Planning only; implementation has not started.
- Reviewed baseline: `f1ac7d3d` on `feature/stremio-addons`.
- Worktree: `D:\antigravity-scratch\iptv-player\.worktrees\stremio-addons`.

## Purpose and scope

Bring the Stremio experience up to the app's current browsing standard in both
layout and behavior. Preserve the existing theme and colors, correct the
integration defects found after merging develop and modern visuals, and make
navigation with a TV remote predictable.

This plan reconciles the five agreed UI directions with the subsequent code
review. It supplements the original Stremio implementation plan and handoff;
historical verification in those documents is not proof that the merged
baseline passes the new acceptance criteria below.

## The five agreed UI decisions

| Decision | Resulting behavior | Review issues addressed |
| --- | --- | --- |
| 1. Keep installation and management in Extensions | Once add-ons exist, show a compact heading with installed count and an Add button. Use the larger introduction only for an empty installation. | The current hero and repeated headings consume most of the viewport. |
| 2. Give browsing a dedicated content screen | Browse opens a full content page using the Movies/Series visual language. Back returns to Extensions management. | Browsing is currently nested below Extensions tabs, a large introduction, and another tab row. |
| 3. Make installed add-ons compact rows | Show logo, name, enabled switch, and capability badges. Open details for version, server origin, catalog settings, refresh, ordering, and removal. | Large management cards expose infrequent controls and do not explain what each add-on supplies. |
| 4. Make browsing content-first | Use a compact catalog/type/filter/search toolbar and a poster grid consistent with Movies/Series. | Long genre-chip strips, per-catalog search fields, and repeated panels push posters below the fold. |
| 5. Use one main vertical content scroller per screen | Management uses a list; browsing uses a grid. Give selected state and current remote focus distinct appearances. | Content is squeezed into a small scrolling region, and focus can be confused with tab selection. |

A short toolbar may remain above the scroller. It must leave room for complete
poster cards and titles on the target TV viewport. Filters and add-on details
can use compact dialogs or sheets with their own bounded scrolling when needed.

## Review findings

The findings below are based on source inspection and existing unit tests.
The torrent and legacy-upgrade failures were traced in code, not reproduced
through a real torrent session or an emulator database upgrade during this review.

| ID | Priority | Finding and effect | Implementation evidence |
| --- | --- | --- | --- |
| R1 | P1 | Torrent handoff applies HTTP header validation to the original magnet URL. This rejects a valid plugin-resolved torrent and can mask the useful missing-plugin error. | `feature/playback/.../player/PlayerViewModel.kt`, header validation after stream resolution; `domain/.../stremio/StremioMediaRequestHeaders.kt`. |
| R2 | P1 | Earlier Stremio and develop schemas independently used versions 79-80. The earlier Stremio v80 lacks the merged Xtream receipt columns and corrected episode index. The current upgrade chain does not repair those differences. Upgrading that schema can fail final Room validation. | Historical schema at `2d275bc1`; current `FeatureMigrationsV78To79.kt`, `FeatureMigrationsV79To80.kt`, and `FeatureMigrationsV82To83.kt`. |
| R3 | P1 UX | The large hero and two navigation layers sit outside the content scroller. Management controls and browse posters are pushed into the bottom of the screen. | `feature/stremio/.../presentation/AddonsScreen.kt`. |
| R4 | P1 UX | Catalog search uses a normal editable text field with no separate navigation-focus and editing states. | `StremioCatalogBrowseContent.kt`; the existing shared `SearchInput.kt` already supports click/select activation. |
| R5 | P1 UX | Episode sources are appended after the full episode list. Selecting an early episode in a long series can leave its sources far offscreen. | `StremioItemDetailContent.kt`, episode items before the streams section. |
| R6 | P2 | Player Back explicitly targets the add-on root, skipping title details. Browse lacks deliberate restoration of the focused poster. | `feature/stremio/.../navigation/StremioGraph.kt`; Stremio browse composables. |
| R7 | P2 | All eligible catalog rows start loading, including offscreen rows. Stream resolution waits for all providers before publishing usable results. | `StremioCatalogBrowseViewModel.refreshRows`; `StremioResourceResolver.resolveMany` uses `awaitAll`. |
| R8 | P2 | Retry after a pagination failure resets the query to page one, clears loaded items, and can serve the first page from cache. It does not retry the failed offset. | `StremioCatalogBrowseViewModel.retry`, request reset, and page-cache lookup. |
| R9 | P2 | Removal is immediate, manifest refresh is absent, and add-ons without catalogs still expose a Catalogs action. | `AddonsScreen`, `AddonsViewModel`, and `StremioAddonRepositoryImpl`. |
| R10 | P2 | Some unexpected repository failures lack cleanup of loading state; some detail exceptions are swallowed and can appear as an empty/no-provider result. | Install/management operations in `AddonsViewModel`; exception paths in `StremioItemDetailViewModel`. |

### Requirements clarified by the existing handoff

- **Visibility is an agreed product choice:** Stremio remains open by default,
  controlled by the separate Settings > Privacy > Hide Stremio Add-ons option.
  The historical handoff records that this is independent of global parental
  levels, and existing tests enforce it. The review initially raised this as an
  open question; this plan preserves the recorded choice. A different adult
  category policy would require new user direction.
- **Upgrade-origin scope needs explicit verification:** the database invariants
  document excludes some discarded version-79/80 branch schemas. Establish
  whether that exclusion covers the earlier Stremio schemas before applying it
  to them. Identify actual supported Stremio upgrade origins and preserve their
  data. Do not infer that an earlier installed Stremio database has no users.
- **User acceptance remains user-owned:** the existing handoff reserves manual
  UI/UX and real-torrent acceptance for the user. Agent verification covers code
  tests and builds; install the requested build on the designated emulator for
  the user's acceptance pass.

## Intended user experience

### Extensions management

- Keep the Plugins/Stremio section selector in Extensions.
- With installed add-ons, replace the hero with a compact header such as
  **Stremio add-ons · 2 installed**, plus **Add add-on** and **Browse** actions.
- Each compact row shows logo or fallback, name, enabled switch, and declared
  capabilities: Catalogs, Streams, and/or Subtitles. Capability labels come
  from the manifest, rather than catalog count alone.
- Selecting a row opens add-on details. Expose catalog toggles only when the
  add-on has catalogs. Offer refresh, ordering, server origin/version, and
  removal confirmation there.
- Preserve the install dialog's content-sized height. Show installation errors
  within the dialog, retain the entered URL after a recoverable error, and
  prevent duplicate submissions.
- Explain streams-only and subtitles-only add-ons. An enabled streams-only
  add-on can supply sources for compatible titles from another catalog.
- Distinguish no installed add-ons, no enabled add-ons, and no enabled catalogs;
  provide an appropriate next action for each state.

### Dedicated browsing

- Keep the app shell and a short page toolbar; remove the management hero and
  Extensions sub-navigation from this content page.
- Select an available catalog using a compact picker showing catalog name and
  supplying add-on. Offer Movies/Series selection when relevant to the available
  catalogs. Do not imply that an arbitrary type filter is supported by an add-on.
- Show only filters declared by the selected catalog. Use a compact genre picker
  rather than an unbounded strip of chips. Explain unsupported required filters.
- Search applies to the selected catalog and appears only when that catalog
  supports it. Search-required catalogs wait for a query and show a clear prompt.
- Reuse the shared search control: D-pad focus only highlights it; click/OK
  opens editing and the keyboard. Back exits editing and restores control focus;
  Search/Done closes editing and moves to results. Preserve the query when the
  keyboard closes. Mouse activation must also work.
- Keep debouncing and stale-request cancellation. Preserve existing results
  while a replacement query loads, with a clear loading indication. Do not label
  old results as the new query's results.
- Reuse the Movies/Series poster proportions, title treatment, spacing, focus
  behavior, and responsive grid sizing. Missing images have a stable fallback.
- Load the selected catalog and subsequent pages on demand, with bounded
  concurrency and memory. Keep any prefetch deliberate and limited.
- Retain catalog, filters, query, loaded pages, scroll position, and focused item
  when returning from details. If an item disappears, restore focus to a nearby
  valid item or the relevant control.
- Place Continue Watching in the browsing experience without adding another
  permanent header layer. Preserve exact playback identity and resume behavior.

### Title, episode, sources, and player

- Use a compact season/episode selection flow for series. Selecting an episode
  immediately opens its source picker; sources do not sit below the episode list.
- Show the exact title/episode being resolved. Publish successful source results
  as providers complete while showing which providers are still loading.
- Preserve configured provider ordering and source attribution even when results
  arrive out of order. Keep successful sources available beside provider errors.
- Preserve useful source name/title/description fields and show a clear reason
  when a source kind is unsupported.
- For torrents, check plugin availability before playback. Offer a clear route
  to plugin management when unavailable. Preserve plugin resolution errors and
  validate the plugin's resulting playable stream through the existing policy.
- Scope add-on HTTP headers to direct HTTP media; preserve the torrent plugin's
  resolved-stream behavior. Cover both successful and unsuccessful handoffs.
- Expected Back flow: **Player > Sources/Title > Browse > Extensions management**.
  Retain the chosen episode, source selection, and browse focus as appropriate.
- Preserve current history/resume, inline and add-on subtitles, and direct-stream
  header behavior throughout the navigation changes.

## Implementation sequence and completion gates

### Phase 1 - Correct playback and upgrade compatibility

Addresses R1-R2.

- [ ] Repair magnet handoff without applying the direct-HTTP header requirement
  to the original magnet URI; preserve meaningful plugin failures.
- [ ] Audit supported upgrade origins against the historical Stremio and develop
  schemas, including the episode index and four Xtream receipt columns.
- [ ] Add an appropriate forward compatibility migration for supported legacy
  variants and the already-installed current schema. Follow the database
  invariants, export the final schema, and preserve providers, add-on settings,
  recordings, and playback history.
- [ ] Add regression coverage for direct HTTP, invalid headers, missing/failed
  torrent plugin, valid plugin output, and populated supported upgrade origins.

Gate: targeted playback tests pass, supported database upgrades validate the
final Room schema and foreign keys, and a current debug APK builds.

### Phase 2 - Split navigation and compact management

Addresses UI decisions 1-3 and 5; R3, R6, and R9.

- [ ] Separate management and dedicated browse routes with explicit Back targets.
- [ ] Replace the installed-state hero with the compact header and add-on rows.
- [ ] Add capability-aware details, catalog settings, ordering, and removal
  confirmation. Make repeated actions safe and restore focus after a row changes
  or is removed.
- [ ] Add manifest refresh preserving install identity, enabled state, catalog
  choices, ordering, and history. Keep the saved manifest if refresh fails.
  Invalidate affected browse data after successful refresh.
- [ ] Preserve the larger empty-install introduction and compact install dialog.

Gate: management state/action tests pass; the user can inspect the refreshed
management screen and its empty, disabled, and streams-only states.

### Phase 3 - Build full browsing and explicit search activation

Addresses UI decisions 2, 4, and 5; R3-R4 and the catalog portion of R7-R8.

- [ ] Use the established browsing grid/components with a compact toolbar.
- [ ] Implement capability-aware catalog selection, search, and filter pickers.
- [ ] Use shared click/OK-to-edit search and retain the draft/query on exit.
- [ ] Fetch the selected catalog on demand and cancel obsolete work.
- [ ] Preserve results during replacement requests and show distinct loading,
  empty, required-input, unsupported, and failed states.
- [ ] Retry a failed page at its original offset while retaining prior pages.
  Distinguish retry from an explicit refresh; refresh must bypass stale cache.
- [ ] Restore grid position and focus after returning from details.

Gate: request/state regressions pass; user acceptance confirms complete poster
cards and titles are visible on entry and D-pad focus never opens the keyboard.

### Phase 4 - Fix episode/source flow and contextual Back navigation

Addresses R5-R7 and the playback-facing portion of R1.

- [ ] Add season/episode selection and an immediate source picker.
- [ ] Publish provider outcomes progressively with stable attribution/order.
- [ ] Preserve successful sources on partial failure and retry only the failed
  provider. Cancel stale episode lookups.
- [ ] Add torrent capability feedback and clear unsupported-source behavior.
- [ ] Return from the player to the selected title/episode context, then restore
  the browse catalog, query, filters, position, and focused poster on Back.
- [ ] Cover history/resume and subtitle behavior across the new flow.

Gate: exact-episode, cancellation, partial-failure, player-return, history, and
subtitle tests pass; user acceptance covers a long series and multiple providers.

### Phase 5 - Harden operation states and complete acceptance

Addresses R8-R10 and verifies all five UI decisions.

- [ ] Ensure install, refresh, toggles, ordering, removal, metadata, catalog, and
  source operations handle expected/unexpected errors and clear loading state.
- [ ] Separate an actual empty result, no eligible provider, unsupported resource,
  and request failure. Keep retry actions tied to the failed operation.
- [ ] Handle an add-on/catalog being disabled or removed while its content is
  selected, with a valid next selection and useful explanation.
- [ ] Retain URL/token sanitization in UI errors, logs, and stored history.
- [ ] Run relevant state, resolver, repository, playback, and migration checks;
  build and install `com.streamvault.app.debug` on `emulator-5558` with app data
  retained for the user's manual acceptance.
- [ ] Record build/test evidence separately from user UI and real-playback
  acceptance. Close acceptance items only when supported by that evidence.

Gate: required code checks pass and user-owned acceptance results are recorded.

## Verification baseline and acceptance matrix

The review ran 107 existing tests with no failures: 30 Stremio feature/state,
17 Stremio domain, 48 Stremio data, and 12 targeted playback tests. These passing
tests do not establish visual quality or cover the newly identified handoff and
historical-schema integration failures.

| Area | Required evidence after implementation |
| --- | --- |
| Management layout | User inspects compact installed rows, details, install error/retry, refresh, reorder, disable, and removal confirmation. |
| Browse layout | User inspects complete posters/titles, long catalogs, filters, missing artwork, and Continue Watching on the target viewport. |
| Search interaction | Focus causes no keyboard; click/OK opens editing; Back preserves text and control focus; Search/Done closes editing and reaches results. |
| Catalog requests | Tests cover selected-only loading, required/unsupported extras, stale-response cancellation, page retry retaining previous items, and explicit refresh/cache behavior. |
| Sources and series | Tests cover exact episode IDs, long episode lists, progressive providers, partial failures, retry, and rapid episode changes; user verifies the source-picker flow. |
| Navigation | User verifies player-to-title-to-browse Back behavior and retained selection, filters, scroll, and focus; automated state/navigation checks cover the same contracts where practical. |
| Playback and subtitles | Regression tests cover HTTP headers, plugin failure/success handoff, resume identity, inline/add-on subtitles, and supported casting behavior. Real stream/torrent acceptance remains with the user. |
| Database upgrade | Migration tests use populated supported historical variants and verify final schema, episode uniqueness, Xtream columns, preserved data, and foreign keys. |
| Visibility | Tests retain independent Hide Stremio Add-ons behavior and prevent content access through hidden Stremio routes. |

If a live-TV regression is investigated while shared player code is changed,
follow `AGENTS.md`: sustained two-second screenshot cadence, frame hashes,
healthy media-session state, sanitized log evidence, and multiple channels when
the issue affects live TV generally. A build, install, or single screenshot is
not evidence that a live-playback regression is resolved.

## Existing behavior to preserve

- Theme palettes, colors, and the shared browse visual system.
- Exact episode IDs, resource capability/prefix routing, and catalog provenance.
- Cancellation and protection from stale catalog/episode responses.
- Successful providers remaining usable alongside failed providers.
- Configured install identities and URL-free playback history.
- Resume behavior and inline/add-on subtitle support.
- Scoped direct-media headers and configured-URL sanitization.
- Separate Stremio visibility preference and the user's ownership of acceptance.

## Related project records

- `docs/STREMIO_ADDONS_IMPLEMENTATION_PLAN.md`
- `docs/STREMIO_ADDONS_PLAN_REVIEW.md`
- `docs/STREMIO_ADDONS_HANDOFF.md`
- `docs/STREMIO_ADDONS_ACCEPTANCE_CHECKLIST.md`
- `docs/STREMIO_TORRENT_P6_ACCEPTANCE.md`
- `docs/UI_VISUAL_SYSTEM.md`
- `docs/DATABASE_MIGRATION_INVARIANTS.md`
- `AGENTS.md`
