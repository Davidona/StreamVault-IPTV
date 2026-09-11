# FFmpeg Integration

StreamVault bundles the Media3 FFmpeg audio decoder artifact for unsupported IPTV audio codecs.

Current product scope:

- Audio fallback only
- Media3 version: `1.11.0`
- Supported ABIs: `arm64-v8a`, `armeabi-v7a`
- Enabled decoders: `ac3`, `eac3`, `dca`, `mp2`, `mp3`, `truehd`
- License target: LGPL-compatible build only

Important product notes:

- Local FFmpeg fallback improves playback on this device only; Cast receivers may still use different codec support.
- Recordings keep their source codecs. Successful in-app playback does not guarantee the same file will play everywhere else.

Refresh procedure:

1. Rebuild the AndroidX FFmpeg decoder against Media3 `1.11.0`.
2. Replace `player/libs/media3-decoder-ffmpeg-1.11.0.aar`.
3. Update `player/libs/media3-decoder-ffmpeg-1.11.0.properties` if provenance changes.
4. Run `:player:verifyLocalFfmpegArtifact` and the player/app test builds.
