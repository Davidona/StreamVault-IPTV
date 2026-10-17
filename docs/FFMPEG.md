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

The current binary's pinned source revisions and build inputs are recorded in `player/libs/media3-decoder-ffmpeg-1.11.0.properties`. The source repositories are [AndroidX Media3 at the recorded commit](https://github.com/androidx/media/tree/2bc207851df311340767e913931ca7b28cab1794/libraries/decoder_ffmpeg) and [FFmpeg at the recorded commit](https://github.com/FFmpeg/FFmpeg/tree/ba69be84a1ceabfb39127831ad8da0fd7cb471f3). Follow the [pinned upstream decoder build instructions](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/decoder_ffmpeg/README.md) to build FFmpeg and assemble the decoder AAR.

For this artifact, build FFmpeg for `armeabi-v7a` and `arm64-v8a` with `ac3`, `eac3`, `dca`, `mp2`, `mp3`, and `truehd` enabled. The metadata records FFmpeg NDK `26.1.10909125` and AAR NDK `28.2.13676358`; use compatible NDK/CMake tooling as described by the pinned upstream instructions. The generated AAR is statically linked with the FFmpeg libraries, so preserve the build outputs and wrapper object/source needed to relink it with a modified FFmpeg library.

1. Replace `player/libs/media3-decoder-ffmpeg-1.11.0.aar`.
2. Update `player/libs/media3-decoder-ffmpeg-1.11.0.properties` with the new source revisions, configuration, toolchain and SHA-256.
3. Keep the corresponding source available for the release and update `docs/THIRD-PARTY-LICENSES.md` if the source or configuration changes.
4. Run `:player:verifyLocalFfmpegArtifact` and the player/app test builds.
