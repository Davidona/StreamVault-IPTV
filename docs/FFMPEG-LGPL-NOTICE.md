# FFmpeg LGPL Notice

StreamVault distributes a bundled Media3 FFmpeg audio decoder artifact built for LGPL-compatible use.

Distribution rules for this repo:

- Do not enable GPL or nonfree FFmpeg components in the bundled artifact.
- Keep the build provenance recorded in `player/libs/media3-decoder-ffmpeg-1.11.0.properties`.
- Preserve rebuild instructions so recipients can replace or relink the shipped decoder artifact if required by the applicable LGPL obligations.

Operationally, this repo treats the FFmpeg AAR as a versioned bundled dependency rather than rebuilding it in CI.
