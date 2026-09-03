# Phase 5 Catalog - temporary Xtream fixture journeys

Date: 2026-09-03
Target: `emulator-5554` (`Television_1080p(AVD) - 16`), API 36, AOSP TV on x86,
physical 1920x1080 at 320 dpi.

## Purpose and scope

The public M3U seed used by Task 12 contains live channels only, so it cannot
exercise the Catalog's movie, series, detail, or saved-content paths. To test
whether the open journey gate was caused by the provider data rather than the
extracted UI, a temporary local Xtream-compatible HTTP fixture was run on the
host at port 8765. The emulator reached it through `10.0.2.2`; the debug APK
was configured through the existing `xtream.dev.*` `local.properties` hooks,
then the settings were removed after the run. The fixture process and route
captures remain ignored local artifacts and are not application code or a
production-provider claim.

The fixture returned one live channel, two movies, two series, movie metadata,
and one season with two episodes per series. All observed player-API, XMLTV,
and image requests returned HTTP 200. No credentials or raw provider URLs are
included in this evidence.

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

## Interpretation and remaining gates

This run demonstrates that the extracted Catalog production activity can load
seeded VOD/series data, navigate detail and season/episode surfaces, toggle
movie and series favourites, apply saved filters, and search across all three
content types. It narrows the previous Task 12 blocker: the checkout lacks a
committed/reusable production-activity fixture harness, not a Catalog UI path.

The run does not close the full Phase 5 acceptance gate. The temporary server
was not committed, the public M3U flow remains live-only, and the following
journeys still need a stable rerunnable fixture or approved provider data:

- Dashboard customization save/cancel and focus traversal;
- browse load-more, reorder, return-route, download enqueue, and Cast chooser;
- direct Favorites host rendering and reorder/save/cancel;
- touch-mode, phone/tablet, RTL, reduced-motion, and accessibility variants;
- cache-equivalent before/after Dashboard performance comparison.

The original public-M3U smoke evidence and connected test counts remain in
`task12-device-journeys.md` and `task12-connected-validation.md`.
