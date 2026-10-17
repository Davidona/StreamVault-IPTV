# Sports UI refinement — 2026-10-17

Worktree: `D:/antigravity-scratch/iptv-player/.worktrees/sports-integration`  
Branch: `codex/sports-integration`  
Device: `emulator-5590`, configured guide retained.

## Scope

Presentation correction during Plan v2; no later Sports plan or remote sports-data provider was implemented. Existing navigation, EPG loading, classification, event grouping, channel preferences, manual search, and playback request contracts remain in place.

- Replaced the vertically scrolling eager column with a lazy list of league rows.
- Moved time-window controls alongside the title; reduced sport/league controls to 30 dp.
- Replaced the 270 dp minimum trophy hero with a 140 dp split fixture/broadcast panel.
- Ordered recognized league rows by event count, followed by generic guide categories. Featured a two-competitor fixture from the leading row when available.
- Added compact 244 x 140 dp event cards with competitor monograms, schedule, status and source-channel logo. Monograms are derived from guide names, not official team artwork.
- Made each event card one focus target that opens details. Direct playback remains available in the featured channel action; all broadcast choices and manual correction remain in details.
- Removed nested horizontal channel-button lists from event cards.
- Bounded the details dialog to 640 x 440 dp with fixed header/actions and an independently scrolling broadcast list.
- Added a localized EFL Championship label; no other UI copy changed.

## Validation

- `:app:assembleDebug`: passed; APK installed on emulator-5590.
- Sports JVM suite: 50 tests across seven suites, zero failures/errors.
- The configured EFL Championship filter selected Burnley vs Wolverhampton Wanderers correctly, even though the guide description referenced Premier League. Screenshot: [league-regression.png](../artifacts/sports-ui-review/league-regression.png).
- Resource audit: 29 referenced UI keys present and nonempty across all 38 advertised locales, including the EFL Championship label.
- Screenshot review caught and corrected a clipped first event row and details-header scrolling.
- Final overview screenshot confirms the entire 140 dp featured panel and first league row fit together at 1920 x 1080.
- D-pad Down entered league cards; horizontal movement scrolled the row while keeping the focused card visible. OK opened Liverpool vs Manchester City with both mapped channel choices.
- Opened Burnley details with 14 channel choices and moved seven choices down; the title, description, channel count and footer stayed fixed while only the channel list scrolled.
- No app fatal exception or ANR was found in the inspected recent logcat window.
- Device screenshots and remote-navigation evidence are under `artifacts/sports-ui-review/`.
- This is UI validation, not a new live-playback stability certification. Existing WSL playback failures in Sports-IMPLEMENTATION.md remain unresolved.

## Known data limitations

Scores, standings, official team artwork and video preview require separate planned work/data. The guide still surfaces weakly parsed entries such as `Upcoming vs MLB Network Strike Zone`; classifier changes were not included in this presentation pass.
