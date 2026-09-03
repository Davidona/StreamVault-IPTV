# Dev Onboarding Seeding

Optional convenience for contributors: skip the manual onboarding form on
every fresh install (or after `pm clear`) by pre-configuring a provider
via `local.properties`.

## Quick start

```bash
cp local.properties.example local.properties
```

Open `local.properties` and uncomment **one** of the two sections.

### Option A — Your own Xtream account

```properties
xtream.dev.server=http://your-server.example.org:80
xtream.dev.username=your-username
xtream.dev.password=your-password
xtream.dev.name=My Dev Server
```

Your credentials live only in your machine's `local.properties`, which is
gitignored (line 1 of `.gitignore`).

### Option B — Free public M3U playlist

[iptv-org](https://iptv-org.github.io/) maintains a curated aggregation of
**publicly available, legal** IPTV channels. Pick a country playlist:

```properties
m3u.dev.url=https://iptv-org.github.io/iptv/countries/fr.m3u
m3u.dev.name=iptv-org France
```

Browse [`iptv-org/iptv`](https://github.com/iptv-org/iptv) for other
country/category playlists.

### Option C — Deterministic local Catalog fixture

For Catalog movie/series/detail journeys without a provider account, run the
checked-in fixture from the repository root:

```bash
python -m unittest tools.tests.test_catalog_xtream_fixture
python tools/catalog_xtream_fixture.py --port 8765
```

Then configure the debug build with the emulator host mapping:

```properties
xtream.dev.server=http://10.0.2.2:8765
xtream.dev.username=fixture
xtream.dev.password=fixture
xtream.dev.name=Catalog Fixture
```

The fixture serves one live channel, two movies, two series, and deterministic
detail/episode metadata. It is for local diagnostic and acceptance journeys;
stop the process and remove the four entries after testing. The script never
contacts an external provider.

For the repeatable TV semantic journey, with the fixture-configured debug APK
installed, run:

```powershell
python tools/catalog_connected_validation.py --adb E:\androidSdk\platform-tools\adb.exe --serial emulator-5554
```

The harness drives production D-pad/UIAutomator semantics and writes its XML
snapshots and `report.json` under the ignored `build/catalog-validation`
directory. It does not edit `local.properties` or claim Cast/download receiver
success.

## How it works

`WelcomeViewModel.maybeSeedDevProvider()` runs once at app start, before
the existing `getProviders()` observation:

1. If the device already has at least one provider — no-op.
2. If all three Xtream fields are non-blank — call
   `ValidateAndAddProvider.loginXtream(...)` and let the regular flow
   sync the catalog. Navigation falls through to the home screen.
3. Otherwise, if `m3u.dev.url` is non-blank — call
   `ValidateAndAddProvider.addM3u(...)` instead.
4. Otherwise — onboarding runs normally.

The values are read at build time by `app/build.gradle.kts` and exposed
as `BuildConfig.XTREAM_DEV_*` / `BuildConfig.M3U_DEV_*`.

## Release-build safety

`defaultConfig` declares the six fields as empty strings. The `debug`
build type overrides them with the contributor's `local.properties`
values. The `release` build type does NOT override, so release APKs
always see empty strings regardless of what's in your local environment.

If you need to verify, decompile a release APK and search for
`XTREAM_DEV_SERVER` / `M3U_DEV_URL` — they should be empty constants.

## Re-seeding from scratch

The seeding logic only runs when no provider exists. To force a re-seed:

```bash
adb shell pm clear com.streamvault.app
```

Then relaunch the app.
