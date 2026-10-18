# FFmpeg LGPL Notice

StreamVault distributes a bundled Media3 FFmpeg audio decoder artifact built for LGPL-compatible use.

The current artifact provenance is recorded in `player/libs/media3-decoder-ffmpeg-1.11.0.properties`. It identifies AndroidX Media3 source commit `2bc207851df311340767e913931ca7b28cab1794`, FFmpeg source commit `ba69be84a1ceabfb39127831ad8da0fd7cb471f3`, the enabled decoders and supported ABIs. The corresponding license texts are packaged in `app/src/main/assets/licenses/`.

Corresponding source is available from the commit-pinned [AndroidX Media3 decoder module](https://github.com/androidx/media/tree/2bc207851df311340767e913931ca7b28cab1794/libraries/decoder_ffmpeg) and [FFmpeg source](https://github.com/FFmpeg/FFmpeg/tree/ba69be84a1ceabfb39127831ad8da0fd7cb471f3). The pinned [Media3 decoder README](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/decoder_ffmpeg/README.md) contains upstream build instructions. StreamVault's specific settings for this artifact are listed in the properties file and in `docs/FFMPEG.md`.

Distribution rules for this repo:

- Do not enable GPL or nonfree FFmpeg components in the bundled artifact.
- Keep the build provenance recorded in `player/libs/media3-decoder-ffmpeg-1.11.0.properties`.
- Preserve rebuild instructions so recipients can replace or relink the shipped decoder artifact if required by the applicable LGPL obligations.

Operationally, this repo treats the FFmpeg AAR as a versioned bundled dependency rather than rebuilding it in CI.
