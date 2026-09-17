# Settings experience implementation plan

> **Rejected design — superseded.** The user rejected the resulting UI and UX, including legibility, layout, and focus behavior. The checked items below are historical implementation records, not design acceptance. Use [the replacement plan](2026-09-16-settings-redesign-replacement.md).

**Goal:** Replace dense settings lists with focused destinations and a consistent, readable TV interface while retaining existing preference and action handlers.

**Design:** Keep the feature boundary and persistence model. A settings destination catalog owns category and page metadata. The existing section renderers accept an optional page and retain their original callbacks. Playback becomes short task-based pages. Browsing is replaced by Live TV, Movies & Series, and App & Remote. Provider parser options move out of Privacy. Use a quiet navigation rail, proper icons, large page titles, descriptive navigation cards, and consistent full-width rows with a single focus target per switch. Back returns to the owning overview. Existing category IDs used by backup entry points stay stable.

**Constraints:** Work only in the current checkout; no worktrees. Preserve all controls, conditional behavior, dialogs, preferences, backup schema, and feature boundaries. Do not change playback engines.

## Steps

- [x] Add catalog navigation tests for unique destinations, ownership, legacy category IDs, and page bounds; verify the initial failing test.
- [x] Add `SettingsDestination.kt` and resources. Update `SettingsNavigationRail.kt` and `SettingsContentPane.kt` with overview/detail navigation and saved selection.
- [x] Partition `SettingsPlaybackSection.kt` and `SettingsBrowsingSection.kt` by explicit page ownership; preserve all callbacks. Move parser compatibility from `SettingsPrivacySection.kt` to Sources.
- [x] Update `SettingsRowComponents.kt`: readable values, consistent surfaces, disabled semantics, single switch focus. Apply the row language to migrated controls.
- [x] Verify settings unit tests, feature boundary, debug build, and connected UI navigation/interaction tests. Inspect emulator screenshots for overview, detail, dialogs, focus, long labels, and scrolling. Record limitations accurately.
- [x] Update `docs/CHANGELOG.md` and record visual validation evidence.

## Validation details

Run `./gradlew.bat :feature:settings:testDebugUnitTest :app:assembleDebug` and the relevant connected settings tests. Compare the original and new renderer callback inventories to detect lost functionality. On the emulator, navigate with D-pad into overview and detail, open and dismiss a choice, toggle a representative preference and restore it, and verify Back. No live playback fix is in scope; do not claim live playback validation from settings screenshots.
