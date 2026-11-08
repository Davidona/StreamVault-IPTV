## UI / FE rules (mandatory)

### Tokens
1. Colors come from `AppColors`/`AppPalette`. No `Color(0x...)` outside `core/ui/design/`.
2. `Color.White`/`Color.Black` only for content over video or artwork, on a scrim (rule 9). Elsewhere they break the `light` theme.
3. No `Color.White.copy(alpha = ...)` for text or icons. Use `textPrimary`, `textSecondary`, `textTertiary`.
4. Text sizes come from `MaterialTheme.typography`. No raw `fontSize = N.sp` in feature modules. Missing size: add a token to `AppTypography.kt` in its own commit.
5. Spacing comes from `LocalAppSpacing`. No new magic `dp` for padding, gaps, or gutters.
6. A new color role must be added to `AppPalette` for all nine themes in the same change.

### Contrast (WCAG relative luminance ratio)
7. Text under 18sp regular (or 14sp bold): at least 4.5:1. Larger text: at least 3:1. Meaningful non-text UI (icons, borders, progress vs track, toggle state, focus ring): at least 3:1.
8. Measure against the real background in resting, focused, pressed, and selected states. Must hold in all nine themes: `classic_blue`, `m3_purple`, `light`, `forest`, `sunset`, `ocean`, `rose`, `amoled_black`, `vintage`.
9. Text over video or artwork needs a scrim of black at 55% alpha or more, limited to the text region. No new full-width translucent layer over video without a measured GPU frame time on the TCL TV.
10. Never convey state (live, error, selected, focused) by color alone. Add a label, icon, shape, or border.
11. Compute ratios, don't judge by eye. Contrast-sensitive components get a unit test like `BrowsePaletteContrastTest`, iterating every palette id.

### Focus and size
12. Every focusable element has a focus state that differs by shape (border of 2dp or more, scale, or filled container), at least 3:1 against its surroundings. Use `AppColors.Focus`.
13. D-pad order is defined, Back has a defined destination, no dead-end focus.
14. Minimum text: 12sp everywhere, 14sp for anything read on TV. Phone touch targets: 48dp x 48dp or more.

### Text robustness
15. Strings follow `AGENTS.md`. Layouts must fit the longest translation without clipping. Text that can overflow sets `maxLines` and `overflow`. Use `start`/`end`, never `left`/`right`, and verify mirroring for each RTL locale in `locales_config.xml`.

### Done means
16. Screenshots on the emulator of every changed screen in `classic_blue`, `light`, and `amoled_black`, default and focused. State what was captured; anything else is "untested".
17. Report computed ratios, with token names, for every new or changed text/surface pair.
18. Not done if any ratio is below its threshold in any theme, or rules 1 to 5 were broken.

## Localization requirement (mandatory)

- Every user-facing string is localized product content. This includes UI labels,
  buttons, dialogs, errors, notifications, accessibility labels, and text shown
  by Compose or XML layouts.
- When adding or changing user-facing text, define it in the owning Android
  module's default `res/values/strings.xml` and provide a real translation in
  that module for every locale listed in
  `app/src/main/res/xml/locales_config.xml`. Add missing locale resource files
  as needed; do not rely on Android falling back to English or use English as a
  placeholder translation.
- Keep resource names and formatting contracts aligned across locales, including
  `%s`/`%d` placeholders, positional arguments, plurals, and escaped characters.
- Do not put user-facing text directly in Kotlin, Compose, or layout XML. Read it
  from the localized resources for the module that owns the UI.
- Before finishing a change that adds or edits user-facing text, verify that each
  affected resource key exists and is translated in every advertised locale.

## Live TV playback validation

For live TV playback bugs, do not validate with a single screenshot, a short
visual check, build success, install success, or launch success. Use frequent
screenshots and log evidence from the emulator.

Use a 2-second screenshot cadence for live TV stuckness checks. Capture long
enough to pass the historical stuck window: at least 45 screenshots for roughly
90 seconds, and prefer 61 screenshots for roughly 2 minutes when validating a
fix that previously failed around the one-minute mark.

Example:

```bash
mkdir -p /private/tmp/streamvault_live_validation
for i in $(seq -w 0 60); do
  adb exec-out screencap -p > /private/tmp/streamvault_live_validation/freq_${i}.png
  stat -f "freq_${i} %z" /private/tmp/streamvault_live_validation/freq_${i}.png
  sleep 2
done
```

After capture, confirm frame progression with hashes:

```bash
shasum -a 256 /private/tmp/streamvault_live_validation/freq_*.png | awk '{print $1}' | sort | uniq | wc -l
```

Then confirm the player is still healthy:

```bash
adb shell dumpsys media_session | awk '/package=com.streamvault.app/{seen=1} seen && /metadata:/{print; getline; print; getline; print} seen && /state=PlaybackState/{print; exit}'
adb logcat -d -v time > /private/tmp/streamvault_live_validation.log
rg -n "fatal-error|live-recovery selected|live-recovery no-candidate|prepare resolvedStreamType=MPEG_TS_LIVE|source-malformed live-ts-fallback|Player stuck|state=ERROR" /private/tmp/streamvault_live_validation.log
rg -n "retry category=|first-frame-success|prepare resolvedStreamType=HLS|read-progress streamType=HLS" /private/tmp/streamvault_live_validation.log | tail -80
```

A passing validation needs:
- screenshots that keep changing through the full capture window
- media session still in `PLAYING` with `error=null`
- no fatal player error, no stuck-player timeout, and no unintended MPEG-TS
  fallback
- sanitized log evidence showing HLS prepare/read/first-frame or recovery
  behavior

Validate more than one live channel when the bug is reported as affecting live
TV generally. Record the channel names, screenshot count, interval, unique hash
count, media-session result, and log findings in the final report.
