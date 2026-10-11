# OpenSubtitles.com integration

Branch: `feature/opensubtitles-integration`

The account, settings, search, download, and player-injection flows are implemented. The application API key remains the existing placeholder in `OpenSubtitlesApi`; key provisioning/configuration is intentionally deferred.

## User flow

1. Open **Settings → Playback → Subtitles** and connect an OpenSubtitles.com account.
2. During movie or series-episode playback, open **Subtitles → Search online subtitles**.
3. Choose a result to download and add it to the current player session.

Online search is hidden for live TV and catch-up. Search uses the movie or series TMDb ID when available, title/year fallbacks for movies, season/episode metadata for episodes, and the preferred audio language (English when the preference is automatic).

## Session, quota, and downloads

- Username, password, and JWT are encrypted with the app's `CredentialCrypto`. Existing plaintext usernames are still readable for migration; new writes are encrypted.
- Session reads, sign-in/out, and one-time refresh after HTTP 401 share a mutex. Concurrent requests reuse a token refreshed by another request. If the refreshed token also receives 401, the saved session and credentials are cleared.
- The account page shows the daily allowance from login until a current remaining count is known. Successful `/download` responses update the remaining count, which expires at `reset_time_utc`.
- Subtitle files are cached by OpenSubtitles file ID. A valid cached file avoids another quota-consuming `/download` request.
- File responses are cancellable, written to a temporary file, and moved into the cache only after completion. The temporary download URL is excluded from OkHttp request logging.
- OpenSubtitles can enforce both account download allowances and request limits. The app displays the account's remaining download count when the API provides it; IP-based request limits remain controlled by OpenSubtitles.

## API key

`OpenSubtitlesApi` still contains `STREAMVAULT_OPENSUBTITLES_API_KEY`. This branch does not change the key or add ignore-file changes. The API key is an application identifier; account credentials and bearer tokens remain private and encrypted locally.

## Validation

The implementation was checked with the data, settings, playback, and app Hilt compilation tasks. Focused unit tests cover temporary-link log redaction, complete/failed/cancelled file downloads, VOD subtitle-action availability, and player Back behavior.
