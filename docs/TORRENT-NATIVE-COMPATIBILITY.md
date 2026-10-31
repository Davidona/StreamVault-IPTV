# Torrent native compatibility

This is shared Android compatibility work for both direct APK and Google Play
distribution. It does not depend on an app store or change the app's minimum
SDK, content sources, or torrent feature availability.

## Dependency selection

All Java and native libtorrent4j artifacts use **2.1.0-38** from Maven Central.
The app's minimum remains Android API **25**. The selected native artifacts
declare Android API **24** and NDK **r28c** in their Android ELF identity notes.
The ARM64 load segments are aligned to **0x4000 (16 KiB)**; the old 2.0.6-26
artifact has **0x1000 (4 KiB)** alignment.

Upstream 2.1.0-39 raises its minimum to API 28, so it is not a compatible
replacement while retaining the current device support. The selected version
also updates native dependencies; packaged license texts and pinned source
references are in `app/src/main/assets/licenses/libtorrent4j-native-NOTICES.txt`.

Sources: [upstream changelog](https://github.com/aldenml/libtorrent4j/blob/v2.1.0-38/changelog.txt),
[Android native build configuration](https://github.com/aldenml/libtorrent4j/blob/v2.1.0-38/swig/android-build/Dockerfile),
[Android 16 KiB guidance](https://developer.android.com/guide/practices/page-sizes).

## Release checks

The GitHub beta and release workflows check every packaged 64-bit ELF library
before publishing an APK:

```sh
python3 tools/check-native-page-alignment.py path/to/app.apk
```

The same command accepts an AAB for a Play release. It validates ELF load
alignment and offset/address congruence, not just zip entry alignment. Check
APK packaging separately with the SDK's `zipalign -c -P 16 -v 4 app.apk`.
Binary checks do not replace runtime testing.

`TorrentNativeCompatibilityTest` exercises actual JNI loading, native torrent
creation/hashing, file checking, and an asynchronous native piece read using
generated local data. Peer discovery, UPnP, and NAT-PMP are disabled for the
test; it does not depend on public torrents or copyrighted test media.

Build an x86_64-capable debug app and test APK with `-PcompatAbi=x86_64`, then
install on a dedicated emulator and run:

```sh
adb -s DEVICE shell getconf PAGE_SIZE
adb -s DEVICE shell am instrument -w \
  -e class com.streamvault.torrent.TorrentNativeCompatibilityTest \
  -e expectedPageSize 16384 \
  com.streamvault.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Repeat on API 25 with `expectedPageSize 4096` to validate the oldest supported
Android version as well as a 16 KiB emulator. For a changed application ID,
use that variant's test application ID in the command; Kotlin class names
remain unchanged.

## Validation

| Check | Result |
| --- | --- |
| Old 2.0.6-26 ARM64 binary | Rejected by the alignment gate at 0x1000 |
| 2.1.0-38 ARM64 and x86_64 binaries | All load segments aligned to 0x4000 |
| All four upstream ABI binaries | Android identity notes declare API 24 |
| Packaged debug APK | All seven packaged 64-bit libraries pass ELF checks; `zipalign -c -P 16 -v 4` passes |
| API 35 x86_64, actual page size 16384 | Native hashing, file checking and final-piece read pass |
| API 25 x86_64, actual page size 4096 | Same native test passes; existing minimum Android support retained |
| Torrent parser, byte-range and local range-server unit tests | 11 passed |

The native test logs the actual OS page size and loaded libtorrent4j version.
It validates native file operations, not a complete remote torrent playback
session. No public torrent or provider credentials were used in the test.
