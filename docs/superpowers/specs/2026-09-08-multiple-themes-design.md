# Multiple Themes Design

## Goal

Add a user-selectable palette theme to StreamVault. The existing blue/navy palette remains the default, and the M3 purple-on-black palette becomes a second selectable theme.

## Scope

- Theme selection is a persisted application preference.
- Settings exposes a single theme selector in the Browsing section, directly below the existing view-mode-style app preferences.
- The first release changes colors, Material color roles, and focus treatment only. Typography, shapes, spacing, layout density, and behavior remain shared.
- Theme selection applies to the main app and the TV input setup activity.
- Backup export and restore include the selected theme.
- Unknown or missing stored values safely fall back to the blue default.
- This slice does not add remote theme packages, user-authored colors, per-screen themes, or runtime plugin loading.

## Theme IDs

Theme IDs are stable storage values, not enum ordinals:

| ID | Display name | Palette |
| --- | --- | --- |
| `classic_blue` | Classic blue | Current StreamVault blue/navy palette |
| `m3_purple` | M3 purple | Purple primary with neutral black surfaces |

## Architecture

The domain layer owns the stable `AppTheme` ID enum and its tolerant storage parser. The data layer exposes it through `SettingsPreferences`, stores it in DataStore, and includes it in the existing portable backup registry and backup codec. The settings feature observes and writes the enum through its existing preference snapshot/view-model flow.

The core UI layer owns the color palettes because it is intentionally independent of the domain and app modules. `StreamVaultTheme(themeId: String)` resolves the requested palette, updates the existing `AppColors` facade through Compose state, and constructs the Material TV color scheme from that palette. Existing color call sites remain source-compatible, while top-level aliases become computed getters so they follow palette changes. The app entry points collect the domain preference and pass its stable storage value to core UI.

## Safety and compatibility

- The default is the current palette, so upgrades do not change appearance for users without a stored theme.
- Invalid backup values are ignored by the domain parser and resolve to the default.
- No network, permission, reflection, dynamic code, credential, or provider behavior is introduced.
- Backup keys follow existing portable preference conventions and are not device-bound.

## Verification

- Unit tests cover theme ID parsing/defaulting and palette resolution.
- Settings tests cover snapshot propagation and the setter contract.
- Backup registry tests cover portable admission.
- Targeted Gradle unit tests are run for domain, core UI, feature settings, and data; existing unrelated baseline failures are reported separately.
