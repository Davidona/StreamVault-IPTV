# Live TV channel count and pagination review

Validated 2026-11-17 on emulator-5554, Android TV, 3840 x 2160, COMPACT mode.

## Behavior

Live TV still reads category channels in database batches of 200. The browse header now uses the existing category total, including updated category metadata, instead of the size of the loaded page. A category with 525 channels therefore shows 525 immediately. Search, virtual categories, provider-filtered combined categories, and reorder mode retain their own loaded membership count. No full-catalog query was added.

The scroll observer reads the latest availability flag and callback through `rememberUpdatedState`. Its emission includes the loaded item count, so a small appended page can trigger another request while the viewport remains near the end.

Channel rows have no resting outline. The portrait sidebar Cancel action uses the standard secondary TV button with a spacing-token minimum target of 48 x 48dp. The count label truncates with an ellipsis if necessary.

## Verification

- 48 HomeViewModel tests and four HomeUiState tests passed. Coverage includes 200 -> 400 -> 525, terminal availability, a smaller category and return to a fresh first page, 300 -> 600 -> 725 search results, clearing search, and reactive category totals.
- Two contrast tests passed across all nine themes (126 measured token/background pairs).
- `LiveChannelPaginationTest` passed on the TV emulator: availability changes from false to true without replacing the list, the latest callback runs, all 525 rows are reachable, D-pad Down crosses 200 -> 201 and 400 -> 401, and requests stop after the final page.
- `LiveChannelBrowseJourneyTest` passed against a temporary local database provider: the actual screen shows 525, then eight after category switching, in classic_blue, light and amoled_black. It restores the original provider, live source and preferences and deletes its fixture.
- New `live_channel_count` exists once with a matching `%1$d` placeholder and a real translation in all 38 advertised locales. Arabic, Persian and Hebrew captures show 525 and mirrored category/channel columns without clipping the count. The synthetic provider/category/channel names are test data.
- App and test APK assembly, feature boundary verification, and the final Live feature compilation passed. `git diff --check` passed for the changed source/resources.

## Captures

84 PNGs with matching UI hierarchy XML files are stored in `build/reports/live-channel-review/live-browse-review/`:

- Each required theme: one default capture, 25 focus captures, one eight-channel category capture (27 per theme).
- Three RTL default captures: `rtl_ar_default`, `rtl_fa_default`, `rtl_iw_default`, each waiting for a total of 525.
- Focus captures include both search shells, Quick filters, each visible category, visible channel rows and visible app navigation. The input editing children are inactive until activation; direct accessibility focus on those internal EditText nodes is unavailable. Search-shell focus is captured for both inputs.

Representative captures:

- [Classic blue default](../build/reports/live-channel-review/live-browse-review/classic_blue_default.png)
- [Light channel-search focus](../build/reports/live-channel-review/live-browse-review/light_focus_18.png)
- [AMOLED default](../build/reports/live-channel-review/live-browse-review/amoled_black_default.png)
- [Persian count and mirroring](../build/reports/live-channel-review/live-browse-review/rtl_fa_default.png)

Untested: the reporting users' real providers, portrait screenshots, PRO/COMFORTABLE screenshots, keyboard editing screenshots, and optional controls absent from this fixture (source switcher, split launcher, hidden-item actions and expanded quick-filter actions). This is list/count validation; no playback health claim is made.

## Computed contrast

WCAG relative luminance: `(max(L1,L2)+0.05)/(min(L1,L2)+0.05)`. Values below are ratios to 1. Count text is static across resting, focused, pressed and selected states. `TextTertiary` is the dense header's `OnSurfaceDim` alias; `TextSecondary` is the metadata strip. The measured surfaces include the underlying canvas and both endpoints of the browse panel gradient. Required text threshold: 4.5:1. Required focus threshold: 3:1.

### TextSecondary count text

| Theme | Canvas | CanvasElevated | Surface | SurfaceElevated |
| --- | ---: | ---: | ---: | ---: |
| classic_blue | 11.788 | 11.387 | 10.843 | 9.635 |
| m3_purple | 11.628 | 11.352 | 10.939 | 10.066 |
| light | 7.489 | 7.489 | 8.046 | 7.554 |
| forest | 12.337 | 11.766 | 11.003 | 9.147 |
| sunset | 12.093 | 11.593 | 10.916 | 9.460 |
| ocean | 12.499 | 11.793 | 10.955 | 9.086 |
| rose | 12.040 | 11.529 | 10.802 | 9.269 |
| amoled_black | 11.845 | 11.773 | 11.496 | 10.962 |
| vintage | 7.471 | 7.471 | 8.506 | 7.612 |

### TextTertiary count text

| Theme | Canvas | CanvasElevated | Surface | SurfaceElevated |
| --- | ---: | ---: | ---: | ---: |
| classic_blue | 7.781 | 7.516 | 7.157 | 6.359 |
| m3_purple | 7.657 | 7.476 | 7.203 | 6.628 |
| light | 6.839 | 6.839 | 7.349 | 6.899 |
| forest | 8.262 | 7.880 | 7.369 | 6.126 |
| sunset | 8.210 | 7.871 | 7.411 | 6.422 |
| ocean | 8.576 | 8.092 | 7.517 | 6.234 |
| rose | 8.265 | 7.914 | 7.415 | 6.362 |
| amoled_black | 8.179 | 8.129 | 7.938 | 7.569 |
| vintage | 6.989 | 6.989 | 7.957 | 7.121 |

### Secondary Cancel action

ControlContainer is the resting/pressed fill; SurfaceAccent is the focused fill. The focus ring is measured against the fill and surrounding panel surfaces.

| Theme | TextPrimary/ControlContainer | TextPrimary/SurfaceAccent | Focus/ControlContainer | Focus/SurfaceAccent | Focus/SurfaceElevated | Focus/Surface |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| classic_blue | 10.791 | 11.237 | 10.866 | 11.316 | 15.594 | 17.550 |
| m3_purple | 11.386 | 13.284 | 10.948 | 12.773 | 15.999 | 17.387 |
| light | 11.589 | 12.431 | 13.132 | 14.085 | 17.424 | 18.560 |
| forest | 10.027 | 7.860 | 10.307 | 8.079 | 14.329 | 17.236 |
| sunset | 10.380 | 9.986 | 10.015 | 9.635 | 14.335 | 16.541 |
| ocean | 9.934 | 7.889 | 10.092 | 8.014 | 14.002 | 16.882 |
| rose | 10.260 | 9.536 | 9.868 | 9.172 | 14.058 | 16.383 |
| amoled_black | 12.228 | 14.202 | 13.198 | 15.328 | 19.436 | 20.381 |
| vintage | 9.227 | 8.256 | 11.343 | 10.150 | 15.476 | 17.295 |

## Focus inventory

```text
classic_blue focus_0 Home | Home
classic_blue focus_1 Live TV | Live TV
classic_blue focus_2 Sports | Sports
classic_blue focus_3 Movies | Movies
classic_blue focus_4 Series | Series
classic_blue focus_5 Downloads | Downloads
classic_blue focus_6 Guide | Guide
classic_blue focus_7 Search | Search
classic_blue focus_8 Extensions | Extensions
classic_blue focus_9 Close app
classic_blue focus_10 Search categories...
classic_blue UNTESTED focus_11
classic_blue focus_12 Quick filters | Show | Showing all categories
classic_blue focus_13 Recent | 0
classic_blue focus_14 All Channels | 533
classic_blue focus_15 Large Review | 525
classic_blue focus_16 Small Review | 8
classic_blue focus_17 Favorites | 0
classic_blue focus_18 Search channels...
classic_blue UNTESTED focus_19
classic_blue focus_20 Channel 1, Review Channel 001 | RC | 01  Review Channel 001 | No schedule information
classic_blue focus_21 Channel 2, Review Channel 002 | RC | 02  Review Channel 002 | No schedule information
classic_blue focus_22 Channel 3, Review Channel 003 | RC | 03  Review Channel 003 | No schedule information
classic_blue focus_23 Channel 4, Review Channel 004 | RC | 04  Review Channel 004 | No schedule information
classic_blue focus_24 Channel 5, Review Channel 005 | RC | 05  Review Channel 005 | No schedule information
classic_blue focus_25 Channel 6, Review Channel 006 | RC | 06  Review Channel 006 | No schedule information
classic_blue focus_26 Channel 7, Review Channel 007 | RC | 07  Review Channel 007
light focus_0 Home | Home
light focus_1 Live TV | Live TV
light focus_2 Sports | Sports
light focus_3 Movies | Movies
light focus_4 Series | Series
light focus_5 Downloads | Downloads
light focus_6 Guide | Guide
light focus_7 Search | Search
light focus_8 Extensions | Extensions
light focus_9 Close app
light focus_10 Search categories...
light UNTESTED focus_11
light focus_12 Quick filters | Show | Showing all categories
light focus_13 Recent | 0
light focus_14 All Channels | 533
light focus_15 Large Review | 525
light focus_16 Small Review | 8
light focus_17 Favorites | 0
light focus_18 Search channels...
light UNTESTED focus_19
light focus_20 Channel 1, Review Channel 001 | RC | 01  Review Channel 001 | No schedule information
light focus_21 Channel 2, Review Channel 002 | RC | 02  Review Channel 002 | No schedule information
light focus_22 Channel 3, Review Channel 003 | RC | 03  Review Channel 003 | No schedule information
light focus_23 Channel 4, Review Channel 004 | RC | 04  Review Channel 004 | No schedule information
light focus_24 Channel 5, Review Channel 005 | RC | 05  Review Channel 005 | No schedule information
light focus_25 Channel 6, Review Channel 006 | RC | 06  Review Channel 006 | No schedule information
light focus_26 Channel 7, Review Channel 007 | RC | 07  Review Channel 007
amoled_black focus_0 Home | Home
amoled_black focus_1 Live TV | Live TV
amoled_black focus_2 Sports | Sports
amoled_black focus_3 Movies | Movies
amoled_black focus_4 Series | Series
amoled_black focus_5 Downloads | Downloads
amoled_black focus_6 Guide | Guide
amoled_black focus_7 Search | Search
amoled_black focus_8 Extensions | Extensions
amoled_black focus_9 Close app
amoled_black focus_10 Search categories...
amoled_black UNTESTED focus_11
amoled_black focus_12 Quick filters | Show | Showing all categories
amoled_black focus_13 Recent | 0
amoled_black focus_14 All Channels | 533
amoled_black focus_15 Large Review | 525
amoled_black focus_16 Small Review | 8
amoled_black focus_17 Favorites | 0
amoled_black focus_18 Search channels...
amoled_black UNTESTED focus_19
amoled_black focus_20 Channel 1, Review Channel 001 | RC | 01  Review Channel 001 | No schedule information
amoled_black focus_21 Channel 2, Review Channel 002 | RC | 02  Review Channel 002 | No schedule information
amoled_black focus_22 Channel 3, Review Channel 003 | RC | 03  Review Channel 003 | No schedule information
amoled_black focus_23 Channel 4, Review Channel 004 | RC | 04  Review Channel 004 | No schedule information
amoled_black focus_24 Channel 5, Review Channel 005 | RC | 05  Review Channel 005 | No schedule information
amoled_black focus_25 Channel 6, Review Channel 006 | RC | 06  Review Channel 006 | No schedule information
amoled_black focus_26 Channel 7, Review Channel 007 | RC | 07  Review Channel 007
```
