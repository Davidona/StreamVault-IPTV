# Stremio and Extensions UX and functionality improvement plan

- Date: 2026-10-16.
- Status: Implementation checks are complete, including plugin Messenger request/reply coverage. Debug APK is installed on the requested emulator; configured-emulator UX and real-torrent acceptance remain pending.
- Reviewed baseline: `f1ac7d3d` on `feature/stremio-addons`.
- Worktree: `D:\antigravity-scratch\iptv-player\.worktrees\stremio-addons`.

## Purpose and scope

Bring Stremio browsing and both Extensions management sections up to the app's
current layout and behavior standard. Preserve the existing theme and colors,
correct the integration defects found after merging develop and modern visuals,
and make navigation with a TV remote predictable.

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
| R11 | P1 UX | The Plugins page also spends a fixed header area on the Extensions tabs, a title/description, and an install panel before the installed-plugin list. The install panel remains visible after setup, while the list is confined to the remaining viewport. | `feature/system/.../presentation/plugins/PluginsScreen.kt`; `PluginsContent` and `PluginInstallPanel`. |
| R12 | P2 UX | Plugin rows repeat enabled state as both a status pill and switch, expose package/version details in the main row, and configuration keeps the Plugins/Stremio tabs above a full-page form. | `PluginsScreen.kt`; `PluginCard`, `PluginConfigurationPanel`, and `ExtensionsSectionTabs`. |

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

### Extensions navigation and Plugins

- Keep Extensions as a full-screen app destination in the existing app shell.
  The current Plugins and Stremio routes already work this way; selecting
  Extensions should continue to open a normal destination with the app's
  navigation shell, not a drop-down attached to the top navigation bar.
- Keep a compact, clearly selected **Plugins | Stremio-compatible Add-ons**
  section switcher near the page title. Both are substantial destinations, and
  visible tabs make the alternate section discoverable and easy to reach with a
  TV remote. Avoid adding a second large navigation strip.
- When plugins are installed, replace the introductory title/description and
  permanent install panel with a compact **Plugins · N installed** header,
  **Add plugin** action, and refresh action. **Add plugin** offers install from
  URL or APK. Keep explanatory copy and both install choices in the empty state.
- Make the installed-plugin list the main vertical scroller. Use compact rows
  with plugin name, capability badges, one enabled switch, and Configure when
  supported. Move package name, version, and less-used diagnostics into plugin
  details/configuration so they do not dominate the list.
- Treat configuration as a nested full-screen page within Extensions. Replace
  the section tabs with a clear Back-to-Plugins header, plugin name, and Save /
  Refresh actions while the form is open. Keep the form itself as one bounded
  vertical scroller and preserve draft values and focus on recoverable errors.
- Preserve visible focus and selected-tab styling, a useful empty state, local
  APK picking, URL installation, refresh, enable/disable, and manifest-declared
  capability badges. Do not conflate the Android companion-plugin install path
  with Stremio manifest URL installation.

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

- [x] Repair magnet handoff without applying the direct-HTTP header requirement
  to the original magnet URI; preserve meaningful plugin failures.
- [x] Audit supported upgrade origins against the historical Stremio and develop
  schemas, including the episode index and four Xtream receipt columns.
- [x] Add an appropriate forward compatibility migration for supported legacy
  variants and the already-installed current schema. Follow the database
  invariants, export the final schema, and preserve providers, add-on settings,
  recordings, and playback history.
- [x] Cover direct HTTP header scoping, invalid headers, unavailable torrent
  capability, explicit magnet plugin routing, safe/unsafe loopback output, and
  populated supported upgrade origins.
- [x] Exercise the Android Messenger bind/request/reply/unbind path for both a
  handler-reported failure and successful magnet preparation response.

Gate: targeted playback tests pass, supported database upgrades validate the
final Room schema and foreign keys, and a current debug APK builds.

### Phase 2 - Split navigation and compact management

Addresses UI decisions 1-3 and 5; R3, R6, R9, R11, and R12.

- [x] Separate management and dedicated browse routes with explicit Back targets.
- [x] Replace the installed-state hero with the compact header and add-on rows.
- [x] Apply the same compact installed-state hierarchy to Plugins: put URL/APK
  installation behind Add plugin, retain refresh, and make its list the primary
  vertical content area.
- [x] Keep Extensions as a full-screen shell destination with compact section
  tabs; make plugin configuration a nested page with a Back-to-Plugins header
  and no duplicate section tabs.
- [x] Add capability-aware details, catalog settings, ordering, and removal
  confirmation. Make repeated actions safe.
- [x] Implement explicit remote-focus restoration after a row is reordered or
  removed, with a fallback target if the operation fails.
- [ ] Verify reorder and removal focus on a configured TV emulator. The current
  `emulator-5558` install opens first-run provider setup and has no add-ons to
  exercise those actions.
- [x] Add manifest refresh preserving install identity, enabled state, catalog
  choices, ordering, and history. Keep the saved manifest if refresh fails.
  Invalidate affected browse data after successful refresh.
- [x] Preserve the larger empty-install introduction and compact install dialog.

Gate: management state/action tests pass; the user can inspect the refreshed
management screen and its empty, disabled, and streams-only states.

### Phase 3 - Build full browsing and explicit search activation

Addresses UI decisions 2, 4, and 5; R3-R4 and the catalog portion of R7-R8.

- [x] Use the established browsing grid/components with a compact toolbar.
- [x] Implement capability-aware catalog selection, search, and filter pickers.
- [x] Use shared click/OK-to-edit search and retain the draft/query on exit.
- [x] Fetch the selected catalog on demand and cancel obsolete work.
- [x] Preserve results during replacement requests and show distinct loading,
  empty, required-input, unsupported, and failed states.
- [x] Retry a failed page at its original offset while retaining prior pages.
  Distinguish retry from an explicit refresh; refresh must bypass stale cache.
- [x] Restore grid position and focus after returning from details.

Gate: request/state regressions pass; user acceptance confirms complete poster
cards and titles are visible on entry and D-pad focus never opens the keyboard.

### Phase 4 - Fix episode/source flow and contextual Back navigation

Addresses R5-R7 and the playback-facing portion of R1.

- [x] Add season/episode selection and an immediate source picker.
- [x] Publish provider outcomes progressively with stable attribution/order.
- [x] Preserve successful sources on partial failure and retry only the failed
  provider. Cancel stale episode lookups.
- [x] Add torrent capability feedback and clear unsupported-source behavior.
  Check enabled magnet-capable plugins before enabling Play, and offer Plugins
  when none is available.
- [x] Return from the player to the selected title/episode context, then restore
  the browse catalog, query, filters, position, and focused poster on Back.
- [x] Cover exact history replay/resume identity, scoped playback handoff,
  inline subtitle handoff, and inline/add-on subtitle picker behavior.

Gate: exact-episode, cancellation, partial-failure, player-return, history, and
subtitle tests pass; user acceptance covers a long series and multiple providers.

### Phase 5 - Harden operation states and complete acceptance

Addresses R8-R10 and verifies all five UI decisions.

- [x] Ensure install, refresh, toggles, ordering, removal, metadata, catalog, and
  source operations handle expected/unexpected errors and clear loading state.
- [x] Separate an actual empty result, no eligible provider, unsupported resource,
  and request failure. Keep retry actions tied to the failed operation.
- [x] When the selected add-on/catalog is disabled or removed, select another
  available catalog and show a useful explanation; cover disable/removal cases
  in browse state tests.
- [x] Classify and back up the Hide Stremio Add-ons preference as a portable
  global setting. The full data suite caught the missing classification and
  import/export mapping; a restore regression now covers it.
- [x] Retain URL/token sanitization in UI errors, logs, and stored history.
- [x] Obtain clean runs of the relevant Stremio, playback, app, and data unit
  suites; build and install the debug APK on the requested emulator.
- [x] Record implementation verification separately from user UI and
  real-playback acceptance; those acceptance items remain open until inspected
  on a configured emulator and a real stream is exercised.

Gate: required code checks pass and user-owned acceptance results are recorded.

## Verification baseline and acceptance matrix

The review baseline ran 107 existing tests with no failures: 30 Stremio
feature/state, 17 Stremio domain, 48 Stremio data, and 12 targeted playback
tests. Implementation verification passed `:feature:stremio:testDebugUnitTest`,
`:feature:playback:testDebugUnitTest`, `:app:testDebugUnitTest`, and the full
`:data:testDebugUnitTest` suite. The first full data run exposed an
unclassified `hide_stremio_addons` preference; the registry and backup
import/export mappings were fixed, and the full suite then passed. The debug APK
`1.0.19-debug` built and installed on `emulator-5558`. That emulator is currently
at first-run provider setup, so add-on management focus could not be exercised
there. `PluginMessengerClientTest` now exercises the Messenger round trip for a
reported handler failure and a successful magnet preparation reply, including
request metadata and service unbinding. These checks do not establish visual
quality or real torrent playback.

| Area | Required evidence after implementation |
| --- | --- |
| Management layout | User inspects compact Stremio add-on and companion-plugin rows, empty and installed states, install error/retry, refresh, configuration, reorder, disable, and removal confirmation. |
| Extensions navigation | User confirms Extensions opens as a full-screen app destination, the compact section switcher is easy to reach with a remote, and plugin configuration has a clear nested Back path. |
| Browse layout | User inspects complete posters/titles, long catalogs, filters, missing artwork, and Continue Watching on the target viewport. |
| Search interaction | Focus causes no keyboard; click/OK opens editing; Back preserves text and control focus; Search/Done closes editing and reaches results. |
| Catalog requests | Tests cover selected-only loading, required/unsupported extras, stale-response cancellation, page retry retaining previous items, and explicit refresh/cache behavior. |
| Sources and series | Tests cover exact episode IDs, long episode lists, progressive providers, partial failures, retry, and rapid episode changes; user verifies the source-picker flow. |
| Navigation | User verifies player-to-title-to-browse Back behavior and retained selection, filters, scroll, and focus; automated state/navigation checks cover the same contracts where practical. |
| Playback and subtitles | Tests cover direct HTTP headers, invalid headers, magnet capability/routing/output policy, Messenger handler failure/success replies, handoff headers, resume identity, and inline/add-on subtitles. Real stream/torrent acceptance remains open. |
| Database upgrade | Migration tests use populated supported historical variants and verify final schema, episode uniqueness, Xtream columns, preserved data, and foreign keys. |
| Visibility | Tests retain independent Hide Stremio Add-ons behavior and prevent content access through hidden Stremio routes. |

If a live-TV regression is investigated while shared player code is changed,
follow `AGENTS.md`: sustained two-second screenshot cadence, frame hashes,
healthy media-session state, sanitized log evidence, and multiple channels when
the issue affects live TV generally. A build, install, or single screenshot is
not evidence that a live-playback regression is resolved.

## Existing behavior to preserve

- Theme palettes, colors, and the shared browse visual system.
- Full-screen Extensions destination in the app shell and separate companion APK versus Stremio manifest install flows.
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
