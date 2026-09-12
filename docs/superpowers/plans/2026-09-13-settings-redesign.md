# StreamVault Settings Redesign Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current flat, difficult-to-scan Settings UI with a TV-first, accessible, visually coherent settings system in which every existing option has a logical home and can be reached predictably with a D-pad.

**Architecture:** Keep the existing preference storage, defaults, backup schema, and `SettingsViewModel` behavior intact during the UI migration. Replace integer-selected monolithic lists with stable typed destinations, a reusable master/detail shell, small domain pages, a searchable settings catalog, and one typed modal state. Migrate one domain at a time so every phase remains usable and testable.

**Tech Stack:** Kotlin, Jetpack Compose, Compose for TV Material 3, Hilt, StateFlow, Android resources, Compose UI tests, Android TV emulator screenshot tests.

**Spec:** This file; sections “Product decision” through “State, error, and destructive-action behavior” are the UX specification implemented by the task plan.

## Global Constraints

- StreamVault remains TV-first; phone and tablet layouts remain supported.
- Preserve every existing setting, stored preference key, default value, provider behavior, and backup/import field unless this plan explicitly says the UI label or location changes.
- Preserve the existing `settings?backupUri=...` entry path and route it directly to Backup & Restore review without exposing internal destination indexes.
- Do not reset or migrate user preferences merely because their screen location changes.
- All controls must support D-pad, keyboard, mouse, touch, RTL, and accessibility services.
- All new user-facing text must come from Android string resources; remove existing hard-coded Settings copy while each page is migrated.
- A setting must never change merely because it receives focus.
- Destructive actions require confirmation and must name the affected data.
- Advanced playback controls remain available but must not dominate the normal path.
- Every completed phase must compile, pass its focused tests, and leave Settings fully navigable.

---

## 1. Current-state audit

The problem is structural, not cosmetic.

- `SettingsPlaybackSection.kt` is about 700 lines and renders roughly 37 controls in one continuous list.
- `SettingsBrowsingSection.kt` is about 600 lines and mixes app navigation, Live TV browsing, guide behavior, Movies/Series behavior, sorting, theme, language, time format, and twelve remote-button assignments.
- Dividers are the main grouping device. They do not provide a name, purpose, or orientation when the user scrolls into the middle of a page.
- The left rail uses one-character pseudo-icons (`P`, `>`, `#`, `L`, and similar), which look unfinished and do not provide reliable semantic meaning.
- The screen subtitle is always the provider subtitle rather than describing the selected destination.
- `SettingsContentPane.kt` switches on integer positions, so navigation identity depends on list order.
- `SettingsScreenDialogState.kt` holds dozens of independent Boolean dialog flags. Illegal combinations are possible and focus restoration is difficult to reason about.
- The graph identifies `SettingsViewModel` and `PreferencesRepository` as god nodes with 112 and 120 connections respectively. A full persistence rewrite during a visual redesign would create unnecessary risk.
- Existing connected tests cover basic rail selection, RTL availability, several dialogs, and a few accessibility semantics, but do not prove full D-pad traversal, focus restoration, page hierarchy, large text, or that every preference remains reachable.
- Some EPG UI copy and playback labels are hard-coded English, so localization is incomplete.
- Static screenshots and the running emulator show a visually sparse but cognitively dense list: there is little page identity, weak hierarchy, long horizontal eye travel, and no indication of where the user is within a long domain.

## 2. Product decision

### Recommended: two-level master/detail Settings

Keep a persistent domain rail on TV. Selecting a domain opens a compact overview of named subpages. Selecting a subpage opens its focused detail page in the right pane. The active page title, description, and breadcrumb remain visible while its rows scroll.

Why this is the right fit:

- It preserves the fast left/right D-pad mental model already used by the app.
- It reduces every detail page to a comprehensible group instead of hiding organization in dividers.
- It makes expert playback controls available without making first-time users parse them.
- It supports direct search results and future deep links because every destination and item has a stable ID.
- It can be introduced domain by domain without changing preference persistence.

### Rejected alternatives

1. **Keep the flat pages and add section headers.** Lowest implementation cost, but Playback would still be a very long page and Browsing would still combine unrelated concepts.
2. **Replace everything with a tile-only Settings home.** Attractive at first glance, but repetitive Back navigation and loss of a persistent orientation anchor make it slower on a D-pad.
3. **Search-first Settings.** Useful as a supplement, but poor as the primary model because users should be able to discover available controls without knowing their names.

## 3. Target information architecture

```text
Settings
├── Sources & Guide
│   ├── Providers
│   ├── Combined sources
│   ├── Guide sources
│   ├── Provider assignments & guide policies
│   ├── Guide time shift
│   └── Source compatibility & diagnostics                 [Advanced]
├── Playback
│   ├── General playback
│   ├── Live TV playback & timeshift
│   ├── Audio, subtitles & translation
│   ├── Picture, quality & network
│   ├── Controls, timers & multiview
│   └── Playback compatibility                              [Advanced]
├── Live TV
│   ├── Browsing layout
│   ├── Categories & quick filters
│   ├── Channel numbering & grouping
│   └── Guide defaults
├── Movies & Series
│   ├── Library layout
│   ├── Loading & search
│   ├── Duplicates & variants
│   └── Playback behavior
├── App & Remote
│   ├── Home & top navigation
│   ├── Appearance & language
│   └── Remote shortcuts
├── Privacy & Parental
│   ├── Parental controls
│   └── Privacy & history
├── Recording
│   ├── Recording overview
│   ├── Storage
│   ├── Recording defaults
│   └── Scheduled recordings
├── Backup & Restore
│   ├── Local backups
│   ├── USB backups                                      [when available]
│   └── Google Drive backup                              [when available]
└── About & Support
    ├── App updates
    ├── Crash reports
    └── Version, project links & support
```

The nine rail domains fit the product better than the current eight because the current “Browsing” bucket is removed. Subpages are displayed as overview cards in the right pane, not as more rail rows.

## 4. Complete setting relocation map

No existing user-facing preference is dropped. Dynamic provider, recording, backup, and EPG records stay with their owning domain.

### 4.1 Current Playback page

| New page | Existing controls moved there |
|---|---|
| General playback | Keep screen on during playback; player Back button; system media session; external playback; default playback speed |
| Live TV playback & timeshift | Live clock; clock position; clock size; clock font; timeshift; timeshift depth; timeshift backend; live stream format; live buffer size; zap auto-revert; live overlay timeout |
| Audio, subtitles & translation | Preferred audio language; audio output mode; A/V sync; default A/V offset; subtitle size; subtitle text color; subtitle background; live translation; translation service endpoint |
| Picture, quality & network | Wi-Fi quality cap; Ethernet quality cap; internet speed test and recommendation |
| Controls, timers & multiview | Controls timeout; notice timeout; default stop timer; default idle-standby timer; respect provider connection limit; centered two-slot multiview layout |
| Playback compatibility (Advanced) | Fast retry; audio decoder; video decoder; compatibility memory; clear learned compatibility; surface mode; diagnostics timeout |
| Movies & Series → Playback behavior | Auto-play next episode; VOD HTTP protocol mode |

Rules for dependent controls:

- Clock position, size, and font appear in an indented “Clock appearance” subgroup only when Live clock is enabled.
- Timeshift depth and backend remain visible but disabled with an explanatory state when Timeshift is off; this preserves discoverability without implying they currently apply.
- A/V offset remains visible but disabled when A/V sync is off.
- Translation endpoint remains visible but disabled when live translation is off.
- “Clear learned compatibility” is styled as a secondary destructive action and requires confirmation.
- Decoder, surface, retry, and compatibility controls show a concise warning: change them only to solve playback problems.

### 4.2 Current Browsing page

| New page | Existing controls moved there |
|---|---|
| Live TV → Browsing layout | Live TV channel mode; auto-hide categories; show Live source browser; hide decorative Live rows |
| Live TV → Categories & quick filters | Show Favorites; show All Channels; show Recent Channels; quick-filter terms; quick-filter visibility; Live category sort |
| Live TV → Channel numbering & grouping | Numbering mode; grouping mode; grouped-channel label mode; Live variant preference |
| Live TV → Guide defaults | Default guide category |
| Movies & Series → Library layout | VOD layout; type badge style; Movie category sort; Series category sort |
| Movies & Series → Loading & search | Complete category on open; infinite scroll; portal search |
| Movies & Series → Duplicates & variants | Duplicate handling; VOD variant preference |
| App & Remote → Home & top navigation | Top navigation customization; Home shelf customization; default landing screen |
| App & Remote → Appearance & language | Theme; app language; time format |
| App & Remote → Remote shortcuts | Global, playback, and Live-browse profiles; Red, Green, Yellow, and Blue assignments for each profile |

Rules for dependent controls:

- Auto-hide categories is visually nested under Live TV channel mode when that mode supports it.
- Group label and variant preference remain visible but disabled until Grouped mode is selected.
- VOD variant preference remains visible but disabled when Duplicate handling is “Show all.”
- Remote shortcut profiles use a compact tab row and a two-column button grid on TV; on narrow layouts they become a one-column list.

### 4.3 Remaining current domains

| New domain/page | Existing content |
|---|---|
| Sources & Guide → Providers | Active provider/source, add, edit, delete, provider status, sync, rebuild, catalog warnings |
| Sources & Guide → Combined sources | Create, rename, delete, add/remove/reorder/enable combined M3U members |
| Sources & Guide → Guide sources | Add, enable, refresh, edit timezone, and delete external XMLTV sources |
| Sources & Guide → Assignments & policies | Source priority per provider, assign/unassign/reorder, guide source policy, logo source policy, resolution summary |
| Sources & Guide → Guide time shift | Per-provider EPG offset and reset |
| Sources & Guide → Source compatibility | Xtream text classification and Base64 compatibility, moved out of Privacy; provider diagnostics and maintenance links |
| Sources & Guide → Provider settings/compatibility | Per-provider M3U VOD classification, source-specific catalog controls, provider diagnostics, and database maintenance |
| Privacy & Parental → Parental controls | Protection level, PIN management, and per-provider protected-category management |
| Privacy & Parental → Privacy & history | Incognito mode and clear playback history |
| Recording → Overview | Storage health, free space, active count, scheduled count, and direct “Manage recordings” action |
| Recording → Storage | App/custom/USB location and Wi-Fi-only recording |
| Recording → Defaults | File-name pattern, retention, simultaneous recordings, before/after padding |
| Recording → Scheduled recordings | Browse, stop, enable/disable, skip, cancel, retry, delete, and repair schedules |
| Backup & Restore → Local | Create, share, restore, and manage local backups |
| Backup & Restore → USB | Create and restore USB backups when removable storage exists |
| Backup & Restore → Drive | Sign in/out, push, pull, choose, manage, and delete cloud snapshots |
| About & Support → Updates | Version, auto-check, auto-download, check/download/install, latest release, status, last checked |
| About & Support → Crash reports | Latest report summary, view, share, and delete |
| About & Support → About | Build description and verification, developer, GitHub, donate/support |

## 5. Screen model

### 5.1 TV layout

```text
┌─────────────────────────────────────────────────────────────────────────────┐
│ StreamVault                              Settings              Search   Exit │
├───────────────────┬─────────────────────────────────────────────────────────┤
│ Sources & Guide   │ Playback                                                │
│ Playback          │ Tune how video and audio behave.                        │
│ Live TV           │                                                         │
│ Movies & Series   │ ┌ General playback ───────────────────────────────────┐ │
│ App & Remote      │ │ Everyday playback behavior and player integration  │ │
│ Privacy & Parental│ └─────────────────────────────────────────────────────┘ │
│ Recording         │ ┌ Live TV playback & timeshift ──────────────────────┐ │
│ Backup & Restore  │ │ Stream format, live buffer, clock and rewind       │ │
│ About & Support   │ └─────────────────────────────────────────────────────┘ │
│                   │ ┌ Audio, subtitles & translation ────────────────────┐ │
│                   │ │ Language, output, sync and subtitle appearance     │ │
│                   │ └─────────────────────────────────────────────────────┘ │
└───────────────────┴─────────────────────────────────────────────────────────┘
```

Selecting “Live TV playback & timeshift” replaces the overview cards with a detail page:

```text
Playback / Live TV playback & timeshift                         Restore defaults
Stream format, timeshift, live buffering and on-screen clock.

┌ Clock ─────────────────────────────────────────────────────────────────────┐
│ Live clock                                                     [ On  ]     │
│   Position                                             Top right     ›     │
│   Size                                                     Medium    ›     │
│   Style                                              Digital mono    ›     │
└────────────────────────────────────────────────────────────────────────────┘
┌ Timeshift ─────────────────────────────────────────────────────────────────┐
│ Enable timeshift                                               [ Off ]     │
│   Buffer length                                     30 minutes (disabled) │
│   Storage method                                   Automatic (disabled) › │
└────────────────────────────────────────────────────────────────────────────┘
```

### 5.2 Narrow phone/tablet layout

- Use the same destination and page models.
- Replace the permanent rail with a top-level list and normal forward navigation.
- Render one content column with system Back behavior.
- Keep at least 48 dp touch targets and do not rely on hover or focus color alone.

### 5.3 Header behavior

- The app top bar says “Settings”; the right pane owns the active domain/page title and description.
- Search is always available from the Settings top bar.
- The content header is sticky while rows scroll.
- Detail pages show `Domain / Page` as a breadcrumb and a visible Back affordance on touch layouts.
- “Restore defaults” is page-scoped, never global, and opens a confirmation listing the affected group.

## 6. Row and card language

Use one coherent component family:

| Component | Use |
|---|---|
| `SettingsOverviewCard` | Opens a subpage; title, one-line purpose, optional status summary, chevron |
| `SettingsGroupCard` | Visually and semantically groups 2–7 related rows under a heading |
| `SettingsToggleRow` | Boolean preference; full row toggles; label, short explanation, switch |
| `SettingsChoiceRow` | Opens a selection dialog or sheet; label, current value, chevron |
| `SettingsActionRow` | Executes or opens an action; verb-led label and optional status |
| `SettingsDangerRow` | Clear/delete/reset; warning color is supplemental to explicit text |
| `SettingsStatusCard` | Provider, recording, backup, speed-test, update, or diagnostic state |
| `SettingsInlineNotice` | Dependency explanation, expert warning, error, or restart requirement |

Visual rules:

- Use real Material icons with accessible descriptions where the icon is meaningful; decorative icons are excluded from semantics.
- Use restrained domain accent colors only on icons, selected indicators, and small status marks. Do not tint entire pages differently.
- Use an opaque surface for group cards so related items read as a unit against the existing dark background.
- Keep row text left-aligned and values near the trailing edge, but constrain value width and wrap gracefully.
- Use a 2–3 dp high-contrast focus outline plus a modest surface change. Focus must remain clear in every theme and must not depend on scale animation.
- Keep selected, focused, enabled, and pressed states visually distinct.
- Remove repeated dividers between every row; use internal spacing and group boundaries.
- Prefer plain-language labels. Put protocol names, decoder details, retry intervals, and service endpoints in Advanced pages or supporting descriptions.

## 7. D-pad and Back behavior

1. Entering Settings focuses the last selected domain; first visit focuses Sources & Guide.
2. Up/Down moves only within the rail. Right enters the selected domain’s first overview card or restores its last focused card.
3. Left from the first horizontal position returns to the rail without changing the domain.
4. Selecting an overview card opens the detail page and focuses its first enabled row.
5. Back closes the active dialog first, then returns from detail to the domain overview, then exits Settings.
6. After a dialog closes, focus returns to the exact row that opened it.
7. After a conditional subgroup appears or disappears, focus remains on the parent toggle; it must not jump into or beyond the inserted rows.
8. Disabled rows may be focused only when their explanation is useful; activating them does nothing and accessibility state says why they are unavailable. Purely irrelevant rows are hidden.
9. Long lists keep the focused row within a comfortable central viewport when possible, rather than pinning focus against the screen edge.
10. Mouse/touch activation follows the same state transition as remote activation and never creates a second focus-only click requirement.

## 8. Search behavior

- Search matches localized titles, descriptions, common synonyms, and technical aliases such as “buffer,” “decoder,” “EPG,” “XMLTV,” “subtitles,” and “DVR.”
- Results show `Setting title`, current value/status, and `Domain › Page`.
- Selecting a result opens the correct page, scrolls to the setting, and moves focus to it.
- Search includes static preference/action entries, not dynamic provider names, recording titles, or backup file names.
- Unavailable device-specific results are omitted. Conditional results may appear with their unmet requirement, for example “Enable timeshift first.”
- Search is a convenience layer over the same catalog used by page navigation; it must not maintain a separate list of settings.

## 9. Accessibility, localization, and readability

- Every actionable row exposes one merged semantic node with role, label, current value, enabled state, and toggle state where applicable.
- Switches must not create a duplicate focus target inside the row.
- Section headings use heading semantics so accessibility services can navigate by group.
- Status is never conveyed by color alone; pair it with text and, where useful, an icon.
- Minimum TV row height is 56 dp; touch targets are at least 48×48 dp.
- Body copy must remain readable at couch distance and support Android font scaling without clipping. Test at 1.0× and 1.5× font scale.
- Focus outline and text meet WCAG AA contrast against every shipped theme; normal text targets 4.5:1 and large text/UI boundaries target 3:1.
- All layouts use start/end rather than left/right, except user-facing clock-position choices whose localized labels describe physical screen position.
- Replace all hard-coded Settings text with resources, including EPG headings, policy labels, time-shift buttons, live stream format, and helper copy.
- Validate English, one long-string locale, and an RTL locale before release; update all shipped locale packs as part of the release translation pass.
- Avoid auto-scrolling text. Allow two-line descriptions, then place longer explanations in an info dialog.
- Motion is subtle and nonessential. Focus, selection, and expanded state remain understandable when animations are disabled.

## 10. State, error, and destructive-action behavior

- Preference changes remain immediate and persistent, matching current behavior.
- A changed value is reflected in the row immediately; persistence failures show an inline error/snackbar and restore the last confirmed value.
- Long-running sync, backup, restore, speed-test, update, recording, and EPG actions keep status in their owning card. Do not freeze unrelated Settings navigation.
- Empty states offer a primary next action: Add provider, Add EPG source, Create backup, or Schedule from Guide as appropriate.
- Device-specific controls such as USB storage are hidden when unsupported, not shown disabled forever.
- Clear history, delete provider/source/backup/report, clear learned compatibility, and restore defaults require confirmation.
- Confirmations state the exact scope and whether the operation can be undone.
- Provider deletion, restore, and other long destructive work show determinate progress when available and prevent duplicate submission.
- Advanced pages include “Recommended: Automatic” guidance but never silently rewrite an expert’s existing value.

## 11. Code architecture

### 11.1 Navigation and catalog

Replace integer categories with stable IDs:

```kotlin
enum class SettingsDomain(val route: String) {
    SOURCES_GUIDE("sources-guide"),
    PLAYBACK("playback"),
    LIVE_TV("live-tv"),
    MOVIES_SERIES("movies-series"),
    APP_REMOTE("app-remote"),
    PRIVACY_PARENTAL("privacy-parental"),
    RECORDING("recording"),
    BACKUP_RESTORE("backup-restore"),
    ABOUT_SUPPORT("about-support")
}

data class SettingsDestination(
    val id: String,
    val domain: SettingsDomain,
    val titleRes: Int,
    val descriptionRes: Int,
    val icon: ImageVector,
    val advanced: Boolean = false,
)

sealed interface SettingsModal {
    data class Choice(val itemId: String) : SettingsModal
    data class Confirmation(val actionId: String) : SettingsModal
    data class TextEntry(val itemId: String) : SettingsModal
}
```

The catalog owns stable IDs, destination relationships, localized search metadata, visibility requirements, and the target item ID used for focus. It does **not** own preference values or business actions.

### 11.2 UI state strategy

- Keep `SettingsViewModel` as the compatibility facade during the migration.
- Expose small page-specific state projections and event interfaces so a Composable does not receive the entire state plus dozens of Boolean setters.
- Replace `selectedCategory: Int` with a saveable navigation state containing domain ID, page ID, target item ID, and last-focused item per destination.
- Replace independent modal Booleans with one nullable `SettingsModal`, while retaining specialized provider/backup/recording workflow state where a real multi-step workflow needs it.
- Do not create a second preference repository or duplicate flows.
- After all pages are migrated and tests are green, split action coordinators only where ownership is clear. Persistence decomposition is a follow-up, not a prerequisite for the visual redesign.

### 11.3 Proposed file structure

```text
feature/settings/src/main/java/com/streamvault/feature/settings/presentation/
├── SettingsScreen.kt                         # composition entry and platform callbacks
├── SettingsShell.kt                          # adaptive rail/detail layout and top actions
├── SettingsNavigationState.kt                # typed destination/back/focus state
├── SettingsCatalog.kt                        # domains, pages, items and search metadata
├── SettingsSearchOverlay.kt                  # catalog search and result navigation
├── components/
│   ├── SettingsRows.kt                       # toggle, choice, action, danger rows
│   ├── SettingsCards.kt                      # overview, group, status and notice cards
│   └── SettingsFocus.kt                      # traversal and restoration helpers
└── pages/
    ├── sources/                              # provider and EPG pages
    ├── playback/                             # six focused playback pages
    ├── livetv/                               # Live TV browsing pages
    ├── vod/                                  # Movies & Series pages
    ├── appremote/                            # navigation, appearance and shortcuts
    ├── privacy/                              # parental, privacy and history
    ├── recording/                            # overview, storage, defaults, schedules
    ├── backup/                               # local, USB and Drive
    └── about/                                # updates, reports and project information
```

Do not move every legacy file in one mechanical commit. Create the destination, migrate its behavior and tests, then delete its obsolete legacy section code.

## 12. Implementation plan

### Task 1: Freeze the behavior and preference inventory

**Files:**

- Create: `feature/settings/src/test/java/com/streamvault/feature/settings/presentation/SettingsInventoryTest.kt`
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsCatalog.kt`
- Modify: `feature/settings/src/test/java/com/streamvault/feature/settings/presentation/SettingsPreferenceSnapshotMapperTest.kt`

**Deliverable:** A machine-checked catalog in which stable domain, page, and item IDs are unique and every existing preference/action in sections 4.1–4.3 has an owner.

- [ ] Add the nine `SettingsDomain` IDs and all page IDs from the hierarchy.
- [ ] Add item IDs for every preference and action in the relocation map; include title/description resources, search aliases, page ID, and optional visibility condition.
- [ ] Write tests that reject duplicate IDs, orphan pages, empty labels, and preference items with no destination.
- [ ] Extend the preference snapshot test so changing UI organization cannot omit an observed preference.
- [ ] Run `./gradlew.bat :feature:settings:testDebugUnitTest` and require all tests to pass.
- [ ] Commit as `test(settings): lock redesigned settings inventory`.

### Task 2: Build the typed shell and focus contract

**Files:**

- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsShell.kt`
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsNavigationState.kt`
- Replace: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsNavigationRail.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsScreen.kt`
- Test: `feature/settings/src/androidTest/java/com/streamvault/feature/settings/presentation/SettingsConnectedBehaviorTest.kt`

**Deliverable:** A master/detail shell that supports typed domains, overview/detail Back behavior, and deterministic focus restoration while still hosting legacy page content.

- [ ] Write failing Compose tests for rail traversal, Right-to-content entry, Left-to-rail return, detail-to-overview Back, dialog-to-row focus restoration, and restored focus after switching domains.
- [ ] Implement `SettingsNavigationState` with stable IDs and saved last-focused item IDs.
- [ ] Render the nine domain entries with Material icons, selected/focused separation, and localized labels.
- [ ] Add the sticky content header and correct destination-specific title/subtitle.
- [ ] Add a compatibility adapter that hosts existing section functions until their domain migrates.
- [ ] Route an incoming backup URI to Backup & Restore and preserve the existing import-review workflow.
- [ ] Run the connected Settings test class on the TV emulator and save 1080p screenshots for the rail and one overview.
- [ ] Commit as `feat(settings): add typed master detail shell`.

### Task 3: Create the component system

**Files:**

- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/components/SettingsRows.kt`
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/components/SettingsCards.kt`
- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/components/SettingsFocus.kt`
- Replace usages from: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsRowComponents.kt`
- Test: `feature/settings/src/androidTest/java/com/streamvault/feature/settings/presentation/SettingsComponentsAccessibilityTest.kt`

**Deliverable:** One reusable, semantic, focus-safe visual language for all settings pages.

- [ ] Write tests for merged toggle semantics, choice current value, disabled explanation, heading semantics, minimum target size, and single activation per click/key press.
- [ ] Implement the eight components defined in section 6 using existing core UI tokens where possible.
- [ ] Add consistent enabled, disabled, focused, pressed, selected, warning, and destructive states for all shipped themes.
- [ ] Verify long values wrap without covering controls and switches are not separate focus stops.
- [ ] Capture dark, light, focused, disabled, and 1.5× font-scale component screenshots.
- [ ] Commit as `feat(settings): add accessible settings components`.

### Task 4: Redesign Playback

**Files:**

- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/pages/playback/PlaybackOverview.kt`
- Create six page files under: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/pages/playback/`
- Create the initial VOD playback page: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/pages/vod/VodPlaybackSettingsPage.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsPlayerPreferenceDialogs.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsScreenDialogs.kt`
- Delete after parity: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsPlaybackSection.kt`
- Test: `feature/settings/src/androidTest/java/com/streamvault/feature/settings/presentation/SettingsPlaybackNavigationTest.kt`

**Deliverable:** The current Playback mega-list is replaced by six small pages plus the two VOD-specific settings moved to Movies & Series.

- [ ] Write reachability tests for every item in section 4.1 and dependency tests for clock, timeshift, A/V sync, and translation children.
- [ ] Implement the Playback overview cards with useful current-state summaries such as “Timeshift off” or “Quality automatic.”
- [ ] Migrate General playback without changing stored values or callbacks.
- [ ] Migrate Live TV playback & timeshift and preserve live stream format collection.
- [ ] Migrate Audio, subtitles & translation with a subtitle preview inside the appearance dialog.
- [ ] Migrate Picture, quality & network and preserve speed-test progress/recommendation actions.
- [ ] Migrate Controls, timers & multiview.
- [ ] Migrate Advanced compatibility with warning copy and clear-compatibility confirmation.
- [ ] Move auto-play next episode and VOD HTTP protocol mode into the initial Movies & Series Playback behavior page before removing the old list.
- [ ] Remove old Playback list code only after the inventory test proves parity.
- [ ] Run unit tests and connected playback settings tests; manually verify values survive app restart.
- [ ] Commit as `feat(settings): redesign playback settings`.

### Task 5: Replace Browsing with Live TV, Movies & Series, and App & Remote

**Files:**

- Create page files under:
  - `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/pages/livetv/`
  - `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/pages/vod/`
  - `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/pages/appremote/`
- Reuse/move: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/RemoteShortcutSettingsSupport.kt`
- Reuse/move: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/RemoteShortcutSettingsModels.kt`
- Delete after parity: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsBrowsingSection.kt`
- Test: `feature/settings/src/androidTest/java/com/streamvault/feature/settings/presentation/SettingsBrowsingDomainsTest.kt`

**Deliverable:** There is no “Browsing” catch-all. Its settings are discoverable in three clear domains.

- [ ] Write reachability tests for every item in section 4.2 and dependency tests for Live grouping and VOD duplicate handling.
- [ ] Implement the four Live TV pages and move Live category sort with the other category controls.
- [ ] Implement the remaining Movies & Series pages and retain auto-play next episode plus VOD HTTP mode on Playback behavior.
- [ ] Implement App & Remote pages, preserving navigation/shelf reorder behavior and all twelve remote shortcut assignments.
- [ ] Add page summaries that report meaningful state without exposing technical enum names.
- [ ] Delete the old Browsing section only after inventory and callback parity pass.
- [ ] Test D-pad entry/exit for every page, one RTL locale, and narrow one-column rendering.
- [ ] Commit as `feat(settings): split browsing into focused domains`.

### Task 6: Redesign Sources & Guide

**Files:**

- Create page files under: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/pages/sources/`
- Refactor existing `SettingsProvider*.kt`, `SettingsCombinedM3uComponents.kt`, and `SettingsEpg*.kt` into those pages without changing their action classes.
- Test: `feature/settings/src/androidTest/java/com/streamvault/feature/settings/presentation/SettingsSourcesGuideTest.kt`

**Deliverable:** Provider and EPG management become one coherent source domain, and source parser compatibility no longer appears under Privacy.

- [ ] Write tests for no-provider, one-provider, combined-source, no-EPG, assignment, unsupported-policy, syncing, warning, and deletion states.
- [ ] Build Providers and Combined sources overview pages from existing cards and workflows.
- [ ] Build Guide sources, Assignments & policies, and Guide time-shift pages.
- [ ] Move Xtream classification and Base64 compatibility to Advanced source compatibility.
- [ ] Localize every remaining hard-coded EPG and policy string.
- [ ] Preserve sync progress, cancellation, warnings, active-source selection, and provider deletion behavior.
- [ ] Commit as `feat(settings): unify source and guide settings`.

### Task 7: Migrate Privacy, Recording, Backup, and About

**Files:**

- Create page files under `pages/privacy/`, `pages/recording/`, `pages/backup/`, and `pages/about/`.
- Refactor existing `SettingsPrivacySection.kt`, `SettingsRecording*.kt`, `SettingsBackup*.kt`, and about/update/report code into the page structure.
- Test: add one connected behavior test class per domain.

**Deliverable:** All remaining domains use the same page, card, row, focus, and error patterns.

- [ ] Keep parental level/PIN and incognito/history under Privacy & Parental; remove source compatibility controls from this domain.
- [ ] Split Recording status, storage, defaults, and schedule management while preserving all status-specific actions.
- [ ] Split Local, USB, and Drive backup pages and keep unavailable device paths hidden.
- [ ] Split updates, crash reports, and project information under About & Support.
- [ ] Add explicit confirmation text for every destructive action listed in section 10.
- [ ] Verify long-running work does not block navigation outside the owning action.
- [ ] Commit each independently testable domain separately.

### Task 8: Add catalog-backed search

**Files:**

- Create: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsSearchOverlay.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsCatalog.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsShell.kt`
- Test: `feature/settings/src/test/java/com/streamvault/feature/settings/presentation/SettingsSearchTest.kt`
- Test: `feature/settings/src/androidTest/java/com/streamvault/feature/settings/presentation/SettingsSearchNavigationTest.kt`

**Deliverable:** Search finds and focuses a setting without duplicating Settings metadata.

- [ ] Write ranking tests for title, description, synonym, acronym, and technical alias matches.
- [ ] Implement normalized locale-aware catalog filtering and deterministic result ordering.
- [ ] Implement result rows with current value and breadcrumb.
- [ ] Navigate a result to the target page/item and restore focus to Search after Back.
- [ ] Exclude unavailable device-specific entries and explain unmet conditional dependencies.
- [ ] Commit as `feat(settings): add settings search`.

### Task 9: Replace modal flags with typed modal state

**Files:**

- Replace: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsScreenDialogState.kt`
- Modify: `feature/settings/src/main/java/com/streamvault/feature/settings/presentation/SettingsScreenDialogs.kt`
- Modify page-specific dialog hosts under `pages/`.
- Test: `feature/settings/src/test/java/com/streamvault/feature/settings/presentation/SettingsModalStateTest.kt`

**Deliverable:** At most one simple settings modal is active, its owner is explicit, and dismiss always restores focus.

- [ ] Model choice, confirmation, and text-entry modals with `SettingsModal`.
- [ ] Keep complex provider, backup-import, recording-browser, and parental workflows as typed workflow states rather than forcing them into a generic dialog.
- [ ] Migrate one dialog family at a time and remove each obsolete Boolean after its tests pass.
- [ ] Test replacement, dismissal, process recreation, and opener focus restoration.
- [ ] Commit as `refactor(settings): use typed modal state`.

### Task 10: Localization, responsive layouts, and visual polish

**Files:**

- Modify base and localized resources under: `feature/settings/src/main/res/values*/`
- Modify Settings page/component files for RTL and adaptive layout fixes.
- Add golden assets under the existing Settings screenshot-test location.

**Deliverable:** The redesign is readable, translated, RTL-safe, and visually consistent at supported sizes.

- [ ] Audit with `rg` to ensure no user-facing Settings text remains hard-coded in Kotlin.
- [ ] Finalize concise titles, descriptions, expert warnings, disabled explanations, and confirmation copy.
- [ ] Update all shipped locale packs; resolve long-label overflow instead of shrinking text below the typography scale.
- [ ] Capture 1080p TV goldens for all nine domain overviews and representative detail pages.
- [ ] Capture 720p TV, narrow phone, light theme, 1.5× font, and RTL goldens.
- [ ] Verify focus, selected, disabled, warning, and error contrast in every shipped theme.
- [ ] Commit as `style(settings): finish responsive accessible settings UI`.

### Task 11: Regression, persistence, and release validation

**Files:**

- Modify: Settings unit and connected tests.
- Update: `docs/images/Settings.png` after the final UI is accepted.
- Add: release notes in the project’s normal changelog location.

**Deliverable:** Evidence that the redesign did not lose behavior, corrupt preferences, or introduce navigation traps.

- [ ] Run `./gradlew.bat :feature:settings:testDebugUnitTest`.
- [ ] Run `./gradlew.bat :feature:settings:connectedDebugAndroidTest` on the Android TV emulator.
- [ ] Run the project’s full unit-test command and build the debug APK.
- [ ] For every catalog item, prove that it is visible or intentionally conditionally unavailable and invokes the original callback/persistence path.
- [ ] Change representative values in all nine domains, restart the app, and verify persistence.
- [ ] Export a backup before the redesign build, import it into the redesign build, and confirm preference parity.
- [ ] Validate D-pad-only journeys: open Settings, search, change a choice, toggle a dependency, dismiss a modal, manage a provider, and exit.
- [ ] Validate accessibility-service announcements for toggle, choice, disabled, status, heading, and destructive rows.
- [ ] Inspect screenshots for clipping, scroll-edge focus, value overlap, incorrect titles, and accidental hard-coded English.
- [ ] Update `docs/images/Settings.png` with the accepted final overview.
- [ ] Run `graphify update .` after code changes and commit the updated graph artifacts according to repository policy.
- [ ] Commit as `docs(settings): record redesign validation`.

## 13. Acceptance criteria

The redesign is complete only when all of the following are true:

- The “Browsing” catch-all no longer exists.
- No detail page contains more than eight primary rows before conditional children; Advanced pages may contain up to ten.
- Every existing setting/action in section 4 is reachable through the new hierarchy and retains its stored value and behavior.
- Common settings are reachable within three activations from the Settings root; Search reaches any indexed setting within two activations after text entry.
- A D-pad user can move rail → overview → detail → dialog and all the way back without focus loss or ambiguity.
- Closing a dialog restores focus to its opener.
- Switching a parent toggle does not make focus jump when child rows appear/disappear.
- Every page has an accurate title and plain-language description.
- All actionable rows expose correct accessibility role, state, value, and enabled semantics.
- There are no hard-coded user-facing Settings strings in Kotlin.
- TV 720p/1080p, phone, 1.5× font, light theme, and RTL screenshots have no clipping or overlapping controls.
- Existing backup files import without a schema migration caused solely by the redesign.
- Unit, connected, and build verification commands pass.
- The public Settings screenshot and release notes reflect the final design.

## 14. Delivery order and review gates

Review the work at these points instead of waiting until the entire redesign is complete:

1. **IA gate:** Approve the nine domains, subpages, and relocation map before production UI implementation.
2. **Shell gate:** Approve one TV overview and one detail-page golden, including focus treatment.
3. **Playback gate:** Validate the hardest domain first; do not migrate the rest if Playback still feels dense.
4. **Browsing replacement gate:** Confirm Live TV, Movies & Series, and App & Remote feel like independent, understandable domains.
5. **Functional parity gate:** Inventory tests show every old control has a new owner.
6. **Accessibility/localization gate:** RTL, large text, semantics, and D-pad paths pass before visual sign-off.
7. **Release gate:** Persistence and old-backup import pass before replacing the public screenshot.

This ordering deliberately solves the navigation and component language first, then uses Playback as the proving ground, then removes the current Browsing page, and only afterward migrates lower-risk domains.
