# Phase 5 Catalog - temporary Xtream fixture journeys

Date: 2026-09-04
Target: `emulator-5554` (`Television_1080p(AVD) - 16`), API 36, AOSP TV on x86,
physical 1920x1080 at 320 dpi.

## Purpose and scope

The public M3U seed used by Task 12 contains live channels only, so it cannot
exercise the Catalog's movie, series, detail, or saved-content paths. To test
whether the open journey gate was caused by the provider data rather than the
extracted UI, the checked-in `tools/catalog_xtream_fixture.py` was run on the
host at port 8765. The emulator reached it through `10.0.2.2`; the debug APK
was configured through the existing `xtream.dev.*` `local.properties` hooks,
then the settings were removed after the run. Route captures remain ignored
local artifacts; the fixture is a development diagnostic, not a production-
provider claim.

The fixture returns one live channel, 63 movies, 63 series, movie metadata,
and one season with two episodes per series. The first two movie/series items
remain stable fixture titles for detail and Search assertions; the remaining
items are generated in pagination categories so the selected-library flow can
prove a second page. It also serves deterministic 40-byte MP4 fixture content
for the provider movie/series transfer paths, allowing the production download
service to reach `Completed`. All observed player-API, XMLTV, image, and
download requests returned HTTP 200. No credentials or raw provider URLs are
included in this evidence.

## Rerun protocol

1. Run `python -m unittest tools.tests.test_catalog_xtream_fixture`.
2. Add the four `xtream.dev.*` entries described in `docs/DEV_SEEDING.md` to
   the ignored root `local.properties`, using `http://10.0.2.2:8765` as the
   server and the fixture account values.
3. Start `python tools/catalog_xtream_fixture.py --port 8765` from the repo
   root, build/install the debug APK, clear `com.streamvault.app.debug`, and
   launch `MainActivity`.
4. Exercise the journeys below with D-pad/UIAutomator, then stop the server and
   remove the four local fixture entries.

The repeatable semantic subset can be driven by the checked-in ADB harness
after the fixture-configured debug APK is installed:

```powershell
python tools/catalog_connected_validation.py --adb E:\androidSdk\platform-tools\adb.exe --serial emulator-5554
```

It writes UIAutomator snapshots and `report.json` under the ignored
`build/catalog-validation` directory. The harness uses the production TV
focus/input path and does not mutate `local.properties`.

## Harness result (2026-09-04)

The command passed on `emulator-5554` (API 36 AOSP TV, 1920x1080) with
`com.streamvault.app.debug`. It proved these surfaces in one run: Home,
Movies browse and full-library entry/back, movie detail and favourite toggle,
the movie Download action followed by the production Downloads card reaching
`Completed` with a non-empty output path, the Movies `Saved` filter, Series browse, series detail with Season 1 and both
episodes, Search with the five grouped fixture results, Settings Browsing, and
the VOD pagination toggle. With Infinite scroll disabled, the selected Movies
library reached `Load more (60/63)`; semantic focus activated it and the next
page rendered through `Pagination Movie 63` with the button gone. The same
selected-library path then proved Series `Load more (60/63)` through
`Pagination Series 63`, again with the load-more card gone. Infinite scroll was
restored to enabled before the Dashboard customization checks. The latest
report and UI dumps are local ignored artifacts under
`build/catalog-validation-download-completion-retry`; no provider credentials
or raw URLs are checked in.

The same run passed the Settings-owned Dashboard shelf customization dialog.
It opened the dialog through semantic Settings targets, removed a shelf and
cancelled without changing the persisted `7 shelves` setting, saved the removal
to `6 shelves`, then reset and saved the default order back to `7 shelves`.
The report includes the settings route, dialog, cancel, save, and restore
surfaces.

The fixture test uses an ephemeral loopback port and does not require Android.
The production-activity run uses port 8765 because that is the emulator-host
mapping used by `10.0.2.2`.

## Journey results

| Surface | Evidence observed |
|---|---|
| Dashboard | `streamvault.destination:home`; Recently Added Movies and Recently Updated Series each rendered two fixture cards. |
| Movies browse | `streamvault.destination:movies`; two titles, one category, Top Rated/Newest shelves, and both movie cards rendered. |
| Movies full-library entry/back | Browse hero activated `Browse Full Movie Library`; the selected library rendered `Filters & Sort` and returned to the modern browse surface through Back. |
| Movies pagination | With Infinite scroll off, the selected library rendered `Load more (60/63)` after 20 swipes; activation appended the second page through `Pagination Movie 63` and removed the button. Infinite scroll was then restored on. |
| Series pagination | With Infinite scroll off, the selected library rendered `Load more (60/63)` after 20 swipes; activation appended the second page through `Pagination Series 63` and removed the button. |
| Movie detail | `Fixture Movie One` rendered rating, release date, duration, genre, director, cast, plot, Play/Copy URL/Download/Cast/Trailer actions, and favourite toggle. |
| Download completion | The movie Download action reached the production Downloads route; the `Fixture Movie One` card showed `Completed`, a non-empty output path under the app Downloads directory, and the fixture served the deterministic 40-byte body. |
| Movies saved filter | Movies `Saved` filter rendered exactly `Fixture Movie One` after the detail toggle. |
| Series browse | `streamvault.destination:series`; two titles, one category, and both series cards rendered. |
| Series detail | `Fixture Series One` rendered metadata, Season 1, and `Episodes (2)` with `Pilot` and `Second Signal`. |
| Series saved filter | Series `Saved` filter rendered exactly `Fixture Series One` after the detail toggle. |
| Movies browse reorder | The Favorites reorder surface moved the two fixture movies, saved the changed order, re-entered to prove persistence, and restored the original order. |
| Series browse reorder | The Favorites reorder surface moved the two fixture series, saved the changed order, re-entered to prove persistence, and restored the original order. |
| Search | `streamvault.destination:search`; query `Fixture` rendered `5 results` grouped as Live TV 1, Movies 2, and Series 2. The movie and series rows were confirmed after scrolling. |
| Direct Favorites host | A focused API 36 TV instrumentation test hosted the real `FavoritesScreen` without adding a production route, rendered the Saved shell and reorder panel, and dispatched Activity Back through the registered `BackHandler` to cancel reorder. |
| RTL and large text | `CatalogPresentationBehaviorTest` rendered Movie and Series cards under `LayoutDirection.Rtl` and a 1.5x font scale; merged content descriptions and click actions remained available, and the first RTL card was placed to the right of the second. |
| Reduced motion | The full 7-test Catalog instrumentation suite passed on the same API 36 TV emulator with `settings global animator_duration_scale=0`; the setting was restored to `1` after the run. |
| TalkBack service | The full 7-test Catalog instrumentation suite passed with the API 36 AOSP TalkBack service enabled and was rerun after restoring the service. The production fixture harness reached Catalog reorder, but its semantic D-pad path lost focus under TalkBack: one run timed out waiting for `Reorder Items`, and a warmed rerun timed out focusing `Fixture Movie One`. The app exposed `Reorder Items` in the captured accessibility tree and a manual D-pad long press opened it; the end-to-end TalkBack fixture journey therefore remains a harness limitation rather than a passing production audit. |

The D-pad path was used for route changes and detail activation; scrolling was
used only to expose lower search and episode rows. UIAutomator dumps captured
the route markers, titles, metadata, episode count, result counts, and saved
filter cards. Screenshots were captured locally for review but are not checked
in because they contain no additional contract beyond the semantic dumps.

## Detail action probes

On the movie detail surface, D-pad activation reached Copy URL, Download, and
Cast without a Catalog crash or lost focus. Download reached the production
Downloads screen and completed through the deterministic fixture media
endpoint. The Cast probe had no receiver available on the emulator and did not produce a chooser;
receiver-backed Cast behavior remains open. Copy URL activation was dispatched
and the helper is now independently verified by a focused Robolectric test
that reads the primary `ClipboardManager` clip. The API 36 shell still does
not expose a supported clipboard-read command for a second device-shell
assertion.

## Interpretation and remaining gates

This run demonstrates that the extracted Catalog production activity can load
seeded VOD/series data, navigate detail and season/episode surfaces, paginate
both selected Movie and Series libraries, reorder and persist Movie and Series
Favorites shelves, toggle movie and series favourites, apply saved filters,
search across all three content types, and complete a fixture-backed movie
download through the production Downloads screen. It narrows the
previous Task 12 blocker: the checkout lacked a
fixture capable of exercising these paths, not a Catalog UI path. The
checked-in fixture now makes this diagnostic run rerunnable.

The run does not close the full Phase 5 acceptance gate. The public M3U flow
remains live-only, and the following journeys still need to be executed against
the checked-in fixture (or approved provider data):

- Cast receiver chooser;
- touch-mode, phone/tablet, and a complete production accessibility-service
  journey; RTL/large-text semantics, reduced-motion instrumentation, and the
  direct TalkBack Catalog instrumentation suite are covered by the checks
  above, but the fixture-driven TalkBack traversal still needs a stable
  semantic D-pad harness path;
- cache-equivalent before/after Dashboard performance comparison (deferred
  until the benchmark environment has stable capacity).

The original public-M3U smoke evidence and connected test counts remain in
`task12-device-journeys.md` and `task12-connected-validation.md`.
