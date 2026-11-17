# Featured Movies and Series card

Restores the horizontal title/subtitle/action panel removed by `9001fbe5b`. It appears above the action row and Continue Watching shelf in the Modern Movies and Series libraries.

## Settings and behavior

Settings → Movies & Series → Library browsing contains independent Movie featured card and Series featured card choices. Both are searchable in Settings.

- Hidden: removes the item, its spacing, focus target, and candidate query.
- Latest releases: greatest valid release date; movie year is accepted when the release date is absent.
- Recently added (Movies): stored `Movie.addedAt` timestamp (provider supplied where available; some adapters use an import-time fallback).
- Recently updated (Series): provider `Series.lastModified` timestamp. This is deliberately not labelled as a provider addition date.
- Continue watching: most recent unfinished title from the active provider.
- Feeling lucky: a stable, seeded random unwatched title; it does not change on recomposition and gets a new seed on library entry.
- For you: local genre affinity from viewing history and the connected account's Trakt watchlist, with watchlist membership and rating as additional signals. Without affinity data, rating determines the order. No recommendation data is sent to another service.
- Up next (Series): resume an unfinished episode, or choose the next available unwatched episode after 90% completion or an explicit watched mark. Episodes are ordered by season/episode rather than database ID. A caught-up show does not block another show's next episode. Saved progress is retained.

Default choices: Continue watching for Movies, Up next for Series. If a mode has no eligible title, the card is omitted. The selector does not invent missing dates. Selection is provider scoped and excludes hidden categories and protected titles while parental controls are enabled. Discovery modes inspect the active local catalog; resume modes fetch history identities only. Selection work runs off the main thread. History-based selection considers the most recent 5,000 history rows.

## Verification

- Six selection unit tests passed: independent release/addition order, invalid dates, provider identity, completion boundary, hidden mode, stable random choice, watchlist affinity, season crossing, and unknown episode duration.
- SettingsDesignTokensTest: three tests passed, including nine-theme control contrast.
- Resource coverage: all 11 catalog keys and 10 settings keys exist with translations in all 38 advertised locales.
- Featured card text, action and focus contrast passed across all nine themes. Values below are WCAG relative luminance ratios; resting surface is SurfaceElevated and focused surface is SurfaceAccent. The action uses OnPrimary on Brand.

| Token pair | Minimum | Maximum |
|---|---:|---:|
| TextPrimary/SurfaceElevated | 12.59:1 | 18.01:1 |
| TextSecondary/SurfaceElevated | 7.55:1 | 10.96:1 |
| Focus/SurfaceElevated | 14.00:1 | 19.44:1 |
| TextPrimary/SurfaceAccent | 7.86:1 | 14.20:1 |
| TextSecondary/SurfaceAccent | 4.99:1 | 8.65:1 |
| Focus/SurfaceAccent | 8.01:1 | 15.33:1 |
| SettingsTextPrimary/Selected | 9.42:1 | 14.00:1 |
| SettingsFocus/Selected | 9.57:1 | 15.11:1 |
| OnPrimary/Brand | 4.94:1 | 12.46:1 |

The debug app and instrumentation APK build successfully. The full opt-in `FeaturedCardJourneyTest` passed on the Android TV emulator (421.714 seconds), using the production screens and an isolated local Xtream provider. A separate Settings focus journey also passed (84.784 seconds) after the final initial-focus adjustment. Existing providers and preferences are preserved and restored.

Captured in `classic_blue`, `light`, and `amoled_black`:

- Modern Movies and Series: default Resume/Up next, latest release, recently added/updated, Feeling lucky, For you, Hidden, and horizontal/vertical D-pad traversal.
- Settings: initial screen, Library browsing, the two new rows, and each choice in both featured-card dialogs with D-pad focus.
- Arabic, Persian and Hebrew: Movies, Series, Library browsing, and the Movie featured-card dialog. Artwork/title and action placement mirror, and translated controls fit the captured TV layout.

The existing Continue Watching removal confirmation and other Settings pages were not captured in this review; their visual states remain untested. Real-provider playback, a real provider's next-episode availability, connected Trakt account synchronization, and phone layouts are also untested. The local fixture exercises rendering and selection without playing a real stream. The 90% advancement and season transition are covered by unit tests.

Local evidence is stored in `C:/Users/David/AppData/Local/Temp/iptv-featured-card-work/`: `full-emulator-review.log`, `featured-settings-focus.log`, and the `emulator/` PNG/XML captures. Focus screenshots are named `*_settings_Movie_row_focused` and `*_settings_Series_row_focused`; diagnostic `failure` files are excluded from successful evidence. The contrast test XML includes the individual ratios for every palette. No physical TCL TV GPU measurement was needed because the restored panel does not add a translucent layer over video.
