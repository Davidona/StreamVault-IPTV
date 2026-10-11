# Stremio addons — continuation handoff

Review update (2026-10-11): read `STREMIO_ADDONS_PLAN_REVIEW.md` before continuing.
The implementation plan records the correct 78→79 migration. Follow-up work fixed
the missing `StremioManifestResource` test import and rejects manifests marked
`configurationRequired`; the latter has repository test coverage. Fresh unit and
emulator smoke results are below. The review still tracks cross-addon routing,
playback identity/privacy, subtitles, parental-control, and torrent-contract work.

Copy this whole block into a new session working in the worktree.

---

Continue the StreamVault Stremio addon feature. Work only in:
`D:\antigravity-scratch\iptv-player\.worktrees\stremio-addons` (branch `feature/stremio-addons`, based on `develop` @ 540ec30c). Read `docs/STREMIO_ADDONS_IMPLEMENTATION_PLAN.md` first. P1 was committed after its verification; check the current branch history for the commit.

Important: this branch's Room DB version is 78 (not 81 — that number in the plan belongs to the `codex/stalker-sync-improvements` branch). The migration added here is 78→79.

## Done and verified

- P0 (protocol client): `data/src/main/java/com/streamvault/data/remote/stremio/` (`StremioAddonClient`, `StremioAddonUrl`, `StremioAddonDtos`, `StremioMapper`) + domain models `domain/src/main/java/com/streamvault/domain/model/stremio/`; 22 unit tests green.
- P1 data layer: `StremioAddonEntity`, `StremioAddonDao`, `FeatureMigrationsV78To79`, `StremioAddonRepository` (domain) + `StremioAddonRepositoryImpl`, `StremioCatalogSettingsCodec`, `StremioAddonClient.fetchManifestSnapshot/parseManifest`; DI wired in `DatabaseModule`/`RepositoryModule`; schema export `data/schemas/com.streamvault.data.local.StreamVaultDatabase/79.json`; `StremioAddonRepositoryImplTest` now has 10 passing tests including configuration-required rejection; `StreamVaultDatabaseMigrationTest` — 43 tests green on emulator-5554 (API 36), including the new `migrate78To79_...` test.
- P1 verification update: `:app:testDebugUnitTest` passed 163 tests; `:feature:stremio:testDebugUnitTest` passed 6; focused data tests passed 34 (`StremioAddonClientTest` 13, `StremioAddonUrlTest` 9, `StremioAddonRepositoryImplTest` 10, migration registry 2). `:app:assembleDebug` succeeded. A broader data run hit unrelated download mock-server and Google Drive backup test failures; the focused Stremio rerun passed.
- Emulator smoke: installed `com.streamvault.app.debug` on `Television_1080p` (API 36, temporary read-only session), opened Addons, and installed `https://v3-cinemeta.strem.io/manifest.json`. Screenshot: `addons.png`. `Television_4K_2` already has a version-81 database, so this version-79 branch crashes there on the unsupported 81→79 downgrade; its data was left untouched.
- P1 nav/UI: `AppDestination.Addons`; `AppTopLevelDestination.ADDONS`; route codec/adapters/startup resolver/shell nav (`Icons.Default.Extension`, `R.string.nav_addons`); new module `feature:stremio` (build.gradle.kts, `StremioRoutePatterns.ADDONS="addons"`, `registerStremioGraph`, `StremioScaffoldContent`, `AddonsViewModel`, `AddonsScreen`, `AddonsViewModelTest`); wired in `AppNavHost` via `appStremioScaffold`; settings exhaustive `when`s updated; `nav_addons` added to app/settings strings and 25 app locale `strings_missing.xml` files. Unit and emulator smoke validation are complete.

## Immediate next steps

1. P1 unit verification, emulator install smoke, and the reviewed P1 commit are
   complete; see the results above.
2. Design cross-addon resource resolution and a compatible stable playback
   identity before beginning P2/P3, following `STREMIO_ADDONS_PLAN_REVIEW.md`.

## Environment gotchas

- Windows PowerShell; use `.\gradlew.bat`. Long builds: run detached `Start-Process cmd.exe -ArgumentList "/c",".\gradlew.bat ... > log 2>&1" -PassThru -NoNewWindow` and poll the log — don't block in foreground >2 min.
- Worktree has its own `local.properties` (sdk.dir=C:\Users\David\AppData\Local\Android\Sdk).
- Emulator `emulator-5554` (API 36) is connected; instrumented: `:data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.streamvault.data.local.StreamVaultDatabaseMigrationTest`.
- `Television_4K_2` emulator may hold database version 81 from another branch; use a clean/read-only emulator profile for this 78→79 branch smoke test rather than clearing shared app data.
- Data module uses OkHttp + kotlinx.serialization DTOs; domain has no serialization — keep that split.
- Cosmetic compiler warning: `@StringRes` on data-class property (KT-73255) in `AddonsViewModel` — can switch to `@param:StringRes`.
- Do not reuse provider URL policies for addon streams (relevant in P4).

## Remaining phases (details in the plan doc)

P2 catalog browsing UI, P3 meta + stream picker, P4 playback integration (+ magnet cast guard), P5 subtitles, P6 torrent companion plugin, P7 polish. Do not start until P1 is verified and committed.
