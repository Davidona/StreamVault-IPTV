# Media3 FFmpeg Extension Artifact

This directory holds the bundled Media3 FFmpeg decoder artifact used by StreamVault builds.

Bundled artifact:

- `media3-decoder-ffmpeg-1.11.0.aar`

Build provenance for the checked-in artifact:

- Upstream source: `androidx/media` tag `1.11.0`
- Upstream commit used here: `2bc207851df311340767e913931ca7b28cab1794`
- Media3 version match: `1.11.0`
- FFmpeg recommendation from upstream README: `release/6.0`
- FFmpeg commit used here: `ba69be84a1ceabfb39127831ad8da0fd7cb471f3`
- Android NDK used for FFmpeg: `26.1.10909125` (upstream-tested r26b toolchain)
- Android NDK used to link the AAR JNI wrapper: `28.2.13676358` (Media3 1.11.0 build default)
- AAR SHA-256: `2D617618E39C58DF6FDB2B8B7DC493029CD7BFADF2325B0DA2A8D0476615F31A`
- Supported ABIs: `arm64-v8a`, `armeabi-v7a`
- Enabled decoders: `ac3`, `eac3`, `dca`, `mp2`, `mp3`, `truehd`
- License target: LGPL-compatible build only; do not enable GPL/nonfree codecs

Refresh procedure:

1. Rebuild the AndroidX FFmpeg decoder against the exact Media3 version used by this repo.
2. Verify the packaged AAR contains the Java decoder classes plus native `ffmpegJNI` libraries for the supported ABIs.
3. Replace the local artifact in this directory, update the adjacent `.properties` manifest if needed, and rerun `:player:verifyLocalFfmpegArtifact` plus the player unit tests and an app build.
