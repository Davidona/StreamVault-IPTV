# Third-party licenses and source

StreamVault includes third-party software. The licenses below apply to those components; they are separate from the StreamVault project license. The corresponding license texts are also packaged under `app/src/main/assets/licenses/`.

## Components

| Component | Version / source revision | License | Packaged notice |
| --- | --- | --- | --- |
| jcifs-ng (SMB recording) | 2.1.10 | GNU LGPL 2.1 or later | [`jcifs-ng-LGPL-2.1.txt`](../app/src/main/assets/licenses/jcifs-ng-LGPL-2.1.txt), [`GPL-2.0.txt`](../app/src/main/assets/licenses/GPL-2.0.txt) |
| Bouncy Castle Provider (`bcprov-jdk18on`, jcifs-ng dependency) | 1.76 | Bouncy Castle License | [`BouncyCastle-LICENSE.html`](../app/src/main/assets/licenses/BouncyCastle-LICENSE.html) |
| SLF4J API (jcifs-ng dependency) | 1.7.36 | MIT | [`SLF4J-MIT.txt`](../app/src/main/assets/licenses/SLF4J-MIT.txt) |
| AndroidX Media3 libraries, including the FFmpeg decoder wrapper | Media3 1.11.0; source commit `2bc207851df311340767e913931ca7b28cab1794`; Copyright (C) 2016 The Android Open Source Project | Apache License 2.0 | [`Apache-2.0.txt`](../app/src/main/assets/licenses/Apache-2.0.txt) |
| Other packaged app libraries (non-Media3 AndroidX, Kotlin/Kotlinx, Dagger/Hilt, OkHttp/Retrofit, Gson, Coil, ZXing and core-library desugaring) | Versions in the app's Gradle catalog/module declarations | Apache License 2.0 | [`Apache-2.0.txt`](../app/src/main/assets/licenses/Apache-2.0.txt) |
| FFmpeg decoder libraries in the Media3 AAR | FFmpeg source commit `ba69be84a1ceabfb39127831ad8da0fd7cb471f3` (release/6.0) | GNU LGPL 2.1 or later, subject to the enabled configuration | [`FFmpeg-LGPL-2.1.txt`](../app/src/main/assets/licenses/FFmpeg-LGPL-2.1.txt), [`GPL-2.0.txt`](../app/src/main/assets/licenses/GPL-2.0.txt) |
| libtorrent4j | 2.0.6-26 | MIT | [`libtorrent4j-MIT.txt`](../app/src/main/assets/licenses/libtorrent4j-MIT.txt) |
| libtorrent native library | Source commit `ed88357b4cdf7ae9d4b35aa77e654afbe2b7b11c` pinned by libtorrent4j 2.0.6-26 | BSD-3-Clause | [`libtorrent-BSD-3-Clause.txt`](../app/src/main/assets/licenses/libtorrent-BSD-3-Clause.txt) |
| Apple `route.h` notice included in the libtorrent notice bundle | Upstream libtorrent source notice | Apple Public Source License 2.0 | [`APSL-2.0.txt`](../app/src/main/assets/licenses/APSL-2.0.txt) |

The libtorrent notice file also preserves notices for its other bundled source components, including Boost-licensed and public-domain code.

## Google SDK developer terms

The app also declares Google Play services Auth and Cast SDKs. These are separate from the open-source licenses above. Google Sign-In/API use is subject to the [Google APIs Terms of Service](https://developers.google.com/terms/) and the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy). Cast use is also subject to the [Google Cast SDK Additional Developer Terms of Service](https://developers.google.com/cast/docs/terms/).

## Corresponding source

The source for the application is published at [github.com/Davidona/StreamVault-IPTV](https://github.com/Davidona/StreamVault-IPTV). For a particular app release, use the matching source revision and the dependency revisions recorded in that release's `player/libs/media3-decoder-ffmpeg-1.11.0.properties` file.

* jcifs-ng 2.1.10 source: [tag `jcifs-ng-2.1.10`](https://github.com/AgNO3/jcifs-ng/tree/jcifs-ng-2.1.10).
* Bouncy Castle 1.76 source: [tag `r1rv76`](https://github.com/bcgit/bc-java/tree/r1rv76).
* SLF4J 1.7.36 source: [tag `v_1.7.36`](https://github.com/qos-ch/slf4j/tree/v_1.7.36).
* libtorrent4j 2.0.6-26 source: [tag `v2.0.6-26`](https://github.com/aldenml/libtorrent4j/tree/v2.0.6-26). That tag pins its native libtorrent source to commit [`ed88357b4cdf7ae9d4b35aa77e654afbe2b7b11c`](https://github.com/arvidn/libtorrent/tree/ed88357b4cdf7ae9d4b35aa77e654afbe2b7b11c).
* AndroidX Media3 decoder wrapper source: [pinned decoder module](https://github.com/androidx/media/tree/2bc207851df311340767e913931ca7b28cab1794/libraries/decoder_ffmpeg). Its [pinned README](https://github.com/androidx/media/blob/2bc207851df311340767e913931ca7b28cab1794/libraries/decoder_ffmpeg/README.md) documents the FFmpeg build and AAR assembly steps.
* FFmpeg source: [pinned FFmpeg revision](https://github.com/FFmpeg/FFmpeg/tree/ba69be84a1ceabfb39127831ad8da0fd7cb471f3).

For the bundled FFmpeg AAR, the project records the enabled decoders, supported ABIs, source commits, NDK versions and AAR hash in [`media3-decoder-ffmpeg-1.11.0.properties`](../player/libs/media3-decoder-ffmpeg-1.11.0.properties). The project-specific rebuild notes are in [`FFMPEG.md`](FFMPEG.md) and [`FFMPEG-LGPL-NOTICE.md`](FFMPEG-LGPL-NOTICE.md). If a release uses modified third-party source or different build options, publish the corresponding modified source and update those records for that release.

## License texts

* [Apache License 2.0](../app/src/main/assets/licenses/Apache-2.0.txt)
* [GNU LGPL 2.1 for jcifs-ng](../app/src/main/assets/licenses/jcifs-ng-LGPL-2.1.txt)
* [GNU LGPL 2.1 for FFmpeg](../app/src/main/assets/licenses/FFmpeg-LGPL-2.1.txt)
* [GNU GPL 2.0 text referenced by LGPL 2.1](../app/src/main/assets/licenses/GPL-2.0.txt)
* [Bouncy Castle License](../app/src/main/assets/licenses/BouncyCastle-LICENSE.html)
* [MIT License for SLF4J](../app/src/main/assets/licenses/SLF4J-MIT.txt)
* [MIT License for libtorrent4j](../app/src/main/assets/licenses/libtorrent4j-MIT.txt)
* [BSD-3-Clause and component notices for libtorrent](../app/src/main/assets/licenses/libtorrent-BSD-3-Clause.txt)
* [Apple Public Source License 2.0](../app/src/main/assets/licenses/APSL-2.0.txt)
