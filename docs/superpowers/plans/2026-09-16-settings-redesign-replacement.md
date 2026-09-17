# Settings replacement: design and implementation plan

> **Status: replacement implementation in progress; not yet accepted.** The user rejected the previous redesign. Its layouts, components, screenshots, checked boxes, and test results are not an approved design baseline.
>
> **Execution:** Implement in the current checkout using the executing-plans workflow. No worktrees: do not create, open, enumerate, or inspect another checkout. Preserve unrelated local changes. ADB is allowed for bounded baseline investigation during planning. Once development starts, do not invoke ADB, launch/install the app, or run connected/device tests until every implementation checklist item in section 8 is complete. Final device verification is a separate, consolidated stage. Do not relabel development/debugging as planning to bypass this rule.

**Goal:** Replace the entire Settings experience with one that is good enough to be accepted by Google's top UI reviewer, while preserving all existing functionality.

That is the required quality bar, not a claim of endorsement by Google. Section 9 makes the bar reviewable. A broken focus path, unreadable text, inconsistent layout, or lost function is an automatic rejection regardless of test counts.

**User clarification:** The ambition is a market-leading Settings experience: minimal, modern, theme-driven, and coherent from category menus through the deepest setting or dialog. Technical correctness alone does not satisfy this brief.

**Architecture:** One settings shell, one semantic visual system, one navigation/focus coordinator, and independently rendered pages. Preserve domain preferences and operational callbacks; replace the rejected presentation architecture where it prevents coherent behavior. Settings subflows, search, parental controls, provider management, recordings, backup, and support must share the same interaction rules.

**Tech stack:** Kotlin, Jetpack Compose, existing TV Material and Material 3 dependencies, existing feature navigation and preference/domain boundaries. No new UI framework.

**Spec:** Sections 1–7 of this document. Implementation: section 8. Acceptance: sections 9–10.

### Planning-only device investigation

ADB may be used before development to identify the installed build, inspect existing layout/focus behavior, and resolve gaps that supplied screenshots and source inspection cannot answer. Keep this investigation bounded to a named planning question and record its findings. The supplied screenshots already establish the visual defects; reproducing them is not a prerequisite to accepting them. Once implementation begins, use source review, host tests, and previews until the final verification gate opens. Planning evidence never counts as acceptance of the replacement.

## 1. What failed and what the replacement must solve

| Evidence | Failure | Required correction |
|---|---|---|
| User screenshots: black search label on dark canvas | Search is not readable in its resting state | Explicit semantic foreground/background pairs for every input state |
| User screenshots: dark entered query | The primary interaction becomes unreadable while being used | Specify entered text, placeholder, floating label if present, cursor, selection, icons, and error colours |
| User screenshots: large blank area, oversized rows, inconsistent gaps | Weak hierarchy and poor use of screen space | A measured layout and typography system; inspect compositions rather than individual components alone |
| User report: entering pages such as parental controls sends focus to Home | Navigation does not preserve the user's context | One owner of entry and return focus; test the complete app route, including asynchronous loading |
| User screenshots: Live TV overview and Browsing layout use unrelated surface colours | Menu and detail pages feel like different interfaces | One theme palette and shared surface roles across both levels |
| User screenshots: Browsing layout title and description repeated inside its container | Duplicated hierarchy consumes space without adding information | One page title; group headings only for distinct groups |
| User request: General playback needs a visible return to Playback | Remote Back alone does not make the return path discoverable | Persistent on-screen Back button on every nested page, returning to the parent and restoring its opener |
| Previous redesign: large generic cards, repeated subtitles, extra overview hops | Content was reorganized without resolving the interaction model | Compact grouped settings lists; add a subpage only when it reduces complexity |
| Previous validation: unit/build success presented as completion | Evidence did not cover the real experience | Separate implementation correctness from visual and interaction acceptance |

### Source findings and uncertainty

- `core/ui/.../theme/Theme.kt` currently provides `androidx.tv.material3.MaterialTheme`. Settings also uses `androidx.compose.material3` controls. Those are distinct theme providers. Standard Material input defaults must not be assumed to inherit the TV palette.
- `SettingsScreen.kt`, `SettingsContentPane.kt`, and `ParentalControlGroupScreen.kt` independently request focus; the first two use delayed requests. The app shell also defines focus-entry behavior. This needs coordinated ownership, not another delay.
- `SettingsPageComponents.kt` gives navigation cards a 100dp minimum height; headers, per-page descriptions, card padding, and shell padding accumulate. Reducing one margin is insufficient.
- The exact `Search settings` field shown in the supplied screenshots is not present in the current checkout's production Settings sources found during this audit. The screenshots remain valid defect evidence. Before implementation, identify the renderer/build that supplied that field using files and build metadata in this checkout, supplemented by bounded planning-only ADB inspection if needed. Do not invent a filename, blame the user, or inspect another worktree.
- The precise Home-focus failure is not yet proven. Trace the complete production entry/return path and theme propagation. These findings are investigation targets, not claims that every root cause is already established.

## 2. Design decision

Three approaches were considered:

1. Adjust the current cards and colours: rejected because it retains the failed hierarchy and competing focus requests.
2. Add more cards, categories, and a search field on every page: rejected because it adds repetition and navigation work.
3. **Replace Settings with a self-contained, list-based workspace:** selected. It supports predictable remote navigation, compact information, explicit page context, and shared components across all workflows.

### TV structure

- Enter Settings from the existing app navigation. Inside Settings, render a dedicated workspace with a stable category rail and one content pane.
- Replace the global Home/Live TV/Movies navigation bar within Settings with a local header and explicit exit/back action. Other app destinations remain reachable by exiting Settings. Child Settings routes must not create another global navigation bar.
- Back from the Settings root returns to the actual app destination that opened it, not a hard-coded Home destination. If entry was external, use the existing app navigation fallback.
- Keep the rail and page header outside the page's scrolling body. Only their own overflowing content scrolls. Page identity and section selection must remain visible.
- Display subpages as compact navigation rows with short titles and useful summaries. Do not recreate a grid of oversized cards.
- Put normal preferences in grouped lists. Use cards only for distinct objects or state, such as one provider, a backup snapshot, or an active recording.
- Place advanced compatibility separately from everyday choices. No catch-all Browsing page.

### Compact structure

- Use a Settings root list and full-width detail pages, with a local Back control. Do not compress a TV rail into a narrow screen or create a second horizontally scrolling category bar.
- Derive layout once from the shell's available constraints and input context. Pass the same layout mode to all children.
- Adapt values and supporting text to vertical stacking when needed. Text enlargement may increase row height; fixed heights must never clip text.

### Page header and search

- Every nested page has a persistent, visible Back button in the leading position of the local header, with a directional icon and a localized parent label where space permits (for example, “Playback”). Its accessible label identifies the destination. On narrow layouts, retain the Back icon and accessible parent label. This is a real selectable button, not a decorative breadcrumb or another category tab.
- Example: **Playback → General playback → Back to Playback**. Both the on-screen button and remote/system Back return to Playback, restore its scroll position, and focus the General playback row. They must not exit Settings or select Home. Ordinary nested pages return one parent level; search and dialog return paths retain their explicit origin.
- Back remains visible while the page body scrolls. Opening a page focuses its first enabled setting row, not Back. From that first row, Up reaches the header controls; Down from Back returns to content. Touch and remote users have the same return destination.
- One title per page. Use supporting copy only when it explains a choice or consequence; no generic promotional sentence on every page.
- Search is one persistent action in the Settings header, opening a dedicated Settings search surface. It is not a large outlined field repeated above every list.
- Search scope is all available Settings, labelled clearly. A result includes the setting name, owning section, and current value where useful.
- Activating a result opens the owning page, scrolls to the setting, and focuses it. Dismissing search returns to its opener with the original page position intact.
- Show initial suggestions, no-results, and clear-query states. Do not display a blank panel with no explanation.
- On TV, focusing Search does not open the keyboard. Select enters text editing; Back first leaves text editing/hides the keyboard, then dismisses search. Touch activates editing on the first tap.

## 3. Layout, typography, and component contract

These are project design tokens and initial composition constraints, not claims that Google mandates these exact dimensions. Refine them together in Compose previews before implementation sign-off; do not improvise per-screen values.

| Element | TV baseline | Compact baseline |
|---|---|---|
| Spacing scale | 4, 8, 12, 16, 24, 32dp | Same scale |
| Outer settings inset | 32dp horizontal, 24dp vertical | 16dp |
| Category rail | 208dp; 24dp content gap | Root category list, no rail |
| Content width | Fill remaining space, cap at 720dp | Fill available width |
| Local header | 56dp minimum; 16dp gap to body | 56dp minimum; 12dp gap |
| Page title | 28sp / 34sp, medium | 24sp / 30sp, medium |
| Primary row text | 18sp / 24sp | 16sp / 24sp |
| Supporting text / value | 14sp / 20sp | 14sp / 20sp |
| Simple row | 56dp minimum | 56dp minimum |
| Row with supporting text | 72dp minimum; expand for wrapping | 72dp minimum; expand for wrapping |
| Row inset | 16dp horizontal; 8–12dp vertical | 16dp horizontal; 8–12dp vertical |
| Search input when open | 56dp minimum | 56dp minimum |
| Icons and chevrons | 20–24dp | 20–24dp |
| Group radius / separators | 12dp group radius; subtle 1dp inset separators | Same |

- At the reference 960 × 540dp TV composition, show at least six simple rows or four explanatory rows below the header without scrolling. Validate with actual supported text sizes; do not meet this by shrinking labels.
- The first meaningful control begins no more than 16dp after the header block. No unexplained blank band before a page title or between search and results.
- The page title belongs inside the local 56dp-minimum header, with no second title band beneath it. Align the rail and content to the same workspace top. Apply the outer top inset once; do not add reserved global-navbar space, a hero spacer, or repeated scaffold padding. The replacement local header removes the large navbar-to-title gap shown in the user's screenshot.
- The visual direction is minimal and modern: restrained surfaces, clear typography, compact grouped rows, and only useful supporting text. Do not repeat the page title/description inside its first group or surround every group with another large decorative card. Whitespace must distinguish hierarchy without pushing ordinary controls off screen.
- All labels share a leading alignment line; values, switches, and chevrons share a trailing alignment line.
- Short values sit at the trailing edge on wide layouts. Long values and explanations wrap below their label; they never collide with controls.
- Distinguish choice/navigation, toggle, destructive action, progress, and read-only status. A read-only status must not look clickable. A toggle is one activation target, not separately focusable row and switch.
- State must not rely only on colour. Selection and focus are visually distinct. Focus uses a consistent, high-contrast outline and paired surface/text colours; it must not change layout size or be cropped by list edges.
- Use semantic tokens for canvas, grouped surface, primary/secondary text, outline, accent, focused surface/content, disabled state, error, cursor, and selection. No default black/white foreground assumptions.
- Resolve every colour from the active theme. Overview navigation rows and detail preference rows use the same base surface/content roles at the same hierarchy and interaction state. In particular, Live TV → Browsing layout must not change from a grey menu to an unrelated blue panel. Rail selection, keyboard focus, and errors may use distinct shared semantic roles because they communicate different states; they must not introduce per-page palettes.
- Theme both Material families from the same palette, or explicitly theme every mixed-family control through a shared adapter. Validate theme switching without restarting the app.
- Theme switching updates the rail, overview, detail page, Back button, search, values, switches, and dialogs together. No fixed dark fill in Light theme, stale menu colour, or platform-default control tint is acceptable.
- Search must remain legible when empty, focused, typed, selected, disabled, loading, and in error. Include the keyboard-open composition.

## 4. Information architecture and preservation

The inventory is the source of truth for preservation. Categories below are the proposed organization; retain a subpage only if it has a coherent task or would otherwise produce a long list. Common controls should not require an empty intermediate page.

| Section | Responsibilities |
|---|---|
| Sources & guide | Providers, combined sources, sync, guide sources/assignments/policies/time shift; advanced source text parsing |
| Playback | General behavior; audio and lip sync; subtitle appearance and translation; quality/network; controls and timers; advanced decoder/display compatibility |
| Live TV | Channel layout, visible categories and filters, grouping/numbering/variants, live playback/timeshift, clock, multiview |
| Movies & series | Library layout/loading/search, duplicate handling and variants, category sorting, episode playback and connection compatibility |
| Appearance & remote | Theme/language/time format, landing screen/navigation/home shelves, all remote shortcut profiles |
| Privacy & parental | PIN and protection level, protected/hidden categories, incognito viewing, history removal |
| Recordings | Status/schedule/files, storage choices, file naming/retention/concurrency/padding/network restriction |
| Backup & restore | Local and device-specific storage, share/import preview, Drive/account/snapshots/conflict handling |
| About & support | Updates and progress, diagnostics/crash reports, build/project information, close-app action |

Inventory fields:

```text
stableSettingId | oldLocation | newLocation | labelResource | persistedKey
readBinding | writeOrActionCallback | availability | dependency
confirmation | searchAliases | returnFocusTarget | verificationCase
```

Account for conditional and operational states, not only static labels: no provider, provider busy/error, PIN missing/present/incorrect, no recordings/active recordings, storage unavailable, USB absent, Drive disconnected, empty backups, update checking/downloading/install-permission-required, and failed operations.

No preference-key or backup-schema migration is permitted solely for the redesign. Preserve ongoing operation state across page changes. Destructive actions retain or gain a clear confirmation with consequences. Never reset settings as part of migration.

## 5. Navigation and focus contract

### Ownership

One `SettingsFocusCoordinator` owns requested transitions within the Settings workspace. Pages register stable targets; they do not independently focus on composition. Keep keyboard-editing focus local to the field while it owns editing.

Suggested interfaces:

```kotlin
data class SettingsLocation(val sectionId: String, val pageId: String, val itemId: String?)
data class SettingsReturnPoint(val location: SettingsLocation, val focusedItemId: String?)
data class SettingsFocusIntent(val targetId: String, val transitionId: Long)

// UI state stores stable IDs and per-page list positions, never FocusRequester instances.
// The coordinator applies the current intent only after its target is attached and laid out.
// If another transition occurs first, the stale intent is discarded.
```

No `delay(80)`, `delay(100)`, or retries that compete to win focus. Scroll the target into composition, wait for its attachment/layout, then apply the current focus intent once. Loading completion must not override a focus move the user already made.

### Required transitions

| Event | Focus destination | State retained |
|---|---|---|
| Enter Settings | Current section's first useful control, or restored last control on return | Section and page context |
| Activate another section | Its first useful control; selection stays visually marked | Previous section position |
| Open detail page | First enabled editable/action row | Opener ID and overview scroll |
| Activate visible Back on General playback | General playback row in Playback | Same result as remote/system Back; Playback scroll restored |
| Up from first content row / Down from header Back | Local header controls / first content row | No transfer to global Home navigation |
| Open parental controls | Relevant parental control or first content-type control after loading | Parent route/provider and opener |
| Open a choice/PIN/confirmation dialog | Selected choice or first required input/action | Exact opener |
| Dismiss or complete dialog | Exact opener, including after its value changes | Page position |
| Enable a parent preference | The same switch | Newly visible child settings below it |
| Focused item becomes unavailable | Nearest valid item in the same page | Never fall back to app Home |
| Activate search result | Exact setting on its owning page | Query/results position and return point |
| Back from detail | Exact opener in previous page | Scroll offset restored before focus |
| Leave content toward category rail | Selected section | Current content target for re-entry |
| Back from root | Previous app destination and its opener where supported | App navigation state |
| Restore configuration/process state | Saved valid Settings location; nearest valid item if removed | No Home jump |

Directional rules must respect RTL. Within a list, Up/Down move between controls; at boundaries, an explicit destination is used. In a text field, Left/Right edit text only while editing is active. Dialog focus is contained. Global app navigation must not steal focus during any of these transitions.

## 6. File ownership and scope

All paths below are inside this checkout.

| Files | Responsibility |
|---|---|
| `core/ui/.../theme/Theme.kt`, `design/AppPalette.kt` | Coherent TV/standard Material theme mapping; verify shared consumers |
| `core/ui/.../components/SearchInput.kt` | Audit shared input ownership and existing callers; avoid changing unrelated search behavior accidentally |
| `core/ui/.../components/shell/AppShellVisuals.kt` | Audit shell focus entry and local/global navigation separation |
| `app/.../navigation/AppNavHost.kt`, `feature/settings/.../navigation/SettingsGraph.kt` | Preserve app entry/back and parental subroute ownership |
| Existing `SettingsScreen.kt`, `SettingsContentPane.kt`, `SettingsAdaptiveLayout.kt`, `SettingsNavigationRail.kt` | Replace shell composition and competing focus logic |
| Existing `SettingsPageComponents.kt`, `SettingsRowComponents.kt`, selection/value/input dialog components | Replace rejected visual language and inconsistent control states |
| New `SettingsDesignTokens.kt`, `SettingsShell.kt`, `SettingsNavigationState.kt`, `SettingsFocusCoordinator.kt` under settings presentation | Explicit design tokens, workspace, saved locations/return points, and focus transitions |
| New `SettingsCatalog.kt`, `SettingsSearchSurface.kt` under settings presentation | Inventory-backed search and result navigation |
| `SettingsPlaybackSection.kt`, `SettingsBrowsingSection.kt`, remaining section renderers | Migrate to focused page renderers; remove obsolete catch-all composition when parity is proven |
| `parental/ParentalControlGroupScreen.kt` and its controls | Complete parental workflow inside the same shell/focus system |
| `feature/settings/src/main/res/values*/` | Concise labels, dependency explanations, and all shipped translations |
| Settings/core UI unit tests; Settings and app connected tests | Palette, navigation, component, and complete-route regressions |

`...` in this table denotes the existing `src/main/java/com/streamvault/<module>/` package root, not an unknown file to discover in another checkout. New presentation files go in `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/`.

## 7. Design review before device work

Create Compose preview fixtures from the production components. They must include the actual shell and theme providers, not isolated idealized controls or HTML that differs from implementation.

Required preview sheets: Settings root, Playback, Privacy & parental, parental category management, search empty/typed/results/no-results, one preference detail page, PIN dialog, source error/busy state, backup import/conflict state, recording defaults, and update progress.

Include paired overview/detail compositions for Live TV → Browsing layout and Playback → General playback in each theme. Compare surface roles, typography, top spacing, visible Back, and first-row focus side by side. Review the full shell so cropped content images cannot hide wasted space above the title.

Render or inspect them without ADB using available host-side Compose preview/render tooling. If that capability is unavailable, record the gap and inspect the preview definitions and measured tokens; do not pretend the previews were rendered or use a device early. The final device stage must resolve any remaining visual evidence gap.

**Host preview evidence (2026-09-16):** production-component previews now compile in `SettingsPreviews.kt` for Classic blue, M3 purple, Light, Playback detail, Privacy and parental controls, parental category management, Search empty/results/no-results, PIN error, provider busy/error, backup conflicts, recording defaults, and update progress. `:feature:settings:tasks --all` exposes no Compose preview or screenshot-render task (only RenderScript packaging), so no host-rendered image is claimed. The final consolidated device pass must close this visual evidence gap after sections A–F are complete.

Review whole compositions for hierarchy, rhythm, density, alignment, contrast, state clarity, and task flow. No prior screenshot is a golden to copy merely because it exists. Resolve review defects before advancing the implementation checklist.

## 8. Implementation checklist — ADB forbidden throughout

Check an item only when its artifact or non-device evidence exists. Completing this checklist means ready for final verification, not design accepted.

### A. Establish the real baseline and preservation inventory

- [x] Identify the production Settings entry points and reconcile the supplied search screenshots with this checkout's sources/build metadata. Record what is known and any unproven cause.
- [x] Produce `docs/superpowers/specs/2026-09-16-settings-control-inventory.md` with every field in section 4; include conditional controls, device-specific actions, dialogs, and exit/back behavior.
- [x] Trace complete navigation and focus ownership from app entry through parental controls, loading, dialogs, search, and return. Record competing requests and their removal plan.
- [x] Mark prior design/validation records as rejected or historical; do not reuse their checked boxes as evidence.

### B. Build the visual foundation

- [x] Implement `SettingsDesignTokens.kt` and shared semantic state colours. Remove duplicated per-screen dimensions and inherited input defaults.
- [x] Bridge TV Material and standard Material themes from the same palette. Cover Classic blue, M3 purple, and Light, including theme changes in place.
- [x] Apply identical semantic surface roles to overview/detail rows and all nested workflows; review Live TV/Browsing layout pairs in every theme. Remove per-page colours, duplicated headings, and stacked top insets.
- [x] Implement list group, choice row, toggle row, action row, status row, header, text field, and dialog primitives with the section 3 contracts.
- [x] Add palette contrast tests and component preview fixtures for all text/control states. Verify placeholder, entered text, cursor, selection, error, disabled explanation, and focus explicitly.

### C. Replace navigation and focus ownership

- [x] Implement typed Settings locations, return points, and per-page scroll state. Use stable IDs; retain compatibility for backup-import and provider/parental entry points.
- [x] Build the self-contained Settings shell and compact root/detail layout, including a real return route to the app.
- [x] Add persistent on-screen Back to every nested page. Verify Playback → General playback → Back to Playback restores the General playback row, with identical remote/system Back behavior and a reachable header from the first row.
- [x] Implement the single focus coordinator with attachment-aware transitions and stale-request cancellation. Remove competing delayed entry requests.
- [x] Integrate parental category management into this shell. Loading, PIN validation, filter changes, and save/reset actions must follow the same focus policy.
- [x] Add non-device tests for navigation transitions, return points, removed targets, and restoration. Write complete app-route connected regression cases now; do not execute them yet.

### D. Migrate the entire Settings surface

- [x] Migrate Playback and Live TV first; prove each control in their inventory still uses the original persistence/action path.
- [x] Migrate Movies & series and Appearance & remote, including all remote profiles and conditional grouping/variant behavior.
- [x] Migrate Sources & guide, including provider editing, combined sources, sync/cancel/error states, guide assignment/policies, and parser compatibility.
- [x] Migrate Privacy & parental end to end, including protected/hidden categories, PIN states, incognito, and destructive-history confirmation.
- [x] Migrate Recordings and Backup & restore, including progress, storage permissions/unavailability, USB conditions, Drive states, preview/conflict flows, and confirmations.
- [x] Migrate About & support, including update download/install state, diagnostics, project links, and close app.
- [x] Remove rejected navigation cards/section layout paths once their replacements cover the inventory. Do not retain old UI as hidden fallbacks.

### E. Implement search and adaptive behavior

- [x] Build one catalog-backed search surface with label, alias, and owning-section matching. Search selects existing controls; it does not duplicate preference logic.
- [x] Implement target navigation, scrolling, focus, query/result restoration, clear query, and no-results behavior. Exclude unavailable actions; explain disabled prerequisites.
- [x] Implement TV select-to-edit, keyboard exit, mouse/touch activation, and field focus ownership. Add regression cases for the black-on-dark placeholder and typed text.
- [x] Complete compact root/detail navigation, narrow input handling, RTL directions, long translations, and text scaling without truncating essential labels/values.
- [x] Translate new navigation and help copy into shipped locale packs; no English-only navigation gap accepted as finished.

### F. Finish review and all non-device checks

- [x] Complete and review the production Compose preview sheets in section 7. Correct excessive whitespace, oversized cards, dense text, inconsistent baseline alignment, and mismatched states.
- [x] Run `./gradlew.bat :core:ui:testDebugUnitTest :feature:settings:testDebugUnitTest :app:testDebugUnitTest` and resolve failures relevant to changed shared UI/navigation.
- [x] Run `./gradlew.bat :feature:settings:verifyFeatureSettingsBoundary :app:assembleDebug :feature:settings:assembleDebugAndroidTest :app:assembleDebugAndroidTest`. Build tests without installing or executing them.
- [x] Add connected coverage for every transition in section 5, including app → Settings → parental controls → loading/PIN → Back. Do not substitute component-only focus tests for full-route tests.
- [x] Review the completed change against every inventory entry and section 9 criterion; record code/host-test/preview evidence and any final-device-only checks separately.
- [x] Verify all implementation boxes A–F are complete, record the exact build/source identity for final verification, and prepare one scripted device verification pass. No manual discovery sessions on the emulator.

**Final verification identity (2026-09-17):** Git HEAD `952cd6ca76bbad69e77a0b4cb9b102853ca65ba3` on `feature/improveSettings`; 91 changed or untracked source files under `app`, `core`, and `feature` produce SHA-256 manifest `49880E40FF13986512C64A28905DC3692A01242C8A070388B5E2ED8F3FD006D3`. The final APKs are `app-debug.apk` (`A875BE7114DEADC80649D9FDCBF1F3CCA20EBD9EA02F76401D913B6BB380824A`), `app-debug-androidTest.apk` (`4539053FA50BD7C406DB9BE426C444C3088ABDAF3E77252C96FF5985E99FB6D0`), and `settings-debug-androidTest.apk` (`6A9D08E6C4EEF520781E38225AA5EE81E1C7D08677472BCDF997F0A168D36D2B`). The app identity is `com.streamvault.app.debug`, version `1.0.17.1-debug` (`19`).

`tools/validate-settings-redesign.ps1` is the sole prepared device pass. It requires one explicitly selected online device, records device/build/source identity, installs those exact APKs, runs the Settings behavior/row/route suites and app navigation contract suite, opens the production Settings route directly, verifies focus and Back restoration across every category and representative detail page, verifies typed search/result/return, captures PNG/XML/focus/log evidence, and fails on instrumentation or fatal-process evidence. It changes no destructive user data. Compact-device acceptance remains a separately reported final-device condition because this machine currently has only an Android TV system image; a resized TV surface must not be misreported as phone evidence.

### TV Guide polish follow-up (2026-09-17)

- [x] Give EPG source fields a visible resting outline, readable placeholder/entered text, a 56dp interaction target, and theme-derived cursor/focus colors.
- [x] Make EPG field editing dismissible from touch/mouse keyboard Back, TV Back, the IME Done action, and a Back key event without navigating away from Settings.
- [x] Replace the time-shift card's inherited text/chip colors with the shared Settings semantic roles; provider labels and disabled/reset states remain readable in every palette.
- [x] Add unit coverage for EPG field Back ownership and pass settings/core UI tests plus app and Android-test assembly.
- [ ] Re-run the single final device verification pass after all implementation changes; no development ADB session is used for this follow-up.

## 9. Acceptance contract

**Required standard: good enough to be accepted by Google's top UI reviewer.** The following are mandatory pass/fail gates, not a points system where one success compensates for a defect.

| Gate | Pass requirement | Automatic rejection examples |
|---|---|---|
| Legibility | Project target: at least 4.5:1 for all informative text, including placeholders and values, against its actual composited background; at least 3:1 for meaningful boundaries/focus indicators | Black-on-dark search text; unreadable typed/selected text; explanation rendered as barely visible disabled text |
| Hierarchy and sizing | Section 3 tokens used consistently; titles, labels, values, help, and actions have distinct roles | Giant generic cards, arbitrary margins, repeated title/subtitle blocks, tiny text used to force density |
| Alignment and density | Stable leading/trailing alignment; stated TV visible-row targets met at reference size; no avoidable blank bands | A handful of ordinary preferences consume several screens through padding alone |
| Navigation | Every section/page/modal has a deterministic entry and return target; all section 5 transitions pass | Opening parental controls or any other Settings page focuses Home |
| Visible return path | Every nested page retains a visible Back button; Playback → General playback → Back restores Playback and its General playback row | Remote-only return; missing/scrolled-away Back; button exits Settings instead of returning to the parent |
| Focus feedback | One visible focus owner, clear distinction from selection, no clipping, no jumps after loading/recomposition | Focus disappears, moves after the user starts navigating, or lands on a non-actionable status |
| Search | Empty and typed text readable in all themes; results reach and focus the actual setting; keyboard and Back behave predictably | A search field repeated across every page; focus lost after query/results change |
| Task efficiency | Common tasks take at most three activations from Settings root; no empty overview hop; groups match user intent | Users hunt through unrelated Playback/Browsing options or traverse cosmetic intermediary pages |
| Functional parity | Every inventory row has a reachable control/state, preserved callback/key, and an evidence entry | Omitted USB/Drive/remote/parental/advanced action; hidden unavailable option without explanation |
| Accessibility | 48dp minimum touch targets, one switch target, correct role/state/value semantics, sensible reading order | Label and control announce conflicting state; only colour communicates focus or failure |
| Adaptation | No clipping/overlap in actual TV, compact, enlarged-text, long-label, or RTL compositions | Narrow layout consumes all height with navigation; value obscures switch; mirrored Back points wrong way |
| Visual coherence | One reviewed component language across regular settings, provider/backup objects, progress, errors, and dialogs | Old and new row styles mixed across tabs; modal looks like an unrelated app |
| Theme continuity | All surfaces and states derive from the active theme; overview/detail pairs use matching roles and update together | Grey Live TV menu becomes an unrelated blue Browsing layout panel; stale or hard-coded control colours |
| Minimal composition | One page title within the local header; single outer inset; compact useful content follows within 16dp | Large empty navbar-to-title band, repeated Browsing layout heading/description, decorative panels that force needless scrolling |
| Honesty of completion | Evidence covers real production routes and exact final build; reported gaps are not silently called passes | Build success, callback counts, or one screenshot presented as design acceptance |

Normal text and touch-target minimums are grounded in [Android accessibility guidance](https://developer.android.com/guide/topics/ui/accessibility/apps). The all-informative-text 4.5:1 rule is this project's stricter target. TV hierarchy and readability follow [Android TV typography guidance](https://developer.android.com/design/ui/tv/guides/styles/typography); explicit focus states follow [Android TV focus guidance](https://developer.android.com/design/ui/tv/guides/styles/focus-system). Passing these measurable checks supports, but does not replace, a critical visual/task-flow review.

## 10. Final verification stage — only after section 8 is complete

This is the only ADB/device stage after development begins; bounded baseline inspection during planning is separately allowed above. It is one consolidated verification session, not one shell command. Do not mark its acceptance results before it runs.

1. Verify build/source identity, install the final build, and run the prepared Settings and app-route connected tests. Start with the exact reported failures: empty search text, typed search text, page entry, and parental controls.
   Include Playback → General playback → visible Back and remote Back, confirming the restored opener; Live TV → Browsing layout, confirming matching theme roles; and the complete shell, confirming removal of the excessive top gap. Ordinary forward navigation must focus the first enabled content row while keeping Back visible and reachable.
2. Execute a predetermined route matrix covering every section, every modal family, search result navigation/return, conditional controls, provider loading/error, recording/storage states, backup restore/Drive, and update progress. Record exact focus targets, not just visible screens.
3. Inspect representative full compositions in all three themes, 720p/1080p TV, a genuine compact-device configuration, larger text, and RTL/long labels. Do not count a letterboxed TV emulator as phone evidence.
4. Verify representative changes persist after restart and old backup data still maps to the same settings. Restore temporary test changes. Do not perform destructive operations on real user data.
5. Save a compact contact sheet and focused evidence for the search states, parental entry/return, main sections, and responsive/theme variants. Review every acceptance gate against this evidence.
6. If any gate fails, report the concrete failure and reopen the responsible implementation items. Fix them before any targeted re-verification; do not keep exploratory ADB sessions running. Never declare success because the verification stage was reached.
7. Only after the design and interaction gates pass, update public screenshots, changelog, and a new acceptance report. Previous reports remain historical and explicitly rejected as design acceptance.

No live-playback fix is included. If implementation unexpectedly requires a playback-engine change, stop that scope expansion and apply the repository's separate live-playback validation requirements; Settings screenshots cannot prove stream health.

## 11. Completion rule

Implementation boxes describe work completed. Acceptance describes the quality actually observed. They are separate records.

Do not say the redesign is complete while any contrast failure, focus regression, missing function, broken layout, localization gap, or unverified production-route behavior remains. Do not ask the user to accept an organized version of the rejected design. Deliver the replacement and its evidence against the stated review bar.
