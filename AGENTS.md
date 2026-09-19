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
