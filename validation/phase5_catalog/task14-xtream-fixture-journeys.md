# Phase 5 Catalog - temporary Xtream fixture journeys

Date: 2026-09-03
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

The fixture returned one live channel, two movies, two series, movie metadata,
and one season with two episodes per series. All observed player-API, XMLTV,
and image requests returned HTTP 200. No credentials or raw provider URLs are
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

The fixture test uses an ephemeral loopback port and does not require Android.
The production-activity run uses port 8765 because that is the emulator-host
mapping used by `10.0.2.2`.

## Journey results

| Surface | Evidence observed |
|---|---|
| Dashboard | `streamvault.destination:home`; Recently Added Movies and Recently Updated Series each rendered two fixture cards. |
| Movies browse | `streamvault.destination:movies`; two titles, one category, Top Rated/Newest shelves, and both movie cards rendered. |
| Movie detail | `Fixture Movie One` rendered rating, release date, duration, genre, director, cast, plot, Play/Copy URL/Download/Cast/Trailer actions, and favourite toggle. |
| Movies saved filter | Movies `Saved` filter rendered exactly `Fixture Movie One` after the detail toggle. |
| Series browse | `streamvault.destination:series`; two titles, one category, and both series cards rendered. |
| Series detail | `Fixture Series One` rendered metadata, Season 1, and `Episodes (2)` with `Pilot` and `Second Signal`. |
| Series saved filter | Series `Saved` filter rendered exactly `Fixture Series One` after the detail toggle. |
| Search | `streamvault.destination:search`; query `Fixture` rendered `5 results` grouped as Live TV 1, Movies 2, and Series 2. The movie and series rows were confirmed after scrolling. |

The D-pad path was used for route changes and detail activation; scrolling was
used only to expose lower search and episode rows. UIAutomator dumps captured
the route markers, titles, metadata, episode count, result counts, and saved
filter cards. Screenshots were captured locally for review but are not checked
in because they contain no additional contract beyond the semantic dumps.

## Detail action probes

On the movie detail surface, D-pad activation reached Copy URL, Download, and
Cast without a Catalog crash or lost focus. The Download probe started and
stopped `DownloadForegroundService`; the fixture intentionally does not serve
media-file transfer URLs, so a completed download is not claimed. The Cast
probe had no receiver available on the emulator and did not produce a chooser;
receiver-backed Cast behavior remains open. Copy URL activation was dispatched
but the API 36 shell does not expose a supported clipboard-read command for
independent verification.

## Interpretation and remaining gates

This run demonstrates that the extracted Catalog production activity can load
seeded VOD/series data, navigate detail and season/episode surfaces, toggle
movie and series favourites, apply saved filters, and search across all three
content types. It narrows the previous Task 12 blocker: the checkout lacked a
fixture capable of exercising these paths, not a Catalog UI path. The checked-
in fixture now makes this diagnostic run rerunnable.

The run does not close the full Phase 5 acceptance gate. The public M3U flow
remains live-only, and the following journeys still need to be executed against
the checked-in fixture (or approved provider data):

- Dashboard customization save/cancel and focus traversal;
- browse load-more, reorder, return-route, download enqueue, and Cast chooser;
- direct Favorites host rendering and reorder/save/cancel;
- touch-mode, phone/tablet, RTL, reduced-motion, and accessibility variants;
- cache-equivalent before/after Dashboard performance comparison.

The original public-M3U smoke evidence and connected test counts remain in
`task12-device-journeys.md` and `task12-connected-validation.md`.
