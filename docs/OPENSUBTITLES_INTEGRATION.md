# OpenSubtitles.com integration

The optional account, settings, search, download, and player-injection flows are implemented. Every OpenSubtitles API request uses the application API key. When the API consumer permits anonymous access, search and download can run without a user session; signing in adds the user's bearer token and account-specific allowance.

## User flow

1. During movie or series-episode playback, open **Subtitles → Search online subtitles**.
2. Choose a subtitle language from the OpenSubtitles language list (or **All languages** to search without a language filter), then pick a version from the results.
3. Search anonymously when allowed by the app's OpenSubtitles consumer settings, or optionally connect an OpenSubtitles.com account in **Settings → Playback → Subtitles**.
4. Choose a result to download and add it to the current player session. Anonymous downloads may be restricted by the consumer configuration or OpenSubtitles limits.

Online search is hidden for live TV and catch-up. Search uses the movie or series TMDb ID when available, title/year fallbacks for movies, and season/episode metadata for episodes. The language list comes from `GET /infos/languages` and is cached in memory; pinned subtitle languages (Settings → Playback → Subtitles → Pinned subtitle languages) are listed first, followed by the app language, then the remaining languages alphabetically. Pinning only changes ordering; it never hides or filters other languages.

## Session, quota, and downloads

- Username, password, and JWT are encrypted with the app's `CredentialCrypto`. Existing plaintext usernames are still readable for migration; new writes are encrypted.
- Session reads, sign-in/out, and one-time refresh after HTTP 401 share a mutex. Login requests are spaced to respect OpenSubtitles' one-request-per-second login limit. Concurrent requests reuse a token refreshed by another request. If the refreshed token also receives 401, the saved session and credentials are cleared.
- The account page shows the daily allowance from login until a current remaining count is known. Successful `/download` responses update the remaining count, which expires at `reset_time_utc`. The app does not hard-code an anonymous or account quota because OpenSubtitles applies limits by consumer and account.
- Subtitle files are cached by OpenSubtitles file ID. A valid cached file avoids another quota-consuming `/download` request.
- File responses are cancellable, written to a temporary file, and moved into the cache only after completion. The temporary download URL is excluded from OkHttp request logging.
- OpenSubtitles can enforce both account download allowances and request limits. The app displays the account's remaining download count when the API provides it; IP-based request limits remain controlled by OpenSubtitles.

## API key

Add this line to the root `local.properties` file (already gitignored), then rebuild a debug build:

```properties
opensubtitles.api.key=YOUR_OPENSUBTITLES_API_KEY
```

The key is injected into debug builds only; release variants currently have an empty key. The app can still compile without a key, but OpenSubtitles requests return setup guidance and the online subtitle feature cannot work. The key is required even when no user is signed in. It is packaged into the app binary and can be extracted from an APK, so this local setup prevents Git exposure but does not make the key secret from app users. Account passwords and bearer tokens remain encrypted locally.

## Validation

The implementation was checked with the data, settings, playback, and app Hilt compilation tasks. Focused unit tests cover temporary-link log redaction, complete/failed/cancelled file downloads, VOD subtitle-action availability, and player Back behavior.
