# OpenSubtitles.com Integration Plan

Branch: `feature/opensubtitles-integration`
Status: Plan (no code written yet)

## 1. Goal

Add an official, open-source-friendly subtitles provider backed by the
OpenSubtitles.com REST API v1:

- The developer registers one application API key for StreamVault and ships it
  in the app (it is expected to be visible in public source).
- Users sign in with their own free OpenSubtitles.com account from a new
  Settings section.
- The app uses that user's session token for search and download, so the daily
  download quota is per-user (typically 20/day for a free account) instead of a
  shared global pool.
- Downloaded `.srt`/`.vtt` files are injected into the existing Media3 player.

## 2. What already exists (foundation)

The repository already contains a partial, dormant external-subtitle path. Do
not rebuild it — wire it up.

| Layer | Existing symbol | Location |
|-------|-----------------|----------|
| Model | `ExternalSubtitle`, `ExternalSubtitleSource.OPENSUBTITLES` | `domain/.../model/ExternalSubtitle.kt` |
| Contract | `ExternalSubtitleRepository` (`search`, `downloadToCache`) | `domain/.../repository/ExternalSubtitleRepository.kt` |
| Use case | `SearchExternalSubtitles` | `domain/.../usecase/SearchExternalSubtitles.kt` |
| Player | `PlayerEngine.addExternalSubtitle(uri, language)` | `player/.../PlayerEngine.kt:104` |
| Player impl | `Media3PlayerEngine.addExternalSubtitle(...)` | `player/.../Media3PlayerEngine.kt:803` |
| Player helpers | `buildExternalSubtitleConfiguration`, `buildExternalSubtitleMediaSource`, `withExternalSubtitleEnabled` | `player/.../ExternalSubtitlePlayback.kt` |
| Settings page | `SettingsPage.SUBTITLES` (under `PLAYBACK`) | `feature/settings/.../SettingsDestination.kt:37` |
| Subtitle picker | `PlayerTrackSelectionDialog` | `feature/playback/.../overlay/PlayerSystemOverlays.kt:254` |

There is **no** data-layer implementation of `ExternalSubtitleRepository` and
**no** DI binding for it. That is the core of this work.

## 3. API reference (verified against OpenSubtitles docs)

Base URL: `https://api.opensubtitles.com/api/v1` (default)
VIP mirror: `https://vip-api.opensubtitles.com/api/v1`

Required headers on every request:

- `Api-Key: <app API key>` (application key)
- `Authorization: Bearer <user token>` (user session; required for download,
  recommended everywhere)
- `User-Agent: StreamVault v<version>` (OpenSubtitles asks consumers not to use
  a generic default)
- `Content-Type: application/json` (for POST bodies)

### 3.1 Login

`POST /api/v1/login`

```json
{ "username": "<user>", "password": "<pass>" }
```

Response `200`:

```json
{
  "token": "<JWT>",
  "base_url": "api.opensubtitles.com",
  "user": { "allowed_downloads": 20, "level": "Sub leecher", "user_id": 66, "vip": false }
}
```

Rules:

- Rate limit is intentionally tight (1/s, 10/min, 30/hour). Treat login as
  expensive: on `401`, stop retrying the same credentials until the user edits
  them.
- Honor the returned `base_url` host for subsequent requests (VIP users get a
  different host).

### 3.2 Logout

`DELETE /api/v1/logout` (Bearer token required). Response `200`.

### 3.3 Search

`GET /api/v1/subtitles`

Query parameters (all optional, at least one identity is required):

- `query` — free-text title (least precise; prefer IDs)
- `imdb_id` — IMDb id **without** the `tt` prefix (e.g. `1375666`)
- `tmdb_id` — TMDB movie/series id
- `parent_imdb_id` / `parent_tmdb_id` — series id for episode lookups
- `season_number`, `episode_number` — episode targeting
- `languages` — ISO 639-1 code(s), e.g. `en` (see `/api/v1/infos/languages`)
- `order_by` — e.g. `download_count`, `language`, `upload_date`
- Defaults: `ai_translated=include`, `machine_translated=exclude`

Response `200`:

```json
{
  "total_pages": 1,
  "total_count": 13,
  "page": 1,
  "data": [
    {
      "id": "1000001",
      "type": "subtitle",
      "attributes": {
        "subtitle_id": "1000001",
        "language": "en",
        "language_name": "English",
        "release": "Movie.Name.2020.1080p.WEB-DL",
        "download_count": 1234,
        "feature_details": {
          "title": "Movie Name",
          "year": 2020,
          "imdb_id": 1375666,
          "tmdb_id": 27205,
          "parent_imdb_id": null,
          "parent_tmdb_id": null,
          "season_number": 0,
          "episode_number": 0
        },
        "files": [
          { "file_id": 123456, "file_name": "movie.srt", "cd_number": 1 }
        ]
      }
    }
  ]
}
```

### 3.4 Download link

`POST /api/v1/download` (requires **both** `Api-Key` and `Authorization`)

```json
{ "file_id": 123456 }
```

Optional: `sub_format` (`srt`, `webvtt`, ...), `file_name`, `timeshift`.

Response `200`:

```json
{
  "link": "https://www.opensubtitles.com/download/<token>/subfile/movie.srt",
  "file_name": "movie.srt",
  "requests": 3,
  "remaining": 17,
  "message": "Your quota will be renewed in 07 hours and 30 minutes ...",
  "reset_time": "07 hours and 30 minutes",
  "reset_time_utc": "2022-04-08T13:03:16.000Z"
}
```

The `link` is a temporary URL to fetch the subtitle file itself (always UTF-8).
Track `remaining` and stop attempting downloads once it hits zero; there is no
dedicated "quota exceeded" error code.

## 4. Design

Follow existing codebase conventions (OkHttp + Gson, no Retrofit; Hilt; Room +
DataStore; Compose). Mirror `JellyfinProvider` for the HTTP client and
`MinistraApiV3Provider`/`StalkerPortalStateStore` for encrypted credential and
token storage.

### 4.1 API key

Add a single top-level constant in the data layer, e.g.
`OpenSubtitlesApi.API_KEY`. It is intentionally non-secret and shipped in source
(per the official open-source policy). The actual key value is obtained from
https://www.opensubtitles.com/consumers after registering StreamVault; keep it
as a named constant so it is trivial to rotate.

### 4.2 Session model

Introduce a session holder (data layer) that caches, in memory and in
encrypted DataStore:

- `token` (JWT, encrypted with `CredentialCrypto`)
- `baseUrl` (host, plaintext)
- `allowedDownloads` / `level` / `vip` (for the settings status line)
- `username` (encrypted) and a boolean `hasCredentials`

The repository obtains a valid token lazily: if a cached token exists use it; if
a request returns `401`, drop the token and re-login once using stored
credentials; if that also fails, surface a "sign in required" result.

## 5. Work breakdown

### Phase A — Data layer (core)

New files under `data/src/main/java/com/streamvault/data/remote/subtitle/`:

1. `OpenSubtitlesApi.kt` — OkHttp-based client (`@Singleton`, injects
   `OkHttpClient` + `Gson`). Methods:
   - `suspend fun login(username, password): Result<OpenSubtitlesSession>`
   - `suspend fun logout(token): Result<Unit>`
   - `suspend fun search(query): Result<List<OpenSubtitlesSubtitleDto>>`
   - `suspend fun downloadLink(fileId, token): Result<OpenSubtitlesDownloadDto>`
   - Builds `Api-Key` / `Authorization` / `User-Agent` headers; uses
     `useCancellableResponse` and `withContext(Dispatchers.IO)` like
     `JellyfinProvider`.
2. `OpenSubtitlesDtos.kt` — Gson DTOs for the payloads above (login, search
   envelope, `data[].attributes`, `files[]`, download, languages optional).
   Nullable scalar fields with defaults, matching the lenient style in
   `data/remote/dto/LenientJsonSerializers.kt`.
3. `OpenSubtitlesSessionStore.kt` — encrypted persistence of token/baseUrl/quota
   using `CredentialCrypto` + DataStore (see `StalkerPortalStateStore.kt`).
4. `OpenSubtitlesApiException.kt` — small error type mapping HTTP codes
   (`401 Unauthorized`, `429 Rate limited`, non-2xx, decode errors).

New file under `data/src/main/java/com/streamvault/data/repository/`:

5. `OpenSubtitlesRepositoryImpl.kt` — `@Singleton`, implements
   `ExternalSubtitleRepository`. Responsibilities:
   - Map `ContentType` + ids into a search request (see 5.1).
   - `search(...)` returns `Result<List<ExternalSubtitle>>`, populating
     `id`/`downloadId` from `files[0].file_id`, `language`, `releaseName`,
     `source = OPENSUBTITLES`.
   - `downloadToCache(subtitle)` calls `downloadLink`, fetches the file into a
     cache dir (e.g. `context.cacheDir/subtitles/<id>.srt`), returns the local
     `File`.
   - When there is no stored token and no stored credentials, return
     `Result.error("OpenSubtitles not signed in")` (or a dedicated result the
     UI can map to a sign-in prompt).

DI wiring:

6. `app/src/main/java/com/streamvault/app/di/RepositoryModule.kt` — add
   `@Binds abstract fun bindExternalSubtitleRepository(impl: OpenSubtitlesRepositoryImpl): ExternalSubtitleRepository`.
7. Confirm an `OkHttpClient`/`Gson` provider exists (used by `JellyfinProvider`);
   if not, add providers in the appropriate data DI module.

### 5.1 Search mapping

For `search(contentType, title, year, tmdbId, parentTmdbId, seasonNumber, episodeNumber, language)`:

- `MOVIE`: `tmdb_id=tmdbId` when present, plus `query="$title $year"`, `languages`.
- `SERIES`: `tmdb_id=tmdbId` (or `query=title`), `languages`.
- `SERIES_EPISODE`: `parent_tmdb_id=parentTmdbId`, `season_number`,
  `episode_number`; fall back to `query="$title S${season}E${episode}"`.
- Strip `tt` and leading zeros from any IMDb id (none is passed today, but keep
  a helper for future use). Prefer IDs over `query`; only send `query` as a
  fallback.
- Normalize `language` to an ISO 639-1 code (OpenSubtitles uses codes like
  `en`, `fr`, `zh-cn`). Wire the app's preferred subtitle/audio language or the
  currently selected subtitle language as the default.

### Phase B — Settings UI ("Subtitles Provider (OpenSubtitles)")

Extend the existing `SUBTITLES` page (`SettingsPlaybackSection.kt`, the
`if (page == null || page == SettingsPage.SUBTITLES)` block around line 371):

1. Add a "Subtitles provider" section header + status rows:
   - Signed-out: `ClickableSettingsRow` "Sign in to OpenSubtitles" opening a
     login dialog (username + password text fields).
   - Signed-in: status text ("Signed in as <username> · N downloads remaining")
     plus a "Sign out" action.
2. Add login/logout actions to the settings ViewModel. Persist credentials
   (password encrypted via `CredentialCrypto`) and the session via
   `OpenSubtitlesSessionStore`.
3. New dialog composable (follow `SettingsPreferenceDialogs.kt` /
   `SettingsProviderManagementDialogs.kt` patterns for TV text entry).
4. Add strings to `feature/settings/.../values/strings.xml` and a catalog/search
   entry in `SettingsSearchSurface.kt` (e.g. `playback.subtitles_provider`).
5. Expose sign-in state (Flow) from the session store to the settings UI.

The developer-facing detail (the app key is public, per-user quota, why a login
is needed) belongs in the section subtitle text.

### Phase C — Player subtitle search + injection

1. In `PlayerTrackSelectionDialog` (`PlayerSystemOverlays.kt`), add a
   "Search subtitles online" item at the bottom of the `TrackType.TEXT` list
   (mirror the existing `liveTranslationAvailable` hook at lines 362-369).
2. Add a new modal/route in `PlayerModalHosts.kt` + `PlayerModal` that hosts a
   subtitle search sheet:
   - Query OpenSubtitles via `SearchExternalSubtitles` using the currently
     playing item's metadata (title, year, tmdb id, season/episode) sourced from
     the playback context already held by `PlayerViewModel`/`PlaybackContextCoordinator`.
   - Show results (language + release name + download count).
   - On selection, call `downloadToCache`, then
     `playerEngine.addExternalSubtitle(Uri.fromFile(file), language)`.
   - Handle "not signed in": show a message directing the user to Settings.
3. New `SubtitleSearchViewModel` (or extend `PlayerViewModel`) in
   `feature/playback`, using `SearchExternalSubtitles` and the download use case.
   The selected file should be downloaded to cache then handed to the engine as
   a `file://` Uri — the engine's `addExternalSubtitle` already builds a
   `SubtitleConfiguration` and a merged media source.

### 5.2 Mime type handling

`ExternalSubtitlePlayback.kt` currently hard-codes `MimeTypes.APPLICATION_SUBRIP`
(and the player test asserts `.srt`). Prefer requesting `sub_format=srt` on
download so existing code works unchanged. If `.vtt` support is wanted later,
extend `buildExternalSubtitleConfiguration` to select
`APPLICATION_SUBRIP` vs `TEXT_VTT` by file extension and update
`ExternalSubtitlePlaybackTest`.

## 6. Domain additions (small)

Optionally extend the domain contract so the UI can drive sign-in and report
state cleanly:

- `ExternalSubtitleRepository` gains `suspend fun signIn(username, password)`,
  `suspend fun signOut()`, and `val sessionState: Flow<...>` — OR keep the
  session store purely in the data layer and expose a separate
  `OpenSubtitlesSessionManager` domain contract. Prefer the separate-manager
  approach so `ExternalSubtitleRepository` stays focused on search/download.

## 7. Security notes

- Password and JWT token: encrypted at rest via `CredentialCrypto`
  (AES-GCM, Android Keystore) — reuse existing helper, do not store plaintext.
- API key: plaintext constant by design (not a secret).
- Never log credentials or tokens; sanitize error messages before they reach
  the UI/logs.

## 8. Testing

- Unit tests for DTO parsing and search-query mapping (no network), following
  `player/src/test/.../ExternalSubtitlePlaybackTest.kt` style.
- Unit tests for `OpenSubtitlesRepositoryImpl` with a fake client (mock the
  OkHttp layer or a fake `OpenSubtitlesApi`) covering: signed-out error, token
  reuse, `401` re-login, download-to-cache path, quota-zero handling.
- Verify existing `ExternalSubtitlePlaybackTest` still passes (no changes to the
  player helpers expected in this phase).

## 9. Risks / open questions

- The actual API key must be requested from the OpenSubtitles.com "API
  consumers" page; a placeholder constant ships until then.
- `addExternalSubtitle` re-prepares the media source; confirm it is safe to call
  mid-playback for VOD vs live (live typically has no metadata to match, so
  subtitle search should be surfaced only for VOD/movie/series contexts).
- OpenSubtitles search precision depends on having TMDB/IMDb ids; providers vary
  in metadata quality. Fall back to `query` (title + year) when ids are absent.

## 10. Suggested commit sequence

1. Data layer: DTOs + API client + session store + repository impl + DI.
2. Domain: session-manager contract (if added).
3. Settings UI: provider section + login dialog + status.
4. Player: search sheet + injection wiring.
5. Tests + strings + search-surface catalog entries.
