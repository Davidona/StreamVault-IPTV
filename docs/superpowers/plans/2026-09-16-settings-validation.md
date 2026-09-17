# Settings redesign validation

> **Historical checks; design acceptance rejected.** The user reported unreadable search text, poor sizing/spacing, and focus jumping to Home when opening Settings pages. The test/build results below did not establish an acceptable complete experience. They must not be used to sign off the replacement design. See [the replacement plan](2026-09-16-settings-redesign-replacement.md).

## Result

The debug app builds and is installed on the Android TV emulator. All 42 settings unit tests and all 18 connected tests pass. The settings module boundary check also passes.

Command:

```powershell
.\gradlew.bat :feature:settings:testDebugUnitTest :feature:settings:connectedDebugAndroidTest :app:assembleDebug
```

## Structure and behavior

- Ten clear categories replace the Browsing catch-all with Live TV, Movies & Series, and App & Remote.
- Playback has seven focused pages. Live TV has six, Movies & Series three, and App & Remote three. Recording, backups, and support also have dedicated pages.
- Source compatibility moved from Privacy to Providers. Close app moved from the crowded top bar to About → App information.
- Comparison against the original renderers retained all 46 Playback and all 29 Browsing preference/action handlers. Stored preferences, backup schema, and playback engine code were not changed.
- Independent review found two navigation problems, both fixed: overview scroll/focus restoration on Back, and inconsistent compact-layout breakpoints.

## Emulator walkthrough

Device: Television_1080p, Android 16, 1920 × 1080.

- Opened Settings and navigated rail → Playback → Audio using D-pad.
- Opened Audio Output, dismissed it with Back, and verified focus returned to its row.
- Enabled A/V sync adjustment: the dependent default-offset row appeared and focus stayed on the switch. Restored the original disabled value afterward.
- Scrolled Playback to Advanced compatibility, entered it, then pressed Back. The overview retained its scroll position and focus returned to the same card.
- Inspected Live TV overview and Channel list & guide, plus Movies & Series overview. Verified readable rows and full-width navigation cards.
- Inspected app runtime errors and settings focus warnings for the running process; none were reported.

Screenshots:

- [Playback overview](../../images/Settings.png)
- [Audio detail](../../images/Settings-audio.png)
- [Live TV overview](../../images/Settings-live-tv.png)
- [Movies & Series overview](../../images/Settings-movies.png)

## Connected test coverage and limits

Connected tests cover existing D-pad and RTL navigation, dialogs, backup behavior, single-target switch semantics and keyboard activation, disabled actions, long labels at 300dp, and content visibility in a constrained 590dp layout.

This is settings validation, not a live-stream playback validation. No playback engine changes were made. A physical phone, full light-theme/large-font visual matrix, and every provider/backup workflow were not exercised manually. New navigation copy currently falls back to English outside the base locale; existing setting translations remain in place. Attempting portrait sizing on the TV emulator produced a constrained TV window, so that capture is not treated as phone validation. Emulator display overrides were restored.
